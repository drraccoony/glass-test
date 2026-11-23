package com.google.android.glass.widget;

import android.content.Context;
import android.view.View;

/** Stub of the GDK slider: progress, waiting and grace-period bars at the bottom of a card. */
public class Slider {

    public interface GracePeriod {
        void cancel();

        interface Listener {
            void onGracePeriodEnd();

            void onGracePeriodCancel();
        }
    }

    public interface Indeterminate {
        void show();

        void hide();
    }

    Slider() {
        throw new RuntimeException("stub");
    }

    public static Slider from(View view) {
        throw new RuntimeException("stub");
    }

    public static Slider from(Context context) {
        throw new RuntimeException("stub");
    }

    public GracePeriod startGracePeriod(GracePeriod.Listener listener) {
        throw new RuntimeException("stub");
    }

    public Indeterminate startIndeterminate() {
        throw new RuntimeException("stub");
    }
}
