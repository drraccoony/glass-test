package dev.glassrevival.airports;

import android.content.Context;
import dev.glassrevival.hub.HubReceiver;
import org.json.JSONObject;

/**
 * New METARs while the app isn't open: keep them for next time. The phone sends them
 * when Glass connects and every few minutes after, so this stays quiet (no sound or wake).
 */
public class MetarReceiver extends HubReceiver {

    @Override
    protected void onMessage(Context context, String channel, JSONObject msg) {
        if (Metar.TYPE.equals(msg.optString("type"))) Metar.save(context, msg);
    }
}
