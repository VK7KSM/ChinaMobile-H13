package net.elfradio.h13dmrtx;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;

final class SoftwarePipelineDiagnostic {
    static final String MODE = "software_pipeline_diagnostic_no_radio";
    private static final byte[] RUNTIME14 = new byte[] {
            0x01, 0x01, 0x01, 0x00, 0x00,
            0x12, 0x34, 0x56, 0x78, (byte) 0x90,
            0x12, 0x34, 0x56, 0x78
    };

    private SoftwarePipelineDiagnostic() {}

    static String run(Context context) throws Exception {
        File parent = new File(context.getExternalFilesDir(null),
                "software_diagnostics");
        File output = new File(parent, new SimpleDateFormat(
                "yyyyMMdd_HHmmss_SSS", Locale.US).format(new Date()));
        if (!output.mkdirs()) {
            throw new IllegalStateException("无法建立纯软件诊断目录");
        }
        try {
            byte[] historicalDmr = readAsset(context,
                    "software_ambe_vectors/tone_800hz.ambe9_sequence.bin");
            byte[] historicalExpected49 = parseReference49(readAsset(context,
                    "software_ambe_vectors/tone_800hz_mbelib_reference.csv"));
            byte[] historicalExtracted49 = SoftwareAmbeDecoder
                    .channelDecodeTo49BitPacked9(historicalDmr);
            requireEqual("历史52帧规范49位", historicalExpected49,
                    historicalExtracted49);
            byte[] historicalRebuilt = SoftwareAmbeEncoder
                    .channelEncode49BitPacked9(historicalExtracted49);
            requireEqual("历史52帧72位重建", historicalDmr, historicalRebuilt);
            SoftwareAmbeDecoder.DecodeResult historicalDecoded;
            try (SoftwareAmbeDecoder decoder = new SoftwareAmbeDecoder()) {
                historicalDecoded = decoder.decodeDmrReference(historicalDmr);
            }
            byte[] historicalPcm = SoftwareDmrPrivacyPipeline
                    .pcmS16Le(historicalDecoded.pcm);
            byte[] historicalWav = readAsset(context,
                    "software_ambe_vectors/tone_800hz_mbelib_reference.wav");
            if (historicalWav.length != historicalPcm.length + 44) {
                throw new IllegalStateException("历史mbelib WAV长度不匹配");
            }
            byte[] historicalExpectedPcm = Arrays.copyOfRange(
                    historicalWav, 44, historicalWav.length);
            write(output, "historical_tone800_channel72.bin", historicalDmr);
            write(output, "historical_tone800_expected49.bin",
                    historicalExpected49);
            write(output, "historical_tone800_extracted49.bin",
                    historicalExtracted49);
            write(output, "historical_tone800_rebuilt_channel72.bin",
                    historicalRebuilt);
            write(output, "historical_tone800_expected_pcm_s16le.bin",
                    historicalExpectedPcm);
            write(output, "historical_tone800_reference_pcm_s16le.bin",
                    historicalPcm);
            write(output, "historical_tone800_reference_errors_i32le.bin",
                    intArrayLe(historicalDecoded.frameErrors));
            write(output, "historical_tone800_pcm_difference.txt",
                    pcmDifferenceReport(historicalExpectedPcm, historicalPcm)
                            .getBytes(StandardCharsets.UTF_8));
            AudioMetrics historicalExpectedMetrics = AudioMetrics.measure(
                    pcmFromS16Le(historicalExpectedPcm), 8000, 800.0);
            AudioMetrics historicalActualMetrics = AudioMetrics.measure(
                    historicalDecoded.pcm, 8000, 800.0);
            write(output, "historical_tone800_expected_metrics.txt",
                    historicalExpectedMetrics.report().getBytes(
                            StandardCharsets.UTF_8));
            write(output, "historical_tone800_actual_metrics.txt",
                    historicalActualMetrics.report().getBytes(
                            StandardCharsets.UTF_8));
            double historicalCorrelation = requireReferenceEquivalent(
                    historicalExpectedPcm, historicalPcm,
                    historicalExpectedMetrics, historicalActualMetrics,
                    historicalDecoded.frameErrors);

            // 剥信道编码的两条实现是否一致（2026-09-21）。
            // 离线工具 chan_d_to_params.py 走 chan_d_to_wav.exe，设备上走
            // 这里的 native channelDecodeTo49BitPacked9。网关要把网络来的
            // 已编码帧剥成 49 位参数，两条实现若不一致，取哪条会决定空口
            // 上是不是可懂——而"能听出是人声但听不懂"恰恰分辨不出这种错
            // （十九次失败发射就是这个样子）。
            // 锚点是有空口结果背书的那一对：输入为 2026-08-06 捕获的空口
            // 单元，期望输出就是 2.8.83 那次成功发射真正送出的载荷。
            byte[] replayAir = readAsset(context,
                    "software_ambe_vectors/dmr_replay_air_input.chan_d27.bin");
            byte[] replayExpected49 = readAsset(context,
                    "software_ambe_vectors/dmr_replay_captured.chan_d27.bin");
            byte[] replayExtracted49 = SoftwareAmbeDecoder
                    .channelDecodeTo49BitPacked9(replayAir);
            write(output, "replay_air_input.bin", replayAir);
            write(output, "replay_expected49.bin", replayExpected49);
            write(output, "replay_native_extracted49.bin", replayExtracted49);
            write(output, "replay_strip_agreement.txt",
                    ("input_bytes=" + replayAir.length + "\n"
                     + "expected_bytes=" + replayExpected49.length + "\n"
                     + "native_bytes=" + replayExtracted49.length + "\n"
                     + "agrees=" + Arrays.equals(replayExpected49,
                            replayExtracted49) + "\n")
                            .getBytes(StandardCharsets.UTF_8));
            requireEqual("剥信道编码与冻结载荷一致", replayExpected49,
                    replayExtracted49);

            byte[] inputPcmRaw = readAsset(context,
                    "software_ambe_vectors/tone_800hz.pcm_s16le");
            short[] input = pcmFromS16Le(inputPcmRaw);
            byte[] encodedChannel72;
            try (SoftwareAmbeEncoder encoder = new SoftwareAmbeEncoder()) {
                encodedChannel72 = encoder.encode(input);
            }
            requireEqual("冻结PCM软件编码72位黄金向量", historicalDmr,
                    encodedChannel72);
            byte[] raw49 = SoftwareAmbeDecoder
                    .channelDecodeTo49BitPacked9(encodedChannel72);
            requireEqual("冻结PCM软件编码规范49位", historicalExpected49,
                    raw49);
            SoftwareAmbeDecoder.DecodeResult rawDecoded = decode(raw49);
            AudioMetrics rawMetrics = AudioMetrics.measure(rawDecoded.pcm,
                    8000, 800.0);

            byte[] privacy49 = DmrPrivacy.fromRuntime14(RUNTIME14)
                    .encrypt49BitParameters(raw49);
            byte[] channelBeforeC3 = SoftwareAmbeEncoder
                    .channelEncode49BitPacked9(privacy49);
            byte[] preReceiverPrivacy = SoftwareAmbeDecoder
                    .channelDecodeTo49BitPacked9(channelBeforeC3);
            int[] preHamming = SoftwareDmrPrivacyPipeline
                    .channelHammingDistances(channelBeforeC3,
                            preReceiverPrivacy, null);
            byte[] preReceiverPlain = DmrPrivacy.fromRuntime14(RUNTIME14)
                    .decrypt49BitParameters(preReceiverPrivacy);
            SoftwareDmrPrivacyPipeline.requireRoundTripExceptLateEntry(raw49,
                    preReceiverPlain);
            SoftwareAmbeDecoder.DecodeResult preDecoded = decode(
                    preReceiverPlain);
            AudioMetrics preMetrics = AudioMetrics.measure(preDecoded.pcm,
                    8000, 800.0);

            byte[] channelFinal = DmrPrivacy.fromRuntime14(RUNTIME14)
                    .applyLateEntryC3ToChannelFrames(channelBeforeC3);
            byte[] finalReceiverPrivacy = SoftwareAmbeDecoder
                    .channelDecodeTo49BitPacked9(channelFinal);
            int[] finalHamming = SoftwareDmrPrivacyPipeline
                    .channelHammingDistances(channelFinal,
                            finalReceiverPrivacy,
                            new int[] {21, 67, 69, 71});
            byte[] finalReceiverPlain = DmrPrivacy.fromRuntime14(RUNTIME14)
                    .decrypt49BitParameters(finalReceiverPrivacy);
            SoftwareDmrPrivacyPipeline.requireRoundTripExceptLateEntry(raw49,
                    finalReceiverPlain);
            SoftwareAmbeDecoder.DecodeResult finalDecoded = decode(
                    finalReceiverPlain);
            AudioMetrics finalMetrics = AudioMetrics.measure(finalDecoded.pcm,
                    8000, 800.0);

            write(output, "input_800hz_pcm_s16le.bin", inputPcmRaw);
            write(output, "software_encoded_channel72.bin",
                    encodedChannel72);
            write(output, "raw49.bin", raw49);
            saveDecode(output, "raw49", rawDecoded, rawMetrics);
            write(output, "privacy49.bin", privacy49);
            write(output, "channel72_before_c3.bin", channelBeforeC3);
            write(output, "before_c3_receiver_privacy49.bin",
                    preReceiverPrivacy);
            write(output, "before_c3_receiver_plain49.bin", preReceiverPlain);
            write(output, "before_c3_hamming_i32le.bin", intArrayLe(preHamming));
            saveDecode(output, "before_c3", preDecoded, preMetrics);
            write(output, "channel72_final.bin", channelFinal);
            write(output, "final_receiver_privacy49.bin",
                    finalReceiverPrivacy);
            write(output, "final_receiver_plain49.bin", finalReceiverPlain);
            write(output, "final_hamming_i32le.bin", intArrayLe(finalHamming));
            saveDecode(output, "final", finalDecoded, finalMetrics);

            String summary = "mode=" + MODE + "\n"
                    + "radio_opened=false\nserial_opened=false\n"
                    + "h13_hardware_ambe_used=false\n"
                    + "historical_reference_frames="
                    + historicalDmr.length / 9 + "\n"
                    + "historical_reference_49_match=true\n"
                    + "historical_reference_channel_rebuild_match=true\n"
                    + "historical_reference_pcm_semantic_match=true\n"
                    + "historical_reference_pcm_correlation="
                    + String.format(Locale.US, "%.9f", historicalCorrelation)
                    + "\n"
                    + "software_encode_channel72_match=true\n"
                    + "input_pcm_sha256=" + Bytes.sha256(inputPcmRaw) + "\n"
                    + "software_encoded_channel72_sha256="
                    + Bytes.sha256(encodedChannel72) + "\n"
                    + "frames=" + raw49.length / 9 + "\n"
                    + "raw49_sha256=" + Bytes.sha256(raw49) + "\n"
                    + "privacy49_sha256=" + Bytes.sha256(privacy49) + "\n"
                    + "channel_before_c3_sha256="
                    + Bytes.sha256(channelBeforeC3) + "\n"
                    + "channel_final_sha256=" + Bytes.sha256(channelFinal)
                    + "\npre_hamming_sum=" + sum(preHamming)
                    + "\nfinal_hamming_sum=" + sum(finalHamming) + "\n"
                    + "[原始49位]\n" + rawMetrics.report()
                    + "[写C3前往返]\n" + preMetrics.report()
                    + "[写C3后最终往返]\n" + finalMetrics.report();
            write(output, "summary.txt",
                    summary.getBytes(StandardCharsets.UTF_8));
            rawMetrics.requireSoftwareAmbeTone800();
            preMetrics.requireSoftwareAmbeTone800();
            finalMetrics.requireSoftwareAmbeTone800();
            return "纯软件分层诊断完成：" + output.getAbsolutePath();
        } catch (Throwable error) {
            write(output, "failure.txt", (error.getClass().getSimpleName()
                    + ":" + error.getMessage() + "\n")
                    .getBytes(StandardCharsets.UTF_8));
            throw error;
        }
    }

    private static SoftwareAmbeDecoder.DecodeResult decode(byte[] packed49) {
        try (SoftwareAmbeDecoder decoder = new SoftwareAmbeDecoder()) {
            return decoder.decodeDetailed49BitPacked9(packed49);
        }
    }

    private static void saveDecode(File output, String prefix,
            SoftwareAmbeDecoder.DecodeResult decoded, AudioMetrics metrics)
            throws Exception {
        write(output, prefix + "_decoded_pcm_s16le.bin",
                SoftwareDmrPrivacyPipeline.pcmS16Le(decoded.pcm));
        write(output, prefix + "_decode_errors_i32le.bin",
                intArrayLe(decoded.frameErrors));
        write(output, prefix + "_metrics.txt",
                metrics.report().getBytes(StandardCharsets.UTF_8));
    }

    private static void write(File directory, String name, byte[] value)
            throws Exception {
        try (FileOutputStream stream = new FileOutputStream(
                new File(directory, name))) {
            stream.write(value);
            stream.getFD().sync();
        }
    }

    private static byte[] readAsset(Context context, String name)
            throws Exception {
        try (InputStream input = context.getAssets().open(name);
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }

    private static byte[] parseReference49(byte[] csvBytes) {
        String[] lines = new String(csvBytes, StandardCharsets.US_ASCII)
                .split("\\r?\\n");
        byte[] result = new byte[(lines.length - 1) * 9];
        int frame = 0;
        for (int line = 1; line < lines.length; ++line) {
            if (lines[line].trim().isEmpty()) {
                continue;
            }
            if (lines[line].startsWith("frames=")) {
                continue;
            }
            String[] fields = lines[line].split(",");
            if (fields.length != 4 || Integer.parseInt(fields[0]) != frame) {
                throw new IllegalStateException("历史49位CSV格式错误");
            }
            long value = Long.parseUnsignedLong(fields[3], 16);
            for (int bit = 0; bit < 49; ++bit) {
                if (((value >>> (48 - bit)) & 1L) != 0) {
                    result[frame * 9 + bit / 8] |= (byte) (0x80 >>> (bit & 7));
                }
            }
            frame++;
        }
        if (frame * 9 != result.length) {
            return Arrays.copyOf(result, frame * 9);
        }
        return result;
    }

    private static void requireEqual(String name, byte[] expected,
            byte[] actual) {
        if (!Arrays.equals(expected, actual)) {
            int limit = Math.min(expected.length, actual.length);
            int first = 0;
            while (first < limit && expected[first] == actual[first]) {
                first++;
            }
            throw new IllegalStateException(name + "不一致：expected_len="
                    + expected.length + " actual_len=" + actual.length
                    + " first_diff=" + first);
        }
    }

    private static String pcmDifferenceReport(byte[] expected, byte[] actual) {
        if (expected.length != actual.length || (expected.length & 1) != 0) {
            return "长度不匹配：expected=" + expected.length + " actual="
                    + actual.length + "\n";
        }
        int different = 0;
        int first = -1;
        int maxAbsolute = 0;
        long absoluteSum = 0;
        for (int offset = 0; offset < expected.length; offset += 2) {
            int left = (short) ((expected[offset] & 0xff)
                    | ((expected[offset + 1] & 0xff) << 8));
            int right = (short) ((actual[offset] & 0xff)
                    | ((actual[offset + 1] & 0xff) << 8));
            int difference = Math.abs(left - right);
            if (difference != 0) {
                different++;
                if (first < 0) {
                    first = offset / 2;
                }
            }
            maxAbsolute = Math.max(maxAbsolute, difference);
            absoluteSum += difference;
        }
        return "samples=" + expected.length / 2 + "\n"
                + "different_samples=" + different + "\n"
                + "first_different_sample=" + first + "\n"
                + "max_absolute_difference=" + maxAbsolute + "\n"
                + "mean_absolute_difference="
                + String.format(Locale.US, "%.6f",
                        absoluteSum / (double) (expected.length / 2)) + "\n";
    }

    private static short[] pcmFromS16Le(byte[] value) {
        if ((value.length & 1) != 0) {
            throw new IllegalArgumentException("PCM字节数必须为偶数");
        }
        short[] result = new short[value.length / 2];
        for (int index = 0; index < result.length; ++index) {
            result[index] = (short) ((value[index * 2] & 0xff)
                    | ((value[index * 2 + 1] & 0xff) << 8));
        }
        return result;
    }

    private static double requireReferenceEquivalent(byte[] expectedBytes,
            byte[] actualBytes, AudioMetrics expectedMetrics,
            AudioMetrics actualMetrics, int[] frameErrors) {
        if (sum(frameErrors) != 0) {
            throw new IllegalStateException("历史mbelib参考解码出现位错误");
        }
        short[] expected = pcmFromS16Le(expectedBytes);
        short[] actual = pcmFromS16Le(actualBytes);
        if (expected.length != actual.length) {
            throw new IllegalStateException("历史mbelib PCM长度不匹配");
        }
        double dot = 0.0;
        double expectedEnergy = 0.0;
        double actualEnergy = 0.0;
        for (int index = 0; index < expected.length; ++index) {
            dot += expected[index] * (double) actual[index];
            expectedEnergy += expected[index] * (double) expected[index];
            actualEnergy += actual[index] * (double) actual[index];
        }
        double correlation = dot / Math.sqrt(expectedEnergy * actualEnergy);
        double rmsRelativeDifference = Math.abs(actualMetrics.rms
                - expectedMetrics.rms) / expectedMetrics.rms;
        double dominantRelativeDifference = Math.abs(
                actualMetrics.dominantAmplitude
                        - expectedMetrics.dominantAmplitude)
                / expectedMetrics.dominantAmplitude;
        if (correlation < 0.995 || rmsRelativeDifference > 0.01
                || actualMetrics.dominantFrequency
                        != expectedMetrics.dominantFrequency
                || actualMetrics.dominantFrequency < 760.0
                || actualMetrics.dominantFrequency > 840.0
                || dominantRelativeDifference > 0.02) {
            throw new IllegalStateException("历史mbelib跨架构语义门失败：correlation="
                    + String.format(Locale.US, "%.9f", correlation)
                    + " rms_relative_difference="
                    + String.format(Locale.US, "%.9f", rmsRelativeDifference)
                    + " dominant_relative_difference="
                    + String.format(Locale.US, "%.9f",
                            dominantRelativeDifference));
        }
        return correlation;
    }

    private static int sum(int[] values) {
        int result = 0;
        for (int value : values) {
            result += value;
        }
        return result;
    }

    private static byte[] intArrayLe(int[] values) {
        byte[] result = new byte[values.length * 4];
        for (int index = 0; index < values.length; index++) {
            int value = values[index];
            result[index * 4] = (byte) value;
            result[index * 4 + 1] = (byte) (value >>> 8);
            result[index * 4 + 2] = (byte) (value >>> 16);
            result[index * 4 + 3] = (byte) (value >>> 24);
        }
        return result;
    }
}
