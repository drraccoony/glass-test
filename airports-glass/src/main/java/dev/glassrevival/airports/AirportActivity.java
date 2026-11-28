package dev.glassrevival.airports;

import android.content.Intent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import com.google.android.glass.media.Sounds;
import com.google.android.glass.widget.CardScrollAdapter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * One airport: the raw METAR, decoded conditions with density altitude, runway winds,
 * and field elevation, frequencies and runways. Tap to fetch a new report; swipe down to
 * go back to the list.
 */
public class AirportActivity extends MetarActivity {

    static final String EXTRA_ID = "id";

    private static final int METAR = 0, CONDITIONS = 1, RUNWAYS = 2, INFO = 3;

    private String id;
    private final List<Integer> cards = new ArrayList<>();

    @Override
    protected void onCreate(android.os.Bundle savedInstanceState) {
        id = getIntent().getStringExtra(EXTRA_ID);
        super.onCreate(savedInstanceState);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        String next = intent.getStringExtra(EXTRA_ID);
        if (next != null && !next.equals(id)) {
            id = next;
            changed();
            select(0);
        }
    }

    @Override
    protected void onAlert(String alertId) {
        if (alertId.equals(id)) {
            MetarReceiver.chimeAndWake(this);
            select(0); // the new METAR
        } else {
            super.onAlert(alertId);
        }
    }

    @Override
    protected void onTap(int position) {
        refresh();
    }

    private Metar.Station station() {
        return metar == null || id == null ? null : metar.station(id);
    }

    @Override
    protected void changed() {
        Metar.Station st = station();
        if (st == null) { // removed on the phone, or METARs turned off
            finish();
            return;
        }
        cards.clear();
        cards.add(METAR);
        if (!st.windText().isEmpty() || !st.altimeterText().isEmpty()) cards.add(CONDITIONS);
        if (!st.runways.isEmpty() && st.windSpeed >= 0) cards.add(RUNWAYS);
        if (!st.runways.isEmpty() || !st.freqs.isEmpty() || !Double.isNaN(st.elevationM)) cards.add(INFO);
        super.changed();
    }

    @Override
    protected CardScrollAdapter adapter() {
        return adapter;
    }

    private final CardScrollAdapter adapter = new CardScrollAdapter() {
        @Override
        public int getCount() {
            return cards.size();
        }

        @Override
        public Object getItem(int position) {
            return cards.get(position);
        }

        @Override
        public int getPosition(Object item) {
            return cards.indexOf(item);
        }

        @Override
        public long getItemId(int position) {
            return cards.get(position);
        }

        @Override
        public int getViewTypeCount() {
            return 2;
        }

        @Override
        public int getItemViewType(int position) {
            return cards.get(position) == CONDITIONS ? 1 : 0;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            Metar.Station st = station();
            int n = cards.size();
            String name = nameOf(st);
            String age = Metar.age(st.observed) + ", tap to refresh";
            switch (cards.get(position)) {
                case CONDITIONS:
                    return conditions(convertView, parent, st, age, position, n);
                case RUNWAYS:
                    return textCard(convertView, parent, st, name, "Runway winds", false,
                            runwayText(st), false, 20, age, position, n);
                case INFO:
                    return textCard(convertView, parent, st, name, "Airport", false,
                            infoText(st), false, 18, st.id, position, n);
                default: // METAR
                    String raw = st.raw.startsWith("METAR ") || st.raw.startsWith("SPECI ")
                            ? st.raw.substring(6) : st.raw;
                    return textCard(convertView, parent, st, name, st.id, true, raw, true, 20,
                            age, position, n);
            }
        }
    };

    private View conditions(View convertView, ViewGroup parent, Metar.Station st, String footer,
            int position, int count) {
        View v = convertView != null ? convertView
                : LayoutInflater.from(this).inflate(R.layout.conditions_card, parent, false);
        bindFrame(v, st, st.id + "  " + nameOf(st), footer, position, count);
        int da = st.densityAltitude();
        String[][] cells = {
                {"WIND", st.windText()},
                {"VISIBILITY", st.visibilityText()},
                {"CEILING", st.ceilingText()},
                {"TEMP / DEW", st.tempText()},
                {"ALTIMETER", st.altimeterText()},
                {"DENSITY ALTITUDE", da == Integer.MIN_VALUE ? "" : Metar.feet(da)},
        };
        int[] ids = {R.id.cell0, R.id.cell1, R.id.cell2, R.id.cell3, R.id.cell4, R.id.cell5};
        for (int i = 0; i < ids.length; i++) {
            View cell = v.findViewById(ids[i]);
            ((TextView) cell.findViewById(R.id.label)).setText(cells[i][0]);
            ((TextView) cell.findViewById(R.id.value)).setText(cells[i][1].isEmpty() ? "–" : cells[i][1]);
        }
        return v;
    }

    /** "Rwy 30: 6 kt head, 2 kt from left", best runway first, at most four. */
    private static String runwayText(Metar.Station st) {
        if (st.windSpeed == 0) return "Wind calm, any runway";
        if (st.windDir < 0) return "Wind variable at " + st.windSpeed + " kt, no runway favored";
        List<Metar.RunwayWind> winds = st.runwayWinds();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < winds.size() && i < 4; i++) {
            Metar.RunwayWind w = winds.get(i);
            if (sb.length() > 0) sb.append('\n');
            sb.append("Rwy ").append(w.end).append(": ");
            sb.append(Math.abs(w.head)).append(w.head >= 0 ? " head, " : " tail, ");
            if (w.cross == 0 && w.gustCross <= 0) {
                sb.append("no cross");
            } else {
                sb.append(w.cross);
                if (w.gustCross > w.cross) sb.append("–").append(w.gustCross);
                sb.append(" cross from ").append(w.fromRight ? "right" : "left");
            }
        }
        return sb.toString();
    }

    /** Elevation, tower and beacon, then frequencies, then runways, as many as fit. */
    private static String infoText(Metar.Station st) {
        List<String> lines = new ArrayList<>();
        List<String> first = new ArrayList<>();
        if (st.elevationFt() != Integer.MIN_VALUE) first.add("Elevation " + Metar.feet(st.elevationFt()));
        if (st.tower != null) first.add(st.tower ? "Towered" : "No tower");
        if (Boolean.TRUE.equals(st.beacon)) first.add("Beacon");
        if (!first.isEmpty()) lines.add(android.text.TextUtils.join(" \u00B7 ", first));
        Map<String, String> freqs = st.frequencies();
        List<String> f = new ArrayList<>();
        for (Map.Entry<String, String> e : freqs.entrySet()) {
            if (f.size() == 3) break;
            f.add(e.getKey() + " " + e.getValue());
        }
        lines.add(f.isEmpty() ? "No frequencies listed" : android.text.TextUtils.join(" · ", f));
        int room = 5 - lines.size();
        for (int i = 0; i < st.runways.size(); i++) {
            if (i == room - 1 && st.runways.size() > room) {
                lines.add("+" + (st.runways.size() - i) + " more runways");
                break;
            }
            Metar.Runway r = st.runways.get(i);
            String line = r.id;
            if (r.lengthFt > 0) {
                line += String.format(Locale.US, "  %,d × %d ft", r.lengthFt, r.widthFt);
            }
            String surface = surface(r.surface);
            if (!surface.isEmpty()) line += " " + surface;
            lines.add(line);
        }
        return android.text.TextUtils.join("\n", lines);
    }

    private static String surface(String code) {
        switch (code) {
            case "A": return "asphalt";
            case "C": return "concrete";
            case "T": return "turf";
            case "G": return "gravel";
            case "W": return "water";
            default: return "";
        }
    }
}
