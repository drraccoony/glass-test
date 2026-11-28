package dev.glassrevival.hub;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Wakes an add-on app when something arrives and the app has no {@link HubClient}
 * bound. The hub uses a receiver only when no client is listening on that channel,
 * so a running app gets each message once.
 *
 * <pre>
 * &lt;receiver
 *     android:name=".NavReceiver"
 *     android:exported="true"
 *     android:permission="dev.glassrevival.permission.HUB"&gt;
 *     &lt;intent-filter&gt;
 *         &lt;action android:name="dev.glassrevival.hub.action.MESSAGE" /&gt;
 *     &lt;/intent-filter&gt;
 *     &lt;meta-data
 *         android:name="dev.glassrevival.hub.CHANNELS"
 *         android:value="dev.glassrevival.nav" /&gt;
 * &lt;/receiver&gt;
 * </pre>
 *
 * <p>The permission attribute matters: it makes sure only the hub can send to it.
 * Work in {@code onMessage} must be quick. Start an activity or service for anything long.
 */
public abstract class HubReceiver extends BroadcastReceiver {

    @Override
    public final void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (Hub.ACTION_MESSAGE.equals(action)) {
            String channel = intent.getStringExtra(Hub.EXTRA_CHANNEL);
            String json = intent.getStringExtra(Hub.EXTRA_MESSAGE);
            if (channel == null || json == null) return;
            try {
                onMessage(context, channel, new JSONObject(json));
            } catch (JSONException ignored) {
                // the hub only sends JSON objects
            }
        } else if (Hub.ACTION_CONNECTION.equals(action)) {
            onConnectionChanged(context, intent.getBooleanExtra(Hub.EXTRA_CONNECTED, false));
        }
    }

    /** A message arrived on one of the channels this receiver declared. */
    protected void onMessage(Context context, String channel, JSONObject msg) {}

    /** Glass and the phone linked up, or the link dropped. */
    protected void onConnectionChanged(Context context, boolean connected) {}
}
