package dev.glassrevival.glass;

import android.os.SystemClock;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;

/**
 * Turns Glass touchpad input into simple navigation.
 *
 * <p>Without the GDK, Glass delivers a tap as {@code KEYCODE_DPAD_CENTER} and a swipe down
 * as {@code KEYCODE_BACK}. Horizontal swipes only arrive as raw touchpad motion events, so
 * those are detected here. Arrow keys also work, which helps when testing in an emulator
 * or over {@code adb shell input keyevent}.
 */
final class Gestures {

    interface Callback {
        void onNext();

        void onPrevious();

        void onTap();

        void onBack();
    }

    private static final long MAX_SWIPE_MS = 900;
    private static final long KEY_DEBOUNCE_MS = 300;

    private final Callback callback;
    private float downX;
    private float downY;
    private long downAt;
    private boolean tracking;
    private long lastSwipeAt;

    Gestures(Callback callback) {
        this.callback = callback;
    }

    /**
     * @param viewWidth width of the touched view, used as the scale for touchscreens
     * @return true if the event completed a gesture
     */
    boolean onMotion(MotionEvent ev, int viewWidth) {
        boolean touchscreen = (ev.getSource() & InputDevice.SOURCE_TOUCHSCREEN) == InputDevice.SOURCE_TOUCHSCREEN;
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = ev.getX();
                downY = ev.getY();
                downAt = ev.getEventTime();
                tracking = true;
                return false;
            case MotionEvent.ACTION_CANCEL:
                tracking = false;
                return false;
            case MotionEvent.ACTION_UP:
                if (!tracking) return false;
                tracking = false;
                float dx = ev.getX() - downX;
                float dy = ev.getY() - downY;
                float scale = touchscreen ? viewWidth : xRange(ev);
                long duration = ev.getEventTime() - downAt;
                if (duration <= MAX_SWIPE_MS && Math.abs(dx) > scale * 0.15f && Math.abs(dx) > Math.abs(dy)) {
                    lastSwipeAt = SystemClock.uptimeMillis();
                    // On Glass, swiping toward your eye's front moves forward in the list.
                    if (dx > 0) callback.onNext(); else callback.onPrevious();
                    return true;
                }
                // Glass sends its own DPAD_CENTER for taps on the touchpad; only a
                // touchscreen (emulator, phone) needs us to recognize the tap here.
                if (touchscreen && Math.abs(dx) < scale * 0.04f && Math.abs(dy) < scale * 0.04f) {
                    callback.onTap();
                    return true;
                }
                return false;
            default:
                return false;
        }
    }

    boolean onKeyDown(int keyCode, KeyEvent ev) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                callback.onTap();
                return true;
            case KeyEvent.KEYCODE_BACK:
                callback.onBack();
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (recentSwipe()) return true;
                callback.onNext();
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (recentSwipe()) return true;
                callback.onPrevious();
                return true;
            case KeyEvent.KEYCODE_TAB:
                if (recentSwipe()) return true;
                if (ev.isShiftPressed()) callback.onPrevious(); else callback.onNext();
                return true;
            default:
                return false;
        }
    }

    private boolean recentSwipe() {
        return SystemClock.uptimeMillis() - lastSwipeAt < KEY_DEBOUNCE_MS;
    }

    private static float xRange(MotionEvent ev) {
        InputDevice device = ev.getDevice();
        if (device != null) {
            InputDevice.MotionRange range = device.getMotionRange(MotionEvent.AXIS_X, ev.getSource());
            if (range != null && range.getRange() > 0) return range.getRange();
        }
        return 1366f; // Glass touchpad width in device units
    }
}
