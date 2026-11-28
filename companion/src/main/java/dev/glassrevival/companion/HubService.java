package dev.glassrevival.companion;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import dev.glassrevival.hub.Hub;

/**
 * Where add-on phone apps bind to reach Glass. The hub itself lives in
 * {@link CompanionApp} next to the link. This service only hands out its binder.
 */
public class HubService extends Service {

    @Override
    public IBinder onBind(Intent intent) {
        if (!Hub.ACTION_BIND.equals(intent.getAction())) return null;
        // An add-on wants Glass, so bring the link up if the user has forwarding on.
        GlassLinkService.startIfEnabled(this);
        return CompanionApp.hub(this).binder();
    }
}
