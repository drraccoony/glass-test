package dev.glassrevival.recipes;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Base64;
import android.util.Log;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * A recipe or set of instructions from the phone, and where the user is in it. The phone
 * sends it on hub channel dev.glassrevival.recipes as:
 *
 * <pre>
 * {type: "recipe", title, source?, time?, yield?, image? (base64 JPEG),
 *  ingredients: [string], steps: [string]}
 * </pre>
 *
 * Only the latest one is kept, in a file, so "ok glass, show recipe" can reopen it.
 */
final class Recipe {
    static final String TAG = "GlassLinkRecipes";
    private static final String FILE = "recipe.json";
    private static final String PREFS = "recipes";

    final String title, source, time, yield;
    final List<String> ingredients, steps;
    final Bitmap image;
    final String id;

    private Recipe(JSONObject j) {
        title = j.optString("title", "Recipe");
        source = j.optString("source", "");
        time = j.optString("time", "");
        yield = j.optString("yield", "");
        ingredients = strings(j.optJSONArray("ingredients"));
        steps = strings(j.optJSONArray("steps"));
        image = decode(j.optString("image", ""));
        id = Integer.toHexString((title + steps).hashCode());
    }

    // ---- Storage --------------------------------------------------------------------------

    /** Keeps a recipe from the phone as the current one, starting at its first card. */
    static void save(Context context, JSONObject msg) {
        try {
            FileOutputStream out = context.openFileOutput(FILE, Context.MODE_PRIVATE);
            try {
                out.write(msg.toString().getBytes("UTF-8"));
            } finally {
                out.close();
            }
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt("position", 0).apply();
        } catch (IOException e) {
            Log.w(TAG, "Couldn't save recipe", e);
        }
    }

    /** The current recipe, or null if none was sent yet. */
    static Recipe load(Context context) {
        File f = new File(context.getFilesDir(), FILE);
        if (!f.exists()) return null;
        try {
            byte[] data = new byte[(int) f.length()];
            FileInputStream in = new FileInputStream(f);
            try {
                int off = 0;
                while (off < data.length) {
                    int n = in.read(data, off, data.length - off);
                    if (n < 0) break;
                    off += n;
                }
            } finally {
                in.close();
            }
            return new Recipe(new JSONObject(new String(data, "UTF-8")));
        } catch (IOException | JSONException e) {
            Log.w(TAG, "Couldn't read recipe", e);
            return null;
        }
    }

    static int position(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("position", 0);
    }

    static void setPosition(Context context, int position) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt("position", position).apply();
    }

    // ---- Timers in steps ------------------------------------------------------------------

    private static final Pattern DURATION = Pattern.compile(
            "(\\d+(?:[.,]\\d+)?|a|an|one|two|three|four|five|six|seven|eight|nine|ten|fifteen|twenty|thirty|forty|forty-five|sixty)"
                    + "(?:\\s*(?:-|to|–|or)\\s*(\\d+(?:[.,]\\d+)?))?"
                    + "\\s*(hours?|hrs?|minutes?|mins?|seconds?|secs?)\\b",
            Pattern.CASE_INSENSITIVE);

    /** The first time mentioned in a step ("simmer 10 to 12 minutes" gives 12 min), or 0. */
    static long durationMs(String step) {
        Matcher m = DURATION.matcher(step);
        if (!m.find()) return 0;
        double v = number(m.group(2) != null ? m.group(2) : m.group(1));
        if (v <= 0) return 0;
        String unit = m.group(3).toLowerCase(Locale.ROOT);
        double seconds = unit.startsWith("h") ? v * 3600 : unit.startsWith("m") ? v * 60 : v;
        return (long) (seconds * 1000);
    }

    private static double number(String s) {
        switch (s.toLowerCase(Locale.ROOT)) {
            case "a": case "an": case "one": return 1;
            case "two": return 2;
            case "three": return 3;
            case "four": return 4;
            case "five": return 5;
            case "six": return 6;
            case "seven": return 7;
            case "eight": return 8;
            case "nine": return 9;
            case "ten": return 10;
            case "fifteen": return 15;
            case "twenty": return 20;
            case "thirty": return 30;
            case "forty": return 40;
            case "forty-five": return 45;
            case "sixty": return 60;
            default:
                try {
                    return Double.parseDouble(s.replace(',', '.'));
                } catch (NumberFormatException e) {
                    return 0;
                }
        }
    }

    static String formatDuration(long ms) {
        long s = (ms + 999) / 1000;
        if (s >= 3600) return String.format(Locale.US, "%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60);
        return String.format(Locale.US, "%d:%02d", s / 60, s % 60);
    }

    /**
     * Splits text into pieces of at most maxChars, between sentences where possible, then
     * between words. Pieces come out evenly sized rather than one full and one tiny.
     */
    static List<String> split(String text, int maxChars) {
        List<String> out = new ArrayList<>();
        if (text.length() <= maxChars) {
            out.add(text);
            return out;
        }
        // Aim for equal parts: 260 characters become two of ~130, not 170 and 90.
        int parts = (text.length() + maxChars - 1) / maxChars;
        int target = Math.min(maxChars, (text.length() + parts - 1) / parts + 20);
        List<String> pieces = new ArrayList<>();
        for (String sentence : text.split("(?<=[.!?;:])\\s+")) {
            if (sentence.length() <= maxChars) {
                pieces.add(sentence);
            } else {
                // One very long sentence: fall back to words.
                StringBuilder sb = new StringBuilder();
                for (String word : sentence.split("\\s+")) {
                    if (sb.length() > 0 && sb.length() + 1 + word.length() > maxChars) {
                        pieces.add(sb.toString());
                        sb.setLength(0);
                    }
                    if (sb.length() > 0) sb.append(' ');
                    sb.append(word);
                }
                if (sb.length() > 0) pieces.add(sb.toString());
            }
        }
        StringBuilder card = new StringBuilder();
        for (String piece : pieces) {
            int joined = card.length() == 0 ? piece.length() : card.length() + 1 + piece.length();
            if (card.length() > 0 && (joined > maxChars || card.length() >= target)) {
                out.add(card.toString());
                card.setLength(0);
            }
            if (card.length() > 0) card.append(' ');
            card.append(piece);
        }
        if (card.length() > 0) out.add(card.toString());
        return out;
    }

    // ---- Helpers --------------------------------------------------------------------------

    private static List<String> strings(JSONArray a) {
        List<String> out = new ArrayList<>();
        if (a == null) return out;
        for (int i = 0; i < a.length(); i++) {
            String s = a.optString(i, "").trim();
            if (!s.isEmpty()) out.add(s);
        }
        return out;
    }

    private static Bitmap decode(String base64) {
        if (base64.isEmpty()) return null;
        try {
            byte[] b = Base64.decode(base64, Base64.DEFAULT);
            return BitmapFactory.decodeByteArray(b, 0, b.length);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
