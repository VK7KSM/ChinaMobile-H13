package net.elfradio.h13radio;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

/**
 * 全链路拼装用例：网络来的一包语音，一路走到"模块"手里，中途不走样。
 *
 * <pre>
 *   假 master ──DMRD──▶ HomebrewClient ──▶ DmrVoiceBurst 拆 ──▶
 *       JitterBuffer ──▶ FramePump ──▶ 假 ModuleTransport
 * </pre>
 *
 * <p><b>这条链上缺一环</b>：真实网络送来的是**已做信道编码**的语音，
 * 而模块要的是**未编码的 49 位参数**（2.8.83）。中间那次"剥信道编码"
 * 在设备上由 native 解码器完成（2.9.53 已验证它与离线工具逐字节一致），
 * 纯 Java 侧没有实现，因此**本用例里不包含那一步**——用例送什么进去就
 * 期望什么出来。
 *
 * <p>说清楚这一点，是为了以后没人误以为"全链路测过了"就等于"接上网络
 * 就能出声"。缺的那一环在设备上，不在这里。
 */
public class GatewayPipelineTest {

    private static final int REPEATER = 1234567;

    private static final class FakeLink implements HomebrewClient.PacketLink {
        final List<byte[]> sent = new ArrayList<>();
        long now;

        @Override
        public void send(byte[] packet) {
            sent.add(packet.clone());
        }

        @Override
        public byte[] receive(long timeoutMs) {
            return null;
        }

        @Override
        public long nowMs() {
            return now;
        }
    }

    private static final class FakeModule implements ModuleTransport {
        private final int offers;
        private int served;
        private long now;
        final List<byte[]> written = new ArrayList<>();

        FakeModule(int offers) {
            this.offers = offers;
        }

        @Override
        public boolean awaitOffer(long timeoutMs) {
            if (served >= offers) {
                now += timeoutMs;
                return false;
            }
            now += 60;
            served++;
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

    private static byte[] ack(byte[] tail) {
        byte[] t = "RPTACK".getBytes(StandardCharsets.US_ASCII);
        byte[] out = Arrays.copyOf(t, t.length + tail.length);
        System.arraycopy(tail, 0, out, t.length, tail.length);
        return out;
    }

    private static HomebrewClient online(FakeLink link) throws Exception {
        HomebrewClient c = new HomebrewClient(link, REPEATER, new byte[302]);
        c.connect();
        c.onPacket(ack(new byte[] {1, 2, 3, 4}),
                HomebrewClient.passphraseBytes("pw"));
        c.onPacket(ack(new byte[4]), null);
        c.onPacket(ack(new byte[4]), null);
        assertEquals(HomebrewClient.State.ONLINE, c.state());
        return c;
    }

    private static byte[] unit(int seed) {
        byte[] u = new byte[DmrVoiceBurst.UNIT_BYTES];
        for (int i = 0; i < u.length; i++) {
            u[i] = (byte) (seed * 31 + i);
        }
        return u;
    }

    /** 网络来三包语音，模块手里应当原样收到那三个单元。 */
    @Test
    public void networkVoiceReachesTheModuleUnchanged() throws Exception {
        FakeLink link = new FakeLink();
        HomebrewClient client = online(link);

        List<byte[]> origin = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            byte[] u = unit(i + 1);
            origin.add(u);
            byte[] burst = DmrVoiceBurst.toBurst(u, null);
            client.onPacket(HomebrewPacket.encodeDmrd(i, 1701, 99, REPEATER,
                    0, false, 0, 0, 0x1234, burst), null);
        }

        RadioService service = new RadioService();
        assertTrue(service.setChannel(433_550_000L, 8, 0, 99, false).ok);
        service.subscribe(99);
        for (HomebrewPacket.Dmrd d : client.drainInbox()) {
            assertTrue("未订阅的 TG 不该进来",
                    service.onRxFrame(d.sourceId, d.destinationId,
                            d.streamId, d.slot, 0, 0));
            service.pushNetFrame(DmrVoiceBurst.toUnit(d.payload));
        }

        FakeModule module = new FakeModule(3);
        FramePump.Result r =
                new FramePump(module, service.buffer()).run(3, 100_000);
        assertTrue(r.report(), r.contractHeld());
        assertEquals(3, module.written.size());
        for (int i = 0; i < 3; i++) {
            assertArrayEquals("第 " + i + " 单元在链路上走样了",
                    origin.get(i), module.written.get(i));
        }
    }

    /** 缓冲深度三帧，网络一次灌进来五包，最旧的两包该被丢掉。 */
    @Test
    public void burstOfNetworkTrafficDropsOldestNotNewest() throws Exception {
        FakeLink link = new FakeLink();
        HomebrewClient client = online(link);
        for (int i = 0; i < 5; i++) {
            client.onPacket(HomebrewPacket.encodeDmrd(i, 1701, 99, REPEATER,
                    0, false, 0, 0, 1,
                    DmrVoiceBurst.toBurst(unit(i + 1), null)), null);
        }
        RadioService service = new RadioService();
        for (HomebrewPacket.Dmrd d : client.drainInbox()) {
            service.pushNetFrame(DmrVoiceBurst.toUnit(d.payload));
        }
        assertEquals("默认深度三帧", 2, service.buffer().drops());

        FakeModule module = new FakeModule(3);
        new FramePump(module, service.buffer()).run(3, 100_000);
        assertArrayEquals("保新不保旧：队首应是第三包",
                unit(3), module.written.get(0));
    }

    /** 网络断掉之后不再有新帧，模块仍然每次交帧都拿到一帧——补静音。 */
    @Test
    public void networkStallBecomesSilenceNotAStall() throws Exception {
        RadioService service = new RadioService();
        service.pushNetFrame(unit(1));
        FakeModule module = new FakeModule(4);
        FramePump.Result r =
                new FramePump(module, service.buffer()).run(4, 100_000);
        assertEquals(4, module.written.size());
        assertEquals(3, r.underruns);
        assertArrayEquals(unit(1), module.written.get(0));
        for (int i = 1; i < 4; i++) {
            for (byte b : module.written.get(i)) {
                assertEquals("网络断了也不许断流", 0, b);
            }
        }
    }

    /** 链路没在线时，网络侧的语音一包都进不来。 */
    @Test
    public void offlineLinkDeliversNothingDownstream() throws Exception {
        FakeLink link = new FakeLink();
        HomebrewClient client = new HomebrewClient(link, REPEATER,
                new byte[302]);
        client.connect();                       // 停在握手中
        client.onPacket(HomebrewPacket.encodeDmrd(0, 1701, 99, REPEATER, 0,
                false, 0, 0, 1, DmrVoiceBurst.toBurst(unit(1), null)), null);
        assertTrue(client.drainInbox().isEmpty());

        RadioService service = new RadioService();
        FakeModule module = new FakeModule(2);
        FramePump.Result r =
                new FramePump(module, service.buffer()).run(2, 100_000);
        assertEquals("没有网络帧就全是静音", 2, r.underruns);
        assertFalse(module.written.isEmpty());
    }
}
