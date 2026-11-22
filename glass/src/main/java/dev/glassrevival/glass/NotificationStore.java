package dev.glassrevival.glass;

import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-memory list of notifications mirrored from the phone, plus the link state.
 * Safe to call from any thread; every change is applied on the main thread.
 */
final class NotificationStore {

    static final int MAX_NOTES = 50;

    enum LinkState { BLUETOOTH_OFF, WAITING, CONNECTED }

    static final class Note {
        final String key;
        final String pkg;
        final String app;
        final String title;
        final String text;
        final long when;
        final Bitmap icon;

        Note(String key, String pkg, String app, String title, String text, long when, Bitmap icon) {
            this.key = key;
            this.pkg = pkg;
            this.app = app;
            this.title = title;
            this.text = text;
            this.when = when;
            this.icon = icon;
        }
    }

    interface Listener {
        void onStoreChanged();
    }

    private static final NotificationStore INSTANCE = new NotificationStore();

    static NotificationStore get() {
        return INSTANCE;
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final List<Note> notes = new ArrayList<>(); // newest first, main thread only
    private LinkState linkState = LinkState.WAITING;
    private String peerName;

    private NotificationStore() {}

    /**
     * Adds or replaces a notification. An alerting note moves to the front; a
     * silent update keeps its place so the list doesn't jump around.
     */
    void upsert(final Note note, final boolean moveToFront) {
        main.post(() -> {
            int existing = indexOf(note.key);
            if (existing >= 0 && !moveToFront) {
                notes.set(existing, note);
            } else {
                if (existing >= 0) notes.remove(existing);
                notes.add(0, note);
                while (notes.size() > MAX_NOTES) notes.remove(notes.size() - 1);
            }
            fire();
        });
    }

    void remove(final String key) {
        main.post(() -> {
            int i = indexOf(key);
            if (i >= 0) {
                notes.remove(i);
                fire();
            }
        });
    }

    void clear() {
        main.post(() -> {
            notes.clear();
            fire();
        });
    }

    void setLink(final LinkState state, final String peer) {
        main.post(() -> {
            linkState = state;
            peerName = peer;
            fire();
        });
    }

    /** Main thread only. */
    List<Note> notes() {
        return new ArrayList<>(notes);
    }

    /** Main thread only. */
    LinkState linkState() {
        return linkState;
    }

    /** Main thread only. */
    String peerName() {
        return peerName;
    }

    void addListener(Listener l) {
        listeners.add(l);
    }

    void removeListener(Listener l) {
        listeners.remove(l);
    }

    private int indexOf(String key) {
        for (int i = 0; i < notes.size(); i++) {
            if (notes.get(i).key.equals(key)) return i;
        }
        return -1;
    }

    private void fire() {
        for (Listener l : listeners) l.onStoreChanged();
    }
}
