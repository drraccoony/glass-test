package dev.glassrevival.glass;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.AudioManager;
import com.google.android.glass.media.Sounds;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Base64;
import android.util.Log;
import dev.glassrevival.glass.timeline.NativeTimeline;
import dev.glassrevival.hub.Hub;
import dev.glassrevival.hub.HubHost;
import dev.glassrevival.protocol.FrameIO;
import dev.glassrevival.protocol.Protocol;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Bluetooth server that the phone companion connects to. Runs in the foreground
 * so KitKat doesn't kill it, and restarts itself after boot via {@link BootReceiver}.
 */
public class LinkService extends Service {

    static final String TAG = "GlassLink";
    private static final long AUTH_TIMEOUT_MS = 10000;
    /** The phone pings every 25 s, so this much silence means the link is dead. */
    private static final long IDLE_TIMEOUT_MS = 75000;
    private static final UUID SERVICE_UUID = UUID.fromString(Protocol.SERVICE_UUID);

    private static volatile LinkService instance;

    static void start(Context context) {
        context.startService(new Intent(context, LinkService.class));
    }

    /** Tells the phone the user dismissed a notification on Glass. */
    static void sendDismiss(String key) {
        LinkService svc = instance;
        if (svc == null) return;
        try {
            JSONObject msg = new JSONObject();
            msg.put(Protocol.F_TYPE, Protocol.DISMISS);
            msg.put(Protocol.F_KEY, key);
            boolean sent = svc.sendToPeer(msg);
            Log.i(TAG, (sent ? "Sent dismiss for " : "Not connected, couldn't dismiss ") + key);
        } catch (JSONException e) {
            Log.w(TAG, "dismiss", e);
        }
    }

    /**
     * Asks the phone to send a reply through the notification's reply action.
     *
     * @return false if no phone is connected
     */
    static boolean sendReply(String key, String text) {
        LinkService svc = instance;
        if (svc == null) return false;
        try {
            JSONObject msg = new JSONObject();
            msg.put(Protocol.F_TYPE, Protocol.REPLY);
            msg.put(Protocol.F_KEY, key);
            msg.put(Protocol.F_TEXT, text);
            return svc.sendToPeer(msg);
        } catch (JSONException e) {
            Log.w(TAG, "reply", e);
            return false;
        }
    }

    /** Asks the phone for fresh weather. */
    static boolean requestWeather() {
        LinkService svc = instance;
        if (svc == null) return false;
        try {
            return svc.sendToPeer(new JSONObject().put(Protocol.F_TYPE, Protocol.WEATHER_REQUEST));
        } catch (JSONException e) {
            return false;
        }
    }

    /** Asks the phone for fresh METARs. */
    static boolean requestMetar() {
        LinkService svc = instance;
        if (svc == null) return false;
        try {
            return svc.sendToPeer(new JSONObject().put(Protocol.F_TYPE, Protocol.METAR_REQUEST));
        } catch (JSONException e) {
            return false;
        }
    }

    private static final String WEATHER_PREF = "last_weather";
    private static final String METAR_PREF = "last_metar";
    static final String PREF_WAKE = "alert_wake";
    static final String PREF_SOUND = "alert_sound";
    static final String PREF_ALERT_SECONDS = "alert_seconds";
    static final String PREF_TIMELINE = "use_timeline";

    /** How long an alert card stays up before closing itself. */
    static long alertTimeoutMs(Context context) {
        int seconds = context.getSharedPreferences("glasslink", MODE_PRIVATE).getInt(PREF_ALERT_SECONDS, 10);
        return Math.max(3, Math.min(120, seconds)) * 1000L;
    }

    private final NotificationStore store = NotificationStore.get();
    private MediaServer mediaServer;
    private MediaHandler media;
    private HubHost hub;
    /** Glass's own timeline. Null unless GlassLink is installed as a system app. */
    private NativeTimeline timeline;
    private PhoneLocation phoneLocation;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService sender = Executors.newSingleThreadExecutor();

    private BluetoothAdapter adapter;
    private volatile boolean running;
    private volatile BluetoothServerSocket server;
    private Thread acceptThread;
    private Session session; // guarded by this
    private volatile int batteryPercent = -1;
    private volatile boolean charging;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        adapter = BluetoothAdapter.getDefaultAdapter();
        startForeground(1, new Notification.Builder(this)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.service_running))
                .setSmallIcon(R.drawable.ic_launcher)
                .setOngoing(true)
                .build());
        registerReceiver(batteryReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        // Show the last forecast right away, even before the phone reconnects.
        Weather saved = Weather.parse(getSharedPreferences("glasslink", MODE_PRIVATE)
                .getString(WEATHER_PREF, null));
        if (saved != null) store.setWeather(saved);
        Metar savedMetar = Metar.parse(getSharedPreferences("glasslink", MODE_PRIVATE)
                .getString(METAR_PREF, null));
        if (savedMetar != null) store.setMetar(savedMetar);
        MediaLibrary library = new MediaLibrary(getContentResolver());
        mediaServer = new MediaServer(this, library);
        media = new MediaHandler(library, mediaServer);
        hub = new HubHost(this, new HubHost.Link() {
            @Override
            public boolean isConnected() {
                return addOnSession() != null;
            }

            @Override
            public boolean send(JSONObject frame) {
                Session s = addOnSession();
                if (s == null) return false;
                s.send(frame);
                return true;
            }
        });
        if (NativeTimeline.isAvailable(this)) {
            timeline = new NativeTimeline(this, key -> {
                // Deleted from the Glass timeline: same as dismissing it in GlassLink.
                store.remove(key);
                sendDismiss(key);
            });
            timeline.start();
            timeline.clearAll(); // leftovers from before; the phone resends on connect
            Log.i(TAG, "Glass timeline is available");
        }
        phoneLocation = new PhoneLocation(this);
        phoneLocation.ensureReady(); // register providers now, so apps find them before a fix
        running = true;
        acceptThread = new Thread(this::acceptLoop, "GlassLink-accept");
        acceptThread.start();
        Log.i(TAG, "Service started, pair code " + PairCode.get(this));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        instance = null;
        hub.setConnected(false);
        if (timeline != null) timeline.stop();
        closeQuietly(server);
        synchronized (this) {
            if (session != null) session.close();
        }
        acceptThread.interrupt();
        unregisterReceiver(batteryReceiver);
        media.shutdown();
        mediaServer.stop();
        sender.shutdownNow();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        if (!Hub.ACTION_BIND.equals(intent.getAction())) return null;
        // An add-on app bound first. Start the service too, so it keeps running after they unbind.
        start(this);
        return hub.binder();
    }

    // ---- Accepting connections ------------------------------------------------------------

    @SuppressLint("MissingPermission") // BLUETOOTH_CONNECT only exists on Android 12+; Glass is 4.4
    private void acceptLoop() {
        while (running) {
            if (adapter == null || !adapter.isEnabled()) {
                store.setLink(NotificationStore.LinkState.BLUETOOTH_OFF, null);
                if (!pause(3000)) return;
                continue;
            }
            try {
                // "Insecure" means the phone doesn't need to be bonded first. If it is
                // bonded, the link is still encrypted. The pair code guards access either way.
                server = adapter.listenUsingInsecureRfcommWithServiceRecord(
                        Protocol.SERVICE_NAME, SERVICE_UUID);
                if (!hasAuthedSession()) store.setLink(NotificationStore.LinkState.WAITING, null);
                Log.i(TAG, "Listening on RFCOMM");
                while (running) {
                    BluetoothSocket socket = server.accept();
                    Log.i(TAG, "Incoming connection from " + socket.getRemoteDevice().getAddress());
                    // Serve one phone at a time. On KitKat, calling accept() again while a
                    // session is open fails, and closing the failed server socket also kills
                    // the open session. Dead sessions are cut by the idle timeout instead.
                    Session s = startSession(socket);
                    try {
                        s.join();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            } catch (IOException e) {
                if (running) Log.w(TAG, "Server socket error, restarting", e);
            } finally {
                closeQuietly(server);
                server = null;
            }
            if (!pause(2000)) return;
        }
    }

    private synchronized Session startSession(BluetoothSocket socket) {
        if (session != null) session.close();
        session = new Session(socket);
        session.start();
        return session;
    }

    private synchronized boolean hasAuthedSession() {
        return session != null && session.authed;
    }

    private boolean sendToPeer(JSONObject msg) {
        Session s;
        synchronized (this) {
            s = session;
        }
        if (s == null || !s.authed) return false;
        s.send(msg);
        return true;
    }

    /** The current session, once it may carry add-on traffic (after welcome went out). */
    private synchronized Session addOnSession() {
        return session != null && session.welcomed ? session : null;
    }

    private void onSessionEnded(Session s) {
        synchronized (this) {
            if (session != s) return;
            session = null;
        }
        hub.setConnected(false);
        store.setLink(adapter != null && adapter.isEnabled()
                ? NotificationStore.LinkState.WAITING
                : NotificationStore.LinkState.BLUETOOTH_OFF, null);
    }

    private final class Session extends Thread implements MediaHandler.Out {
        private final BluetoothSocket socket;
        private OutputStream out;
        volatile boolean authed;
        /** Set once welcome is queued. Add-on frames wait for it, so welcome stays first. */
        volatile boolean welcomed;
        private volatile long lastReceived = android.os.SystemClock.elapsedRealtime();
        private final Runnable idleCheck = new Runnable() {
            @Override
            public void run() {
                if (android.os.SystemClock.elapsedRealtime() - lastReceived > IDLE_TIMEOUT_MS) {
                    Log.i(TAG, "No word from the phone, closing the link");
                    close();
                } else {
                    main.postDelayed(this, 15000);
                }
            }
        };

        Session(BluetoothSocket socket) {
            super("GlassLink-session");
            this.socket = socket;
        }

        @Override
        public void run() {
            Runnable authTimeout = () -> {
                if (!authed) {
                    Log.w(TAG, "Peer did not say hello in time");
                    close();
                }
            };
            main.postDelayed(authTimeout, AUTH_TIMEOUT_MS);
            main.postDelayed(idleCheck, 15000);
            try {
                InputStream in = socket.getInputStream();
                out = socket.getOutputStream();
                if (!handshake(FrameIO.read(in))) return;
                String frame;
                while ((frame = FrameIO.read(in)) != null) {
                    lastReceived = android.os.SystemClock.elapsedRealtime();
                    handle(new JSONObject(frame));
                }
                Log.i(TAG, "Peer closed the connection");
            } catch (IOException | JSONException e) {
                Log.i(TAG, "Session ended: " + e);
            } finally {
                main.removeCallbacks(authTimeout);
                main.removeCallbacks(idleCheck);
                close();
                onSessionEnded(this);
            }
        }

        private boolean handshake(String frame) throws JSONException {
            if (frame == null) return false;
            JSONObject hello = new JSONObject(frame);
            if (!Protocol.HELLO.equals(hello.optString(Protocol.F_TYPE))) {
                Log.w(TAG, "First frame was not hello");
                return false;
            }
            if (hello.optInt(Protocol.F_VERSION) != Protocol.VERSION) {
                reject(Protocol.REASON_BAD_VERSION);
                return false;
            }
            if (!Protocol.pairCodesMatch(PairCode.get(LinkService.this), hello.optString(Protocol.F_CODE))) {
                Log.w(TAG, "Wrong pair code from " + socket.getRemoteDevice().getAddress());
                reject(Protocol.REASON_BAD_CODE);
                return false;
            }
            authed = true;
            applyTimeZone(hello.optString(Protocol.F_TIMEZONE, ""));
            String peer = hello.optString(Protocol.F_NAME, "your phone");
            Log.i(TAG, "Paired with " + peer);
            // The phone resends everything that is still active, so start clean.
            store.clear();
            if (timeline != null) timeline.clearAll();
            store.setLink(NotificationStore.LinkState.CONNECTED, peer);

            JSONObject welcome = new JSONObject();
            welcome.put(Protocol.F_TYPE, Protocol.WELCOME);
            welcome.put(Protocol.F_VERSION, Protocol.VERSION);
            welcome.put(Protocol.F_NAME, Build.MODEL);
            welcome.put(Protocol.F_BATTERY, batteryPercent);
            welcome.put(Protocol.F_CHARGING, charging);
            send(welcome);
            welcomed = true;
            hub.setConnected(true);
            return true;
        }

        private void reject(String reason) throws JSONException {
            JSONObject msg = new JSONObject();
            msg.put(Protocol.F_TYPE, Protocol.REJECT);
            msg.put(Protocol.F_REASON, reason);
            writeNow(msg.toString());
            pause(300); // let the frame leave before the socket closes
        }

        private void handle(JSONObject msg) throws JSONException {
            if (msg.has(Protocol.F_CHANNEL)) {
                hub.deliver(msg); // for an add-on Glass app
                return;
            }
            String type = msg.optString(Protocol.F_TYPE);
            if (Protocol.NOTIFY.equals(type)) {
                NotificationStore.Note note = new NotificationStore.Note(
                        msg.getString(Protocol.F_KEY),
                        msg.optString(Protocol.F_PACKAGE),
                        msg.optString(Protocol.F_APP),
                        msg.optString(Protocol.F_TITLE),
                        msg.optString(Protocol.F_TEXT),
                        msg.optLong(Protocol.F_WHEN, System.currentTimeMillis()),
                        decodeIcon(msg.optString(Protocol.F_ICON, null)),
                        msg.optBoolean(Protocol.F_CAN_REPLY, false),
                        stringList(msg.optJSONArray(Protocol.F_CHOICES)));
                boolean alert = msg.optBoolean(Protocol.F_ALERT, false);
                store.upsert(note, alert);
                if (useTimeline()) {
                    android.content.SharedPreferences prefs = getSharedPreferences("glasslink", MODE_PRIVATE);
                    // Glass's home screen does the chime and the wake for timeline cards.
                    boolean notify = alert && (prefs.getBoolean(PREF_WAKE, true) || prefs.getBoolean(PREF_SOUND, true));
                    timeline.post(new NativeTimeline.Card(note.key, note.pkg, note.app, note.title,
                            note.text, note.when, note.canReply,
                            msg.optString(Protocol.F_ICON, null),
                            msg.optString(Protocol.F_AVATAR, null)), notify);
                } else if (alert) {
                    alertUser(note);
                }
            } else if (Protocol.REMOVE.equals(type)) {
                String key = msg.getString(Protocol.F_KEY);
                store.remove(key);
                if (timeline != null) timeline.remove(key);
            } else if (type.startsWith(Protocol.MEDIA_PREFIX)) {
                media.handle(this, msg);
            } else if (Protocol.SETTINGS.equals(type)) {
                getSharedPreferences("glasslink", MODE_PRIVATE).edit()
                        .putBoolean(PREF_WAKE, msg.optBoolean(Protocol.F_WAKE, true))
                        .putBoolean(PREF_SOUND, msg.optBoolean(Protocol.F_SOUND, true))
                        .putInt(PREF_ALERT_SECONDS, msg.optInt(Protocol.F_ALERT_SECONDS, 10))
                        .putBoolean(PREF_TIMELINE, msg.optBoolean(Protocol.F_TIMELINE, true))
                        .apply();
                // Turned off: take our cards back out. Turned on: the phone reconnects to resend.
                if (timeline != null && !msg.optBoolean(Protocol.F_TIMELINE, true)) timeline.clearAll();
                Log.i(TAG, "Alert settings updated: " + msg);
            } else if (Protocol.METAR.equals(type)) {
                String json = msg.toString();
                Metar m = Metar.parse(json);
                if (m != null) {
                    store.setMetar(m);
                    getSharedPreferences("glasslink", MODE_PRIVATE).edit()
                            .putString(METAR_PREF, json).apply();
                }
            } else if (Protocol.LOCATION.equals(type)) {
                phoneLocation.update(msg);
            } else if (Protocol.CLOCK.equals(type)) {
                applyTimeZone(msg.optString(Protocol.F_TIMEZONE, ""));
            } else if (Protocol.WEATHER.equals(type)) {
                String json = msg.toString();
                Weather w = Weather.parse(json);
                if (w != null) {
                    store.setWeather(w);
                    getSharedPreferences("glasslink", MODE_PRIVATE).edit()
                            .putString(WEATHER_PREF, json).apply();
                }
            } else if (Protocol.REPLY_RESULT.equals(type)) {
                store.replyResult(msg.getString(Protocol.F_KEY), msg.optBoolean(Protocol.F_OK, false),
                        msg.optString(Protocol.F_ERROR, ""));
            } else if (Protocol.PING.equals(type)) {
                JSONObject pong = new JSONObject();
                pong.put(Protocol.F_TYPE, Protocol.PONG);
                send(pong);
            }
            // Unknown types are ignored so newer phones can talk to older Glass builds.
        }

        @Override
        public boolean sendNow(JSONObject msg) {
            if (!authed) return false;
            return writeNow(msg.toString());
        }

        @Override
        public void send(JSONObject msg) {
            final String payload = msg.toString();
            try {
                sender.execute(() -> writeNow(payload));
            } catch (java.util.concurrent.RejectedExecutionException ignored) {
                // service is shutting down
            }
        }

        private synchronized boolean writeNow(String payload) {
            if (out == null) return false;
            try {
                FrameIO.write(out, payload);
                return true;
            } catch (IOException e) {
                Log.i(TAG, "Write failed: " + e);
                close();
                return false;
            }
        }

        void close() {
            closeQuietly(socket);
        }
    }

    // ---- Alerts ---------------------------------------------------------------------------

    /** True when notifications go to Glass's own timeline instead of GlassLink's pop-up. */
    private boolean useTimeline() {
        return timeline != null
                && getSharedPreferences("glasslink", MODE_PRIVATE).getBoolean(PREF_TIMELINE, true);
    }

    private void alertUser(final NotificationStore.Note note) {
        main.post(() -> {
            android.content.SharedPreferences prefs = getSharedPreferences("glasslink", MODE_PRIVATE);
            boolean wake = prefs.getBoolean(PREF_WAKE, true);
            boolean sound = prefs.getBoolean(PREF_SOUND, true);
            // The same chime Glass plays for its own notifications.
            if (sound) playGlassSound(Sounds.NOTIFICATION);
            if (!wake) return; // just add it to the card list
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            @SuppressWarnings("deprecation")
            PowerManager.WakeLock wakeLock = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                    | PowerManager.ACQUIRE_CAUSES_WAKEUP | PowerManager.ON_AFTER_RELEASE, "GlassLink:alert");
            wakeLock.acquire(5000);
            Intent show = new Intent(this, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(MainActivity.EXTRA_ALERT_KEY, note.key);
            startActivity(show);
        });
    }

    private static java.util.List<String> stringList(org.json.JSONArray array) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (array == null) return out;
        for (int i = 0; i < array.length() && out.size() < Protocol.MAX_CHOICES; i++) {
            String s = array.optString(i, "").trim();
            if (!s.isEmpty()) out.add(s);
        }
        return out;
    }

    /**
     * Glass has no cell radio, so its automatic time zone never updates. MyGlass used to
     * set it from the phone; now GlassLink does.
     */
    private void applyTimeZone(String tz) {
        if (tz.isEmpty() || tz.equals(java.util.TimeZone.getDefault().getID())) return;
        if (!java.util.Arrays.asList(java.util.TimeZone.getAvailableIDs()).contains(tz)) {
            Log.w(TAG, "Unknown time zone from phone: " + tz);
            return;
        }
        try {
            ((android.app.AlarmManager) getSystemService(ALARM_SERVICE)).setTimeZone(tz);
            Log.i(TAG, "Time zone set to " + tz);
        } catch (RuntimeException e) {
            Log.w(TAG, "Couldn't set time zone", e);
        }
    }

    @SuppressLint("WrongConstant") // Glass sound IDs extend AudioManager's
    private void playGlassSound(int sound) {
        ((AudioManager) getSystemService(AUDIO_SERVICE)).playSoundEffect(sound);
    }

    private static Bitmap decodeIcon(String base64) {
        if (base64 == null || base64.isEmpty()) return null;
        try {
            byte[] png = Base64.decode(base64, Base64.DEFAULT);
            return BitmapFactory.decodeByteArray(png, 0, png.length);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // ---- Battery --------------------------------------------------------------------------

    private final BroadcastReceiver batteryReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            int status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            int percent = level < 0 || scale <= 0 ? -1 : Math.round(level * 100f / scale);
            boolean nowCharging = status == BatteryManager.BATTERY_STATUS_CHARGING
                    || status == BatteryManager.BATTERY_STATUS_FULL;
            if (percent == batteryPercent && nowCharging == charging) return;
            batteryPercent = percent;
            charging = nowCharging;
            try {
                JSONObject msg = new JSONObject();
                msg.put(Protocol.F_TYPE, Protocol.STATUS);
                msg.put(Protocol.F_BATTERY, percent);
                msg.put(Protocol.F_CHARGING, nowCharging);
                sendToPeer(msg);
            } catch (JSONException e) {
                Log.w(TAG, "battery", e);
            }
        }
    };

    // ---- Helpers --------------------------------------------------------------------------

    private static boolean pause(long ms) {
        try {
            Thread.sleep(ms);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static void closeQuietly(Closeable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (IOException ignored) {
            // nothing to do
        }
    }
}
