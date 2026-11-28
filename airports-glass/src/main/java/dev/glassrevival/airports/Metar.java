package dev.glassrevival.airports;

import android.content.Context;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * METAR reports for the user's airports, as sent by the phone on {@link #CHANNEL}.
 *
 * <pre>
 * phone -> glass  metar    {updated, stations: [{id, name, raw, observed, cat}]}
 *                          an empty list means METARs are off on the phone
 * glass -> phone  sync     {}    send what you have, and fetch if it's old
 * glass -> phone  refresh  {}    fetch now
 * </pre>
 */
final class Metar {

    static final String CHANNEL = "dev.glassrevival.metar";
    static final String TYPE = "metar";

    private static final String PREFS = "airports";
    private static final String PREF_LAST = "last_metar";

    static final class Station {
        final String id;
        final String name;
        final String raw;
        final long observed;
        /** Flight category: VFR, MVFR, IFR, LIFR, or empty. */
        final String category;

        Station(JSONObject o) throws JSONException {
            id = o.getString("id");
            name = o.optString("name", "");
            raw = o.optString("raw", "");
            observed = o.optLong("observed", 0);
            category = o.optString("cat", "");
        }
    }

    final long updated;
    final List<Station> stations;

    private Metar(JSONObject o) throws JSONException {
        updated = o.optLong("updated", System.currentTimeMillis());
        List<Station> list = new ArrayList<>();
        JSONArray arr = o.optJSONArray("stations");
        if (arr != null) {
            for (int i = 0; i < arr.length() && list.size() < 6; i++) {
                JSONObject s = arr.optJSONObject(i);
                if (s != null && s.has("id")) list.add(new Station(s));
            }
        }
        stations = Collections.unmodifiableList(list);
    }

    static Metar parse(String json) {
        if (json == null) return null;
        try {
            return new Metar(new JSONObject(json));
        } catch (JSONException e) {
            return null;
        }
    }

    /** Keeps the latest report so the cards show right away next time. */
    static void save(Context context, JSONObject msg) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(PREF_LAST, msg.toString()).apply();
    }

    static Metar load(Context context) {
        return parse(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(PREF_LAST, null));
    }

    /** Standard flight category colors: VFR green, MVFR blue, IFR red, LIFR magenta. */
    static int categoryColor(String category) {
        if ("VFR".equals(category)) return 0xFF4CAF50;
        if ("MVFR".equals(category)) return 0xFF34A7FF;
        if ("IFR".equals(category)) return 0xFFE53935;
        if ("LIFR".equals(category)) return 0xFFD040D0;
        return 0xFF808080;
    }
}
