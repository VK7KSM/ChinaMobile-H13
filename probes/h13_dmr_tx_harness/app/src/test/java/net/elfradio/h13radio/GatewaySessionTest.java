package net.elfradio.h13radio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

/**
 * 组件接缝的验收用例。
 *
 * <p>各组件自己的行为已有用例，这里只问接缝上的事：顺序对不对、
 * 失败时有没有把已经打开的东西关回去、**射频记账有没有漏**。
 * 漏掉最后一条的后果是"发射没停下来"，在真机上代价最大。
 */
public class GatewaySessionTest {

    private static final class FakeTransport implements ModuleTransport {
        private final int offerCount;
        private int served;
        private long now;
        final List<byte[]> written = new ArrayList<>();
        boolean failOnWrite;

        FakeTransport(int offerCount) {
            this.offerCount = offerCount;
        }

        @Override
        public boolean awaitOffer(long timeoutMs) {
            if (served >= offerCount) {
                now += timeoutMs;
                return false;
            }
            now += 60;
            served++;
            return true;
        }

        @Override
        public void writeUnit(byte[] unit) throws Exception {
            if (failOnWrite) {
                throw new IllegalStateException("写出失败（用例制造的故障）");
            }
            written.add(unit.clone());
        }

        @Override
        public long nowMs() {
            return now;
        }

        /** 让用例能把时钟往前推，用于跨会话的尾音期与占空比。 */
        void advance(long ms) {
            now += ms;
        }

        void setNow(long ms) {
            now = ms;
        }
    }

    private static RadioService configuredService() {
        RadioService s = new RadioService();
        assertTrue(s.setChannel(433_550_000L, 8, 0, 99, false).ok);
        s.subscribe(99);
        return s;
    }

    /** 功率只有一个标定点，所以正常途径设不进去；这里直连标定码。 */
    private static void useCalibratedCode(RadioService s) throws Exception {
        java.lang.reflect.Field f =
                RadioService.class.getDeclaredField("powerCode");
        f.setAccessible(true);
        f.set(s, 2030);
    }

    private static RfSafetyGate armedGate() {
        RfSafetyGate g = new RfSafetyGate();
        assertTrue(g.grant("VK7KSM", 0, RfSafetyGate.MAX_GRANT_MS).ok);
        g.allowBand(430_000_000L, 440_000_000L);
        g.noteCallsignSent(0);
        return g;
    }

    @Test
    public void happyPathTransmitsAndClosesEverything() throws Exception {
        RadioService s = configuredService();
        useCalibratedCode(s);
        for (int i = 0; i < 5; i++) {
            byte[] f = new byte[JitterBuffer.UNIT_BYTES];
            f[0] = (byte) (i + 1);
            s.pushNetFrame(f);
        }
        RfSafetyGate g = armedGate();
        FakeTransport t = new FakeTransport(5);

        GatewaySession.Result r =
                new GatewaySession(s, g, t).runNetTransmit(5, 1000);
        assertTrue(r.report(), r.started);
        assertEquals(5, r.pump.written);
        assertTrue(r.pump.contractHeld());
        assertFalse("发完必须关记账", g.transmitting());
        assertEquals("发完必须回空闲", Arbiter.State.IDLE, s.arbiter().state());
        assertEquals(null, s.txSource());
    }

    @Test
    public void unauthorizedNeverReachesArbiterOrTransport() throws Exception {
        RadioService s = configuredService();
        useCalibratedCode(s);
        RfSafetyGate g = new RfSafetyGate();     // 没授权
        g.allowBand(430_000_000L, 440_000_000L);
        FakeTransport t = new FakeTransport(5);

        GatewaySession.Result r =
                new GatewaySession(s, g, t).runNetTransmit(5, 1000);
        assertFalse(r.started);
        assertTrue(r.why.contains("未授权"));
        assertTrue("一个字节都不该写出去", t.written.isEmpty());
        assertEquals("仲裁状态不该被碰", Arbiter.State.IDLE,
                s.arbiter().state());
        assertFalse(g.transmitting());
    }

    @Test
    public void uncalibratedPowerBlocksTransmit() throws Exception {
        RadioService s = configuredService();   // 不设功率码
        RfSafetyGate g = armedGate();
        FakeTransport t = new FakeTransport(5);

        GatewaySession.Result r =
                new GatewaySession(s, g, t).runNetTransmit(5, 1000);
        assertFalse(r.started);
        assertTrue(r.why.contains("功率"));
        assertTrue(t.written.isEmpty());
    }

    @Test
    public void receivingBlocksNetTransmit() throws Exception {
        RadioService s = configuredService();
        useCalibratedCode(s);
        RfSafetyGate g = armedGate();
        FakeTransport t = new FakeTransport(5);
        s.onRxStart(0);                          // 空口来话中

        GatewaySession.Result r =
                new GatewaySession(s, g, t).runNetTransmit(5, 1000);
        assertFalse(r.started);
        assertTrue(r.why.contains("仲裁"));
        assertTrue(t.written.isEmpty());
        assertFalse("被仲裁挡住时不得留下射频记账", g.transmitting());
    }

    @Test
    public void unconfiguredChannelIsRefusedBeforeAnythingElse()
            throws Exception {
        RadioService s = new RadioService();     // 没设信道
        RfSafetyGate g = armedGate();
        FakeTransport t = new FakeTransport(5);

        GatewaySession.Result r =
                new GatewaySession(s, g, t).runNetTransmit(5, 1000);
        assertFalse(r.started);
        assertTrue(r.why.contains("信道未配置"));
    }

    /**
     * 最要紧的一条：**帧泵抛异常时，射频记账与仲裁仍然必须落回去**。
     * 漏掉这一步的后果不是数据不准，是发射没停下来。
     */
    @Test
    public void transportFailureStillClosesRf() throws Exception {
        RadioService s = configuredService();
        useCalibratedCode(s);
        for (int i = 0; i < 5; i++) {
            s.pushNetFrame(new byte[JitterBuffer.UNIT_BYTES]);
        }
        RfSafetyGate g = armedGate();
        FakeTransport t = new FakeTransport(5);
        t.failOnWrite = true;

        boolean threw = false;
        try {
            new GatewaySession(s, g, t).runNetTransmit(5, 1000);
        } catch (IllegalStateException expected) {
            threw = true;
        }
        assertTrue("用例制造的写出故障应当向上抛", threw);
        assertFalse("抛异常也必须关掉射频记账", g.transmitting());
        assertEquals("抛异常也必须回空闲", Arbiter.State.IDLE,
                s.arbiter().state());
    }

    /**
     * 背靠背的网络来话会被**尾音期**挡住——这不是缺陷，是仲裁规则五
     * 在起作用（松键后 {@link Arbiter#HANG_MS} 内不接受新发射）。
     *
     * <p>写这条用例时先假设两次发射可以连着跑，第二轮直接被拒，才意识到
     * 热点要处理这件事：网络上一通话刚结束、下一通话紧接着来，网关必须
     * 等过尾音期，否则那一通就被自己挡掉了。**这是接缝用例才问得出来的
     * 问题**，各组件单独测都看不见。
     */
    @Test
    public void backToBackNetCallsMustWaitOutHangTime() throws Exception {
        RadioService s = configuredService();
        useCalibratedCode(s);
        RfSafetyGate g = armedGate();

        for (int i = 0; i < 5; i++) {
            s.pushNetFrame(new byte[JitterBuffer.UNIT_BYTES]);
        }
        FakeTransport first = new FakeTransport(5);
        assertTrue(new GatewaySession(s, g, first)
                .runNetTransmit(5, 1000).started);
        long afterFirst = first.nowMs();

        // 紧接着来第二通：尾音期未过，必须被拒
        FakeTransport tooSoon = new FakeTransport(5);
        tooSoon.setNow(afterFirst);
        for (int i = 0; i < 5; i++) {
            s.pushNetFrame(new byte[JitterBuffer.UNIT_BYTES]);
        }
        GatewaySession.Result refused =
                new GatewaySession(s, g, tooSoon).runNetTransmit(5, 1000);
        assertFalse("尾音期内的第二通必须被拒", refused.started);
        assertTrue(refused.why.contains("仲裁"));

        // 等过尾音期再来，就该放行
        FakeTransport later = new FakeTransport(5);
        later.setNow(afterFirst + Arbiter.HANG_MS);
        GatewaySession.Result ok =
                new GatewaySession(s, g, later).runNetTransmit(5, 1000);
        assertTrue(ok.report(), ok.started);
        assertTrue("两次发射的占空比要累计", g.duty(later.nowMs()) > 0);
    }
}
