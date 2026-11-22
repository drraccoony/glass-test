package dev.glassrevival.protocol;

/**
 * Wire protocol shared by the Glass app and the phone companion.
 *
 * <p>The link is a Bluetooth RFCOMM socket. Glass listens, the phone connects.
 * Every message is one frame: a 4-byte big-endian length followed by that many
 * bytes of UTF-8 JSON. Each JSON object has a {@code "type"} field.
 *
 * <pre>
 * phone -> glass   hello   {v, code, name}           must be the first frame
 * glass -> phone   welcome {v, name, battery, charging}
 * glass -> phone   reject  {reason}                  then Glass closes the socket
 * phone -> glass   notify  {key, pkg, app, title, text, when, alert, icon?}
 * phone -> glass   remove  {key}
 * glass -> phone   dismiss {key}                     user dismissed it on Glass
 * glass -> phone   status  {battery, charging}
 * either           ping / pong
 * </pre>
 */
public final class Protocol {
    private Protocol() {}

    /** RFCOMM service UUID that Glass advertises over SDP. */
    public static final String SERVICE_UUID = "6b3c1f6e-2f0a-4c55-9d0e-6a9f4e1c2b77";
    public static final String SERVICE_NAME = "GlassLink";
    public static final int VERSION = 1;

    /** Largest frame either side will accept. Icons are small, so this is generous. */
    public static final int MAX_FRAME_BYTES = 512 * 1024;

    // Message types
    public static final String HELLO = "hello";
    public static final String WELCOME = "welcome";
    public static final String REJECT = "reject";
    public static final String NOTIFY = "notify";
    public static final String REMOVE = "remove";
    public static final String DISMISS = "dismiss";
    public static final String STATUS = "status";
    public static final String PING = "ping";
    public static final String PONG = "pong";

    // Field names
    public static final String F_TYPE = "type";
    public static final String F_VERSION = "v";
    public static final String F_CODE = "code";
    public static final String F_NAME = "name";
    public static final String F_REASON = "reason";
    public static final String F_KEY = "key";
    public static final String F_PACKAGE = "pkg";
    public static final String F_APP = "app";
    public static final String F_TITLE = "title";
    public static final String F_TEXT = "text";
    public static final String F_WHEN = "when";
    public static final String F_ALERT = "alert";
    public static final String F_ICON = "icon";
    public static final String F_BATTERY = "battery";
    public static final String F_CHARGING = "charging";

    // Reject reasons
    public static final String REASON_BAD_CODE = "bad_code";
    public static final String REASON_BAD_VERSION = "bad_version";

    /** Pair codes are exactly six ASCII digits. */
    public static boolean isValidPairCode(String code) {
        if (code == null || code.length() != 6) return false;
        for (int i = 0; i < 6; i++) {
            char c = code.charAt(i);
            if (c < '0' || c > '9') return false;
        }
        return true;
    }

    /** Constant-time comparison so the code can't be guessed digit by digit from timing. */
    public static boolean pairCodesMatch(String expected, String given) {
        if (expected == null || given == null) return false;
        byte[] a = expected.getBytes(FrameIO.UTF8);
        byte[] b = given.getBytes(FrameIO.UTF8);
        int diff = a.length ^ b.length;
        for (int i = 0; i < Math.min(a.length, b.length); i++) {
            diff |= a[i] ^ b[i];
        }
        return diff == 0;
    }
}
