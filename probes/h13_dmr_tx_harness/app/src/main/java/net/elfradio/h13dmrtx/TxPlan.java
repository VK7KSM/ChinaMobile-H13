package net.elfradio.h13dmrtx;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

final class TxPlan {
    static final int PCM_SAMPLES_PER_FRAME = 160;
    static final int AMBE_BYTES_PER_FRAME = 9;
    static final int AMBE_FRAMES_PER_UNIT = 4;
    static final int EXPECTED_FRAME_COUNT = 104;
    static final int EXPECTED_UNIT_COUNT = 26;
    static final long UNIT_INTERVAL_MS = 80;
    static final long MAX_LATE_MS = 40;
    static final String GOLDEN_PLAIN_SHA256 =
            "D4217935BF2F046A9DDEC9EC6AF358A4788AA82A466AFFE5DD76D118BD1BF5D2";

    private final byte[] plainAmbe;
    private final byte[] channelAmbe;
    private final List<byte[]> wireUnits;

    static short[] decodePcmS16Le(byte[] raw) {
        if (raw == null || raw.length == 0 || (raw.length & 1) != 0) {
            throw new IllegalArgumentException("PCM必须为非空s16le字节序列");
        }
        short[] result = new short[raw.length / 2];
        for (int index = 0; index < result.length; index++) {
            result[index] = (short) ((raw[index * 2] & 0xff)
                    | (raw[index * 2 + 1] << 8));
        }
        return result;
    }

    static TxPlan create(byte[] plainAmbe) {
        if (plainAmbe == null
                || plainAmbe.length != EXPECTED_FRAME_COUNT * AMBE_BYTES_PER_FRAME
                || !GOLDEN_PLAIN_SHA256.equals(Bytes.sha256(plainAmbe))) {
            throw new IllegalArgumentException("AMBE明文不匹配冻结摩尔斯向量");
        }
        // 编码器输出已经是完成Golay、白化和DMR交织的72位码字。
        // 明文频道必须原样下送，禁止在最终9字节上再次修改49个物理位。
        byte[] channel = plainAmbe.clone();
        List<byte[]> units = new ArrayList<>(EXPECTED_UNIT_COUNT);
        for (int unit = 0; unit < EXPECTED_UNIT_COUNT; unit++) {
            int offset = unit * DmrProtocol.DATA36_BYTES;
            units.add(DmrProtocol.data36(Arrays.copyOfRange(channel,
                    offset, offset + DmrProtocol.DATA36_BYTES)));
        }
        return new TxPlan(plainAmbe, channel, units);
    }

    private TxPlan(byte[] plainAmbe, byte[] channelAmbe,
            List<byte[]> wireUnits) {
        this.plainAmbe = plainAmbe.clone();
        this.channelAmbe = channelAmbe.clone();
        List<byte[]> copy = new ArrayList<>(wireUnits.size());
        for (byte[] unit : wireUnits) {
            copy.add(unit.clone());
        }
        this.wireUnits = Collections.unmodifiableList(copy);
    }

    byte[] plainAmbe() {
        return plainAmbe.clone();
    }

    byte[] channelAmbe() {
        return channelAmbe.clone();
    }

    List<byte[]> wireUnits() {
        List<byte[]> copy = new ArrayList<>(wireUnits.size());
        for (byte[] unit : wireUnits) {
            copy.add(unit.clone());
        }
        return copy;
    }
}
