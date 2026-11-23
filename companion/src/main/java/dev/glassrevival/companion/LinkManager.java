package dev.glassrevival.companion;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import dev.glassrevival.protocol.FrameIO;
import dev.glassrevival.protocol.Protocol;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Owns the Bluetooth connection to Glass: connects, says hello with the pair code,
 * keeps the link alive with pings and reconnects with backoff when it drops.
 */
final class LinkManager {

    static final String TAG = "GlassLink";
    private static final UUID SERVICE_UUID = UUID.fromString(Protocol.SERVICE_UUID);
    private static final long PING_EVERY_MS = 25000;
    private static final long DEAD_AFTER_MS = 70000;
    private static final long MIN_BACKOFF_MS = 2000;
    private static final long MAX_BACKOFF_MS = 60000;

    enum State { OFF, NOT_CONFIGURED, NO_PERMISSION, BLUETOOTH_OFF, CONNECTING, CONNECTED, REJECTED }

    interface Listener {
        /** Called on a background thread. */
        void onLinkChanged();
    }

    /** The notification listener, which reacts to link events. */
    interface Peer {
        void onGlassConnected();

        void onGlassDismissed(String key);

        /** @return null if the reply was handed to the app, otherwise a short reason */
        String onGlassReply(String key, String text);
    }

    /** Data sources (weather, METARs), told when Glass connects or asks for an update. */
    interface GlassHook {
        void onGlassConnected();

        /** @param type the request message type, e.g. {@link Protocol#WEATHER_REQUEST} */
        void onGlassRequest(String type);
    }

    private final CopyOnWriteArrayList<GlassHook> hooks = new CopyOnWriteArrayList<>();

    /** Receives media_* replies from Glass. Called on the link's reader thread. */
    interface MessageSink {
        void onMessage(JSONObject msg);
    }

    private final CopyOnWriteArrayList<MessageSink> sinks = new CopyOnWriteArrayList<>();

    void addSink(MessageSink sink) {
        sinks.add(sink);
    }

    private final Context context;
    private final Prefs prefs;
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private final ExecutorService sender = Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
    private final Object wakeLock = new Object();
    private final Object writeLock = new Object();

    private volatile Peer peer;
    private volatile State state = State.OFF;
    private volatile String detail = "";
    private volatile String glassName;
    private volatile int glassBattery = -1;
    private volatile boolean glassCharging;

    private volatile boolean running;
    private Thread worker;
    private boolean woken; // guarded by wakeLock
    private volatile BluetoothSocket socket;
    private volatile OutputStream out;
    private volatile long lastReceived;
    private volatile boolean sessionReachedGlass;

    LinkManager(Context context, Prefs prefs) {
        this.context = context.getApplicationContext();
        this.prefs = prefs;
    }

    // ---- Public API -----------------------------------------------------------------------

    synchronized void start() {
        if (worker != null && worker.isAlive()) return;
        running = true;
        worker = new Thread(this::loop, "GlassLink-link");
        worker.start();
    }

    synchronized void stop() {
        running = false;
        closeSocket();
        poke();
        worker = null;
        setState(State.OFF, "");
    }

    /** Drop the current connection (if any) and try again right away, e.g. after settings change. */
    void reconnectNow() {
        closeSocket();
        poke();
    }

    /** Wake the reconnect loop without dropping a live connection, e.g. when Bluetooth turns on. */
    void poke() {
        synchronized (wakeLock) {
            woken = true;
            wakeLock.notifyAll();
        }
    }

    boolean isConnected() {
        return state == State.CONNECTED;
    }

    State state() {
        return state;
    }

    String detail() {
        return detail;
    }

    String glassName() {
        return glassName;
    }

    int glassBattery() {
        return glassBattery;
    }

    boolean glassCharging() {
        return glassCharging;
    }

    void addHook(GlassHook hook) {
        hooks.add(hook);
    }

    void setPeer(Peer peer) {
        this.peer = peer;
    }

    void clearPeer(Peer peer) {
        if (this.peer == peer) this.peer = null;
    }

    void addListener(Listener l) {
        listeners.add(l);
    }

    void removeListener(Listener l) {
        listeners.remove(l);
    }

    /** Tells Glass the phone's time zone changed, e.g. after travelling. */
    void sendTimeZone() {
        try {
            send(new JSONObject().put(Protocol.F_TYPE, Protocol.CLOCK)
                    .put(Protocol.F_TIMEZONE, java.util.TimeZone.getDefault().getID()));
        } catch (JSONException ignored) {
            // cannot happen
        }
    }

    /** Queues a message for Glass. Dropped silently when not connected. */
    void send(JSONObject msg) {
        if (!isConnected()) return;
        final String payload = msg.toString();
        try {
            sender.execute(() -> write(payload));
        } catch (RejectedExecutionException ignored) {
            // shutting down
        }
    }

    // ---- Connection loop ------------------------------------------------------------------

    private void loop() {
        long backoff = MIN_BACKOFF_MS;
        while (running) {
            State blocked = precheck();
            if (blocked != null) {
                setState(blocked, "");
                waitForPoke(15000);
                continue;
            }
            setState(State.CONNECTING, detail);
            sessionReachedGlass = false;
            try {
                runSession();
            } catch (IOException | JSONException | SecurityException e) {
                Log.i(TAG, "Link ended: " + e);
                if (running && state != State.REJECTED) setState(State.CONNECTING, friendly(e));
            } finally {
                closeSocket();
            }
            if (!running) break;
            if (state == State.REJECTED) {
                // Wrong code or version. Retrying won't help until the user changes something.
                waitForPoke(10 * 60 * 1000);
                continue;
            }
            if (sessionReachedGlass) backoff = MIN_BACKOFF_MS;
            if (state == State.CONNECTED) setState(State.CONNECTING, "Connection lost, retrying");
            waitForPoke(backoff);
            backoff = Math.min(backoff * 2, MAX_BACKOFF_MS);
        }
    }

    private State precheck() {
        if (!prefs.isConfigured()) return State.NOT_CONFIGURED;
        if (Build.VERSION.SDK_INT >= 31 && context.checkSelfPermission(
                Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            return State.NO_PERMISSION;
        }
        BluetoothAdapter adapter = adapter();
        if (adapter == null || !adapter.isEnabled()) return State.BLUETOOTH_OFF;
        return null;
    }

    @SuppressLint("MissingPermission") // precheck() verified BLUETOOTH_CONNECT; SecurityException is caught
    private void runSession() throws IOException, JSONException {
        BluetoothDevice device = adapter().getRemoteDevice(prefs.glassAddress().toUpperCase(java.util.Locale.ROOT));
        // Bonded devices get an authenticated, encrypted link. Unbonded ones connect
        // without a pairing dialog; Glass still checks the pair code.
        BluetoothSocket s = device.getBondState() == BluetoothDevice.BOND_BONDED
                ? device.createRfcommSocketToServiceRecord(SERVICE_UUID)
                : device.createInsecureRfcommSocketToServiceRecord(SERVICE_UUID);
        socket = s;
        s.connect();
        InputStream in = s.getInputStream();
        out = s.getOutputStream();

        JSONObject hello = new JSONObject();
        hello.put(Protocol.F_TYPE, Protocol.HELLO);
        hello.put(Protocol.F_VERSION, Protocol.VERSION);
        hello.put(Protocol.F_CODE, prefs.pairCode());
        hello.put(Protocol.F_NAME, phoneName());
        hello.put(Protocol.F_TIMEZONE, java.util.TimeZone.getDefault().getID());
        write(hello.toString());

        String first = FrameIO.read(in);
        if (first == null) throw new IOException("Glass closed the connection");
        JSONObject reply = new JSONObject(first);
        String type = reply.optString(Protocol.F_TYPE);
        if (Protocol.REJECT.equals(type)) {
            String reason = reply.optString(Protocol.F_REASON);
            setState(State.REJECTED, Protocol.REASON_BAD_CODE.equals(reason)
                    ? "Glass rejected the pair code"
                    : "Glass app version doesn't match, update both apps");
            return;
        }
        if (!Protocol.WELCOME.equals(type)) throw new IOException("Unexpected reply: " + type);

        sessionReachedGlass = true;
        glassName = reply.optString(Protocol.F_NAME, "Glass");
        glassBattery = reply.optInt(Protocol.F_BATTERY, -1);
        glassCharging = reply.optBoolean(Protocol.F_CHARGING, false);
        lastReceived = SystemClock.elapsedRealtime();
        setState(State.CONNECTED, "");
        Log.i(TAG, "Connected to " + glassName);

        ScheduledFuture<?> keepAlive = timer.scheduleWithFixedDelay(this::keepAlive,
                PING_EVERY_MS, PING_EVERY_MS, TimeUnit.MILLISECONDS);
        try {
            Peer p = peer;
            if (p != null) p.onGlassConnected();
            for (GlassHook h : hooks) h.onGlassConnected();
            String frame;
            while ((frame = FrameIO.read(in)) != null) {
                lastReceived = SystemClock.elapsedRealtime();
                handle(new JSONObject(frame));
            }
        } finally {
            keepAlive.cancel(false);
        }
    }

    private void handle(JSONObject msg) {
        String type = msg.optString(Protocol.F_TYPE);
        if (type.startsWith(Protocol.MEDIA_PREFIX)) {
            for (MessageSink s : sinks) s.onMessage(msg);
            return;
        }
        if (Protocol.DISMISS.equals(type)) {
            Peer p = peer;
            String key = msg.optString(Protocol.F_KEY, null);
            if (p != null && key != null) p.onGlassDismissed(key);
        } else if (Protocol.REPLY.equals(type)) {
            String key = msg.optString(Protocol.F_KEY, null);
            String text = msg.optString(Protocol.F_TEXT, "").trim();
            Peer p = peer;
            String error;
            if (key == null || text.isEmpty()) error = "Empty reply";
            else if (p == null) error = "Notification access is off on your phone";
            else error = p.onGlassReply(key, text);
            try {
                JSONObject result = new JSONObject();
                result.put(Protocol.F_TYPE, Protocol.REPLY_RESULT);
                result.put(Protocol.F_KEY, key == null ? "" : key);
                result.put(Protocol.F_OK, error == null);
                if (error != null) result.put(Protocol.F_ERROR, error);
                send(result);
            } catch (JSONException ignored) {
                // cannot happen
            }
        } else if (Protocol.WEATHER_REQUEST.equals(type) || Protocol.METAR_REQUEST.equals(type)) {
            for (GlassHook h : hooks) h.onGlassRequest(type);
        } else if (Protocol.STATUS.equals(type)) {
            glassBattery = msg.optInt(Protocol.F_BATTERY, glassBattery);
            glassCharging = msg.optBoolean(Protocol.F_CHARGING, glassCharging);
            fire();
        } else if (Protocol.PING.equals(type)) {
            try {
                send(new JSONObject().put(Protocol.F_TYPE, Protocol.PONG));
            } catch (JSONException ignored) {
                // cannot happen
            }
        }
    }

    private void keepAlive() {
        if (SystemClock.elapsedRealtime() - lastReceived > DEAD_AFTER_MS) {
            Log.i(TAG, "No reply from Glass, dropping link");
            closeSocket();
            return;
        }
        try {
            send(new JSONObject().put(Protocol.F_TYPE, Protocol.PING));
        } catch (JSONException ignored) {
            // cannot happen
        }
    }

    private void write(String payload) {
        synchronized (writeLock) {
            OutputStream o = out;
            if (o == null) return;
            try {
                FrameIO.write(o, payload);
            } catch (IOException e) {
                Log.i(TAG, "Write failed: " + e);
                closeSocket();
            }
        }
    }

    private void closeSocket() {
        BluetoothSocket s = socket;
        socket = null;
        out = null;
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
                // already closed
            }
        }
    }

    private void waitForPoke(long ms) {
        synchronized (wakeLock) {
            long deadline = SystemClock.elapsedRealtime() + ms;
            while (!woken && running) {
                long left = deadline - SystemClock.elapsedRealtime();
                if (left <= 0) break;
                try {
                    wakeLock.wait(left);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            woken = false;
        }
    }

    // ---- Helpers --------------------------------------------------------------------------

    private void setState(State s, String d) {
        state = s;
        detail = d == null ? "" : d;
        fire();
    }

    private void fire() {
        for (Listener l : listeners) l.onLinkChanged();
    }

    private BluetoothAdapter adapter() {
        BluetoothManager bm = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        return bm == null ? null : bm.getAdapter();
    }

    private String phoneName() {
        String name = Settings.Global.getString(context.getContentResolver(), Settings.Global.DEVICE_NAME);
        return name == null || name.isEmpty() ? Build.MODEL : name;
    }

    private static String friendly(Exception e) {
        String m = String.valueOf(e.getMessage());
        if (m.contains("read failed") || m.contains("timeout") || m.contains("Service discovery")) {
            return "Glass not reachable. Is GlassLink open on Glass and Bluetooth on?";
        }
        return m;
    }
}
