package net.elfradio.h13dmrtx;

import android_serialport_api.SerialPort;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

final class SerialTransport implements AutoCloseable {
    enum Mode { TEXT, BRIDGE, CLOSED }

    interface TraceSink {
        void record(String direction, String stage, byte[] value)
                throws Exception;
    }

    private final SerialPort port;
    private final InputStream input;
    private final OutputStream output;
    private final ActiveHpiStreamRouter activeRouter =
            new ActiveHpiStreamRouter();
    private Mode mode = Mode.TEXT;
    private TraceSink trace;
    private int controlWriteAttempts;
    private int controlFlushCompleted;
    private int dataWriteAttempts;
    private int dataFlushCompleted;

    SerialTransport(File device, int baud) throws IOException {
        port = new SerialPort(device, baud, 0);
        port.enableClock();
        input = port.getInputStream();
        output = port.getOutputStream();
    }

    synchronized Mode mode() {
        return mode;
    }

    synchronized void setTraceSink(TraceSink trace) {
        if (mode == Mode.CLOSED) {
            throw new IllegalStateException("串口已经关闭");
        }
        this.trace = trace;
    }

    synchronized byte[] exchangeText(String command, long timeoutMs)
            throws Exception {
        requireMode(Mode.TEXT);
        QuietResult drained = drainUntilQuiet(40, 300);
        trace("rx", "text_predrain", drained.drained);
        byte[] request = (command + "\r\n")
                .getBytes(StandardCharsets.US_ASCII);
        output.write(request);
        output.flush();
        trace("tx", "text_request", request);
        byte[] response = readUntilQuietAfterData(80, timeoutMs);
        trace("rx", "text_response", response);
        return response;
    }

    synchronized byte[] exchangeMemread(String command, int expectedLength,
            long timeoutMs) throws Exception {
        requireMode(Mode.TEXT);
        if (expectedLength <= 0) {
            throw new IllegalArgumentException("memread期望长度无效");
        }
        QuietResult drained = drainUntilQuiet(40, 300);
        trace("rx", "text_predrain", drained.drained);
        byte[] request = (command + "\r\n")
                .getBytes(StandardCharsets.US_ASCII);
        output.write(request);
        output.flush();
        trace("tx", "text_request", request);
        byte[] response = readUntilMemreadComplete(command, expectedLength,
                timeoutMs);
        trace("rx", "text_response", response);
        return response;
    }

    synchronized QuietResult drainUntilQuiet(long quietMs, long maximumMs)
            throws Exception {
        if (mode == Mode.CLOSED || quietMs < 0 || maximumMs < quietMs) {
            throw new IllegalStateException("串口静默窗参数或状态错误");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        long started = nowMs();
        long lastByte = started;
        byte[] buffer = new byte[1024];
        while (nowMs() - started <= maximumMs) {
            int available = input.available();
            if (available > 0) {
                int count = input.read(buffer, 0,
                        Math.min(buffer.length, available));
                if (count > 0) {
                    out.write(buffer, 0, count);
                    lastByte = nowMs();
                }
            } else if (nowMs() - lastByte >= quietMs) {
                return new QuietResult(true, nowMs() - started,
                        out.toByteArray());
            } else {
                Thread.sleep(2);
            }
        }
        return new QuietResult(false, nowMs() - started, out.toByteArray());
    }

    // 接收态采集：模块持续上报接收帧，串口上不会出现静默窗。
    // 置位后仍照常排空并记录（那正是要采的帧），但不因线上有数据而中止。
    private boolean allowBusyBridge;

    synchronized void allowBusyBridge(boolean allow) {
        this.allowBusyBridge = allow;
    }

    synchronized byte[] rawExchange(byte[] request, long responseMs,
            long lateMs) throws Exception {
        RawExchange result = rawExchangeDetailed(request, responseMs, lateMs);
        return result.combined();
    }

    synchronized RawExchange rawExchangeDetailed(byte[] request,
            long responseMs, long lateMs) throws Exception {
        requireMode(Mode.BRIDGE);
        QuietResult quiet = drainUntilQuiet(150, 300);
        trace("rx", "hpi_predrain", quiet.drained);
        String rebuildSignal = DmrProtocol.sessionRebuildSignal(quiet.drained);
        if (rebuildSignal != null) {
            throw new PreWriteSessionResetException(rebuildSignal,
                    quiet.drained);
        }
        if (!quiet.established && !allowBusyBridge) {
            throw new IOException("HPI请求前未取得静默窗");
        }
        controlWriteAttempts++;
        output.write(request);
        output.flush();
        controlFlushCompleted++;
        trace("tx", "hpi_request", request);
        byte[] first = readWindow(responseMs);
        byte[] late = readWindow(lateMs);
        trace("rx", "hpi_primary", first);
        trace("rx", "hpi_late", late);
        return new RawExchange(quiet.drained, first, late,
                concat(first, late), new byte[0]);
    }

    synchronized RawExchange rawExchangeActiveDetailed(byte[] request,
            long boundaryMs, long responseMs,
            ActiveHpiStreamRouter.FrameMatcher matcher) throws Exception {
        requireMode(Mode.BRIDGE);
        ActiveHpiStreamRouter.Read boundary =
                activeRouter.drainToFrameBoundary(input, boundaryMs);
        trace("rx", "hpi_active_predrain", boundary.observed);
        trace("rx", "hpi_active_carry_before_tx", boundary.carry);
        controlWriteAttempts++;
        output.write(request);
        output.flush();
        controlFlushCompleted++;
        trace("tx", "hpi_request", request);
        ActiveHpiStreamRouter.Read response = activeRouter.readUntil(input,
                responseMs, matcher);
        trace("rx", "hpi_active_observed", response.observed);
        trace("rx", "hpi_active_carry_after_rx", response.carry);
        return new RawExchange(boundary.observed, response.observed,
                new byte[0], response.matchedFrame, response.carry);
    }

    synchronized QuietResult drainControlQuiet() throws Exception {
        requireMode(Mode.BRIDGE);
        QuietResult quiet = drainUntilQuiet(150, 300);
        trace("rx", "hpi_predrain", quiet.drained);
        String rebuildSignal = DmrProtocol.sessionRebuildSignal(quiet.drained);
        if (rebuildSignal != null) {
            throw new PreWriteSessionResetException(rebuildSignal,
                    quiet.drained);
        }
        if (!quiet.established && !allowBusyBridge) {
            throw new IOException("HPI请求前未取得静默窗");
        }
        return quiet;
    }

    synchronized void writeControl(byte[] request) throws Exception {
        requireMode(Mode.BRIDGE);
        if (request == null || request.length == 0) {
            throw new IllegalArgumentException("HPI控制请求为空");
        }
        controlWriteAttempts++;
        output.write(request);
        output.flush();
        controlFlushCompleted++;
        trace("tx", "hpi_request", request);
    }

    /**
     * 冻结探针 VLC 合同：在时限内持续读取，不因首帧 ACK 提前返回。
     * {@code waitMs=0} 时只排空当前已到达字节。
     */
    synchronized byte[] readAvailable(long waitMs) throws Exception {
        return readAvailable(waitMs, 2048);
    }

    synchronized byte[] readAvailable(long waitMs, int maximumBytes)
            throws Exception {
        requireMode(Mode.BRIDGE);
        if (waitMs < 0 || maximumBytes <= 0 || maximumBytes > 2048) {
            throw new IllegalArgumentException("持续读取等待为负");
        }
        byte[] buffer = new byte[2048];
        long deadline = nowMs() + waitMs;
        while (true) {
            int available = input.available();
            if (available > 0) {
                int count = input.read(buffer, 0,
                        Math.min(maximumBytes,
                                Math.min(buffer.length, available)));
                if (count > 0) {
                    return Arrays.copyOf(buffer, count);
                }
            }
            if (nowMs() >= deadline) {
                return new byte[0];
            }
            Thread.sleep(1);
        }
    }

    synchronized RawExchange readActiveUntil(long timeoutMs,
            ActiveHpiStreamRouter.FrameMatcher matcher) throws Exception {
        requireMode(Mode.BRIDGE);
        if (timeoutMs <= 0 || matcher == null) {
            throw new IllegalArgumentException("活动HPI等待参数无效");
        }
        ActiveHpiStreamRouter.Read response = activeRouter.readUntil(input,
                timeoutMs, matcher);
        trace("rx", "hpi_active_observed", response.observed);
        trace("rx", "hpi_active_carry_after_rx", response.carry);
        return new RawExchange(new byte[0], response.observed, new byte[0],
                response.matchedFrame, response.carry);
    }

    synchronized void rawWrite(byte[] request) throws Exception {
        requireMode(Mode.BRIDGE);
        dataWriteAttempts++;
        output.write(request);
        output.flush();
        dataFlushCompleted++;
        trace("tx", "hpi_write", request);
    }

    synchronized int controlWriteAttempts() {
        return controlWriteAttempts;
    }

    synchronized int controlFlushCompleted() {
        return controlFlushCompleted;
    }

    synchronized int dataWriteAttempts() {
        return dataWriteAttempts;
    }

    synchronized int dataFlushCompleted() {
        return dataFlushCompleted;
    }

    synchronized byte[] readWindow(long timeoutMs) throws Exception {
        if (mode == Mode.CLOSED || timeoutMs < 0) {
            throw new IllegalStateException("串口读取状态错误");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[2048];
        long deadline = nowMs() + timeoutMs;
        while (nowMs() < deadline) {
            int available = input.available();
            if (available > 0) {
                int count = input.read(buffer, 0,
                        Math.min(buffer.length, available));
                if (count > 0) {
                    out.write(buffer, 0, count);
                }
            } else {
                Thread.sleep(2);
            }
        }
        return out.toByteArray();
    }

    synchronized byte[] readUntilQuietAfterData(long quietMs, long maximumMs)
            throws Exception {
        if (mode == Mode.CLOSED || quietMs <= 0 || maximumMs < quietMs) {
            throw new IllegalStateException("串口响应窗参数或状态错误");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[2048];
        long started = nowMs();
        long lastByte = -1;
        while (nowMs() - started < maximumMs) {
            int available = input.available();
            if (available > 0) {
                int count = input.read(buffer, 0,
                        Math.min(buffer.length, available));
                if (count > 0) {
                    out.write(buffer, 0, count);
                    lastByte = nowMs();
                }
            } else if (lastByte >= 0 && nowMs() - lastByte >= quietMs) {
                return out.toByteArray();
            } else {
                Thread.sleep(2);
            }
        }
        return out.toByteArray();
    }

    synchronized byte[] readUntilMemreadComplete(String command,
            int expectedLength, long maximumMs) throws Exception {
        if (mode == Mode.CLOSED || command == null || expectedLength <= 0
                || maximumMs <= 0) {
            throw new IllegalStateException("memread响应窗参数或状态错误");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[2048];
        long deadline = nowMs() + maximumMs;
        long completeAt = -1;
        while (nowMs() < deadline) {
            int available = input.available();
            if (available > 0) {
                int count = input.read(buffer, 0,
                        Math.min(buffer.length, available));
                if (count > 0) {
                    out.write(buffer, 0, count);
                    if (McuMemory.parseRead(out.toByteArray(), command,
                            expectedLength).length == expectedLength) {
                        completeAt = nowMs();
                    }
                }
            } else if (completeAt >= 0 && nowMs() - completeAt >= 30) {
                return out.toByteArray();
            } else {
                Thread.sleep(2);
            }
        }
        return out.toByteArray();
    }

    synchronized void markBridgeActive() {
        requireMode(Mode.TEXT);
        mode = Mode.BRIDGE;
    }

    synchronized void markAutomaticBridgeExit() {
        requireMode(Mode.BRIDGE);
        mode = Mode.TEXT;
    }

    @Override
    public synchronized void close() {
        if (mode == Mode.CLOSED) {
            return;
        }
        mode = Mode.CLOSED;
        try {
            port.disableClock();
        } catch (Throwable ignored) {
        }
        port.close();
    }

    private void requireMode(Mode expected) {
        if (mode != expected) {
            throw new IllegalStateException("串口模式错误：" + mode
                    + "，要求=" + expected);
        }
    }

    private void trace(String direction, String stage, byte[] value)
            throws Exception {
        if (trace != null) {
            trace.record(direction, stage, value.clone());
        }
    }

    private static long nowMs() {
        return System.nanoTime() / 1_000_000L;
    }

    static final class QuietResult {
        final boolean established;
        final long elapsedMs;
        final byte[] drained;

        QuietResult(boolean established, long elapsedMs, byte[] drained) {
            this.established = established;
            this.elapsedMs = elapsedMs;
            this.drained = drained;
        }
    }

    static final class RawExchange {
        final byte[] preDrain;
        final byte[] primary;
        final byte[] late;
        final byte[] matchedFrame;
        final byte[] carry;

        RawExchange(byte[] preDrain, byte[] primary, byte[] late,
                byte[] matchedFrame, byte[] carry) {
            this.preDrain = preDrain.clone();
            this.primary = primary.clone();
            this.late = late.clone();
            this.matchedFrame = matchedFrame == null
                    ? null : matchedFrame.clone();
            this.carry = carry.clone();
        }

        byte[] combined() {
            byte[] result = new byte[primary.length + late.length];
            System.arraycopy(primary, 0, result, 0, primary.length);
            System.arraycopy(late, 0, result, primary.length, late.length);
            return result;
        }
    }

    static final class PreWriteSessionResetException extends IOException {
        final String signal;
        final byte[] preDrain;

        PreWriteSessionResetException(String signal, byte[] preDrain) {
            super("HPI写出前发现会话重建信号：" + signal);
            this.signal = signal;
            this.preDrain = preDrain.clone();
        }
    }

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] result = new byte[first.length + second.length];
        System.arraycopy(first, 0, result, 0, first.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }
}
