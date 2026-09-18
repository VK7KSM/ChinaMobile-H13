package net.elfradio.h13dmrtx;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.FileInputStream;

import org.junit.Test;

public final class McuAssetsTest {
    @Test
    public void nonRfFullprepUsesMirrorHelperNotRfPrep() throws Exception {
        byte[] value = load(McuAssets.FULLPREP_FILE,
                McuAssets.FULLPREP_LENGTH, McuAssets.FULLPREP_SHA256);
        assertTrue(McuAssets.containsLe32(value, McuAssets.FULLPREP_HELPER | 1));
        assertFalse(McuAssets.containsLe32(value, 0x20003101));
        assertFalse(McuAssets.containsLe32(value, McuAssets.RF_PREP_ENTRY));
        assertFalse(McuAssets.containsLe32(value, McuAssets.RF_PREP_CHAIN_ENTRY));
    }

    @Test
    public void rfCombinedFullprepCallsChainPrepNotOnesHot() throws Exception {
        byte[] value = load(McuAssets.FULLPREP_RF_FILE,
                McuAssets.FULLPREP_LENGTH, McuAssets.FULLPREP_RF_SHA256);
        assertTrue(McuAssets.containsLe32(value, McuAssets.RF_PREP_CHAIN_ENTRY));
        assertTrue(McuAssets.containsLe32(value, McuAssets.INBRIDGE_RF_OFF_ENTRY));
        assertFalse(McuAssets.containsLe32(value, McuAssets.FULLPREP_HELPER | 1));
        assertEquals(McuAssets.RF_PREP_ENTRY, McuAssets.INBRIDGE_RF_OFF_ENTRY);
        assertFalse(McuAssets.containsLe32(value,
                TxStateMachine.RF_PREP_MARKER));
    }

    @Test
    public void rfPrepChainIsV009LongSlotGateNotV007WordStore() throws Exception {
        byte[] value = load(McuAssets.RF_PREP_CHAIN_FILE,
                McuAssets.RF_PREP_CHAIN_LENGTH,
                McuAssets.RF_PREP_CHAIN_SHA256);
        assertEquals(60, value.length);
        assertFalse(McuAssets.RF_PREP_CHAIN_V007_FORBIDDEN_SHA256.equals(
                Bytes.sha256(value)));
        assertFalse(McuAssets.RF_PREP_CHAIN_V008_FORBIDDEN_SHA256.equals(
                Bytes.sha256(value)));
        assertTrue(McuAssets.containsLe32(value, 0x48001028));
        assertTrue(McuAssets.containsLe32(value, 0x48001428));
        assertTrue(McuAssets.containsLe32(value, McuAssets.ACTIVE_GATE));
        assertTrue(McuAssets.containsLe32(value, McuAssets.RF_SLOT_COUNT));
        assertTrue(McuAssets.containsLe32(value, McuAssets.RF_COMPLETION));
        assertTrue(McuAssets.containsLe32(value, McuAssets.FULLPREP_HELPER | 1));
        assertEquals(160, McuAssets.RF_SLOT_COUNT_VALUE);
        assertTrue(containsThumb(value, 0x21a0));
        assertTrue(containsThumb(value, 0x7001));
        assertTrue(containsThumb(value, 0x8001));
        assertFalse(McuAssets.containsLe32(value, McuAssets.VECTOR));
        assertFalse(McuAssets.containsLe32(value,
                TxStateMachine.ORIGINAL_SYSTICK));
        assertFalse(McuAssets.containsLe32(value,
                TxStateMachine.RF_PREP_MARKER));
        assertFalse(McuAssets.containsLe32(value, McuAssets.RF_PREP_ENTRY));
    }

    private static boolean containsThumb(byte[] value, int halfword) {
        byte low = (byte) halfword;
        byte high = (byte) (halfword >>> 8);
        for (int offset = 0; offset + 1 < value.length; offset++) {
            if (value[offset] == low && value[offset + 1] == high) {
                return true;
            }
        }
        return false;
    }

    @Test
    public void rfPrepIsStackSafeOneShotWithExactLiterals() throws Exception {
        byte[] value = load(McuAssets.RF_PREP_FILE,
                McuAssets.RF_PREP_LENGTH, McuAssets.RF_PREP_SHA256);
        assertEquals(0x10, value[0] & 0xff);
        assertEquals(0xb5, value[1] & 0xff);
        assertTrue(McuAssets.containsLe32(value, 0x48001028));
        assertTrue(McuAssets.containsLe32(value, 0x48001428));
        assertTrue(McuAssets.containsLe32(value, McuAssets.ACTIVE_GATE));
        assertTrue(McuAssets.containsLe32(value, McuAssets.RF_MARKER));
        assertTrue(McuAssets.containsLe32(value,
                TxStateMachine.RF_PREP_MARKER));
        assertTrue(McuAssets.containsLe32(value, McuAssets.VECTOR));
        assertTrue(McuAssets.containsLe32(value,
                TxStateMachine.ORIGINAL_SYSTICK));
    }

    @Test
    public void offAndSecondBridgeAssetsMatchFrozenContracts() throws Exception {
        byte[] off = load(McuAssets.RF_OFF_FILE, McuAssets.RF_OFF_LENGTH,
                McuAssets.RF_OFF_SHA256);
        assertTrue(McuAssets.containsLe32(off, 0x0801ff35));
        assertTrue(McuAssets.containsLe32(off,
                TxStateMachine.RF_OFF_MARKER));
        byte[] inbridge = load(McuAssets.INBRIDGE_RF_OFF_FILE,
                McuAssets.INBRIDGE_RF_OFF_LENGTH,
                McuAssets.INBRIDGE_RF_OFF_SHA256);
        assertEquals(84, inbridge.length);
        assertTrue(McuAssets.containsLe32(inbridge, 0x0801ff35));
        assertTrue(McuAssets.containsLe32(inbridge,
                TxStateMachine.RF_OFF_MARKER));
        assertTrue(McuAssets.containsLe32(inbridge,
                TxStateMachine.ORIGINAL_SYSTICK));
        assertFalse(McuAssets.containsLe32(inbridge, McuAssets.VECTOR));
        assertEquals(0x20003074, McuAssets.RF_HOLD_COUNTER);
        assertEquals(16000, DmrTxController.RF_HOLD_MS);

        byte[] bridge = load(McuAssets.SECOND_BRIDGE_FILE,
                McuAssets.SECOND_BRIDGE_LENGTH,
                McuAssets.SECOND_BRIDGE_SHA256);
        assertTrue(McuAssets.containsLe32(bridge,
                McuAssets.SECOND_BRIDGE_COUNTER));
        assertTrue(McuAssets.containsLe32(bridge, McuAssets.BRIDGE_FLAG));
        assertTrue(McuAssets.containsLe32(bridge, McuAssets.VECTOR));
        assertTrue(McuAssets.containsLe32(bridge, 0x47445242));
    }

    @Test
    public void rfTimingStateSeparatesDynamicCounterFromRestoredControl() {
        assertEquals(0x20000134, McuAssets.RF_EDGE_COUNTER);
        assertEquals(4, McuAssets.RF_EDGE_COUNTER_LENGTH);
        assertEquals(0x20000138, McuAssets.RF_TIMING_CONTROL);
        assertEquals(8, McuAssets.RF_TIMING_CONTROL_LENGTH);
        assertEquals(0x20000134, McuAssets.RF_TIMING_STATE);
        assertEquals(12, McuAssets.RF_TIMING_STATE_LENGTH);
        assertEquals(McuAssets.RF_EDGE_COUNTER
                        + McuAssets.RF_EDGE_COUNTER_LENGTH,
                McuAssets.RF_TIMING_CONTROL);
        assertEquals(McuAssets.ACTIVE_GATE,
                McuAssets.RF_TIMING_STATE + McuAssets.ACTIVE_GATE_OFFSET);
        assertEquals(McuAssets.ACTIVE_GATE, McuAssets.RF_TIMING_CONTROL);
        assertTrue(McuAssets.RF_TIMING_CONTROL <= 0x2000013a);
        assertTrue(McuAssets.RF_TIMING_CONTROL
                + McuAssets.RF_TIMING_CONTROL_LENGTH > 0x2000013c);
    }

    private static byte[] load(String path, int length, String sha256)
            throws Exception {
        File direct = new File("src/main/assets", path);
        File file = direct.isFile() ? direct : new File("app/src/main/assets", path);
        return McuAssets.loadVerified(new FileInputStream(file), length, sha256);
    }
}
