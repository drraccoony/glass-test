package dev.glassrevival.hub;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.util.Log;
import dev.glassrevival.protocol.Protocol;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * The hub, as run by the app that owns the Bluetooth link: GlassLink on Glass, the
 * companion on the phone. It hands channel frames from Bluetooth to registered apps
 * and sends their messages back out.
 *
 * <p>The owning app returns {@link #binder()} from its hub service, calls
 * {@link #deliver} for every frame with a {@link Protocol#F_CHANNEL} field, and calls
 * {@link #setConnected} when the link comes up or goes down.
 */
public final class HubHost {

    /** The Bluetooth link, as seen by the hub. */
    public interface Link {
        boolean isConnected();

        /** Queue a frame for the other device. @return false if not connected */
        boolean send(JSONObject frame);
    }

    private static final String TAG = "GlassLinkHub";
    private static final Charset UTF8 = Charset.forName("UTF-8");

    private final Context context;
    private final Link link;
    /** Listeners by channel. Each one's cookie is the caller's uid. Guarded by itself. */
    private final Map<String, RemoteCallbackList<IGlassLinkListener>> channels = new HashMap<>();
    private final Object stateLock = new Object();
    private boolean connected; // guarded by stateLock

    public HubHost(Context context, Link link) {
        this.context = context.getApplicationContext();
        this.link = link;
    }

    public IBinder binder() {
        return binder;
    }

    /**
     * Hands a frame from the other device to the apps on its channel. If no app is
     * bound to that channel, it goes to the receivers that declared it instead.
     */
    public void deliver(JSONObject frame) {
        String channel = frame.optString(Protocol.F_CHANNEL, "");
        if (!Protocol.isValidChannel(channel)) {
            Log.w(TAG, "Dropping frame with bad channel");
            return;
        }
        String json = frame.toString();
        RemoteCallbackList<IGlassLinkListener> list = listFor(channel, false);
        int delivered = 0;
        if (list != null) {
            synchronized (list) {
                int n = list.beginBroadcast();
                try {
                    for (int i = 0; i < n; i++) {
                        try {
                            list.getBroadcastItem(i).onMessage(json);
                            delivered++;
                        } catch (RemoteException e) {
                            // That app died. RemoteCallbackList drops it.
                        }
                    }
                } finally {
                    list.finishBroadcast();
                }
            }
        }
        if (delivered > 0) return;
        List<ComponentName> receivers = receiversFor(channel);
        if (receivers.isEmpty()) {
            Log.i(TAG, "No app for channel " + channel);
            return;
        }
        for (ComponentName c : receivers) {
            Intent intent = new Intent(Hub.ACTION_MESSAGE)
                    .setComponent(c)
                    .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                    .putExtra(Hub.EXTRA_CHANNEL, channel)
                    .putExtra(Hub.EXTRA_MESSAGE, json);
            context.sendBroadcast(intent, Hub.PERMISSION);
        }
    }

    /** Tells apps the link came up or went down. Repeated calls with the same value do nothing. */
    public void setConnected(boolean nowConnected) {
        synchronized (stateLock) {
            if (connected == nowConnected) return;
            connected = nowConnected;
        }
        Set<Integer> listening = new HashSet<>();
        for (RemoteCallbackList<IGlassLinkListener> list : allLists()) {
            synchronized (list) {
                int n = list.beginBroadcast();
                try {
                    for (int i = 0; i < n; i++) {
                        try {
                            list.getBroadcastItem(i).onConnectionChanged(nowConnected);
                            listening.add((Integer) list.getBroadcastCookie(i));
                        } catch (RemoteException e) {
                            // dropped
                        }
                    }
                } finally {
                    list.finishBroadcast();
                }
            }
        }
        // Apps that aren't bound hear about it through their receiver, so a phone app can
        // push its state as soon as Glass connects.
        PackageManager pm = context.getPackageManager();
        for (ComponentName c : allReceivers()) {
            try {
                int uid = pm.getApplicationInfo(c.getPackageName(), 0).uid;
                if (listening.contains(uid)) continue;
            } catch (PackageManager.NameNotFoundException e) {
                continue;
            }
            Intent intent = new Intent(Hub.ACTION_CONNECTION)
                    .setComponent(c)
                    .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                    .putExtra(Hub.EXTRA_CONNECTED, nowConnected);
            context.sendBroadcast(intent, Hub.PERMISSION);
        }
    }

    // ---- Binder ---------------------------------------------------------------------------

    private final IGlassLinkHub.Stub binder = new IGlassLinkHub.Stub() {
        @Override
        public int apiVersion() {
            enforce();
            return Hub.API_VERSION;
        }

        @Override
        public boolean isConnected() {
            enforce();
            return link.isConnected();
        }

        @Override
        public void register(String channel, IGlassLinkListener listener) {
            enforce();
            checkChannel(channel);
            if (listener == null) throw new IllegalArgumentException("listener is null");
            int uid = Binder.getCallingUid();
            listFor(channel, true).register(listener, uid);
            Log.i(TAG, "Channel " + channel + " registered by uid " + uid);
            try {
                listener.onConnectionChanged(link.isConnected());
            } catch (RemoteException ignored) {
                // it already died
            }
        }

        @Override
        public void unregister(String channel, IGlassLinkListener listener) {
            enforce();
            if (listener == null) return;
            RemoteCallbackList<IGlassLinkListener> list = listFor(channel, false);
            if (list != null) list.unregister(listener);
        }

        @Override
        public boolean send(String channel, String json) {
            enforce();
            checkChannel(channel);
            JSONObject frame;
            try {
                frame = new JSONObject(json);
                frame.put(Protocol.F_CHANNEL, channel);
            } catch (JSONException | NullPointerException e) {
                Log.w(TAG, "Not a JSON object on channel " + channel);
                return false;
            }
            // Leave room for the frame header; FrameIO rejects anything over the limit.
            if (frame.toString().getBytes(UTF8).length > Protocol.MAX_FRAME_BYTES) {
                Log.w(TAG, "Message too large on channel " + channel);
                return false;
            }
            return link.send(frame);
        }
    };

    private void enforce() {
        context.enforceCallingOrSelfPermission(Hub.PERMISSION, "GlassLink hub");
    }

    private static void checkChannel(String channel) {
        if (!Protocol.isValidChannel(channel)) {
            throw new IllegalArgumentException("Bad channel name: " + channel);
        }
    }

    // ---- Bookkeeping ----------------------------------------------------------------------

    private RemoteCallbackList<IGlassLinkListener> listFor(String channel, boolean create) {
        synchronized (channels) {
            RemoteCallbackList<IGlassLinkListener> list = channels.get(channel);
            if (list == null && create) {
                list = new RemoteCallbackList<>();
                channels.put(channel, list);
            }
            return list;
        }
    }

    private List<RemoteCallbackList<IGlassLinkListener>> allLists() {
        synchronized (channels) {
            return new ArrayList<>(channels.values());
        }
    }

    /** Receivers that declared this channel. */
    private List<ComponentName> receiversFor(String channel) {
        List<ComponentName> out = new ArrayList<>();
        for (ResolveInfo r : queryReceivers()) {
            for (String c : declaredChannels(r.activityInfo)) {
                if (c.equals(channel)) {
                    out.add(new ComponentName(r.activityInfo.packageName, r.activityInfo.name));
                    break;
                }
            }
        }
        return out;
    }

    /** Every receiver that declared at least one channel. */
    private List<ComponentName> allReceivers() {
        List<ComponentName> out = new ArrayList<>();
        for (ResolveInfo r : queryReceivers()) {
            if (declaredChannels(r.activityInfo).length > 0) {
                out.add(new ComponentName(r.activityInfo.packageName, r.activityInfo.name));
            }
        }
        return out;
    }

    private List<ResolveInfo> queryReceivers() {
        List<ResolveInfo> found = context.getPackageManager().queryBroadcastReceivers(
                new Intent(Hub.ACTION_MESSAGE), PackageManager.GET_META_DATA);
        return found == null ? new ArrayList<ResolveInfo>() : found;
    }

    private static String[] declaredChannels(ActivityInfo info) {
        Bundle meta = info == null ? null : info.metaData;
        String value = meta == null ? null : meta.getString(Hub.META_CHANNELS);
        if (value == null || value.trim().isEmpty()) return new String[0];
        return value.trim().split("[,\\s]+");
    }
}
