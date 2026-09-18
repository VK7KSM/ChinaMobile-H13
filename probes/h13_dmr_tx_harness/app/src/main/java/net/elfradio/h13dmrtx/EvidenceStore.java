package net.elfradio.h13dmrtx;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

final class EvidenceStore implements SramTransaction.EvidenceSink,
        AutoCloseable {
    private final File directory;
    private final File sessionLog;
    private int sequence;
    private boolean resultWritten;
    private boolean manifestWritten;

    EvidenceStore(File root, String sessionId) throws IOException {
        if (root == null || !safeName(sessionId)) {
            throw new IllegalArgumentException("证据目录参数无效");
        }
        directory = new File(root, sessionId);
        if (directory.exists() || !directory.mkdirs()) {
            throw new IOException("证据会话目录已存在或无法创建：" + directory);
        }
        sessionLog = new File(directory, "session.log");
        writeNew(sessionLog, new byte[0]);
        log("证据会话建立 session=" + sessionId);
    }

    File directory() {
        return directory;
    }

    synchronized File saveEvent(String stage, String label, byte[] value)
            throws IOException {
        requireOpen();
        if (!safeName(stage) || !safeName(label) || value == null) {
            throw new IllegalArgumentException("证据事件参数无效");
        }
        String name = String.format(Locale.US, "%04d_%s_%s.bin",
                ++sequence, stage, label);
        File target = new File(directory, name);
        writeNew(target, value);
        log("原始事件 path=" + name + " bytes=" + value.length
                + " sha256=" + Bytes.sha256(value));
        return target;
    }

    synchronized File saveText(String stage, String label, String value)
            throws IOException {
        if (value == null) {
            throw new IllegalArgumentException("文本证据为空");
        }
        return saveEvent(stage, label,
                value.getBytes(StandardCharsets.UTF_8));
    }

    synchronized File saveCredential(String name, String value)
            throws IOException {
        requireOpen();
        if (!safeName(name) || value == null) {
            throw new IllegalArgumentException("固定凭证参数无效");
        }
        File target = new File(directory, name + ".txt");
        writeNew(target, value.getBytes(StandardCharsets.UTF_8));
        log("固定凭证 path=" + target.getName() + " bytes="
                + target.length() + " sha256=" + hashFile(target));
        return target;
    }

    synchronized void saveReadAttempt(McuMemory.ReadAttempt attempt)
            throws IOException {
        if (attempt == null) {
            throw new IllegalArgumentException("memread尝试为空");
        }
        String label = String.format(Locale.US, "0x%08x_try_%d",
                attempt.address, attempt.attempt);
        saveEvent("memread_attempt", label + "_raw", attempt.raw);
        saveEvent("memread_attempt", label + "_parsed", attempt.parsed);
        saveText("memread_attempt", label + "_meta",
                "command=" + attempt.command + "\n"
                + "address=" + String.format(Locale.US, "0x%08x",
                attempt.address) + "\n"
                + "expected_length=" + attempt.expectedLength + "\n"
                + "attempt=" + attempt.attempt + "\n"
                + "observed_at_epoch_ms=" + attempt.observedAtEpochMs + "\n"
                + "raw_length=" + attempt.raw.length + "\n"
                + "parsed_length=" + attempt.parsed.length + "\n"
                + "failure=" + attempt.failure + "\n");
    }

    @Override
    public synchronized void save(String stage, SramTransaction.Region region,
            McuMemory.ReadResult result) throws Exception {
        String base = String.format(Locale.US, "%s_0x%08x_%d",
                sanitize(region.name), region.address, region.length);
        saveEvent(sanitize(stage), base + "_serial_raw", result.raw);
        saveEvent(sanitize(stage), base + "_parsed", result.parsed);
        saveText(sanitize(stage), base + "_metadata",
                "command=" + result.command + "\n"
                + "attempt=" + result.attempt + "\n"
                + "address=" + String.format(Locale.US, "0x%08x",
                region.address) + "\n"
                + "length=" + region.length + "\n");
    }

    synchronized void log(String message) throws IOException {
        if (message == null || manifestWritten) {
            throw new IllegalStateException("证据会话已结束或日志为空");
        }
        String line = timestamp() + " " + message + System.lineSeparator();
        try (FileOutputStream output = new FileOutputStream(sessionLog, true)) {
            output.write(line.getBytes(StandardCharsets.UTF_8));
            output.getFD().sync();
        }
    }

    synchronized void writeAtomicResult(String result) throws IOException {
        requireOpen();
        if (resultWritten || result == null) {
            throw new IllegalStateException("原子结果重复或为空");
        }
        File temporary = new File(directory, "atomic_result.tmp");
        File target = new File(directory, "atomic_result.txt");
        writeNew(temporary, result.getBytes(StandardCharsets.UTF_8));
        if (target.exists() || !temporary.renameTo(target)) {
            throw new IOException("原子结果落盘失败");
        }
        resultWritten = true;
        log("原子结果已持久化 sha256=" + hashFile(target));
    }

    synchronized File finishManifest() throws IOException {
        requireOpen();
        if (!resultWritten) {
            throw new IllegalStateException("没有原子结果，禁止结束清单");
        }
        List<File> files = new ArrayList<>();
        collect(directory, files);
        Collections.sort(files, (left, right) -> relative(left)
                .compareTo(relative(right)));
        File target = new File(directory, "artifacts_sha256.tsv");
        File temporary = new File(directory, "artifacts_sha256.tmp");
        if (target.exists() || temporary.exists()) {
            throw new IOException("证据清单禁止覆盖");
        }
        try (FileOutputStream output = new FileOutputStream(temporary);
                BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                output, StandardCharsets.UTF_8))) {
            writer.write("相对路径\t字节数\tSHA-256\t文件时间毫秒\n");
            for (File file : files) {
                writer.write(relative(file));
                writer.write('\t');
                writer.write(Long.toString(file.length()));
                writer.write('\t');
                writer.write(hashFile(file));
                writer.write('\t');
                writer.write(Long.toString(file.lastModified()));
                writer.write('\n');
            }
            writer.flush();
            output.getFD().sync();
        }
        if (!temporary.renameTo(target)) {
            throw new IOException("证据清单原子落盘失败");
        }
        manifestWritten = true;
        return target;
    }

    @Override
    public synchronized void close() throws IOException {
        if (!manifestWritten) {
            log("证据会话关闭但清单尚未生成");
        }
    }

    private void requireOpen() {
        if (manifestWritten) {
            throw new IllegalStateException("证据清单已冻结");
        }
    }

    private static void writeNew(File target, byte[] value) throws IOException {
        if (target.exists()) {
            throw new IOException("禁止覆盖证据文件：" + target);
        }
        try (FileOutputStream output = new FileOutputStream(target)) {
            output.write(value);
            output.getFD().sync();
        }
    }

    private static void collect(File current, List<File> output) {
        File[] children = current.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (child.isDirectory()) {
                collect(child, output);
            } else if (!"artifacts_sha256.tsv".equals(child.getName())
                    && !"artifacts_sha256.tmp".equals(child.getName())
                    && !"atomic_result.tmp".equals(child.getName())) {
                output.add(child);
            }
        }
    }

    private String relative(File file) {
        return directory.toURI().relativize(file.toURI()).getPath();
    }

    private static String hashFile(File file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (FileInputStream input = new FileInputStream(file)) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) >= 0) {
                    digest.update(buffer, 0, count);
                }
            }
            return Bytes.sha256Digest(digest.digest());
        } catch (IOException error) {
            throw error;
        } catch (Exception error) {
            throw new IOException("证据SHA-256计算失败", error);
        }
    }

    private static String timestamp() {
        SimpleDateFormat format = new SimpleDateFormat(
                "yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US);
        format.setTimeZone(TimeZone.getDefault());
        return format.format(new Date());
    }

    private static String sanitize(String value) {
        if (value == null) {
            return "null";
        }
        return value.replaceAll("[^A-Za-z0-9_-]", "_");
    }

    private static boolean safeName(String value) {
        return value != null && value.matches("[A-Za-z0-9_-]{1,100}");
    }
}
