package dev.glassrevival.companion;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.Person;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Parcelable;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;
import android.util.Base64;
import dev.glassrevival.protocol.Protocol;
import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.Map;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Decides which phone notifications go to Glass and turns them into protocol messages.
 * Remembers what it already sent so repeated identical updates aren't forwarded.
 */
final class NotificationMapper {

    private static final int ICON_PX = 48;
    private static final int MAX_TEXT = 1200;
    private static final int MAX_MESSAGES = 4;

    private final Context context;
    private final Prefs prefs;
    private final PackageManager pm;
    private final Map<String, Integer> sent = new HashMap<>(); // key -> content hash
    private final Map<String, String> iconCache = new HashMap<>();
    private final Map<String, String> labelCache = new HashMap<>();

    NotificationMapper(Context context, Prefs prefs) {
        this.context = context;
        this.prefs = prefs;
        this.pm = context.getPackageManager();
    }

    /**
     * @param snapshot true when resending active notifications after (re)connecting;
     *                 those never buzz Glass
     * @return the message to send, or null to skip this notification
     */
    synchronized JSONObject map(StatusBarNotification sbn,
                                NotificationListenerService.RankingMap rankingMap,
                                boolean snapshot) {
        String pkg = sbn.getPackageName();
        if (pkg.equals(context.getPackageName())) return null;
        Notification n = sbn.getNotification();
        if (n == null) return null;

        int skipFlags = Notification.FLAG_ONGOING_EVENT | Notification.FLAG_FOREGROUND_SERVICE
                | Notification.FLAG_GROUP_SUMMARY | Notification.FLAG_LOCAL_ONLY;
        if ((n.flags & skipFlags) != 0) return null;
        if (Notification.CATEGORY_TRANSPORT.equals(n.category)
                || Notification.CATEGORY_PROGRESS.equals(n.category)
                || Notification.CATEGORY_SERVICE.equals(n.category)) {
            return null;
        }
        Bundle extras = n.extras;
        if (extras.getInt(Notification.EXTRA_PROGRESS_MAX, 0) > 0
                || extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE, false)) {
            return null;
        }

        prefs.recordSeen(pkg);
        if (prefs.isMuted(pkg)) return null;

        boolean mayAlert = true;
        if (rankingMap != null) {
            NotificationListenerService.Ranking ranking = new NotificationListenerService.Ranking();
            if (rankingMap.getRanking(sbn.getKey(), ranking)) {
                int importance = ranking.getImportance();
                if (importance != NotificationManager.IMPORTANCE_UNSPECIFIED
                        && importance <= NotificationManager.IMPORTANCE_MIN) {
                    return null;
                }
                // Quiet channels and Do Not Disturb still show on Glass, just without the chime.
                mayAlert = importance >= NotificationManager.IMPORTANCE_DEFAULT
                        && ranking.matchesInterruptionFilter();
            }
        }

        String title = firstNonEmpty(
                extras.getCharSequence(Notification.EXTRA_TITLE_BIG),
                extras.getCharSequence(Notification.EXTRA_TITLE),
                extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE));
        String text = bodyOf(n, extras);
        if (title.isEmpty() && text.isEmpty()) return null;
        if (text.length() > MAX_TEXT) text = text.substring(0, MAX_TEXT - 1) + "…";

        String key = sbn.getKey();
        int hash = (title + "\u0000" + text).hashCode();
        Integer previous = sent.put(key, hash);
        if (previous != null && previous == hash) return null;

        boolean alertOnce = (n.flags & Notification.FLAG_ONLY_ALERT_ONCE) != 0;
        boolean alert = !snapshot && mayAlert && !(previous != null && alertOnce);

        try {
            JSONObject msg = new JSONObject();
            msg.put(Protocol.F_TYPE, Protocol.NOTIFY);
            msg.put(Protocol.F_KEY, key);
            msg.put(Protocol.F_PACKAGE, pkg);
            msg.put(Protocol.F_APP, labelFor(pkg, extras));
            msg.put(Protocol.F_TITLE, title);
            msg.put(Protocol.F_TEXT, text);
            msg.put(Protocol.F_WHEN, sbn.getPostTime());
            msg.put(Protocol.F_ALERT, alert);
            String icon = iconFor(pkg);
            if (icon != null) msg.put(Protocol.F_ICON, icon);
            return msg;
        } catch (JSONException e) {
            return null;
        }
    }

    /** @return true if this key had been sent to Glass, so Glass should be told it's gone */
    synchronized boolean forget(String key) {
        return sent.remove(key) != null;
    }

    /** Called before a full resync after reconnecting. */
    synchronized void reset() {
        sent.clear();
    }

    synchronized String iconFor(String pkg) {
        if (iconCache.containsKey(pkg)) return iconCache.get(pkg);
        String encoded = null;
        try {
            encoded = encodeIcon(pm.getApplicationIcon(pkg));
        } catch (PackageManager.NameNotFoundException ignored) {
            // app not visible to us; Glass shows no icon
        }
        iconCache.put(pkg, encoded);
        return encoded;
    }

    private String labelFor(String pkg, Bundle extras) {
        String cached = labelCache.get(pkg);
        if (cached != null) return cached;
        CharSequence label = null;
        // Notifications carry the poster's ApplicationInfo, which works even without
        // package visibility.
        Parcelable info = extras.getParcelable("android.appInfo");
        if (info instanceof ApplicationInfo) label = pm.getApplicationLabel((ApplicationInfo) info);
        if (TextUtils.isEmpty(label)) {
            try {
                label = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0));
            } catch (PackageManager.NameNotFoundException e) {
                label = pkg;
            }
        }
        String result = label.toString();
        labelCache.put(pkg, result);
        return result;
    }

    private static String bodyOf(Notification n, Bundle extras) {
        Parcelable[] messages = extras.getParcelableArray(Notification.EXTRA_MESSAGES);
        if (messages != null && messages.length > 0) {
            StringBuilder sb = new StringBuilder();
            for (int i = Math.max(0, messages.length - MAX_MESSAGES); i < messages.length; i++) {
                if (!(messages[i] instanceof Bundle)) continue;
                Bundle m = (Bundle) messages[i];
                CharSequence body = m.getCharSequence("text");
                if (TextUtils.isEmpty(body)) continue;
                CharSequence sender = m.getCharSequence("sender");
                if (Build.VERSION.SDK_INT >= 28 && TextUtils.isEmpty(sender)) {
                    Parcelable person = m.getParcelable("sender_person");
                    if (person instanceof Person) sender = ((Person) person).getName();
                }
                if (sb.length() > 0) sb.append('\n');
                if (!TextUtils.isEmpty(sender)) sb.append(sender).append(": ");
                sb.append(body);
            }
            if (sb.length() > 0) return sb.toString().trim();
        }
        String text = firstNonEmpty(
                extras.getCharSequence(Notification.EXTRA_BIG_TEXT),
                extras.getCharSequence(Notification.EXTRA_TEXT));
        if (!text.isEmpty()) return text;
        CharSequence[] lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES);
        if (lines != null && lines.length > 0) return TextUtils.join("\n", lines).trim();
        return firstNonEmpty(extras.getCharSequence(Notification.EXTRA_SUB_TEXT), n.tickerText);
    }

    private static String firstNonEmpty(CharSequence... values) {
        for (CharSequence v : values) {
            if (!TextUtils.isEmpty(v)) {
                String s = v.toString().trim();
                if (!s.isEmpty()) return s;
            }
        }
        return "";
    }

    static String encodeIcon(Drawable drawable) {
        if (drawable == null) return null;
        Bitmap bitmap = Bitmap.createBitmap(ICON_PX, ICON_PX, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        drawable.setBounds(0, 0, ICON_PX, ICON_PX);
        drawable.draw(canvas);
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, png);
        bitmap.recycle();
        return Base64.encodeToString(png.toByteArray(), Base64.NO_WRAP);
    }
}
