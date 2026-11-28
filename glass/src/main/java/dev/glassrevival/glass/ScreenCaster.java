package dev.glassrevival.glass;

import android.graphics.Bitmap;
import android.os.SystemClock;
import android.util.Base64;
import android.util.Log;
import dev.glassrevival.protocol.Protocol;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.util.Arrays;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Sends Glass's display to the phone, like MyGlass's screencast (view only).
 *
 * <p>Frames come from SurfaceControl.screenshot, a hidden API that needs
 * READ_FRAME_BUFFER (signature|system), so GlassLink must be a system app.
 *
 * <p>Two ways to deliver them:
 * <ul>
 *   <li><b>Wi-Fi</b> (preferred): an MJPEG stream from {@link MediaServer} at /cast. It's fast,
 *       and TCP itself paces the frames.
 *   <li><b>Bluetooth</b> (fallback): cast_frame messages, one at a time, small and at about
 *       2.5 fps. KitKat's Bluetooth stack (bluedroid) crashes under a sustained stream of
 *       large frames, which drops every Bluetooth connection on Glass.
 * </ul>
 * Unchanged frames are skipped in both modes.
 */
final class ScreenCaster {

    /** Sends a message to the phone. @return false if no phone is connected */
    interface Out {
        boolean send(JSONObject msg);
    }

    private static final int WIDTH = 640, HEIGHT = 360;
    // Wi-Fi: full size, up to ~12 fps.
    private static final int WIFI_QUALITY = 70;
    private static final long WIFI_MIN_FRAME_MS = 80;
    // Bluetooth: small and slow, so bluedroid survives.
    private static final int BT_WIDTH = 480, BT_HEIGHT = 270;
    private static final int BT_QUALITY = 45;
    private static final long BT_MIN_FRAME_MS = 400;
    private static final long IDLE_CHECK_MS = 150;
    /** The phone acks every Bluetooth frame; this much silence means the viewer is gone. */
    private static final long ACK_TIMEOUT_MS = 10_000;

    private final Out out;
    private final Object lock = new Object();
    private Method screenshot;      // guarded by lock
    /** Bumped by every start and stop; a loop runs only while its generation is current. */
    private int generation;         // guarded by lock
    private boolean httpAllowed;    // guarded by lock
    private int sentSeq;            // guarded by lock
    private int ackedSeq;           // guarded by lock
    private long lastAckAt;         // guarded by lock

    ScreenCaster(Out out) {
        this.out = out;
    }

    /** The phone will fetch the Wi-Fi stream; allow /cast until {@link #stop}. */
    void allowWifiStream() {
        synchronized (lock) {
            generation++;
            httpAllowed = true;
        }
        Log.i(LinkService.TAG, "Screencast offered over Wi-Fi");
    }

    /** Start sending frames over Bluetooth. */
    void startBluetooth() {
        final int gen;
        synchronized (lock) {
            gen = ++generation;
            httpAllowed = false;
            sentSeq = 0;
            ackedSeq = 0;
            lastAckAt = SystemClock.elapsedRealtime();
        }
        new Thread(() -> runBluetooth(gen), "GlassLink-cast-bt").start();
        Log.i(LinkService.TAG, "Screencast started over Bluetooth");
    }

    void stop() {
        boolean was;
        synchronized (lock) {
            was = httpAllowed || sentSeq > 0;
            generation++;
            httpAllowed = false;
            sentSeq = 0;
            lock.notifyAll();
        }
        if (was) Log.i(LinkService.TAG, "Screencast stopped");
    }

    void ack(int seq) {
        synchronized (lock) {
            if (seq > ackedSeq) ackedSeq = seq;
            lastAckAt = SystemClock.elapsedRealtime();
            lock.notifyAll();
        }
    }

    private boolean current(int gen) {
        synchronized (lock) {
            return gen == generation;
        }
    }

    // ---- Wi-Fi ----------------------------------------------------------------------------

    /**
     * Serves the MJPEG stream on an HTTP connection that {@link MediaServer} accepted and
     * authenticated. Returns when the phone disconnects or the cast stops.
     */
    void streamTo(OutputStream http) throws IOException {
        final int gen;
        synchronized (lock) {
            if (!httpAllowed) {
                http.write("HTTP/1.0 409 Conflict\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                        .getBytes("US-ASCII"));
                return;
            }
            gen = generation;
        }
        http.write(("HTTP/1.0 200 OK\r\nContent-Type: multipart/x-mixed-replace; boundary=frame\r\n"
                + "Cache-Control: no-cache\r\nConnection: close\r\n\r\n").getBytes("US-ASCII"));
        Log.i(LinkService.TAG, "Screencast streaming over Wi-Fi");
        byte[] last = null;
        long lastSentAt = 0;
        while (current(gen)) {
            long wait = WIFI_MIN_FRAME_MS - (SystemClock.elapsedRealtime() - lastSentAt);
            if (wait > 0 && !sleep(wait)) return;
            byte[] jpeg = capture(WIDTH, HEIGHT, WIFI_QUALITY);
            if (jpeg == null || (last != null && Arrays.equals(jpeg, last))) {
                if (!sleep(jpeg == null ? 500 : IDLE_CHECK_MS)) return;
                continue;
            }
            last = jpeg;
            // Blocks while the phone is slow to read, which paces the stream.
            http.write(("--frame\r\nContent-Type: image/jpeg\r\nContent-Length: " + jpeg.length + "\r\n\r\n")
                    .getBytes("US-ASCII"));
            http.write(jpeg);
            http.write("\r\n".getBytes("US-ASCII"));
            http.flush();
            lastSentAt = SystemClock.elapsedRealtime();
        }
    }

    // ---- Bluetooth ------------------------------------------------------------------------

    private void runBluetooth(int gen) {
        byte[] last = null;
        long lastSentAt = 0;
        while (current(gen)) {
            // One frame in flight: wait for the phone's ack before the next.
            synchronized (lock) {
                while (gen == generation && sentSeq > ackedSeq) {
                    if (SystemClock.elapsedRealtime() - lastAckAt > ACK_TIMEOUT_MS) {
                        generation++;
                        Log.i(LinkService.TAG, "Screencast viewer went away");
                        return;
                    }
                    try {
                        lock.wait(1000);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            }
            long wait = BT_MIN_FRAME_MS - (SystemClock.elapsedRealtime() - lastSentAt);
            if (wait > 0 && !sleep(wait)) return;
            byte[] jpeg = capture(BT_WIDTH, BT_HEIGHT, BT_QUALITY);
            if (jpeg == null || (last != null && Arrays.equals(jpeg, last))) {
                if (!sleep(jpeg == null ? 500 : IDLE_CHECK_MS)) return;
                continue;
            }
            last = jpeg;
            int seq;
            synchronized (lock) {
                if (gen != generation) return;
                seq = ++sentSeq;
            }
            try {
                JSONObject msg = new JSONObject()
                        .put(Protocol.F_TYPE, Protocol.CAST_FRAME)
                        .put(Protocol.F_SEQ, seq)
                        .put(Protocol.F_WIDTH, BT_WIDTH)
                        .put(Protocol.F_HEIGHT, BT_HEIGHT)
                        .put(Protocol.F_DATA, Base64.encodeToString(jpeg, Base64.NO_WRAP));
                if (!out.send(msg)) {
                    stop();
                    return;
                }
            } catch (JSONException ignored) {
                // cannot happen
            }
            lastSentAt = SystemClock.elapsedRealtime();
        }
    }

    // ---- Capture --------------------------------------------------------------------------

    /** A JPEG of the display, or null while it's off. Reports and stops on a permission error. */
    private byte[] capture(int w, int h, int quality) {
        Bitmap frame;
        try {
            Method m;
            synchronized (lock) {
                if (screenshot == null) {
                    screenshot = Class.forName("android.view.SurfaceControl")
                            .getMethod("screenshot", int.class, int.class);
                }
                m = screenshot;
            }
            frame = (Bitmap) m.invoke(null, w, h);
        } catch (Exception e) {
            Log.w(LinkService.TAG, "Screen capture failed", e);
            fail("Glass refused screen capture. Run scripts/glasslink.sh install-glass-system.");
            return null;
        }
        if (frame == null) return null; // display off
        ByteArrayOutputStream jpeg = new ByteArrayOutputStream(48 * 1024);
        frame.compress(Bitmap.CompressFormat.JPEG, quality, jpeg);
        frame.recycle();
        return jpeg.toByteArray();
    }

    private void fail(String error) {
        stop();
        try {
            out.send(new JSONObject().put(Protocol.F_TYPE, Protocol.CAST_ERROR).put(Protocol.F_ERROR, error));
        } catch (JSONException ignored) {
            // cannot happen
        }
    }

    private static boolean sleep(long ms) {
        try {
            Thread.sleep(ms);
            return true;
        } catch (InterruptedException e) {
            return false;
        }
    }
}
