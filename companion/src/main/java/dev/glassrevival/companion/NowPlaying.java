package dev.glassrevival.companion;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Base64;
import android.util.Log;
import android.view.KeyEvent;
import dev.glassrevival.protocol.Protocol;
import java.io.ByteArrayOutputStream;
import java.util.List;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * What's playing on the phone, for the Now Playing app on Glass, and the remote control
 * for it. Follows the phone's media sessions (any app with a media notification), which
 * the notification access GlassLink already has allows.
 *
 * <pre>
 * phone -> glass  now      {state, title, artist, album, app, duration, position, volume,
 *                           volumeMax, artId, art?}
 *                          state: "playing", "paused" or "none" (nothing to control).
 *                          position and duration in ms; position is as of sending.
 *                          art: base64 JPEG, only when artId changes
 * glass -> phone  sync     {}    send the current state, with art
 * glass -> phone  toggle | play | pause | next | prev | vol_up | vol_down   {}
 * </pre>
 */
final class NowPlaying implements LinkManager.GlassHook {

    static final String CHANNEL = "dev.glassrevival.music";
    private static final String TAG = "GlassLink";
    private static final int ART_PX = 160;
    /** Coalesces bursts of callbacks (a track change sends several). */
    private static final long SEND_DELAY_MS = 250;

    private final Context context;
    private final LinkManager link;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ComponentName listener;

    // Main thread only.
    private MediaSessionManager sessions;
    private MediaController controller;
    private String lastSent = "";
    private int sentArtId;      // art Glass already has, 0 = none
    private int artId;
    private String artJpeg;
    private Object artSource;   // the bitmap artJpeg was made from

    NowPlaying(Context context, LinkManager link) {
        this.context = context.getApplicationContext();
        this.link = link;
        listener = new ComponentName(this.context, GlassNotificationListener.class);
    }

    /** Starts following media sessions. Needs notification access; call again once granted. */
    void start() {
        main.post(() -> {
            if (sessions != null) return;
            MediaSessionManager m = context.getSystemService(MediaSessionManager.class);
            try {
                m.addOnActiveSessionsChangedListener(this::onSessionsChanged, listener, main);
                sessions = m;
                onSessionsChanged(m.getActiveSessions(listener));
            } catch (SecurityException e) {
                Log.i(TAG, "Now playing waits for notification access");
            }
        });
    }

    @Override
    public void onGlassConnected() {
        main.post(() -> {
            sentArtId = 0;
            lastSent = "";
            sendSoon();
        });
    }

    /** A frame from the Now Playing app on Glass. Any thread. */
    void onGlassMessage(JSONObject msg) {
        String type = msg.optString(Protocol.F_TYPE);
        main.post(() -> command(type));
    }

    // ---- Sessions -------------------------------------------------------------------------

    private void onSessionsChanged(List<MediaController> active) {
        MediaController pick = null;
        if (active != null) {
            for (MediaController c : active) {
                if (isPlaying(c)) {
                    pick = c;
                    break;
                }
            }
            // Nothing playing: the most recent session (the list is ordered that way).
            if (pick == null && !active.isEmpty()) pick = active.get(0);
        }
        follow(pick);
    }

    private void follow(MediaController c) {
        if (controller != null && c != null && controller.getSessionToken().equals(c.getSessionToken())) return;
        if (controller != null) controller.unregisterCallback(callback);
        controller = c;
        if (c != null) {
            c.registerCallback(callback, main);
            Log.i(TAG, "Now playing follows " + c.getPackageName());
        }
        sendSoon();
    }

    private final MediaController.Callback callback = new MediaController.Callback() {
        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            // Another app may have started playing while this one paused.
            if (sessions != null && !isPlaying(controller)) {
                try {
                    for (MediaController c : sessions.getActiveSessions(listener)) {
                        if (isPlaying(c)) {
                            follow(c);
                            return;
                        }
                    }
                } catch (SecurityException ignored) {
                    // access was revoked
                }
            }
            sendSoon();
        }

        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            sendSoon();
        }

        @Override
        public void onAudioInfoChanged(MediaController.PlaybackInfo info) {
            sendSoon();
        }

        @Override
        public void onSessionDestroyed() {
            follow(null);
            if (sessions != null) {
                try {
                    onSessionsChanged(sessions.getActiveSessions(listener));
                } catch (SecurityException ignored) {
                    // access was revoked
                }
            }
        }
    };

    private static boolean isPlaying(MediaController c) {
        PlaybackState s = c == null ? null : c.getPlaybackState();
        return s != null && (s.getState() == PlaybackState.STATE_PLAYING
                || s.getState() == PlaybackState.STATE_BUFFERING);
    }

    // ---- Commands from Glass --------------------------------------------------------------

    private void command(String type) {
        if ("sync".equals(type)) {
            sentArtId = 0;
            lastSent = "";
            sendSoon();
            return;
        }
        MediaController c = controller;
        if (c == null) {
            // No session: a media key wakes the last player, like headphone buttons do.
            if ("play".equals(type) || "toggle".equals(type)) mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY);
            else if ("vol_up".equals(type) || "vol_down".equals(type)) adjustVolume(type);
            return;
        }
        MediaController.TransportControls t = c.getTransportControls();
        switch (type) {
            case "toggle":
                if (isPlaying(c)) t.pause(); else t.play();
                break;
            case "play":
                t.play();
                break;
            case "pause":
                t.pause();
                break;
            case "next":
                t.skipToNext();
                break;
            case "prev":
                t.skipToPrevious();
                break;
            case "vol_up":
            case "vol_down":
                adjustVolume(type);
                break;
            default:
                return;
        }
        Log.i(TAG, "Now playing: " + type + " from Glass");
    }

    private void adjustVolume(String type) {
        int dir = "vol_up".equals(type) ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER;
        if (controller != null) {
            controller.adjustVolume(dir, 0); // also works for casting
        } else {
            context.getSystemService(AudioManager.class).adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, 0);
        }
        sendSoon(); // onAudioInfoChanged doesn't fire for local playback
    }

    private void mediaKey(int code) {
        AudioManager am = context.getSystemService(AudioManager.class);
        am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, code));
        am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, code));
    }

    // ---- To Glass -------------------------------------------------------------------------

    private final Runnable send = this::sendNow;

    private void sendSoon() {
        main.removeCallbacks(send);
        main.postDelayed(send, SEND_DELAY_MS);
    }

    private void sendNow() {
        if (!link.isConnected() || !CompanionApp.addOns(context).isInstalled(AddOnManager.MUSIC)) return;
        try {
            JSONObject msg = state();
            // Position moves on its own; only send when something else changed, or it jumped.
            String key = msg.optString("state") + "|" + msg.optString("title") + "|" + msg.optString("artist")
                    + "|" + msg.optInt("volume") + "|" + msg.optLong("duration") + "|" + (msg.optLong("position") / 3000);
            if (key.equals(lastSent) && !msg.has("art")) return;
            lastSent = key;
            msg.put(Protocol.F_CHANNEL, CHANNEL);
            link.send(msg);
        } catch (JSONException ignored) {
            // cannot happen
        }
    }

    private JSONObject state() throws JSONException {
        JSONObject msg = new JSONObject().put(Protocol.F_TYPE, "now");
        MediaController c = controller;
        AudioManager am = context.getSystemService(AudioManager.class);
        int volume = am.getStreamVolume(AudioManager.STREAM_MUSIC);
        int volumeMax = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        if (c != null && c.getPlaybackInfo() != null
                && c.getPlaybackInfo().getPlaybackType() == MediaController.PlaybackInfo.PLAYBACK_TYPE_REMOTE) {
            volume = c.getPlaybackInfo().getCurrentVolume();
            volumeMax = c.getPlaybackInfo().getMaxVolume();
        }
        msg.put("volume", volume).put("volumeMax", volumeMax);
        MediaMetadata md = c == null ? null : c.getMetadata();
        if (md == null) return msg.put("state", "none");

        PlaybackState ps = c.getPlaybackState();
        msg.put("state", isPlaying(c) ? "playing" : "paused");
        String title = first(md, MediaMetadata.METADATA_KEY_TITLE, MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
        String artist = first(md, MediaMetadata.METADATA_KEY_ARTIST, MediaMetadata.METADATA_KEY_ALBUM_ARTIST,
                MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, MediaMetadata.METADATA_KEY_AUTHOR);
        msg.put("title", title);
        msg.put("artist", artist);
        msg.put("album", first(md, MediaMetadata.METADATA_KEY_ALBUM));
        msg.put("app", appLabel(c.getPackageName()));
        msg.put("duration", Math.max(0, md.getLong(MediaMetadata.METADATA_KEY_DURATION)));
        long position = 0;
        if (ps != null && ps.getPosition() >= 0) {
            position = ps.getPosition();
            if (ps.getState() == PlaybackState.STATE_PLAYING) {
                position += (long) ((SystemClock.elapsedRealtime() - ps.getLastPositionUpdateTime())
                        * ps.getPlaybackSpeed());
            }
        }
        msg.put("position", Math.max(0, position));

        Bitmap art = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        if (art == null) art = md.getBitmap(MediaMetadata.METADATA_KEY_ART);
        if (art == null) art = md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
        if (art != artSource) {
            artSource = art;
            artJpeg = art == null ? null : jpeg(art);
            artId = artJpeg == null ? 0 : (artJpeg.hashCode() | 1);
        }
        msg.put("artId", artId);
        if (artId != 0 && artId != sentArtId) {
            msg.put("art", artJpeg);
            sentArtId = artId;
        }
        return msg;
    }

    private static String first(MediaMetadata md, String... keys) {
        for (String k : keys) {
            CharSequence s = md.getText(k);
            if (!TextUtils.isEmpty(s)) return s.toString().trim();
        }
        return "";
    }

    private String appLabel(String pkg) {
        PackageManager pm = context.getPackageManager();
        try {
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return "";
        }
    }

    private static String jpeg(Bitmap art) {
        try {
            Bitmap src = art.getConfig() == Bitmap.Config.HARDWARE ? art.copy(Bitmap.Config.ARGB_8888, false) : art;
            Bitmap small = Bitmap.createScaledBitmap(src, ART_PX, ART_PX, true);
            if (src != art && src != small) src.recycle();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            small.compress(Bitmap.CompressFormat.JPEG, 80, out);
            if (small != art) small.recycle();
            return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
        } catch (RuntimeException e) { // a recycled or hardware bitmap
            return null;
        }
    }
}
