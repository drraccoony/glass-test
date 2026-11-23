package dev.glassrevival.companion;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.Person;
import android.app.RemoteInput;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
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
    private final Map<String, ReplyTarget> replyTargets = new HashMap<>();
    private final Map<String, Long> repliedAt = new HashMap<>();

    private static final long REPLY_ECHO_MS = 15000;

    /** The app's reply action for one notification, which Glass replies are sent through. */
    static final class ReplyTarget {
        final Notification.Action action;
        final RemoteInput[] inputs;
        final boolean freeForm;
        final List<String> choices = new ArrayList<>();

        ReplyTarget(Notification.Action action) {
            this.action = action;
            this.inputs = action.getRemoteInputs();
            boolean any = false;
            for (RemoteInput input : inputs) {
                any |= input.getAllowFreeFormInput();
                CharSequence[] c = input.getChoices();
                if (c != null) {
                    for (CharSequence s : c) {
                        if (!TextUtils.isEmpty(s)) choices.add(s.toString());
                    }
                }
            }
            this.freeForm = any;
        }
    }

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
        NotificationListenerService.Ranking ranking = null;
        if (rankingMap != null) {
            ranking = new NotificationListenerService.Ranking();
            if (!rankingMap.getRanking(sbn.getKey(), ranking)) {
                ranking = null;
            } else {
                int importance = ranking.getImportance();
                if (importance != NotificationManager.IMPORTANCE_UNSPECIFIED
                        && importance <= NotificationManager.IMPORTANCE_MIN) {
                    return null;
                }
                boolean silent = importance != NotificationManager.IMPORTANCE_UNSPECIFIED
                        && importance < NotificationManager.IMPORTANCE_DEFAULT;
                if (silent && !prefs.includeSilent()) return null;
                // Quiet channels (and Do Not Disturb, if followed) still show on Glass, without an alert.
                mayAlert = !silent && (!prefs.followDnd() || ranking.matchesInterruptionFilter());
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
        // Refresh the reply action on every post: apps replace its PendingIntent on updates.
        ReplyTarget target = findReplyTarget(n);
        if (target != null) replyTargets.put(key, target); else replyTargets.remove(key);
        int hash = (title + "\u0000" + text).hashCode();
        Integer previous = sent.put(key, hash);
        if (previous != null && previous == hash) return null;

        boolean alertOnce = (n.flags & Notification.FLAG_ONLY_ALERT_ONCE) != 0;
        // Apps usually update the conversation right after we reply; don't buzz for our own message.
        Long replied = repliedAt.get(key);
        boolean echoOfOurReply = replied != null
                && System.currentTimeMillis() - replied < REPLY_ECHO_MS;
        boolean alert = !snapshot && mayAlert && !echoOfOurReply && !(previous != null && alertOnce)
                && !(prefs.onlyWhenLocked() && phoneInUse());

        try {
            JSONObject msg = new JSONObject();
            msg.put(Protocol.F_TYPE, Protocol.NOTIFY);
            msg.put(Protocol.F_KEY, key);
            msg.put(Protocol.F_PACKAGE, pkg);
            msg.put(Protocol.F_APP, labelFor(pkg, extras));
            msg.put(Protocol.F_TITLE, title);
            // With "show message text" off, the body never leaves the phone.
            msg.put(Protocol.F_TEXT, prefs.showText() ? text : "");
            msg.put(Protocol.F_WHEN, sbn.getPostTime());
            msg.put(Protocol.F_ALERT, alert);
            String icon = iconFor(pkg);
            if (icon != null) msg.put(Protocol.F_ICON, icon);
            if (target != null) {
                // canReply means free text (voice) works; choices may exist either way.
                msg.put(Protocol.F_CAN_REPLY, target.freeForm);
                List<String> choices = choicesFor(target, ranking);
                if (!choices.isEmpty()) msg.put(Protocol.F_CHOICES, new JSONArray(choices));
            }
            return msg;
        } catch (JSONException e) {
            return null;
        }
    }

    /** True when the phone is unlocked and its screen is on, i.e. you're looking at it. */
    private boolean phoneInUse() {
        android.app.KeyguardManager km = context.getSystemService(android.app.KeyguardManager.class);
        android.os.PowerManager pm = context.getSystemService(android.os.PowerManager.class);
        boolean locked = km != null && km.isKeyguardLocked();
        boolean screenOn = pm == null || pm.isInteractive();
        return screenOn && !locked;
    }

    /** @return true if this key had been sent to Glass, so Glass should be told it's gone */
    synchronized boolean forget(String key) {
        replyTargets.remove(key);
        repliedAt.remove(key);
        return sent.remove(key) != null;
    }

    /** Called before a full resync after reconnecting. */
    synchronized void reset() {
        sent.clear();
        replyTargets.clear();
    }

    synchronized ReplyTarget replyTarget(String key) {
        return replyTargets.get(key);
    }

    synchronized void markReplied(String key) {
        repliedAt.put(key, System.currentTimeMillis());
    }

    /**
     * Picks the action to reply through. Prefers one the app marks as "reply", then any
     * action that takes text input. Falls back to the Wear OS actions, which is where some
     * apps put their only reply action.
     */
    private static ReplyTarget findReplyTarget(Notification n) {
        Notification.Action best = pickReplyAction(n.actions);
        if (best == null) {
            @SuppressWarnings("deprecation")
            List<Notification.Action> wear = new Notification.WearableExtender(n).getActions();
            best = pickReplyAction(wear.toArray(new Notification.Action[0]));
        }
        return best == null || best.actionIntent == null ? null : new ReplyTarget(best);
    }

    private static Notification.Action pickReplyAction(Notification.Action[] actions) {
        if (actions == null) return null;
        Notification.Action fallback = null;
        for (Notification.Action a : actions) {
            RemoteInput[] inputs = a.getRemoteInputs();
            if (inputs == null || inputs.length == 0) continue;
            if (Build.VERSION.SDK_INT >= 28
                    && a.getSemanticAction() == Notification.Action.SEMANTIC_ACTION_REPLY) {
                return a;
            }
            if (fallback == null) fallback = a;
        }
        return fallback;
    }

    /** App suggestions first, then Android's smart replies, then the user's quick replies. */
    private List<String> choicesFor(ReplyTarget target, NotificationListenerService.Ranking ranking) {
        Set<String> out = new LinkedHashSet<>(target.choices);
        if (Build.VERSION.SDK_INT >= 29 && ranking != null && target.freeForm) {
            for (CharSequence s : ranking.getSmartReplies()) {
                if (!TextUtils.isEmpty(s)) out.add(s.toString());
            }
        }
        if (target.freeForm) out.addAll(prefs.quickReplies());
        List<String> list = new ArrayList<>(out);
        return list.size() > Protocol.MAX_CHOICES ? list.subList(0, Protocol.MAX_CHOICES) : list;
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
