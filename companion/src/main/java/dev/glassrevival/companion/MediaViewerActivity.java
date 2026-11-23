package dev.glassrevival.companion;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.text.format.DateFormat;
import android.text.format.Formatter;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.MediaController;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** Full-screen view of one Glass photo or video, with Save and Share. */
public class MediaViewerActivity extends Activity implements MediaClient.DownloadListener {

    static final String EXTRA_ID = "id";

    private MediaClient media;
    private MediaClient.Item item;
    private File file;

    private ImageView photo;
    private VideoView video;
    private View loading;
    private ProgressBar progress;
    private TextView progressText;
    private Button save;
    private Button share;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_media_viewer);
        media = CompanionApp.media(this);
        item = media.find(getIntent().getStringExtra(EXTRA_ID));
        if (item == null) {
            finish();
            return;
        }
        photo = findViewById(R.id.viewer_photo);
        video = findViewById(R.id.viewer_video);
        loading = findViewById(R.id.viewer_loading);
        progress = findViewById(R.id.viewer_progress);
        progressText = findViewById(R.id.viewer_progress_text);
        save = findViewById(R.id.viewer_save);
        share = findViewById(R.id.viewer_share);
        ((TextView) findViewById(R.id.viewer_title)).setText(
                DateFormat.getMediumDateFormat(this).format(item.taken) + "  "
                        + DateFormat.getTimeFormat(this).format(item.taken));
        ((TextView) findViewById(R.id.viewer_subtitle)).setText(
                (item.isVideo() ? "Video" : "Photo") + ", " + Formatter.formatShortFileSize(this, item.size));
        findViewById(R.id.viewer_back).setOnClickListener(v -> finish());
        save.setOnClickListener(v -> saveToPhone());
        share.setOnClickListener(v -> shareFile());
        save.setEnabled(false);
        share.setEnabled(false);
        Bitmap thumb = media.thumb(item.id);
        if (thumb != null) photo.setImageBitmap(thumb); // blurry preview while loading
        media.download(item, this);
    }

    @Override
    protected void onDestroy() {
        if (file == null && media != null && item != null) media.cancelDownload();
        super.onDestroy();
    }

    @Override
    public void onProgress(long done, long total, boolean viaWifi) {
        int pct = total > 0 ? (int) (done * 100 / total) : 0;
        progress.setIndeterminate(total <= 0 || done == 0);
        progress.setProgress(pct);
        progressText.setText((viaWifi ? "Downloading over Wi-Fi" : "Downloading over Bluetooth")
                + (total > 0 ? "  " + pct + "%" : ""));
    }

    @Override
    public void onDone(File f) {
        file = f;
        loading.setVisibility(View.GONE);
        save.setEnabled(true);
        share.setEnabled(true);
        if (item.isVideo()) {
            photo.setVisibility(View.GONE);
            video.setVisibility(View.VISIBLE);
            MediaController controller = new MediaController(this);
            controller.setAnchorView(video);
            video.setMediaController(controller);
            video.setVideoPath(f.getPath());
            video.start();
        } else {
            photo.setImageBitmap(decode(f));
        }
    }

    @Override
    public void onError(String message) {
        progress.setVisibility(View.GONE);
        progressText.setText(message);
    }

    /** Decodes at most about screen-sized, so 5 MP photos don't blow up memory. */
    private Bitmap decode(File f) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), o);
        int max = Math.max(getResources().getDisplayMetrics().widthPixels,
                getResources().getDisplayMetrics().heightPixels);
        int sample = 1;
        while (Math.max(o.outWidth, o.outHeight) / (sample * 2) >= max) sample *= 2;
        o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        return BitmapFactory.decodeFile(f.getPath(), o);
    }

    private void saveToPhone() {
        if (Build.VERSION.SDK_INT < 29) {
            Toast.makeText(this, "Saving needs Android 10 or newer; use Share instead", Toast.LENGTH_LONG).show();
            return;
        }
        ContentResolver cr = getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, file.getName());
        values.put(MediaStore.MediaColumns.MIME_TYPE, item.isVideo() ? "video/mp4" : "image/jpeg");
        values.put(MediaStore.MediaColumns.RELATIVE_PATH,
                (item.isVideo() ? Environment.DIRECTORY_MOVIES : Environment.DIRECTORY_PICTURES) + "/GlassLink");
        values.put(MediaStore.MediaColumns.DATE_TAKEN, item.taken);
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri collection = item.isVideo()
                ? MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                : MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        Uri uri = cr.insert(collection, values);
        if (uri == null) {
            Toast.makeText(this, "Couldn't save", Toast.LENGTH_SHORT).show();
            return;
        }
        try (InputStream in = new FileInputStream(file); OutputStream out = cr.openOutputStream(uri)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } catch (IOException | NullPointerException e) {
            cr.delete(uri, null, null);
            Toast.makeText(this, "Couldn't save", Toast.LENGTH_SHORT).show();
            return;
        }
        values.clear();
        values.put(MediaStore.MediaColumns.IS_PENDING, 0);
        cr.update(uri, values, null, null);
        Toast.makeText(this, "Saved to " + (item.isVideo() ? "Movies" : "Pictures") + "/GlassLink",
                Toast.LENGTH_SHORT).show();
    }

    private void shareFile() {
        Intent send = new Intent(Intent.ACTION_SEND)
                .setType(item.isVideo() ? "video/mp4" : "image/jpeg")
                .putExtra(Intent.EXTRA_STREAM, MediaShareProvider.uriFor(file))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(send, "Share"));
    }
}
