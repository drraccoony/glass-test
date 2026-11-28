package dev.glassrevival.weather;

import android.app.Activity;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.text.format.DateFormat;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.TextView;
import com.google.android.glass.media.Sounds;
import com.google.android.glass.widget.CardBuilder;
import com.google.android.glass.widget.CardScrollAdapter;
import com.google.android.glass.widget.CardScrollView;
import dev.glassrevival.hub.HubClient;
import java.util.Calendar;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * The forecast card: now, today's high and low, rain chance, wind, and the next few hours.
 * Opening it asks the phone for a newer forecast if the one here is old; tapping fetches
 * right away. Swipe down to close.
 */
public class WeatherActivity extends Activity {

    private static final long STALE_MS = 30 * 60 * 1000L;
    private static final long REFRESH_TIMEOUT_MS = 20000;
    /** Redraws the clock and the "updated … ago" footer. */
    private static final long TICK_MS = 30000;

    private final Handler handler = new Handler();
    private CardScrollView scroller;
    private HubClient hub;
    private Weather weather;
    private boolean refreshing;

    private final Runnable refreshTimeout = () -> {
        refreshing = false;
        WeatherActivity.this.adapter.notifyDataSetChanged();
    };

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            WeatherActivity.this.adapter.notifyDataSetChanged();
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
        hub = new HubClient(this, Weather.CHANNEL, new HubClient.Callback() {
            @Override
            public void onMessage(JSONObject msg) {
                if (!Weather.TYPE.equals(msg.optString("type"))) return;
                Weather w = Weather.parse(msg.toString());
                if (w == null) return;
                Weather.save(WeatherActivity.this, msg);
                weather = w;
                refreshing = false;
                handler.removeCallbacks(refreshTimeout);
                adapter.notifyDataSetChanged();
            }

            @Override
            public void onConnectionChanged(boolean connected) {
                if (connected) sync();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        scroller.activate();
        weather = Weather.load(this); // the receiver may have saved a newer one
        hub.connect();
        sync(); // false until the hub is bound; onConnectionChanged covers that
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

    /** Asks for what the phone has; an old forecast shows "Updating…" while it fetches. */
    private void sync() {
        if (!send("sync")) return;
        if (weather == null || System.currentTimeMillis() - weather.updated > STALE_MS) showRefreshing();
    }

    private void refresh() {
        if (!send("refresh")) {
            play(Sounds.DISALLOWED);
            return;
        }
        play(Sounds.TAP);
        showRefreshing();
    }

    private void showRefreshing() {
        refreshing = true;
        handler.removeCallbacks(refreshTimeout);
        handler.postDelayed(refreshTimeout, REFRESH_TIMEOUT_MS);
        adapter.notifyDataSetChanged();
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

    // ---- The card -------------------------------------------------------------------------

    private final CardScrollAdapter adapter = new CardScrollAdapter() {
        @Override
        public int getCount() {
            return 1;
        }

        @Override
        public Object getItem(int position) {
            return weather;
        }

        @Override
        public int getPosition(Object item) {
            return 0;
        }

        @Override
        public long getItemId(int position) {
            return 0;
        }

        @Override
        public int getViewTypeCount() {
            return 2;
        }

        @Override
        public int getItemViewType(int position) {
            return weather == null ? 1 : 0;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (weather == null) {
                return new CardBuilder(WeatherActivity.this, CardBuilder.Layout.ALERT)
                        .setIcon(R.drawable.weather_partly)
                        .setText(getString(R.string.no_weather))
                        .setFootnote(refreshing ? "Asking your phone…" : getString(R.string.no_weather_hint))
                        .getView(convertView, parent);
            }
            View v = convertView != null ? convertView
                    : LayoutInflater.from(WeatherActivity.this).inflate(R.layout.weather_card, parent, false);
            bind(v, weather);
            return v;
        }
    };

    private void bind(View v, Weather w) {
        text(v, R.id.place, w.place.isEmpty() ? getString(R.string.app_name) : w.place);
        text(v, R.id.time, DateFormat.getTimeFormat(this).format(Calendar.getInstance().getTime()));
        ((ImageView) v.findViewById(R.id.weather_icon)).setImageResource(w.iconRes());
        text(v, R.id.weather_temp, w.temp + "°");
        text(v, R.id.weather_desc, w.desc);
        text(v, R.id.stat_feels, w.feels + "°");
        text(v, R.id.stat_high, w.high + "°");
        text(v, R.id.stat_low, w.low + "°");
        text(v, R.id.stat_rain, w.rain >= 0 ? w.rain + "%" : "–");
        text(v, R.id.stat_wind, w.wind >= 0 ? w.wind + " " + w.windUnit : "–");
        int[][] ids = {
            {R.id.hour_0, R.id.hour_0_label, R.id.hour_0_icon, R.id.hour_0_temp},
            {R.id.hour_1, R.id.hour_1_label, R.id.hour_1_icon, R.id.hour_1_temp},
            {R.id.hour_2, R.id.hour_2_label, R.id.hour_2_icon, R.id.hour_2_temp},
        };
        for (int i = 0; i < 3; i++) {
            if (i < w.hours.size()) {
                Weather.Hour h = w.hours.get(i);
                v.findViewById(ids[i][0]).setVisibility(View.VISIBLE);
                text(v, ids[i][1], h.label);
                ((ImageView) v.findViewById(ids[i][2])).setImageResource(h.iconRes());
                text(v, ids[i][3], h.temp + "°");
            } else {
                v.findViewById(ids[i][0]).setVisibility(View.INVISIBLE);
            }
        }
        long minutes = (System.currentTimeMillis() - w.updated) / 60000;
        String age = minutes < 1 ? "just now" : minutes < 60 ? minutes + "m ago"
                : minutes < 24 * 60 ? (minutes / 60) + "h ago" : "over a day ago";
        text(v, R.id.footer, refreshing ? "Updating…" : "Updated " + age + ", tap to refresh");
    }

    private static void text(View v, int id, CharSequence s) {
        ((TextView) v.findViewById(id)).setText(s);
    }
}
