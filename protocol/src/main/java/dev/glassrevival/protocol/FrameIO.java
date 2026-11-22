package dev.glassrevival.protocol;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;

/** Length-prefixed frame reader and writer. Callers synchronize writes themselves. */
public final class FrameIO {
    private FrameIO() {}

    static final Charset UTF8 = Charset.forName("UTF-8");

    public static void write(OutputStream out, String payload) throws IOException {
        byte[] body = payload.getBytes(UTF8);
        if (body.length > Protocol.MAX_FRAME_BYTES) {
            throw new FrameTooLargeException(body.length);
        }
        byte[] frame = new byte[4 + body.length];
        frame[0] = (byte) (body.length >>> 24);
        frame[1] = (byte) (body.length >>> 16);
        frame[2] = (byte) (body.length >>> 8);
        frame[3] = (byte) body.length;
        System.arraycopy(body, 0, frame, 4, body.length);
        // One write call per frame: Bluetooth sockets send each write as its own packet burst.
        out.write(frame);
        out.flush();
    }

    /**
     * Reads one frame.
     *
     * @return the payload, or {@code null} if the stream ended cleanly before a new frame started
     * @throws EOFException if the stream ended partway through a frame
     */
    public static String read(InputStream in) throws IOException {
        int b0 = in.read();
        if (b0 < 0) return null;
        DataInputStream data = new DataInputStream(in);
        int length = (b0 << 24) | (data.readUnsignedByte() << 16)
                | (data.readUnsignedByte() << 8) | data.readUnsignedByte();
        if (length < 0 || length > Protocol.MAX_FRAME_BYTES) {
            throw new FrameTooLargeException(length);
        }
        byte[] body = new byte[length];
        data.readFully(body);
        return new String(body, UTF8);
    }

    public static final class FrameTooLargeException extends IOException {
        public FrameTooLargeException(int length) {
            super("Frame of " + length + " bytes exceeds limit of " + Protocol.MAX_FRAME_BYTES);
        }
    }
}
