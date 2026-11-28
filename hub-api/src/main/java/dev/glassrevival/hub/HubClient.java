package dev.glassrevival.hub;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.util.Log;
import java.util.List;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Connects an add-on app to the GlassLink hub on the same device, for one channel.
 * Works the same on Glass and on the phone. Callbacks arrive on the main thread.
 *
 * <pre>
 * HubClient nav = new HubClient(context, "dev.glassrevival.nav", new HubClient.Callback() {
 *     public void onMessage(JSONObject msg) { ... }
 *     public void onConnectionChanged(boolean connected) { ... }
 * });
 * nav.connect();   // e.g. in onStart
 * nav.send(new JSONObject().put("type", "nav_step").put("street", "Main St"));
 * nav.disconnect(); // e.g. in onStop
 * </pre>
 *
 * <p>The client reconnects by itself if the hub app restarts or is updated.
 */
public final class HubClient {

    /** Override what you need. All are called on the main thread. */
    public abstract static class Callback {
        /** A message from the other device's app on this channel. */
        public void onMessage(JSONObject msg) {}

        /** The link between Glass and the phone came up or went down. */
        public void onConnectionChanged(boolean connected) {}

        /** No hub app is installed on this device, or it refused the binding. */
        public void onHubUnavailable() {}
    }

    private static final String TAG = "GlassLinkHub";

    private final Context context;
    private final String channel;
    private final Callback callback;
    private final Handler main = new Handler(Looper.getMainLooper());

    private boolean bound; // main thread only
    private volatile IGlassLinkHub hub;
    private volatile boolean connected;

    public HubClient(Context context, String channel, Callback callback) {
        if (!dev.glassrevival.protocol.Protocol.isValidChannel(channel)) {
            throw new IllegalArgumentException("Bad channel name: " + channel);
        }
        this.context = context.getApplicationContext();
        this.channel = channel;
        this.callback = callback;
    }

    /**
     * Binds to the hub, starting GlassLink if it isn't running. Call from the main thread.
     *
     * @return false if no hub is installed
     */
    public boolean connect() {
        if (bound) return true;
        ComponentName hubService = findHub(context);
        if (hubService == null) {
            Log.w(TAG, "No GlassLink hub on this device");
            post(callback::onHubUnavailable);
            return false;
        }
        try {
            bound = context.bindService(new Intent(Hub.ACTION_BIND).setComponent(hubService),
                    connection, Context.BIND_AUTO_CREATE);
        } catch (SecurityException e) {
            Log.w(TAG, "Hub refused the binding. Is this app signed with the GlassLink key?", e);
            bound = false;
        }
        if (!bound) post(callback::onHubUnavailable);
        return bound;
    }

    /** Unbinds. No more callbacks after this. Call from the main thread. */
    public void disconnect() {
        if (!bound) return;
        bound = false;
        IGlassLinkHub h = hub;
        hub = null;
        connected = false;
        if (h != null) {
            try {
                h.unregister(channel, listener);
            } catch (RemoteException | RuntimeException ignored) {
                // hub is gone anyway
            }
        }
        context.unbindService(connection);
    }

    /**
     * Sends a message to the other device's app on this channel. Safe from any thread.
     *
     * @return false if not bound, the devices aren't linked, or the message is too large
     */
    public boolean send(JSONObject msg) {
        IGlassLinkHub h = hub;
        if (h == null) return false;
        try {
            return h.send(channel, msg.toString());
        } catch (RemoteException | RuntimeException e) {
            Log.w(TAG, "send failed: " + e);
            return false;
        }
    }

    /** True while bound to the hub and Glass and the phone are linked. */
    public boolean isConnected() {
        return hub != null && connected;
    }

    public String channel() {
        return channel;
    }

    /**
     * Finds the hub service on this device. Only an app that holds the hub's signature
     * permission counts, so another app can't pose as the hub.
     */
    public static ComponentName findHub(Context context) {
        PackageManager pm = context.getPackageManager();
        List<ResolveInfo> found = pm.queryIntentServices(new Intent(Hub.ACTION_BIND), 0);
        if (found == null) return null;
        for (ResolveInfo r : found) {
            ServiceInfo s = r.serviceInfo;
            if (s == null) continue;
            if (pm.checkPermission(Hub.PERMISSION, s.packageName) == PackageManager.PERMISSION_GRANTED) {
                return new ComponentName(s.packageName, s.name);
            }
        }
        return null;
    }

    // ---- Internals ------------------------------------------------------------------------

    private final IGlassLinkListener.Stub listener = new IGlassLinkListener.Stub() {
        @Override
        public void onMessage(String json) {
            final JSONObject msg;
            try {
                msg = new JSONObject(json);
            } catch (JSONException e) {
                return;
            }
            post(() -> {
                if (bound) callback.onMessage(msg);
            });
        }

        @Override
        public void onConnectionChanged(final boolean nowConnected) {
            post(() -> setConnected(nowConnected));
        }
    };

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            if (!bound) return;
            IGlassLinkHub h = IGlassLinkHub.Stub.asInterface(service);
            try {
                h.register(channel, listener);
                hub = h;
                Log.i(TAG, "Connected to hub for " + channel + " (hub API " + h.apiVersion() + ")");
            } catch (RemoteException | RuntimeException e) {
                Log.w(TAG, "Couldn't register " + channel + ": " + e);
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            // Android rebinds by itself when the hub comes back; onServiceConnected re-registers.
            hub = null;
            setConnected(false);
        }

        @Override
        public void onBindingDied(ComponentName name) {
            // The hub app was updated or disabled (Android 8+). Start over.
            if (!bound) return;
            disconnect();
            connect();
        }
    };

    private void setConnected(boolean nowConnected) {
        if (!bound || connected == nowConnected) return;
        connected = nowConnected;
        callback.onConnectionChanged(nowConnected);
    }

    private void post(Runnable r) {
        main.post(r);
    }
}
