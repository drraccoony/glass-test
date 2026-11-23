package com.google.android.glass.widget;

import android.content.Context;
import android.view.View;
import android.widget.AdapterView;

/** Stub of the GDK timeline-style card scroller. */
public class CardScrollView extends AdapterView<CardScrollAdapter> {

    public enum Animation { DELETION, INSERTION, NAVIGATION, USER_INPUT }

    public CardScrollView(Context context) {
        super(context);
        throw new RuntimeException("stub");
    }

    public void activate() {
        throw new RuntimeException("stub");
    }

    public void deactivate() {
        throw new RuntimeException("stub");
    }

    public boolean animate(int position, Animation animation) {
        throw new RuntimeException("stub");
    }

    public void setHorizontalScrollBarEnabled(boolean enabled) {
        throw new RuntimeException("stub");
    }

    @Override
    public CardScrollAdapter getAdapter() {
        throw new RuntimeException("stub");
    }

    @Override
    public void setAdapter(CardScrollAdapter adapter) {
        throw new RuntimeException("stub");
    }

    @Override
    public View getSelectedView() {
        throw new RuntimeException("stub");
    }

    @Override
    public void setSelection(int position) {
        throw new RuntimeException("stub");
    }
}
