package dev.glassrevival.recipes;

import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import com.google.android.glass.media.Sounds;

/**
 * One kitchen timer, tied to a step. It lives in the app process, not the activity, so it
 * keeps running if the user goes back to the timeline. When it ends it chimes, wakes Glass
 * and reopens the recipe at that step.
 */
final class StepTimer {

    interface Listener {
        /** Called every second while running, and once more when it ends or is stopped. */
        void onTimerChanged();
    }

    private static final Handler main = new Handler(Looper.getMainLooper());
    private static long endsAt;     // elapsedRealtime, 0 when idle
    private static int step = -1;   // card position the timer belongs to
    private static boolean rang;    // ended and not yet seen
    private static Listener listener;
    private static Context app;

    private StepTimer() {}

    static void start(Context context, int cardPosition, long durationMs) {
        app = context.getApplicationContext();
        endsAt = SystemClock.elapsedRealtime() + durationMs;
        step = cardPosition;
        rang = false;
        main.removeCallbacks(tick);
        main.post(tick);
    }

    static void stop() {
        endsAt = 0;
        step = -1;
        rang = false;
        main.removeCallbacks(tick);
        notifyListener();
    }

    static boolean running() {
        return endsAt > 0;
    }

    static int step() {
        return step;
    }

    static long remainingMs() {
        return endsAt == 0 ? 0 : Math.max(0, endsAt - SystemClock.elapsedRealtime());
    }

    /** True once after the timer ended, until the user has seen it. */
    static boolean consumeRang() {
        boolean r = rang;
        rang = false;
        return r;
    }

    static void setListener(Listener l) {
        listener = l;
    }

    private static final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (endsAt == 0) return;
            if (remainingMs() > 0) {
                notifyListener();
                main.postDelayed(this, 1000 - (remainingMs() % 1000));
                return;
            }
            int at = step;
            endsAt = 0;
            rang = true;
            ring(at);
            notifyListener();
        }
    };

    private static void ring(int at) {
        if (app == null) return;
        final AudioManager audio = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
        // Three chimes, a second apart, so it's heard over a busy kitchen.
        for (int i = 0; i < 3; i++) {
            main.postDelayed(() -> audio.playSoundEffect(Sounds.NOTIFICATION), i * 1100L);
        }
        PowerManager pm = (PowerManager) app.getSystemService(Context.POWER_SERVICE);
        @SuppressWarnings("deprecation")
        PowerManager.WakeLock wake = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                | PowerManager.ACQUIRE_CAUSES_WAKEUP | PowerManager.ON_AFTER_RELEASE, "GlassLinkRecipes:timer");
        wake.acquire(8000);
        app.startActivity(new Intent(app, RecipeActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(RecipeActivity.EXTRA_POSITION, at));
    }

    private static void notifyListener() {
        Listener l = listener;
        if (l != null) l.onTimerChanged();
    }
}
