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
 * and sends them to Glass, which shows one card per airport.
 */
final class MetarProvider implements LinkManager.GlassHook {

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

    @Override
    public void onGlassRequest(String type) {
        if (Protocol.METAR_REQUEST.equals(type)) exec.execute(() -> refresh(true));
    }

    /** The switch or the airport list changed. */
    void settingsChanged() {
        prefs.setMetar(null, 0);
        exec.execute(() -> refresh(true));
    }

    private void sendCached() {
        if (!prefs.metarEnabled()) {
            sendEmpty();
            return;
        }
        String json = prefs.metarJson();
        if (json == null) return;
        try {
            link.send(new JSONObject(json));
        } catch (JSONException ignored) {
            // replaced by the next fetch
        }
    }

    /** Tells Glass to remove its METAR cards. */
    private void sendEmpty() {
        try {
            link.send(new JSONObject().put(Protocol.F_TYPE, Protocol.METAR)
                    .put(Protocol.F_UPDATED, System.currentTimeMillis())
                    .put(Protocol.F_STATIONS, new JSONArray()));
        } catch (JSONException ignored) {
            // cannot happen
        }
    }

    private void refresh(boolean force) {
        if (!link.isConnected()) return;
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
            prefs.setMetar(msg.toString(), System.currentTimeMillis());
            prefs.setMetarStatus("Updated " + DateFormat.getTimeFormat(context)
                    .format(Calendar.getInstance().getTime()));
            link.send(msg);
        } catch (IOException | JSONException e) {
            Log.w(TAG, "METAR fetch failed", e);
            prefs.setMetarStatus("Couldn't reach aviationweather.gov");
            sendCached();
        }
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
        JSONArray stations = new JSONArray();
        for (String id : ids) { // keep the user's order
            JSONObject r = byId.get(id);
            JSONObject s = new JSONObject();
            s.put(Protocol.F_ID, id);
            if (r == null) {
                s.put(Protocol.F_NAME, "");
                s.put(Protocol.F_RAW, "No recent METAR for " + id);
                s.put(Protocol.F_OBSERVED, 0);
                s.put(Protocol.F_CATEGORY, "");
            } else {
                s.put(Protocol.F_NAME, shortName(r.optString("name", "")));
                s.put(Protocol.F_RAW, r.optString("rawOb", ""));
                s.put(Protocol.F_OBSERVED, r.optLong("obsTime", 0) * 1000L);
                s.put(Protocol.F_CATEGORY, r.optString("fltCat", ""));
            }
            stations.put(s);
        }
        JSONObject msg = new JSONObject();
        msg.put(Protocol.F_TYPE, Protocol.METAR);
        msg.put(Protocol.F_UPDATED, System.currentTimeMillis());
        msg.put(Protocol.F_STATIONS, stations);
        return msg;
    }

    /** "Lakeville/Airlake Arpt, MN, US" becomes "Lakeville/Airlake Arpt, MN". */
    private static String shortName(String name) {
        return name.endsWith(", US") ? name.substring(0, name.length() - 4) : name;
    }
}
