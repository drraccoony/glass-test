package dev.glassrevival.music;

import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Base64;
import android.util.Log;
import android.view.View;
import android.widget.RemoteViews;
import com.google.android.glass.timeline.LiveCard;
import dev.glassrevival.hub.HubClient;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Shows what's playing on the phone as a live card next to the clock. The phone's
 * NowPlaying sends the state on the hub channel {@link #CHANNEL}; see it for the messages.
 *
 * <p>The card appears when music starts on the phone and goes away when there's nothing
 * left to control, or after a long pause. "ok glass, listen to" brings it back any time.
 */
public class MusicService extends Service {

    static final String TAG = "GlassLinkMusic";
    static final String CHANNEL = "dev.glassrevival.music";
    /** A hub message (JSON) handed over by {@link MusicReceiver}. */
    static final String EXTRA_MESSAGE = "message";
    /** Opened by the user: show the card even if nothing is playing, and jump to it. */
    static final String EXTRA_SHOW = "show";

    /** Hide the card after this long paused, unless the user opened it. */
    private static final long PAUSED_HIDE_MS = 15 * 60 * 1000L;
    /** Moves the progress bar along while playing. The time counts by itself. */
    private static final long PROGRESS_EVERY_MS = 5000;
    private static final long VOLUME_SHOWN_MS = 3000;

    private static MusicService instance;

    private final Handler handler = new Handler();
    private LiveCard card;
    private HubClient hub;
    private boolean userOpened;

    // The latest state from the phone.
    private String state = "none";
    private String title = "", artist = "", album = "", app = "";
    private long duration, position, positionAt; // ms; positionAt = elapsedRealtime when position was true
    private int volume = -1, volumeMax;
    private int artId;
    private Bitmap art;
    private long volumeShownUntil;

    /** Sends a command (toggle, next, vol_up, ...) to the phone. */
    static boolean command(String type) {
        MusicService s = instance;
        return s != null && s.send(type);
    }

    static boolean isPlaying() {
        MusicService s = instance;
        return s != null && "playing".equals(s.state);
    }

    /** "Hide" in the menu. */
    static void hide() {
        MusicService s = instance;
        if (s != null) s.stopSelf();
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
                if (connected) send("sync"); // the full state, with album art
            }
        });
        hub.connect();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.getBooleanExtra(EXTRA_SHOW, false)) {
            userOpened = true;
            show(true);
            send("sync");
        }
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
        handler.removeCallbacksAndMessages(null);
        if (card != null && card.isPublished()) card.unpublish();
        card = null;
        hub.disconnect();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private boolean send(String type) {
        try {
            return hub.send(new JSONObject().put("type", type));
        } catch (JSONException e) {
            return false;
        }
    }

    // ---- State ----------------------------------------------------------------------------

    private void handle(JSONObject msg) {
        if (!"now".equals(msg.optString("type"))) return;
        int oldVolume = volume;
        state = msg.optString("state", "none");
        title = msg.optString("title");
        artist = msg.optString("artist");
        album = msg.optString("album");
        app = msg.optString("app");
        duration = msg.optLong("duration");
        position = msg.optLong("position");
        positionAt = SystemClock.elapsedRealtime();
        volume = msg.optInt("volume", -1);
        volumeMax = msg.optInt("volumeMax");
        if (oldVolume >= 0 && volume != oldVolume) volumeShownUntil = positionAt + VOLUME_SHOWN_MS;
        int newArtId = msg.optInt("artId");
        String artJpeg = msg.optString("art", "");
        if (!artJpeg.isEmpty()) {
            try {
                byte[] jpg = Base64.decode(artJpeg, Base64.DEFAULT);
                art = BitmapFactory.decodeByteArray(jpg, 0, jpg.length);
                artId = newArtId;
            } catch (IllegalArgumentException e) {
                Log.w(TAG, "Bad album art");
            }
        } else if (newArtId == 0) {
            art = null;
            artId = 0;
        } else if (newArtId != artId) {
            art = null; // the phone thinks we have it (e.g. after a restart): ask again
            send("sync");
        }

        handler.removeCallbacks(pausedTooLong);
        if ("none".equals(state) && !userOpened) {
            Log.i(TAG, "Nothing to control on the phone");
            stopSelf();
            return;
        }
        if ("paused".equals(state) && !userOpened) handler.postDelayed(pausedTooLong, PAUSED_HIDE_MS);
        show(false);
    }

    private final Runnable pausedTooLong = () -> {
        Log.i(TAG, "Paused for a while, hiding the card");
        stopSelf();
    };

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            render();
        }
    };

    // ---- The card -------------------------------------------------------------------------

    private void show(boolean reveal) {
        boolean first = card == null;
        if (first) {
            card = new LiveCard(this, "glasslink_music");
            card.attach(this);
            card.setAction(PendingIntent.getActivity(this, 0, new Intent(this, MusicMenuActivity.class), 0));
        }
        render();
        if (first) {
            Log.i(TAG, "Showing now playing");
            // Music starting on the phone shouldn't pull you away from what you're looking at.
            card.publish(reveal ? LiveCard.PublishMode.REVEAL : LiveCard.PublishMode.SILENT);
        } else if (reveal) {
            card.navigate();
        }
    }

    private void render() {
        if (card == null) return;
        handler.removeCallbacks(tick);
        long now = SystemClock.elapsedRealtime();
        boolean playing = "playing".equals(state);
        boolean nothing = "none".equals(state);
        long pos = position + (playing ? now - positionAt : 0);
        if (duration > 0) pos = Math.min(pos, duration);

        RemoteViews v = new RemoteViews(getPackageName(), R.layout.music_card);
        if (art != null) v.setImageViewBitmap(R.id.art, art);
        else v.setImageViewResource(R.id.art, R.drawable.ic_music);
        v.setTextViewText(R.id.title, nothing ? getString(R.string.nothing_playing) : title);
        v.setTextViewText(R.id.artist, nothing ? getString(R.string.tap_to_play) : artist);
        List<String> line = new ArrayList<>();
        if (!album.isEmpty() && !album.equals(title)) line.add(album);
        if (!app.isEmpty()) line.add(app);
        v.setTextViewText(R.id.album, nothing ? "" : android.text.TextUtils.join(" · ", line));

        boolean timed = !nothing && duration > 0;
        v.setViewVisibility(R.id.progress, timed ? View.VISIBLE : View.INVISIBLE);
        v.setViewVisibility(R.id.duration, timed ? View.VISIBLE : View.INVISIBLE);
        v.setViewVisibility(R.id.elapsed, nothing ? View.INVISIBLE : View.VISIBLE);
        if (timed) {
            v.setProgressBar(R.id.progress, (int) (duration / 1000), (int) (pos / 1000), false);
            v.setTextViewText(R.id.duration, clock(duration));
        }
        // The Chronometer counts up by itself while playing.
        v.setChronometer(R.id.elapsed, now - pos, null, playing);

        String status = "";
        if (now < volumeShownUntil && volume >= 0) {
            status = "Volume " + volume + " / " + volumeMax;
        } else if ("paused".equals(state)) {
            status = "Paused";
        }
        v.setTextViewText(R.id.status, status);
        card.setViews(v);

        long next = Long.MAX_VALUE;
        if (playing && timed) next = PROGRESS_EVERY_MS;
        if (now < volumeShownUntil) next = Math.min(next, volumeShownUntil - now + 50);
        if (next != Long.MAX_VALUE) handler.postDelayed(tick, next);
    }

    /** Same style as the Chronometer next to it: "04:13", "1:02:45". */
    private static String clock(long ms) {
        long s = ms / 1000;
        return s >= 3600 ? String.format(Locale.US, "%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60)
                : String.format(Locale.US, "%02d:%02d", s / 60, s % 60);
    }
}
