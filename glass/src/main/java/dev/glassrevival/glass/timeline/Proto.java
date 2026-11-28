package dev.glassrevival.glass.timeline;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;

/**
 * Minimal protocol buffer writer, enough to build the timeline cards Glass's own
 * timeline stores (TimelineNano.TimelineItem in GlassSync and GlassHome).
 */
final class Proto {
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();

    Proto string(int field, String value) {
        if (value == null) return this;
        return bytes(field, value.getBytes(UTF8));
    }

    Proto bytes(int field, byte[] value) {
        tag(field, 2);
        varint(value.length);
        out.write(value, 0, value.length);
        return this;
    }

    Proto message(int field, Proto value) {
        return bytes(field, value.toByteArray());
    }

    Proto uint64(int field, long value) {
        tag(field, 0);
        varint(value);
        return this;
    }

    Proto int32(int field, int value) {
        tag(field, 0);
        varint(value); // negative int32 values sign-extend to 10 bytes, as protobuf does
        return this;
    }

    Proto bool(int field, boolean value) {
        tag(field, 0);
        out.write(value ? 1 : 0);
        return this;
    }

    byte[] toByteArray() {
        return out.toByteArray();
    }

    private void tag(int field, int wireType) {
        varint(((long) field << 3) | wireType);
    }

    private void varint(long v) {
        while ((v & ~0x7FL) != 0) {
            out.write((int) ((v & 0x7F) | 0x80));
            v >>>= 7;
        }
        out.write((int) v);
    }
}
