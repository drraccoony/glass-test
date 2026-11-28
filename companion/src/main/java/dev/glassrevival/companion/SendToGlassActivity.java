package dev.glassrevival.companion;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;
import dev.glassrevival.protocol.Protocol;
import org.json.JSONObject;

/**
 * "Send to Glass" in Android's share sheet: a recipe page or any instructions (text)
 * goes to the Recipes app on Glass. No screen of its own; it shows a toast and closes.
 */
public class SendToGlassActivity extends Activity {

    static final String CHANNEL = "dev.glassrevival.recipes";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent intent = getIntent();
        final String text = intent.getStringExtra(Intent.EXTRA_TEXT);
        final String subject = intent.getStringExtra(Intent.EXTRA_SUBJECT);
        final LinkManager link = CompanionApp.link(this);
        if (!link.isConnected()) {
            toast("Glass isn't connected");
            finish();
            return;
        }
        if (text == null || text.trim().isEmpty()) {
            toast("Nothing to send");
            finish();
            return;
        }
        toast("Sending to Glass…");
        new Thread(() -> {
            String result;
            try {
                JSONObject recipe = RecipeParser.parse(text, subject);
                recipe.put(Protocol.F_CHANNEL, CHANNEL);
                link.send(recipe);
                int steps = recipe.getJSONArray("steps").length();
                result = "Sent \"" + recipe.optString("title") + "\" to Glass (" + steps
                        + (steps == 1 ? " step)" : " steps)");
            } catch (RecipeParser.NoRecipeException e) {
                result = e.getMessage();
            } catch (Exception e) {
                Log.w(LinkManager.TAG, "Couldn't send to Glass", e);
                result = "Couldn't read that page";
            }
            final String message = result;
            runOnUiThread(() -> {
                toast(message);
                finish();
            });
        }, "GlassLink-send").start();
    }

    private void toast(String message) {
        Toast.makeText(getApplicationContext(), message, Toast.LENGTH_LONG).show();
    }
}
