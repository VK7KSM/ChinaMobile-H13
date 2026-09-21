package net.elfradio.h13radio;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 帧泵：把已证实的供数契约固化成一个可以对着假传输逐条验收的组件。
 *
 * <p>契约只有一句话——**模块交一帧，宿主回一帧，一对一**（2.8.75）。
 * 这一句是二十次发射换来的，值得单独成件：
 *
 * <ul>
 *   <li>发射态下模块**不发回执**。按回执等待会把串口往返延迟串进时间轴，
 *       68 单元被拉到 10.4 秒而音频只有 4.08 秒，对端完全无声。</li>
 *   <li>没等到交帧就写出去叫**无许可写出**，一次都不允许。零射频复现性
 *       批次里这个数始终是 0，是供数契约成立的判据之一（2.9.43）。</li>
 *   <li>缓冲空了补静音，**绝不断流**——断流会话就散。</li>
 * </ul>
 *
 * <p>这里刻意不开线程：线程归 Android 层，组件本身是同步的，
 * 用例才能把时间轴捏在手里。
 */
public final class FramePump {

    /** 一次供数的结果。计数与统计都在这里，不写日志。 */
    public static final class Result {
        /** 模块交帧次数。 */
        public final int offers;
        /** 宿主写出的单元数。正常情况下等于 offers。 */
        public final int written;
        /** 无许可写出。**必须为 0**，非 0 即违反供数契约。 */
        public final int writesWithoutPermit;
        /** 缓冲空、补静音的次数。 */
        public final int underruns;
        /** 等交帧超时的次数。 */
        public final int offerTimeouts;
        /** 相邻两次交帧的间隔，毫秒。 */
        public final List<Long> intervalsMs;
        /** 为什么停下来。 */
        public final String stopReason;

        Result(int offers, int written, int writesWithoutPermit, int underruns,
                int offerTimeouts, List<Long> intervalsMs, String stopReason) {
            this.offers = offers;
            this.written = written;
            this.writesWithoutPermit = writesWithoutPermit;
            this.underruns = underruns;
            this.offerTimeouts = offerTimeouts;
            this.intervalsMs = Collections.unmodifiableList(intervalsMs);
            this.stopReason = stopReason;
        }

        /**
         * 供数契约是否成立：一对一，且没有无许可写出。
         *
         * <p>**它不回答"写满了没有"**。链路中途断掉时交三写三，契约依然
         * 成立，只是会话没跑完——写没写满看 {@link #written} 与
         * {@link #stopReason}。两者混为一谈会让"链路断了"被误读成
         * "契约破了"，而这两种故障的排查方向完全不同。
         */
        public boolean contractHeld() {
            return writesWithoutPermit == 0 && offers == written;
        }

        public long medianIntervalMs() {
            if (intervalsMs.isEmpty()) {
                return 0;
            }
            List<Long> sorted = new ArrayList<>(intervalsMs);
            Collections.sort(sorted);
            return sorted.get(sorted.size() / 2);
        }

        public String report() {
            return String.format(Locale.US,
                    "交帧 %d，写出 %d，无许可写出 %d，补静音 %d，交帧超时 %d，"
                    + "间隔中位 %d 毫秒，停因：%s",
                    offers, written, writesWithoutPermit, underruns,
                    offerTimeouts, medianIntervalMs(), stopReason);
        }
    }

    /** 等一次交帧的上限。空口帧率 60 毫秒，留到三倍仍等不到就是真出事了。 */
    public static final long OFFER_TIMEOUT_MS = 200L;
    /** 连续多少次等不到交帧就判定链路已断。 */
    public static final int MAX_CONSECUTIVE_TIMEOUTS = 3;

    private final ModuleTransport transport;
    private final JitterBuffer buffer;

    public FramePump(ModuleTransport transport, JitterBuffer buffer) {
        if (transport == null || buffer == null) {
            throw new IllegalArgumentException("传输与缓冲都不能为空");
        }
        this.transport = transport;
        this.buffer = buffer;
    }

    /**
     * 跑一轮供数，直到写满 {@code units} 个单元、超过 {@code deadlineMs}，
     * 或连续等不到交帧。
     *
     * @param units 目标单元数；0 或负数表示不按数量停，只看时限
     */
    public Result run(int units, long deadlineMs) throws Exception {
        int offers = 0;
        int written = 0;
        int underruns = 0;
        int timeouts = 0;
        int consecutiveTimeouts = 0;
        List<Long> intervals = new ArrayList<>();
        long lastOfferMs = -1;
        String stop = "已写满";

        while (true) {
            if (units > 0 && written >= units) {
                break;
            }
            if (transport.nowMs() >= deadlineMs) {
                stop = "到时限";
                break;
            }
            boolean got = transport.awaitOffer(OFFER_TIMEOUT_MS);
            if (!got) {
                timeouts++;
                consecutiveTimeouts++;
                // 没等到交帧就**不写**。补写出去就是无许可写出，
                // 一对一从此不成立，而且模块并不会因此多收一帧。
                if (consecutiveTimeouts >= MAX_CONSECUTIVE_TIMEOUTS) {
                    stop = "连续 " + consecutiveTimeouts + " 次等不到交帧";
                    break;
                }
                continue;
            }
            consecutiveTimeouts = 0;
            offers++;
            long now = transport.nowMs();
            if (lastOfferMs >= 0) {
                intervals.add(now - lastOfferMs);
            }
            lastOfferMs = now;

            int before = buffer.underruns();
            byte[] unit = buffer.pop();
            if (buffer.underruns() > before) {
                underruns++;
            }
            transport.writeUnit(unit);
            written++;
        }
        // 无许可写出在本实现里结构上不可能发生：写只在 awaitOffer 成功后。
        // 计数仍然保留并归零上报，是为了让调用方与设备侧的同名指标能直接对齐，
        // 而不是让"结构上不会发生"变成"没人再核对"。
        return new Result(offers, written, 0, underruns, timeouts,
                intervals, stop);
    }
}
