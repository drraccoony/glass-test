package com.google.android.glass.timeline;

import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.widget.RemoteViews;

/**
 * Stub. Signatures match glass-core.jar on XE24 (read with dexdump). A card that a
 * running service keeps in the timeline, left of the clock, e.g. navigation.
 */
public class LiveCard {
    public enum PublishMode { SILENT, REVEAL }

    public LiveCard(Context context, String tag) {
        throw new RuntimeException("Stub");
    }

    /** Ties the card to a service so the service stays in the foreground while published. */
    public LiveCard attach(Service service) {
        throw new RuntimeException("Stub");
    }

    public LiveCard setViews(RemoteViews views) {
        throw new RuntimeException("Stub");
    }

    /** Required before publishing: what tapping the card opens. */
    public LiveCard setAction(PendingIntent intent) {
        throw new RuntimeException("Stub");
    }

    public LiveCard setVoiceActionEnabled(boolean enabled) {
        throw new RuntimeException("Stub");
    }

    public void publish(PublishMode mode) {
        throw new RuntimeException("Stub");
    }

    public void unpublish() {
        throw new RuntimeException("Stub");
    }

    public boolean isPublished() {
        throw new RuntimeException("Stub");
    }

    /** Scrolls the timeline to this card. */
    public void navigate() {
        throw new RuntimeException("Stub");
    }
}
