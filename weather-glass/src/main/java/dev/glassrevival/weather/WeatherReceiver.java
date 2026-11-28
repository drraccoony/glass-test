package dev.glassrevival.weather;

import android.content.Context;
import dev.glassrevival.hub.HubReceiver;
import org.json.JSONObject;

/** A new forecast while the app isn't open: keep it for next time. */
public class WeatherReceiver extends HubReceiver {

    @Override
    protected void onMessage(Context context, String channel, JSONObject msg) {
        if (Weather.TYPE.equals(msg.optString("type")) && Weather.parse(msg.toString()) != null) {
            Weather.save(context, msg);
        }
    }
}
