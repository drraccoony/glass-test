package dev.glassrevival.recipes;

import android.app.Activity;
import android.content.Intent;
import android.media.AudioManager;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.AdapterView;
import com.google.android.glass.media.Sounds;
import com.google.android.glass.view.WindowUtils;
import com.google.android.glass.widget.CardBuilder;
import com.google.android.glass.widget.CardScrollAdapter;
import com.google.android.glass.widget.CardScrollView;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Steps through the current recipe: a title card, ingredients, then one card per step.
 * The screen stays on. Swipe to move, tap for the menu, or say "ok glass" then "go
 * forward", "go back", "show instructions", "show ingredients", "read aloud", "start a
 * timer" (when the step mentions a time), "stop the timer" or "stop this".
 */
public class RecipeActivity extends Activity implements StepTimer.Listener {

    static final String EXTRA_RELOAD = "reload";
    static final String EXTRA_POSITION = "position";

    private static final int INGREDIENTS_PER_CARD = 5;
    /**
     * Most text a step card shows in full. A TEXT card fits about six lines of 35
     * characters at its smallest size before Glass cuts it off.
     */
    private static final int MAX_STEP_CHARS = 170;
    private static final int MENU_NEXT = 1, MENU_PREVIOUS = 2, MENU_INGREDIENTS = 3, MENU_READ = 4,
            MENU_TIMER = 5, MENU_STOP_TIMER = 6, MENU_CLOSE = 7, MENU_STEPS = 8;

    /** One card. kind decides how it's drawn. */
    private static final class Card {
        static final int TITLE = 0, INGREDIENTS = 1, STEP = 2, EMPTY = 3;
        final int kind;
        final String text;
        final int index, count; // page or step number (1-based) and how many
        final long timerMs;     // a step's mentioned time, or 0
        final int part, parts;  // a long step split across cards: which card of how many

        Card(int kind, String text, int index, int count, long timerMs) {
            this(kind, text, index, count, timerMs, 1, 1);
        }

        Card(int kind, String text, int index, int count, long timerMs, int part, int parts) {
            this.kind = kind;
            this.text = text;
            this.index = index;
            this.count = count;
            this.timerMs = timerMs;
            this.part = part;
            this.parts = parts;
        }
    }

    private Recipe recipe;
    private final List<Card> cards = new ArrayList<>();
    private int firstStep, firstIngredients = -1;
    private CardScrollView scroller;
    private TextToSpeech tts;
    private boolean ttsReady;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().requestFeature(WindowUtils.FEATURE_VOICE_COMMANDS);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED);
        scroller = new CardScrollView(this);
        scroller.setAdapter(adapter);
        scroller.setOnItemClickListener((parent, view, position, id) -> {
            play(Sounds.TAP);
            openOptionsMenu();
        });
        scroller.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                // The voice menu is rebuilt in onPreparePanel each time "ok glass" opens it.
                // Don't invalidate it here: on XE24 that opens it, listening without "ok glass".
                Recipe.setPosition(RecipeActivity.this, position);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        setContentView(scroller);
        load(Recipe.position(this));
        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent.getBooleanExtra(EXTRA_RELOAD, false)) load(0);
        handleIntent(intent);
    }

    private void handleIntent(Intent intent) {
        int at = intent.getIntExtra(EXTRA_POSITION, -1);
        intent.removeExtra(EXTRA_POSITION);
        if (at >= 0 && at < cards.size()) select(at);
    }

    @Override
    protected void onResume() {
        super.onResume();
        scroller.activate();
        StepTimer.setListener(this);
        adapter.notifyDataSetChanged();
    }

    @Override
    protected void onPause() {
        StepTimer.setListener(null);
        scroller.deactivate();
        if (tts != null) tts.stop();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (tts != null) tts.shutdown();
        super.onDestroy();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER) {
            play(Sounds.TAP);
            openOptionsMenu();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    // ---- Cards ----------------------------------------------------------------------------

    private void load(int position) {
        recipe = Recipe.load(this);
        cards.clear();
        firstIngredients = -1;
        if (recipe == null) {
            cards.add(new Card(Card.EMPTY, getString(R.string.no_recipe_hint), 0, 0, 0));
        } else {
            cards.add(new Card(Card.TITLE, recipe.title, 0, 0, 0));
            int pages = (recipe.ingredients.size() + INGREDIENTS_PER_CARD - 1) / INGREDIENTS_PER_CARD;
            if (pages > 0) firstIngredients = cards.size();
            for (int p = 0; p < pages; p++) {
                StringBuilder sb = new StringBuilder();
                for (int i = p * INGREDIENTS_PER_CARD;
                        i < Math.min(recipe.ingredients.size(), (p + 1) * INGREDIENTS_PER_CARD); i++) {
                    if (sb.length() > 0) sb.append('\n');
                    sb.append("• ").append(recipe.ingredients.get(i));
                }
                cards.add(new Card(Card.INGREDIENTS, sb.toString(), p + 1, pages, 0));
            }
            firstStep = cards.size();
            for (int i = 0; i < recipe.steps.size(); i++) {
                String s = recipe.steps.get(i);
                // Long steps don't fit one card (Glass cuts them off with "…"), so they
                // continue on the next card, split between sentences.
                List<String> chunks = Recipe.split(s, MAX_STEP_CHARS);
                for (int k = 0; k < chunks.size(); k++) {
                    String chunk = chunks.get(k);
                    cards.add(new Card(Card.STEP, chunk, i + 1, recipe.steps.size(),
                            Recipe.durationMs(chunk), k + 1, chunks.size()));
                }
            }
        }
        adapter.notifyDataSetChanged();
        select(Math.max(0, Math.min(position, cards.size() - 1)));
    }

    private final CardScrollAdapter adapter = new CardScrollAdapter() {
        @Override
        public int getCount() {
            return cards.size();
        }

        @Override
        public Object getItem(int position) {
            return cards.get(position);
        }

        @Override
        public int getPosition(Object item) {
            return cards.indexOf(item);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public int getViewTypeCount() {
            return CardBuilder.getViewTypeCount();
        }

        @Override
        public int getItemViewType(int position) {
            return build(cards.get(position), position).getItemViewType();
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            return build(cards.get(position), position).getView(convertView, parent);
        }
    };

    /**
     * Shows a card. CardScrollView ignores setSelection until it has laid out its cards,
     * so apply it again after layout. Used to resume where you left off and when a timer rings.
     */
    private void select(final int position) {
        scroller.setSelection(position);
        scroller.post(() -> {
            if (position < cards.size()) scroller.setSelection(position);
        });
    }

    private CardBuilder build(Card c, int position) {
        switch (c.kind) {
            case Card.EMPTY:
                return new CardBuilder(this, CardBuilder.Layout.ALERT)
                        .setIcon(R.drawable.ic_recipe)
                        .setText(getString(R.string.no_recipe))
                        .setFootnote(c.text);
            case Card.TITLE: {
                String info = summary();
                if (recipe.image != null) {
                    // TITLE fits one line (about 22 characters); longer titles get CAPTION,
                    // which has room for two lines and the summary.
                    if (c.text.length() <= 22) {
                        return new CardBuilder(this, CardBuilder.Layout.TITLE)
                                .addImage(recipe.image)
                                .setText(c.text)
                                .setIcon(R.drawable.ic_recipe);
                    }
                    // Short footnote: the "ok glass" prompt sits in the middle of the bottom edge.
                    return new CardBuilder(this, CardBuilder.Layout.CAPTION)
                            .addImage(recipe.image)
                            .setText(c.text)
                            .setFootnote(recipe.steps.size() + (recipe.steps.size() == 1 ? " step" : " steps"))
                            .setTimestamp(recipe.time)
                            .setIcon(R.drawable.ic_recipe);
                }
                return new CardBuilder(this, CardBuilder.Layout.TEXT)
                        .setText(c.text)
                        .setFootnote(info)
                        .setTimestamp(recipe.time);
            }
            case Card.INGREDIENTS:
                return new CardBuilder(this, CardBuilder.Layout.TEXT_FIXED)
                        .setText(c.text)
                        .setFootnote(c.count > 1
                                ? "Ingredients " + c.index + " of " + c.count
                                : "Ingredients");
            default: { // STEP
                CardBuilder b = new CardBuilder(this, CardBuilder.Layout.TEXT)
                        .setText(c.text)
                        .setFootnote(c.parts > 1
                                ? "Step " + c.index + " · " + c.part + "/" + c.parts
                                : "Step " + c.index + " of " + c.count);
                if (StepTimer.running() && StepTimer.step() == position) {
                    // Words, not a timer symbol: Glass's KitKat font has no glyph for it.
                    b.setTimestamp(Recipe.formatDuration(StepTimer.remainingMs()) + " left");
                } else if (c.timerMs > 0) {
                    b.setTimestamp(Recipe.formatDuration(c.timerMs) + " timer");
                }
                return b;
            }
        }
    }

    private String summary() {
        List<String> parts = new ArrayList<>();
        if (!recipe.ingredients.isEmpty()) parts.add(recipe.ingredients.size() + " ingredients");
        parts.add(recipe.steps.size() + (recipe.steps.size() == 1 ? " step" : " steps"));
        if (!recipe.yield.isEmpty()) parts.add(recipe.yield);
        if (!recipe.source.isEmpty()) parts.add(recipe.source);
        return android.text.TextUtils.join(" · ", parts);
    }

    private Card selected() {
        int p = scroller.getSelectedItemPosition();
        return p >= 0 && p < cards.size() ? cards.get(p) : null;
    }

    // ---- Menus (tap) and voice commands ("ok glass, …") ----------------------------------

    @Override
    public boolean onCreatePanelMenu(int featureId, Menu menu) {
        if (featureId == WindowUtils.FEATURE_VOICE_COMMANDS || featureId == Window.FEATURE_OPTIONS_PANEL) {
            fillMenu(menu, featureId == WindowUtils.FEATURE_VOICE_COMMANDS);
            return true;
        }
        return super.onCreatePanelMenu(featureId, menu);
    }

    @Override
    public boolean onPreparePanel(int featureId, View view, Menu menu) {
        // Only the tap menu changes with the card. Preparing the voice menu here makes
        // XE24 show it (listening without "ok glass"), so it is built once and left alone.
        if (featureId == Window.FEATURE_OPTIONS_PANEL) {
            menu.clear();
            fillMenu(menu, false);
            return true;
        }
        return super.onPreparePanel(featureId, view, menu);
    }

    /** Voice menu titles are the phrases to say, so they stay plain there. */
    private void fillMenu(Menu menu, boolean voice) {
        if (voice) {
            // Fixed list; commands that don't apply to the current card just play "disallowed".
            // Only Glass's built-in phrases work offline; see voice_* in strings.xml.
            menu.add(Menu.NONE, MENU_NEXT, 0, R.string.voice_next);
            menu.add(Menu.NONE, MENU_PREVIOUS, 1, R.string.voice_previous);
            menu.add(Menu.NONE, MENU_STEPS, 2, R.string.voice_steps);
            menu.add(Menu.NONE, MENU_INGREDIENTS, 3, R.string.voice_ingredients);
            menu.add(Menu.NONE, MENU_TIMER, 4, R.string.voice_timer);
            menu.add(Menu.NONE, MENU_STOP_TIMER, 5, R.string.voice_stop_timer);
            menu.add(Menu.NONE, MENU_READ, 6, R.string.voice_read);
            menu.add(Menu.NONE, MENU_CLOSE, 7, R.string.voice_close);
            return;
        }
        if (recipe == null) {
            menu.add(Menu.NONE, MENU_CLOSE, 0, R.string.cmd_close);
            return;
        }
        int p = scroller.getSelectedItemPosition();
        Card c = selected();
        if (p < cards.size() - 1) menu.add(Menu.NONE, MENU_NEXT, 0, R.string.cmd_next).setIcon(R.drawable.ic_next);
        if (p > 0) menu.add(Menu.NONE, MENU_PREVIOUS, 1, R.string.cmd_previous);
        if (c != null && c.kind == Card.STEP && c.timerMs > 0 && !(StepTimer.running() && StepTimer.step() == p)) {
            String title = getString(R.string.cmd_timer);
            if (!voice) title = "Start a " + Recipe.formatDuration(c.timerMs) + " timer";
            menu.add(Menu.NONE, MENU_TIMER, 2, title).setIcon(R.drawable.ic_timer);
        }
        if (StepTimer.running()) menu.add(Menu.NONE, MENU_STOP_TIMER, 3, R.string.cmd_stop_timer).setIcon(R.drawable.ic_timer);
        menu.add(Menu.NONE, MENU_READ, 4, R.string.cmd_read);
        if (firstIngredients >= 0) menu.add(Menu.NONE, MENU_INGREDIENTS, 5, R.string.cmd_ingredients);
        menu.add(Menu.NONE, MENU_CLOSE, 6, R.string.cmd_close);
    }

    @Override
    public boolean onMenuItemSelected(int featureId, MenuItem item) {
        if (featureId != WindowUtils.FEATURE_VOICE_COMMANDS && featureId != Window.FEATURE_OPTIONS_PANEL) {
            return super.onMenuItemSelected(featureId, item);
        }
        int p = scroller.getSelectedItemPosition();
        switch (item.getItemId()) {
            case MENU_NEXT:
                go(p + 1);
                break;
            case MENU_PREVIOUS:
                go(p - 1);
                break;
            case MENU_INGREDIENTS:
                go(firstIngredients); // -1 when there are none: disallowed sound
                break;
            case MENU_STEPS:
                go(recipe == null ? -1 : firstStep);
                break;
            case MENU_READ:
                readAloud();
                break;
            case MENU_TIMER: {
                Card c = selected();
                if (c != null && c.kind == Card.STEP && c.timerMs > 0) {
                    StepTimer.start(this, p, c.timerMs);
                    play(Sounds.SUCCESS);
                } else {
                    play(Sounds.DISALLOWED); // this card mentions no time
                }
                break;
            }
            case MENU_STOP_TIMER:
                if (StepTimer.running()) {
                    StepTimer.stop();
                    play(Sounds.DISMISSED);
                } else {
                    play(Sounds.DISALLOWED);
                }
                break;
            case MENU_CLOSE:
                finish();
                break;
            default:
                return false;
        }
        return true;
    }

    private void go(int position) {
        if (position < 0 || position >= cards.size()) {
            play(Sounds.DISALLOWED);
            return;
        }
        scroller.animate(position, CardScrollView.Animation.NAVIGATION);
        Recipe.setPosition(this, position);
    }

    private void readAloud() {
        Card c = selected();
        if (c == null) return;
        final String text;
        if (c.kind == Card.STEP) text = (c.part == 1 ? "Step " + c.index + ". " : "") + c.text;
        else if (c.kind == Card.INGREDIENTS) text = c.text.replace("• ", "").replace('\n', ',');
        else text = c.text;
        if (tts == null) {
            tts = new TextToSpeech(this, status -> {
                ttsReady = status == TextToSpeech.SUCCESS;
                if (ttsReady) {
                    tts.setLanguage(Locale.getDefault());
                    speak(text);
                }
            });
        } else if (ttsReady) {
            speak(text);
        }
    }

    @SuppressWarnings("deprecation") // the Bundle version needs API 21
    private void speak(String text) {
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null);
    }

    // ---- Timer ----------------------------------------------------------------------------

    @Override
    public void onTimerChanged() {
        adapter.notifyDataSetChanged(); // countdown on the step card
        StepTimer.consumeRang();
    }

    private void play(int sound) {
        ((AudioManager) getSystemService(AUDIO_SERVICE)).playSoundEffect(sound);
    }
}
