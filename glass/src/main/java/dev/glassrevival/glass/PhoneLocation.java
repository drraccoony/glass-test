package dev.glassrevival.glass;

import android.content.Context;
import android.location.Criteria;
import android.location.Location;
import android.location.LocationManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import dev.glassrevival.protocol.Protocol;
import org.json.JSONObject;

/**
 * Feeds the phone's location into Android on Glass, which has no GPS.
 *
 * <p>GlassLink registers test ("mock") providers named "gps" and "network" and sets
 * each fix the phone sends. Every app that asks Android for location then gets the
 * phone's position. That includes the camera's geotags and add-on apps. GlassHome's own
 * location service also uses it: its "network" side always reads Android's network
 * provider, and its "gps" side does when the Labs property persist.lab.gps_enable_local is true.
 *
 * <p>Mock providers need ACCESS_MOCK_LOCATION and the secure setting "mock_location" = 1.
 * As a system app GlassLink can set that itself. The install script also sets it.
 */
final class PhoneLocation {

    private static final String[] PROVIDERS = {LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER};

    private final Context context;
    private final LocationManager lm;
    private boolean ready;
    private boolean warned;

    PhoneLocation(Context context) {
        this.context = context.getApplicationContext();
        this.lm = (LocationManager) this.context.getSystemService(Context.LOCATION_SERVICE);
    }

    /** Registers the providers. Safe to call again; later calls do nothing once it worked. */
    synchronized boolean ensureReady() {
        if (ready) return true;
        try {
            allowMockLocations();
            for (String p : PROVIDERS) {
                try {
                    boolean gps = LocationManager.GPS_PROVIDER.equals(p);
                    // Replaces the real provider of that name (Glass has no GPS, and its
                    // Wi-Fi network location is off) until removeTestProvider or a reboot.
                    lm.addTestProvider(p, !gps, gps, false, false, true, true, true,
                            Criteria.POWER_LOW, gps ? Criteria.ACCURACY_FINE : Criteria.ACCURACY_COARSE);
                } catch (IllegalArgumentException alreadyThere) {
                    // added earlier since boot; reuse it
                }
                lm.setTestProviderEnabled(p, true);
            }
            ready = true;
            Log.i(LinkService.TAG, "Phone location providers ready");
        } catch (SecurityException e) {
            if (!warned) {
                Log.w(LinkService.TAG, "Can't provide location to Glass apps: " + e.getMessage()
                        + ". Run scripts/glasslink.sh install-glass-system.");
                warned = true;
            }
        }
        return ready;
    }

    /** One fix from the phone (a {@link Protocol#LOCATION} message). */
    void update(JSONObject msg) {
        if (!ensureReady()) return;
        if (!msg.has(Protocol.F_LAT) || !msg.has(Protocol.F_LON)) return;
        long age = Math.max(0, msg.optLong(Protocol.F_AGE, 0));
        for (String p : PROVIDERS) {
            Location l = new Location(p);
            l.setLatitude(msg.optDouble(Protocol.F_LAT));
            l.setLongitude(msg.optDouble(Protocol.F_LON));
            l.setAccuracy((float) msg.optDouble(Protocol.F_ACCURACY, 100));
            // Android 4.2+ rejects a mock fix without both clocks.
            l.setTime(System.currentTimeMillis() - age);
            l.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos() - age * 1_000_000L);
            if (msg.has(Protocol.F_ALTITUDE)) l.setAltitude(msg.optDouble(Protocol.F_ALTITUDE));
            if (msg.has(Protocol.F_SPEED)) l.setSpeed((float) msg.optDouble(Protocol.F_SPEED));
            if (msg.has(Protocol.F_BEARING)) l.setBearing((float) msg.optDouble(Protocol.F_BEARING));
            try {
                lm.setTestProviderLocation(p, l);
            } catch (RuntimeException e) {
                Log.w(LinkService.TAG, "Couldn't set location on " + p + ": " + e);
                synchronized (this) {
                    ready = false; // e.g. the mock setting was turned off; set up again next time
                }
            }
        }
    }

    private void allowMockLocations() {
        if (Settings.Secure.getInt(context.getContentResolver(), Settings.Secure.ALLOW_MOCK_LOCATION, 0) == 1) return;
        try {
            Settings.Secure.putInt(context.getContentResolver(), Settings.Secure.ALLOW_MOCK_LOCATION, 1);
        } catch (SecurityException e) {
            // Not a system app; addTestProvider below reports the problem.
        }
    }
}
