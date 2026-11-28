package dev.glassrevival.airports;

import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.os.PowerManager;
import com.google.android.glass.media.Sounds;
import dev.glassrevival.hub.HubReceiver;
import org.json.JSONObject;

/**
 * New METARs while the app isn't open. Usually they're just kept for next time. When
 * conditions changed at an airport (the message names it in "alert"), Glass chimes,
 * wakes and opens that airport.
 */
public class MetarReceiver extends HubReceiver {

    @Override
    protected void onMessage(Context context, String channel, JSONObject msg) {
        if (!Metar.TYPE.equals(msg.optString("type"))) return;
        Metar.save(context, msg);
        Metar m = Metar.parse(msg.toString());
        if (m == null || m.alert == null || m.station(m.alert) == null) return;
        chimeAndWake(context);
        context.startActivity(new Intent(context, AirportActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(AirportActivity.EXTRA_ID, m.alert));
    }

    /** Plays the alert sound and turns the screen on, if it was off. */
    static void chimeAndWake(Context context) {
        ((AudioManager) context.getSystemService(Context.AUDIO_SERVICE)).playSoundEffect(Sounds.SUCCESS);
        PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        @SuppressWarnings("deprecation")
        PowerManager.WakeLock wake = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                | PowerManager.ACQUIRE_CAUSES_WAKEUP | PowerManager.ON_AFTER_RELEASE, "GlassLinkAirports:alert");
        wake.acquire(5000);
    }
}
