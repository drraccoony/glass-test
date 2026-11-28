package dev.glassrevival.companion;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;
import dev.glassrevival.protocol.Protocol;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * The Glass add-on apps for the Apps tab. Their APKs are bundled in this app
 * (assets/addons/&lt;package&gt;.apk); Glass reports what's installed, and installs or removes
 * them when asked. See {@link Protocol#APPS}.
 *
 * <p>The last report from Glass is kept, so the tab and the data sources know what's on
 * Glass while it's away. Until Glass has reported once, every add-on counts as installed,
 * so nothing is held back by mistake.
 */
final class AddOnManager implements LinkManager.MessageSink {

    static final class AddOn {
        final String pkg;
        final String name;
        final String summary;
        final String script; // glasslink.sh command, for a Glass without the system install

        AddOn(String pkg, String name, String summary, String script) {
            this.pkg = pkg;
            this.name = name;
            this.summary = summary;
            this.script = script;
        }
    }

    static final AddOn WEATHER = new AddOn("dev.glassrevival.weather", "Weather",
            "The forecast for where you are, or a place you pick: now, today's high and low,"
                    + " rain, wind and the next few hours. Say \"ok glass, show me the weather\".",
            "install-weather");
    static final AddOn AIRPORTS = new AddOn("dev.glassrevival.airports", "Airports",
            "METARs for your airports, with decoded conditions, density altitude, runway winds and"
                    + " alerts when conditions change. Say \"ok glass, show airports\".",
            "install-airports");
    static final AddOn NAV = new AddOn("dev.glassrevival.nav", "Navigation",
            "Google Maps turn-by-turn on a card next to the clock, with a chime before each turn."
                    + " Starts by itself when you navigate in Maps.",
            "install-nav");
    static final AddOn RECIPES = new AddOn("dev.glassrevival.recipes", "Recipes",
            "Step through a recipe hands-free. Share a recipe page or text on the phone and choose"
                    + " Send to Glass.",
            "install-recipes");
    static final List<AddOn> ALL = Collections.unmodifiableList(Arrays.asList(WEATHER, AIRPORTS, NAV, RECIPES));

    enum Status { UNKNOWN, NOT_INSTALLED, INSTALLED, UPDATE }

    interface Listener {
        /** Something on the Apps tab changed. Main thread. */
        void onAddOnsChanged();
    }

    interface InstallListener {
        /** An add-on appeared on Glass. Main thread. */
        void onInstalled(String pkg);
    }

    private static final String TAG = "GlassLink";
    private static final String PREF = "addons_reported";
    private static final long TIMEOUT_MS = 3 * 60 * 1000L; // an APK crosses Bluetooth at about 5 KB/s

    private final Context context;
    private final LinkManager link;
    private final SharedPreferences sp;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final List<InstallListener> installListeners = new CopyOnWriteArrayList<>();
    private final Map<String, String> bundledSha = new HashMap<>();

    // Main thread only.
    private boolean reported;          // Glass has reported at least once
    private boolean canInstall;
    private final Map<String, String> installedSha = new HashMap<>();
    private final Map<String, String> installedVersion = new HashMap<>();
    private final Map<String, String> busy = new HashMap<>();    // pkg -> "install" / "remove"
    private final Map<String, String> errors = new HashMap<>();

    AddOnManager(Context context, LinkManager link) {
        this.context = context.getApplicationContext();
        this.link = link;
        sp = context.getSharedPreferences("glasslink", Context.MODE_PRIVATE);
        String saved = sp.getString(PREF, null);
        if (saved != null) {
            try {
                apply(new JSONObject(saved), false);
            } catch (JSONException ignored) {
                // replaced when Glass reports again
            }
        }
        link.addListener(() -> main.post(() -> {
            if (!link.isConnected() && !busy.isEmpty()) {
                busy.clear();
                fire();
            }
        }));
    }

    void addListener(Listener l) {
        listeners.add(l);
    }

    void removeListener(Listener l) {
        listeners.remove(l);
    }

    void addInstallListener(InstallListener l) {
        installListeners.add(l);
    }

    // ---- State (main thread) --------------------------------------------------------------

    /** False only when Glass reported that the app isn't there. Safe from any thread. */
    synchronized boolean isInstalled(AddOn a) {
        return !reported || installedSha.containsKey(a.pkg);
    }

    synchronized Status status(AddOn a) {
        if (!reported) return Status.UNKNOWN;
        String sha = installedSha.get(a.pkg);
        if (sha == null) return Status.NOT_INSTALLED;
        String bundled = bundledSha(a.pkg);
        return bundled != null && !sha.isEmpty() && !bundled.equals(sha) ? Status.UPDATE : Status.INSTALLED;
    }

    synchronized String version(AddOn a) {
        return installedVersion.get(a.pkg);
    }

    /** Whether GlassLink on Glass may install and remove apps (its system install). */
    synchronized boolean canInstall() {
        return canInstall;
    }

    String busy(AddOn a) {
        return busy.get(a.pkg);
    }

    String error(AddOn a) {
        return errors.get(a.pkg);
    }

    // ---- Actions (main thread) ------------------------------------------------------------

    void install(AddOn a) {
        if (busy.containsKey(a.pkg)) return;
        errors.remove(a.pkg);
        byte[] apk = bundled(a.pkg);
        if (apk == null) {
            errors.put(a.pkg, "This build of GlassLink doesn't include it");
            fire();
            return;
        }
        try {
            send(new JSONObject().put(Protocol.F_TYPE, Protocol.APP_INSTALL)
                    .put(Protocol.F_PKG, a.pkg)
                    .put(Protocol.F_DATA, Base64.encodeToString(apk, Base64.NO_WRAP)), a, "install");
        } catch (JSONException ignored) {
            // cannot happen
        }
    }

    void remove(AddOn a) {
        if (busy.containsKey(a.pkg)) return;
        errors.remove(a.pkg);
        try {
            send(new JSONObject().put(Protocol.F_TYPE, Protocol.APP_REMOVE).put(Protocol.F_PKG, a.pkg), a, "remove");
        } catch (JSONException ignored) {
            // cannot happen
        }
    }

    private void send(JSONObject msg, AddOn a, String op) {
        if (!link.isConnected()) {
            errors.put(a.pkg, "Glass isn't connected");
            fire();
            return;
        }
        busy.put(a.pkg, op);
        fire();
        link.send(msg);
        main.postDelayed(() -> {
            if (op.equals(busy.get(a.pkg))) {
                busy.remove(a.pkg);
                errors.put(a.pkg, "No answer from Glass");
                fire();
            }
        }, TIMEOUT_MS);
    }

    // ---- From Glass (link thread) ---------------------------------------------------------

    @Override
    public void onMessage(JSONObject msg) {
        String type = msg.optString(Protocol.F_TYPE);
        if (Protocol.APPS.equals(type)) {
            main.post(() -> apply(msg, true));
        } else if (Protocol.APP_RESULT.equals(type)) {
            main.post(() -> {
                String pkg = msg.optString(Protocol.F_PKG);
                busy.remove(pkg);
                if (msg.optBoolean(Protocol.F_OK)) errors.remove(pkg);
                else errors.put(pkg, msg.optString(Protocol.F_ERROR, "Glass couldn't do it"));
                fire();
            });
        }
    }

    private void apply(JSONObject msg, boolean fresh) {
        List<String> added = new java.util.ArrayList<>();
        synchronized (this) {
            boolean wasReported = reported;
            Map<String, String> before = new HashMap<>(installedSha);
            reported = true;
            canInstall = msg.optBoolean(Protocol.F_CAN_INSTALL);
            installedSha.clear();
            installedVersion.clear();
            JSONArray items = msg.optJSONArray(Protocol.F_ITEMS);
            for (int i = 0; items != null && i < items.length(); i++) {
                JSONObject o = items.optJSONObject(i);
                if (o == null) continue;
                String pkg = o.optString(Protocol.F_PKG);
                installedSha.put(pkg, o.optString(Protocol.F_SHA));
                installedVersion.put(pkg, o.optString(Protocol.F_VERSION));
                if (fresh && wasReported && !before.containsKey(pkg)) added.add(pkg);
            }
        }
        if (fresh) {
            sp.edit().putString(PREF, msg.toString()).apply();
            Log.i(TAG, "Glass add-ons: " + installedSha.keySet() + (canInstall ? "" : " (can't install)"));
        }
        fire();
        for (String pkg : added) for (InstallListener l : installListeners) l.onInstalled(pkg);
    }

    private void fire() {
        for (Listener l : listeners) l.onAddOnsChanged();
    }

    // ---- Bundled APKs ---------------------------------------------------------------------

    private byte[] bundled(String pkg) {
        try (InputStream in = context.getAssets().open("addons/" + pkg + ".apk")) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        } catch (IOException e) {
            return null;
        }
    }

    private synchronized String bundledSha(String pkg) {
        if (bundledSha.containsKey(pkg)) return bundledSha.get(pkg);
        String sha = null;
        byte[] apk = bundled(pkg);
        if (apk != null) {
            try {
                StringBuilder sb = new StringBuilder();
                for (byte b : MessageDigest.getInstance("SHA-1").digest(apk)) sb.append(String.format("%02x", b));
                sha = sb.toString();
            } catch (NoSuchAlgorithmException ignored) {
                // every Android has SHA-1
            }
        }
        bundledSha.put(pkg, sha);
        return sha;
    }
}
