package dev.glassrevival.companion;

import android.content.Context;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.widget.ImageView;

/**
 * An image you can pinch to zoom, double-tap to zoom in or out, and drag while zoomed.
 * When not zoomed, a horizontal fling is reported so the viewer can move between items.
 */
public class ZoomImageView extends ImageView {

    interface SwipeListener {
        /** @param direction +1 for next (swiped left), -1 for previous */
        void onSwipe(int direction);
    }

    private static final float MAX_ZOOM = 6f;
    private static final float DOUBLE_TAP_ZOOM = 2.5f;

    private final Matrix matrix = new Matrix();
    private final float[] values = new float[9];
    private float baseScale = 1f; // the fit-to-screen scale
    private ScaleGestureDetector scaler;
    private GestureDetector gestures;
    private SwipeListener swipeListener;

    public ZoomImageView(Context context) {
        super(context);
        init(context);
    }

    public ZoomImageView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    void setSwipeListener(SwipeListener l) {
        swipeListener = l;
    }

    private void init(Context context) {
        setScaleType(ScaleType.MATRIX);
        scaler = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector d) {
                zoomBy(d.getScaleFactor(), d.getFocusX(), d.getFocusY());
                return true;
            }
        });
        gestures = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDoubleTap(MotionEvent e) {
                float target = zoom() > 1.05f ? 1f : DOUBLE_TAP_ZOOM;
                zoomBy(target / zoom(), e.getX(), e.getY());
                return true;
            }

            @Override
            public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                if (zoom() <= 1.01f) return false;
                matrix.postTranslate(-dx, -dy);
                clamp();
                return true;
            }

            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                if (zoom() > 1.01f || swipeListener == null || e1 == null) return false;
                float dx = e2.getX() - e1.getX();
                if (Math.abs(dx) > getWidth() * 0.15f && Math.abs(vx) > Math.abs(vy)) {
                    swipeListener.onSwipe(dx < 0 ? 1 : -1);
                    return true;
                }
                return false;
            }
        });
    }

    @Override
    public void setImageDrawable(Drawable drawable) {
        super.setImageDrawable(drawable);
        fit();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        fit();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        scaler.onTouchEvent(event);
        gestures.onTouchEvent(event);
        // Keep the parent from stealing the gesture while zoomed in.
        if (zoom() > 1.01f && getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
        return true;
    }

    /** Zoom relative to fit-to-screen: 1 means the whole image is visible. */
    float zoom() {
        matrix.getValues(values);
        return values[Matrix.MSCALE_X] / baseScale;
    }

    private void fit() {
        Drawable d = getDrawable();
        if (d == null || getWidth() == 0 || getHeight() == 0) return;
        float dw = d.getIntrinsicWidth();
        float dh = d.getIntrinsicHeight();
        baseScale = Math.min(getWidth() / dw, getHeight() / dh);
        matrix.reset();
        matrix.postScale(baseScale, baseScale);
        matrix.postTranslate((getWidth() - dw * baseScale) / 2f, (getHeight() - dh * baseScale) / 2f);
        setImageMatrix(matrix);
    }

    private void zoomBy(float factor, float fx, float fy) {
        float target = Math.max(1f, Math.min(MAX_ZOOM, zoom() * factor));
        factor = target / zoom();
        matrix.postScale(factor, factor, fx, fy);
        clamp();
    }

    /** Keeps the image covering the view when bigger than it, and centred when smaller. */
    private void clamp() {
        Drawable d = getDrawable();
        if (d == null) return;
        RectF r = new RectF(0, 0, d.getIntrinsicWidth(), d.getIntrinsicHeight());
        matrix.mapRect(r);
        float dx = 0;
        float dy = 0;
        if (r.width() <= getWidth()) dx = (getWidth() - r.width()) / 2f - r.left;
        else if (r.left > 0) dx = -r.left;
        else if (r.right < getWidth()) dx = getWidth() - r.right;
        if (r.height() <= getHeight()) dy = (getHeight() - r.height()) / 2f - r.top;
        else if (r.top > 0) dy = -r.top;
        else if (r.bottom < getHeight()) dy = getHeight() - r.bottom;
        matrix.postTranslate(dx, dy);
        setImageMatrix(matrix);
    }
}
