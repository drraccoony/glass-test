package dev.glassrevival.protocol;

/**
 * Wire protocol shared by the Glass app and the phone companion.
 *
 * <p>The link is a Bluetooth RFCOMM socket. Glass listens, the phone connects.
 * Every message is one frame: a 4-byte big-endian length followed by that many
 * bytes of UTF-8 JSON. Each JSON object has a {@code "type"} field.
 *
 * <pre>
 * phone -> glass   hello   {v, code, name, tz?}      must be the first frame
 * phone -> glass   clock   {tz}                      the phone's time zone changed
 * phone -> glass   metar   {updated, stations: [{id, name, raw, observed, cat}]}
 *                            an empty list removes the METAR cards
 * glass -> phone   metar_request                     user asked for fresh METARs
 * phone -> glass   settings {wake, sound, alertSeconds}  how Glass should alert
 *
 * Photos and videos on Glass (ids are "p123" for photos, "v45" for videos):
 * phone -> glass   media_list   {offset, limit}
 * glass -> phone   media_items  {offset, total, items: [{id, kind, name, taken, size, w, h, duration}]}
 * phone -> glass   media_thumb  {id}
 * glass -> phone   media_thumb_data {id, data}         base64 JPEG, about 320 px
 * phone -> glass   media_get    {id, bt}               bt=true forces Bluetooth transfer
 * glass -> phone   media_file   {id, name, size, mime, url?}
 *                    With a url, the phone downloads over Wi-Fi (asking again with bt=true
 *                    if that fails). Without one, media_chunk frames follow.
 * glass -> phone   media_chunk  {id, offset, data, last}
 * phone -> glass   media_cancel {id}
 * glass -> phone   media_error  {id, error}
 * phone -> glass   media_delete {ids}                 permanently deletes from Glass
 * glass -> phone   media_deleted {ids, failed}
 * glass -> phone   welcome {v, name, battery, charging}
 * glass -> phone   reject  {reason}                  then Glass closes the socket
 * phone -> glass   notify  {key, pkg, app, title, text, when, alert, icon?, canReply?, choices?}
 * phone -> glass   remove  {key}
 * glass -> phone   dismiss {key}                     user dismissed it on Glass
 * glass -> phone   reply   {key, text}               user replied on Glass
 * phone -> glass   reply_result {key, ok, error?}
 * phone -> glass   weather {place, temp, feels, desc, sky, day, high, low, rain, wind,
 *                            unit, windUnit, updated, hours: [{label, temp, sky}]}
 * glass -> phone   weather_request                   user asked for fresh weather
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
    public static final String REPLY = "reply";
    public static final String REPLY_RESULT = "reply_result";
    public static final String WEATHER = "weather";
    public static final String CLOCK = "clock";
    public static final String METAR = "metar";
    public static final String METAR_REQUEST = "metar_request";
    public static final String SETTINGS = "settings";
    public static final String MEDIA_LIST = "media_list";
    public static final String MEDIA_ITEMS = "media_items";
    public static final String MEDIA_THUMB = "media_thumb";
    public static final String MEDIA_THUMB_DATA = "media_thumb_data";
    public static final String MEDIA_GET = "media_get";
    public static final String MEDIA_FILE = "media_file";
    public static final String MEDIA_CHUNK = "media_chunk";
    public static final String MEDIA_CANCEL = "media_cancel";
    public static final String MEDIA_ERROR = "media_error";
    public static final String MEDIA_DELETE = "media_delete";
    public static final String MEDIA_DELETED = "media_deleted";
    public static final String MEDIA_PREFIX = "media_";
    public static final String WEATHER_REQUEST = "weather_request";

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
    /** True when the notification has a reply action the phone can fill in. */
    public static final String F_CAN_REPLY = "canReply";
    /** Suggested replies, as a JSON array of strings. */
    public static final String F_CHOICES = "choices";
    // Weather fields. "sky" is one of the SKY_* values below.
    public static final String F_PLACE = "place";
    public static final String F_TEMP = "temp";
    public static final String F_FEELS = "feels";
    public static final String F_DESC = "desc";
    public static final String F_SKY = "sky";
    public static final String F_DAY = "day";
    public static final String F_HIGH = "high";
    public static final String F_LOW = "low";
    public static final String F_RAIN = "rain";
    public static final String F_WIND = "wind";
    public static final String F_UNIT = "unit";
    public static final String F_WIND_UNIT = "windUnit";
    public static final String F_UPDATED = "updated";
    public static final String F_HOURS = "hours";
    public static final String F_LABEL = "label";

    public static final String SKY_CLEAR = "clear";
    public static final String SKY_PARTLY = "partly";
    public static final String SKY_CLOUDY = "cloudy";
    public static final String SKY_FOG = "fog";
    public static final String SKY_RAIN = "rain";
    public static final String SKY_SNOW = "snow";
    public static final String SKY_STORM = "storm";

    // Alert settings, chosen in the phone app and applied on Glass
    public static final String F_WAKE = "wake";
    public static final String F_SOUND = "sound";
    public static final String F_ALERT_SECONDS = "alertSeconds";

    // Media fields
    public static final String F_OFFSET = "offset";
    public static final String F_LIMIT = "limit";
    public static final String F_TOTAL = "total";
    public static final String F_ITEMS = "items";
    public static final String F_KIND = "kind";
    public static final String F_TAKEN = "taken";
    public static final String F_SIZE = "size";
    public static final String F_WIDTH = "w";
    public static final String F_HEIGHT = "h";
    public static final String F_DURATION = "duration";
    public static final String F_DATA = "data";
    public static final String F_BT = "bt";
    public static final String F_MIME = "mime";
    public static final String F_URL = "url";
    public static final String F_LAST = "last";
    public static final String F_IDS = "ids";
    public static final String F_FAILED = "failed";
    public static final String KIND_PHOTO = "photo";
    public static final String KIND_VIDEO = "video";
    /** Raw bytes per Bluetooth chunk; base64 makes each frame about 64 KB. */
    public static final int MEDIA_CHUNK_BYTES = 48 * 1024;

    // METAR fields
    public static final String F_STATIONS = "stations";
    public static final String F_ID = "id";
    public static final String F_RAW = "raw";
    public static final String F_OBSERVED = "observed";
    /** Flight category: VFR, MVFR, IFR or LIFR. */
    public static final String F_CATEGORY = "cat";

    /** An IANA time zone ID such as "America/Chicago". */
    public static final String F_TIMEZONE = "tz";

    public static final String F_OK = "ok";
    public static final String F_ERROR = "error";

    /** Most suggested replies sent with one notification. */
    public static final int MAX_CHOICES = 6;

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
