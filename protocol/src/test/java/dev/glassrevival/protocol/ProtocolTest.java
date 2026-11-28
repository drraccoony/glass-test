package dev.glassrevival.protocol;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ProtocolTest {

    @Test
    public void acceptsPackageStyleChannels() {
        assertTrue(Protocol.isValidChannel("dev.glassrevival.nav"));
        assertTrue(Protocol.isValidChannel("a"));
        assertTrue(Protocol.isValidChannel("my-app_v2.events"));
    }

    @Test
    public void rejectsBadChannels() {
        assertFalse(Protocol.isValidChannel(null));
        assertFalse(Protocol.isValidChannel(""));
        assertFalse(Protocol.isValidChannel("has space"));
        assertFalse(Protocol.isValidChannel("slash/inside"));
        assertFalse(Protocol.isValidChannel("café"));
        StringBuilder tooLong = new StringBuilder();
        for (int i = 0; i <= Protocol.MAX_CHANNEL_LENGTH; i++) tooLong.append('x');
        assertFalse(Protocol.isValidChannel(tooLong.toString()));
    }
}
