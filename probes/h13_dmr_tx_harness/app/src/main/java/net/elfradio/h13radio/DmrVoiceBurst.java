package net.elfradio.h13radio;

/**
 * DMR 语音突发与三帧 AMBE 之间的拆装。
 *
 * <p>这是**网络与射频之间缺的那一环**：网络上 `DMRD` 携带 33 字节的一个
 * 语音突发，而模块要的是三帧一组、每帧 9 字节的单元（2.8.83）。两者不是
 * 同一种排列，必须转换。
 *
 * <p>突发的 264 位按 DMR 的常见布局分三段：
 *
 * <pre>
 *   位 0   … 107   前半净荷（108 位）
 *   位 108 … 155   同步字或嵌入信令（48 位）
 *   位 156 … 263   后半净荷（108 位）
 * </pre>
 *
 * <p>三帧各 72 位，第二帧**跨在中间那 48 位的两侧**：
 *
 * <pre>
 *   帧 0：位 0   … 71
 *   帧 1：位 72  … 107（36 位）＋ 位 156 … 191（36 位）
 *   帧 2：位 192 … 263
 * </pre>
 *
 * <p><b>证据层级要说清楚</b>：这个布局取自公开资料与已有实现的common
 * 形态，本仓库**没有**一段既有 33 字节 `DMRD` 又有对应 27 字节单元的
 * 配对样本可以逐字节核对。因此这里的用例证明的是**拆装互逆、位段算术
 * 正确**，不是"与真实网络流兼容"。拿到配对样本之前，不得把它当作已验证。
 *
 * <p>中间 48 位在拆的时候被原样保留、装回去时原样写回——它承载同步字与
 * 嵌入信令，**不属于语音**，不该被当成语音送给模块，也不该被丢掉。
 */
public final class DmrVoiceBurst {

    public static final int BURST_BYTES = 33;
    public static final int AMBE_FRAME_BYTES = 9;
    public static final int FRAMES_PER_BURST = 3;
    /** 三帧 AMBE 拼成的单元长度，与模块要的一致。 */
    public static final int UNIT_BYTES = AMBE_FRAME_BYTES * FRAMES_PER_BURST;

    private static final int FIRST_HALF_BITS = 108;
    private static final int MIDDLE_BITS = 48;
    private static final int FRAME_BITS = 72;
    private static final int MIDDLE_START = FIRST_HALF_BITS;
    private static final int SECOND_HALF_START = FIRST_HALF_BITS + MIDDLE_BITS;

    private DmrVoiceBurst() {}

    private static int bit(byte[] data, int index) {
        return (data[index >> 3] >> (7 - (index & 7))) & 1;
    }

    private static void setBit(byte[] data, int index, int value) {
        if (value != 0) {
            data[index >> 3] |= (byte) (1 << (7 - (index & 7)));
        } else {
            data[index >> 3] &= (byte) ~(1 << (7 - (index & 7)));
        }
    }

    /** 第 frame 帧的第 offset 位，在整个突发里的位置。 */
    private static int burstBitFor(int frame, int offset) {
        int linear = frame * FRAME_BITS + offset;
        return linear < FIRST_HALF_BITS
                ? linear
                : linear + MIDDLE_BITS;
    }

    /**
     * 拆出三帧 AMBE，拼成 27 字节单元。中间 48 位不在其中。
     *
     * @return 27 字节；入参长度不对则抛异常，不截断也不补齐
     */
    public static byte[] toUnit(byte[] burst) {
        requireBurst(burst);
        byte[] unit = new byte[UNIT_BYTES];
        for (int frame = 0; frame < FRAMES_PER_BURST; frame++) {
            for (int offset = 0; offset < FRAME_BITS; offset++) {
                setBit(unit, frame * FRAME_BITS + offset,
                        bit(burst, burstBitFor(frame, offset)));
            }
        }
        return unit;
    }

    /** 取出中间 48 位（同步字或嵌入信令），6 字节。 */
    public static byte[] middle(byte[] burst) {
        requireBurst(burst);
        byte[] out = new byte[MIDDLE_BITS / 8];
        for (int i = 0; i < MIDDLE_BITS; i++) {
            setBit(out, i, bit(burst, MIDDLE_START + i));
        }
        return out;
    }

    /**
     * 用三帧 AMBE 与中间 48 位装回一个突发。
     *
     * @param unit   27 字节
     * @param middle 6 字节；传 null 则中间 48 位留零
     */
    public static byte[] toBurst(byte[] unit, byte[] middle) {
        if (unit == null || unit.length != UNIT_BYTES) {
            throw new IllegalArgumentException(
                    "单元须为 " + UNIT_BYTES + " 字节");
        }
        if (middle != null && middle.length != MIDDLE_BITS / 8) {
            throw new IllegalArgumentException(
                    "中间段须为 " + (MIDDLE_BITS / 8) + " 字节");
        }
        byte[] burst = new byte[BURST_BYTES];
        for (int frame = 0; frame < FRAMES_PER_BURST; frame++) {
            for (int offset = 0; offset < FRAME_BITS; offset++) {
                setBit(burst, burstBitFor(frame, offset),
                        bit(unit, frame * FRAME_BITS + offset));
            }
        }
        if (middle != null) {
            for (int i = 0; i < MIDDLE_BITS; i++) {
                setBit(burst, MIDDLE_START + i, bit(middle, i));
            }
        }
        return burst;
    }

    /** 第 index 帧的 9 字节，从 27 字节单元里取。 */
    public static byte[] frameOf(byte[] unit, int index) {
        if (unit == null || unit.length != UNIT_BYTES) {
            throw new IllegalArgumentException(
                    "单元须为 " + UNIT_BYTES + " 字节");
        }
        if (index < 0 || index >= FRAMES_PER_BURST) {
            throw new IndexOutOfBoundsException("帧序越界");
        }
        byte[] out = new byte[AMBE_FRAME_BYTES];
        System.arraycopy(unit, index * AMBE_FRAME_BYTES, out, 0,
                AMBE_FRAME_BYTES);
        return out;
    }

    private static void requireBurst(byte[] burst) {
        if (burst == null || burst.length != BURST_BYTES) {
            throw new IllegalArgumentException(
                    "语音突发须为 " + BURST_BYTES + " 字节");
        }
    }

    /** 中间 48 位是否落在第二帧的两段之间——布局假设的自检。 */
    public static boolean layoutIsConsistent() {
        // 帧 1 的第 35 位应当还在前半，第 36 位应当已经跳过中间段
        return burstBitFor(1, 35) == 107 && burstBitFor(1, 36) == 156
                && burstBitFor(0, 0) == 0 && burstBitFor(2, 71) == 263;
    }
}
