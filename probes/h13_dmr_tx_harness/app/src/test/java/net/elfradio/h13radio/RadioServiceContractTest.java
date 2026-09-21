package net.elfradio.h13radio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 射频服务层的验收用例，与 {@code tools/offline/} 下的同名模型逐条对照。
 *
 * <p>为什么两边都要有：模型先把行为定下来（能快速改、能当文档读），
 * Java 实现要真正跑在设备上。**同一组用例跑两边**，行为一旦分叉就会被抓住；
 * 只留一边，迟早会出现"模型说该拒绝、设备上却放行"这种最难查的偏差。
 *
 * <p>用例的重点全在**拒绝**：主流程写对不难，难的是边界什么时候说不。
 */
public class RadioServiceContractTest {

    // ---- 仲裁：与 arbitration_model.py 的五条规则一一对应 ----

    @Test
    public void rxPreemptsTxAndBlocksNewTx() {
        Arbiter a = new Arbiter();
        assertTrue(a.requestLocal(0));
        a.onRxStart(100);
        assertEquals("发射中收到空口来话应让位", Arbiter.State.RX, a.state());
        assertFalse("接收中不得发射", a.requestLocal(200));
    }

    @Test
    public void localPreemptsNet() {
        Arbiter a = new Arbiter();
        assertTrue(a.requestNet(0));
        assertEquals(Arbiter.State.TX_NET, a.state());
        assertTrue("本地 PTT 抢占网络", a.requestLocal(100));
        assertEquals(Arbiter.State.TX_LOCAL, a.state());
    }

    @Test
    public void netDuringTxIsQueuedAndResumesAfterRx() {
        Arbiter a = new Arbiter();
        assertTrue(a.requestLocal(0));
        assertFalse("发射中网络来话入队", a.requestNet(10));
        assertTrue(a.queuedNet());
        a.release(20);
        assertEquals(Arbiter.State.IDLE, a.state());

        // 接收结束时排队的网络来话应自动续上
        Arbiter b = new Arbiter();
        b.onRxStart(0);
        assertFalse(b.requestNet(10));
        b.onRxEnd(1000);
        assertEquals("接收结束后排队的网络来话接上",
                Arbiter.State.TX_NET, b.state());
    }

    @Test
    public void txHasHardDeadline() {
        Arbiter a = new Arbiter();
        assertTrue(a.requestLocal(0));
        a.tick(Arbiter.MAX_TX_MS - 1);
        assertEquals("未到时限不应落回", Arbiter.State.TX_LOCAL, a.state());
        a.tick(Arbiter.MAX_TX_MS);
        assertEquals("到时强制回空闲", Arbiter.State.IDLE, a.state());
    }

    @Test
    public void hangTimeRejectsNewTx() {
        Arbiter a = new Arbiter();
        assertTrue(a.requestLocal(0));
        a.release(100);
        assertFalse("尾音期内拒绝新发射", a.requestLocal(200));
        assertTrue("尾音期后可发射", a.requestLocal(100 + Arbiter.HANG_MS));
    }

    // ---- 抖动缓冲：与 jitter_buffer_model.py 对应 ----

    @Test
    public void bufferNeverStarvesAndDropsOldestWhenFull() {
        JitterBuffer b = new JitterBuffer(3);
        for (int i = 0; i < 5; i++) {
            byte[] f = new byte[JitterBuffer.FRAME_BYTES];
            f[0] = (byte) i;
            b.push(f);
        }
        assertEquals("深度三帧，过载丢最旧的两帧", 2, b.drops());
        assertEquals(3, b.size());
        // 保新不保旧：队首应是第 2 帧而不是第 0 帧
        assertEquals(2, b.pop()[0]);
        b.pop();
        b.pop();
        byte[] silence = b.pop();
        assertEquals("空了必须补静音，不能断流",
                JitterBuffer.FRAME_BYTES, silence.length);
        for (byte v : silence) {
            assertEquals(0, v);
        }
        assertEquals(1, b.underruns());
        assertEquals(4, b.delivered());
    }

    @Test
    public void bufferCopiesCallerFrames() {
        JitterBuffer b = new JitterBuffer();
        byte[] f = new byte[JitterBuffer.FRAME_BYTES];
        f[0] = 0x5a;
        b.push(f);
        f[0] = 0;                       // 调用方改回去，缓冲里的不该跟着变
        assertEquals(0x5a, b.pop()[0] & 0xff);
    }

    // ---- 功率：标定不足必须拒绝而非外推 ----

    @Test
    public void powerRefusesExtrapolationWithOnePoint() {
        PowerTable p = new PowerTable();
        assertEquals("目前只有一个标定点", 1, p.points().size());
        assertTrue("标定点本身可回读", p.wattsForCode(2030).ok);
        assertFalse("单点不得插值", p.wattsForCode(2000).ok);
        assertFalse("单点不得求解", p.codeForWatts(1.0).ok);
        assertFalse("超安全上限必须拒绝", p.codeForWatts(99.0).ok);
        assertFalse("功率须为正", p.codeForWatts(0).ok);
    }

    @Test
    public void powerInterpolatesOnlyInsideCalibratedRange() {
        PowerTable p = new PowerTable();
        p.addPoint(1000, 0.20, "假想第二点，仅用于验证插值与拒绝外推");
        PowerTable.Result mid = p.codeForWatts(0.675);
        assertTrue(mid.ok);
        assertEquals("区间中点应落在两码值中间", 1515, mid.code);
        assertFalse("低于标定下限仍须拒绝", p.codeForWatts(0.1).ok);
        assertFalse("高于标定上限仍须拒绝", p.codeForWatts(2.0).ok);
        assertFalse("码值超范围仍须拒绝", p.wattsForCode(3000).ok);
    }

    // ---- TG 路由与 Last Heard ----

    @Test
    public void unsubscribedTalkgroupIsDropped() {
        TalkgroupRouter r = new TalkgroupRouter();
        r.addStatic(99);
        assertTrue("已订阅放行", r.onFrame(1701, 99, 1, 0, 5000, 0));
        assertFalse("未订阅丢弃", r.onFrame(1701, 505, 1, 0, 5000, 0));
    }

    @Test
    public void dynamicSubscriptionExpires() {
        TalkgroupRouter r = new TalkgroupRouter();
        r.onLocalTransmit(505, 0);
        assertTrue("发过射即动态订阅", r.allows(505, 1000));
        assertFalse("十五分钟无活动即失效",
                r.allows(505, 1000 + TalkgroupRouter.DYNAMIC_TTL_MS));
    }

    @Test
    public void framesAggregateIntoCalls() {
        TalkgroupRouter r = new TalkgroupRouter();
        r.addStatic(99);
        for (int i = 0; i < 10; i++) {
            assertTrue(r.onFrame(1701, 99, 1, 0, 1000 + i * 60L, i % 3 == 0 ? 1 : 0));
        }
        assertEquals(1, r.activeCalls());
        r.flush(1000 + 10 * 60L + TalkgroupRouter.CALL_GAP_MS + 1);
        assertEquals(1, r.lastHeard().size());
        TalkgroupRouter.Call c = r.lastHeard().get(0);
        assertEquals(10, c.frames);
        assertEquals("每三帧记一次误码", 4, c.errors);
        assertEquals(540, c.durationMs());
    }

    // ---- 服务接缝：契约里的拒绝时机 ----

    @Test
    public void encryptedChannelIsAlwaysRefused() {
        RadioService s = new RadioService();
        RadioService.Outcome bad =
                s.setChannel(433_550_000L, 8, 0, 99, true);
        assertFalse(bad.ok);
        assertTrue(bad.why.contains("明文"));
        assertTrue(s.setChannel(433_550_000L, 8, 0, 99, false).ok);
    }

    @Test
    public void channelValidatesColorCodeAndSlot() {
        RadioService s = new RadioService();
        assertFalse(s.setChannel(433_550_000L, 16, 0, 99, false).ok);
        assertFalse(s.setChannel(433_550_000L, 8, 2, 99, false).ok);
    }

    @Test
    public void txRefusedWithoutChannel() {
        RadioService s = new RadioService();
        RadioService.Outcome r = s.startTx(RadioService.Source.LOCAL, 0);
        assertFalse(r.ok);
        assertTrue(r.why.contains("信道未配置"));
    }

    @Test
    public void txRefusedWhileReceiving() {
        RadioService s = new RadioService();
        assertTrue(s.setChannel(433_550_000L, 8, 0, 99, false).ok);
        s.onRxStart(1000);
        assertFalse("接收中不得发射",
                s.startTx(RadioService.Source.LOCAL, 1100).ok);
        s.onRxEnd(2000);
        assertTrue("接收结束后可发射",
                s.startTx(RadioService.Source.LOCAL, 3000).ok);
    }

    /**
     * 供数契约：模块交几帧就必须回几帧。这一条是 2.8.75 用一次失败发射
     * 换来的——按回执等待会把串口往返串进时间轴，68 单元被拉到 10.4 秒，
     * 而音频只有 4.08 秒，对端完全无声。
     */
    @Test
    public void everyOfferGetsExactlyOneFrame() {
        RadioService s = new RadioService();
        assertTrue(s.setChannel(433_550_000L, 8, 0, 99, false).ok);
        assertTrue(s.startTx(RadioService.Source.LOCAL, 3000).ok);
        for (int i = 0; i < 3; i++) {
            byte[] f = new byte[JitterBuffer.FRAME_BYTES];
            f[0] = (byte) i;
            s.pushNetFrame(f);
        }
        int returned = 0;
        for (int i = 0; i < 10; i++) {
            assertEquals(JitterBuffer.FRAME_BYTES, s.onModuleOffer().length);
            returned++;
        }
        assertEquals("交 10 帧回 10 帧", 10, returned);
        assertEquals("缺的 7 帧补静音", 7, s.buffer().underruns());
        s.stopTx(4000);
    }

    @Test
    public void statusIsAlwaysRenderable() {
        RadioService s = new RadioService();
        assertTrue(s.status().contains("未设"));
        assertTrue(s.setChannel(433_550_000L, 8, 0, 99, false).ok);
        s.subscribe(99);
        assertTrue(s.status().contains("已设"));
    }
}
