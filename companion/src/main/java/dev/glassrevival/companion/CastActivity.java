package dev.glassrevival.companion;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import java.io.IOException;
import dev.glassrevival.protocol.Protocol;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Shows Glass's display live (view only), like MyGlass's screencast. Casting runs only
 * while this screen is visible.
 */
public class CastActivity extends Activity implements LinkManager.MessageSink, LinkManager.Listener {

    private LinkManager link;
    private ImageView screen;
    private TextView status;
    private volatile boolean casting;
    /** Glass reported it can't capture; don't keep retrying. */
    private volatile boolean failed;
    /** The Wi-Fi stream being read, if any. */
    private volatile java.net.HttpURLConnection wifi;
    private volatile String via = "";
    // Frames per second, counted on the UI thread.
    private int framesThisSecond;
    private long secondStart;
    private int fps;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        link = CompanionApp.link(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        screen = new ImageView(this);
        screen.setScaleType(ImageView.ScaleType.FIT_CENTER);
        screen.setContentDescription("Glass screen");
        root.addView(screen, new FrameLayout.LayoutParams(-1, -1));
        status = new TextView(this);
        status.setTextColor(0xFFB3B3B3);
        status.setTextSize(14);
        int pad = (int) (12 * getResources().getDisplayMetrics().density);
        status.setPadding(pad, pad, pad, pad);
        root.addView(status, new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START));
        root.setOnClickListener(v -> finish());
        setContentView(root);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    @Override
    protected void onResume() {
        super.onResume();
        link.addSink(this);
        link.addListener(this);
        startCast();
    }

    @Override
    protected void onPause() {
        stopCast();
        link.removeSink(this);
        link.removeListener(this);
        super.onPause();
    }

    private void startCast() {
        failed = false;
        if (!link.isConnected()) {
            casting = false;
            status.setText("Glass isn't connected");
            return;
        }
        casting = true;
        status.setText("Connecting to Glass…");
        sendType(Protocol.CAST_START);
    }

    private void stopCast() {
        if (casting) sendType(Protocol.CAST_STOP);
        casting = false;
        closeWifi();
    }

    private void closeWifi() {
        java.net.HttpURLConnection c = wifi;
        wifi = null;
        if (c != null) new Thread(c::disconnect).start(); // not on the UI thread
    }

    /** Reads Glass's MJPEG stream; falls back to Bluetooth frames if it can't. */
    private void readWifi(String url) {
        boolean gotFrame = false;
        java.net.HttpURLConnection c = null;
        try {
            c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            c.setConnectTimeout(4000);
            c.setReadTimeout(10000);
            wifi = c;
            java.io.InputStream in = new java.io.BufferedInputStream(c.getInputStream(), 64 * 1024);
            via = "Wi-Fi";
            while (casting && wifi == c) {
                int length = -1;
                String line;
                while ((line = readLine(in)) != null) {
                    if (line.regionMatches(true, 0, "Content-Length:", 0, 15)) {
                        length = Integer.parseInt(line.substring(15).trim());
                    } else if (line.isEmpty() && length >= 0) {
                        break;
                    }
                }
                if (line == null) break;
                byte[] jpeg = new byte[length];
                int off = 0;
                while (off < length) {
                    int n = in.read(jpeg, off, length - off);
                    if (n < 0) throw new java.io.EOFException();
                    off += n;
                }
                Bitmap frame = BitmapFactory.decodeByteArray(jpeg, 0, length);
                gotFrame = true;
                if (frame != null) runOnUiThread(() -> show(frame));
            }
        } catch (IOException | NumberFormatException e) {
            android.util.Log.i(LinkManager.TAG, "Wi-Fi screencast ended: " + e);
        } finally {
            if (c != null) c.disconnect();
        }
        // Couldn't reach Glass over Wi-Fi (different network, client isolation…): use Bluetooth.
        if (casting && !gotFrame && wifi == c) {
            wifi = null;
            via = "Bluetooth";
            try {
                link.send(new JSONObject().put(Protocol.F_TYPE, Protocol.CAST_START).put(Protocol.F_BT, true));
            } catch (JSONException ignored) {
                // cannot happen
            }
        } else if (casting && wifi == c) {
            wifi = null;
            runOnUiThread(() -> {
                casting = false;
                if (!isFinishing() && link.isConnected()) startCast(); // stream dropped: start again
            });
        }
    }

    private static String readLine(java.io.InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int b;
        while ((b = in.read()) >= 0) {
            if (b == '\n') return sb.toString().trim();
            sb.append((char) b);
        }
        return sb.length() > 0 ? sb.toString() : null;
    }

    // ---- From Glass (link reader thread) ---------------------------------------------------

    @Override
    public void onMessage(JSONObject msg) {
        String type = msg.optString(Protocol.F_TYPE);
        if (Protocol.CAST_URL.equals(type)) {
            if (!casting) return;
            final String url = msg.optString(Protocol.F_URL, "");
            new Thread(() -> readWifi(url), "GlassLink-cast-wifi").start();
        } else if (Protocol.CAST_FRAME.equals(type)) {
            via = "Bluetooth";
            if (!casting) return;
            int seq = msg.optInt(Protocol.F_SEQ);
            Bitmap frame = null;
            try {
                byte[] jpeg = Base64.decode(msg.optString(Protocol.F_DATA, ""), Base64.DEFAULT);
                frame = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
            } catch (IllegalArgumentException ignored) {
                // skip a bad frame; still ack so Glass continues
            }
            ack(seq); // right away, so Glass can capture the next one while we draw
            final Bitmap shown = frame;
            if (shown != null) runOnUiThread(() -> show(shown));
        } else if (Protocol.CAST_ERROR.equals(type)) {
            casting = false;
            failed = true;
            final String error = msg.optString(Protocol.F_ERROR, "Glass stopped the screencast");
            runOnUiThread(() -> status.setText(error));
        }
    }

    @Override
    public void onLinkChanged() {
        runOnUiThread(() -> {
            if (isFinishing()) return;
            if (!link.isConnected() && casting) {
                casting = false;
                status.setText("Glass disconnected");
            } else if (link.isConnected() && !casting && !failed) {
                startCast(); // the link came back
            }
        });
    }

    private void show(Bitmap frame) {
        screen.setImageBitmap(frame);
        long now = SystemClock.elapsedRealtime();
        framesThisSecond++;
        if (now - secondStart >= 1000) {
            fps = framesThisSecond;
            framesThisSecond = 0;
            secondStart = now;
            status.setText(fps + " fps over " + via + " · tap to close");
        } else if (status.getText().length() == 0 || status.getText().toString().startsWith("Connecting")) {
            status.setText("Tap to close");
        }
    }

    private void ack(int seq) {
        try {
            link.send(new JSONObject().put(Protocol.F_TYPE, Protocol.CAST_ACK).put(Protocol.F_SEQ, seq));
        } catch (JSONException ignored) {
            // cannot happen
        }
    }

    private void sendType(String type) {
        try {
            link.send(new JSONObject().put(Protocol.F_TYPE, type));
        } catch (JSONException ignored) {
            // cannot happen
        }
    }
}
