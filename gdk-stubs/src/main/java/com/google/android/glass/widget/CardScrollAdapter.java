package com.google.android.glass.widget;

import android.widget.BaseAdapter;

/** Stub of the GDK card scroll adapter. */
public abstract class CardScrollAdapter extends BaseAdapter {
    public abstract int getPosition(Object item);

    public int getHomePosition() {
        throw new RuntimeException("stub");
    }
}
