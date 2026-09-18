package net.elfradio.h13dmrtx;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

final class OfflineContract {
    static final byte[] FROZEN_RUNTIME14 = new byte[] {
            0x01, 0x01, 0x01, 0x00, 0x00,
            0x12, 0x34, 0x56, 0x78, (byte) 0x90,
            0x12, 0x34, 0x56, 0x78
    };
    static final String GOLDEN_CLEAR_WIRE_SHA256 =
            "92ED7A6219B125FC67A1E3AA6C6A3A85BA0CF0A365858FF59BB4215A21524269";

    private OfflineContract() {}

    static String runThreeVectors(InputStream silencePcm,
            InputStream silenceGolden, InputStream tonePcm,
            InputStream toneGolden, InputStream morsePcm,
            InputStream morseGolden) throws Exception {
        VectorResult silence = encodeVector("静音", silencePcm, silenceGolden);
        VectorResult tone = encodeVector("800赫兹", tonePcm, toneGolden);
        VectorResult morse = encodeVector("摩尔斯", morsePcm, morseGolden);
        int common = Math.min(silence.encoded.length, morse.encoded.length);
        if (Arrays.equals(silence.encoded, tone.encoded)
                || Arrays.equals(Arrays.copyOf(silence.encoded, common),
                        Arrays.copyOf(morse.encoded, common))) {
            throw new IllegalStateException("三组AMBE内容因果对照未形成差异");
        }
        TxPlan plan = TxPlan.create(morse.encoded);
        if (!Arrays.equals(morse.encoded, plan.channelAmbe())) {
            throw new IllegalStateException("明文频道AMBE被意外修改");
        }
        byte[] wire = HpiCodec.join(plan.wireUnits());
        if (!GOLDEN_CLEAR_WIRE_SHA256.equals(Bytes.sha256(wire))) {
            throw new IllegalStateException("明文44字节信封流黄金哈希不匹配");
        }
        return "三组离线合同通过\n" + silence.summary + "\n"
                + tone.summary + "\n" + morse.summary + "\n"
                + "data36单元=" + plan.wireUnits().size() + "\n"
                + "线帧 SHA-256=" + Bytes.sha256(wire);
    }

    static String run(InputStream pcmInput, InputStream goldenAmbeInput)
            throws Exception {
        byte[] pcmRaw = readAll(pcmInput);
        byte[] golden = readAll(goldenAmbeInput);
        short[] pcm = TxPlan.decodePcmS16Le(pcmRaw);
        if (pcm.length != TxPlan.EXPECTED_FRAME_COUNT
                * TxPlan.PCM_SAMPLES_PER_FRAME) {
            throw new IllegalStateException("冻结PCM帧数错误");
        }

        byte[] encoded;
        long started = System.nanoTime();
        try (SoftwareAmbeEncoder encoder = new SoftwareAmbeEncoder()) {
            encoded = encoder.encode(pcm);
        }
        long elapsedUs = (System.nanoTime() - started) / 1000L;
        if (!java.util.Arrays.equals(encoded, golden)) {
            throw new IllegalStateException("真机AMBE编码结果不匹配冻结向量");
        }
        TxPlan plan = TxPlan.create(encoded);
        if (!Arrays.equals(encoded, plan.channelAmbe())) {
            throw new IllegalStateException("明文频道AMBE被意外修改");
        }
        byte[] wire = HpiCodec.join(plan.wireUnits());
        if (!GOLDEN_CLEAR_WIRE_SHA256.equals(Bytes.sha256(wire))) {
            throw new IllegalStateException("明文44字节信封流黄金哈希不匹配");
        }
        return "离线合同通过\n"
                + "PCM采样=" + pcm.length + "\n"
                + "AMBE帧=" + (encoded.length / 9) + "\n"
                + "data36单元=" + plan.wireUnits().size() + "\n"
                + "编码总耗时微秒=" + elapsedUs + "\n"
                + "AMBE SHA-256=" + Bytes.sha256(encoded) + "\n"
                + "线帧 SHA-256=" + Bytes.sha256(wire);
    }

    private static VectorResult encodeVector(String name, InputStream pcmInput,
            InputStream goldenInput) throws Exception {
        byte[] pcmRaw = readAll(pcmInput);
        byte[] golden = readAll(goldenInput);
        short[] pcm = TxPlan.decodePcmS16Le(pcmRaw);
        byte[] encoded;
        long started = System.nanoTime();
        try (SoftwareAmbeEncoder encoder = new SoftwareAmbeEncoder()) {
            encoded = encoder.encode(pcm);
        }
        long elapsedUs = (System.nanoTime() - started) / 1000L;
        if (!Arrays.equals(encoded, golden)
                || encoded.length != pcm.length / 160 * 9) {
            throw new IllegalStateException(name + "AMBE输出不匹配冻结向量");
        }
        return new VectorResult(encoded, name + "：帧=" + encoded.length / 9
                + "，总耗时微秒=" + elapsedUs + "，SHA-256="
                + Bytes.sha256(encoded));
    }

    private static final class VectorResult {
        final byte[] encoded;
        final String summary;

        VectorResult(byte[] encoded, String summary) {
            this.encoded = encoded;
            this.summary = summary;
        }
    }

    private static byte[] readAll(InputStream input) throws IOException {
        try (InputStream source = input;
                ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = source.read(buffer)) >= 0) {
                out.write(buffer, 0, count);
            }
            return out.toByteArray();
        }
    }
}
