package dev.glassrevival.nav;

import android.content.Context;
import android.content.Intent;
import dev.glassrevival.hub.HubReceiver;
import org.json.JSONObject;

/**
 * The GlassLink hub calls this when a nav message arrives and {@link NavService} isn't
 * running (so nothing is bound to the channel). A step starts the service.
 */
public class NavReceiver extends HubReceiver {

    @Override
    protected void onMessage(Context context, String channel, JSONObject msg) {
        if (!"step".equals(msg.optString("type"))) return; // "end" with nothing running: nothing to do
        context.startService(new Intent(context, NavService.class)
                .putExtra(NavService.EXTRA_MESSAGE, msg.toString()));
    }
}
