package dev.glassrevival.companion;

import android.bluetooth.BluetoothAdapter;
import android.content.Context;
import android.content.SharedPreferences;
import dev.glassrevival.protocol.Protocol;
import java.util.HashSet;
import java.util.Set;

final class Prefs {
    private static final String ADDRESS = "glass_address";
    private static final String CODE = "pair_code";
    private static final String ENABLED = "enabled";
    private static final String MUTED = "muted_packages";
    private static final String SEEN = "seen_packages";
    private static final String QUICK_REPLIES = "quick_replies";
    private static final String WEATHER_ON = "weather_enabled";
    private static final String WEATHER_F = "weather_fahrenheit";
    private static final String PLACE_NAME = "weather_place_name";
    private static final String PLACE_LAT = "weather_place_lat";
    private static final String PLACE_LON = "weather_place_lon";
    private static final String LAST_LAT = "last_lat";
    private static final String LAST_LON = "last_lon";
    private static final String LAST_LOC_AT = "last_loc_at";
    private static final String WEATHER_JSON = "weather_json";
    private static final String WEATHER_AT = "weather_at";
    private static final String WEATHER_STATUS = "weather_status";
    private static final String WAKE = "alert_wake";
    private static final String SOUND = "alert_sound";
    private static final String ALERT_SECONDS = "alert_seconds";
    private static final String ONLY_LOCKED = "alert_only_locked";
    private static final String FOLLOW_DND = "follow_dnd";
    private static final String SHOW_TEXT = "show_text";
    private static final String INCLUDE_SILENT = "include_silent";
    private static final String METAR_ON = "metar_enabled";
    private static final String METAR_IDS = "metar_airports";
    private static final String METAR_JSON = "metar_json";
    private static final String METAR_AT = "metar_at";
    private static final String METAR_STATUS = "metar_status";
    static final String DEFAULT_QUICK_REPLIES =
            "OK\nOn my way\nCan't talk now, I'll reply later\nThanks!";

    private final SharedPreferences sp;
    private final Set<String> seenCache;

    Prefs(Context context) {
        sp = context.getSharedPreferences("glasslink", Context.MODE_PRIVATE);
        seenCache = new HashSet<>(sp.getStringSet(SEEN, new HashSet<String>()));
    }

    String glassAddress() {
        return sp.getString(ADDRESS, "");
    }

    String pairCode() {
        return sp.getString(CODE, "");
    }

    void setGlass(String address, String code) {
        sp.edit().putString(ADDRESS, address).putString(CODE, code).apply();
    }

    boolean isConfigured() {
        return BluetoothAdapter.checkBluetoothAddress(glassAddress())
                && Protocol.isValidPairCode(pairCode());
    }

    boolean enabled() {
        return sp.getBoolean(ENABLED, false);
    }

    void setEnabled(boolean enabled) {
        sp.edit().putBoolean(ENABLED, enabled).apply();
    }

    boolean isMuted(String pkg) {
        return sp.getStringSet(MUTED, new HashSet<String>()).contains(pkg);
    }

    Set<String> muted() {
        return new HashSet<>(sp.getStringSet(MUTED, new HashSet<String>()));
    }

    void setMuted(Set<String> packages) {
        sp.edit().putStringSet(MUTED, new HashSet<>(packages)).apply();
    }

    /** One reply per line, in the order they appear on Glass. */
    String quickRepliesText() {
        return sp.getString(QUICK_REPLIES, DEFAULT_QUICK_REPLIES);
    }

    void setQuickRepliesText(String text) {
        sp.edit().putString(QUICK_REPLIES, text).apply();
    }

    java.util.List<String> quickReplies() {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String line : quickRepliesText().split("\n")) {
            String s = line.trim();
            if (!s.isEmpty()) out.add(s);
        }
        return out;
    }

    // ---- Weather ----

    boolean weatherEnabled() {
        return sp.getBoolean(WEATHER_ON, true);
    }

    void setWeatherEnabled(boolean on) {
        sp.edit().putBoolean(WEATHER_ON, on).apply();
    }

    boolean fahrenheit() {
        if (sp.contains(WEATHER_F)) return sp.getBoolean(WEATHER_F, false);
        // Default by region: the countries that still use Fahrenheit.
        String country = java.util.Locale.getDefault().getCountry();
        return java.util.Arrays.asList("US", "LR", "MM", "BS", "BZ", "KY", "PW").contains(country);
    }

    void setFahrenheit(boolean f) {
        sp.edit().putBoolean(WEATHER_F, f).apply();
    }

    /** A fixed place chosen by the user, or null to use the phone's location. */
    String placeName() {
        return sp.getString(PLACE_NAME, null);
    }

    double[] placeCoords() {
        if (placeName() == null) return null;
        return new double[] {Double.parseDouble(sp.getString(PLACE_LAT, "0")),
                Double.parseDouble(sp.getString(PLACE_LON, "0"))};
    }

    void setPlace(String name, double lat, double lon) {
        sp.edit().putString(PLACE_NAME, name).putString(PLACE_LAT, Double.toString(lat))
                .putString(PLACE_LON, Double.toString(lon)).apply();
    }

    void clearPlace() {
        sp.edit().remove(PLACE_NAME).remove(PLACE_LAT).remove(PLACE_LON).apply();
    }

    void setLastLocation(double lat, double lon) {
        sp.edit().putString(LAST_LAT, Double.toString(lat)).putString(LAST_LON, Double.toString(lon))
                .putLong(LAST_LOC_AT, System.currentTimeMillis()).apply();
    }

    double[] lastLocation() {
        if (!sp.contains(LAST_LAT)) return null;
        return new double[] {Double.parseDouble(sp.getString(LAST_LAT, "0")),
                Double.parseDouble(sp.getString(LAST_LON, "0"))};
    }

    String weatherJson() {
        return sp.getString(WEATHER_JSON, null);
    }

    long weatherFetchedAt() {
        return sp.getLong(WEATHER_AT, 0);
    }

    void setWeather(String json, long at) {
        sp.edit().putString(WEATHER_JSON, json).putLong(WEATHER_AT, at).apply();
    }

    /** Clears the cached forecast so the next refresh fetches, e.g. after a settings change. */
    void invalidateWeather() {
        sp.edit().putLong(WEATHER_AT, 0).apply();
    }

    String weatherStatus() {
        return sp.getString(WEATHER_STATUS, "");
    }

    void setWeatherStatus(String status) {
        sp.edit().putString(WEATHER_STATUS, status).apply();
    }

    // ---- Notification options ----

    boolean wakeGlass() {
        return sp.getBoolean(WAKE, true);
    }

    void setWakeGlass(boolean on) {
        sp.edit().putBoolean(WAKE, on).apply();
    }

    boolean playSound() {
        return sp.getBoolean(SOUND, true);
    }

    void setPlaySound(boolean on) {
        sp.edit().putBoolean(SOUND, on).apply();
    }

    int alertSeconds() {
        return sp.getInt(ALERT_SECONDS, 10);
    }

    void setAlertSeconds(int seconds) {
        sp.edit().putInt(ALERT_SECONDS, seconds).apply();
    }

    boolean onlyWhenLocked() {
        return sp.getBoolean(ONLY_LOCKED, false);
    }

    void setOnlyWhenLocked(boolean on) {
        sp.edit().putBoolean(ONLY_LOCKED, on).apply();
    }

    boolean followDnd() {
        return sp.getBoolean(FOLLOW_DND, true);
    }

    void setFollowDnd(boolean on) {
        sp.edit().putBoolean(FOLLOW_DND, on).apply();
    }

    boolean showText() {
        return sp.getBoolean(SHOW_TEXT, true);
    }

    void setShowText(boolean on) {
        sp.edit().putBoolean(SHOW_TEXT, on).apply();
    }

    boolean includeSilent() {
        return sp.getBoolean(INCLUDE_SILENT, true);
    }

    void setIncludeSilent(boolean on) {
        sp.edit().putBoolean(INCLUDE_SILENT, on).apply();
    }

    // ---- METAR ----

    boolean metarEnabled() {
        return sp.getBoolean(METAR_ON, false);
    }

    void setMetarEnabled(boolean on) {
        sp.edit().putBoolean(METAR_ON, on).apply();
    }

    /** The airports as the user typed them, e.g. "KLVN, KMSP". */
    String metarAirportsText() {
        return sp.getString(METAR_IDS, "");
    }

    void setMetarAirportsText(String text) {
        sp.edit().putString(METAR_IDS, text).apply();
    }

    /** Cleaned-up airport codes, in the order typed, at most six. */
    java.util.List<String> metarAirports() {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String part : metarAirportsText().split("[,\\s]+")) {
            String id = part.trim().toUpperCase(java.util.Locale.ROOT);
            if (id.matches("[A-Z0-9]{3,4}") && !out.contains(id) && out.size() < 6) out.add(id);
        }
        return out;
    }

    String metarJson() {
        return sp.getString(METAR_JSON, null);
    }

    long metarFetchedAt() {
        return sp.getLong(METAR_AT, 0);
    }

    void setMetar(String json, long at) {
        sp.edit().putString(METAR_JSON, json).putLong(METAR_AT, at).apply();
    }

    String metarStatus() {
        return sp.getString(METAR_STATUS, "");
    }

    void setMetarStatus(String status) {
        sp.edit().putString(METAR_STATUS, status).apply();
    }

    /** Remembers apps that have posted notifications, so the mute list only shows relevant apps. */
    synchronized void recordSeen(String pkg) {
        if (seenCache.add(pkg)) {
            sp.edit().putStringSet(SEEN, new HashSet<>(seenCache)).apply();
        }
    }

    synchronized Set<String> seen() {
        return new HashSet<>(seenCache);
    }
}
