package net.elfradio.h13interphoneprobe;

import android.content.res.AssetManager;
import android.os.Debug;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;

final class SoftwareAmbeNoRfTest {
    private static final String ASSET_ROOT = "software_ambe_vectors/";
    private static final int SAMPLES_PER_FRAME = 160;
    private static final int BYTES_PER_FRAME = 9;

    private SoftwareAmbeNoRfTest() {}

    static final class VerifiedMorseAsset {
        final byte[] pcmS16le;
        final byte[] ambe9;

        VerifiedMorseAsset(byte[] pcmS16le, byte[] ambe9) {
            this.pcmS16le = pcmS16le.clone();
            this.ambe9 = ambe9.clone();
        }
    }

    /** 读取并在本机重新编码摩尔斯资产；只有逐字匹配冻结黄金向量才返回。 */
    static VerifiedMorseAsset requireVerifiedMorse(AssetManager assets)
            throws Exception {
        byte[] pcmBytes = readAsset(assets,
                ASSET_ROOT + "morse_sos.pcm_s16le");
        byte[] expected = readAsset(assets,
                ASSET_ROOT + "morse_sos.ambe9_sequence.bin");
        byte[] actual;
        try (SoftwareAmbeEncoder encoder = new SoftwareAmbeEncoder()) {
            actual = encoder.encode(toShorts(pcmBytes));
        }
        if (!Arrays.equals(expected, actual) || actual.length == 0
                || actual.length % 36 != 0) {
            throw new IllegalStateException("摩尔斯软件AMBE源未通过黄金向量门");
        }
        return new VerifiedMorseAsset(pcmBytes, actual);
    }

    static ProbeResult runData36Privacy(File filesDir, AssetManager assets) {
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                .format(new Date());
        File outputDir = new File(filesDir,
                "software_ambe_data36_privacy_no_rf_" + stamp);
        StringBuilder report = new StringBuilder();
        boolean success = false;
        try {
            if (!outputDir.mkdirs() && !outputDir.isDirectory()) {
                throw new IllegalStateException("无法创建原始证据目录");
            }
            VerifiedMorseAsset verified = requireVerifiedMorse(assets);
            byte[] pcmBytes = verified.pcmS16le;
            byte[] plain = verified.ambe9;

            byte[] runtime14 = new byte[] {
                    0x01, 0x01, 0x01, 0x00, 0x00,
                    0x12, 0x34, 0x56, 0x78, (byte) 0x90,
                    0x12, 0x34, 0x56, 0x78
            };
            InterphoneProbe.DmrPrivacyStreamContext whole =
                    InterphoneProbe.DmrPrivacyStreamContext
                            .fromMeasuredRuntime(runtime14);
            byte[] encryptedWhole = whole.encrypt(plain);
            String wholeState = whole.stateSummary();

            InterphoneProbe.DmrPrivacyStreamContext chunked =
                    InterphoneProbe.DmrPrivacyStreamContext
                            .fromMeasuredRuntime(runtime14);
            byte[] encryptedChunked = new byte[plain.length];
            ByteArrayOutputStream wires = new ByteArrayOutputStream();
            int units = plain.length / 36;
            for (int unit = 0; unit < units; unit++) {
                byte[] plain36 = Arrays.copyOfRange(plain, unit * 36,
                        (unit + 1) * 36);
                byte[] encrypted36 = chunked.encryptData36(plain36);
                System.arraycopy(encrypted36, 0, encryptedChunked,
                        unit * 36, 36);
                byte[] wire = InterphoneProbe
                        .createSoftwareAmbeExternalEncodedTxFrame(encrypted36);
                if (wire.length != 44
                        || !InterphoneProbe.looksLikeExternalData36Frame(wire)
                        || !Arrays.equals(encrypted36,
                                Arrays.copyOfRange(wire, 8, 44))) {
                    throw new IllegalStateException("第" + unit
                            + "个data36信封合同失败");
                }
                wires.write(wire);
            }
            String chunkedState = chunked.stateSummary();
            if (!Arrays.equals(encryptedWhole, encryptedChunked)
                    || !wholeState.equals(chunkedState)) {
                throw new IllegalStateException("整流与逐data36连续privacy结果不一致");
            }
            if (chunked.globalFrameIndex() != plain.length / BYTES_PER_FRAME
                    || chunked.frameInSuperframe() != 14
                    || chunked.completedData36Units() != units) {
                throw new IllegalStateException("privacy跨超帧状态总账错误");
            }

            write(new File(outputDir, "morse_sos.pcm_s16le"), pcmBytes);
            write(new File(outputDir, "morse_sos.plain.ambe9.bin"), plain);
            write(new File(outputDir, "morse_sos.encrypted.ambe9.bin"),
                    encryptedChunked);
            write(new File(outputDir, "morse_sos.wire44_sequence.bin"),
                    wires.toByteArray());
            write(new File(outputDir, "privacy_fixture_runtime14.bin"), runtime14);
            report.append("软件AMBE帧数=").append(plain.length / 9)
                    .append("，data36单元数=").append(units)
                    .append("，44字节信封数=").append(wires.size() / 44).append('\n')
                    .append("明文SHA-256=").append(sha256(plain)).append('\n')
                    .append("连续密文SHA-256=").append(sha256(encryptedChunked)).append('\n')
                    .append("信封流SHA-256=").append(sha256(wires.toByteArray())).append('\n')
                    .append("privacy终态=").append(chunkedState).append('\n')
                    .append("整流/分块一致=true\n")
                    .append("串口打开次数=0\n射频命令次数=0\n频道修改次数=0\n");
            success = true;
        } catch (Throwable error) {
            report.append("失败：").append(error.getClass().getSimpleName())
                    .append("：").append(error.getMessage()).append('\n')
                    .append("串口打开次数=0\n射频命令次数=0\n频道修改次数=0\n");
        }
        report.append("证据目录=").append(outputDir.getAbsolutePath()).append('\n')
                .append("结论=").append(success ? "通过" : "失败").append('\n');
        try {
            writeAtomic(new File(outputDir, "result.txt"),
                    report.toString().getBytes("UTF-8"));
        } catch (Exception writeError) {
            report.append("结果文件写入失败：").append(writeError.getMessage()).append('\n');
            success = false;
        }
        return new ProbeResult(success, report.toString());
    }

    static ProbeResult run(File filesDir, AssetManager assets) {
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                .format(new Date());
        File outputDir = new File(filesDir, "software_ambe_no_rf_" + stamp);
        StringBuilder report = new StringBuilder();
        boolean success = false;
        try {
            if (!outputDir.mkdirs() && !outputDir.isDirectory()) {
                throw new IllegalStateException("无法创建原始证据目录");
            }
            String[] names = {"silence", "tone_800hz", "morse_sos"};
            byte[][] actual = new byte[names.length][];
            for (int index = 0; index < names.length; index++) {
                String name = names[index];
                byte[] pcmBytes = readAsset(assets,
                        ASSET_ROOT + name + ".pcm_s16le");
                byte[] expected = readAsset(assets,
                        ASSET_ROOT + name + ".ambe9_sequence.bin");
                short[] pcm = toShorts(pcmBytes);
                try (SoftwareAmbeEncoder encoder = new SoftwareAmbeEncoder()) {
                    actual[index] = encoder.encode(pcm);
                }
                write(new File(outputDir, name + ".pcm_s16le"), pcmBytes);
                write(new File(outputDir, name + ".expected.ambe9.bin"), expected);
                write(new File(outputDir, name + ".actual.ambe9.bin"), actual[index]);
                boolean exact = Arrays.equals(expected, actual[index]);
                report.append(name).append("：PCM采样=").append(pcm.length)
                        .append("，AMBE帧=").append(actual[index].length / BYTES_PER_FRAME)
                        .append("，data36单元=").append(actual[index].length / 36)
                        .append("，逐字节匹配=").append(exact)
                        .append("，实际SHA-256=").append(sha256(actual[index]))
                        .append('\n');
                if (!exact) {
                    throw new IllegalStateException(name + "未匹配桌面黄金AMBE向量");
                }
            }

            int silenceTone = firstDifferentFrame(actual[0], actual[1]);
            int silenceMorse = firstDifferentFrame(actual[0], actual[2]);
            int toneMorse = firstDifferentFrame(actual[1], actual[2]);
            report.append("首差帧：静音/连续音=").append(silenceTone)
                    .append("，静音/摩尔斯=").append(silenceMorse)
                    .append("，连续音/摩尔斯=").append(toneMorse).append('\n');
            if (silenceTone != 1 || silenceMorse != 1 || toneMorse != 5) {
                throw new IllegalStateException("内容因果首差帧不符合黄金合同");
            }

            byte[] tonePcmBytes = readAsset(assets,
                    ASSET_ROOT + "tone_800hz.pcm_s16le");
            short[] tonePcm = toShorts(tonePcmBytes);
            short[] frame = new short[SAMPLES_PER_FRAME];
            long nativeBefore = Debug.getNativeHeapAllocatedSize();
            long javaBefore = usedJavaMemory();
            long[] elapsed = new long[1_000];
            try (SoftwareAmbeEncoder encoder = new SoftwareAmbeEncoder()) {
                for (int attempt = 0; attempt < 1_010; attempt++) {
                    int frameIndex = attempt % (tonePcm.length / SAMPLES_PER_FRAME);
                    System.arraycopy(tonePcm, frameIndex * SAMPLES_PER_FRAME,
                            frame, 0, SAMPLES_PER_FRAME);
                    long start = System.nanoTime();
                    byte[] encoded = encoder.encode(frame);
                    long duration = System.nanoTime() - start;
                    if (encoded.length != BYTES_PER_FRAME) {
                        throw new IllegalStateException("单帧编码长度不是9字节");
                    }
                    if (attempt >= 10) {
                        elapsed[attempt - 10] = duration;
                    }
                }
            }
            long nativeAfter = Debug.getNativeHeapAllocatedSize();
            long javaAfter = usedJavaMemory();
            long[] sorted = elapsed.clone();
            Arrays.sort(sorted);
            long p50 = percentile(sorted, 50);
            long p95 = percentile(sorted, 95);
            long p99 = percentile(sorted, 99);
            long maximum = sorted[sorted.length - 1];
            StringBuilder timing = new StringBuilder("序号\t耗时纳秒\n");
            for (int index = 0; index < elapsed.length; index++) {
                timing.append(index).append('\t').append(elapsed[index]).append('\n');
            }
            write(new File(outputDir, "single_frame_timing.tsv"),
                    timing.toString().getBytes("UTF-8"));
            report.append("单帧1000次耗时：P50=").append(p50)
                    .append("纳秒，P95=").append(p95)
                    .append("纳秒，P99=").append(p99)
                    .append("纳秒，最大=").append(maximum).append("纳秒\n")
                    .append("内存：本地堆前=").append(nativeBefore)
                    .append("，本地堆后=").append(nativeAfter)
                    .append("，Java堆前=").append(javaBefore)
                    .append("，Java堆后=").append(javaAfter).append('\n');
            if (p99 >= 20_000_000L) {
                throw new IllegalStateException("单帧编码P99未达到20毫秒实时门");
            }
            report.append("串口打开次数=0\n射频命令次数=0\n频道修改次数=0\n");
            success = true;
        } catch (Throwable error) {
            report.append("失败：").append(error.getClass().getSimpleName())
                    .append("：").append(error.getMessage()).append('\n')
                    .append("串口打开次数=0\n射频命令次数=0\n频道修改次数=0\n");
        }

        report.append("证据目录=").append(outputDir.getAbsolutePath()).append('\n')
                .append("结论=").append(success ? "通过" : "失败").append('\n');
        try {
            writeAtomic(new File(outputDir, "result.txt"),
                    report.toString().getBytes("UTF-8"));
        } catch (Exception writeError) {
            report.append("结果文件写入失败：").append(writeError.getMessage()).append('\n');
            success = false;
        }
        return new ProbeResult(success, report.toString());
    }

    private static byte[] readAsset(AssetManager assets, String name) throws Exception {
        try (InputStream input = assets.open(name);
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                if (count > 0) output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }

    private static short[] toShorts(byte[] littleEndian) {
        if (littleEndian.length == 0 || littleEndian.length % 320 != 0) {
            throw new IllegalArgumentException("PCM必须是完整160采样帧的s16le数据");
        }
        short[] result = new short[littleEndian.length / 2];
        ByteBuffer.wrap(littleEndian).order(ByteOrder.LITTLE_ENDIAN)
                .asShortBuffer().get(result);
        return result;
    }

    private static int firstDifferentFrame(byte[] left, byte[] right) {
        int frames = Math.min(left.length, right.length) / BYTES_PER_FRAME;
        for (int frame = 0; frame < frames; frame++) {
            int offset = frame * BYTES_PER_FRAME;
            for (int item = 0; item < BYTES_PER_FRAME; item++) {
                if (left[offset + item] != right[offset + item]) return frame;
            }
        }
        return frames;
    }

    private static long percentile(long[] sorted, int percent) {
        int index = (int) Math.ceil(sorted.length * percent / 100.0) - 1;
        return sorted[Math.max(0, Math.min(sorted.length - 1, index))];
    }

    private static long usedJavaMemory() {
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }

    private static String sha256(byte[] data) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(data);
        StringBuilder result = new StringBuilder(64);
        for (byte value : digest) result.append(String.format(Locale.US, "%02X", value & 0xff));
        return result.toString();
    }

    private static void write(File file, byte[] data) throws Exception {
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(data);
            output.getFD().sync();
        }
    }

    private static void writeAtomic(File file, byte[] data) throws Exception {
        File temporary = new File(file.getParentFile(), file.getName() + ".tmp");
        write(temporary, data);
        if (!temporary.renameTo(file)) {
            throw new IllegalStateException("原子结果文件重命名失败");
        }
    }
}
