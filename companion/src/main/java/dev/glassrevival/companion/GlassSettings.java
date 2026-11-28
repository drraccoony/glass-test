package dev.glassrevival.companion;

import dev.glassrevival.protocol.Protocol;
import org.json.JSONException;
import org.json.JSONObject;

/** Sends the alert options that Glass applies itself (wake, sound, how long cards stay up). */
final class GlassSettings implements LinkManager.GlassHook {

    private final Prefs prefs;
    private final LinkManager link;

    GlassSettings(Prefs prefs, LinkManager link) {
        this.prefs = prefs;
        this.link = link;
    }

    @Override
    public void onGlassConnected() {
        push();
    }

    @Override
    public void onGlassRequest(String type) {
        // Glass never asks; settings are pushed on connect and on change.
    }

    void push() {
        try {
            link.send(new JSONObject()
                    .put(Protocol.F_TYPE, Protocol.SETTINGS)
                    .put(Protocol.F_WAKE, prefs.wakeGlass())
                    .put(Protocol.F_SOUND, prefs.playSound())
                    .put(Protocol.F_ALERT_SECONDS, prefs.alertSeconds())
                    .put(Protocol.F_TIMELINE, prefs.useTimeline()));
        } catch (JSONException ignored) {
            // cannot happen
        }
    }
}
