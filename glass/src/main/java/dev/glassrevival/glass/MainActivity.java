package dev.glassrevival.glass;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.media.AudioManager;
import android.os.Bundle;
import android.util.Log;
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
    /** Open on this notification with its reply menu showing (from a Glass timeline card). */
    static final String EXTRA_REPLY_KEY = "reply_key";
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
        static final int METAR = 3;

        final int kind;
        final NotificationStore.Note note;
        final Metar.Station station;

        Card(int kind, NotificationStore.Note note) {
            this(kind, note, null);
        }

        Card(int kind, NotificationStore.Note note, Metar.Station station) {
            this.kind = kind;
            this.note = note;
            this.station = station;
        }

        String id() {
            if (kind == NOTE) return "note:" + note.key;
            if (kind == METAR) return "metar:" + station.id;
            return "kind:" + kind;
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
    private Metar metar;
    private boolean metarRefreshing;

    private String pendingAlertKey;
    private boolean alertMode;
    private boolean pendingWeatherCard;
    private boolean weatherRefreshing;
    /** The scroller ignores selections made before the activity is on screen. */
    private boolean resumed;

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
        String replyKey = intent.getStringExtra(EXTRA_REPLY_KEY);
        intent.removeExtra(EXTRA_REPLY_KEY);
        // Don't pull the user away from a reply in progress.
        if (overlay != null) {
            key = null;
            replyKey = null;
        }
        alertMode = key != null && replyKey == null;
        pendingAlertKey = replyKey != null ? replyKey : key;
        if (alertMode) handler.postDelayed(alertTimeout, LinkService.alertTimeoutMs(this));
        refresh();
        if (replyKey != null && noteFor(replyKey) != null) {
            pendingReplyMenuKey = replyKey;
            // Already showing (opened again from the timeline): open now. Otherwise wait
            // for onWindowFocusChanged, since the menu needs the window to exist.
            if (hasWindowFocus()) handler.post(this::openPendingReplyMenu);
        }
    }

    /** Notification whose reply menu should open once the window has focus. */
    private String pendingReplyMenuKey;

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && pendingReplyMenuKey != null) handler.post(this::openPendingReplyMenu);
    }

    private void openPendingReplyMenu() {
        String k = pendingReplyMenuKey;
        if (k == null || !hasWindowFocus()) return;
        pendingReplyMenuKey = null;
        if (overlay != null || noteFor(k) == null) return;
        actionKey = k;
        try {
            openOptionsMenu();
        } catch (RuntimeException e) {
            // Never take the link down over a menu; the card is on screen to tap instead.
            Log.w(LinkService.TAG, "Couldn't open the reply menu", e);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        store.addListener(this);
        if (overlay == null) scroller.activate();
        resumed = true;
        refresh();
    }

    @Override
    protected void onPause() {
        resumed = false;
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
        Metar previousMetar = metar;
        metar = store.metar();
        if (metar != previousMetar) metarRefreshing = false;

        List<Card> next = new ArrayList<>();
        next.add(new Card(Card.STATUS, null));
        if (weather != null) next.add(new Card(Card.WEATHER, null));
        if (metar != null) {
            for (Metar.Station st : metar.stations) next.add(new Card(Card.METAR, null, st));
        }
        for (NotificationStore.Note n : notes) next.add(new Card(Card.NOTE, n));
        cards = next;

        int target = current == null ? 0 : cards.indexOf(current);
        if (target < 0) target = Math.min(selected, cards.size() - 1); // its card went away
        if (pendingAlertKey != null && resumed) {
            int i = indexOfNote(pendingAlertKey);
            if (i >= 0) {
                target = i;
                pendingAlertKey = null;
            }
        }
        if (pendingWeatherCard && weather != null && resumed) {
            target = 1;
            pendingWeatherCard = false;
        }
        adapter.notifyDataSetChanged();
        final int selection = Math.max(0, target);
        scroller.setSelection(selection);
        // Apply again after layout, in case the scroller wasn't ready yet.
        scroller.post(() -> scroller.setSelection(selection));
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
        final View statusDetails;
        final ImageView statusIcon;
        final TextView statusHeadline;
        final TextView statusSubline;
        final View[] statusCells = new View[3];
        final TextView[] statusLabels = new TextView[3];
        final TextView[] statusValues = new TextView[3];
        final View weatherDetails;
        final ImageView weatherIcon;
        final TextView weatherTemp;
        final TextView weatherDesc;
        final TextView feels;
        final TextView high;
        final TextView low;
        final TextView rain;
        final TextView wind;
        final View[] hours = new View[3];
        final TextView[] hourLabels = new TextView[3];
        final ImageView[] hourIcons = new ImageView[3];
        final TextView[] hourTemps = new TextView[3];

        CardViews(View v) {
            icon = (ImageView) v.findViewById(R.id.icon);
            app = (TextView) v.findViewById(R.id.app);
            time = (TextView) v.findViewById(R.id.time);
            title = (TextView) v.findViewById(R.id.title);
            text = (TextView) v.findViewById(R.id.text);
            linkDot = v.findViewById(R.id.link_dot);
            footer = (TextView) v.findViewById(R.id.footer);
            position = (TextView) v.findViewById(R.id.position);
            statusDetails = v.findViewById(R.id.status_details);
            statusIcon = (ImageView) v.findViewById(R.id.status_icon);
            statusHeadline = (TextView) v.findViewById(R.id.status_headline);
            statusSubline = (TextView) v.findViewById(R.id.status_subline);
            int[][] cellIds = {
                {R.id.status_cell_0, R.id.status_label_0, R.id.status_value_0},
                {R.id.status_cell_1, R.id.status_label_1, R.id.status_value_1},
                {R.id.status_cell_2, R.id.status_label_2, R.id.status_value_2},
            };
            for (int i = 0; i < 3; i++) {
                statusCells[i] = v.findViewById(cellIds[i][0]);
                statusLabels[i] = (TextView) v.findViewById(cellIds[i][1]);
                statusValues[i] = (TextView) v.findViewById(cellIds[i][2]);
            }
            weatherDetails = v.findViewById(R.id.weather_details);
            weatherIcon = (ImageView) v.findViewById(R.id.weather_icon);
            weatherTemp = (TextView) v.findViewById(R.id.weather_temp);
            weatherDesc = (TextView) v.findViewById(R.id.weather_desc);
            feels = (TextView) v.findViewById(R.id.stat_feels);
            high = (TextView) v.findViewById(R.id.stat_high);
            low = (TextView) v.findViewById(R.id.stat_low);
            rain = (TextView) v.findViewById(R.id.stat_rain);
            wind = (TextView) v.findViewById(R.id.stat_wind);
            int[][] ids = {
                {R.id.hour_0, R.id.hour_0_label, R.id.hour_0_icon, R.id.hour_0_temp},
                {R.id.hour_1, R.id.hour_1_label, R.id.hour_1_icon, R.id.hour_1_temp},
                {R.id.hour_2, R.id.hour_2_label, R.id.hour_2_icon, R.id.hour_2_temp},
            };
            for (int i = 0; i < 3; i++) {
                hours[i] = v.findViewById(ids[i][0]);
                hourLabels[i] = (TextView) v.findViewById(ids[i][1]);
                hourIcons[i] = (ImageView) v.findViewById(ids[i][2]);
                hourTemps[i] = (TextView) v.findViewById(ids[i][3]);
            }
        }
    }

    private void bind(CardViews v, Card card, int index) {
        boolean connected = store.linkState() == NotificationStore.LinkState.CONNECTED;
        v.linkDot.setBackgroundColor(getResources().getColor(
                connected ? R.color.glass_green : R.color.glass_red));
        boolean weatherCard = card.kind == Card.WEATHER;
        boolean statusCard = card.kind == Card.STATUS;
        boolean customBody = weatherCard || statusCard;
        v.weatherDetails.setVisibility(weatherCard ? View.VISIBLE : View.GONE);
        v.statusDetails.setVisibility(statusCard ? View.VISIBLE : View.GONE);
        v.title.setVisibility(customBody ? View.GONE : View.VISIBLE);
        v.text.setVisibility(customBody ? View.GONE : View.VISIBLE);
        // Reset what the METAR card changes, since card views are recycled.
        v.title.setCompoundDrawablesWithIntrinsicBounds(0, 0, 0, 0);
        v.text.setTypeface(android.graphics.Typeface.create("sans-serif-light", android.graphics.Typeface.NORMAL));
        v.time.setTextColor(getResources().getColor(R.color.glass_dim));
        if (card.kind == Card.STATUS) {
            bindStatus(v, connected);
        } else if (weatherCard) {
            bindWeather(v);
        } else if (card.kind == Card.METAR) {
            bindMetar(v, card.station);
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

        v.statusIcon.setColorFilter(getResources().getColor(
                connected ? R.color.glass_green : R.color.glass_dim));
        String pairCode = PairCode.pretty(PairCode.get(this));
        if (connected) {
            String peer = store.peerName();
            v.statusHeadline.setText("Connected");
            v.statusSubline.setText(peer == null ? "Your phone" : peer);
            int n = notes.size();
            int battery = glassBattery();
            setStatusCell(v, 0, "NOTIFICATIONS", Integer.toString(n));
            setStatusCell(v, 1, "GLASS BATTERY", battery < 0 ? "\u2013" : battery + "%");
            setStatusCell(v, 2, "PAIR CODE", pairCode);
            v.footer.setText(cards.size() > 1 ? "Swipe for more cards" : "Ready for notifications");
        } else {
            boolean btOff = store.linkState() == NotificationStore.LinkState.BLUETOOTH_OFF;
            v.statusHeadline.setText(btOff ? "Bluetooth is off" : "Waiting for phone");
            v.statusSubline.setText(btOff ? "Turn it on in Settings" : "Open GlassLink on your phone");
            BluetoothAdapter bt = BluetoothAdapter.getDefaultAdapter();
            setStatusCell(v, 0, "PAIR CODE", pairCode);
            setStatusCell(v, 1, "GLASS ADDRESS", bt == null ? "\u2013" : bt.getAddress());
            setStatusCell(v, 2, null, null);
            v.footer.setText("Tap to make Glass discoverable");
        }
        v.position.setText("");
    }

    /** A null label hides the cell; the address needs two cells' width when it's shown. */
    private void setStatusCell(CardViews v, int i, String label, String value) {
        if (label == null) {
            v.statusCells[i].setVisibility(View.GONE);
            return;
        }
        v.statusCells[i].setVisibility(View.VISIBLE);
        v.statusLabels[i].setText(label);
        v.statusValues[i].setText(value);
    }

    private int glassBattery() {
        android.content.Intent b = registerReceiver(null,
                new android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED));
        if (b == null) return -1;
        int level = b.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1);
        int scale = b.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100);
        return level < 0 || scale <= 0 ? -1 : Math.round(level * 100f / scale);
    }

    private void bindWeather(CardViews v) {
        Weather w = weather;
        v.icon.setVisibility(View.GONE);
        v.app.setText(w.place.isEmpty() ? getString(R.string.weather) : w.place);
        v.time.setText(DateFormat.getTimeFormat(this).format(Calendar.getInstance().getTime()));
        v.weatherIcon.setImageResource(w.iconRes());
        v.weatherTemp.setText(w.temp + "\u00B0");
        v.weatherDesc.setText(w.desc);
        v.feels.setText(w.feels + "\u00B0");
        v.high.setText(w.high + "\u00B0");
        v.low.setText(w.low + "\u00B0");
        v.rain.setText(w.rain >= 0 ? w.rain + "%" : "\u2013");
        v.wind.setText(w.wind >= 0 ? w.wind + " " + w.windUnit : "\u2013");
        for (int i = 0; i < 3; i++) {
            if (i < w.hours.size()) {
                Weather.Hour h = w.hours.get(i);
                v.hours[i].setVisibility(View.VISIBLE);
                v.hourLabels[i].setText(h.label);
                v.hourIcons[i].setImageResource(h.iconRes());
                v.hourTemps[i].setText(h.temp + "\u00B0");
            } else {
                v.hours[i].setVisibility(View.INVISIBLE);
            }
        }
        long minutes = (System.currentTimeMillis() - w.updated) / 60000;
        String age = minutes < 1 ? "just now" : minutes < 60 ? minutes + "m ago"
                : minutes < 24 * 60 ? (minutes / 60) + "h ago" : "over a day ago";
        v.footer.setText(weatherRefreshing ? "Updating\u2026" : "Updated " + age + ", tap to refresh");
        v.position.setText("");
    }

    private void bindMetar(CardViews v, Metar.Station st) {
        v.icon.setVisibility(View.GONE);
        v.app.setText(st.name.isEmpty() ? "METAR" : st.name);
        v.time.setText(st.category);
        v.time.setTextColor(Metar.categoryColor(st.category));
        v.title.setText(st.id);
        v.title.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_airplane, 0, 0, 0);
        v.title.setCompoundDrawablePadding((int) (10 * getResources().getDisplayMetrics().density));
        v.text.setTypeface(android.graphics.Typeface.MONOSPACE);
        v.text.setText(st.raw.startsWith("METAR ") || st.raw.startsWith("SPECI ")
                ? st.raw.substring(6) : st.raw);
        String age;
        if (st.observed <= 0) {
            age = "No report";
        } else {
            long minutes = (System.currentTimeMillis() - st.observed) / 60000;
            age = "Observed " + (minutes < 1 ? "just now" : minutes < 60 ? minutes + "m ago"
                    : minutes < 24 * 60 ? (minutes / 60) + "h ago" : "over a day ago");
        }
        v.footer.setText(metarRefreshing ? "Updating\u2026" : age + ", tap to refresh");
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
        int first = cards.size() - notes.size();
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
        } else if (card.kind == Card.METAR) {
            metarRefreshing = LinkService.requestMetar();
            play(metarRefreshing ? Sounds.TAP : Sounds.DISALLOWED);
            adapter.notifyDataSetChanged();
            handler.postDelayed(() -> {
                if (metarRefreshing) {
                    metarRefreshing = false;
                    adapter.notifyDataSetChanged();
                }
            }, 20000);
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
