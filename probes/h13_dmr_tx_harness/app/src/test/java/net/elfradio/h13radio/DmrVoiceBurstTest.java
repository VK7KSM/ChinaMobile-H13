package net.elfradio.h13radio;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Random;

import org.junit.Test;

/**
 * 语音突发拆装用例。
 *
 * <p><b>这些用例证明的是拆装互逆与位段算术正确，不是与真实网络流兼容。</b>
 * 本仓库没有一段既有 33 字节 `DMRD` 又有对应 27 字节单元的配对样本，
 * 拿不到之前，布局假设只能靠自洽性守住。这一点写在这里，是为了以后有人
 * 拿这个类去接真实网络时不至于误以为它已经对过真实数据。
 */
public class DmrVoiceBurstTest {

    private static byte[] random(int n, long seed) {
        byte[] b = new byte[n];
        new Random(seed).nextBytes(b);
        return b;
    }

    @Test
    public void layoutAssumptionIsSelfConsistent() {
        assertTrue("第二帧必须跨在中间 48 位两侧",
                DmrVoiceBurst.layoutIsConsistent());
    }

    @Test
    public void unitAndMiddleReassembleTheOriginalBurst() {
        byte[] burst = random(DmrVoiceBurst.BURST_BYTES, 42);
        byte[] unit = DmrVoiceBurst.toUnit(burst);
        byte[] middle = DmrVoiceBurst.middle(burst);
        assertEquals(DmrVoiceBurst.UNIT_BYTES, unit.length);
        assertEquals(6, middle.length);
        assertArrayEquals("拆开再装回必须逐字节相同",
                burst, DmrVoiceBurst.toBurst(unit, middle));
    }

    @Test
    public void unitRoundTripsThroughABurst() {
        byte[] unit = random(DmrVoiceBurst.UNIT_BYTES, 7);
        byte[] burst = DmrVoiceBurst.toBurst(unit, null);
        assertArrayEquals(unit, DmrVoiceBurst.toUnit(burst));
    }

    /** 中间 48 位承载同步/嵌入信令，**不是语音**，不得混进单元。 */
    @Test
    public void middleBitsNeverLeakIntoTheUnit() {
        byte[] burst = new byte[DmrVoiceBurst.BURST_BYTES];
        // 只把中间 48 位全置 1
        for (int i = 108; i < 156; i++) {
            burst[i >> 3] |= (byte) (1 << (7 - (i & 7)));
        }
        byte[] unit = DmrVoiceBurst.toUnit(burst);
        for (byte b : unit) {
            assertEquals("中间段不该出现在语音单元里", 0, b);
        }
        for (byte b : DmrVoiceBurst.middle(burst)) {
            assertEquals("中间段本身应当全 1", (byte) 0xff, b);
        }
    }

    /** 反过来：语音位也不得漏进中间段。 */
    @Test
    public void voiceBitsNeverLeakIntoTheMiddle() {
        byte[] unit = new byte[DmrVoiceBurst.UNIT_BYTES];
        java.util.Arrays.fill(unit, (byte) 0xff);
        byte[] burst = DmrVoiceBurst.toBurst(unit, null);
        for (byte b : DmrVoiceBurst.middle(burst)) {
            assertEquals("语音位不该跑进中间段", 0, b);
        }
    }

    @Test
    public void framesComeOutInOrder() {
        byte[] unit = new byte[DmrVoiceBurst.UNIT_BYTES];
        for (int i = 0; i < unit.length; i++) {
            unit[i] = (byte) i;
        }
        assertEquals(0, DmrVoiceBurst.frameOf(unit, 0)[0]);
        assertEquals(9, DmrVoiceBurst.frameOf(unit, 1)[0]);
        assertEquals(18, DmrVoiceBurst.frameOf(unit, 2)[0]);
        assertEquals(DmrVoiceBurst.AMBE_FRAME_BYTES,
                DmrVoiceBurst.frameOf(unit, 2).length);
    }

    @Test
    public void wrongLengthsAreRefusedNotTruncated() {
        try {
            DmrVoiceBurst.toUnit(new byte[32]);
            throw new AssertionError("短突发应当被拒");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("33"));
        }
        try {
            DmrVoiceBurst.toBurst(new byte[26], null);
            throw new AssertionError("短单元应当被拒");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("27"));
        }
        try {
            DmrVoiceBurst.toBurst(new byte[DmrVoiceBurst.UNIT_BYTES],
                    new byte[5]);
            throw new AssertionError("中间段长度不对应当被拒");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("6"));
        }
        try {
            DmrVoiceBurst.frameOf(new byte[DmrVoiceBurst.UNIT_BYTES], 3);
            throw new AssertionError("帧序越界应当被拒");
        } catch (IndexOutOfBoundsException expected) {
            assertTrue(expected.getMessage().contains("帧序"));
        }
    }

    /** 每一位都得能独立走一个来回，位段算术错一位就会被抓住。 */
    @Test
    public void everySingleBitSurvivesTheRoundTrip() {
        for (int i = 0; i < DmrVoiceBurst.UNIT_BYTES * 8; i++) {
            byte[] unit = new byte[DmrVoiceBurst.UNIT_BYTES];
            unit[i >> 3] |= (byte) (1 << (7 - (i & 7)));
            byte[] back = DmrVoiceBurst.toUnit(
                    DmrVoiceBurst.toBurst(unit, null));
            assertArrayEquals("第 " + i + " 位走丢了", unit, back);
        }
    }

    /** 与网络报文拼起来：`DMRD` 的净荷就是一个突发。 */
    @Test
    public void burstFitsTheDmrdPayload() {
        assertEquals(HomebrewPacket.DMR_PAYLOAD_BYTES,
                DmrVoiceBurst.BURST_BYTES);
        byte[] unit = random(DmrVoiceBurst.UNIT_BYTES, 99);
        byte[] burst = DmrVoiceBurst.toBurst(unit, null);
        byte[] wire = HomebrewPacket.encodeDmrd(0, 1701, 99, 7, 0, false,
                0, 0, 1, burst);
        HomebrewPacket.Dmrd decoded = HomebrewPacket.decodeDmrd(wire);
        assertArrayEquals("一路走到网络报文再回来，语音单元不变",
                unit, DmrVoiceBurst.toUnit(decoded.payload));
    }
}
