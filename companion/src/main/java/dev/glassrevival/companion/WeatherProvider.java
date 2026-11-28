package dev.glassrevival.companion;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.location.LocationManager;
import android.os.Build;
import android.text.format.DateFormat;
import android.util.Log;
import dev.glassrevival.protocol.Protocol;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Fetches the forecast from Open-Meteo (free, no API key) for the phone's location or a
 * place the user picked, and sends it on the hub channel {@link #CHANNEL} to the Weather
 * app on Glass. Coordinates are rounded to two decimal places (about 1 km) before they
 * leave the phone.
 *
 * <pre>
 * phone -> glass  weather  {place, temp, feels, desc, sky, day, high, low, rain, wind, unit,
 *                           windUnit, updated, hours: [{label, temp, sky, day}]}
 * glass -> phone  sync     {}    send what you have, and fetch if it's old
 * glass -> phone  refresh  {}    fetch now
 * </pre>
 */
final class WeatherProvider implements LinkManager.GlassHook {

    static final String CHANNEL = "dev.glassrevival.weather";
    private static final String TYPE = "weather";

    private static final String TAG = "GlassLink";
    private static final long STALE_MS = 30 * 60 * 1000L;
    private static final long MIN_FETCH_GAP_MS = 60 * 1000L;
    private static final long CHECK_EVERY_MIN = 10;

    private final Context context;
    private final Prefs prefs;
    private final LinkManager link;
    private final ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor();

    WeatherProvider(Context context, Prefs prefs, LinkManager link) {
        this.context = context.getApplicationContext();
        this.prefs = prefs;
        this.link = link;
        exec.scheduleWithFixedDelay(() -> {
            if (link.isConnected()) refresh(false);
        }, CHECK_EVERY_MIN, CHECK_EVERY_MIN, TimeUnit.MINUTES);
    }

    // ---- Triggers -------------------------------------------------------------------------

    @Override
    public void onGlassConnected() {
        exec.execute(() -> {
            sendCached();
            refresh(false);
        });
    }

    /** A frame from the Weather app on Glass. */
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

    /** The Weather app was just installed on Glass. */
    void glassAppInstalled() {
        exec.execute(() -> {
            sendCached();
            refresh(false);
        });
    }

    /** False when Glass reported it has no Weather app: then nothing is fetched or sent. */
    private boolean glassHasApp() {
        return CompanionApp.addOns(context).isInstalled(AddOnManager.WEATHER);
    }

    private void send(JSONObject msg) {
        try {
            msg.put(Protocol.F_CHANNEL, CHANNEL);
        } catch (JSONException ignored) {
            // cannot happen
        }
        link.send(msg);
    }

    /** Settings changed (units, place): fetch again now. */
    void settingsChanged() {
        prefs.invalidateWeather();
        exec.execute(() -> refresh(true));
    }

    /**
     * Called while the app is on screen, when location access is allowed even without
     * "all the time" permission. The saved location is used for background updates.
     */
    void rememberForegroundLocation() {
        exec.execute(() -> {
            Location l = lastKnownLocation();
            if (l != null) prefs.setLastLocation(l.getLatitude(), l.getLongitude());
        });
    }

    // ---- Refresh --------------------------------------------------------------------------

    private void sendCached() {
        if (!prefs.weatherEnabled() || !glassHasApp()) return;
        String json = prefs.weatherJson();
        if (json == null) return;
        try {
            send(new JSONObject(json));
        } catch (JSONException ignored) {
            // corrupt cache; the next fetch replaces it
        }
    }

    private void refresh(boolean force) {
        if (!prefs.weatherEnabled() || !link.isConnected() || !glassHasApp()) return;
        long age = System.currentTimeMillis() - prefs.weatherFetchedAt();
        if (!force && age < STALE_MS) return;
        if (force && age < MIN_FETCH_GAP_MS) {
            sendCached();
            return;
        }
        double[] where;
        String place;
        double[] fixed = prefs.placeCoords();
        if (fixed != null) {
            where = fixed;
            place = prefs.placeName();
        } else {
            where = currentLocation();
            if (where == null) {
                prefs.setWeatherStatus("No location yet. Allow location access, or choose a place.");
                sendCached();
                return;
            }
            place = placeName(where[0], where[1]);
        }
        try {
            JSONObject msg = fetch(round(where[0]), round(where[1]), place);
            prefs.setWeather(msg.toString(), System.currentTimeMillis());
            prefs.setWeatherStatus("Updated " + DateFormat.getTimeFormat(context)
                    .format(Calendar.getInstance().getTime()) + (place.isEmpty() ? "" : ", " + place));
            send(msg);
        } catch (IOException | JSONException e) {
            Log.w(TAG, "Weather fetch failed", e);
            prefs.setWeatherStatus("Couldn't reach the weather service");
            sendCached();
        }
    }

    // ---- Forecast -------------------------------------------------------------------------

    private JSONObject fetch(double lat, double lon, String place) throws IOException, JSONException {
        boolean f = prefs.fahrenheit();
        String url = "https://api.open-meteo.com/v1/forecast"
                + "?latitude=" + lat + "&longitude=" + lon
                + "&current=temperature_2m,apparent_temperature,weather_code,wind_speed_10m,is_day"
                + "&hourly=temperature_2m,weather_code,is_day"
                + "&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max"
                + "&forecast_days=2&timezone=auto"
                + (f ? "&temperature_unit=fahrenheit&wind_speed_unit=mph" : "&wind_speed_unit=kmh");
        JSONObject data = new JSONObject(get(url));
        JSONObject current = data.getJSONObject("current");
        JSONObject daily = data.getJSONObject("daily");
        int code = current.getInt("weather_code");

        JSONObject msg = new JSONObject();
        msg.put(Protocol.F_TYPE, TYPE);
        msg.put(Protocol.F_PLACE, place);
        msg.put(Protocol.F_TEMP, (int) Math.round(current.getDouble("temperature_2m")));
        msg.put(Protocol.F_FEELS, (int) Math.round(current.getDouble("apparent_temperature")));
        msg.put(Protocol.F_DESC, describe(code));
        msg.put(Protocol.F_SKY, sky(code));
        msg.put(Protocol.F_DAY, current.optInt("is_day", 1) == 1);
        msg.put(Protocol.F_HIGH, (int) Math.round(daily.getJSONArray("temperature_2m_max").getDouble(0)));
        msg.put(Protocol.F_LOW, (int) Math.round(daily.getJSONArray("temperature_2m_min").getDouble(0)));
        JSONArray rain = daily.optJSONArray("precipitation_probability_max");
        if (rain != null && !rain.isNull(0)) msg.put(Protocol.F_RAIN, rain.getInt(0));
        msg.put(Protocol.F_WIND, (int) Math.round(current.getDouble("wind_speed_10m")));
        msg.put(Protocol.F_UNIT, f ? "F" : "C");
        msg.put(Protocol.F_WIND_UNIT, f ? "mph" : "km/h");
        msg.put(Protocol.F_UPDATED, System.currentTimeMillis());
        msg.put(Protocol.F_HOURS, nextHours(data.getJSONObject("hourly"), current.getString("time")));
        return msg;
    }

    /** Three upcoming hours, two hours apart, starting with the next full hour. */
    private JSONArray nextHours(JSONObject hourly, String now) throws JSONException {
        JSONArray times = hourly.getJSONArray("time");
        JSONArray temps = hourly.getJSONArray("temperature_2m");
        JSONArray codes = hourly.getJSONArray("weather_code");
        JSONArray days = hourly.optJSONArray("is_day");
        int start = -1;
        for (int i = 0; i < times.length(); i++) {
            // Same ISO format ("2026-11-23T16:00"), so string order is time order.
            if (times.getString(i).compareTo(now) > 0) {
                start = i;
                break;
            }
        }
        JSONArray out = new JSONArray();
        if (start < 0) return out;
        boolean h24 = DateFormat.is24HourFormat(context);
        for (int i = start; i < times.length() && out.length() < 3; i += 2) {
            int hour = Integer.parseInt(times.getString(i).substring(11, 13));
            String label = h24 ? String.format(Locale.US, "%02d:00", hour)
                    : (hour % 12 == 0 ? 12 : hour % 12) + (hour < 12 ? " AM" : " PM");
            JSONObject h = new JSONObject();
            h.put(Protocol.F_LABEL, label);
            h.put(Protocol.F_TEMP, (int) Math.round(temps.getDouble(i)));
            h.put(Protocol.F_SKY, sky(codes.getInt(i)));
            h.put(Protocol.F_DAY, days == null || days.optInt(i, 1) == 1);
            out.put(h);
        }
        return out;
    }

    /** WMO weather codes, as used by Open-Meteo. */
    static String describe(int code) {
        switch (code) {
            case 0: return "Clear";
            case 1: return "Mostly clear";
            case 2: return "Partly cloudy";
            case 3: return "Cloudy";
            case 45: case 48: return "Fog";
            case 51: case 53: case 55: return "Drizzle";
            case 56: case 57: return "Freezing drizzle";
            case 61: return "Light rain";
            case 63: return "Rain";
            case 65: return "Heavy rain";
            case 66: case 67: return "Freezing rain";
            case 71: return "Light snow";
            case 73: return "Snow";
            case 75: return "Heavy snow";
            case 77: return "Snow grains";
            case 80: return "Light showers";
            case 81: return "Showers";
            case 82: return "Heavy showers";
            case 85: case 86: return "Snow showers";
            case 95: return "Thunderstorm";
            case 96: case 99: return "Thunderstorm, hail";
            default: return "";
        }
    }

    static String sky(int code) {
        if (code <= 1) return Protocol.SKY_CLEAR;
        if (code == 2) return Protocol.SKY_PARTLY;
        if (code == 3) return Protocol.SKY_CLOUDY;
        if (code == 45 || code == 48) return Protocol.SKY_FOG;
        if ((code >= 71 && code <= 77) || code == 85 || code == 86) return Protocol.SKY_SNOW;
        if (code >= 95) return Protocol.SKY_STORM;
        if (code >= 51) return Protocol.SKY_RAIN;
        return Protocol.SKY_CLOUDY;
    }

    // ---- Place search (for choosing a fixed place) ----------------------------------------

    static final class Place {
        final String name;
        final String detail;
        final double lat;
        final double lon;

        Place(String name, String detail, double lat, double lon) {
            this.name = name;
            this.detail = detail;
            this.lat = lat;
            this.lon = lon;
        }
    }

    /** Blocking; call off the main thread. */
    static List<Place> search(String query) throws IOException, JSONException {
        String url = "https://geocoding-api.open-meteo.com/v1/search?count=6&format=json&name="
                + URLEncoder.encode(query.trim(), "UTF-8")
                + "&language=" + Locale.getDefault().getLanguage();
        JSONArray results = new JSONObject(get(url)).optJSONArray("results");
        List<Place> out = new ArrayList<>();
        if (results == null) return out;
        for (int i = 0; i < results.length(); i++) {
            JSONObject r = results.getJSONObject(i);
            StringBuilder detail = new StringBuilder();
            for (String k : new String[] {"admin1", "country"}) {
                String v = r.optString(k, "");
                if (!v.isEmpty()) {
                    if (detail.length() > 0) detail.append(", ");
                    detail.append(v);
                }
            }
            out.add(new Place(r.getString("name"), detail.toString(),
                    r.getDouble("latitude"), r.getDouble("longitude")));
        }
        return out;
    }

    // ---- Location -------------------------------------------------------------------------

    private boolean hasLocationPermission() {
        return context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    /** A fresh fix if Android allows one right now, else the last saved location. */
    private double[] currentLocation() {
        Location l = freshLocation();
        if (l == null) l = lastKnownLocation();
        if (l != null) {
            prefs.setLastLocation(l.getLatitude(), l.getLongitude());
            return new double[] {l.getLatitude(), l.getLongitude()};
        }
        return prefs.lastLocation();
    }

    private Location freshLocation() {
        if (!hasLocationPermission() || Build.VERSION.SDK_INT < 30) return null;
        LocationManager lm = context.getSystemService(LocationManager.class);
        if (lm == null) return null;
        String provider = null;
        if (Build.VERSION.SDK_INT >= 31 && lm.hasProvider(LocationManager.FUSED_PROVIDER)
                && lm.isProviderEnabled(LocationManager.FUSED_PROVIDER)) {
            provider = LocationManager.FUSED_PROVIDER;
        } else if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
            provider = LocationManager.NETWORK_PROVIDER;
        }
        if (provider == null) return null;
        final AtomicReference<Location> result = new AtomicReference<>();
        final CountDownLatch done = new CountDownLatch(1);
        try {
            lm.getCurrentLocation(provider, null, context.getMainExecutor(), loc -> {
                result.set(loc);
                done.countDown();
            });
            done.await(20, TimeUnit.SECONDS);
        } catch (SecurityException e) {
            // In the background without "all the time" access. Fall back to the saved fix.
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return result.get();
    }

    private Location lastKnownLocation() {
        if (!hasLocationPermission()) return null;
        LocationManager lm = context.getSystemService(LocationManager.class);
        if (lm == null) return null;
        Location best = null;
        for (String p : lm.getProviders(true)) {
            try {
                Location l = lm.getLastKnownLocation(p);
                if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
            } catch (SecurityException | IllegalArgumentException ignored) {
                // provider not allowed right now
            }
        }
        return best;
    }

    @SuppressWarnings("deprecation") // the async Geocoder API needs 33+; this runs off the main thread
    private String placeName(double lat, double lon) {
        if (!Geocoder.isPresent()) return "";
        try {
            List<Address> list = new Geocoder(context, Locale.getDefault()).getFromLocation(lat, lon, 1);
            if (list == null || list.isEmpty()) return "";
            Address a = list.get(0);
            if (a.getLocality() != null) return a.getLocality();
            if (a.getSubAdminArea() != null) return a.getSubAdminArea();
            return a.getAdminArea() == null ? "" : a.getAdminArea();
        } catch (IOException | IllegalArgumentException e) {
            return "";
        }
    }

    private static double round(double v) {
        return Math.round(v * 100) / 100.0;
    }

    /** Small HTTP GET helper, shared with {@link MetarProvider}. Blocking. */
    static String get(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(15000);
        c.setRequestProperty("Accept", "application/json");
        c.setRequestProperty("User-Agent", "GlassLink/0.1 (Google Glass companion)");
        try {
            int status = c.getResponseCode();
            if (status == 204) return "";
            if (status != 200) throw new IOException("HTTP " + status);
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                byte[] chunk = new byte[8192];
                int n;
                while ((n = in.read(chunk)) > 0) buf.write(chunk, 0, n);
                return new String(buf.toByteArray(), StandardCharsets.UTF_8);
            }
        } finally {
            c.disconnect();
        }
    }
}
