package dev.glassrevival.companion;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/** Saving downloaded Glass media into the phone's gallery, and sharing it. */
final class MediaSaver {
    private MediaSaver() {}

    static String mime(MediaClient.Item it) {
        return it.isVideo() ? "video/mp4" : "image/jpeg";
    }

    /** Copies into Pictures/GlassLink or Movies/GlassLink. Needs Android 10+. */
    static boolean save(Context context, MediaClient.Item item, File file) {
        if (Build.VERSION.SDK_INT < 29) return false;
        ContentResolver cr = context.getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, file.getName());
        values.put(MediaStore.MediaColumns.MIME_TYPE, mime(item));
        values.put(MediaStore.MediaColumns.RELATIVE_PATH,
                (item.isVideo() ? Environment.DIRECTORY_MOVIES : Environment.DIRECTORY_PICTURES) + "/GlassLink");
        values.put(MediaStore.MediaColumns.DATE_TAKEN, item.taken);
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri collection = item.isVideo()
                ? MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                : MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        Uri uri = cr.insert(collection, values);
        if (uri == null) return false;
        try (InputStream in = new FileInputStream(file); OutputStream out = cr.openOutputStream(uri)) {
            if (out == null) throw new IOException("no stream");
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } catch (IOException e) {
            cr.delete(uri, null, null);
            return false;
        }
        values.clear();
        values.put(MediaStore.MediaColumns.IS_PENDING, 0);
        cr.update(uri, values, null, null);
        return true;
    }

    /** A share sheet for one or more downloaded files. */
    static Intent shareIntent(List<MediaClient.Item> items, List<File> files) {
        boolean videos = false;
        boolean photos = false;
        for (MediaClient.Item it : items) {
            if (it.isVideo()) videos = true; else photos = true;
        }
        String type = videos && photos ? "*/*" : videos ? "video/mp4" : "image/jpeg";
        Intent send;
        if (files.size() == 1) {
            send = new Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM,
                    MediaShareProvider.uriFor(files.get(0)));
        } else {
            ArrayList<Uri> uris = new ArrayList<>();
            for (File f : files) uris.add(MediaShareProvider.uriFor(f));
            send = new Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
        }
        send.setType(type).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        return Intent.createChooser(send, "Share");
    }
}
