package dev.glassrevival.airports;

import android.app.Activity;
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
import com.google.android.glass.widget.CardBuilder;
import com.google.android.glass.widget.CardScrollAdapter;
import com.google.android.glass.widget.CardScrollView;
import dev.glassrevival.hub.HubClient;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * One card per airport with its latest METAR and flight category. Opening the app asks
 * the phone for anything newer; tapping a card fetches right away. Swipe down to close.
 */
public class AirportsActivity extends Activity {

    private static final long REFRESH_TIMEOUT_MS = 20000;
    /** Redraws the "observed … ago" footers. */
    private static final long TICK_MS = 60000;

    private final Handler handler = new Handler();
    private CardScrollView scroller;
    private HubClient hub;
    private Metar metar;
    private boolean refreshing;

    private final Runnable refreshTimeout = () -> {
        refreshing = false;
        AirportsActivity.this.adapter.notifyDataSetChanged();
    };

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            AirportsActivity.this.adapter.notifyDataSetChanged();
            handler.postDelayed(this, TICK_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        scroller = new CardScrollView(this);
        scroller.setAdapter(adapter);
        scroller.setOnItemClickListener((parent, view, position, id) -> refresh());
        setContentView(scroller);
        metar = Metar.load(this);
        hub = new HubClient(this, Metar.CHANNEL, new HubClient.Callback() {
            @Override
            public void onMessage(JSONObject msg) {
                if (!Metar.TYPE.equals(msg.optString("type"))) return;
                Metar m = Metar.parse(msg.toString());
                if (m == null) return;
                Metar.save(AirportsActivity.this, msg);
                show(m);
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
        hub.connect();
        send("sync"); // false until the hub is bound; onConnectionChanged covers that
        handler.postDelayed(tick, TICK_MS);
        adapter.notifyDataSetChanged();
    }

    @Override
    protected void onPause() {
        handler.removeCallbacksAndMessages(null);
        refreshing = false;
        hub.disconnect();
        scroller.deactivate();
        super.onPause();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER) {
            refresh();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    private void refresh() {
        if (metar == null || metar.stations.isEmpty() || !send("refresh")) {
            play(Sounds.DISALLOWED);
            return;
        }
        play(Sounds.TAP);
        refreshing = true;
        handler.removeCallbacks(refreshTimeout);
        handler.postDelayed(refreshTimeout, REFRESH_TIMEOUT_MS);
        adapter.notifyDataSetChanged();
    }

    private void show(Metar m) {
        metar = m;
        refreshing = false;
        handler.removeCallbacks(refreshTimeout);
        adapter.notifyDataSetChanged();
        if (scroller.getSelectedItemPosition() >= adapter.getCount()) scroller.setSelection(0);
    }

    private boolean send(String type) {
        try {
            return hub.send(new JSONObject().put("type", type));
        } catch (JSONException e) {
            return false;
        }
    }

    private void play(int sound) {
        ((AudioManager) getSystemService(AUDIO_SERVICE)).playSoundEffect(sound);
    }

    // ---- Cards ----------------------------------------------------------------------------

    private boolean hasStations() {
        return metar != null && !metar.stations.isEmpty();
    }

    private final CardScrollAdapter adapter = new CardScrollAdapter() {
        @Override
        public int getCount() {
            return hasStations() ? metar.stations.size() : 1;
        }

        @Override
        public Object getItem(int position) {
            return hasStations() ? metar.stations.get(position) : null;
        }

        @Override
        public int getPosition(Object item) {
            return hasStations() ? metar.stations.indexOf(item) : 0;
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public int getViewTypeCount() {
            return 2;
        }

        @Override
        public int getItemViewType(int position) {
            return hasStations() ? 0 : 1;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (!hasStations()) {
                return new CardBuilder(AirportsActivity.this, CardBuilder.Layout.ALERT)
                        .setIcon(R.drawable.ic_airplane)
                        .setText(getString(metar == null ? R.string.no_airports : R.string.metar_off))
                        .setFootnote(getString(R.string.no_airports_hint))
                        .getView(convertView, parent);
            }
            View v = convertView != null ? convertView
                    : LayoutInflater.from(AirportsActivity.this).inflate(R.layout.metar_card, parent, false);
            bind(v, metar.stations.get(position), position);
            return v;
        }
    };

    private void bind(View v, Metar.Station st, int position) {
        ((TextView) v.findViewById(R.id.name)).setText(st.name.isEmpty() ? "METAR" : st.name);
        TextView cat = v.findViewById(R.id.category);
        cat.setText(st.category);
        cat.setTextColor(Metar.categoryColor(st.category));
        ((TextView) v.findViewById(R.id.station)).setText(st.id);
        TextView raw = v.findViewById(R.id.raw);
        raw.setTypeface(Typeface.MONOSPACE); // in XML the Glass theme's font wins
        raw.setText(st.raw.startsWith("METAR ") || st.raw.startsWith("SPECI ") ? st.raw.substring(6) : st.raw);
        String age;
        if (st.observed <= 0) {
            age = "No report";
        } else {
            long minutes = (System.currentTimeMillis() - st.observed) / 60000;
            age = "Observed " + (minutes < 1 ? "just now" : minutes < 60 ? minutes + "m ago"
                    : minutes < 24 * 60 ? (minutes / 60) + "h ago" : "over a day ago");
        }
        ((TextView) v.findViewById(R.id.footer)).setText(refreshing ? "Updating…" : age + ", tap to refresh");
        ((TextView) v.findViewById(R.id.position)).setText(
                metar.stations.size() > 1 ? (position + 1) + " / " + metar.stations.size() : "");
    }
}
