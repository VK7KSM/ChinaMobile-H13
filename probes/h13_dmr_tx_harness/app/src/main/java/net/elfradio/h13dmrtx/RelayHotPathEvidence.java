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
 * 人声素材为 480 包（28.8 秒），第 257 包即触上限，于是放大到 1024 项。
 *
 * 2026-09-21 的长时供数老化（1020 单元、61.2 秒）在**第 1010 单元**撞上
 * 1024 这条线，会话以「实时原始证据缓冲区已满」失败——是宿主的天花板，
 * 不是模块的。这正是长时测试该暴露的东西：此前所有素材都不够长，这条
 * 上限从来没被碰到过。
 *
 * 现按 2040 单元（约两分钟）留余量。单元请求每条 44 字节，2040 条约
 * 90 KB，字节上限放到 1 MiB 仍有富余。**仍为有界内存**——不改成无界，
 * 是因为热路径落盘正是当初 v0.53 让第六包迟到 67 毫秒的原因。
 */
final class RelayHotPathEvidence {
    static final int MAX_EVENTS = 4096;
    static final int MAX_EVENT_BYTES = 1048576;
    // 三个VLC确认窗都可能触发信用背压。读取轮询次数随包数增长，
    // 与事件上限同比例放大。
    static final int MAX_READ_SAMPLES = 8192;
    static final int MAX_READ_BYTES = 1048576;

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
