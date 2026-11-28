package dev.glassrevival.hub;

/**
 * Names shared by the hub hosts and the apps that use them.
 *
 * <p>An add-on app talks to the other device through a channel, a name such as
 * {@code "dev.glassrevival.nav"}. A Glass app and a phone app that use the same
 * channel can exchange JSON messages. GlassLink owns the Bluetooth link on both ends
 * and carries the messages.
 *
 * <ul>
 *   <li>While running, use {@link HubClient} to send and receive messages.
 *   <li>To be woken when a message arrives and nothing is bound, declare a
 *       {@link HubReceiver} that lists its channels in {@link #META_CHANNELS}.
 * </ul>
 */
public final class Hub {
    private Hub() {}

    /** Version of the hub interface. Bumped when methods are added. */
    public static final int API_VERSION = 1;

    /** Signature permission that protects the hub service and the receivers. */
    public static final String PERMISSION = "dev.glassrevival.permission.HUB";

    /** Service action the hub answers to. */
    public static final String ACTION_BIND = "dev.glassrevival.hub.action.BIND";

    /** Broadcast to a {@link HubReceiver} when a message arrives for one of its channels. */
    public static final String ACTION_MESSAGE = "dev.glassrevival.hub.action.MESSAGE";

    /** Broadcast to a {@link HubReceiver} when the link to the other device comes up or goes down. */
    public static final String ACTION_CONNECTION = "dev.glassrevival.hub.action.CONNECTION";

    /**
     * Receiver {@code <meta-data>} naming the channels it handles, separated by commas
     * or spaces.
     */
    public static final String META_CHANNELS = "dev.glassrevival.hub.CHANNELS";

    public static final String EXTRA_CHANNEL = "dev.glassrevival.hub.extra.CHANNEL";
    /** The message as a JSON string. */
    public static final String EXTRA_MESSAGE = "dev.glassrevival.hub.extra.MESSAGE";
    public static final String EXTRA_CONNECTED = "dev.glassrevival.hub.extra.CONNECTED";
}
