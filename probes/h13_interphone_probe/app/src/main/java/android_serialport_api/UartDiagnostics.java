package android_serialport_api;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.FileDescriptor;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

public final class UartDiagnostics {
    private static final int SCHEMA = 1;
    private static final int VALUE_COUNT = 25;
    private static final int DRAIN_SCHEMA = 1;
    private static final int DRAIN_VALUE_COUNT = 16;

    static {
        System.loadLibrary("uart_diag");
    }

    private UartDiagnostics() {
    }

    public static Snapshot snapshot(SerialPort serialPort) throws IOException {
        if (serialPort == null || serialPort.getFileDescriptor() == null) {
            throw new IOException("串口或文件描述符为空");
        }
        long[] values = nativeSnapshot(serialPort.getFileDescriptor());
        if (values == null || values.length != VALUE_COUNT || values[0] != SCHEMA) {
            throw new IOException("UART诊断快照格式不符");
        }
        return new Snapshot(values);
    }

    public static DrainSnapshot drainTransmitter(SerialPort serialPort,
            int baudRate, int byteCount, int timeoutMs) throws IOException {
        if (serialPort == null || serialPort.getFileDescriptor() == null) {
            throw new IOException("串口或文件描述符为空");
        }
        if (baudRate <= 0 || byteCount <= 0 || timeoutMs <= 0) {
            throw new IOException("发送排空参数非法");
        }
        long[] values = nativeDrainTransmitter(serialPort.getFileDescriptor(),
                baudRate, byteCount, timeoutMs);
        if (values == null || values.length != DRAIN_VALUE_COUNT
                || values[0] != DRAIN_SCHEMA) {
            throw new IOException("UART发送排空快照格式不符");
        }
        return new DrainSnapshot(values);
    }

    private static native long[] nativeSnapshot(FileDescriptor descriptor);

    private static native long[] nativeDrainTransmitter(
            FileDescriptor descriptor, int baudRate, int byteCount,
            int timeoutMs);

    public static final class DrainSnapshot {
        private final long[] values;

        private DrainSnapshot(long[] values) {
            this.values = values.clone();
        }

        public boolean successful() {
            return values[3] == 0 && values[4] == 0
                    && values[8] == 0 && values[10] == 0
                    && (values[13] != 0 || (values[15] & 1) != 0);
        }

        public int errorNumber() {
            return (int) values[4];
        }

        public long startedNanos() {
            return values[1];
        }

        public long completedNanos() {
            return values[2];
        }

        public long guardNanos() {
            return values[12];
        }

        public int queuedBefore() {
            return (int) values[7];
        }

        public int queuedAfter() {
            return (int) values[10];
        }

        public int pollCount() {
            return (int) values[11];
        }

        public byte[] toRawBytes() throws IOException {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(
                    values.length * (Long.SIZE / Byte.SIZE));
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                for (long value : values) {
                    output.writeLong(Long.reverseBytes(value));
                }
            }
            return bytes.toByteArray();
        }

        public byte[] toChineseText() {
            String text = "格式版本=" + values[0] + "\n"
                    + "开始单调纳秒=" + values[1] + "\n"
                    + "完成单调纳秒=" + values[2] + "\n"
                    + "排空返回=" + values[3] + "\n"
                    + "排空错误码=" + values[4] + "\n"
                    + "首次发送队列返回=" + values[5] + "\n"
                    + "首次发送队列错误码=" + values[6] + "\n"
                    + "首次发送队列字节=" + values[7] + "\n"
                    + "末次发送队列返回=" + values[8] + "\n"
                    + "末次发送队列错误码=" + values[9] + "\n"
                    + "末次发送队列字节=" + values[10] + "\n"
                    + "发送队列轮询次数=" + values[11] + "\n"
                    + "线时长保护纳秒=" + values[12] + "\n"
                    + "发送器空检查返回=" + values[13] + "\n"
                    + "发送器空检查错误码=" + values[14] + "\n"
                    + "发送器空检查值=" + values[15] + "\n";
            return text.getBytes(StandardCharsets.UTF_8);
        }
    }

    public static final class Snapshot {
        private final long[] values;

        private Snapshot(long[] values) {
            this.values = values.clone();
        }

        public boolean termiosAvailable() {
            return values[2] == 0;
        }

        public int termiosErrno() {
            return (int) values[3];
        }

        public boolean icountAvailable() {
            return values[4] == 0;
        }

        public int icountErrno() {
            return (int) values[5];
        }

        public long inputBaud() {
            return values[12];
        }

        public long outputBaud() {
            return values[13];
        }

        public long frameCount() {
            return values[21];
        }

        public long overrunCount() {
            return values[22];
        }

        public long parityCount() {
            return values[23];
        }

        public long breakCount() {
            return values[24];
        }

        public long monotonicNanos() {
            return values[1];
        }

        public byte[] toRawBytes() throws IOException {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(
                    values.length * (Long.SIZE / Byte.SIZE));
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                for (long value : values) {
                    output.writeLong(Long.reverseBytes(value));
                }
            }
            return bytes.toByteArray();
        }

        public byte[] toChineseText() {
            String text = "格式版本=" + values[0] + "\n"
                    + "单调时钟纳秒=" + values[1] + "\n"
                    + "termios返回=" + values[2] + "\n"
                    + "termios错误码=" + values[3] + "\n"
                    + "TIOCGICOUNT返回=" + values[4] + "\n"
                    + "TIOCGICOUNT错误码=" + values[5] + "\n"
                    + String.format(Locale.US,
                    "输入标志=0x%08x\n输出标志=0x%08x\n控制标志=0x%08x\n本地标志=0x%08x\n",
                    values[6], values[7], values[8], values[9])
                    + "输入速率编码=" + values[10] + "\n"
                    + "输出速率编码=" + values[11] + "\n"
                    + "输入波特率=" + values[12] + "\n"
                    + "输出波特率=" + values[13] + "\n"
                    + "CTS计数=" + values[14] + "\n"
                    + "DSR计数=" + values[15] + "\n"
                    + "RNG计数=" + values[16] + "\n"
                    + "DCD计数=" + values[17] + "\n"
                    + "接收字节计数=" + values[18] + "\n"
                    + "发送字节计数=" + values[19] + "\n"
                    + "帧错误计数=" + values[21] + "\n"
                    + "溢出错误计数=" + values[22] + "\n"
                    + "奇偶错误计数=" + values[23] + "\n"
                    + "中断错误计数=" + values[24] + "\n";
            return text.getBytes(StandardCharsets.UTF_8);
        }
    }
}
