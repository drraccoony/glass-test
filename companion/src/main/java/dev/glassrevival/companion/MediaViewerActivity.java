package dev.glassrevival.companion;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.text.format.DateFormat;
import android.text.format.Formatter;
import android.view.View;
import android.widget.Button;
import android.widget.MediaController;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Full-screen view of Glass photos and videos. Pinch or double-tap to zoom, swipe to move
 * between items (in the grid's current filter), and Save, Share, Delete or show Info.
 */
public class MediaViewerActivity extends Activity implements MediaClient.DownloadListener, MediaClient.Listener {

    static final String EXTRA_IDS = "ids";
    static final String EXTRA_INDEX = "index";

    private MediaClient media;
    private final List<String> ids = new ArrayList<>();
    private int index;
    private MediaClient.Item item;
    private File file;

    private ZoomImageView photo;
    private VideoView video;
    private View loading;
    private ProgressBar progress;
    private TextView progressText;
    private TextView title;
    private TextView subtitle;
    private Button save;
    private Button share;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_media_viewer);
        media = CompanionApp.media(this);
        String[] list = getIntent().getStringArrayExtra(EXTRA_IDS);
        if (list != null) Collections.addAll(ids, list);
        index = savedInstanceState != null ? savedInstanceState.getInt(EXTRA_INDEX)
                : getIntent().getIntExtra(EXTRA_INDEX, 0);
        photo = findViewById(R.id.viewer_photo);
        video = findViewById(R.id.viewer_video);
        loading = findViewById(R.id.viewer_loading);
        progress = findViewById(R.id.viewer_progress);
        progressText = findViewById(R.id.viewer_progress_text);
        title = findViewById(R.id.viewer_title);
        subtitle = findViewById(R.id.viewer_subtitle);
        save = findViewById(R.id.viewer_save);
        share = findViewById(R.id.viewer_share);
        findViewById(R.id.viewer_back).setOnClickListener(v -> finish());
        save.setOnClickListener(v -> {
            boolean ok = MediaSaver.save(this, item, file);
            Toast.makeText(this, ok ? "Saved to " + (item.isVideo() ? "Movies" : "Pictures") + "/GlassLink"
                    : "Couldn't save", Toast.LENGTH_SHORT).show();
        });
        share.setOnClickListener(v -> startActivity(MediaSaver.shareIntent(
                Collections.singletonList(item), Collections.singletonList(file))));
        findViewById(R.id.viewer_delete).setOnClickListener(v -> confirmDelete());
        findViewById(R.id.viewer_info).setOnClickListener(v -> showInfo());
        photo.setSwipeListener(this::move);
        media.addListener(this);
        show();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putInt(EXTRA_INDEX, index);
    }

    @Override
    protected void onDestroy() {
        media.removeListener(this);
        if (file == null) media.cancelDownload();
        super.onDestroy();
    }

    private void move(int direction) {
        int next = index + direction;
        if (next < 0 || next >= ids.size()) return;
        index = next;
        show();
    }

    private void show() {
        item = index >= 0 && index < ids.size() ? media.find(ids.get(index)) : null;
        if (item == null) {
            finish();
            return;
        }
        file = null;
        video.stopPlayback();
        video.setVisibility(View.GONE);
        photo.setVisibility(View.VISIBLE);
        photo.setImageBitmap(media.thumb(item.id)); // blurry preview while loading
        loading.setVisibility(View.VISIBLE);
        progress.setVisibility(View.VISIBLE);
        progress.setIndeterminate(true);
        progressText.setText("Asking Glass for the file");
        save.setEnabled(false);
        share.setEnabled(false);
        title.setText(DateFormat.getMediumDateFormat(this).format(item.taken) + "  "
                + DateFormat.getTimeFormat(this).format(item.taken));
        subtitle.setText((item.isVideo() ? "Video" : "Photo") + ", "
                + Formatter.formatShortFileSize(this, item.size)
                + (ids.size() > 1 ? "   " + (index + 1) + " of " + ids.size() : ""));
        media.download(item, this);
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

    // Gallery updates (after a delete)
    @Override
    public void onItems(List<MediaClient.Item> items) {
    }

    @Override
    public void onThumb(String id) {
        if (item != null && id.equals(item.id) && file == null) photo.setImageBitmap(media.thumb(id));
    }

    @Override
    public void onDeleted(int deleted, int failed) {
        if (failed > 0) {
            Toast.makeText(this, "Glass couldn't delete that file", Toast.LENGTH_SHORT).show();
            return;
        }
        ids.remove(index);
        if (ids.isEmpty()) {
            finish();
            return;
        }
        index = Math.min(index, ids.size() - 1);
        show();
    }

    private void confirmDelete() {
        new AlertDialog.Builder(this)
                .setTitle("Delete from Glass?")
                .setMessage("This permanently removes " + item.name + " from Glass. Copies you saved to "
                        + "the phone are kept.")
                .setPositiveButton("Delete", (d, w) -> media.deleteOnGlass(Collections.singletonList(item.id)))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showInfo() {
        StringBuilder sb = new StringBuilder();
        sb.append(item.name)
                .append("\n\n").append(DateFormat.getLongDateFormat(this).format(item.taken))
                .append(", ").append(DateFormat.getTimeFormat(this).format(item.taken));
        if (item.width > 0) sb.append("\n").append(item.width).append(" × ").append(item.height);
        if (item.isVideo()) {
            long s = item.duration / 1000;
            sb.append("\n").append(s / 60).append(" min ").append(s % 60).append(" s");
        }
        sb.append("\n").append(Formatter.formatFileSize(this, item.size));
        if (item.name.endsWith("_x.jpg")) sb.append("\n\nIncludes what the Glass display showed.");
        new AlertDialog.Builder(this).setTitle("Info").setMessage(sb)
                .setPositiveButton(android.R.string.ok, null).show();
    }

    /** Decodes at most about twice screen size, enough to zoom in without blowing up memory. */
    private Bitmap decode(File f) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), o);
        int max = 2 * Math.max(getResources().getDisplayMetrics().widthPixels,
                getResources().getDisplayMetrics().heightPixels);
        int sample = 1;
        while (Math.max(o.outWidth, o.outHeight) / (sample * 2) >= max) sample *= 2;
        o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        return BitmapFactory.decodeFile(f.getPath(), o);
    }
}
