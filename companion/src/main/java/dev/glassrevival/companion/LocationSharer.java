package dev.glassrevival.companion;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.location.LocationRequest;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import dev.glassrevival.protocol.Protocol;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Sends the phone's location to Glass while they're linked. Glass has no GPS; MyGlass used
 * to do this. On Glass the fixes feed Android's "gps" and "network" providers, so the
 * camera's geotags, Glass's own location service and add-on apps all see them.
 */
final class LocationSharer implements LinkManager.GlassHook {

    /** About every 30 s on balanced power: enough for geotags without draining the phone. */
    private static final long INTERVAL_MS = 30_000;
    private static final float MIN_DISTANCE_M = 10;

    private final Context context;
    private final Prefs prefs;
    private final LinkManager link;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final LocationListener listener = this::send;
    private boolean active; // main thread only

    LocationSharer(Context context, Prefs prefs, LinkManager link) {
        this.context = context.getApplicationContext();
        this.prefs = prefs;
        this.link = link;
        link.addListener(() -> main.post(this::update)); // start or stop with the link
    }

    @Override
    public void onGlassConnected() {
        main.post(() -> {
            update();
            if (active) sendLastKnown(); // Glass gets a position right away
        });
    }

    @Override
    public void onGlassRequest(String type) {
        // Glass doesn't ask; fixes are pushed while linked.
    }

    /** Call after the setting or the location permission changes. */
    void settingsChanged() {
        main.post(this::update);
    }

    static boolean hasPermission(Context c) {
        return c.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    static boolean hasPrecise(Context c) {
        return c.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void update() {
        boolean want = prefs.shareLocation() && link.isConnected() && hasPermission(context);
        if (want && !active) start();
        else if (!want && active) stop();
    }

    private void start() {
        LocationManager lm = context.getSystemService(LocationManager.class);
        String provider = provider(lm);
        if (provider == null) {
            Log.i(LinkManager.TAG, "No location provider is on; not sharing location");
            return;
        }
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                LocationRequest request = new LocationRequest.Builder(INTERVAL_MS)
                        .setQuality(LocationRequest.QUALITY_BALANCED_POWER_ACCURACY)
                        .setMinUpdateDistanceMeters(MIN_DISTANCE_M)
                        .build();
                lm.requestLocationUpdates(provider, request, context.getMainExecutor(), listener);
            } else {
                lm.requestLocationUpdates(provider, INTERVAL_MS, MIN_DISTANCE_M, listener, Looper.getMainLooper());
            }
            active = true;
            Log.i(LinkManager.TAG, "Sharing location with Glass (" + provider + ")");
        } catch (SecurityException | IllegalArgumentException e) {
            Log.w(LinkManager.TAG, "Couldn't start location updates: " + e);
        }
    }

    private void stop() {
        context.getSystemService(LocationManager.class).removeUpdates(listener);
        active = false;
        Log.i(LinkManager.TAG, "Stopped sharing location");
    }

    private static String provider(LocationManager lm) {
        if (Build.VERSION.SDK_INT >= 31 && lm.hasProvider(LocationManager.FUSED_PROVIDER)
                && lm.isProviderEnabled(LocationManager.FUSED_PROVIDER)) {
            return LocationManager.FUSED_PROVIDER;
        }
        if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) return LocationManager.NETWORK_PROVIDER;
        if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) return LocationManager.GPS_PROVIDER;
        return null;
    }

    private void sendLastKnown() {
        LocationManager lm = context.getSystemService(LocationManager.class);
        Location best = null;
        try {
            for (String p : lm.getProviders(true)) {
                Location l = lm.getLastKnownLocation(p);
                if (l != null && (best == null || l.getElapsedRealtimeNanos() > best.getElapsedRealtimeNanos())) {
                    best = l;
                }
            }
        } catch (SecurityException e) {
            return;
        }
        // Old fixes would mislabel photos; let the next real update do it.
        if (best != null && ageMs(best) < 10 * 60_000) send(best);
    }

    private void send(Location l) {
        try {
            JSONObject msg = new JSONObject()
                    .put(Protocol.F_TYPE, Protocol.LOCATION)
                    .put(Protocol.F_LAT, l.getLatitude())
                    .put(Protocol.F_LON, l.getLongitude())
                    .put(Protocol.F_ACCURACY, l.hasAccuracy() ? l.getAccuracy() : 100)
                    // Glass's clock may differ a little, so send the age rather than a time.
                    .put(Protocol.F_AGE, ageMs(l));
            if (l.hasAltitude()) msg.put(Protocol.F_ALTITUDE, l.getAltitude());
            if (l.hasSpeed()) msg.put(Protocol.F_SPEED, l.getSpeed());
            if (l.hasBearing()) msg.put(Protocol.F_BEARING, l.getBearing());
            link.send(msg);
        } catch (JSONException ignored) {
            // only for NaN, which Location never holds
        }
    }

    private static long ageMs(Location l) {
        return Math.max(0, (SystemClock.elapsedRealtimeNanos() - l.getElapsedRealtimeNanos()) / 1_000_000);
    }
}
