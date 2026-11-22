package dev.glassrevival.glass;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.text.format.DateFormat;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;

/**
 * Card viewer. Card 0 shows the link status and pair code, the rest are
 * notifications, newest first. Swipe to move, tap to dismiss, swipe down to leave.
 *
 * <p>When launched by {@link LinkService} for a new alert, it opens on that card and
 * closes itself after a few seconds unless you touch the pad.
 */
public class MainActivity extends Activity implements NotificationStore.Listener, Gestures.Callback {

    static final String EXTRA_ALERT_KEY = "alert_key";
    private static final long ALERT_TIMEOUT_MS = 10000;

    private final NotificationStore store = NotificationStore.get();
    private final Handler handler = new Handler();
    private final Gestures gestures = new Gestures(this);
    private final Runnable alertTimeout = this::finish;

    private ImageView icon;
    private TextView app;
    private TextView time;
    private TextView title;
    private TextView text;
    private View linkDot;
    private TextView footer;
    private TextView position;
    private View root;

    private List<NotificationStore.Note> notes = Collections.emptyList();
    private int index; // 0 is the status card
    private String pendingAlertKey;
    private boolean alertMode;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED);
        setContentView(R.layout.activity_main);
        root = findViewById(android.R.id.content);
        icon = (ImageView) findViewById(R.id.icon);
        app = (TextView) findViewById(R.id.app);
        time = (TextView) findViewById(R.id.time);
        title = (TextView) findViewById(R.id.title);
        text = (TextView) findViewById(R.id.text);
        linkDot = findViewById(R.id.link_dot);
        footer = (TextView) findViewById(R.id.footer);
        position = (TextView) findViewById(R.id.position);

        LinkService.start(this);
        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent intent) {
        String key = intent.getStringExtra(EXTRA_ALERT_KEY);
        handler.removeCallbacks(alertTimeout);
        alertMode = key != null;
        pendingAlertKey = key;
        if (alertMode) handler.postDelayed(alertTimeout, ALERT_TIMEOUT_MS);
        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        store.addListener(this);
        refresh();
    }

    @Override
    protected void onPause() {
        store.removeListener(this);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(alertTimeout);
        super.onDestroy();
    }

    @Override
    public void onStoreChanged() {
        refresh();
    }

    private void refresh() {
        String currentKey = index > 0 && index <= notes.size() ? notes.get(index - 1).key : null;
        notes = store.notes();
        String target = pendingAlertKey != null ? pendingAlertKey : currentKey;
        if (target != null) {
            int found = findIndex(target);
            if (found >= 0) {
                index = found + 1;
                if (target.equals(pendingAlertKey)) pendingAlertKey = null;
            }
        }
        index = Math.max(0, Math.min(index, notes.size()));
        render();
    }

    private int findIndex(String key) {
        for (int i = 0; i < notes.size(); i++) {
            if (notes.get(i).key.equals(key)) return i;
        }
        return -1;
    }

    private void render() {
        NotificationStore.LinkState link = store.linkState();
        linkDot.setBackgroundColor(getResources().getColor(
                link == NotificationStore.LinkState.CONNECTED ? R.color.glass_green : R.color.glass_red));

        if (index == 0) {
            renderStatus(link);
            return;
        }
        NotificationStore.Note note = notes.get(index - 1);
        setIcon(note.icon);
        app.setText(note.app.isEmpty() ? note.pkg : note.app);
        time.setText(relativeTime(note.when));
        title.setText(note.title);
        title.setVisibility(note.title.isEmpty() ? View.GONE : View.VISIBLE);
        text.setText(note.text);
        footer.setText("Tap to dismiss");
        position.setText(index + " / " + notes.size());
    }

    @SuppressLint({"MissingPermission", "HardwareIds"}) // the address is shown so the user can type it on the phone
    private void renderStatus(NotificationStore.LinkState link) {
        icon.setImageResource(R.drawable.ic_launcher);
        icon.setVisibility(View.VISIBLE);
        app.setText(R.string.app_name);
        time.setText(DateFormat.getTimeFormat(this).format(Calendar.getInstance().getTime()));
        title.setVisibility(View.VISIBLE);
        switch (link) {
            case CONNECTED:
                String peer = store.peerName();
                title.setText("Connected to " + (peer == null ? "your phone" : peer));
                break;
            case BLUETOOTH_OFF:
                title.setText("Bluetooth is off");
                break;
            default:
                title.setText("Waiting for your phone");
                break;
        }
        BluetoothAdapter bt = BluetoothAdapter.getDefaultAdapter();
        StringBuilder sb = new StringBuilder();
        sb.append("Pair code  ").append(PairCode.pretty(PairCode.get(this)));
        if (bt != null) {
            sb.append("\nGlass address  ").append(bt.getAddress());
        }
        if (link != NotificationStore.LinkState.CONNECTED) {
            sb.append("\nTap to make Glass discoverable");
        }
        text.setText(sb);
        int n = notes.size();
        footer.setText(n == 0 ? "No notifications" : n == 1 ? "1 notification, swipe to view"
                : n + " notifications, swipe to view");
        position.setText("");
    }

    private void setIcon(Bitmap bitmap) {
        if (bitmap == null) {
            icon.setVisibility(View.GONE);
        } else {
            icon.setImageBitmap(bitmap);
            icon.setVisibility(View.VISIBLE);
        }
    }

    private String relativeTime(long when) {
        long minutes = (System.currentTimeMillis() - when) / 60000;
        if (minutes < 1) return "now";
        if (minutes < 60) return minutes + "m";
        if (minutes < 24 * 60) return (minutes / 60) + "h";
        return DateFormat.getDateFormat(this).format(when);
    }

    // ---- Input ----------------------------------------------------------------------------

    private void userInteracted() {
        if (alertMode) {
            alertMode = false;
            handler.removeCallbacks(alertTimeout);
        }
    }

    @Override
    public boolean onGenericMotionEvent(MotionEvent event) {
        return gestures.onMotion(event, root.getWidth()) || super.onGenericMotionEvent(event);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        return gestures.onMotion(event, root.getWidth()) || super.onTouchEvent(event);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        return gestures.onKeyDown(keyCode, event) || super.onKeyDown(keyCode, event);
    }

    @Override
    public void onNext() {
        userInteracted();
        if (index < notes.size()) {
            index++;
            render();
        }
    }

    @Override
    public void onPrevious() {
        userInteracted();
        if (index > 0) {
            index--;
            render();
        }
    }

    @Override
    public void onTap() {
        userInteracted();
        if (index == 0) {
            if (store.linkState() != NotificationStore.LinkState.CONNECTED) requestDiscoverable();
            return;
        }
        NotificationStore.Note note = notes.get(index - 1);
        store.remove(note.key);
        LinkService.sendDismiss(note.key);
        // Stay at the same slot so the next card slides in; refresh() clamps the index.
    }

    @Override
    public void onBack() {
        finish();
    }

    private void requestDiscoverable() {
        Intent intent = new Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
                .putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300);
        try {
            startActivity(intent);
            Toast.makeText(this, "Discoverable for 5 minutes", Toast.LENGTH_SHORT).show();
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, "Use Settings > Bluetooth on Glass", Toast.LENGTH_LONG).show();
        }
    }
}
