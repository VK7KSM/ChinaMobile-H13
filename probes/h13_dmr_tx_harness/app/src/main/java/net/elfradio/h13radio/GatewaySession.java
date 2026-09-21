package net.elfradio.h13radio;

import java.util.Locale;

/**
 * 把各组件拼成一次完整的网络来话发射，证明它们能搭在一起。
 *
 * <p>组件各自有用例，但**组件之间的接缝没人验过**——接缝上的错（谁先谁后、
 * 谁负责回滚、失败时射频关没关）恰恰是最难在单元测试里发现、又最容易在
 * 真机上变成"发射没停下来"的那类。这个类存在的意义就是把接缝也钉住。
 *
 * <p>一次发射的顺序是固定的，而且**每一步失败都必须把前面已经打开的东西
 * 关回去**：
 *
 * <pre>
 *   安全闸 mayTransmit  →  仲裁 requestNet  →  安全闸 open
 *       →  帧泵 run  →  安全闸 close  →  仲裁 release
 * </pre>
 *
 * <p>安全闸在仲裁**之前**：没授权就根本不该去争用射频。安全闸的 open
 * 在仲裁之后：占空比要从真正开始发射算起，不是从请求算起。
 */
public final class GatewaySession {

    /** 一次发射的结果。失败时 {@code pump} 可能为空。 */
    public static final class Result {
        public final boolean started;
        public final String why;
        public final FramePump.Result pump;

        Result(boolean started, String why, FramePump.Result pump) {
            this.started = started;
            this.why = why;
            this.pump = pump;
        }

        public String report() {
            if (!started) {
                return "未发射：" + why;
            }
            return String.format(Locale.US, "已发射：%s", pump.report());
        }
    }

    private final RadioService service;
    private final RfSafetyGate gate;
    private final ModuleTransport transport;

    public GatewaySession(RadioService service, RfSafetyGate gate,
            ModuleTransport transport) {
        if (service == null || gate == null || transport == null) {
            throw new IllegalArgumentException("服务、安全闸、传输都不能为空");
        }
        this.service = service;
        this.gate = gate;
        this.transport = transport;
    }

    /**
     * 跑一次网络来话发射。
     *
     * @param units     目标单元数
     * @param plannedMs 预计发射时长，交给安全闸做占空比预判
     */
    public Result runNetTransmit(int units, long plannedMs) throws Exception {
        RadioService.Channel channel = service.channel();
        if (channel == null) {
            return new Result(false, "拒绝：信道未配置", null);
        }
        long now = transport.nowMs();

        PowerTable.Result power = service.powerCode() == null
                ? PowerTable.Result.refuse("功率未设定")
                : service.power().wattsForCode(service.powerCode());
        RadioService.Outcome allowed =
                gate.mayTransmit(channel.freqHz, power, now, plannedMs);
        if (!allowed.ok) {
            return new Result(false, allowed.why, null);
        }

        RadioService.Outcome started =
                service.startTx(RadioService.Source.NET, now);
        if (!started.ok) {
            return new Result(false, started.why, null);
        }

        gate.open(transport.nowMs());
        FramePump.Result pump;
        try {
            pump = new FramePump(transport, service.buffer())
                    .run(units, transport.nowMs() + plannedMs + 2000L);
        } finally {
            // 无论帧泵怎么结束——写满、到时限、链路断、还是抛异常——
            // 射频记账与仲裁都必须落回去。这一段放 finally 不是防御性编程，
            // 是因为漏掉它的后果是"发射没停下来"。
            gate.close(transport.nowMs());
            service.stopTx(transport.nowMs());
        }
        return new Result(true, started.why, pump);
    }
}
