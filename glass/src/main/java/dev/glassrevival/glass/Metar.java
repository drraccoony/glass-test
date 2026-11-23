package dev.glassrevival.glass;

import dev.glassrevival.protocol.Protocol;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** METAR reports for the user's airports, as sent by the phone. */
final class Metar {

    static final class Station {
        final String id;
        final String name;
        final String raw;
        final long observed;
        final String category;

        Station(JSONObject o) throws JSONException {
            id = o.getString(Protocol.F_ID);
            name = o.optString(Protocol.F_NAME, "");
            raw = o.optString(Protocol.F_RAW, "");
            observed = o.optLong(Protocol.F_OBSERVED, 0);
            category = o.optString(Protocol.F_CATEGORY, "");
        }
    }

    final long updated;
    final List<Station> stations;

    private Metar(JSONObject o) throws JSONException {
        updated = o.optLong(Protocol.F_UPDATED, System.currentTimeMillis());
        List<Station> list = new ArrayList<>();
        JSONArray arr = o.optJSONArray(Protocol.F_STATIONS);
        if (arr != null) {
            for (int i = 0; i < arr.length() && list.size() < 6; i++) {
                JSONObject s = arr.optJSONObject(i);
                if (s != null && s.has(Protocol.F_ID)) list.add(new Station(s));
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

    /** Standard flight category colors: VFR green, MVFR blue, IFR red, LIFR magenta. */
    static int categoryColor(String category) {
        if ("VFR".equals(category)) return 0xFF4CAF50;
        if ("MVFR".equals(category)) return 0xFF34A7FF;
        if ("IFR".equals(category)) return 0xFFE53935;
        if ("LIFR".equals(category)) return 0xFFD040D0;
        return 0xFF808080;
    }
}
