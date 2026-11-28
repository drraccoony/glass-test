package dev.glassrevival.airports;

import android.content.Context;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * METAR reports for the user's airports, as sent by the phone on {@link #CHANNEL}.
 *
 * <pre>
 * phone -> glass  metar    {updated, alert?, stations: [{id, name, raw, observed, cat, change?,
 *                            type, wdir, wspd, wgst, visib, altim, temp, dewp, cover, clouds,
 *                            elev, freqs, tower, beacon, rwys: [{id, align, size, surface}]}]}
 *                          an empty list means METARs are off. alert = an airport whose
 *                          category changed or that has a special report
 * glass -> phone  sync     {}    send what you have, and fetch if it's old
 * glass -> phone  refresh  {}    fetch now
 * </pre>
 *
 * Units as aviationweather.gov gives them: wind degrees true and knots, visibility statute
 * miles, altimeter hPa, temperatures °C, cloud bases feet AGL, elevation metres. Runway
 * alignments are true headings too, so they compare directly with the wind.
 */
final class Metar {

    static final String CHANNEL = "dev.glassrevival.metar";
    static final String TYPE = "metar";

    private static final String PREFS = "airports";
    private static final String PREF_LAST = "last_metar";
    private static final double NONE = Double.NaN;

    static final class Cloud {
        final String cover;
        final int base; // feet AGL, -1 if not given

        Cloud(String cover, int base) {
            this.cover = cover;
            this.base = base;
        }
    }

    static final class Runway {
        final String id;      // "12L/30R"
        final int align;      // true heading of the first end
        final int lengthFt, widthFt;
        final String surface;

        Runway(JSONObject o) {
            id = o.optString("id");
            align = o.optInt("align");
            String[] size = o.optString("size").split("x");
            lengthFt = size.length == 2 ? parseInt(size[0]) : -1;
            widthFt = size.length == 2 ? parseInt(size[1]) : -1;
            surface = o.optString("surface");
        }
    }

    /** One runway end and the wind along and across it. */
    static final class RunwayWind {
        final String end;     // "30R"
        final int head;       // knots; negative is a tailwind
        final int cross;      // knots, always >= 0
        final int gustCross;  // crosswind in the gusts, or -1
        final boolean fromRight;

        RunwayWind(String end, int head, int cross, int gustCross, boolean fromRight) {
            this.end = end;
            this.head = head;
            this.cross = cross;
            this.gustCross = gustCross;
            this.fromRight = fromRight;
        }
    }

    static final class Station {
        final String id;
        final String name;
        final String raw;
        final long observed;
        /** Flight category: VFR, MVFR, IFR, LIFR, or empty. */
        final String category;
        /** "Now IFR, was MVFR" or "Special report", or empty. */
        final String change;

        /** Degrees true, or -1 when variable or missing. */
        final int windDir;
        final boolean windVariable;
        final int windSpeed, windGust; // knots, -1 if missing
        final String visibility;        // statute miles as reported, e.g. "10+", "1.5"
        final double altimeterHpa, temp, dewpoint, elevationM;
        final String cover;
        final List<Cloud> clouds;
        final List<Runway> runways;
        final String freqs;
        /** Control tower / rotating beacon, or null when the phone sent no airport data. */
        final Boolean tower, beacon;

        Station(JSONObject o) throws JSONException {
            id = o.getString("id");
            name = o.optString("name", "");
            raw = o.optString("raw", "");
            observed = o.optLong("observed", 0);
            category = o.optString("cat", "");
            change = o.optString("change", "");
            String wdir = o.optString("wdir", "");
            windVariable = "VRB".equalsIgnoreCase(wdir);
            windDir = windVariable ? -1 : parseInt(wdir);
            windSpeed = o.has("wspd") ? o.optInt("wspd", -1) : -1;
            windGust = o.has("wgst") ? o.optInt("wgst", -1) : -1;
            visibility = o.optString("visib", "");
            altimeterHpa = o.optDouble("altim", NONE);
            temp = o.optDouble("temp", NONE);
            dewpoint = o.optDouble("dewp", NONE);
            elevationM = o.optDouble("elev", NONE);
            cover = o.optString("cover", "");
            List<Cloud> cl = new ArrayList<>();
            JSONArray ca = o.optJSONArray("clouds");
            for (int i = 0; ca != null && i < ca.length(); i++) {
                JSONObject c = ca.optJSONObject(i);
                if (c != null) cl.add(new Cloud(c.optString("cover"), c.has("base") ? c.optInt("base", -1) : -1));
            }
            clouds = Collections.unmodifiableList(cl);
            List<Runway> rw = new ArrayList<>();
            JSONArray ra = o.optJSONArray("rwys");
            for (int i = 0; ra != null && i < ra.length(); i++) {
                JSONObject r = ra.optJSONObject(i);
                if (r != null) rw.add(new Runway(r));
            }
            runways = Collections.unmodifiableList(rw);
            freqs = o.optString("freqs", "");
            tower = o.has("tower") ? o.optBoolean("tower") : null;
            beacon = o.has("beacon") ? o.optBoolean("beacon") : null;
        }

        boolean hasReport() {
            return observed > 0;
        }

        // ---- Decoded text --------------------------------------------------------------

        /** "250° 6 kt", "250° 12 G22 kt", "Variable 3 kt", "Calm", or "" if not reported. */
        String windText() {
            if (windSpeed < 0) return "";
            if (windSpeed == 0) return "Calm";
            String gust = windGust > 0 ? " G" + windGust : "";
            if (windDir < 0) return "Variable " + windSpeed + gust + " kt";
            return String.format(Locale.US, "%03d° %d%s kt", windDir, windSpeed, gust);
        }

        /** "10+ sm", "1/2 sm", "2.5 sm". */
        String visibilityText() {
            if (visibility.isEmpty()) return "";
            String v = visibility;
            try {
                double d = Double.parseDouble(v);
                if (Math.abs(d - 0.125) < 0.01) v = "1/8";
                else if (Math.abs(d - 0.25) < 0.01) v = "1/4";
                else if (Math.abs(d - 0.5) < 0.01) v = "1/2";
                else if (Math.abs(d - 0.75) < 0.01) v = "3/4";
                else if (d == Math.rint(d)) v = Integer.toString((int) d);
            } catch (NumberFormatException ignored) {
                // "10+" and similar stay as they are
            }
            return v + " sm";
        }

        /** The lowest broken, overcast or obscured layer, or null for no ceiling. */
        Cloud ceiling() {
            Cloud low = null;
            for (Cloud c : clouds) {
                boolean counts = "BKN".equals(c.cover) || "OVC".equals(c.cover)
                        || "OVX".equals(c.cover) || "VV".equals(c.cover);
                if (counts && c.base >= 0 && (low == null || c.base < low.base)) low = c;
            }
            return low;
        }

        /** "OVC 800 ft", "None", or "" when the report has no sky condition. */
        String ceilingText() {
            Cloud c = ceiling();
            if (c != null) return ("OVX".equals(c.cover) ? "VV" : c.cover) + " " + feet(c.base);
            return clouds.isEmpty() && cover.isEmpty() ? "" : "None";
        }

        /** Short sky summary: "Clear", "FEW 7,500", "BKN 2,500 ft". */
        String skyText() {
            Cloud c = ceiling();
            if (c != null) return ceilingText();
            if (!clouds.isEmpty() && clouds.get(0).base >= 0) {
                Cloud first = clouds.get(0);
                return first.cover + " " + String.format(Locale.US, "%,d", first.base);
            }
            if ("CLR".equals(cover) || "SKC".equals(cover) || "CAVOK".equals(cover)) return "Clear";
            return cover;
        }

        /** "9° / 0°C". */
        String tempText() {
            if (Double.isNaN(temp)) return "";
            return Math.round(temp) + "°" + (Double.isNaN(dewpoint) ? "" : " / " + Math.round(dewpoint) + "°") + "C";
        }

        double altimeterInHg() {
            return Double.isNaN(altimeterHpa) ? NONE : altimeterHpa / 33.8639;
        }

        /** "29.64 inHg". */
        String altimeterText() {
            double a = altimeterInHg();
            return Double.isNaN(a) ? "" : String.format(Locale.US, "%.2f inHg", a);
        }

        int elevationFt() {
            return Double.isNaN(elevationM) ? Integer.MIN_VALUE : (int) Math.round(elevationM * 3.28084);
        }

        /**
         * Density altitude in feet, or Integer.MIN_VALUE when the report lacks what it
         * needs. Pressure altitude from the altimeter setting, then 118.8 ft per °C above
         * standard temperature (the usual rule-of-thumb formula).
         */
        int densityAltitude() {
            double in = altimeterInHg();
            int elev = elevationFt();
            if (Double.isNaN(in) || Double.isNaN(temp) || elev == Integer.MIN_VALUE) return Integer.MIN_VALUE;
            double pa = elev + (29.92 - in) * 1000;
            double isa = 15 - 1.98 * pa / 1000;
            double da = pa + 118.8 * (temp - isa);
            return (int) (Math.round(da / 10.0) * 10);
        }

        /**
         * For each runway, the end with the wind most on the nose. Best first: least
         * crosswind, then most headwind. Empty when calm, variable or no runway data.
         */
        List<RunwayWind> runwayWinds() {
            List<RunwayWind> out = new ArrayList<>();
            if (windDir < 0 || windSpeed <= 0) return out;
            for (Runway r : runways) {
                String[] ends = r.id.split("/");
                RunwayWind a = component(ends[0], r.align, windSpeed, windGust);
                RunwayWind b = ends.length > 1 ? component(ends[1], r.align + 180, windSpeed, windGust) : null;
                out.add(b != null && b.head > a.head ? b : a);
            }
            Collections.sort(out, (x, y) -> x.cross != y.cross ? x.cross - y.cross : y.head - x.head);
            return out;
        }

        private RunwayWind component(String end, int heading, int speed, int gust) {
            double angle = Math.toRadians(windDir - heading);
            double sin = Math.sin(angle);
            int head = (int) Math.round(speed * Math.cos(angle));
            int cross = (int) Math.round(Math.abs(speed * sin));
            int gustCross = gust > 0 ? (int) Math.round(Math.abs(gust * sin)) : -1;
            return new RunwayWind(end, head, cross, gustCross, sin > 0);
        }

        /** Frequencies by name in a sensible order: ATIS, Tower, Ground, CTAF, UNICOM, then others. */
        Map<String, String> frequencies() {
            Map<String, String> found = new LinkedHashMap<>();
            for (String part : freqs.split(";")) {
                String[] kv = part.split(",");
                if (kv.length != 2 || kv[1].trim().isEmpty()) continue;
                String label = freqLabel(kv[0].trim());
                if (!found.containsKey(label)) found.put(label, kv[1].trim());
            }
            Map<String, String> ordered = new LinkedHashMap<>();
            for (String k : new String[] {"ATIS", "Tower", "Ground", "CTAF", "UNICOM"}) {
                if (found.containsKey(k)) ordered.put(k, found.remove(k));
            }
            ordered.putAll(found);
            return ordered;
        }

        private static String freqLabel(String code) {
            String c = code.toUpperCase(Locale.US);
            if (c.contains("ATIS")) return "ATIS";
            if (c.startsWith("LCL")) return "Tower";
            if (c.startsWith("GND")) return "Ground";
            if (c.startsWith("CTAF")) return "CTAF";
            if (c.startsWith("UNIC")) return "UNICOM";
            if (c.startsWith("AWOS") || c.startsWith("ASOS")) return c.substring(0, 4);
            return code;
        }
    }

    final long updated;
    final List<Station> stations;
    /** Airport to open with a chime, or null. */
    final String alert;

    private Metar(JSONObject o) throws JSONException {
        updated = o.optLong("updated", System.currentTimeMillis());
        alert = o.has("alert") ? o.optString("alert") : null;
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

    Station station(String id) {
        for (Station s : stations) if (s.id.equals(id)) return s;
        return null;
    }

    /** Keeps the latest report, without its alert, so the cards show right away next time. */
    static void save(Context context, JSONObject msg) {
        JSONObject copy;
        try {
            copy = new JSONObject(msg.toString());
        } catch (JSONException e) {
            return;
        }
        copy.remove("alert");
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(PREF_LAST, copy.toString()).apply();
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

    static String feet(int ft) {
        return String.format(Locale.US, "%,d ft", ft);
    }

    /** "Observed 12m ago". */
    static String age(long observed) {
        if (observed <= 0) return "No report";
        long minutes = (System.currentTimeMillis() - observed) / 60000;
        return "Observed " + (minutes < 1 ? "just now" : minutes < 60 ? minutes + "m ago"
                : minutes < 24 * 60 ? (minutes / 60) + "h ago" : "over a day ago");
    }

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
