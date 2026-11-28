package dev.glassrevival.companion;

import android.text.format.DateFormat;
import android.util.Log;
import android.content.Context;
import dev.glassrevival.protocol.Protocol;
import java.io.IOException;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Fetches METARs for the user's airports from aviationweather.gov (NOAA, free, no key)
 * and sends them on the hub channel {@link #CHANNEL} to the Airports app on Glass, which
 * shows one card per airport.
 *
 * <pre>
 * phone -> glass  metar    {updated, alert?, stations: [{id, name, raw, observed, cat, change?,
 *                            type, wdir, wspd, wgst, visib, altim, temp, dewp, cover, clouds,
 *                            elev, freqs, tower, beacon, rwys: [{id, align, size, surface}]}]}
 *                          an empty list means METARs are off. alert = the id of an airport
 *                          whose category changed or that has a special report; Glass chimes
 *                          and opens it. Only sent once, never with the cached copy.
 * glass -> phone  sync     {}    send what you have, and fetch if it's old
 * glass -> phone  refresh  {}    fetch now
 * </pre>
 */
final class MetarProvider implements LinkManager.GlassHook {

    static final String CHANNEL = "dev.glassrevival.metar";
    private static final String TYPE = "metar";
    /** Bump when airportInfo() keeps more fields, so cached entries are fetched again. */
    private static final int INFO_VERSION = 2;

    private static final String TAG = "GlassLink";
    /** METARs come out hourly, with specials in between. */
    private static final long STALE_MS = 10 * 60 * 1000L;
    private static final long MIN_FETCH_GAP_MS = 30 * 1000L;

    private final Context context;
    private final Prefs prefs;
    private final LinkManager link;
    private final ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor();

    MetarProvider(Context context, Prefs prefs, LinkManager link) {
        this.context = context.getApplicationContext();
        this.prefs = prefs;
        this.link = link;
        exec.scheduleWithFixedDelay(() -> {
            if (link.isConnected()) refresh(false);
        }, 5, 5, TimeUnit.MINUTES);
    }

    @Override
    public void onGlassConnected() {
        exec.execute(() -> {
            sendCached();
            refresh(false);
        });
    }

    /** A frame from the Airports app on Glass. */
    void onGlassMessage(JSONObject msg) {
        String type = msg.optString(Protocol.F_TYPE);
        if ("sync".equals(type)) {
            exec.execute(() -> {
                sendCached();
                refresh(false);
            });
        } else if ("refresh".equals(type)) {
            exec.execute(() -> refresh(true));
        }
    }

    /** The Airports app was just installed on Glass. */
    void glassAppInstalled() {
        exec.execute(() -> {
            sendCached();
            refresh(false);
        });
    }

    /** False when Glass reported it has no Airports app: then nothing is fetched or sent. */
    private boolean glassHasApp() {
        return CompanionApp.addOns(context).isInstalled(AddOnManager.AIRPORTS);
    }

    /** The switch or the airport list changed. */
    void settingsChanged() {
        prefs.setMetar(null, 0);
        exec.execute(() -> refresh(true));
    }

    private void sendCached() {
        if (!glassHasApp()) return;
        if (!prefs.metarEnabled()) {
            sendEmpty();
            return;
        }
        String json = prefs.metarJson();
        if (json == null) return;
        try {
            send(new JSONObject(json));
        } catch (JSONException ignored) {
            // replaced by the next fetch
        }
    }

    /** Tells Glass that METARs are off. */
    private void sendEmpty() {
        try {
            send(new JSONObject().put(Protocol.F_TYPE, TYPE)
                    .put(Protocol.F_UPDATED, System.currentTimeMillis())
                    .put("stations", new JSONArray()));
        } catch (JSONException ignored) {
            // cannot happen
        }
    }

    private void send(JSONObject msg) {
        try {
            msg.put(Protocol.F_CHANNEL, CHANNEL);
        } catch (JSONException ignored) {
            // cannot happen
        }
        link.send(msg);
    }

    private void refresh(boolean force) {
        if (!link.isConnected() || !glassHasApp()) return;
        List<String> ids = prefs.metarAirports();
        if (!prefs.metarEnabled() || ids.isEmpty()) {
            sendEmpty();
            return;
        }
        long age = System.currentTimeMillis() - prefs.metarFetchedAt();
        if (!force && age < STALE_MS) return;
        if (force && age < MIN_FETCH_GAP_MS) {
            sendCached();
            return;
        }
        try {
            JSONObject msg = fetch(ids);
            String alertId = markChanges(prefs.metarJson(), msg);
            prefs.setMetar(msg.toString(), System.currentTimeMillis());
            prefs.setMetarStatus("Updated " + DateFormat.getTimeFormat(context)
                    .format(Calendar.getInstance().getTime()));
            // Only this fresh send alerts; the cached copy (resent on reconnect) doesn't.
            if (alertId != null && prefs.metarAlerts()) {
                msg.put("alert", alertId);
                Log.i(TAG, "METAR alert for " + alertId);
            }
            send(msg);
        } catch (IOException | JSONException e) {
            Log.w(TAG, "METAR fetch failed", e);
            prefs.setMetarStatus("Couldn't reach aviationweather.gov");
            sendCached();
        }
    }

    /**
     * Compares with the previous fetch. A new report whose flight category changed, or a
     * special report (SPECI), gets a "change" note such as "Now IFR, was MVFR".
     *
     * @return the first airport with a change, or null
     */
    private static String markChanges(String previousJson, JSONObject msg) throws JSONException {
        if (previousJson == null) return null; // first fetch after a settings change
        Map<String, JSONObject> before = new HashMap<>();
        JSONArray old = new JSONObject(previousJson).optJSONArray("stations");
        for (int i = 0; old != null && i < old.length(); i++) {
            JSONObject o = old.getJSONObject(i);
            before.put(o.optString("id"), o);
        }
        String first = null;
        JSONArray stations = msg.getJSONArray("stations");
        for (int i = 0; i < stations.length(); i++) {
            JSONObject s = stations.getJSONObject(i);
            JSONObject b = before.get(s.optString("id"));
            if (b == null) continue;
            long observed = s.optLong("observed");
            if (observed <= 0 || observed <= b.optLong("observed")) {
                // Same report as before: keep its note so the card still shows it.
                if (observed > 0 && b.has("change")) s.put("change", b.getString("change"));
                continue;
            }
            String cat = s.optString("cat"), was = b.optString("cat");
            String change = null;
            if (!cat.isEmpty() && !was.isEmpty() && !cat.equals(was)) {
                change = "Now " + cat + ", was " + was;
            } else if ("SPECI".equals(s.optString("type"))) {
                change = "Special report";
            }
            if (change != null) {
                s.put("change", change);
                if (first == null) first = s.getString("id");
            }
        }
        return first;
    }

    private JSONObject fetch(List<String> ids) throws IOException, JSONException {
        String body = WeatherProvider.get("https://aviationweather.gov/api/data/metar?format=json&ids="
                + android.text.TextUtils.join(",", ids));
        Map<String, JSONObject> byId = new HashMap<>();
        if (!body.trim().isEmpty()) {
            JSONArray reports = new JSONArray(body);
            for (int i = 0; i < reports.length(); i++) {
                JSONObject r = reports.getJSONObject(i);
                byId.put(r.optString("icaoId"), r);
            }
        }
        Map<String, JSONObject> airports = airportInfo(ids);
        JSONArray stations = new JSONArray();
        for (String id : ids) { // keep the user's order
            JSONObject r = byId.get(id);
            JSONObject s = new JSONObject();
            s.put("id", id);
            if (r == null) {
                s.put("name", "");
                s.put("raw", "No recent METAR for " + id);
                s.put("observed", 0);
                s.put("cat", "");
            } else {
                s.put("name", shortName(r.optString("name", "")));
                s.put("raw", r.optString("rawOb", ""));
                s.put("observed", r.optLong("obsTime", 0) * 1000L);
                s.put("cat", r.optString("fltCat", ""));
                // Decoded values, as aviationweather.gov gives them: wind in degrees true
                // and knots (wdir may be "VRB"), visibility in statute miles ("10+"),
                // altimeter in hPa, temperatures in °C, cloud bases in feet AGL.
                copy(r, s, "metarType", "type");
                copy(r, s, "wdir", "wdir");
                copy(r, s, "wspd", "wspd");
                copy(r, s, "wgst", "wgst");
                copy(r, s, "visib", "visib");
                copy(r, s, "altim", "altim");
                copy(r, s, "temp", "temp");
                copy(r, s, "dewp", "dewp");
                copy(r, s, "cover", "cover");
                copy(r, s, "clouds", "clouds");
                copy(r, s, "elev", "elev"); // metres
            }
            JSONObject a = airports.get(id);
            if (a != null) {
                // Field elevation (metres), runways with true alignment, radio frequencies.
                if (a.has("elev")) s.put("elev", a.get("elev"));
                copy(a, s, "freqs", "freqs");
                // Undocumented in the API; "T" and "B" match towered fields and fields with
                // a rotating beacon in every airport checked. "services" is left out: it was
                // "S" almost everywhere and its meaning is unclear.
                s.put("tower", "T".equals(a.optString("tower")));
                s.put("beacon", "B".equals(a.optString("beacon")));
                JSONArray rwys = new JSONArray();
                JSONArray src = a.optJSONArray("runways");
                for (int i = 0; src != null && i < src.length(); i++) {
                    JSONObject rw = src.getJSONObject(i);
                    if (!rw.has("alignment")) continue;
                    rwys.put(new JSONObject().put("id", rw.optString("id"))
                            .put("align", rw.optInt("alignment"))
                            .put("size", rw.optString("dimension"))
                            .put("surface", rw.optString("surface")));
                }
                s.put("rwys", rwys);
            }
            stations.put(s);
        }
        JSONObject msg = new JSONObject();
        msg.put(Protocol.F_TYPE, TYPE);
        msg.put(Protocol.F_UPDATED, System.currentTimeMillis());
        msg.put("stations", stations);
        return msg;
    }

    private static void copy(JSONObject from, JSONObject to, String fromKey, String toKey) throws JSONException {
        Object v = from.opt(fromKey);
        if (v != null && v != JSONObject.NULL) to.put(toKey, v);
    }

    /**
     * Runways, elevation and frequencies, which hardly ever change: fetched once per airport
     * and kept. A failed lookup just leaves those cards out until the next fetch.
     */
    private Map<String, JSONObject> airportInfo(List<String> ids) {
        Map<String, JSONObject> out = new HashMap<>();
        JSONObject cache;
        try {
            cache = new JSONObject(prefs.metarAirportInfo());
        } catch (JSONException e) {
            cache = new JSONObject();
        }
        List<String> missing = new java.util.ArrayList<>();
        for (String id : ids) {
            JSONObject a = cache.optJSONObject(id);
            // Entries from before tower and beacon were kept are fetched again.
            if (a != null && a.optInt("v") >= INFO_VERSION) out.put(id, a);
            else missing.add(id);
        }
        if (missing.isEmpty()) return out;
        try {
            String body = WeatherProvider.get("https://aviationweather.gov/api/data/airport?format=json&ids="
                    + android.text.TextUtils.join(",", missing));
            JSONArray list = body.trim().isEmpty() ? new JSONArray() : new JSONArray(body);
            for (int i = 0; i < list.length(); i++) {
                JSONObject a = list.getJSONObject(i);
                String id = a.optString("icaoId");
                if (!missing.contains(id)) continue;
                JSONObject keep = new JSONObject().put("v", INFO_VERSION);
                copy(a, keep, "tower", "tower");
                copy(a, keep, "beacon", "beacon");
                copy(a, keep, "elev", "elev");
                copy(a, keep, "freqs", "freqs");
                copy(a, keep, "runways", "runways");
                cache.put(id, keep);
                out.put(id, keep);
            }
            prefs.setMetarAirportInfo(cache.toString());
        } catch (IOException | JSONException e) {
            Log.w(TAG, "Airport info fetch failed", e);
        }
        return out;
    }

    /** "Lakeville/Airlake Arpt, MN, US" becomes "Lakeville/Airlake Arpt, MN". */
    private static String shortName(String name) {
        return name.endsWith(", US") ? name.substring(0, name.length() - 4) : name;
    }
}
