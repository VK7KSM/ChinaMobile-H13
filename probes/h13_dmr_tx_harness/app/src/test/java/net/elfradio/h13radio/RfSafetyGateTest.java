package net.elfradio.h13radio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 射频安全闸的验收用例。
 *
 * <p>每一条都在问同一个问题：**这种情况下会不会把射频打开**。
 * 安全闸写反的代价与其它组件不同——其它组件写错是功能不对，这里写错
 * 是在无人在场时发射。因此用例以「该拒绝而没拒绝」为主。
 */
public class RfSafetyGateTest {

    private static PowerTable.Result calibratedPower() {
        return new PowerTable().wattsForCode(2030);   // 唯一的标定点
    }

    /**
     * 授权时长取最大值：占空比用例的时间轴会推进到十分钟量级，
     * 用短授权会让它们在「授权过期」上先拒掉，于是**断言因为错误的
     * 理由通过**——这正是本文件第一次跑时踩到的（一条 assertTrue 失败
     * 暴露了它，另一条 assertFalse 则悄悄地以错误理由通过了）。
     */
    private static RfSafetyGate armed(long nowMs) {
        RfSafetyGate g = new RfSafetyGate();
        assertTrue(g.grant("VK7KSM", nowMs, RfSafetyGate.MAX_GRANT_MS).ok);
        g.allowBand(430_000_000L, 440_000_000L);
        g.noteCallsignSent(nowMs);
        return g;
    }

    @Test
    public void defaultIsRefuse() {
        RfSafetyGate g = new RfSafetyGate();
        RadioService.Outcome r =
                g.mayTransmit(433_550_000L, calibratedPower(), 0, 1000);
        assertFalse("默认必须禁发", r.ok);
        assertTrue(r.why.contains("未授权"));
    }

    @Test
    public void grantRequiresCallsignAndSaneDuration() {
        RfSafetyGate g = new RfSafetyGate();
        assertFalse("无呼号不得授权", g.grant("", 0, 1000).ok);
        assertFalse("无呼号不得授权", g.grant(null, 0, 1000).ok);
        assertFalse("零时长不得授权", g.grant("VK7KSM", 0, 0).ok);
        assertFalse("超长授权等于没有授权",
                g.grant("VK7KSM", 0, RfSafetyGate.MAX_GRANT_MS + 1).ok);
        assertTrue(g.grant("VK7KSM", 0, RfSafetyGate.MAX_GRANT_MS).ok);
    }

    @Test
    public void grantExpires() {
        // 这条要的就是短授权，不能用 armed()——它给的是最长授权。
        RfSafetyGate g = new RfSafetyGate();
        assertTrue(g.grant("VK7KSM", 0, 60_000).ok);
        g.allowBand(430_000_000L, 440_000_000L);
        g.noteCallsignSent(0);
        assertTrue(g.mayTransmit(433_550_000L, calibratedPower(), 1000, 100).ok);
        assertFalse("授权到期后必须拒绝",
                g.mayTransmit(433_550_000L, calibratedPower(), 60_000, 100).ok);
    }

    @Test
    public void revokeTakesEffectImmediately() {
        RfSafetyGate g = armed(0);
        assertTrue(g.mayTransmit(433_550_000L, calibratedPower(), 1000, 100).ok);
        g.revoke();
        assertFalse(g.mayTransmit(433_550_000L, calibratedPower(), 1001, 100).ok);
    }

    @Test
    public void bandMustBeSetAndRespected() {
        RfSafetyGate g = new RfSafetyGate();
        assertTrue(g.grant("VK7KSM", 0, 60_000).ok);
        g.noteCallsignSent(0);
        assertFalse("未设频段一律拒绝",
                g.mayTransmit(433_550_000L, calibratedPower(), 100, 100).ok);
        g.allowBand(430_000_000L, 440_000_000L);
        assertTrue(g.mayTransmit(433_550_000L, calibratedPower(), 100, 100).ok);
        assertFalse("频段外拒绝",
                g.mayTransmit(145_000_000L, calibratedPower(), 100, 100).ok);
        assertFalse("频段外拒绝（高侧）",
                g.mayTransmit(440_000_001L, calibratedPower(), 100, 100).ok);
    }

    @Test
    public void uncalibratedPowerIsRefused() {
        RfSafetyGate g = armed(0);
        PowerTable p = new PowerTable();
        assertFalse("单点标定求解会被拒，这里必须跟着拒",
                g.mayTransmit(433_550_000L, p.codeForWatts(1.0), 100, 100).ok);
        assertFalse("功率结果为空也拒绝",
                g.mayTransmit(433_550_000L, null, 100, 100).ok);
    }

    @Test
    public void callsignMustBeSentBeforeAndPeriodically() {
        RfSafetyGate g = new RfSafetyGate();
        assertTrue(g.grant("VK7KSM", 0, RfSafetyGate.MAX_GRANT_MS).ok);
        g.allowBand(430_000_000L, 440_000_000L);
        assertTrue("授权后第一次发射前呼号就该播", g.callsignDue(0));
        assertFalse("没播呼号不得发射",
                g.mayTransmit(433_550_000L, calibratedPower(), 0, 100).ok);
        g.noteCallsignSent(0);
        assertTrue(g.mayTransmit(433_550_000L, calibratedPower(), 100, 100).ok);
        long late = RfSafetyGate.CALLSIGN_INTERVAL_MS;
        assertTrue("过了间隔就该再播", g.callsignDue(late));
        assertFalse("超期未播必须拒绝",
                g.mayTransmit(433_550_000L, calibratedPower(), late, 100).ok);
    }

    @Test
    public void dutyCycleIsEnforcedIncludingTheBurstInFlight() {
        RfSafetyGate g = armed(0);
        long window = RfSafetyGate.DUTY_WINDOW_MS;
        long budget = (long) (window * RfSafetyGate.MAX_DUTY);

        // 先发掉预算的八成
        g.open(0);
        g.close((long) (budget * 0.8));
        long now = (long) (budget * 0.8);
        assertEquals(0.8 * RfSafetyGate.MAX_DUTY, g.duty(now), 1e-9);

        assertTrue("剩余预算内允许",
                g.mayTransmit(433_550_000L, calibratedPower(), now,
                        (long) (budget * 0.1)).ok);
        RadioService.Outcome over = g.mayTransmit(433_550_000L,
                calibratedPower(), now, (long) (budget * 0.5));
        assertFalse("超出预算必须拒绝", over.ok);
        assertTrue(over.why.contains("占空比"));
    }

    @Test
    public void inFlightBurstCountsTowardDuty() {
        RfSafetyGate g = armed(0);
        g.open(0);
        assertTrue(g.transmitting());
        long half = (long) (RfSafetyGate.DUTY_WINDOW_MS
                * RfSafetyGate.MAX_DUTY * 0.5);
        // 还没 close，正在发的这一段也必须算进占空
        assertEquals(RfSafetyGate.MAX_DUTY * 0.5, g.duty(half), 1e-9);
        RadioService.Outcome r = g.mayTransmit(433_550_000L,
                calibratedPower(), half, (long) (half * 1.2));
        assertFalse("正在发的部分若不计入，这里会误判为允许", r.ok);
        assertTrue("必须是因为占空比被拒，而不是别的理由",
                r.why.contains("占空比"));
    }

    @Test
    public void oldBurstsLeaveTheWindow() {
        RfSafetyGate g = armed(0);
        long budget = (long) (RfSafetyGate.DUTY_WINDOW_MS
                * RfSafetyGate.MAX_DUTY);
        g.open(0);
        g.close(budget);
        long far = RfSafetyGate.DUTY_WINDOW_MS + budget + 1;
        assertEquals("旧的发射滑出窗口后占空归零", 0.0, g.duty(far), 1e-9);
    }

    @Test
    public void closeWithoutOpenIsHarmless() {
        RfSafetyGate g = armed(0);
        g.close(1000);
        assertFalse(g.transmitting());
        assertEquals(0.0, g.duty(1000), 1e-9);
    }
}
