package dev.glassrevival.companion;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.os.Bundle;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;
import android.util.Base64;
import android.util.Log;
import dev.glassrevival.protocol.Protocol;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.regex.Pattern;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Turn-by-turn for the GlassLink Navigation app on Glass. Reads Google Maps' navigation
 * notification and sends each step on the hub channel {@link #CHANNEL}.
 *
 * <pre>
 * phone -> glass  step  {dist, instr, eta, arrowId, arrow?, progress?, max?}
 *                       arrow: white PNG (base64), sent only when arrowId changes
 * phone -> glass  end   {}                          navigation stopped
 * glass -> phone  sync  {}                          resend the current step, with arrow
 * glass -> phone  exit  {}                          stop navigation in Maps
 * </pre>
 *
 * The Maps notification (2026) is titled "350 ft · Turn right onto E 130th St", with
 * subText "Arrive 1:11 AM", the turn arrow as its large icon, progress/progressMax along
 * the route, and an "Exit navigation" action.
 */
final class NavProvider implements LinkManager.GlassHook {

    static final String CHANNEL = "dev.glassrevival.nav";
    private static final String MAPS = "com.google.android.apps.maps";
    private static final int ARROW_PX = 120;
    private static final Pattern DISTANCE =
            Pattern.compile("^\\s*([\\d.,]+\\s*(?:ft|mi|m|km|yd|feet|miles|meters|metres))\\s*$",
                    Pattern.CASE_INSENSITIVE);

    private final Context context;
    private final LinkManager link;

    // Current navigation, guarded by this.
    private String key;
    private JSONObject step;      // last step, without the arrow
    private String arrowPng;      // base64 PNG of the current arrow
    private int arrowId;
    private int sentArrowId;      // arrow Glass already has, 0 = none
    private PendingIntent exitIntent;

    NavProvider(Context context, LinkManager link) {
        this.context = context.getApplicationContext();
        this.link = link;
    }

    static boolean isNavigation(StatusBarNotification sbn) {
        Notification n = sbn.getNotification();
        return n != null && MAPS.equals(sbn.getPackageName())
                && Notification.CATEGORY_NAVIGATION.equals(n.category);
    }

    /** Every posted notification; ignores all but Maps navigation. */
    synchronized void onPosted(StatusBarNotification sbn) {
        if (!isNavigation(sbn)) return;
        Notification n = sbn.getNotification();
        Bundle extras = n.extras;
        String title = str(extras.getCharSequence(Notification.EXTRA_TITLE));
        String text = str(extras.getCharSequence(Notification.EXTRA_TEXT));
        String sub = str(extras.getCharSequence(Notification.EXTRA_SUB_TEXT));

        // "350 ft · Turn right onto E 130th St", or older builds: title "350 ft", text "Turn right…"
        String dist = "";
        String instr = title;
        int dot = title.indexOf(" · ");
        if (dot > 0 && DISTANCE.matcher(title.substring(0, dot)).matches()) {
            dist = title.substring(0, dot).trim();
            instr = title.substring(dot + 3).trim();
        } else if (DISTANCE.matcher(title).matches() && !text.isEmpty()) {
            dist = title.trim();
            instr = text;
        } else if (instr.isEmpty()) {
            instr = text;
        }

        key = sbn.getKey();
        exitIntent = findExit(n);
        updateArrow(n);
        try {
            JSONObject s = new JSONObject()
                    .put(Protocol.F_TYPE, "step")
                    .put("dist", dist)
                    .put("instr", instr)
                    .put("eta", sub)
                    .put("arrowId", arrowId);
            int max = extras.getInt(Notification.EXTRA_PROGRESS_MAX, 0);
            if (max > 0) {
                s.put("progress", extras.getInt(Notification.EXTRA_PROGRESS, 0)).put("max", max);
            }
            // Maps reposts constantly; only send what changed.
            if (step != null && step.toString().equals(s.toString())) return;
            step = s;
        } catch (JSONException e) {
            return;
        }
        sendStep();
    }

    synchronized void onRemoved(StatusBarNotification sbn) {
        if (key == null || !key.equals(sbn.getKey())) return;
        key = null;
        step = null;
        arrowPng = null;
        arrowId = 0;
        sentArrowId = 0;
        exitIntent = null;
        Log.i(LinkManager.TAG, "Navigation ended");
        send(obj("end"));
    }

    /** A frame from the Glass nav app. */
    synchronized void onGlassMessage(JSONObject msg) {
        String type = msg.optString(Protocol.F_TYPE);
        if ("sync".equals(type)) {
            sentArrowId = 0;
            if (step != null) sendStep(); else send(obj("end"));
        } else if ("exit".equals(type)) {
            if (exitIntent == null) return;
            try {
                exitIntent.send();
                Log.i(LinkManager.TAG, "Stopped navigation from Glass");
            } catch (PendingIntent.CanceledException e) {
                Log.w(LinkManager.TAG, "Maps no longer accepts exit", e);
            }
        }
    }

    @Override
    public synchronized void onGlassConnected() {
        sentArrowId = 0; // Glass may have restarted
        if (step != null) sendStep();
    }

    @Override
    public void onGlassRequest(String type) {
        // Requests come on the channel instead.
    }

    // ---- Helpers --------------------------------------------------------------------------

    private void sendStep() {
        try {
            JSONObject out = new JSONObject(step.toString());
            if (arrowPng != null && arrowId != sentArrowId) {
                out.put("arrow", arrowPng);
                sentArrowId = arrowId;
            }
            send(out);
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

    private static JSONObject obj(String type) {
        try {
            return new JSONObject().put(Protocol.F_TYPE, type);
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The turn arrow, redrawn white so it shows on Glass's black display. */
    private void updateArrow(Notification n) {
        Icon icon = n.getLargeIcon();
        if (icon == null) return;
        Drawable d;
        try {
            d = icon.loadDrawable(context);
        } catch (RuntimeException e) {
            return;
        }
        if (d == null) return;
        Bitmap bitmap = Bitmap.createBitmap(ARROW_PX, ARROW_PX, Bitmap.Config.ARGB_8888);
        d.setBounds(0, 0, ARROW_PX, ARROW_PX);
        d.setColorFilter(new PorterDuffColorFilter(0xFFFFFFFF, PorterDuff.Mode.SRC_IN));
        d.draw(new Canvas(bitmap));
        int[] pixels = new int[ARROW_PX * ARROW_PX];
        bitmap.getPixels(pixels, 0, ARROW_PX, 0, 0, ARROW_PX, ARROW_PX);
        int id = Arrays.hashCode(pixels) | 1; // never 0
        if (id != arrowId) {
            ByteArrayOutputStream png = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, png);
            arrowPng = Base64.encodeToString(png.toByteArray(), Base64.NO_WRAP);
            arrowId = id;
        }
        bitmap.recycle();
    }

    private static PendingIntent findExit(Notification n) {
        if (n.actions == null) return null;
        for (Notification.Action a : n.actions) {
            String t = str(a.title).toLowerCase(java.util.Locale.ROOT);
            if (t.contains("exit") || t.contains("end") || t.contains("stop")) return a.actionIntent;
        }
        return n.actions.length > 0 ? n.actions[0].actionIntent : null;
    }

    private static String str(CharSequence cs) {
        return TextUtils.isEmpty(cs) ? "" : cs.toString().trim();
    }
}
