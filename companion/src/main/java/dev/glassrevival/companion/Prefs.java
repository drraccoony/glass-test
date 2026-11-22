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
