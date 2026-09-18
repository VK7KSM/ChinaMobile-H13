package net.elfradio.h13dmrtx;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

final class HpiCodec {
    static final int SYNC0 = 0x84;
    static final int SYNC1 = 0xa9;
    static final int COMMAND = 0x61;

    private HpiCodec() {}

    static byte[] frame(int packetType, byte[] payload) {
        if ((packetType & ~0xff) != 0 || payload == null
                || payload.length > 0xffff) {
            throw new IllegalArgumentException("HPI帧参数无效");
        }
        byte[] raw = new byte[6 + payload.length];
        raw[0] = (byte) SYNC0;
        raw[1] = (byte) SYNC1;
        raw[2] = COMMAND;
        raw[3] = (byte) (payload.length >>> 8);
        raw[4] = (byte) payload.length;
        raw[5] = (byte) packetType;
        System.arraycopy(payload, 0, raw, 6, payload.length);
        return raw;
    }

    static byte[] paddedFrame(int packetType, byte[] payload) {
        byte[] raw = frame(packetType, payload);
        return (raw.length & 1) == 0 ? raw : Arrays.copyOf(raw, raw.length + 1);
    }

    static List<WireFrame> parseComplete(byte[] raw) {
        if (raw == null) {
            return null;
        }
        List<WireFrame> result = new ArrayList<>();
        int offset = 0;
        while (offset < raw.length) {
            if (raw.length - offset < 6
                    || (raw[offset] & 0xff) != SYNC0
                    || (raw[offset + 1] & 0xff) != SYNC1
                    || (raw[offset + 2] & 0xff) != COMMAND) {
                return null;
            }
            int payloadLength = ((raw[offset + 3] & 0xff) << 8)
                    | (raw[offset + 4] & 0xff);
            int unpadded = 6 + payloadLength;
            if (raw.length - offset < unpadded) {
                return null;
            }
            int wireLength = unpadded + (unpadded & 1);
            if (raw.length - offset < wireLength) {
                return null;
            }
            if (wireLength != unpadded && raw[offset + unpadded] != 0) {
                return null;
            }
            result.add(new WireFrame(offset, wireLength,
                    raw[offset + 5] & 0xff,
                    Arrays.copyOfRange(raw, offset + 6,
                            offset + 6 + payloadLength)));
            offset += wireLength;
        }
        return result;
    }

    static byte[] join(List<byte[]> values) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] value : values) {
            out.write(value, 0, value.length);
        }
        return out.toByteArray();
    }

    static final class WireFrame {
        final int offset;
        final int wireLength;
        final int packetType;
        final byte[] payload;

        WireFrame(int offset, int wireLength, int packetType, byte[] payload) {
            this.offset = offset;
            this.wireLength = wireLength;
            this.packetType = packetType;
            this.payload = payload;
        }
    }
}
