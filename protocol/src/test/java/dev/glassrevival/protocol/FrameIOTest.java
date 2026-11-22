package dev.glassrevival.protocol;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.util.Arrays;
import org.junit.Test;

public class FrameIOTest {

    @Test
    public void roundTripsSeveralFrames() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        FrameIO.write(out, "{\"type\":\"ping\"}");
        FrameIO.write(out, "");
        FrameIO.write(out, "héllo 👓 世界");

        ByteArrayInputStream in = new ByteArrayInputStream(out.toByteArray());
        assertEquals("{\"type\":\"ping\"}", FrameIO.read(in));
        assertEquals("", FrameIO.read(in));
        assertEquals("héllo 👓 世界", FrameIO.read(in));
        assertNull(FrameIO.read(in));
    }

    @Test
    public void lengthPrefixIsBigEndian() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        FrameIO.write(out, "ab");
        assertTrue(Arrays.equals(new byte[] {0, 0, 0, 2, 'a', 'b'}, out.toByteArray()));
    }

    @Test(expected = EOFException.class)
    public void truncatedFrameThrows() throws IOException {
        FrameIO.read(new ByteArrayInputStream(new byte[] {0, 0, 0, 5, 'a', 'b'}));
    }

    @Test(expected = EOFException.class)
    public void truncatedHeaderThrows() throws IOException {
        FrameIO.read(new ByteArrayInputStream(new byte[] {0, 0}));
    }

    @Test
    public void rejectsOversizedIncomingFrame() throws IOException {
        int len = Protocol.MAX_FRAME_BYTES + 1;
        byte[] header = {(byte) (len >>> 24), (byte) (len >>> 16), (byte) (len >>> 8), (byte) len};
        try {
            FrameIO.read(new ByteArrayInputStream(header));
            fail("expected FrameTooLargeException");
        } catch (FrameIO.FrameTooLargeException expected) {
            // ok
        }
    }

    @Test
    public void rejectsNegativeLength() throws IOException {
        try {
            FrameIO.read(new ByteArrayInputStream(new byte[] {(byte) 0xff, 0, 0, 1}));
            fail("expected FrameTooLargeException");
        } catch (FrameIO.FrameTooLargeException expected) {
            // ok
        }
    }

    @Test
    public void refusesToWriteOversizedFrame() throws IOException {
        char[] big = new char[Protocol.MAX_FRAME_BYTES + 1];
        Arrays.fill(big, 'x');
        try {
            FrameIO.write(new ByteArrayOutputStream(), new String(big));
            fail("expected FrameTooLargeException");
        } catch (FrameIO.FrameTooLargeException expected) {
            // ok
        }
    }

    @Test
    public void worksAcrossAStreamingPipe() throws Exception {
        final PipedOutputStream pout = new PipedOutputStream();
        PipedInputStream pin = new PipedInputStream(pout, 16); // tiny buffer forces partial reads
        Thread writer = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    for (int i = 0; i < 50; i++) FrameIO.write(pout, "message number " + i);
                    pout.close();
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
        });
        writer.start();
        for (int i = 0; i < 50; i++) assertEquals("message number " + i, FrameIO.read(pin));
        assertNull(FrameIO.read(pin));
        writer.join();
    }

    @Test
    public void pairCodeValidation() {
        assertTrue(Protocol.isValidPairCode("012345"));
        assertFalse(Protocol.isValidPairCode("12345"));
        assertFalse(Protocol.isValidPairCode("1234567"));
        assertFalse(Protocol.isValidPairCode("12a456"));
        assertFalse(Protocol.isValidPairCode(null));
    }

    @Test
    public void pairCodeMatching() {
        assertTrue(Protocol.pairCodesMatch("123456", "123456"));
        assertFalse(Protocol.pairCodesMatch("123456", "123457"));
        assertFalse(Protocol.pairCodesMatch("123456", "12345"));
        assertFalse(Protocol.pairCodesMatch("123456", null));
    }
}
