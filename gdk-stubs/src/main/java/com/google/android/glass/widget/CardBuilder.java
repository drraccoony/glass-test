package com.google.android.glass.widget;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;

/** Stub of the GDK card builder. The real class is provided by Glass at runtime. */
public class CardBuilder {

    public enum Layout {
        ALERT, AUTHOR, CAPTION, COLUMNS, COLUMNS_FIXED, EMBED_BEHIND, EMBED_INSIDE, MENU, TEXT,
        TEXT_FIXED, TITLE
    }

    public CardBuilder(Context context, Layout layout) {
        throw new RuntimeException("stub");
    }

    public static int getViewTypeCount() {
        throw new RuntimeException("stub");
    }

    public int getItemViewType() {
        throw new RuntimeException("stub");
    }

    public View getView() {
        throw new RuntimeException("stub");
    }

    public View getView(View convertView, ViewGroup parent) {
        throw new RuntimeException("stub");
    }

    public CardBuilder setText(CharSequence text) {
        throw new RuntimeException("stub");
    }

    public CardBuilder setHeading(CharSequence heading) {
        throw new RuntimeException("stub");
    }

    public CardBuilder setSubheading(CharSequence subheading) {
        throw new RuntimeException("stub");
    }

    public CardBuilder setFootnote(CharSequence footnote) {
        throw new RuntimeException("stub");
    }

    public CardBuilder setTimestamp(CharSequence timestamp) {
        throw new RuntimeException("stub");
    }

    public CardBuilder setIcon(int resId) {
        throw new RuntimeException("stub");
    }

    public CardBuilder setIcon(Bitmap bitmap) {
        throw new RuntimeException("stub");
    }

    public CardBuilder setIcon(Drawable drawable) {
        throw new RuntimeException("stub");
    }

    public CardBuilder setAttributionIcon(int resId) {
        throw new RuntimeException("stub");
    }

    public CardBuilder setAttributionIcon(Bitmap bitmap) {
        throw new RuntimeException("stub");
    }

    public CardBuilder addImage(int resId) {
        throw new RuntimeException("stub");
    }

    public CardBuilder addImage(Bitmap bitmap) {
        throw new RuntimeException("stub");
    }

    public CardBuilder showStackIndicator(boolean show) {
        throw new RuntimeException("stub");
    }
}
