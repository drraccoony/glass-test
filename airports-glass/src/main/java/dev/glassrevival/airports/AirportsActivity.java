package dev.glassrevival.airports;

import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;
import com.google.android.glass.widget.CardBuilder;
import com.google.android.glass.widget.CardScrollAdapter;
import java.util.ArrayList;
import java.util.List;

/**
 * The airport list: one card each with its flight category and a one-glance summary.
 * Tap one for its METAR, decoded conditions, runway winds and details. Swipe down to close.
 */
public class AirportsActivity extends MetarActivity {

    @Override
    protected void onTap(int position) {
        if (!hasStations() || position < 0 || position >= metar.stations.size()) {
            play(com.google.android.glass.media.Sounds.DISALLOWED);
            return;
        }
        play(com.google.android.glass.media.Sounds.TAP);
        startActivity(new Intent(this, AirportActivity.class)
                .putExtra(AirportActivity.EXTRA_ID, metar.stations.get(position).id));
    }

    private boolean hasStations() {
        return metar != null && !metar.stations.isEmpty();
    }

    @Override
    protected CardScrollAdapter adapter() {
        return adapter;
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
            Metar.Station st = metar.stations.get(position);
            return textCard(convertView, parent, st, nameOf(st), st.id, true, summary(st), false, 22,
                    Metar.age(st.observed) + ", tap for details", position, metar.stations.size());
        }
    };

    /** "Wind 250° 6 kt · 10+ sm" / "Clear · 9° / 0°C · 29.64". */
    private static String summary(Metar.Station st) {
        if (!st.hasReport()) return st.raw;
        List<String> one = new ArrayList<>(), two = new ArrayList<>();
        if (!st.windText().isEmpty()) one.add("Wind " + st.windText());
        if (!st.visibilityText().isEmpty()) one.add(st.visibilityText());
        if (!st.skyText().isEmpty()) two.add(st.skyText());
        if (!st.tempText().isEmpty()) two.add(st.tempText());
        double a = st.altimeterInHg();
        if (!Double.isNaN(a)) two.add(String.format(java.util.Locale.US, "%.2f", a));
        if (one.isEmpty() && two.isEmpty()) return st.raw; // an older phone app: raw only
        return android.text.TextUtils.join(" · ", one) + "\n" + android.text.TextUtils.join(" · ", two);
    }
}
