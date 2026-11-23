package dev.glassrevival.glass;

import android.util.Base64;
import android.util.Log;
import dev.glassrevival.protocol.Protocol;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Answers the phone's media requests: listing, thumbnails and file transfers. */
final class MediaHandler {

    /** How replies get back to the phone. */
    interface Out {
        void send(JSONObject msg);

        /** Writes right away and waits, so large transfers don't pile up in memory. */
        boolean sendNow(JSONObject msg);
    }

    private static final String TAG = LinkService.TAG;

    private final MediaLibrary library;
    private final MediaServer server;
    // Listing and thumbnails stay quick even while a big Bluetooth transfer runs.
    private final ExecutorService quick = Executors.newSingleThreadExecutor();
    private final ExecutorService transfers = Executors.newSingleThreadExecutor();
    private volatile String cancelled;

    MediaHandler(MediaLibrary library, MediaServer server) {
        this.library = library;
        this.server = server;
    }

    void shutdown() {
        quick.shutdownNow();
        transfers.shutdownNow();
    }

    void handle(final Out out, final JSONObject msg) {
        final String type = msg.optString(Protocol.F_TYPE);
        final String id = msg.optString(Protocol.F_ID, null);
        if (Protocol.MEDIA_CANCEL.equals(type)) {
            cancelled = id;
            return;
        }
        ExecutorService exec = Protocol.MEDIA_GET.equals(type) ? transfers : quick;
        try {
            exec.execute(() -> {
                try {
                    if (Protocol.MEDIA_LIST.equals(type)) {
                        list(out, msg.optInt(Protocol.F_OFFSET, 0), msg.optInt(Protocol.F_LIMIT, 500));
                    } else if (Protocol.MEDIA_THUMB.equals(type)) {
                        thumb(out, id);
                    } else if (Protocol.MEDIA_DELETE.equals(type)) {
                        delete(out, msg.optJSONArray(Protocol.F_IDS));
                    } else if (Protocol.MEDIA_GET.equals(type)) {
                        get(out, id, msg.optBoolean(Protocol.F_BT, false));
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Media request failed: " + type, e);
                    error(out, id, "Glass couldn't read that file");
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // shutting down
        }
    }

    private void list(Out out, int offset, int limit) throws JSONException {
        List<MediaLibrary.Item> all = library.all();
        JSONArray items = new JSONArray();
        for (int i = Math.max(0, offset); i < all.size() && items.length() < limit; i++) {
            MediaLibrary.Item it = all.get(i);
            JSONObject o = new JSONObject();
            o.put(Protocol.F_ID, it.id);
            o.put(Protocol.F_KIND, it.kind);
            o.put(Protocol.F_NAME, it.name);
            o.put(Protocol.F_TAKEN, it.taken);
            o.put(Protocol.F_SIZE, it.size);
            o.put(Protocol.F_WIDTH, it.width);
            o.put(Protocol.F_HEIGHT, it.height);
            if (it.duration > 0) o.put(Protocol.F_DURATION, it.duration);
            items.put(o);
        }
        out.send(new JSONObject().put(Protocol.F_TYPE, Protocol.MEDIA_ITEMS)
                .put(Protocol.F_OFFSET, offset).put(Protocol.F_TOTAL, all.size())
                .put(Protocol.F_ITEMS, items));
    }

    private void delete(Out out, JSONArray ids) throws JSONException {
        JSONArray deleted = new JSONArray();
        JSONArray failed = new JSONArray();
        for (int i = 0; ids != null && i < ids.length(); i++) {
            String id = ids.optString(i);
            MediaLibrary.Item it = library.find(id);
            if (it == null || library.delete(it)) deleted.put(id); else failed.put(id);
        }
        Log.i(TAG, "Deleted " + deleted.length() + " items, " + failed.length() + " failed");
        out.send(new JSONObject().put(Protocol.F_TYPE, Protocol.MEDIA_DELETED)
                .put(Protocol.F_IDS, deleted).put(Protocol.F_FAILED, failed));
    }

    private void thumb(Out out, String id) throws JSONException {
        MediaLibrary.Item it = library.find(id);
        byte[] jpeg = it == null ? null : library.thumbnail(it);
        if (jpeg == null) {
            error(out, id, "No preview");
            return;
        }
        out.send(new JSONObject().put(Protocol.F_TYPE, Protocol.MEDIA_THUMB_DATA)
                .put(Protocol.F_ID, id)
                .put(Protocol.F_DATA, Base64.encodeToString(jpeg, Base64.NO_WRAP)));
    }

    private void get(Out out, String id, boolean forceBluetooth) throws JSONException, IOException {
        MediaLibrary.Item it = library.find(id);
        if (it == null) {
            error(out, id, "That file is no longer on Glass");
            return;
        }
        String url = forceBluetooth ? null : server.urlFor(it);
        JSONObject file = new JSONObject().put(Protocol.F_TYPE, Protocol.MEDIA_FILE)
                .put(Protocol.F_ID, id).put(Protocol.F_NAME, it.name)
                .put(Protocol.F_SIZE, it.size).put(Protocol.F_MIME, it.mime);
        if (url != null) file.put(Protocol.F_URL, url);
        if (!out.sendNow(file) || url != null) return;
        streamOverBluetooth(out, it);
    }

    private void streamOverBluetooth(Out out, MediaLibrary.Item it) throws JSONException, IOException {
        cancelled = null;
        Log.i(TAG, "Sending " + it.name + " over Bluetooth (" + it.size + " bytes)");
        FileInputStream in = new FileInputStream(it.path);
        try {
            byte[] buf = new byte[Protocol.MEDIA_CHUNK_BYTES];
            long offset = 0;
            while (true) {
                if (it.id.equals(cancelled)) {
                    Log.i(TAG, "Transfer cancelled");
                    return;
                }
                int n = readFully(in, buf);
                boolean last = n < buf.length;
                JSONObject chunk = new JSONObject().put(Protocol.F_TYPE, Protocol.MEDIA_CHUNK)
                        .put(Protocol.F_ID, it.id).put(Protocol.F_OFFSET, offset)
                        .put(Protocol.F_DATA, Base64.encodeToString(buf, 0, Math.max(0, n), Base64.NO_WRAP))
                        .put(Protocol.F_LAST, last);
                if (!out.sendNow(chunk)) return; // link dropped
                offset += Math.max(0, n);
                if (last) return;
            }
        } finally {
            in.close();
        }
    }

    private static int readFully(FileInputStream in, byte[] buf) throws IOException {
        int total = 0;
        while (total < buf.length) {
            int n = in.read(buf, total, buf.length - total);
            if (n < 0) break;
            total += n;
        }
        return total;
    }

    private static void error(Out out, String id, String message) {
        try {
            out.send(new JSONObject().put(Protocol.F_TYPE, Protocol.MEDIA_ERROR)
                    .put(Protocol.F_ID, id == null ? "" : id).put(Protocol.F_ERROR, message));
        } catch (JSONException ignored) {
            // cannot happen
        }
    }
}
