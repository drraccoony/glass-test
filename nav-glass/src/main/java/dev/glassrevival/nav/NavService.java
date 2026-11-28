package dev.glassrevival.nav;

import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.AudioManager;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Base64;
import android.util.Log;
import android.widget.RemoteViews;
import com.google.android.glass.media.Sounds;
import com.google.android.glass.timeline.LiveCard;
import dev.glassrevival.hub.HubClient;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Shows the current navigation step as a live card in the Glass timeline, next to the
 * clock, while Google Maps navigates on the phone. Steps arrive from the phone through
 * the GlassLink hub; see the companion's NavProvider for the messages.
 */
public class NavService extends Service {

    static final String TAG = "GlassLinkNav";
    static final String CHANNEL = "dev.glassrevival.nav";
    /** A hub message (JSON) handed over by {@link NavReceiver}. */
    static final String EXTRA_MESSAGE = "message";
    static final String ACTION_STOP = "dev.glassrevival.nav.STOP";

    /** Wake Glass and chime when the next turn gets this close. */
    private static final double ALERT_METERS = 160; // about 500 ft
    private static final Pattern DISTANCE =
            Pattern.compile("([\\d.,]+)\\s*(ft|feet|mi|miles?|yd|m|meters?|metres?|km)", Pattern.CASE_INSENSITIVE);

    private static NavService instance;

    private LiveCard card;
    private HubClient hub;
    private Bitmap arrow;
    private String instruction = "";
    private boolean alertedForInstruction;

    /** Asks the phone to end navigation in Maps. */
    static void requestStop() {
        NavService s = instance;
        if (s != null) s.send("exit");
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        hub = new HubClient(this, CHANNEL, new HubClient.Callback() {
            @Override
            public void onMessage(JSONObject msg) {
                handle(msg);
            }

            @Override
            public void onConnectionChanged(boolean connected) {
                // Ask for the current step, e.g. after starting from a receiver broadcast
                // that carried no arrow, or after the link came back.
                if (connected) send("sync");
            }
        });
        hub.connect();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String json = intent == null ? null : intent.getStringExtra(EXTRA_MESSAGE);
        if (json != null) {
            try {
                handle(new JSONObject(json));
            } catch (JSONException e) {
                Log.w(TAG, "Bad message", e);
            }
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        instance = null;
        if (card != null && card.isPublished()) card.unpublish();
        card = null;
        hub.disconnect();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ---- Messages -------------------------------------------------------------------------

    private void handle(JSONObject msg) {
        String type = msg.optString("type");
        if ("step".equals(type)) {
            showStep(msg);
        } else if ("end".equals(type)) {
            Log.i(TAG, "Navigation ended");
            stopSelf();
        }
    }

    private void showStep(JSONObject step) {
        String arrowPng = step.optString("arrow", "");
        if (!arrowPng.isEmpty()) {
            try {
                byte[] png = Base64.decode(arrowPng, Base64.DEFAULT);
                arrow = BitmapFactory.decodeByteArray(png, 0, png.length);
            } catch (IllegalArgumentException e) {
                Log.w(TAG, "Bad arrow image");
            }
        }
        String dist = step.optString("dist", "");
        String instr = step.optString("instr", "");

        RemoteViews views = new RemoteViews(getPackageName(), R.layout.nav_card);
        if (arrow != null) views.setImageViewBitmap(R.id.arrow, arrow);
        views.setTextViewText(R.id.distance, dist);
        views.setTextViewText(R.id.instruction, instr);
        // Steps like "Head toward E 130th St" have no distance: give the line its space.
        views.setViewVisibility(R.id.distance, dist.isEmpty() ? android.view.View.GONE : android.view.View.VISIBLE);
        views.setTextViewTextSize(R.id.instruction, android.util.TypedValue.COMPLEX_UNIT_SP,
                dist.isEmpty() ? 30 : 22);
        views.setViewPadding(R.id.instruction, 0, dist.isEmpty() ? (int) (14 * getResources().getDisplayMetrics().density) : 0, 0, 0);
        views.setTextViewText(R.id.eta, step.optString("eta", ""));
        int max = step.optInt("max", 0);
        views.setViewVisibility(R.id.progress, max > 0 ? android.view.View.VISIBLE : android.view.View.INVISIBLE);
        if (max > 0) views.setProgressBar(R.id.progress, max, step.optInt("progress", 0), false);

        boolean first = card == null;
        if (first) {
            card = new LiveCard(this, "glasslink_nav");
            card.attach(this);
            card.setAction(PendingIntent.getActivity(this, 0,
                    new Intent(this, NavMenuActivity.class), 0));
        }
        card.setViews(views);
        if (first) {
            Log.i(TAG, "Navigation started");
            card.publish(LiveCard.PublishMode.REVEAL); // jump to the card
        }

        if (!instr.equals(instruction)) {
            instruction = instr;
            alertedForInstruction = false;
        }
        double meters = toMeters(dist);
        if (!first && !alertedForInstruction && meters >= 0 && meters <= ALERT_METERS) {
            alertedForInstruction = true;
            alertTurn();
        }
    }

    /** The next turn is close: chime, wake the display and bring the card to the front. */
    private void alertTurn() {
        ((AudioManager) getSystemService(Context.AUDIO_SERVICE)).playSoundEffect(Sounds.SUCCESS);
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        @SuppressWarnings("deprecation")
        PowerManager.WakeLock wake = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                | PowerManager.ACQUIRE_CAUSES_WAKEUP | PowerManager.ON_AFTER_RELEASE, "GlassLinkNav:turn");
        wake.acquire(6000);
        if (card != null) card.navigate();
    }

    private void send(String type) {
        try {
            hub.send(new JSONObject().put("type", type));
        } catch (JSONException ignored) {
            // cannot happen
        }
    }

    /** "350 ft", "0.3 mi", "200 m", "1,2 km" to meters, or -1. */
    static double toMeters(String dist) {
        Matcher m = DISTANCE.matcher(dist);
        if (!m.find()) return -1;
        double v;
        try {
            v = Double.parseDouble(m.group(1).replace(',', '.'));
        } catch (NumberFormatException e) {
            return -1;
        }
        String unit = m.group(2).toLowerCase(Locale.ROOT);
        if (unit.startsWith("ft") || unit.startsWith("feet")) return v * 0.3048;
        if (unit.startsWith("mi")) return v * 1609.34;
        if (unit.startsWith("yd")) return v * 0.9144;
        if (unit.startsWith("km")) return v * 1000;
        return v; // meters
    }
}
