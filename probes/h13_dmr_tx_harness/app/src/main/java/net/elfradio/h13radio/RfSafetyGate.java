package net.elfradio.h13radio;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;

/**
 * 射频安全闸：**默认禁发**，一切发射请求先过这里。
 *
 * <p>2.9.4 第六块要求「默认禁发、显式使能；频率与功率上限；占空比限制；
 * 呼号识别要求」，并且明确**实现在服务层，不放应用层**——放应用层等于
 * 每个调用方各自实现一遍，漏一处就是一次不该发生的发射。
 *
 * <p>这一层与 {@link Arbiter} 的分工：仲裁管「现在该不该发」（收发冲突、
 * 时限、尾音），安全闸管「允不允许发」（授权、频率、功率、占空、呼号）。
 * 两者都通过才允许发射，任一不过就拒绝。
 *
 * <p>设计取向是**宁可拒绝**：授权过期拒绝、频率不在白名单拒绝、
 * 功率无标定拒绝、占空超限拒绝、呼号超期未播拒绝。每一条拒绝都带理由，
 * 便于上层向操作者解释，而不是静默不发。
 */
public final class RfSafetyGate {
    /** 一次授权的最长有效期。再长就等于没有授权这回事。 */
    public static final long MAX_GRANT_MS = 60L * 60L * 1000L;
    /** 占空比统计窗口。 */
    public static final long DUTY_WINDOW_MS = 10L * 60L * 1000L;
    /** 窗口内允许的最大发射占比。 */
    public static final double MAX_DUTY = 0.20;
    /** 呼号播发间隔上限：超过这么久没播呼号就不许再发。 */
    public static final long CALLSIGN_INTERVAL_MS = 10L * 60L * 1000L;

    /** 一段已完成的发射，用于占空比统计。 */
    private static final class Burst {
        final long startMs;
        final long endMs;

        Burst(long startMs, long endMs) {
            this.startMs = startMs;
            this.endMs = endMs;
        }
    }

    private final Deque<Burst> bursts = new ArrayDeque<>();
    private long grantUntilMs = Long.MIN_VALUE;
    private long[] allowedBandHz;
    private String callsign;
    private long callsignSentMs = Long.MIN_VALUE;
    private Long openedAtMs;

    /**
     * 显式授权发射，有效期 {@code durationMs}。没有这一步，任何发射都被拒。
     *
     * @param callsign 呼号，空则拒绝授权——无呼号不得发射
     */
    public RadioService.Outcome grant(String callsign, long nowMs,
            long durationMs) {
        if (callsign == null || callsign.trim().isEmpty()) {
            return new RadioService.Outcome(false, "拒绝：未提供呼号");
        }
        if (durationMs <= 0 || durationMs > MAX_GRANT_MS) {
            return new RadioService.Outcome(false, String.format(Locale.US,
                    "拒绝：授权时长须在 1 毫秒到 %d 毫秒之间", MAX_GRANT_MS));
        }
        this.callsign = callsign.trim();
        this.grantUntilMs = nowMs + durationMs;
        return new RadioService.Outcome(true, String.format(Locale.US,
                "已授权至 %d（呼号 %s）", grantUntilMs, this.callsign));
    }

    /** 撤销授权。撤销后正在进行的发射由调用方自己停，这里只管不再放行。 */
    public void revoke() {
        grantUntilMs = Long.MIN_VALUE;
    }

    /** 设允许的频段，闭区间。不设则任何频率都被拒。 */
    public void allowBand(long lowHz, long highHz) {
        if (lowHz > highHz) {
            throw new IllegalArgumentException("频段下限高于上限");
        }
        allowedBandHz = new long[] {lowHz, highHz};
    }

    /** 记一次呼号播发。 */
    public void noteCallsignSent(long nowMs) {
        callsignSentMs = nowMs;
    }

    /**
     * 呼号是否该播了。授权后第一次发射前必须播过一次，此后每
     * {@link #CALLSIGN_INTERVAL_MS} 至少一次。
     */
    public boolean callsignDue(long nowMs) {
        return callsignSentMs == Long.MIN_VALUE
                || nowMs - callsignSentMs >= CALLSIGN_INTERVAL_MS;
    }

    /** 窗口内已发射的占比。 */
    public double duty(long nowMs) {
        prune(nowMs);
        long busy = 0;
        long windowStart = nowMs - DUTY_WINDOW_MS;
        for (Burst b : bursts) {
            busy += b.endMs - Math.max(b.startMs, windowStart);
        }
        if (openedAtMs != null) {
            busy += nowMs - Math.max(openedAtMs, windowStart);
        }
        return busy / (double) DUTY_WINDOW_MS;
    }

    private void prune(long nowMs) {
        long windowStart = nowMs - DUTY_WINDOW_MS;
        while (!bursts.isEmpty() && bursts.peekFirst().endMs <= windowStart) {
            bursts.pollFirst();
        }
    }

    /**
     * 是否允许开始一次发射。五条依次核，先到先拒，理由具体。
     *
     * @param plannedMs 本次打算发多久，用于占空比预判
     */
    public RadioService.Outcome mayTransmit(long freqHz, PowerTable.Result power,
            long nowMs, long plannedMs) {
        if (nowMs >= grantUntilMs) {
            return new RadioService.Outcome(false, "拒绝：未授权或授权已过期");
        }
        if (allowedBandHz == null) {
            return new RadioService.Outcome(false, "拒绝：未设允许频段");
        }
        if (freqHz < allowedBandHz[0] || freqHz > allowedBandHz[1]) {
            return new RadioService.Outcome(false, String.format(Locale.US,
                    "拒绝：%d Hz 不在允许频段 [%d, %d]",
                    freqHz, allowedBandHz[0], allowedBandHz[1]));
        }
        if (power == null || !power.ok) {
            return new RadioService.Outcome(false, "拒绝：功率未标定（"
                    + (power == null ? "未给出" : power.why) + "）");
        }
        if (callsignDue(nowMs)) {
            return new RadioService.Outcome(false, "拒绝：呼号超期未播");
        }
        if (plannedMs < 0) {
            return new RadioService.Outcome(false, "拒绝：计划发射时长为负");
        }
        double projected = duty(nowMs) + plannedMs / (double) DUTY_WINDOW_MS;
        if (projected > MAX_DUTY) {
            return new RadioService.Outcome(false, String.format(Locale.US,
                    "拒绝：占空比将达 %.1f%%，超过上限 %.1f%%",
                    projected * 100, MAX_DUTY * 100));
        }
        return new RadioService.Outcome(true, String.format(Locale.US,
                "允许（占空 %.1f%%，呼号 %s）", duty(nowMs) * 100, callsign));
    }

    /** 发射开始。与 {@link #close} 成对，用于占空比记账。 */
    public void open(long nowMs) {
        if (openedAtMs == null) {
            openedAtMs = nowMs;
        }
    }

    /** 发射结束。 */
    public void close(long nowMs) {
        if (openedAtMs == null) {
            return;
        }
        if (nowMs > openedAtMs) {
            bursts.addLast(new Burst(openedAtMs, nowMs));
        }
        openedAtMs = null;
        prune(nowMs);
    }

    public boolean transmitting() {
        return openedAtMs != null;
    }

    public String callsign() {
        return callsign;
    }
}
