package dev.glassrevival.music;

import android.content.Context;
import android.content.Intent;
import dev.glassrevival.hub.HubReceiver;
import org.json.JSONObject;

/**
 * The GlassLink hub calls this when the phone sends something and {@link MusicService}
 * isn't running. Music starting on the phone starts the service and its card; pauses and
 * "nothing playing" are ignored, so a paused app doesn't bring the card back.
 */
public class MusicReceiver extends HubReceiver {

    @Override
    protected void onMessage(Context context, String channel, JSONObject msg) {
        if (!"now".equals(msg.optString("type")) || !"playing".equals(msg.optString("state"))) return;
        context.startService(new Intent(context, MusicService.class)
                .putExtra(MusicService.EXTRA_MESSAGE, msg.toString()));
    }
}
