package net.elfradio.h13dmrtx;

import java.security.MessageDigest;
import java.util.Locale;

final class Bytes {
    private Bytes() {}

    static String hex(byte[] value) {
        StringBuilder out = new StringBuilder(value.length * 3);
        for (int i = 0; i < value.length; i++) {
            if (i != 0) {
                out.append(' ');
            }
            out.append(String.format(Locale.US, "%02x", value[i] & 0xff));
        }
        return out.toString();
    }

    static String sha256(byte[] value) {
        try {
            return sha256Digest(MessageDigest.getInstance("SHA-256")
                    .digest(value));
        } catch (Exception error) {
            throw new IllegalStateException("SHA-256不可用", error);
        }
    }

    static String sha256Digest(byte[] digest) {
        if (digest == null) {
            throw new IllegalArgumentException("摘要为空");
        }
        StringBuilder out = new StringBuilder(digest.length * 2);
        for (byte item : digest) {
            out.append(String.format(Locale.US, "%02X", item & 0xff));
        }
        return out.toString();
    }

    static byte[] concat(byte[] left, byte[] right) {
        if (left == null || left.length == 0) {
            return right == null ? new byte[0] : right.clone();
        }
        if (right == null || right.length == 0) {
            return left.clone();
        }
        byte[] result = new byte[left.length + right.length];
        System.arraycopy(left, 0, result, 0, left.length);
        System.arraycopy(right, 0, result, left.length, right.length);
        return result;
    }

    static int u32le(byte[] value, int offset) {
        if (value == null || offset < 0 || value.length - offset < 4) {
            throw new IllegalArgumentException("小端32位读取越界");
        }
        return (value[offset] & 0xff)
                | ((value[offset + 1] & 0xff) << 8)
                | ((value[offset + 2] & 0xff) << 16)
                | ((value[offset + 3] & 0xff) << 24);
    }
}
