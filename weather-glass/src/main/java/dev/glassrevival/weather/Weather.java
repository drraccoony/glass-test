package dev.glassrevival.glass;

import dev.glassrevival.protocol.Protocol;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** A forecast as sent by the phone. The phone does the formatting; Glass only displays it. */
final class Weather {

    static final class Hour {
        final String label;
        final int temp;
        final String sky;
        final boolean day;

        Hour(String label, int temp, String sky, boolean day) {
            this.label = label;
            this.temp = temp;
            this.sky = sky;
            this.day = day;
        }

        int iconRes() {
            return iconFor(sky, day);
        }
    }

    final String place;
    final int temp;
    final int feels;
    final String desc;
    final String sky;
    final boolean day;
    final int high;
    final int low;
    final int rain;
    final int wind;
    final String unit;
    final String windUnit;
    final long updated;
    final List<Hour> hours;

    private Weather(JSONObject o) throws JSONException {
        place = o.optString(Protocol.F_PLACE, "");
        temp = o.getInt(Protocol.F_TEMP);
        feels = o.optInt(Protocol.F_FEELS, temp);
        desc = o.optString(Protocol.F_DESC, "");
        sky = o.optString(Protocol.F_SKY, Protocol.SKY_CLOUDY);
        day = o.optBoolean(Protocol.F_DAY, true);
        high = o.optInt(Protocol.F_HIGH, temp);
        low = o.optInt(Protocol.F_LOW, temp);
        rain = o.optInt(Protocol.F_RAIN, -1);
        wind = o.optInt(Protocol.F_WIND, -1);
        unit = o.optString(Protocol.F_UNIT, "");
        windUnit = o.optString(Protocol.F_WIND_UNIT, "");
        updated = o.optLong(Protocol.F_UPDATED, System.currentTimeMillis());
        List<Hour> list = new ArrayList<>();
        JSONArray arr = o.optJSONArray(Protocol.F_HOURS);
        if (arr != null) {
            for (int i = 0; i < arr.length() && list.size() < 4; i++) {
                JSONObject h = arr.optJSONObject(i);
                if (h == null || !h.has(Protocol.F_TEMP)) continue;
                list.add(new Hour(h.optString(Protocol.F_LABEL, ""), h.getInt(Protocol.F_TEMP),
                        h.optString(Protocol.F_SKY, Protocol.SKY_CLOUDY),
                        h.optBoolean(Protocol.F_DAY, true)));
            }
        }
        hours = Collections.unmodifiableList(list);
    }

    static Weather parse(String json) {
        if (json == null) return null;
        try {
            return new Weather(new JSONObject(json));
        } catch (JSONException e) {
            return null;
        }
    }

    int iconRes() {
        return iconFor(sky, day);
    }

    static int iconFor(String sky, boolean day) {
        if (Protocol.SKY_CLEAR.equals(sky)) {
            return day ? R.drawable.weather_clear_day : R.drawable.weather_clear_night;
        }
        if (Protocol.SKY_PARTLY.equals(sky)) {
            return day ? R.drawable.weather_partly : R.drawable.weather_cloudy;
        }
        if (Protocol.SKY_FOG.equals(sky)) return R.drawable.weather_fog;
        if (Protocol.SKY_RAIN.equals(sky)) return R.drawable.weather_rain;
        if (Protocol.SKY_SNOW.equals(sky)) return R.drawable.weather_snow;
        if (Protocol.SKY_STORM.equals(sky)) return R.drawable.weather_storm;
        return R.drawable.weather_cloudy;
    }
}
