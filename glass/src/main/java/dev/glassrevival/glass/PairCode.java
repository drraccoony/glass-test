package dev.glassrevival.glass;

import android.content.Context;
import android.content.SharedPreferences;
import dev.glassrevival.protocol.Protocol;
import java.security.SecureRandom;
import java.util.Locale;

/** The six-digit code the phone must present before Glass accepts anything from it. */
final class PairCode {
    private PairCode() {}

    private static final String PREFS = "glasslink";
    private static final String KEY = "pair_code";

    static synchronized String get(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String code = prefs.getString(KEY, null);
        if (!Protocol.isValidPairCode(code)) {
            code = String.format(Locale.US, "%06d", new SecureRandom().nextInt(1000000));
            prefs.edit().putString(KEY, code).commit();
        }
        return code;
    }

    /** "123456" becomes "123 456", which is easier to read on the prism. */
    static String pretty(String code) {
        return code.substring(0, 3) + " " + code.substring(3);
    }
}
