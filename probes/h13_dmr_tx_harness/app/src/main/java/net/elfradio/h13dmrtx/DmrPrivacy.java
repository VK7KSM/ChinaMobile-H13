package net.elfradio.h13dmrtx;

import java.util.Arrays;

final class DmrPrivacy {
    private final byte[] key5;
    private byte[] currentMi;
    private byte[] nextMi;
    private byte[] fragments;
    private Rc4 rc4;
    private int globalFrame;
    private int frameInSuperframe;

    static DmrPrivacy fromRuntime14(byte[] runtime14) {
        if (runtime14 == null || runtime14.length != 14) {
            throw new IllegalArgumentException("privacy需要14字节运行快照");
        }
        return new DmrPrivacy(Arrays.copyOfRange(runtime14, 5, 10),
                Arrays.copyOfRange(runtime14, 10, 14));
    }

    DmrPrivacy(byte[] key5, byte[] initialMi) {
        if (key5 == null || key5.length != 5
                || initialMi == null || initialMi.length != 4) {
            throw new IllegalArgumentException("privacy参数长度错误");
        }
        this.key5 = key5.clone();
        currentMi = initialMi.clone();
        resetSuperframe();
    }

    byte[] encrypt(byte[] plain) {
        if (plain == null || plain.length == 0 || plain.length % 9 != 0) {
            throw new IllegalArgumentException("privacy输入必须为9字节帧序列");
        }
        byte[] result = new byte[plain.length];
        for (int offset = 0; offset < plain.length; offset += 9) {
            byte[] frame = rc4.apply49Bits(Arrays.copyOfRange(plain,
                    offset, offset + 9));
            setC3(frame, fragments[frameInSuperframe] & 0x0f);
            System.arraycopy(frame, 0, result, offset, 9);
            globalFrame++;
            frameInSuperframe++;
            if (frameInSuperframe == 18) {
                currentMi = nextMi;
                resetSuperframe();
            }
        }
        return result;
    }

    byte[] encrypt49BitParameters(byte[] plain49) {
        return transform49BitParameters(plain49);
    }

    byte[] decrypt49BitParameters(byte[] encrypted49) {
        return transform49BitParameters(encrypted49);
    }

    private byte[] transform49BitParameters(byte[] input) {
        requirePacked49(input);
        byte[] result = new byte[input.length];
        for (int offset = 0; offset < input.length; offset += 9) {
            byte[] frame = rc4.apply49Bits(Arrays.copyOfRange(input,
                    offset, offset + 9));
            System.arraycopy(frame, 0, result, offset, 9);
            globalFrame++;
            frameInSuperframe++;
            if (frameInSuperframe == 18) {
                currentMi = nextMi;
                resetSuperframe();
            }
        }
        return result;
    }

    byte[] applyLateEntryC3ToChannelFrames(byte[] channelFrames) {
        if (channelFrames == null || channelFrames.length == 0
                || channelFrames.length % 9 != 0) {
            throw new IllegalArgumentException("C3输入必须为9字节信道帧序列");
        }
        byte[] result = channelFrames.clone();
        for (int offset = 0; offset < result.length; offset += 9) {
            byte[] frame = Arrays.copyOfRange(result, offset, offset + 9);
            setC3(frame, fragments[frameInSuperframe] & 0x0f);
            System.arraycopy(frame, 0, result, offset, 9);
            globalFrame++;
            frameInSuperframe++;
            if (frameInSuperframe == 18) {
                currentMi = nextMi;
                resetSuperframe();
            }
        }
        return result;
    }

    private static void requirePacked49(byte[] input) {
        if (input == null || input.length == 0 || input.length % 9 != 0) {
            throw new IllegalArgumentException("49位privacy输入必须为9字节帧序列");
        }
        for (int offset = 0; offset < input.length; offset += 9) {
            if ((input[offset + 6] & 0x7f) != 0
                    || input[offset + 7] != 0 || input[offset + 8] != 0) {
                throw new IllegalArgumentException("49位privacy输入填充位必须为零");
            }
        }
    }

    int globalFrame() {
        return globalFrame;
    }

    int frameInSuperframe() {
        return frameInSuperframe;
    }

    private void resetSuperframe() {
        nextMi = evolveMi(currentMi);
        fragments = lateEntryFragments(nextMi);
        rc4 = new Rc4(key5, currentMi);
        frameInSuperframe = 0;
    }

    static byte[] evolveMi(byte[] mi) {
        if (mi == null || mi.length != 4) {
            throw new IllegalArgumentException("MI必须为4字节");
        }
        int value = ((mi[0] & 0xff) << 24) | ((mi[1] & 0xff) << 16)
                | ((mi[2] & 0xff) << 8) | (mi[3] & 0xff);
        for (int round = 0; round < 32; round++) {
            int feedback = ((value >>> 31) ^ (value >>> 3)
                    ^ (value >>> 1)) & 1;
            value = (value << 1) | feedback;
        }
        return new byte[] {(byte) (value >>> 24), (byte) (value >>> 16),
                (byte) (value >>> 8), (byte) value};
    }

    static byte[] lateEntryFragments(byte[] mi) {
        boolean[] data = new boolean[36];
        for (int bit = 0; bit < 32; bit++) {
            data[bit] = ((mi[bit / 8] >>> (7 - bit % 8)) & 1) != 0;
        }
        int crc = crc4(mi);
        for (int bit = 0; bit < 4; bit++) {
            data[32 + bit] = ((crc >>> (3 - bit)) & 1) != 0;
        }
        int[] rows = {0xc75, 0x63b, 0xf68, 0x7b4, 0x3da, 0xd99,
                0x6cd, 0x367, 0xdc6, 0xa97, 0x93e, 0x8eb};
        boolean[] code = new boolean[72];
        for (int block = 0; block < 3; block++) {
            int parity = 0;
            for (int bit = 0; bit < 12; bit++) {
                boolean value = data[block * 12 + bit];
                code[block * 24 + bit] = value;
                if (value) {
                    parity ^= rows[bit];
                }
            }
            for (int bit = 0; bit < 12; bit++) {
                code[block * 24 + 12 + bit] =
                        ((parity >>> (11 - bit)) & 1) != 0;
            }
        }
        byte[] result = new byte[18];
        for (int fragment = 0; fragment < 18; fragment++) {
            int value = 0;
            for (int bit = 0; bit < 4; bit++) {
                int source = 24 * (fragment % 3)
                        + 4 * (fragment / 3) + bit;
                value = (value << 1) | (code[source] ? 1 : 0);
            }
            result[fragment] = (byte) value;
        }
        return result;
    }

    private static int crc4(byte[] mi) {
        int[] work = new int[36];
        for (int bit = 0; bit < 32; bit++) {
            work[bit] = (mi[bit / 8] >>> (7 - bit % 8)) & 1;
        }
        for (int bit = 0; bit < 32; bit++) {
            if (work[bit] != 0) {
                work[bit] ^= 1;
                work[bit + 3] ^= 1;
                work[bit + 4] ^= 1;
            }
        }
        int crc = 0;
        for (int bit = 32; bit < 36; bit++) {
            crc = (crc << 1) | work[bit];
        }
        return crc ^ 0x0f;
    }

    private static void setC3(byte[] frame, int nibble) {
        int[] positions = {21, 71, 69, 67};
        for (int bit = 0; bit < 4; bit++) {
            int position = positions[bit];
            int index = position / 8;
            int mask = 1 << (7 - position % 8);
            if (((nibble >>> (3 - bit)) & 1) != 0) {
                frame[index] |= (byte) mask;
            } else {
                frame[index] &= (byte) ~mask;
            }
        }
    }

    private static final class Rc4 {
        private final int[] box = new int[256];
        private int i;
        private int j;

        Rc4(byte[] key5, byte[] mi) {
            byte[] key = new byte[9];
            System.arraycopy(key5, 0, key, 0, 5);
            System.arraycopy(mi, 0, key, 5, 4);
            for (int n = 0; n < box.length; n++) {
                box[n] = n;
            }
            int keyJ = 0;
            for (int n = 0; n < box.length; n++) {
                keyJ = (keyJ + box[n] + (key[n % key.length] & 0xff)) & 0xff;
                swap(n, keyJ);
            }
            for (int discard = 0; discard < 256; discard++) {
                next();
            }
        }

        byte[] apply49Bits(byte[] plain) {
            byte[] result = plain.clone();
            for (int n = 0; n < 7; n++) {
                int stream = next();
                result[n] ^= (byte) (n < 6 ? stream : stream & 0x80);
            }
            return result;
        }

        private int next() {
            i = (i + 1) & 0xff;
            j = (j + box[i]) & 0xff;
            swap(i, j);
            return box[(box[i] + box[j]) & 0xff];
        }

        private void swap(int left, int right) {
            int value = box[left];
            box[left] = box[right];
            box[right] = value;
        }
    }
}
