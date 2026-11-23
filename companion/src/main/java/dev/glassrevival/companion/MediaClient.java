package dev.glassrevival.companion;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;
import android.util.LruCache;
import dev.glassrevival.protocol.Protocol;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * The phone's side of browsing Glass photos and videos: lists them, fetches thumbnails,
 * and downloads files (Wi-Fi when both are on the same network, Bluetooth otherwise).
 * Downloads are cached in the app's cache folder.
 */
final class MediaClient implements LinkManager.MessageSink, LinkManager.Listener {

    private static final String TAG = "GlassLink";

    static final class Item {
        final String id;
        final String kind;
        final String name;
        final long taken;
        final long size;
        final int width;
        final int height;
        final long duration;

        Item(JSONObject o) {
            id = o.optString(Protocol.F_ID);
            kind = o.optString(Protocol.F_KIND, Protocol.KIND_PHOTO);
            name = o.optString(Protocol.F_NAME, id);
            taken = o.optLong(Protocol.F_TAKEN, 0);
            size = o.optLong(Protocol.F_SIZE, 0);
            width = o.optInt(Protocol.F_WIDTH, 0);
            height = o.optInt(Protocol.F_HEIGHT, 0);
            duration = o.optLong(Protocol.F_DURATION, 0);
        }

        boolean isVideo() {
            return Protocol.KIND_VIDEO.equals(kind);
        }
    }

    /** Gallery updates, on the main thread. */
    interface Listener {
        void onItems(List<Item> items);

        void onThumb(String id);

        /** After a delete finished on Glass. */
        default void onDeleted(int deleted, int failed) {
        }
    }

    /** One download's progress, on the main thread. */
    interface DownloadListener {
        void onProgress(long done, long total, boolean viaWifi);

        void onDone(File file);

        void onError(String message);
    }

    private final Context context;
    private final LinkManager link;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private final LruCache<String, Bitmap> thumbs = new LruCache<String, Bitmap>(24 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };
    private final Set<String> pendingThumbs = Collections.synchronizedSet(new HashSet<String>());
    private volatile List<Item> items = Collections.emptyList();
    private volatile boolean listLoaded;

    // The one download in progress (guarded by this)
    private Item dlItem;
    private DownloadListener dlListener;
    private File dlTemp;
    private OutputStream dlOut;
    private long dlDone;
    private long dlTotal;

    MediaClient(Context context, LinkManager link) {
        this.context = context.getApplicationContext();
        this.link = link;
        link.addSink(this);
        link.addListener(this);
    }

    void addListener(Listener l) {
        listeners.add(l);
    }

    void removeListener(Listener l) {
        listeners.remove(l);
    }

    List<Item> items() {
        return items;
    }

    boolean listLoaded() {
        return listLoaded;
    }

    Item find(String id) {
        for (Item it : items) if (it.id.equals(id)) return it;
        return null;
    }

    /** Permanently deletes these items from Glass. Listeners get onItems when it's done. */
    void deleteOnGlass(List<String> ids) {
        try {
            link.send(new JSONObject().put(Protocol.F_TYPE, Protocol.MEDIA_DELETE)
                    .put(Protocol.F_IDS, new JSONArray(ids)));
        } catch (JSONException ignored) {
            // cannot happen
        }
    }

    void refreshList() {
        send(new Object[] {Protocol.F_TYPE, Protocol.MEDIA_LIST, Protocol.F_OFFSET, 0, Protocol.F_LIMIT, 1000});
    }

    /** The cached thumbnail, or null (one is then requested and onThumb follows). */
    Bitmap thumb(String id) {
        Bitmap b = thumbs.get(id);
        if (b == null && link.isConnected() && pendingThumbs.add(id)) {
            send(new Object[] {Protocol.F_TYPE, Protocol.MEDIA_THUMB, Protocol.F_ID, id});
        }
        return b;
    }

    File cacheFile(Item it) {
        File dir = new File(context.getCacheDir(), "media");
        dir.mkdirs();
        return new File(dir, it.name);
    }

    /** Starts a download, replacing any other. Cached files complete immediately. */
    synchronized void download(Item it, DownloadListener listener) {
        cancelDownload();
        File done = cacheFile(it);
        if (done.isFile() && done.length() == it.size) {
            main.post(() -> listener.onDone(done));
            return;
        }
        if (!link.isConnected()) {
            main.post(() -> listener.onError("Glass isn't connected"));
            return;
        }
        dlItem = it;
        dlListener = listener;
        dlTotal = it.size;
        dlDone = 0;
        send(new Object[] {Protocol.F_TYPE, Protocol.MEDIA_GET, Protocol.F_ID, it.id, Protocol.F_BT, false});
    }

    synchronized void cancelDownload() {
        if (dlItem == null) return;
        send(new Object[] {Protocol.F_TYPE, Protocol.MEDIA_CANCEL, Protocol.F_ID, dlItem.id});
        closeTemp(true);
        dlItem = null;
        dlListener = null;
    }

    // ---- Messages from Glass (reader thread) ----------------------------------------------

    @Override
    public void onMessage(JSONObject msg) {
        String type = msg.optString(Protocol.F_TYPE);
        try {
            if (Protocol.MEDIA_ITEMS.equals(type)) {
                onItems(msg);
            } else if (Protocol.MEDIA_THUMB_DATA.equals(type)) {
                onThumbData(msg);
            } else if (Protocol.MEDIA_FILE.equals(type)) {
                onFile(msg);
            } else if (Protocol.MEDIA_CHUNK.equals(type)) {
                onChunk(msg);
            } else if (Protocol.MEDIA_DELETED.equals(type)) {
                onDeleted(msg);
            } else if (Protocol.MEDIA_ERROR.equals(type)) {
                String id = msg.optString(Protocol.F_ID);
                pendingThumbs.remove(id);
                failIfCurrent(id, msg.optString(Protocol.F_ERROR, "Glass reported an error"));
            }
        } catch (JSONException | IOException e) {
            Log.w(TAG, "Media message failed", e);
        }
    }

    @Override
    public void onLinkChanged() {
        if (!link.isConnected()) {
            pendingThumbs.clear();
            synchronized (this) {
                if (dlItem != null) failIfCurrent(dlItem.id, "Lost the connection to Glass");
            }
        }
    }

    private void onItems(JSONObject msg) throws JSONException {
        JSONArray arr = msg.getJSONArray(Protocol.F_ITEMS);
        List<Item> list = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) list.add(new Item(arr.getJSONObject(i)));
        final List<Item> result = Collections.unmodifiableList(list);
        main.post(() -> {
            items = result;
            listLoaded = true;
            for (Listener l : listeners) l.onItems(result);
        });
    }

    private void onDeleted(JSONObject msg) {
        JSONArray ids = msg.optJSONArray(Protocol.F_IDS);
        final Set<String> gone = new HashSet<>();
        for (int i = 0; ids != null && i < ids.length(); i++) gone.add(ids.optString(i));
        final int failed = msg.optJSONArray(Protocol.F_FAILED) == null ? 0
                : msg.optJSONArray(Protocol.F_FAILED).length();
        main.post(() -> {
            List<Item> keep = new ArrayList<>();
            for (Item it : items) {
                if (gone.contains(it.id)) {
                    cacheFile(it).delete();
                    thumbs.remove(it.id);
                } else {
                    keep.add(it);
                }
            }
            items = Collections.unmodifiableList(keep);
            for (Listener l : listeners) l.onItems(items);
            for (Listener l : listeners) l.onDeleted(gone.size(), failed);
        });
    }

    private void onThumbData(JSONObject msg) {
        final String id = msg.optString(Protocol.F_ID);
        byte[] jpeg = Base64.decode(msg.optString(Protocol.F_DATA, ""), Base64.DEFAULT);
        Bitmap b = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
        pendingThumbs.remove(id);
        if (b == null) return;
        thumbs.put(id, b);
        main.post(() -> {
            for (Listener l : listeners) l.onThumb(id);
        });
    }

    private synchronized void onFile(JSONObject msg) throws IOException {
        if (dlItem == null || !dlItem.id.equals(msg.optString(Protocol.F_ID))) return;
        dlTotal = msg.optLong(Protocol.F_SIZE, dlTotal);
        final String url = msg.optString(Protocol.F_URL, null);
        if (url == null) {
            openTemp(); // chunks follow over Bluetooth
            progress(false);
            return;
        }
        final Item item = dlItem;
        new Thread(() -> downloadOverWifi(item, url), "GlassLink-wifi-download").start();
    }

    private synchronized void onChunk(JSONObject msg) throws IOException {
        if (dlItem == null || !dlItem.id.equals(msg.optString(Protocol.F_ID))) return;
        if (dlOut == null) openTemp();
        long offset = msg.optLong(Protocol.F_OFFSET, -1);
        if (offset != dlDone) {
            failIfCurrent(dlItem.id, "Transfer got out of order");
            return;
        }
        byte[] data = Base64.decode(msg.optString(Protocol.F_DATA, ""), Base64.DEFAULT);
        dlOut.write(data);
        dlDone += data.length;
        progress(false);
        if (msg.optBoolean(Protocol.F_LAST, false)) finish();
    }

    private void downloadOverWifi(Item item, String url) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(4000);
            c.setReadTimeout(15000);
            try {
                if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());
                InputStream in = c.getInputStream();
                byte[] buf = new byte[64 * 1024];
                int n;
                synchronized (this) {
                    if (dlItem != item) return;
                    openTemp();
                }
                while ((n = in.read(buf)) > 0) {
                    synchronized (this) {
                        if (dlItem != item) return; // cancelled
                        dlOut.write(buf, 0, n);
                        dlDone += n;
                        progress(true);
                    }
                }
                synchronized (this) {
                    if (dlItem == item) finish();
                }
            } finally {
                c.disconnect();
            }
        } catch (IOException e) {
            // Different networks, client isolation, a firewall: fall back to Bluetooth.
            Log.i(TAG, "Wi-Fi download failed (" + e + "), using Bluetooth");
            synchronized (this) {
                if (dlItem != item) return;
                closeTemp(true);
                dlDone = 0;
                progress(false);
                send(new Object[] {Protocol.F_TYPE, Protocol.MEDIA_GET, Protocol.F_ID, item.id, Protocol.F_BT, true});
            }
        }
    }

    // ---- Helpers (callers hold the lock) --------------------------------------------------

    private void openTemp() throws IOException {
        closeTemp(true);
        dlTemp = new File(cacheFile(dlItem).getPath() + ".part");
        dlOut = new FileOutputStream(dlTemp);
        dlDone = 0;
    }

    private void closeTemp(boolean delete) {
        if (dlOut != null) {
            try {
                dlOut.close();
            } catch (IOException ignored) {
                // nothing to do
            }
            dlOut = null;
        }
        if (delete && dlTemp != null) dlTemp.delete();
        if (delete) dlTemp = null;
    }

    private void finish() {
        closeTemp(false);
        final File done = cacheFile(dlItem);
        done.delete();
        if (dlTemp == null || !dlTemp.renameTo(done)) {
            failIfCurrent(dlItem.id, "Couldn't save the file");
            return;
        }
        final DownloadListener l = dlListener;
        dlItem = null;
        dlListener = null;
        dlTemp = null;
        main.post(() -> l.onDone(done));
    }

    private void progress(final boolean wifi) {
        final DownloadListener l = dlListener;
        final long done = dlDone;
        final long total = dlTotal;
        if (l != null) main.post(() -> l.onProgress(done, total, wifi));
    }

    private void failIfCurrent(String id, final String message) {
        synchronized (this) {
            if (dlItem == null || !dlItem.id.equals(id)) return;
            final DownloadListener l = dlListener;
            closeTemp(true);
            dlItem = null;
            dlListener = null;
            main.post(() -> l.onError(message));
        }
    }

    private void send(Object[] keyValues) {
        try {
            JSONObject o = new JSONObject();
            for (int i = 0; i < keyValues.length; i += 2) o.put((String) keyValues[i], keyValues[i + 1]);
            link.send(o);
        } catch (JSONException ignored) {
            // cannot happen
        }
    }
}
