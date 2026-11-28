package dev.glassrevival.recipes;

import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.os.PowerManager;
import com.google.android.glass.media.Sounds;
import dev.glassrevival.hub.HubReceiver;
import org.json.JSONObject;

/** A recipe arrived from the phone: keep it and open it. */
public class RecipeReceiver extends HubReceiver {

    @Override
    protected void onMessage(Context context, String channel, JSONObject msg) {
        if (!"recipe".equals(msg.optString("type"))) return;
        Recipe.save(context, msg);
        ((AudioManager) context.getSystemService(Context.AUDIO_SERVICE)).playSoundEffect(Sounds.SUCCESS);
        PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        @SuppressWarnings("deprecation")
        PowerManager.WakeLock wake = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                | PowerManager.ACQUIRE_CAUSES_WAKEUP | PowerManager.ON_AFTER_RELEASE, "GlassLinkRecipes:new");
        wake.acquire(5000);
        context.startActivity(new Intent(context, RecipeActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(RecipeActivity.EXTRA_RELOAD, true));
    }
}
