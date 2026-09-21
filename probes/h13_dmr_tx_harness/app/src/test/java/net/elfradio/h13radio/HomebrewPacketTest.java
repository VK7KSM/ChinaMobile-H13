package net.elfradio.h13radio;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import org.junit.Test;

/**
 * Homebrew 报文编解码用例。
 *
 * <p>**注意这些用例证明的是"编解码自洽"，不是"与真实 master 兼容"**。
 * 该协议没有权威公开规范，实现按常见形态写。与真实 master 对接属于对外
 * 发包，要等操作者同意；在那之前这里的每一条都只是内部一致性。
 *
 * <p>因此用例刻意包含「拿不准就返回 null，不猜」这一类断言——宁可解析
 * 失败也不要把一段不认识的字节当成有效语音送上空口。
 */
public class HomebrewPacketTest {

    private static byte[] payload33(int seed) {
        byte[] p = new byte[HomebrewPacket.DMR_PAYLOAD_BYTES];
        for (int i = 0; i < p.length; i++) {
            p[i] = (byte) (seed + i);
        }
        return p;
    }

    @Test
    public void controlPacketsCarryTagAndId() {
        byte[] login = HomebrewPacket.login(0x0102_0304);
        assertEquals(8, login.length);
        assertEquals("RPTL",
                new String(Arrays.copyOf(login, 4), StandardCharsets.US_ASCII));
        assertEquals(0x01, login[4] & 0xff);
        assertEquals(0x04, login[7] & 0xff);

        assertEquals(11, HomebrewPacket.ping(1).length);       // RPTPING + 4
        assertEquals(9, HomebrewPacket.close(1).length);       // RPTCL + 4
    }

    @Test
    public void authenticateRequiresExactDigestLength() {
        byte[] digest = new byte[32];
        byte[] pkt = HomebrewPacket.authenticate(12345, digest);
        assertEquals(4 + 4 + 32, pkt.length);
        try {
            HomebrewPacket.authenticate(1, new byte[31]);
            throw new AssertionError("短摘要应当被拒");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("32"));
        }
    }

    @Test
    public void saltIsOnlyTakenFromAWellFormedAck() {
        byte[] ack = new byte[10];
        System.arraycopy("RPTACK".getBytes(StandardCharsets.US_ASCII), 0,
                ack, 0, 6);
        ack[6] = 0x11;
        ack[9] = 0x44;
        assertArrayEquals(new byte[] {0x11, 0, 0, 0x44},
                HomebrewPacket.saltFromAck(ack));

        assertNull("长度不足不猜", HomebrewPacket.saltFromAck(
                Arrays.copyOf(ack, 8)));
        assertNull("标签不对不猜", HomebrewPacket.saltFromAck(
                "MSTNAK\0\0\0\0".getBytes(StandardCharsets.US_ASCII)));
        assertNull(HomebrewPacket.saltFromAck(null));
    }

    @Test
    public void dmrdRoundTrips() {
        byte[] p = payload33(7);
        byte[] wire = HomebrewPacket.encodeDmrd(3, 1701, 99, 0x00C0FFEE,
                1, false, 2, 5, 0xDEADBEEF, p);
        assertEquals(HomebrewPacket.DMRD_CORE_BYTES, wire.length);

        HomebrewPacket.Dmrd d = HomebrewPacket.decodeDmrd(wire);
        assertNotNull(d);
        assertEquals(3, d.sequence);
        assertEquals(1701, d.sourceId);
        assertEquals(99, d.destinationId);
        assertEquals(0x00C0FFEE, d.repeaterId);
        assertEquals(1, d.slot);
        assertFalse(d.privateCall);
        assertEquals(2, d.frameType);
        assertEquals(5, d.dataType);
        assertEquals(0xDEADBEEF, d.streamId);
        assertArrayEquals(p, d.payload);
        assertFalse(d.hadTail);
    }

    @Test
    public void privateCallBitSurvives() {
        byte[] wire = HomebrewPacket.encodeDmrd(0, 1, 2, 3, 0, true, 0, 0, 0,
                payload33(0));
        HomebrewPacket.Dmrd d = HomebrewPacket.decodeDmrd(wire);
        assertNotNull(d);
        assertTrue(d.privateCall);
        assertEquals(0, d.slot);
    }

    /**
     * 有的 master 在 `DMRD` 尾部追加两字节。**接收时两种长度都收**，
     * 发送时只发不带尾部的那种——收得宽、发得窄，是在没有权威规范时
     * 唯一稳妥的取向。
     */
    @Test
    public void tailBytesAreAcceptedOnReceive() {
        byte[] core = HomebrewPacket.encodeDmrd(1, 1701, 99, 7, 0, false,
                0, 0, 1, payload33(3));
        byte[] withTail = Arrays.copyOf(core,
                HomebrewPacket.DMRD_WITH_TAIL_BYTES);
        withTail[53] = 0x12;
        withTail[54] = 0x34;

        HomebrewPacket.Dmrd d = HomebrewPacket.decodeDmrd(withTail);
        assertNotNull("带尾部的也要能收", d);
        assertTrue(d.hadTail);
        assertArrayEquals(payload33(3), d.payload);
    }

    @Test
    public void unknownLengthsAreRefusedRatherThanGuessed() {
        byte[] core = HomebrewPacket.encodeDmrd(1, 1, 2, 3, 0, false, 0, 0, 1,
                payload33(0));
        assertNull("短一字节不猜", HomebrewPacket.decodeDmrd(
                Arrays.copyOf(core, core.length - 1)));
        assertNull("长一字节不猜", HomebrewPacket.decodeDmrd(
                Arrays.copyOf(core, core.length + 1)));
        assertNull("标签不对不猜", HomebrewPacket.decodeDmrd(
                new byte[HomebrewPacket.DMRD_CORE_BYTES]));
    }

    @Test
    public void payloadLengthIsEnforcedOnEncode() {
        try {
            HomebrewPacket.encodeDmrd(0, 1, 2, 3, 0, false, 0, 0, 0,
                    new byte[32]);
            throw new AssertionError("净荷长度不对应当被拒");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("33"));
        }
        try {
            HomebrewPacket.encodeDmrd(0, 1, 2, 3, 2, false, 0, 0, 0,
                    payload33(0));
            throw new AssertionError("时隙越界应当被拒");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("时隙"));
        }
    }

    @Test
    public void idsUseBigEndianAndSurviveLargeValues() {
        int src = 0x00FF_FFFF;                  // 24 位上限
        int rpt = 0x7FFF_FFFF;
        byte[] wire = HomebrewPacket.encodeDmrd(255, src, src, rpt, 1, true,
                3, 15, 0x7FFFFFFF, payload33(1));
        HomebrewPacket.Dmrd d = HomebrewPacket.decodeDmrd(wire);
        assertNotNull(d);
        assertEquals(255, d.sequence);
        assertEquals(src, d.sourceId);
        assertEquals(src, d.destinationId);
        assertEquals(rpt, d.repeaterId);
        assertEquals(3, d.frameType);
        assertEquals(15, d.dataType);
    }
}
