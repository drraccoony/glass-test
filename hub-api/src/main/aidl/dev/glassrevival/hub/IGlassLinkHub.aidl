package dev.glassrevival.hub;

import dev.glassrevival.hub.IGlassLinkListener;

/**
 * The hub service hosted by GlassLink on Glass and by the companion on the phone.
 * Messages are JSON objects. The hub tags each one with its channel and sends it
 * over Bluetooth to the same channel on the other device.
 */
interface IGlassLinkHub {
    /** See Hub.API_VERSION. */
    int apiVersion();

    /** True while Glass and the phone are linked. */
    boolean isConnected();

    /** Start receiving messages for a channel. The listener is told the current link state right away. */
    void register(String channel, IGlassLinkListener listener);

    void unregister(String channel, IGlassLinkListener listener);

    /**
     * Send a JSON object to the other device's app on this channel.
     * Returns false if the devices aren't linked, or the message isn't valid JSON or is too large.
     */
    boolean send(String channel, String json);
}
