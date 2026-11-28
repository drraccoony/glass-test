package dev.glassrevival.glass;

import android.content.Context;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.util.Log;
import dev.glassrevival.protocol.Protocol;
import java.io.BufferedReader;
import java.io.Closeable;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.SecureRandom;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Tiny HTTP server so the phone can fetch full photos and videos over Wi-Fi, which is
 * far faster than Bluetooth. Every URL carries a random token that is only ever given
 * to the paired phone over Bluetooth; requests without it are refused.
 */
final class MediaServer {

    private static final String TAG = LinkService.TAG;

    private final Context context;
    private final MediaLibrary library;
    private final String token;
    private final ExecutorService pool = Executors.newFixedThreadPool(2);
    private ServerSocket server;
    private volatile ScreenCaster caster;

    void setCaster(ScreenCaster caster) {
        this.caster = caster;
    }

    /** The Wi-Fi screencast stream, or null when Glass isn't on Wi-Fi. */
    synchronized String castUrl() {
        String ip = wifiAddress();
        if (ip == null) return null;
        try {
            if (server == null || server.isClosed()) start();
        } catch (IOException e) {
            Log.w(TAG, "Media server failed to start", e);
            return null;
        }
        return "http://" + ip + ":" + server.getLocalPort() + "/cast?t=" + token;
    }

    MediaServer(Context context, MediaLibrary library) {
        this.context = context.getApplicationContext();
        this.library = library;
        byte[] b = new byte[16];
        new SecureRandom().nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format(Locale.US, "%02x", x));
        token = sb.toString();
    }

    /** A Wi-Fi URL for this item, or null when Glass isn't on Wi-Fi. */
    synchronized String urlFor(MediaLibrary.Item item) {
        String ip = wifiAddress();
        if (ip == null) return null;
        try {
            if (server == null || server.isClosed()) start();
        } catch (IOException e) {
            Log.w(TAG, "Media server failed to start", e);
            return null;
        }
        return "http://" + ip + ":" + server.getLocalPort() + "/m/" + item.id + "?t=" + token;
    }

    synchronized void stop() {
        closeQuietly(server);
        server = null;
        pool.shutdownNow();
    }

    private void start() throws IOException {
        final ServerSocket s = new ServerSocket(0);
        server = s;
        Thread t = new Thread(() -> {
            while (!s.isClosed()) {
                try {
                    final Socket c = s.accept();
                    pool.execute(() -> serve(c));
                } catch (IOException e) {
                    break;
                }
            }
        }, "GlassLink-media-http");
        t.start();
        Log.i(TAG, "Media server on port " + s.getLocalPort());
    }

    private void serve(Socket c) {
        try {
            c.setSoTimeout(15000);
            BufferedReader in = new BufferedReader(new InputStreamReader(c.getInputStream(), "US-ASCII"));
            String request = in.readLine();
            String line;
            while ((line = in.readLine()) != null && !line.isEmpty()) {
                // ignore headers
            }
            OutputStream out = c.getOutputStream();
            if (isCastRequest(request)) {
                ScreenCaster sc = caster;
                c.setSoTimeout(0);
                c.setTcpNoDelay(true);
                if (sc != null) sc.streamTo(out);
                return;
            }
            MediaLibrary.Item item = parse(request);
            if (item == null) {
                out.write("HTTP/1.0 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                        .getBytes("US-ASCII"));
                return;
            }
            out.write(("HTTP/1.0 200 OK\r\nContent-Type: " + item.mime + "\r\nContent-Length: "
                    + item.size + "\r\nConnection: close\r\n\r\n").getBytes("US-ASCII"));
            InputStream file = new FileInputStream(item.path);
            try {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = file.read(buf)) > 0) out.write(buf, 0, n);
            } finally {
                file.close();
            }
            out.flush();
        } catch (IOException e) {
            Log.i(TAG, "Media request ended: " + e);
        } finally {
            closeQuietly(c);
        }
    }

    /** "GET /cast?t=TOKEN HTTP/1.1" with the right token. */
    private boolean isCastRequest(String request) {
        if (request == null || !request.startsWith("GET /cast?t=")) return false;
        int end = request.indexOf(' ', 12);
        String given = end < 0 ? request.substring(12) : request.substring(12, end);
        return Protocol.pairCodesMatch(token, given);
    }

    /** "GET /m/p123?t=TOKEN HTTP/1.1" with the right token gives the item; anything else null. */
    private MediaLibrary.Item parse(String request) {
        if (request == null || !request.startsWith("GET /m/")) return null;
        int end = request.indexOf(' ', 4);
        String target = end < 0 ? request.substring(4) : request.substring(4, end);
        int q = target.indexOf("?t=");
        if (q < 0) return null;
        if (!Protocol.pairCodesMatch(token, target.substring(q + 3))) return null; // constant-time compare
        return library.find(target.substring(3, q));
    }

    @SuppressWarnings("deprecation")
    private String wifiAddress() {
        WifiManager wm = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
        if (wm == null || !wm.isWifiEnabled()) return null;
        WifiInfo info = wm.getConnectionInfo();
        int ip = info == null ? 0 : info.getIpAddress();
        if (ip == 0) return null;
        return (ip & 0xff) + "." + ((ip >> 8) & 0xff) + "." + ((ip >> 16) & 0xff) + "." + ((ip >> 24) & 0xff);
    }

    private static void closeQuietly(Closeable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (IOException ignored) {
            // nothing to do
        }
    }
}
