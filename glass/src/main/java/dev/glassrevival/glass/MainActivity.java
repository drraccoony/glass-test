package dev.glassrevival.glass;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.speech.RecognizerIntent;
import android.text.format.DateFormat;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ImageView;
import android.widget.TextView;
import com.google.android.glass.media.Sounds;
import com.google.android.glass.widget.CardBuilder;
import com.google.android.glass.widget.CardScrollAdapter;
import com.google.android.glass.widget.CardScrollView;
import com.google.android.glass.widget.Slider;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;

/**
 * GlassLink's cards, shown in the same scroller as the Glass timeline. Card 0 is the link
 * status, then the weather (once the phone has sent a forecast), then notifications,
 * newest first. Tap a notification for the Glass menu: reply by voice, quick replies or
 * dismiss. Replies use the Glass "Sending" card with a grace period; swipe down cancels.
 *
 * <p>When launched by {@link LinkService} for a new alert, it opens on that card and
 * closes itself after a few seconds unless you touch the pad.
 */
public class MainActivity extends Activity implements NotificationStore.Listener {

    static final String EXTRA_ALERT_KEY = "alert_key";
    private static final long ALERT_TIMEOUT_MS = 10000;
    private static final long REPLY_TIMEOUT_MS = 15000;
    private static final long RESULT_SHOW_MS = 1500;
    private static final long WEATHER_STALE_MS = 30 * 60 * 1000L;
    private static final int REQ_VOICE = 1;

    private static final int MENU_VOICE = 1;
    private static final int MENU_DISMISS = 2;
    private static final int MENU_CHOICE_BASE = 100;

    /** One card in the scroller. Equal cards keep their place when the list changes. */
    private static final class Card {
        static final int STATUS = 0;
        static final int WEATHER = 1;
        static final int NOTE = 2;

        final int kind;
        final NotificationStore.Note note;

        Card(int kind, NotificationStore.Note note) {
            this.kind = kind;
            this.note = note;
        }

        String id() {
            return kind == NOTE ? "note:" + note.key : "kind:" + kind;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Card && ((Card) o).id().equals(id());
        }

        @Override
        public int hashCode() {
            return id().hashCode();
        }
    }

    private final NotificationStore store = NotificationStore.get();
    private final Handler handler = new Handler();
    private final Runnable alertTimeout = this::finish;
    private final Runnable replyTimeout = () -> showResult(false, "No answer from your phone");
    private final Runnable backToCards = this::showCards;

    private AudioManager audio;
    private CardScrollView scroller;
    private final CardsAdapter adapter = new CardsAdapter();
    private List<Card> cards = new ArrayList<>();
    private List<NotificationStore.Note> notes = Collections.emptyList();
    private Weather weather;

    private String pendingAlertKey;
    private boolean alertMode;
    private boolean pendingWeatherCard;
    private boolean weatherRefreshing;

    // Reply flow: an overlay card replaces the scroller while a reply is sent.
    private String actionKey;
    private View overlay;
    private Slider.GracePeriod grace;
    private Slider.Indeterminate waiting;
    private String replyText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED);
        audio = (AudioManager) getSystemService(AUDIO_SERVICE);
        scroller = new CardScrollView(this);
        scroller.setAdapter(adapter);
        scroller.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                onCardTapped(position);
            }
        });
        setContentView(scroller);
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
        // "ok glass, check the weather" arrives through the WeatherAlias component.
        if (intent.getComponent() != null
                && intent.getComponent().getClassName().endsWith(".WeatherAlias")) {
            pendingWeatherCard = true;
            Weather w = store.weather();
            if (w == null || System.currentTimeMillis() - w.updated > WEATHER_STALE_MS) refreshWeather();
        }
        // Don't pull the user away from a reply in progress.
        if (overlay != null) key = null;
        alertMode = key != null;
        pendingAlertKey = key;
        if (alertMode) handler.postDelayed(alertTimeout, ALERT_TIMEOUT_MS);
        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        store.addListener(this);
        if (overlay == null) scroller.activate();
        refresh();
    }

    @Override
    protected void onPause() {
        store.removeListener(this);
        scroller.deactivate();
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

    // ---- Card list ------------------------------------------------------------------------

    private void refresh() {
        int selected = scroller.getSelectedItemPosition();
        Card current = selected >= 0 && selected < cards.size() ? cards.get(selected) : null;
        Weather previous = weather;
        notes = store.notes();
        weather = store.weather();
        if (weather != previous) weatherRefreshing = false;

        List<Card> next = new ArrayList<>();
        next.add(new Card(Card.STATUS, null));
        if (weather != null) next.add(new Card(Card.WEATHER, null));
        for (NotificationStore.Note n : notes) next.add(new Card(Card.NOTE, n));
        cards = next;

        int target = current == null ? 0 : cards.indexOf(current);
        if (target < 0) target = Math.min(selected, cards.size() - 1); // its card went away
        if (pendingAlertKey != null) {
            int i = indexOfNote(pendingAlertKey);
            if (i >= 0) {
                target = i;
                pendingAlertKey = null;
            }
        }
        if (pendingWeatherCard && weather != null) {
            target = 1;
            pendingWeatherCard = false;
        }
        adapter.notifyDataSetChanged();
        scroller.setSelection(Math.max(0, target));
        // The note behind an open reply flow was cleared on the phone.
        if (overlay != null && grace != null && noteFor(actionKey) == null) cancelReply();
    }

    private int indexOfNote(String key) {
        for (int i = 0; i < cards.size(); i++) {
            Card c = cards.get(i);
            if (c.kind == Card.NOTE && c.note.key.equals(key)) return i;
        }
        return -1;
    }

    private NotificationStore.Note noteFor(String key) {
        int i = indexOfNote(key);
        return i < 0 ? null : cards.get(i).note;
    }

    private final class CardsAdapter extends CardScrollAdapter {
        @Override
        public int getCount() {
            return cards.size();
        }

        @Override
        public Object getItem(int position) {
            return cards.get(position);
        }

        @Override
        public long getItemId(int position) {
            return cards.get(position).id().hashCode();
        }

        @Override
        public int getPosition(Object item) {
            int i = cards.indexOf(item);
            return i < 0 ? AdapterView.INVALID_POSITION : i;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View view = convertView != null ? convertView
                    : getLayoutInflater().inflate(R.layout.card, parent, false);
            bind(new CardViews(view), cards.get(position), position);
            return view;
        }
    }

    /** The views of one card (res/layout/card.xml), GlassLink's own card design. */
    private static final class CardViews {
        final ImageView icon;
        final TextView app;
        final TextView time;
        final TextView title;
        final TextView text;
        final View linkDot;
        final TextView footer;
        final TextView position;
        final ImageView weatherIcon;

        CardViews(View v) {
            icon = (ImageView) v.findViewById(R.id.icon);
            app = (TextView) v.findViewById(R.id.app);
            time = (TextView) v.findViewById(R.id.time);
            title = (TextView) v.findViewById(R.id.title);
            text = (TextView) v.findViewById(R.id.text);
            linkDot = v.findViewById(R.id.link_dot);
            footer = (TextView) v.findViewById(R.id.footer);
            position = (TextView) v.findViewById(R.id.position);
            weatherIcon = (ImageView) v.findViewById(R.id.weather_icon);
        }
    }

    private void bind(CardViews v, Card card, int index) {
        boolean connected = store.linkState() == NotificationStore.LinkState.CONNECTED;
        v.linkDot.setBackgroundColor(getResources().getColor(
                connected ? R.color.glass_green : R.color.glass_red));
        boolean weatherCard = card.kind == Card.WEATHER;
        v.weatherIcon.setVisibility(weatherCard ? View.VISIBLE : View.GONE);
        int iconRoom = weatherCard ? (int) (72 * getResources().getDisplayMetrics().density) : 0;
        v.title.setPadding(0, 0, iconRoom, 0);
        v.text.setPadding(0, 0, iconRoom, 0);
        v.text.setTextSize(weatherCard ? 18 : 20);
        v.title.setVisibility(View.VISIBLE);
        if (card.kind == Card.STATUS) {
            bindStatus(v, connected);
        } else if (weatherCard) {
            bindWeather(v);
        } else {
            bindNote(v, card.note, index);
        }
    }

    @SuppressLint({"MissingPermission", "HardwareIds"}) // the address is shown so the user can type it on the phone
    private void bindStatus(CardViews v, boolean connected) {
        v.icon.setImageResource(R.drawable.ic_launcher);
        v.icon.setVisibility(View.VISIBLE);
        v.app.setText(R.string.app_name);
        v.time.setText(DateFormat.getTimeFormat(this).format(Calendar.getInstance().getTime()));
        switch (store.linkState()) {
            case CONNECTED:
                String peer = store.peerName();
                v.title.setText("Connected to " + (peer == null ? "your phone" : peer));
                break;
            case BLUETOOTH_OFF:
                v.title.setText("Bluetooth is off");
                break;
            default:
                v.title.setText("Waiting for your phone");
                break;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Pair code  ").append(PairCode.pretty(PairCode.get(this)));
        BluetoothAdapter bt = BluetoothAdapter.getDefaultAdapter();
        if (bt != null) sb.append("\nGlass address  ").append(bt.getAddress());
        if (!connected) sb.append("\nTap to make Glass discoverable");
        v.text.setText(sb);
        int n = notes.size();
        v.footer.setText(n == 0 ? "No notifications" : n == 1 ? "1 notification, swipe to view"
                : n + " notifications, swipe to view");
        v.position.setText("");
    }

    private void bindWeather(CardViews v) {
        Weather w = weather;
        v.icon.setVisibility(View.GONE);
        v.weatherIcon.setImageResource(w.iconRes());
        v.app.setText(w.place.isEmpty() ? getString(R.string.weather) : w.place);
        v.time.setText(DateFormat.getTimeFormat(this).format(Calendar.getInstance().getTime()));
        v.title.setText(w.temp + "\u00B0 " + w.desc);
        StringBuilder sb = new StringBuilder();
        sb.append("Feels like ").append(w.feels).append('\u00B0')
                .append("   High ").append(w.high).append('\u00B0')
                .append("  Low ").append(w.low).append('\u00B0');
        StringBuilder line2 = new StringBuilder();
        if (w.rain >= 0) line2.append("Rain ").append(w.rain).append('%');
        if (w.wind >= 0) {
            if (line2.length() > 0) line2.append("   ");
            line2.append("Wind ").append(w.wind).append(' ').append(w.windUnit);
        }
        if (line2.length() > 0) sb.append('\n').append(line2);
        if (!w.hours.isEmpty()) {
            sb.append('\n');
            for (int i = 0; i < w.hours.size() && i < 3; i++) {
                Weather.Hour h = w.hours.get(i);
                if (i > 0) sb.append("    ");
                sb.append(h.label).append(' ').append(h.temp).append('\u00B0');
            }
        }
        v.text.setText(sb);
        long minutes = (System.currentTimeMillis() - w.updated) / 60000;
        String age = minutes < 1 ? "just now" : minutes < 60 ? minutes + "m ago"
                : minutes < 24 * 60 ? (minutes / 60) + "h ago" : "over a day ago";
        v.footer.setText(weatherRefreshing ? "Updating\u2026" : "Updated " + age + ", tap to refresh");
        v.position.setText("");
    }

    private void bindNote(CardViews v, NotificationStore.Note note, int index) {
        if (note.icon == null) {
            v.icon.setVisibility(View.GONE);
        } else {
            v.icon.setImageBitmap(note.icon);
            v.icon.setVisibility(View.VISIBLE);
        }
        v.app.setText(note.app.isEmpty() ? note.pkg : note.app);
        v.time.setText(relativeTime(note.when));
        v.title.setText(note.title);
        v.title.setVisibility(note.title.isEmpty() ? View.GONE : View.VISIBLE);
        v.text.setText(note.text);
        v.footer.setText(note.canReply || !note.choices.isEmpty() ? "Tap to reply or dismiss" : "Tap for options");
        int first = weather != null ? 2 : 1;
        v.position.setText((index - first + 1) + " / " + notes.size());
    }

    private String relativeTime(long when) {
        long minutes = (System.currentTimeMillis() - when) / 60000;
        if (minutes < 1) return "now";
        if (minutes < 60) return minutes + "m";
        if (minutes < 24 * 60) return (minutes / 60) + "h";
        return DateFormat.getDateFormat(this).format(when);
    }

    // ---- Taps and the Glass menu ----------------------------------------------------------

    private void onCardTapped(int position) {
        userInteracted();
        if (position < 0 || position >= cards.size()) return;
        Card card = cards.get(position);
        if (card.kind == Card.STATUS) {
            if (store.linkState() == NotificationStore.LinkState.CONNECTED) {
                play(Sounds.DISALLOWED);
            } else {
                play(Sounds.TAP);
                requestDiscoverable();
            }
        } else if (card.kind == Card.WEATHER) {
            play(Sounds.TAP);
            refreshWeather();
        } else {
            play(Sounds.TAP);
            actionKey = card.note.key;
            openOptionsMenu();
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        menu.clear();
        NotificationStore.Note note = noteFor(actionKey);
        if (note == null) return false;
        int order = 0;
        if (note.canReply && voiceAvailable()) {
            menu.add(Menu.NONE, MENU_VOICE, order++, "Reply").setIcon(R.drawable.ic_menu_reply);
        }
        for (int i = 0; i < note.choices.size(); i++) {
            menu.add(Menu.NONE, MENU_CHOICE_BASE + i, order++, note.choices.get(i))
                    .setIcon(R.drawable.ic_menu_quick);
        }
        menu.add(Menu.NONE, MENU_DISMISS, order, "Dismiss").setIcon(R.drawable.ic_menu_dismiss);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        NotificationStore.Note note = noteFor(actionKey);
        if (note == null) return true;
        int id = item.getItemId();
        if (id == MENU_VOICE) {
            startVoiceReply(note);
        } else if (id == MENU_DISMISS) {
            play(Sounds.DISMISSED);
            store.remove(note.key);
            LinkService.sendDismiss(note.key);
        } else if (id >= MENU_CHOICE_BASE && id - MENU_CHOICE_BASE < note.choices.size()) {
            queueReply(note.choices.get(id - MENU_CHOICE_BASE));
        }
        return true;
    }

    // ---- Replies --------------------------------------------------------------------------

    private boolean voiceAvailable() {
        return !getPackageManager().queryIntentActivities(
                new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH), 0).isEmpty();
    }

    private void startVoiceReply(NotificationStore.Note note) {
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PROMPT,
                        note.title.isEmpty() ? "Reply" : "Reply to " + note.title);
        try {
            startActivityForResult(intent, REQ_VOICE);
        } catch (ActivityNotFoundException e) {
            showResult(false, "Voice input isn't available");
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
            return; // cancelled
        }
        queueReply(results.get(0).trim());
    }

    /** Shows the Glass "Sending" card; the reply goes out when the grace bar runs out. */
    private void queueReply(String reply) {
        replyText = reply;
        showOverlay(new CardBuilder(this, CardBuilder.Layout.MENU)
                .setIcon(R.drawable.ic_menu_reply)
                .setText("Sending")
                .setFootnote("“" + reply + "”")
                .getView());
        grace = Slider.from(overlay).startGracePeriod(new Slider.GracePeriod.Listener() {
            @Override
            public void onGracePeriodEnd() {
                grace = null;
                sendPendingReply();
            }

            @Override
            public void onGracePeriodCancel() {
                grace = null;
                play(Sounds.DISMISSED);
                showCards();
            }
        });
    }

    private void cancelReply() {
        if (grace != null) grace.cancel();
    }

    private void sendPendingReply() {
        if (!LinkService.sendReply(actionKey, replyText)) {
            showResult(false, "Phone isn't connected");
            return;
        }
        waiting = Slider.from(overlay).startIndeterminate();
        handler.postDelayed(replyTimeout, REPLY_TIMEOUT_MS);
    }

    @Override
    public void onReplyResult(String key, boolean ok, String error) {
        if (overlay == null || grace != null || !key.equals(actionKey)) return;
        handler.removeCallbacks(replyTimeout);
        showResult(ok, ok ? "" : error);
    }

    private void showResult(boolean ok, String message) {
        handler.removeCallbacks(replyTimeout);
        if (waiting != null) {
            waiting.hide();
            waiting = null;
        }
        play(ok ? Sounds.SUCCESS : Sounds.ERROR);
        showOverlay(new CardBuilder(this, CardBuilder.Layout.MENU)
                .setIcon(ok ? R.drawable.ic_done : R.drawable.ic_error)
                .setText(ok ? "Sent" : "Not sent")
                .setFootnote(message == null ? "" : message)
                .getView());
        handler.postDelayed(backToCards, RESULT_SHOW_MS);
    }

    private void showOverlay(View view) {
        handler.removeCallbacks(backToCards);
        scroller.deactivate();
        overlay = view;
        setContentView(view);
    }

    private void showCards() {
        handler.removeCallbacks(backToCards);
        overlay = null;
        setContentView(scroller);
        scroller.activate();
        refresh();
    }

    // ---- Weather and Bluetooth ------------------------------------------------------------

    private void refreshWeather() {
        weatherRefreshing = LinkService.requestWeather();
        if (!weatherRefreshing) {
            play(Sounds.DISALLOWED);
            return;
        }
        adapter.notifyDataSetChanged();
        // If nothing changes (the phone says it's still fresh), stop showing "updating".
        handler.postDelayed(() -> {
            if (weatherRefreshing) {
                weatherRefreshing = false;
                adapter.notifyDataSetChanged();
            }
        }, 20000);
    }

    private void requestDiscoverable() {
        Intent intent = new Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
                .putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300);
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException e) {
            play(Sounds.ERROR);
        }
    }

    /** Glass system sounds. Their IDs extend AudioManager's, so lint doesn't know them. */
    @SuppressLint("WrongConstant")
    private void play(int glassSound) {
        audio.playSoundEffect(glassSound);
    }

    // ---- Input ----------------------------------------------------------------------------

    /** Any touch stops an alert from closing itself. */
    private void userInteracted() {
        if (alertMode) {
            alertMode = false;
            handler.removeCallbacks(alertTimeout);
        }
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        userInteracted();
        return super.dispatchGenericMotionEvent(event);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        // The card scroller consumes taps and swipes before onKeyDown sees them.
        userInteracted();
        return super.dispatchKeyEvent(event);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && overlay != null) {
            // Swipe down during the grace period cancels the reply. Once it's sent, wait.
            cancelReply();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }
}
