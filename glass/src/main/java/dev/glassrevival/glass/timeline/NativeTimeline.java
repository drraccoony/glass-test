package dev.glassrevival.glass.timeline;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import com.google.glass.timeline.TimelineItemId;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;

/**
 * Puts phone notifications into Glass's own timeline, the cards to the right of the
 * clock, the way MyGlass used to.
 *
 * <p>Writing to the timeline needs {@link #PERMISSION}, which Glass grants only to apps
 * installed in /system/priv-app. When GlassLink is installed normally,
 * {@link #isAvailable} is false and GlassLink uses its own cards instead.
 *
 * <p>The card format and broadcast below come from XE24's GlassSync and GlassHome:
 * the TimelineProvider columns, the TimelineItem protobuf, and the NOTIFY_TIMELINE_ITEM
 * broadcast that the home screen's NotificationService listens for.
 */
public final class NativeTimeline {

    /** Called on a background thread when the user deletes one of our cards on Glass. */
    public interface Listener {
        void onCardDismissed(String key);
    }

    static final String TAG = "GlassLink";
    public static final String PERMISSION = "com.google.glass.sync.permission.TIMELINE_PROVIDER";
    private static final Uri TIMELINE = Uri.parse("content://com.google.glass.sync.timeline/timeline");
    /** Includes expired items, so tombstones are always visible to us. */
    private static final Uri TIMELINE_ALL = Uri.withAppendedPath(TIMELINE, "withExpired");
    private static final String ACTION_NOTIFY = "com.google.glass.action.NOTIFY_TIMELINE_ITEM";
    private static final String HOME_PACKAGE = "com.google.glass.home";
    /** Menu broadcast for "Reply", handled by {@link TimelineMenuReceiver}. */
    public static final String ACTION_REPLY = "dev.glassrevival.glass.action.TIMELINE_REPLY";
    /** Every card GlassLink writes has this source, so we only ever touch our own. */
    static final String SOURCE = "glasslink";
    private static final String ID_PREFIX = "glasslink-";

    // TimelineItem fields
    private static final int F_ID = 1, F_CREATION_TIME = 2, F_MODIFIED_TIME = 3, F_SOURCE = 4,
            F_CREATOR = 5, F_TEXT = 8, F_MENU_ITEM = 11, F_CLOUD_SYNC_PROTOCOL = 13,
            F_SOURCE_TYPE = 14, F_DISPLAY_TIME = 17, F_SPEAKABLE_TEXT = 19, F_TITLE = 23,
            F_SOURCE_ITEM_ID = 30, F_COMPANION_SYNC_PROTOCOL = 34, F_SPEAKABLE_TYPE = 38,
            F_HTML = 24;
    /**
     * Sync protocol meaning "never sync". Without it Glass shows a "syncing" icon on the
     * card and would try to upload it to Google's (long gone) Mirror service.
     */
    private static final int SYNC_NEVER = 2;
    // Entity fields
    private static final int E_SOURCE = 1, E_ID = 2, E_DISPLAY_NAME = 3;
    // MenuItem fields and actions
    private static final int M_ID = 1, M_ACTION = 2, M_VALUE = 3, M_BROADCAST_ACTION = 5,
            M_VOICE_COMMAND = 11;
    private static final int ACTION_DELETE = 3, ACTION_READ_ALOUD = 5, ACTION_BROADCAST = 7;
    // MenuValue fields
    private static final int V_DISPLAY_NAME = 1;
    /** TimelineItem.sourceType for items made on the device. */
    private static final int SOURCE_TYPE_DEVICE = 1;

    private final Context context;
    private final ContentResolver resolver;
    private final Listener listener;
    private final Handler worker;
    /** Card id to notification key, for cards we have on the timeline. Worker thread only. */
    private final Map<String, String> keys = new HashMap<>();
    private ContentObserver observer;

    public NativeTimeline(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.resolver = this.context.getContentResolver();
        this.listener = listener;
        HandlerThread thread = new HandlerThread("GlassLink-timeline");
        thread.start();
        worker = new Handler(thread.getLooper());
    }

    /** True when GlassLink is a system app and may write to the timeline. */
    public static boolean isAvailable(Context context) {
        return context.getPackageManager().checkPermission(PERMISSION, context.getPackageName())
                == PackageManager.PERMISSION_GRANTED;
    }

    /** Starts watching for cards the user deletes. */
    public void start() {
        if (!isAvailable(context)) return;
        worker.post(() -> {
            observer = new ContentObserver(worker) {
                @Override
                public void onChange(boolean selfChange) {
                    checkForDismissals();
                }
            };
            resolver.registerContentObserver(TIMELINE, true, observer);
        });
    }

    public void stop() {
        worker.post(() -> {
            if (observer != null) resolver.unregisterContentObserver(observer);
            observer = null;
            worker.getLooper().quit();
        });
    }

    /**
     * Adds or updates the card for a notification.
     *
     * @param alert true to chime and show it now, as Glass does for new messages
     */
    public void post(final Card card, final boolean alert) {
        worker.post(() -> {
            try {
                insert(card, alert);
            } catch (RuntimeException e) {
                Log.w(TAG, "Couldn't add timeline card", e);
            }
        });
    }

    /** Removes the card for a notification that was cleared on the phone. */
    public void remove(final String key) {
        worker.post(() -> {
            String id = idFor(key);
            keys.remove(id);
            try {
                resolver.delete(Uri.withAppendedPath(TIMELINE, id), null, null);
            } catch (RuntimeException e) {
                Log.w(TAG, "Couldn't remove timeline card", e);
            }
        });
    }

    /** Removes every GlassLink card, e.g. before the phone resends what is still active. */
    public void clearAll() {
        worker.post(() -> {
            keys.clear();
            try {
                int n = resolver.delete(TIMELINE_ALL, "source=?", new String[] {SOURCE});
                if (n > 0) Log.i(TAG, "Removed " + n + " timeline cards");
            } catch (RuntimeException e) {
                Log.w(TAG, "Couldn't clear timeline cards", e);
            }
        });
    }

    /** What a card shows. */
    public static final class Card {
        public final String key, app, pkg, title, text;
        public final long when;
        public final boolean canReply;
        /** Base64 PNGs from the phone: the app icon and the notification's own picture. */
        public final String iconPng, avatarPng;

        public Card(String key, String pkg, String app, String title, String text, long when,
                boolean canReply, String iconPng, String avatarPng) {
            this.iconPng = emptyToNull(iconPng);
            this.avatarPng = emptyToNull(avatarPng);
            this.key = key;
            this.pkg = pkg == null ? "" : pkg;
            this.app = app == null ? "" : app;
            this.title = title == null ? "" : title;
            this.text = text == null ? "" : text;
            this.when = when;
            this.canReply = canReply;
        }
    }

    // ---- Writing --------------------------------------------------------------------------

    private void insert(Card card, boolean alert) {
        String id = idFor(card.key);
        long now = System.currentTimeMillis();
        // Apps set "when" themselves, and some send nonsense (0, uptime, far future).
        // The timeline is ordered by this, so fall back to now when it's implausible.
        long display = card.when > now - 7L * 24 * 3600 * 1000 && card.when < now + 3600 * 1000
                ? card.when : now;
        // A card the user deleted stays as a tombstone, and the provider ignores inserts
        // over it. A notification posted again under the same key should show, so clear it.
        resolver.delete(Uri.withAppendedPath(TIMELINE, id), "is_deleted=1", null);

        byte[] blob = buildItem(id, card, display, now);
        ContentValues v = new ContentValues();
        v.put("_id", id);
        v.put("creation_time", display);
        v.put("modified_time", now);
        v.put("display_time", display);
        v.put("delivery_time", display);
        v.put("expiration_time", 0L);
        v.put("pin_time", -1L);
        v.put("pin_score", Integer.MAX_VALUE);
        v.put("is_deleted", 0);
        v.put("has_media_attachment", 0);
        v.put("sync_status", 0);
        v.put("sync_protocol", SYNC_NEVER);
        v.put("bundle_id", "");
        v.put("bundle_cover_status", 0);
        v.put("source", SOURCE);
        v.put("protobuf_blob", blob);
        v.put("attribution_type", -1);
        v.put("source_item_id", card.key);
        v.put("is_bundle_cover", 0);
        v.put("is_directory_cover", 0);
        resolver.insert(TIMELINE, v);
        keys.put(id, card.key);

        if (alert) {
            // The home screen chimes, wakes and shows the card, if Glass is being worn.
            Intent notify = new Intent(ACTION_NOTIFY)
                    .setPackage(HOME_PACKAGE)
                    .putExtra("item", blob)
                    .putExtra("item_id", new TimelineItemId(id, false, display, "", false))
                    .putExtra("num_notifications", 1);
            context.sendBroadcast(notify);
        }
    }

    private static byte[] buildItem(String id, Card card, long display, long now) {
        Proto creator = new Proto()
                .string(E_SOURCE, SOURCE)
                .string(E_ID, card.pkg)
                .string(E_DISPLAY_NAME, card.app);
        Proto item = new Proto()
                .string(F_ID, id)
                .uint64(F_CREATION_TIME, display)
                .uint64(F_MODIFIED_TIME, now)
                .string(F_SOURCE, SOURCE)
                .message(F_CREATOR, creator)
                .string(F_TEXT, cardText(card)) // plain fallback; Glass shows the HTML
                .string(F_HTML, CardHtml.build(card));
        if (card.canReply) {
            item.message(F_MENU_ITEM, new Proto()
                    .string(M_ID, "reply")
                    .int32(M_ACTION, ACTION_BROADCAST)
                    .message(M_VALUE, new Proto().string(V_DISPLAY_NAME, "Reply"))
                    .string(M_BROADCAST_ACTION, ACTION_REPLY)
                    // A custom label hides an item from the "ok glass" menu unless it also
                    // names one of the GDK's ContextualMenus.Command values.
                    .string(M_VOICE_COMMAND, "REPLY"));
        }
        item.message(F_MENU_ITEM, new Proto().string(M_ID, "read").int32(M_ACTION, ACTION_READ_ALOUD));
        item.message(F_MENU_ITEM, new Proto().string(M_ID, "delete").int32(M_ACTION, ACTION_DELETE)
                .message(M_VALUE, new Proto().string(V_DISPLAY_NAME, "Dismiss"))
                .string(M_VOICE_COMMAND, "DISMISS_THIS")); // "ok glass, dismiss this"
        item.int32(F_CLOUD_SYNC_PROTOCOL, SYNC_NEVER)
                .int32(F_SOURCE_TYPE, SOURCE_TYPE_DEVICE)
                .uint64(F_DISPLAY_TIME, display)
                .string(F_SPEAKABLE_TEXT, speakable(card))
                .string(F_TITLE, card.title)
                .string(F_SOURCE_ITEM_ID, card.key)
                .int32(F_COMPANION_SYNC_PROTOCOL, SYNC_NEVER)
                .string(F_SPEAKABLE_TYPE, card.app.isEmpty() ? "Notification" : card.app);
        return item.toByteArray();
    }

    /** Text cards show only the text, so the sender or subject goes on its own first line. */
    private static String cardText(Card card) {
        if (card.title.isEmpty()) return card.text;
        if (card.text.isEmpty()) return card.title;
        return card.title + "\n" + card.text;
    }

    private static String speakable(Card card) {
        if (card.title.isEmpty()) return card.text;
        return card.title + ". " + card.text;
    }

    // ---- Dismissals -----------------------------------------------------------------------

    /** Finds our cards the user deleted on Glass, tells the phone, then removes the tombstones. */
    private void checkForDismissals() {
        Cursor c = null;
        try {
            c = resolver.query(TIMELINE_ALL, new String[] {"_id"}, "source=? AND is_deleted=1",
                    new String[] {SOURCE}, null);
            if (c == null) return;
            while (c.moveToNext()) {
                String id = c.getString(0);
                String key = keys.remove(id);
                resolver.delete(Uri.withAppendedPath(TIMELINE, id), null, null);
                if (key != null) {
                    Log.i(TAG, "Timeline card dismissed on Glass: " + key);
                    listener.onCardDismissed(key);
                }
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "Couldn't check timeline", e);
        } finally {
            if (c != null) c.close();
        }
    }

    /** Looks up the notification key for a card, for menu actions. Blocking; not on the UI thread. */
    public static String keyForCard(Context context, String id) {
        if (id == null || !id.startsWith(ID_PREFIX)) return null;
        Cursor c = null;
        try {
            c = context.getContentResolver().query(Uri.withAppendedPath(TIMELINE, id), new String[] {"source_item_id"},
                    null, null, null);
            return c != null && c.moveToFirst() ? c.getString(0) : null;
        } catch (RuntimeException e) {
            return null;
        } finally {
            if (c != null) c.close();
        }
    }

    private static String emptyToNull(String s) {
        return s == null || s.isEmpty() ? null : s;
    }

    /** Stable card id for a notification key, so updates replace the same card. */
    static String idFor(String key) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-1").digest(key.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder(ID_PREFIX);
            for (int i = 0; i < 12; i++) sb.append(String.format("%02x", d[i]));
            return sb.toString();
        } catch (Exception e) {
            return ID_PREFIX + Integer.toHexString(key.hashCode());
        }
    }
}
