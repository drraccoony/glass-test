package dev.glassrevival.companion;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.text.Html;
import android.util.Base64;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Turns a shared web page or text into the recipe message for the Recipes app on Glass:
 * {type: "recipe", title, source?, time?, yield?, image?, ingredients[], steps[]}.
 *
 * <p>Web pages: almost every recipe site embeds schema.org data as JSON-LD (a Recipe, or
 * a HowTo for other instructions), which carries clean ingredient and step lists. Text:
 * each line or numbered item becomes a step, and a short first line becomes the title.
 */
final class RecipeParser {

    static final class NoRecipeException extends Exception {
        NoRecipeException(String message) {
            super(message);
        }
    }

    private static final int MAX_PAGE_BYTES = 4 * 1024 * 1024;
    private static final Pattern URL = Pattern.compile("https?://\\S+");
    private static final Pattern LD_JSON = Pattern.compile(
            "<script[^>]*type\\s*=\\s*[\"']application/ld\\+json[\"'][^>]*>(.*?)</script>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern NUMBERED = Pattern.compile("^\\s*(?:step\\s*)?\\d+\\s*[.):-]\\s*", Pattern.CASE_INSENSITIVE);
    private static final Pattern BULLET = Pattern.compile("^\\s*[-*•·]\\s+");

    private RecipeParser() {}

    /** A shared URL (fetched, needs the network) or plain text. Runs off the UI thread. */
    static JSONObject parse(String shared, String subject) throws IOException, NoRecipeException, JSONException {
        String text = shared == null ? "" : unwrapReaderView(shared.trim());
        Matcher m = URL.matcher(text);
        // Browsers share "Title https://…"; a bare link means "read this page".
        if (m.find() && text.length() - m.group().length() < 200) {
            return fromPage(m.group());
        }
        return fromText(text, subject);
    }

    /**
     * Firefox's Reader View shares "about:reader?url=https%3A%2F%2F…" with the real page
     * percent-encoded inside. Swap in the real address.
     */
    static String unwrapReaderView(String text) {
        Matcher m = Pattern.compile("about:reader\\?url=([^\\s&]+)").matcher(text);
        if (!m.find()) return text;
        try {
            String real = java.net.URLDecoder.decode(m.group(1), "UTF-8");
            return text.substring(0, m.start()) + real + text.substring(m.end());
        } catch (java.io.UnsupportedEncodingException | IllegalArgumentException e) {
            return text;
        }
    }

    // ---- Web pages ------------------------------------------------------------------------

    static JSONObject fromPage(String url) throws IOException, NoRecipeException, JSONException {
        String html = fetch(url);
        JSONObject found = null;
        Matcher m = LD_JSON.matcher(html);
        while (m.find() && found == null) {
            String json = m.group(1).trim();
            try {
                Object root = json.startsWith("[") ? new JSONArray(json) : new JSONObject(json);
                found = findRecipe(root);
            } catch (JSONException ignored) {
                // some sites ship broken JSON-LD; try the next block
            }
        }
        if (found == null) throw new NoRecipeException("That page has no recipe or instructions Glass can read");

        JSONObject out = new JSONObject().put("type", "recipe");
        out.put("title", clean(found.optString("name", "Recipe")));
        out.put("source", new URL(url).getHost().replaceFirst("^www\\.", ""));
        String time = duration(found.optString("totalTime", found.optString("cookTime", "")));
        if (!time.isEmpty()) out.put("time", time);
        String yield = yieldOf(found.opt("recipeYield"));
        if (!yield.isEmpty()) out.put("yield", yield);

        JSONArray ingredients = new JSONArray();
        Object ing = found.opt("recipeIngredient");
        if (ing == null) ing = found.opt("supply"); // HowTo
        for (String s : texts(ing)) ingredients.put(s);
        out.put("ingredients", ingredients);

        List<String> steps = new ArrayList<>();
        Object inst = found.opt("recipeInstructions");
        if (inst == null) inst = found.opt("step"); // HowTo
        collectSteps(inst, steps);
        if (steps.isEmpty()) throw new NoRecipeException("The recipe on that page has no steps");
        JSONArray stepArray = new JSONArray();
        for (String s : steps) stepArray.put(s);
        out.put("steps", stepArray);

        String image = imageUrl(found.opt("image"));
        if (image != null) {
            String jpeg = fetchImage(image);
            if (jpeg != null) out.put("image", jpeg);
        }
        return out;
    }

    /** Finds a Recipe or HowTo anywhere in the JSON-LD (arrays, @graph, nested). */
    private static JSONObject findRecipe(Object node) {
        if (node instanceof JSONArray) {
            JSONArray a = (JSONArray) node;
            for (int i = 0; i < a.length(); i++) {
                JSONObject r = findRecipe(a.opt(i));
                if (r != null) return r;
            }
        } else if (node instanceof JSONObject) {
            JSONObject o = (JSONObject) node;
            if (isType(o, "Recipe") || isType(o, "HowTo")) return o;
            JSONObject r = findRecipe(o.opt("@graph"));
            if (r == null) r = findRecipe(o.opt("mainEntity"));
            return r;
        }
        return null;
    }

    private static boolean isType(JSONObject o, String type) {
        Object t = o.opt("@type");
        if (t instanceof String) return type.equals(t);
        if (t instanceof JSONArray) {
            JSONArray a = (JSONArray) t;
            for (int i = 0; i < a.length(); i++) if (type.equals(a.optString(i))) return true;
        }
        return false;
    }

    /** Instructions come as a string, strings, HowToStep{text}, or HowToSection{itemListElement}. */
    private static void collectSteps(Object node, List<String> out) {
        if (node == null) return;
        if (node instanceof String) {
            String s = clean((String) node);
            // One block of text: split it the same way as shared text.
            for (String line : s.split("\\n+")) {
                String step = stripMarker(line);
                if (!step.isEmpty()) out.add(step);
            }
        } else if (node instanceof JSONArray) {
            JSONArray a = (JSONArray) node;
            for (int i = 0; i < a.length(); i++) collectSteps(a.opt(i), out);
        } else if (node instanceof JSONObject) {
            JSONObject o = (JSONObject) node;
            if (o.has("itemListElement")) {
                collectSteps(o.opt("itemListElement"), out); // a section's steps
            } else {
                String text = clean(o.optString("text", o.optString("name", "")));
                if (!text.isEmpty()) out.add(text);
            }
        }
    }

    private static List<String> texts(Object node) {
        List<String> out = new ArrayList<>();
        if (node instanceof JSONArray) {
            JSONArray a = (JSONArray) node;
            for (int i = 0; i < a.length(); i++) {
                Object v = a.opt(i);
                String s = v instanceof JSONObject ? ((JSONObject) v).optString("name", "") : String.valueOf(v);
                s = clean(s);
                if (!s.isEmpty()) out.add(s);
            }
        } else if (node instanceof String) {
            String s = clean((String) node);
            if (!s.isEmpty()) out.add(s);
        }
        return out;
    }

    private static String yieldOf(Object node) {
        if (node instanceof JSONArray) {
            JSONArray a = (JSONArray) node;
            // Often ["4", "4 servings"]: the longest is the most readable.
            String best = "";
            for (int i = 0; i < a.length(); i++) {
                String s = clean(a.optString(i));
                if (s.length() > best.length()) best = s;
            }
            node = best;
        }
        if (node == null) return "";
        String s = clean(String.valueOf(node));
        return s.matches("\\d+") ? s + " servings" : s;
    }

    private static String imageUrl(Object node) {
        if (node instanceof String) return (String) node;
        if (node instanceof JSONArray) return imageUrl(((JSONArray) node).opt(0));
        if (node instanceof JSONObject) return ((JSONObject) node).optString("url", null);
        return null;
    }

    /** ISO 8601 "PT1H15M" to "1 hr 15 min". */
    static String duration(String iso) {
        Matcher m = Pattern.compile("P(?:(\\d+)D)?T?(?:(\\d+)H)?(?:(\\d+)M)?", Pattern.CASE_INSENSITIVE).matcher(iso);
        if (iso.isEmpty() || !m.matches()) return "";
        int minutes = (m.group(1) == null ? 0 : Integer.parseInt(m.group(1)) * 1440)
                + (m.group(2) == null ? 0 : Integer.parseInt(m.group(2)) * 60)
                + (m.group(3) == null ? 0 : Integer.parseInt(m.group(3)));
        if (minutes <= 0) return "";
        if (minutes < 60) return minutes + " min";
        return (minutes / 60) + " hr" + (minutes % 60 == 0 ? "" : " " + (minutes % 60) + " min");
    }

    // ---- Text -----------------------------------------------------------------------------

    static JSONObject fromText(String text, String subject) throws NoRecipeException, JSONException {
        List<String> lines = new ArrayList<>();
        for (String line : text.split("\\r?\\n")) {
            String s = line.trim();
            if (!s.isEmpty()) lines.add(s);
        }
        if (lines.isEmpty()) throw new NoRecipeException("Nothing to send");
        String title = subject == null ? "" : subject.trim();
        // A short first line without a step number is a title.
        if (title.isEmpty() && lines.size() > 1 && lines.get(0).length() <= 60
                && !NUMBERED.matcher(lines.get(0)).find()) {
            title = lines.remove(0);
        }
        // One long paragraph: split it into sentences so each card is readable.
        if (lines.size() == 1 && lines.get(0).length() > 200) {
            String para = lines.remove(0);
            for (String sentence : para.split("(?<=[.!?])\\s+")) {
                if (!sentence.trim().isEmpty()) lines.add(sentence.trim());
            }
        }
        JSONArray steps = new JSONArray();
        for (String l : lines) {
            String s = stripMarker(l);
            if (!s.isEmpty()) steps.put(s);
        }
        return new JSONObject()
                .put("type", "recipe")
                .put("title", title.isEmpty() ? "Instructions" : title)
                .put("ingredients", new JSONArray())
                .put("steps", steps);
    }

    private static String stripMarker(String line) {
        String s = NUMBERED.matcher(line).replaceFirst("");
        return BULLET.matcher(s).replaceFirst("").trim();
    }

    // ---- Network --------------------------------------------------------------------------

    private static String fetch(String url) throws IOException {
        HttpURLConnection c = open(url);
        try {
            InputStream in = c.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[32 * 1024];
            int n;
            while ((n = in.read(buf)) > 0 && out.size() < MAX_PAGE_BYTES) out.write(buf, 0, n);
            return out.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }

    /** The recipe photo, scaled for Glass (640×360) and compressed to keep the message small. */
    private static String fetchImage(String url) {
        try {
            HttpURLConnection c = open(url);
            Bitmap src;
            try {
                src = BitmapFactory.decodeStream(c.getInputStream());
            } finally {
                c.disconnect();
            }
            if (src == null) return null;
            float scale = Math.max(640f / src.getWidth(), 360f / src.getHeight());
            Bitmap scaled = Bitmap.createScaledBitmap(src,
                    Math.round(src.getWidth() * scale), Math.round(src.getHeight() * scale), true);
            // Center-crop to the Glass screen.
            Bitmap crop = Bitmap.createBitmap(scaled, (scaled.getWidth() - 640) / 2,
                    (scaled.getHeight() - 360) / 2, 640, 360);
            ByteArrayOutputStream jpeg = new ByteArrayOutputStream();
            crop.compress(Bitmap.CompressFormat.JPEG, 60, jpeg);
            return Base64.encodeToString(jpeg.toByteArray(), Base64.NO_WRAP);
        } catch (IOException | RuntimeException e) {
            return null; // the recipe still works without its photo
        }
    }

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(15000);
        c.setInstanceFollowRedirects(true);
        // A plain browser-like agent; some recipe sites refuse unknown clients.
        c.setRequestProperty("User-Agent",
                "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Mobile Safari/537.36");
        c.setRequestProperty("Accept-Language", Locale.getDefault().toLanguageTag());
        return c;
    }

    /** Decodes HTML entities and tags (some sites put markup in JSON-LD) and tidies spaces. */
    private static String clean(String s) {
        if (s == null) return "";
        String t = Html.fromHtml(s, Html.FROM_HTML_MODE_LEGACY).toString();
        return t.replace('\u00a0', ' ').replaceAll("[ \\t]+", " ").trim();
    }
}
