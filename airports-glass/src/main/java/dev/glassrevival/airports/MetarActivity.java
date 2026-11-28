package dev.glassrevival.airports;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.TextView;
import com.google.android.glass.media.Sounds;
import com.google.android.glass.widget.CardScrollAdapter;
import com.google.android.glass.widget.CardScrollView;
import dev.glassrevival.hub.HubClient;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * What the airport list and an airport's own cards share: a card scroller, the hub
 * connection, the latest report, "Updating…" while a refresh runs, and alerts that arrive
 * while the app is open.
 */
abstract class MetarActivity extends Activity {

    private static final long REFRESH_TIMEOUT_MS = 20000;
    /** Redraws the "observed … ago" footers. */
    private static final long TICK_MS = 60000;

    protected final Handler handler = new Handler();
    protected CardScrollView scroller;
    protected Metar metar;
    protected boolean refreshing;
    private HubClient hub;

    private final Runnable refreshTimeout = () -> {
        refreshing = false;
        changed();
    };

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            changed();
            handler.postDelayed(this, TICK_MS);
        }
    };

    /** The scroller's cards. Rebuilt by {@link #changed()}. */
    protected abstract CardScrollAdapter adapter();

    /** A card was tapped. */
    protected abstract void onTap(int position);

    /** Card to show after the next redraw, or -1 to stay put. */
    private int pendingSelection = -1;

    /**
     * The report changed or the clock ticked; redraw the cards. Override to rebuild more.
     * Keeps the current card: the scroller can jump back to the first one on a data change.
     */
    protected void changed() {
        CardScrollAdapter a = adapter();
        int at = pendingSelection >= 0 ? pendingSelection : scroller.getSelectedItemPosition();
        pendingSelection = -1;
        a.notifyDataSetChanged();
        select(Math.max(0, Math.min(at, a.getCount() - 1)));
    }

    /** CardScrollView ignores setSelection until it has laid out its cards, so repeat it after. */
    protected void select(final int position) {
        scroller.setSelection(position);
        scroller.post(() -> {
            if (position < adapter().getCount()) scroller.setSelection(position);
        });
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        metar = Metar.load(this);
        scroller = new CardScrollView(this);
        scroller.setAdapter(adapter());
        scroller.setOnItemClickListener((parent, view, position, id) -> onTap(position));
        setContentView(scroller);
        hub = new HubClient(this, Metar.CHANNEL, new HubClient.Callback() {
            @Override
            public void onMessage(JSONObject msg) {
                if (!Metar.TYPE.equals(msg.optString("type"))) return;
                Metar m = Metar.parse(msg.toString());
                if (m == null) return;
                Metar.save(MetarActivity.this, msg);
                metar = m;
                refreshing = false;
                handler.removeCallbacks(refreshTimeout);
                changed();
                if (m.alert != null && m.station(m.alert) != null) onAlert(m.alert);
            }

            @Override
            public void onConnectionChanged(boolean connected) {
                if (connected) send("sync");
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        scroller.activate();
        Metar saved = Metar.load(this); // the receiver may have saved a newer one
        if (saved != null) metar = saved;
        hub.connect();
        send("sync"); // false until the hub is bound; onConnectionChanged covers that
        handler.postDelayed(tick, TICK_MS);
        changed();
    }

    @Override
    protected void onPause() {
        pendingSelection = scroller.getSelectedItemPosition();
        handler.removeCallbacksAndMessages(null);
        refreshing = false;
        hub.disconnect();
        scroller.deactivate();
        super.onPause();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER) {
            onTap(scroller.getSelectedItemPosition());
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    /**
     * Conditions changed at an airport: chime and show it. The app can still be open, and
     * get this itself, with the screen off, so wake it too.
     */
    protected void onAlert(String id) {
        MetarReceiver.chimeAndWake(this);
        startActivity(new Intent(this, AirportActivity.class).putExtra(AirportActivity.EXTRA_ID, id));
    }

    /** Asks the phone to fetch now. */
    protected void refresh() {
        if (metar == null || metar.stations.isEmpty() || !send("refresh")) {
            play(Sounds.DISALLOWED);
            return;
        }
        play(Sounds.TAP);
        refreshing = true;
        handler.removeCallbacks(refreshTimeout);
        handler.postDelayed(refreshTimeout, REFRESH_TIMEOUT_MS);
        changed();
    }

    private boolean send(String type) {
        try {
            return hub.send(new JSONObject().put("type", type));
        } catch (JSONException e) {
            return false;
        }
    }

    protected void play(int sound) {
        ((AudioManager) getSystemService(AUDIO_SERVICE)).playSoundEffect(sound);
    }

    // ---- Card helpers ---------------------------------------------------------------------

    /** Fills in the header and footer that every airport card has. */
    protected void bindFrame(View v, Metar.Station st, String name, String footer, int position, int count) {
        ((TextView) v.findViewById(R.id.name)).setText(name);
        TextView cat = v.findViewById(R.id.category);
        cat.setText(st.category);
        cat.setTextColor(Metar.categoryColor(st.category));
        String foot = refreshing ? "Updating…" : footer;
        // "Now IFR, was MVFR · Observed 3m ago": the change note replaces the tap hint.
        if (!refreshing && !st.change.isEmpty()) foot = st.change + " · " + footer.split(",")[0];
        ((TextView) v.findViewById(R.id.footer)).setText(foot);
        ((TextView) v.findViewById(R.id.position)).setText(count > 1 ? (position + 1) + " / " + count : "");
    }

    /** A text card: the airport list, the METAR, runway winds, airport details. */
    protected View textCard(View convertView, ViewGroup parent, Metar.Station st, String name,
            String title, boolean plane, CharSequence body, boolean mono, float bodySp,
            String footer, int position, int count) {
        View v = convertView != null ? convertView
                : LayoutInflater.from(this).inflate(R.layout.metar_card, parent, false);
        bindFrame(v, st, name, footer, position, count);
        TextView t = v.findViewById(R.id.station);
        t.setText(title);
        t.setCompoundDrawablesWithIntrinsicBounds(plane ? R.drawable.ic_airplane : 0, 0, 0, 0);
        TextView b = v.findViewById(R.id.body);
        // In XML the Glass theme's font wins, so set it here every time (views are recycled).
        b.setTypeface(mono ? Typeface.MONOSPACE : Typeface.create("sans-serif-light", Typeface.NORMAL));
        b.setTextSize(bodySp);
        b.setText(body);
        return v;
    }

    static String nameOf(Metar.Station st) {
        return st.name.isEmpty() ? st.id : st.name;
    }
}
