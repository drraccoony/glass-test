package com.google.glass.timeline;

import java.io.Serializable;

/**
 * Wire-compatible copy of GlassHome's own TimelineItemId, so GlassLink can send the
 * "notify timeline item" broadcast that makes Glass chime and show a card. The home
 * screen deserializes this with its own class, so the name, the fields and the
 * serialVersionUID must match XE24's. The ID was read from GlassHome.apk on a device.
 */
public class TimelineItemId implements Serializable {
    private static final long serialVersionUID = 9213358818717748552L;

    private final String bundleId;
    private final long displayTime;
    private final boolean isBundleCover;
    private final boolean isPinned;
    private final String itemId;

    public TimelineItemId(String itemId, boolean isPinned, long displayTime, String bundleId,
            boolean isBundleCover) {
        this.itemId = itemId;
        this.isPinned = isPinned;
        this.displayTime = displayTime;
        this.bundleId = bundleId;
        this.isBundleCover = isBundleCover;
    }
}
