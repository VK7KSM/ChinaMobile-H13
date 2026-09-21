package net.elfradio.h13radio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

/**
 * 帧泵验收用例。
 *
 * <p>假传输把时间轴捏在手里：交帧按给定的时刻发生，读取不真的阻塞。
 * 这样「间隔 60 毫秒」「迟到的交帧」「交帧断掉」都能确定性复现，
 * 不用靠真机和运气。
 */
public class FramePumpTest {

    /** 按预定时刻交帧的假传输。 */
    private static final class FakeTransport implements ModuleTransport {
        private final long[] offerAtMs;
        private int next;
        private long now;
        final List<byte[]> written = new ArrayList<>();

        FakeTransport(long[] offerAtMs) {
            this.offerAtMs = offerAtMs;
        }

        @Override
        public boolean awaitOffer(long timeoutMs) {
            if (next >= offerAtMs.length) {
                now += timeoutMs;              // 没有更多交帧了，等满超时
                return false;
            }
            long due = offerAtMs[next];
            if (due - now > timeoutMs) {
                now += timeoutMs;              // 下一次交帧比超时还远
                return false;
            }
            now = Math.max(now, due);
            next++;
            return true;
        }

        @Override
        public void writeUnit(byte[] unit) {
            written.add(unit.clone());
        }

        @Override
        public long nowMs() {
            return now;
        }
    }

    private static long[] steady(int count, long stepMs) {
        long[] t = new long[count];
        for (int i = 0; i < count; i++) {
            t[i] = i * stepMs;
        }
        return t;
    }

    private static void fill(JitterBuffer b, int frames) {
        for (int i = 0; i < frames; i++) {
            byte[] f = new byte[JitterBuffer.FRAME_BYTES];
            f[0] = (byte) (i + 1);            // 非零，好与静音区分
            b.push(f);
        }
    }

    @Test
    public void oneOfferGetsExactlyOneUnit() throws Exception {
        FakeTransport t = new FakeTransport(steady(10, 60));
        JitterBuffer b = new JitterBuffer(16);
        fill(b, 10);
        FramePump.Result r = new FramePump(t, b).run(10, 100_000);
        assertEquals("交十帧", 10, r.offers);
        assertEquals("回十帧", 10, r.written);
        assertEquals(10, t.written.size());
        assertEquals("无许可写出必须为 0", 0, r.writesWithoutPermit);
        assertTrue("一对一成立", r.contractHeld());
        assertEquals("间隔应贴合空口帧率", 60, r.medianIntervalMs());
        assertEquals("已写满", r.stopReason);
    }

    @Test
    public void emptyBufferYieldsSilenceNotAStall() throws Exception {
        FakeTransport t = new FakeTransport(steady(6, 60));
        JitterBuffer b = new JitterBuffer(16);
        fill(b, 2);                            // 只有两帧，其余要补静音
        FramePump.Result r = new FramePump(t, b).run(6, 100_000);
        assertEquals(6, r.written);
        assertEquals("缺的四帧补静音", 4, r.underruns);
        assertTrue("补静音不影响一对一", r.contractHeld());
        // 前两帧是真数据，后四帧必须是全零静音
        assertEquals(1, t.written.get(0)[0]);
        assertEquals(2, t.written.get(1)[0]);
        for (int i = 2; i < 6; i++) {
            for (byte v : t.written.get(i)) {
                assertEquals("补的必须是静音帧", 0, v);
            }
        }
    }

    /**
     * 最要紧的一条：**等不到交帧不许补写**。
     * 补写出去就是无许可写出，一对一从此不成立，而模块并不会因此多收一帧。
     */
    @Test
    public void timeoutNeverWritesWithoutPermit() throws Exception {
        // 三次交帧之后彻底断掉
        FakeTransport t = new FakeTransport(steady(3, 60));
        JitterBuffer b = new JitterBuffer(16);
        fill(b, 20);
        FramePump.Result r = new FramePump(t, b).run(20, 100_000);
        assertEquals("只交了三帧", 3, r.offers);
        assertEquals("就只能写三帧", 3, r.written);
        assertEquals(3, t.written.size());
        assertEquals("等不到交帧不写", 0, r.writesWithoutPermit);
        assertEquals(FramePump.MAX_CONSECUTIVE_TIMEOUTS, r.offerTimeouts);
        assertTrue(r.stopReason.contains("等不到交帧"));
        // 供数契约与会话完整是两件事：这里一对一**确实成立**（交三写三），
        // 只是链路断了没写满。contractHeld() 只回答前者；写没写满看
        // written 与 stopReason。第一次写这条用例时把两者混为一谈了。
        assertTrue("一对一仍然成立：交三写三", r.contractHeld());
        assertTrue("但没写满，调用方须自己看 written 与停因", r.written < 20);
    }

    @Test
    public void isolatedLateOfferDoesNotAbort() throws Exception {
        // 第三次交帧迟到 150 毫秒，仍在单次超时内，不该中断
        long[] t = {0, 60, 210, 270, 330};
        FakeTransport fake = new FakeTransport(t);
        JitterBuffer b = new JitterBuffer(16);
        fill(b, 8);
        FramePump.Result r = new FramePump(fake, b).run(5, 100_000);
        assertEquals(5, r.written);
        assertTrue(r.contractHeld());
        assertEquals("偶发迟到不该记成超时", 0, r.offerTimeouts);
        assertTrue("迟到应体现在间隔里", r.intervalsMs.contains(150L));
    }

    @Test
    public void deadlineStopsThePump() throws Exception {
        FakeTransport t = new FakeTransport(steady(100, 60));
        JitterBuffer b = new JitterBuffer(16);
        fill(b, 200);
        FramePump.Result r = new FramePump(t, b).run(100, 300);
        assertEquals("到时限", r.stopReason);
        assertTrue("时限内只写得下几帧", r.written <= 6);
        assertEquals("时限内仍须一对一", r.offers, r.written);
    }

    @Test
    public void unlimitedUnitsRunsUntilDeadline() throws Exception {
        FakeTransport t = new FakeTransport(steady(100, 60));
        JitterBuffer b = new JitterBuffer(16);
        fill(b, 200);
        FramePump.Result r = new FramePump(t, b).run(0, 600);
        assertEquals("到时限", r.stopReason);
        assertEquals(r.offers, r.written);
        assertTrue(r.written >= 9 && r.written <= 11);
    }

    @Test
    public void reportIsRenderable() throws Exception {
        FakeTransport t = new FakeTransport(steady(3, 60));
        JitterBuffer b = new JitterBuffer(16);
        fill(b, 3);
        FramePump.Result r = new FramePump(t, b).run(3, 100_000);
        assertTrue(r.report().contains("无许可写出 0"));
        assertTrue(r.report().contains("间隔中位 60"));
    }
}
