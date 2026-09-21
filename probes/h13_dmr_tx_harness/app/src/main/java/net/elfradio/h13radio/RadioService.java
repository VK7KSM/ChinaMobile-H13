package net.elfradio.h13radio;

import java.util.Locale;

/**
 * 射频核心服务：把仲裁、抖动缓冲、TG 路由、功率标定串成一个接缝。
 *
 * <p>与 {@code tools/offline/radio_service_contract.py} 逐条对应。这一层
 * **不碰硬件**：它只决定"允不允许、回哪一帧"，怎么下发交给上层的传输实现。
 * 这样服务的行为可以在没有设备的情况下逐条验收，射频测试只用来核对，
 * 不用来摸索——2026-09-20 那天多次让操作者发射却因流程缺陷白费，根子就在
 * 当时没有这样一层可离线验收的行为定义。
 *
 * <p>边界条件在这里写死：加密信道一律拒绝、未配置信道不得发射、
 * 接收中不得发射、标定不足不得设功率、未订阅的 TG 不得占用射频。
 */
public final class RadioService {
    public enum Source { LOCAL, NET }

    /** 一次调用的结果：允许与否，以及理由。理由永远不为空。 */
    public static final class Outcome {
        public final boolean ok;
        public final String why;

        Outcome(boolean ok, String why) {
            this.ok = ok;
            this.why = why;
        }
    }

    /** 信道配置。加密字段只存在于入参，是为了能把它拒掉。 */
    public static final class Channel {
        public final long freqHz;
        public final int colorCode;
        public final int slot;
        public final int talkgroup;

        Channel(long freqHz, int colorCode, int slot, int talkgroup) {
            this.freqHz = freqHz;
            this.colorCode = colorCode;
            this.slot = slot;
            this.talkgroup = talkgroup;
        }
    }

    private final Arbiter arbiter = new Arbiter();
    private final JitterBuffer buffer = new JitterBuffer();
    private final TalkgroupRouter router = new TalkgroupRouter();
    private final PowerTable power = new PowerTable();

    private Channel channel;
    private Integer powerCode;
    private Source txSource;

    public Arbiter arbiter() {
        return arbiter;
    }

    public JitterBuffer buffer() {
        return buffer;
    }

    public TalkgroupRouter router() {
        return router;
    }

    public PowerTable power() {
        return power;
    }

    public Channel channel() {
        return channel;
    }

    public Integer powerCode() {
        return powerCode;
    }

    public Source txSource() {
        return txSource;
    }

    // ---- 配置 ----

    /**
     * 配置信道。**明文是硬要求**：加密开启时对端没有密钥，收到的必然是
     * 与内容无关的噪音，而且这一条会掩盖其它所有问题——2.8.71 记录了
     * 十五次发射全部白费在这上面。
     */
    public Outcome setChannel(long freqHz, int colorCode, int slot, int tg,
            boolean encrypted) {
        if (encrypted) {
            return new Outcome(false, "拒绝：本项目只在明文信道工作");
        }
        if (colorCode < 0 || colorCode > 15) {
            return new Outcome(false, "色码超范围");
        }
        if (slot != 0 && slot != 1) {
            return new Outcome(false, "时隙须为 0 或 1");
        }
        channel = new Channel(freqHz, colorCode, slot, tg);
        return new Outcome(true, "信道已设");
    }

    public void subscribe(int tg) {
        router.addStatic(tg);
    }

    public Outcome setPower(double watts) {
        PowerTable.Result r = power.codeForWatts(watts);
        if (!r.ok) {
            return new Outcome(false, "拒绝：" + r.why);
        }
        powerCode = r.code;
        return new Outcome(true, String.format(Locale.US, "功率码 %d（%s）",
                r.code, r.why));
    }

    // ---- 发射 ----

    public Outcome startTx(Source source, long nowMs) {
        if (channel == null) {
            return new Outcome(false, "拒绝：信道未配置");
        }
        boolean ok = source == Source.LOCAL
                ? arbiter.requestLocal(nowMs)
                : arbiter.requestNet(nowMs);
        if (!ok) {
            return new Outcome(false,
                    "拒绝：仲裁不允许（当前 " + arbiter.state() + "）");
        }
        txSource = source;
        return new Outcome(true, "开始发射（" + source + "）");
    }

    public void pushNetFrame(byte[] frame) {
        buffer.push(frame);
    }

    /**
     * 模块交一帧，必须回一帧——回不上就补静音，绝不断流。
     * 供数契约是"收到一帧才回送一帧"，一对一（2.8.75）。
     */
    public byte[] onModuleOffer() {
        return buffer.pop();
    }

    public void stopTx(long nowMs) {
        arbiter.release(nowMs);
        txSource = null;
    }

    // ---- 接收 ----

    public void onRxStart(long nowMs) {
        arbiter.onRxStart(nowMs);
    }

    public boolean onRxFrame(int src, int dst, int stream, int slot, long nowMs,
            int errors) {
        return router.onFrame(src, dst, stream, slot, nowMs, errors);
    }

    public void onRxEnd(long nowMs) {
        arbiter.onRxEnd(nowMs);
        router.flush(nowMs + TalkgroupRouter.CALL_GAP_MS + 1);
    }

    public String status() {
        return String.format(Locale.US,
                "状态 %s  信道 %s  功率码 %s  缓冲 %d 帧  Last Heard %d 条",
                arbiter.state(), channel != null ? "已设" : "未设",
                powerCode != null ? powerCode.toString() : "未设",
                buffer.size(), router.lastHeard().size());
    }
}
