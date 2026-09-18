package net.elfradio.h13dmrtx;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 实时供数窗口只保存有界内存证据，退桥并关射频后再落盘。
 *
 * 之所以不在热路径上直接落盘：历史上 v0.53 每包同步建文件并刷盘，
 * 导致第六包迟到 67 毫秒；改为有界内存缓冲后最大迟到降到 1 毫秒。
 *
 * 容量按最长正文估算。原值 256 项是按 78 包（6.24 秒摩尔斯）设计的，
 * 人声素材为 480 包（28.8 秒），第 257 包即触上限。现按 480 包留余量取 1024 项，
 * 字节上限同步放大。仍为有界内存，不会无限增长。
 */
final class RelayHotPathEvidence {
    static final int MAX_EVENTS = 1024;
    static final int MAX_EVENT_BYTES = 262144;
    // 三个VLC确认窗都可能触发信用背压。读取轮询次数随包数增长，
    // 按 480 包留余量取 2048 项，仍为有界内存。
    static final int MAX_READ_SAMPLES = 2048;
    static final int MAX_READ_BYTES = 262144;

    static final class Event {
        final String category;
        final String label;
        final byte[] value;

        Event(String category, String label, byte[] value) {
            this.category = category;
            this.label = label;
            this.value = value;
        }
    }

    static final class ReadSample {
        final String point;
        final int unitBefore;
        final int creditBefore;
        final long beginMs;
        final long requestedMs;
        final long returnMs;
        final byte[] value;

        ReadSample(String point, int unitBefore, int creditBefore,
                long beginMs, long requestedMs, long returnMs, byte[] value) {
            this.point = point;
            this.unitBefore = unitBefore;
            this.creditBefore = creditBefore;
            this.beginMs = beginMs;
            this.requestedMs = requestedMs;
            this.returnMs = returnMs;
            this.value = value.clone();
        }

        String asText() {
            return "point=" + point + "\n"
                    + "unit_before=" + unitBefore + "\n"
                    + "credit_before=" + creditBefore + "\n"
                    + "begin_ms=" + beginMs + "\n"
                    + "requested_ms=" + requestedMs + "\n"
                    + "return_ms=" + returnMs + "\n"
                    + "elapsed_ms=" + (returnMs - beginMs) + "\n"
                    + "bytes=" + value.length + "\n";
        }
    }

    private final List<Event> events = new ArrayList<>();
    private final List<ReadSample> reads = new ArrayList<>();
    private int eventBytes;
    private int readBytes;

    void addEvent(String category, String label, byte[] value)
            throws IOException {
        if (category == null || label == null || value == null) {
            throw new IllegalArgumentException("实时证据参数为空");
        }
        if (events.size() >= MAX_EVENTS
                || eventBytes + value.length > MAX_EVENT_BYTES) {
            throw new IOException("实时原始证据缓冲区已满");
        }
        byte[] copy = value.clone();
        events.add(new Event(category, label, copy));
        eventBytes += copy.length;
    }

    void addRead(String point, int unitBefore, int creditBefore,
            long beginMs, long requestedMs, long returnMs, byte[] value)
            throws IOException {
        if (point == null || beginMs < 0 || requestedMs < 0
                || returnMs < beginMs || value == null) {
            throw new IllegalArgumentException("实时读取证据参数无效");
        }
        if (reads.size() >= MAX_READ_SAMPLES
                || readBytes + value.length > MAX_READ_BYTES) {
            throw new IOException("实时读取证据缓冲区已满");
        }
        reads.add(new ReadSample(point, unitBefore, creditBefore,
                beginMs, requestedMs, returnMs, value));
        readBytes += value.length;
    }

    List<Event> events() {
        return Collections.unmodifiableList(events);
    }

    List<ReadSample> reads() {
        return Collections.unmodifiableList(reads);
    }

    int eventBytes() {
        return eventBytes;
    }

    int readBytes() {
        return readBytes;
    }
}
