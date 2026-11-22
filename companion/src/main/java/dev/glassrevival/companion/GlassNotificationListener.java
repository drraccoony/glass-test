package dev.glassrevival.companion;

import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;
import java.util.Arrays;
import java.util.Comparator;
import org.json.JSONException;
import org.json.JSONObject;
import dev.glassrevival.protocol.Protocol;

/** Receives every notification on the phone and forwards the relevant ones to Glass. */
public class GlassNotificationListener extends NotificationListenerService implements LinkManager.Peer {

    private LinkManager link;
    private NotificationMapper mapper;
    private volatile boolean listening;

    @Override
    public void onCreate() {
        super.onCreate();
        link = CompanionApp.link(this);
        mapper = new NotificationMapper(this, CompanionApp.prefs(this));
    }

    @Override
    public void onListenerConnected() {
        listening = true;
        link.setPeer(this);
        GlassLinkService.startIfEnabled(this);
        if (link.isConnected()) onGlassConnected();
    }

    @Override
    public void onListenerDisconnected() {
        listening = false;
        link.clearPeer(this);
    }

    @Override
    public void onDestroy() {
        link.clearPeer(this);
        super.onDestroy();
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn, RankingMap rankingMap) {
        if (!link.isConnected()) {
            // Still note the app so it appears in the mute list.
            CompanionApp.prefs(this).recordSeen(sbn.getPackageName());
            return;
        }
        JSONObject msg = mapper.map(sbn, rankingMap, false);
        if (msg != null) link.send(msg);
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn, RankingMap rankingMap) {
        if (mapper.forget(sbn.getKey())) sendRemove(sbn.getKey());
    }

    /** Glass just connected (and cleared its list): send everything currently active. */
    @Override
    public void onGlassConnected() {
        if (!listening) return;
        StatusBarNotification[] active;
        RankingMap ranking;
        try {
            active = getActiveNotifications();
            ranking = getCurrentRanking();
        } catch (SecurityException | IllegalStateException e) {
            Log.w(LinkManager.TAG, "Can't read active notifications", e);
            return;
        }
        if (active == null) return;
        mapper.reset();
        // Oldest first, so Glass ends up with the newest at the front.
        Arrays.sort(active, new Comparator<StatusBarNotification>() {
            @Override
            public int compare(StatusBarNotification a, StatusBarNotification b) {
                return Long.compare(a.getPostTime(), b.getPostTime());
            }
        });
        for (StatusBarNotification sbn : active) {
            JSONObject msg = mapper.map(sbn, ranking, true);
            if (msg != null) link.send(msg);
        }
    }

    @Override
    public void onGlassDismissed(String key) {
        mapper.forget(key);
        try {
            cancelNotification(key);
        } catch (SecurityException | IllegalStateException e) {
            Log.w(LinkManager.TAG, "Couldn't dismiss " + key, e);
        }
    }

    private void sendRemove(String key) {
        try {
            JSONObject msg = new JSONObject();
            msg.put(Protocol.F_TYPE, Protocol.REMOVE);
            msg.put(Protocol.F_KEY, key);
            link.send(msg);
        } catch (JSONException ignored) {
            // cannot happen
        }
    }
}
