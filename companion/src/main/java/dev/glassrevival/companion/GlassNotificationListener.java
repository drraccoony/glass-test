package dev.glassrevival.companion;

import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
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
        Log.i(LinkManager.TAG, "Notification listener connected");
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
        CompanionApp.nav(this).onPosted(sbn); // Maps turn-by-turn; kept even while disconnected
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
        CompanionApp.nav(this).onRemoved(sbn);
        if (mapper.forget(sbn.getKey())) sendRemove(sbn.getKey());
    }

    /** Glass just connected (and cleared its list): send everything currently active. */
    @Override
    public void onGlassConnected() {
        if (!listening) {
            Log.i(LinkManager.TAG, "Glass connected before the listener; will sync when it binds");
            return;
        }
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
        int sent = 0;
        for (StatusBarNotification sbn : active) {
            CompanionApp.nav(this).onPosted(sbn); // pick up a navigation already running
            JSONObject msg = mapper.map(sbn, ranking, true);
            if (msg != null) {
                link.send(msg);
                sent++;
            }
        }
        Log.i(LinkManager.TAG, "Synced " + sent + " of " + active.length + " notifications to Glass");
    }

    @Override
    public void onGlassDismissed(String key) {
        Log.i(LinkManager.TAG, "Glass dismissed " + key);
        mapper.forget(key);
        try {
            cancelNotification(key);
        } catch (SecurityException | IllegalStateException e) {
            Log.w(LinkManager.TAG, "Couldn't dismiss " + key, e);
        }
    }

    /** Fills in the app's reply action, the same way a smartwatch reply works. */
    @Override
    public String onGlassReply(String key, String text) {
        NotificationMapper.ReplyTarget target = mapper.replyTarget(key);
        if (target == null) return "That conversation is no longer on your phone";
        Intent fillIn = new Intent();
        Bundle results = new Bundle();
        for (RemoteInput input : target.inputs) {
            results.putCharSequence(input.getResultKey(), text);
        }
        RemoteInput.addResultsToIntent(target.inputs, fillIn, results);
        if (Build.VERSION.SDK_INT >= 28) {
            RemoteInput.setResultsSource(fillIn, RemoteInput.SOURCE_FREE_FORM_INPUT);
        }
        try {
            target.action.actionIntent.send(this, 0, fillIn);
        } catch (PendingIntent.CanceledException e) {
            return "The app no longer accepts replies to this";
        } catch (RuntimeException e) {
            Log.w(LinkManager.TAG, "Reply failed", e);
            return "The app refused the reply";
        }
        mapper.markReplied(key);
        Log.i(LinkManager.TAG, "Sent reply from Glass for " + key);
        return null;
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
