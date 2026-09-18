package net.elfradio.h13dmrtx;

import java.util.Arrays;

final class SoftwareDmrPrivacyPipeline {
    private SoftwareDmrPrivacyPipeline() {}

    static Result build(byte[] runtime14, short[] pcm) {
        if (pcm == null || pcm.length == 0 || pcm.length % 640 != 0) {
            throw new IllegalArgumentException("发送PCM必须为640采样的非零倍数");
        }
        byte[] clearChannel72;
        try (SoftwareAmbeEncoder encoder = new SoftwareAmbeEncoder()) {
            clearChannel72 = encoder.encode(pcm);
        }
        Result result = buildFromClearChannel72(runtime14, clearChannel72);
        if (result.decodedPcm.length != pcm.length) {
            throw new IllegalStateException("软件AMBE解码采样数不匹配");
        }
        return result;
    }

    static Result buildFromClearChannel72(byte[] runtime14,
            byte[] clearChannel72) {
        if (clearChannel72 == null || clearChannel72.length == 0
                || clearChannel72.length % 36 != 0) {
            throw new IllegalArgumentException("明文信道AMBE必须为36字节的非零倍数");
        }
        byte[] raw49 = SoftwareAmbeDecoder
                .channelDecodeTo49BitPacked9(clearChannel72);
        byte[] privacy49 = DmrPrivacy.fromRuntime14(runtime14)
                .encrypt49BitParameters(raw49);
        byte[] channel72BeforeC3 = SoftwareAmbeEncoder
                .channelEncode49BitPacked9(privacy49);
        byte[] channel72 = DmrPrivacy.fromRuntime14(runtime14)
                .applyLateEntryC3ToChannelFrames(channel72BeforeC3);

        byte[] receiverPrivacy49 = SoftwareAmbeDecoder
                .channelDecodeTo49BitPacked9(channel72);
        byte[] receiverPlain49 = DmrPrivacy.fromRuntime14(runtime14)
                .decrypt49BitParameters(receiverPrivacy49);
        requireRoundTripExceptLateEntry(raw49, receiverPlain49);

        SoftwareAmbeDecoder.DecodeResult decoded;
        try (SoftwareAmbeDecoder decoder = new SoftwareAmbeDecoder()) {
            decoded = decoder.decodeDetailed49BitPacked9(receiverPlain49);
        }
        int[] channelHamming = channelHammingDistances(channel72,
                receiverPrivacy49, new int[] {21, 67, 69, 71});
        requireAllZero(channelHamming, "C3以外信道往返");
        AudioMetrics metrics = AudioMetrics.measure(decoded.pcm, 8000, 800.0);
        return new Result(clearChannel72, raw49, privacy49,
                channel72BeforeC3, channel72,
                receiverPrivacy49, receiverPlain49, decoded.pcm,
                decoded.frameErrors, channelHamming, metrics);
    }

    static void requireRoundTripExceptLateEntry(byte[] expected,
            byte[] actual) {
        if (expected == null || actual == null
                || expected.length != actual.length
                || expected.length == 0 || expected.length % 9 != 0) {
            throw new IllegalArgumentException("49位往返向量长度错误");
        }
        for (int frame = 0; frame < expected.length / 9; frame++) {
            for (int bit = 0; bit < 49; bit++) {
                if (bit == 47 || bit == 48) {
                    continue;
                }
                int offset = frame * 9 + bit / 8;
                int mask = 1 << (7 - bit % 8);
                if ((expected[offset] & mask) != (actual[offset] & mask)) {
                    throw new IllegalStateException("49位往返出现非C3差异 frame="
                            + frame + " bit=" + bit);
                }
            }
        }
    }

    static int[] channelHammingDistances(byte[] channel72,
            byte[] decoded49, int[] ignoredPhysicalBits) {
        if (channel72 == null || decoded49 == null
                || channel72.length != decoded49.length
                || channel72.length == 0 || channel72.length % 9 != 0) {
            throw new IllegalArgumentException("信道往返向量长度错误");
        }
        byte[] rebuilt = SoftwareAmbeEncoder
                .channelEncode49BitPacked9(decoded49);
        int[] result = new int[channel72.length / 9];
        for (int frame = 0; frame < result.length; frame++) {
            for (int bit = 0; bit < 72; bit++) {
                if (contains(ignoredPhysicalBits, bit)) {
                    continue;
                }
                int absolute = frame * 72 + bit;
                int mask = 1 << (7 - absolute % 8);
                if ((channel72[absolute / 8] & mask)
                        != (rebuilt[absolute / 8] & mask)) {
                    result[frame]++;
                }
            }
        }
        return result;
    }

    static void requireAllZero(int[] values, String label) {
        for (int index = 0; index < values.length; index++) {
            if (values[index] != 0) {
                throw new IllegalStateException(label + "第" + index
                        + "帧距离=" + values[index]);
            }
        }
    }

    private static boolean contains(int[] values, int candidate) {
        if (values == null) {
            return false;
        }
        for (int value : values) {
            if (value == candidate) {
                return true;
            }
        }
        return false;
    }

    static short[] tone800Pcm(int milliseconds, int amplitude) {
        if (milliseconds <= 0 || milliseconds % 80 != 0
                || amplitude <= 0 || amplitude > 30000) {
            throw new IllegalArgumentException("800赫兹向量参数错误");
        }
        short[] pcm = new short[milliseconds * 8];
        for (int index = 0; index < pcm.length; index++) {
            pcm[index] = (short) Math.round(amplitude
                    * Math.sin(2.0 * Math.PI * 800.0 * index / 8000.0));
        }
        return pcm;
    }

    static byte[] pcmS16Le(short[] pcm) {
        byte[] result = new byte[pcm.length * 2];
        for (int index = 0; index < pcm.length; index++) {
            result[index * 2] = (byte) pcm[index];
            result[index * 2 + 1] = (byte) (pcm[index] >>> 8);
        }
        return result;
    }

    static final class Result {
        final byte[] clearChannel72;
        final byte[] raw49;
        final byte[] privacy49;
        final byte[] channel72BeforeC3;
        final byte[] channel72;
        final byte[] receiverPrivacy49;
        final byte[] receiverPlain49;
        final short[] decodedPcm;
        final int[] decodeErrors;
        final int[] channelHamming;
        final AudioMetrics audioMetrics;

        Result(byte[] clearChannel72, byte[] raw49, byte[] privacy49,
                byte[] channel72BeforeC3,
                byte[] channel72,
                byte[] receiverPrivacy49, byte[] receiverPlain49,
                short[] decodedPcm, int[] decodeErrors,
                int[] channelHamming, AudioMetrics audioMetrics) {
            this.clearChannel72 = clearChannel72.clone();
            this.raw49 = raw49.clone();
            this.privacy49 = privacy49.clone();
            this.channel72BeforeC3 = channel72BeforeC3.clone();
            this.channel72 = channel72.clone();
            this.receiverPrivacy49 = receiverPrivacy49.clone();
            this.receiverPlain49 = receiverPlain49.clone();
            this.decodedPcm = decodedPcm.clone();
            this.decodeErrors = decodeErrors.clone();
            this.channelHamming = channelHamming.clone();
            this.audioMetrics = audioMetrics;
        }

        int frames() {
            return channel72.length / 9;
        }

        int data36Units() {
            return channel72.length / 36;
        }

        boolean differsFromPostFecPrivacy(byte[] legacy) {
            return legacy != null && legacy.length == channel72.length
                    && !Arrays.equals(channel72, legacy);
        }
    }
}
