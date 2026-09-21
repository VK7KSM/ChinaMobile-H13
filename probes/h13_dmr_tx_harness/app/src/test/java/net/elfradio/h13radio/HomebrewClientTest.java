package net.elfradio.h13radio;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;

import org.junit.Test;

/**
 * Homebrew 链路状态机用例，对着一个假 master 跑完整握手。
 *
 * <p>同样的提醒：这些用例证明状态机**自洽**，不证明与真实 master 兼容。
 * 重点放在拒绝与安全上——什么时候不发语音、口令有没有留副本、
 * 认证被拒后会不会傻等或重试。
 */
public class HomebrewClientTest {

    private static final int REPEATER = 1234567;
    private static final String PASS = "s3cret";

    private static final class FakeLink implements HomebrewClient.PacketLink {
        final List<byte[]> sent = new ArrayList<>();
        final Deque<byte[]> toDeliver = new ArrayDeque<>();
        long now;

        @Override
        public void send(byte[] packet) {
            sent.add(packet.clone());
        }

        @Override
        public byte[] receive(long timeoutMs) {
            return toDeliver.pollFirst();
        }

        @Override
        public long nowMs() {
            return now;
        }

        byte[] lastSent() {
            return sent.get(sent.size() - 1);
        }

        String tagOf(byte[] p) {
            int n = Math.min(p.length, 7);
            String s = new String(Arrays.copyOf(p, n),
                    StandardCharsets.US_ASCII);
            for (String t : new String[] {"RPTPING", "RPTACK", "MSTPONG",
                    "MSTNAK", "RPTCL", "RPTL", "RPTK", "RPTC", "DMRD"}) {
                if (s.startsWith(t)) {
                    return t;
                }
            }
            return s;
        }
    }

    private static byte[] ack(byte[] tail) {
        byte[] t = "RPTACK".getBytes(StandardCharsets.US_ASCII);
        byte[] out = Arrays.copyOf(t, t.length + tail.length);
        System.arraycopy(tail, 0, out, t.length, tail.length);
        return out;
    }

    private static byte[] salt() {
        return new byte[] {0x0a, 0x0b, 0x0c, 0x0d};
    }

    private static HomebrewClient connected(FakeLink link) throws Exception {
        HomebrewClient c = new HomebrewClient(link, REPEATER, new byte[302]);
        c.connect();
        c.onPacket(ack(salt()), HomebrewClient.passphraseBytes(PASS));
        c.onPacket(ack(new byte[4]), null);        // 认证通过
        c.onPacket(ack(new byte[4]), null);        // 配置通过
        assertEquals(HomebrewClient.State.ONLINE, c.state());
        return c;
    }

    @Test
    public void fullHandshakeReachesOnline() throws Exception {
        FakeLink link = new FakeLink();
        HomebrewClient c = new HomebrewClient(link, REPEATER, new byte[302]);

        c.connect();
        assertEquals(HomebrewClient.State.AWAIT_SALT, c.state());
        assertEquals("RPTL", link.tagOf(link.lastSent()));

        c.onPacket(ack(salt()), HomebrewClient.passphraseBytes(PASS));
        assertEquals(HomebrewClient.State.AWAIT_AUTH, c.state());
        assertEquals("RPTK", link.tagOf(link.lastSent()));

        c.onPacket(ack(new byte[4]), null);
        assertEquals(HomebrewClient.State.AWAIT_CONFIG, c.state());
        assertEquals("RPTC", link.tagOf(link.lastSent()));

        c.onPacket(ack(new byte[4]), null);
        assertEquals(HomebrewClient.State.ONLINE, c.state());
    }

    @Test
    public void digestIsSaltThenPassphrase() throws Exception {
        FakeLink link = new FakeLink();
        HomebrewClient c = new HomebrewClient(link, REPEATER, new byte[302]);
        c.connect();
        c.onPacket(ack(salt()), HomebrewClient.passphraseBytes(PASS));

        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        sha.update(salt());
        sha.update(PASS.getBytes(StandardCharsets.US_ASCII));
        byte[] expected = sha.digest();

        byte[] rptk = link.lastSent();
        assertEquals(4 + 4 + 32, rptk.length);
        assertArrayEquals(expected, Arrays.copyOfRange(rptk, 8, 40));
    }

    /** 口令用完必须当场清零，不该在内存里多留一份。 */
    @Test
    public void passphraseIsWipedAfterUse() throws Exception {
        FakeLink link = new FakeLink();
        HomebrewClient c = new HomebrewClient(link, REPEATER, new byte[302]);
        c.connect();
        byte[] pass = HomebrewClient.passphraseBytes(PASS);
        c.onPacket(ack(salt()), pass);
        for (byte b : pass) {
            assertEquals("口令数组必须被清零", 0, b);
        }
    }

    @Test
    public void statusNeverLeaksSecrets() throws Exception {
        FakeLink link = new FakeLink();
        HomebrewClient c = connected(link);
        String s = c.status();
        assertFalse(s.contains(PASS));
        assertTrue(s.contains("ONLINE"));
        for (String line : c.log()) {
            assertFalse("日志也不得含口令", line.contains(PASS));
        }
    }

    /** 认证被拒就回断开，**不重试同一份凭据**——重试只会更快被封。 */
    @Test
    public void nakDropsToDisconnectedWithoutRetry() throws Exception {
        FakeLink link = new FakeLink();
        HomebrewClient c = new HomebrewClient(link, REPEATER, new byte[302]);
        c.connect();
        c.onPacket(ack(salt()), HomebrewClient.passphraseBytes(PASS));
        int sentBefore = link.sent.size();

        c.onPacket("MSTNAK\0\0\0\0".getBytes(StandardCharsets.US_ASCII), null);
        assertEquals(HomebrewClient.State.DISCONNECTED, c.state());
        c.tick();
        assertEquals("断开后不得自己再发东西", sentBefore, link.sent.size());
    }

    @Test
    public void voiceIsRefusedUntilOnline() throws Exception {
        FakeLink link = new FakeLink();
        HomebrewClient c = new HomebrewClient(link, REPEATER, new byte[302]);
        byte[] voice = HomebrewPacket.encodeDmrd(0, 1701, 99, REPEATER, 0,
                false, 0, 0, 1, new byte[HomebrewPacket.DMR_PAYLOAD_BYTES]);

        assertFalse("断开态不得发语音", c.sendVoice(voice));
        c.connect();
        assertFalse("握手中不得发语音", c.sendVoice(voice));
        c.onPacket(ack(salt()), HomebrewClient.passphraseBytes(PASS));
        assertFalse(c.sendVoice(voice));
        c.onPacket(ack(new byte[4]), null);
        assertFalse(c.sendVoice(voice));
        c.onPacket(ack(new byte[4]), null);
        assertTrue("在线才准发", c.sendVoice(voice));
    }

    @Test
    public void inboundVoiceIsCollectedOnlyWhenOnline() throws Exception {
        FakeLink link = new FakeLink();
        HomebrewClient c = new HomebrewClient(link, REPEATER, new byte[302]);
        byte[] voice = HomebrewPacket.encodeDmrd(1, 1701, 99, REPEATER, 0,
                false, 0, 0, 7, new byte[HomebrewPacket.DMR_PAYLOAD_BYTES]);

        c.connect();
        c.onPacket(voice, null);                    // 握手中来的语音要丢掉
        assertTrue(c.drainInbox().isEmpty());

        c.onPacket(ack(salt()), HomebrewClient.passphraseBytes(PASS));
        c.onPacket(ack(new byte[4]), null);
        c.onPacket(ack(new byte[4]), null);
        c.onPacket(voice, null);
        List<HomebrewPacket.Dmrd> got = c.drainInbox();
        assertEquals(1, got.size());
        assertEquals(1701, got.get(0).sourceId);
        assertTrue("取走之后收件箱要空", c.drainInbox().isEmpty());
    }

    @Test
    public void pingIsSentOnIntervalWhileOnline() throws Exception {
        FakeLink link = new FakeLink();
        HomebrewClient c = connected(link);
        int before = link.sent.size();

        link.now = HomebrewClient.PING_INTERVAL_MS - 1;
        c.tick();
        assertEquals("没到点不发", before, link.sent.size());

        link.now = HomebrewClient.PING_INTERVAL_MS;
        c.tick();
        assertEquals(before + 1, link.sent.size());
        assertEquals("RPTPING", link.tagOf(link.lastSent()));
    }

    @Test
    public void silenceEventuallyDropsTheLink() throws Exception {
        FakeLink link = new FakeLink();
        HomebrewClient c = connected(link);

        link.now = HomebrewClient.LINK_TIMEOUT_MS - 1;
        c.tick();
        assertEquals(HomebrewClient.State.ONLINE, c.state());

        link.now = HomebrewClient.LINK_TIMEOUT_MS;
        c.tick();
        assertEquals("久无应答必须判掉线",
                HomebrewClient.State.DISCONNECTED, c.state());
    }

    @Test
    public void masterCloseDropsImmediately() throws Exception {
        FakeLink link = new FakeLink();
        HomebrewClient c = connected(link);
        c.onPacket("MSTCL\0\0\0\0".getBytes(StandardCharsets.US_ASCII), null);
        assertEquals(HomebrewClient.State.DISCONNECTED, c.state());
    }

    @Test
    public void reconnectStartsFromScratch() throws Exception {
        FakeLink link = new FakeLink();
        HomebrewClient c = connected(link);
        byte[] voice = HomebrewPacket.encodeDmrd(1, 1701, 99, REPEATER, 0,
                false, 0, 0, 7, new byte[HomebrewPacket.DMR_PAYLOAD_BYTES]);
        c.onPacket(voice, null);

        c.connect();
        assertEquals(HomebrewClient.State.AWAIT_SALT, c.state());
        assertTrue("重连要清掉上次的收件箱", c.drainInbox().isEmpty());
        assertEquals("RPTL", link.tagOf(link.lastSent()));
    }

    @Test
    public void disconnectTellsTheMaster() throws Exception {
        FakeLink link = new FakeLink();
        HomebrewClient c = connected(link);
        c.disconnect();
        assertEquals("RPTCL", link.tagOf(link.lastSent()));
        assertEquals(HomebrewClient.State.DISCONNECTED, c.state());
    }

    @Test
    public void unrecognisedPacketsAreIgnoredNotGuessed() throws Exception {
        FakeLink link = new FakeLink();
        HomebrewClient c = new HomebrewClient(link, REPEATER, new byte[302]);
        c.connect();
        int before = link.sent.size();
        c.onPacket("HELLO".getBytes(StandardCharsets.US_ASCII),
                HomebrewClient.passphraseBytes(PASS));
        assertEquals("不认识的包不该推进状态机",
                HomebrewClient.State.AWAIT_SALT, c.state());
        assertEquals(before, link.sent.size());
    }
}
