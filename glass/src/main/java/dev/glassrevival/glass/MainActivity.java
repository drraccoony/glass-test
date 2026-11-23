package dev.glassrevival.glass;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.speech.RecognizerIntent;
import android.text.format.DateFormat;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;

/**
 * Card viewer. Card 0 shows the link status and pair code, the rest are
 * notifications, newest first. Swipe to move, tap for options, swipe down to leave.
 *
 * <p>Tapping a notification opens a menu: reply by voice, quick replies and dismiss.
 * A chosen reply waits two seconds before sending, so a swipe down can cancel it.
 *
 * <p>When launched by {@link LinkService} for a new alert, it opens on that card and
 * closes itself after a few seconds unless you touch the pad.
 */
public class MainActivity extends Activity implements NotificationStore.Listener, Gestures.Callback {

    static final String EXTRA_ALERT_KEY = "alert_key";
    private static final long ALERT_TIMEOUT_MS = 10000;
    private static final long SEND_DELAY_MS = 2000;
    private static final long REPLY_TIMEOUT_MS = 15000;
    private static final long RESULT_SHOW_MS = 1800;
    private static final int REQ_VOICE = 1;

    private enum Mode { CARDS, MENU, SENDING, WAITING, RESULT }

    private static final class MenuItem {
        static final int VOICE = 0;
        static final int QUICK = 1;
        static final int DISMISS = 2;

        final int kind;
        final String label;
        final String text;

        MenuItem(int kind, String label, String text) {
            this.kind = kind;
            this.label = label;
            this.text = text;
        }
    }

    private final NotificationStore store = NotificationStore.get();
    private final Handler handler = new Handler();
    private final Gestures gestures = new Gestures(this);
    private final Runnable alertTimeout = this::finish;
    private final Runnable sendNow = this::sendPendingReply;
    private final Runnable replyTimeout = () -> showResult(false, "No answer from your phone");
    private final Runnable backToCards = () -> {
        mode = Mode.CARDS;
        render();
    };

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

    private Mode mode = Mode.CARDS;
    private String actionKey; // the notification the menu or reply is about
    private List<MenuItem> menu = Collections.emptyList();
    private int menuPos;
    private String replyText;
    private boolean resultOk;
    private String resultMessage;

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
        // Don't yank the user out of a menu or a reply in progress.
        if (mode != Mode.CARDS) key = null;
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
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override
    public void onStoreChanged() {
        refresh();
    }

    @Override
    public void onReplyResult(String key, boolean ok, String error) {
        if (mode != Mode.WAITING || !key.equals(actionKey)) return;
        handler.removeCallbacks(replyTimeout);
        showResult(ok, ok ? "" : error);
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
        // The notification went away (cleared on the phone) while its menu was open.
        if (mode == Mode.MENU && findIndex(actionKey) < 0) mode = Mode.CARDS;
        render();
    }

    private int findIndex(String key) {
        if (key == null) return -1;
        for (int i = 0; i < notes.size(); i++) {
            if (notes.get(i).key.equals(key)) return i;
        }
        return -1;
    }

    private NotificationStore.Note noteFor(String key) {
        int i = findIndex(key);
        return i < 0 ? null : notes.get(i);
    }

    // ---- Rendering ------------------------------------------------------------------------

    private void render() {
        NotificationStore.LinkState link = store.linkState();
        linkDot.setBackgroundColor(getResources().getColor(
                link == NotificationStore.LinkState.CONNECTED ? R.color.glass_green : R.color.glass_red));

        switch (mode) {
            case MENU:
                renderMenu();
                return;
            case SENDING:
            case WAITING:
                renderSending();
                return;
            case RESULT:
                renderResult();
                return;
            default:
                break;
        }
        if (index == 0) {
            renderStatus(link);
            return;
        }
        NotificationStore.Note note = notes.get(index - 1);
        setIcon(note.icon);
        app.setText(appName(note));
        time.setText(relativeTime(note.when));
        title.setText(note.title);
        title.setVisibility(note.title.isEmpty() ? View.GONE : View.VISIBLE);
        text.setText(note.text);
        footer.setText(note.canReply ? "Tap to reply or dismiss" : "Tap for options");
        position.setText(index + " / " + notes.size());
    }

    private void renderMenu() {
        NotificationStore.Note note = noteFor(actionKey);
        MenuItem item = menu.get(menuPos);
        if (note != null) {
            setIcon(note.icon);
            app.setText(note.title.isEmpty() ? appName(note) : note.title);
        }
        time.setText("");
        title.setVisibility(View.VISIBLE);
        title.setText(item.label);
        text.setText(item.kind == MenuItem.VOICE ? "Speak after the prompt" : "");
        footer.setText("Tap to choose, swipe down to go back");
        position.setText((menuPos + 1) + " / " + menu.size());
    }

    private void renderSending() {
        NotificationStore.Note note = noteFor(actionKey);
        if (note != null) {
            setIcon(note.icon);
            app.setText("Reply to " + (note.title.isEmpty() ? appName(note) : note.title));
        }
        time.setText("");
        title.setVisibility(View.VISIBLE);
        title.setText("Sending…");
        text.setText("“" + replyText + "”");
        footer.setText(mode == Mode.SENDING ? "Swipe down to cancel" : "Waiting for your phone");
        position.setText("");
    }

    private void renderResult() {
        time.setText("");
        title.setVisibility(View.VISIBLE);
        title.setText(resultOk ? "Sent" : "Not sent");
        text.setText(resultMessage);
        footer.setText("");
        position.setText("");
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

    private static String appName(NotificationStore.Note note) {
        return note.app.isEmpty() ? note.pkg : note.app;
    }

    private String relativeTime(long when) {
        long minutes = (System.currentTimeMillis() - when) / 60000;
        if (minutes < 1) return "now";
        if (minutes < 60) return minutes + "m";
        if (minutes < 24 * 60) return (minutes / 60) + "h";
        return DateFormat.getDateFormat(this).format(when);
    }

    // ---- Menu and replies -----------------------------------------------------------------

    private void openMenu(NotificationStore.Note note) {
        List<MenuItem> items = new ArrayList<>();
        if (note.canReply && voiceAvailable()) {
            items.add(new MenuItem(MenuItem.VOICE, "Reply by voice", null));
        }
        for (String choice : note.choices) {
            items.add(new MenuItem(MenuItem.QUICK, "“" + choice + "”", choice));
        }
        items.add(new MenuItem(MenuItem.DISMISS, "Dismiss", null));
        actionKey = note.key;
        menu = items;
        menuPos = 0;
        mode = Mode.MENU;
        render();
    }

    private void choose(MenuItem item) {
        switch (item.kind) {
            case MenuItem.VOICE:
                startVoiceReply();
                break;
            case MenuItem.QUICK:
                queueReply(item.text);
                break;
            default:
                store.remove(actionKey);
                LinkService.sendDismiss(actionKey);
                mode = Mode.CARDS;
                render();
                break;
        }
    }

    private boolean voiceAvailable() {
        return !getPackageManager().queryIntentActivities(
                new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH), 0).isEmpty();
    }

    private void startVoiceReply() {
        NotificationStore.Note note = noteFor(actionKey);
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PROMPT,
                        note == null || note.title.isEmpty() ? "Reply" : "Reply to " + note.title);
        try {
            startActivityForResult(intent, REQ_VOICE);
        } catch (ActivityNotFoundException e) {
            showResult(false, "Voice input isn't available on this Glass");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_VOICE) return;
        ArrayList<String> results = data == null ? null
                : data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
        if (resultCode != RESULT_OK || results == null || results.isEmpty()
                || results.get(0).trim().isEmpty()) {
            // Cancelled, or the recognizer couldn't reach its server. Stay in the menu.
            Toast.makeText(this, "Didn't catch that", Toast.LENGTH_SHORT).show();
            return;
        }
        queueReply(results.get(0).trim());
    }

    private void queueReply(String reply) {
        replyText = reply;
        mode = Mode.SENDING;
        handler.postDelayed(sendNow, SEND_DELAY_MS);
        render();
    }

    private void sendPendingReply() {
        if (mode != Mode.SENDING) return;
        if (!LinkService.sendReply(actionKey, replyText)) {
            showResult(false, "Phone isn't connected");
            return;
        }
        mode = Mode.WAITING;
        handler.postDelayed(replyTimeout, REPLY_TIMEOUT_MS);
        render();
    }

    private void showResult(boolean ok, String message) {
        handler.removeCallbacks(sendNow);
        handler.removeCallbacks(replyTimeout);
        resultOk = ok;
        resultMessage = message == null ? "" : message;
        mode = Mode.RESULT;
        render();
        handler.postDelayed(backToCards, RESULT_SHOW_MS);
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
        if (mode == Mode.MENU) {
            if (menuPos < menu.size() - 1) menuPos++;
            render();
        } else if (mode == Mode.CARDS && index < notes.size()) {
            index++;
            render();
        }
    }

    @Override
    public void onPrevious() {
        userInteracted();
        if (mode == Mode.MENU) {
            if (menuPos > 0) menuPos--;
            render();
        } else if (mode == Mode.CARDS && index > 0) {
            index--;
            render();
        }
    }

    @Override
    public void onTap() {
        userInteracted();
        switch (mode) {
            case MENU:
                choose(menu.get(menuPos));
                return;
            case CARDS:
                break;
            default:
                return; // sending or showing a result
        }
        if (index == 0) {
            if (store.linkState() != NotificationStore.LinkState.CONNECTED) requestDiscoverable();
            return;
        }
        openMenu(notes.get(index - 1));
    }

    @Override
    public void onBack() {
        switch (mode) {
            case SENDING:
                handler.removeCallbacks(sendNow);
                mode = Mode.MENU;
                render();
                return;
            case MENU:
                mode = Mode.CARDS;
                render();
                return;
            case WAITING:
            case RESULT:
                return; // already sent; let it finish
            default:
                finish();
        }
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
