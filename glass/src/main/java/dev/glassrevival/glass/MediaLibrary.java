package dev.glassrevival.glass;

import android.content.ContentResolver;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.ThumbnailUtils;
import android.provider.MediaStore;
import dev.glassrevival.protocol.Protocol;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Photos and videos taken on Glass, read from Android's media index. */
final class MediaLibrary {

    static final class Item {
        String id;     // "p123" or "v45"
        String kind;   // Protocol.KIND_PHOTO or KIND_VIDEO
        long rowId;
        String path;
        String name;
        String mime;
        long taken;    // ms
        long size;
        int width;
        int height;
        long duration; // ms, videos only
    }

    private static final int THUMB_PX = 320;

    private final ContentResolver resolver;

    MediaLibrary(ContentResolver resolver) {
        this.resolver = resolver;
    }

    /** Everything, newest first. */
    List<Item> all() {
        List<Item> out = new ArrayList<>();
        query(out, false, null);
        query(out, true, null);
        Collections.sort(out, new Comparator<Item>() {
            @Override
            public int compare(Item a, Item b) {
                return Long.compare(b.taken, a.taken);
            }
        });
        return out;
    }

    Item find(String id) {
        if (id == null || id.length() < 2) return null;
        boolean video = id.charAt(0) == 'v';
        if (!video && id.charAt(0) != 'p') return null;
        long rowId;
        try {
            rowId = Long.parseLong(id.substring(1));
        } catch (NumberFormatException e) {
            return null;
        }
        List<Item> out = new ArrayList<>();
        query(out, video, rowId);
        return out.isEmpty() ? null : out.get(0);
    }

    private void query(List<Item> out, boolean video, Long onlyId) {
        String[] cols = video
                ? new String[] {MediaStore.Video.Media._ID, MediaStore.Video.Media.DATA,
                        MediaStore.Video.Media.DATE_MODIFIED, MediaStore.Video.Media.SIZE,
                        MediaStore.Video.Media.WIDTH, MediaStore.Video.Media.HEIGHT,
                        MediaStore.Video.Media.MIME_TYPE, MediaStore.Video.Media.DURATION}
                : new String[] {MediaStore.Images.Media._ID, MediaStore.Images.Media.DATA,
                        MediaStore.Images.Media.DATE_MODIFIED, MediaStore.Images.Media.SIZE,
                        MediaStore.Images.Media.WIDTH, MediaStore.Images.Media.HEIGHT,
                        MediaStore.Images.Media.MIME_TYPE};
        String where = onlyId == null ? null : "_id=?";
        String[] args = onlyId == null ? null : new String[] {Long.toString(onlyId)};
        Cursor c = resolver.query(video ? MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                : MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cols, where, args, null);
        if (c == null) return;
        try {
            while (c.moveToNext()) {
                Item it = new Item();
                it.rowId = c.getLong(0);
                it.id = (video ? "v" : "p") + it.rowId;
                it.kind = video ? Protocol.KIND_VIDEO : Protocol.KIND_PHOTO;
                it.path = c.getString(1);
                if (it.path == null || !new File(it.path).isFile()) continue;
                it.name = new File(it.path).getName();
                it.taken = c.getLong(2) * 1000L;
                it.size = c.getLong(3);
                if (it.size <= 0) it.size = new File(it.path).length();
                it.width = c.getInt(4);
                it.height = c.getInt(5);
                it.mime = c.getString(6);
                if (it.mime == null) it.mime = video ? "video/mp4" : "image/jpeg";
                if (video) it.duration = c.getLong(7);
                out.add(it);
            }
        } finally {
            c.close();
        }
    }

    /** A small JPEG preview, or null. */
    byte[] thumbnail(Item it) {
        Bitmap b;
        if (Protocol.KIND_VIDEO.equals(it.kind)) {
            b = MediaStore.Video.Thumbnails.getThumbnail(resolver, it.rowId,
                    MediaStore.Video.Thumbnails.MINI_KIND, null);
            if (b == null) b = ThumbnailUtils.createVideoThumbnail(it.path, MediaStore.Video.Thumbnails.MINI_KIND);
        } else {
            b = MediaStore.Images.Thumbnails.getThumbnail(resolver, it.rowId,
                    MediaStore.Images.Thumbnails.MINI_KIND, null);
            if (b == null) {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inSampleSize = 8;
                b = BitmapFactory.decodeFile(it.path, o);
            }
        }
        if (b == null) return null;
        float scale = Math.min(1f, (float) THUMB_PX / Math.max(b.getWidth(), b.getHeight()));
        if (scale < 1f) {
            Bitmap small = Bitmap.createScaledBitmap(b, Math.round(b.getWidth() * scale),
                    Math.round(b.getHeight() * scale), true);
            if (small != b) b.recycle();
            b = small;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        b.compress(Bitmap.CompressFormat.JPEG, 75, out);
        b.recycle();
        return out.toByteArray();
    }
}
