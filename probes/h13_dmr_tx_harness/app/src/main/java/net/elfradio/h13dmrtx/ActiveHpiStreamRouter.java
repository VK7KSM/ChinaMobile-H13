package net.elfradio.h13dmrtx;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

/** 持续HPI流中的完整帧路由器，跨串口读取保留半帧。 */
final class ActiveHpiStreamRouter {
    interface FrameMatcher {
        boolean matches(byte[] frame);
    }

    private byte[] carry = new byte[0];

    Read drainToFrameBoundary(InputStream input, long timeoutMs)
            throws Exception {
        requireArguments(input, timeoutMs);
        ByteArrayOutputStream observed = new ByteArrayOutputStream();
        long deadline = nowMs() + timeoutMs;
        while (true) {
            int frameLength = firstCompleteFrameLength(carry);
            if (frameLength > 0) {
                observed.write(carry, 0, frameLength);
                carry = Arrays.copyOfRange(carry, frameLength, carry.length);
                continue;
            }
            if (carry.length == 0 && input.available() <= 0) {
                return result(observed, null);
            }
            if (nowMs() >= deadline) {
                throw new IOException("活动HPI流未在期限内到达完整帧边界，残片字节="
                        + carry.length);
            }
            readAvailableIntoCarry(input);
            Thread.sleep(2);
        }
    }

    Read readUntil(InputStream input, long timeoutMs, FrameMatcher matcher)
            throws Exception {
        requireArguments(input, timeoutMs);
        if (matcher == null) {
            throw new IllegalArgumentException("活动HPI匹配器为空");
        }
        ByteArrayOutputStream observed = new ByteArrayOutputStream();
        long deadline = nowMs() + timeoutMs;
        while (nowMs() < deadline) {
            int frameLength = firstCompleteFrameLength(carry);
            if (frameLength > 0) {
                byte[] frame = Arrays.copyOfRange(carry, 0, frameLength);
                carry = Arrays.copyOfRange(carry, frameLength, carry.length);
                observed.write(frame, 0, frame.length);
                if (matcher.matches(frame)) {
                    return result(observed, frame);
                }
                continue;
            }
            readAvailableIntoCarry(input);
            Thread.sleep(2);
        }
        return result(observed, null);
    }

    private Read result(ByteArrayOutputStream observed, byte[] matched) {
        return new Read(observed.toByteArray(), matched, carry);
    }

    private void readAvailableIntoCarry(InputStream input) throws IOException {
        int available = input.available();
        if (available <= 0) {
            return;
        }
        byte[] chunk = new byte[Math.min(available, 4096)];
        int count = input.read(chunk);
        if (count > 0) {
            byte[] next = Arrays.copyOf(carry, carry.length + count);
            System.arraycopy(chunk, 0, next, carry.length, count);
            carry = next;
            if (carry.length > 8192) {
                throw new IOException("活动HPI路由缓冲超过8192字节");
            }
        }
    }

    static int firstCompleteFrameLength(byte[] raw) throws IOException {
        if (raw == null || raw.length == 0 || raw.length < 6) {
            return 0;
        }
        if ((raw[0] & 0xff) != HpiCodec.SYNC0
                || (raw[1] & 0xff) != HpiCodec.SYNC1
                || (raw[2] & 0xff) != HpiCodec.COMMAND) {
            throw new IOException("活动HPI流失去帧同步");
        }
        int payloadLength = ((raw[3] & 0xff) << 8) | (raw[4] & 0xff);
        int declared = 6 + payloadLength;
        int wire = declared + (declared & 1);
        if (wire > 4096) {
            throw new IOException("活动HPI帧声明长度异常：" + wire);
        }
        if (raw.length < wire) {
            return 0;
        }
        if (wire != declared && raw[declared] != 0) {
            throw new IOException("活动HPI帧补齐字节非零");
        }
        return wire;
    }

    private static void requireArguments(InputStream input, long timeoutMs) {
        if (input == null || timeoutMs <= 0) {
            throw new IllegalArgumentException("活动HPI边界参数无效");
        }
    }

    private static long nowMs() {
        return System.nanoTime() / 1_000_000L;
    }

    static final class Read {
        final byte[] observed;
        final byte[] matchedFrame;
        final byte[] carry;

        Read(byte[] observed, byte[] matchedFrame, byte[] carry) {
            this.observed = observed.clone();
            this.matchedFrame = matchedFrame == null
                    ? null : matchedFrame.clone();
            this.carry = carry.clone();
        }
    }
}
