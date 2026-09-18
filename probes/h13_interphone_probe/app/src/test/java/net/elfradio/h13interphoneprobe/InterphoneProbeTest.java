package net.elfradio.h13interphoneprobe;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.lang.reflect.Method;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import java.io.File;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

public final class InterphoneProbeTest {
    @Test
    public void bridge0TextPlaneHpiWriteIsRejected() {
        byte[] off = new byte[] {
                (byte) 0x84, (byte) 0xa9, 0x61, 0x00, 0x02, 0x00, 0x3e, 0x00
        };
        try {
            InterphoneProbe.requireHpiPlaneForBinaryWrite(0, off);
            fail("bridge=0 文本面写 HPI 必须失败");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("bridge=0"));
        }
        // bridge=1 允许
        InterphoneProbe.requireHpiPlaneForBinaryWrite(1, off);
    }

    @Test
    public void v219FrameAdapterArtifactAndTruncatedOutcomeContract()
            throws Exception {
        Field adapterField = InterphoneProbe.class.getDeclaredField(
                "FRAME_ADAPTER_BASE64");
        adapterField.setAccessible(true);
        byte[] adapter = java.util.Base64.getDecoder().decode(
                (String) adapterField.get(null));
        assertEquals(InterphoneProbe.FRAME_ADAPTER_CODE_LENGTH, adapter.length);
        assertEquals(InterphoneProbe.FRAME_ADAPTER_SHA256, sha256Hex(adapter));
        // v2.27：内嵌二进制最终向量字面量必须为奇数 Thumb 入口，禁止二次 +1。
        assertTrue(containsLeU32(adapter, InterphoneProbe.FRAME_ADAPTER_USART1_ENTRY));
        assertTrue(containsLeU32(adapter, InterphoneProbe.FRAME_ADAPTER_SYSTICK_ENTRY));
        assertFalse(containsLeU32(adapter, 0x20001d02));
        assertFalse(containsLeU32(adapter, 0x20001eee));
        assertEquals(0x20001bd5, InterphoneProbe.FRAME_ADAPTER_USART1_ENTRY);
        assertEquals(0x20001de9, InterphoneProbe.FRAME_ADAPTER_SYSTICK_ENTRY);
        assertEquals(0x20001fdf, InterphoneProbe.FRAME_ADAPTER_ARM_ENTRY);
        assertEquals(0x20001edf, InterphoneProbe.FRAME_ADAPTER_THREAD_ENTRY);
        assertEquals(0x20001ede, InterphoneProbe.FRAME_ADAPTER_THREAD_CODE);
        // v3.09：HPI只在线程态执行，旧PendSV/优先级路径必须不存在。
        assertEquals(1520, InterphoneProbe.FRAME_ADAPTER_CODE_LENGTH);
        assertEquals(0x13b4, InterphoneProbe.FRAME_ADAPTER_RANGE_LENGTH);
        assertEquals(5044, InterphoneProbe.FRAME_ADAPTER_RANGE_LENGTH);
        assertEquals(90000L, InterphoneProbe.FRAME_ADAPTER_EVIDENCE_HOLD_MS);
        assertEquals(9000L,
                InterphoneProbe.FRAME_ADAPTER_EVIDENCE_HOLD_TICKS);
        assertEquals(40L,
                InterphoneProbe.FRAME_ADAPTER_EVIDENCE_RELEASE_DELAY_TICKS);
        assertEquals(0x20002270, InterphoneProbe.FRAME_ADAPTER_BUFFER_ADDRESS);
        assertEquals(168, InterphoneProbe.FRAME_ADAPTER_TELEMETRY_LENGTH);
        // 线程PC 0x58/0x5c不得与HOBIB 0x98..0xa4同址。
        assertTrue(0x58 != 0x98 && 0x5c != 0x9c && 0x5c != 0xa0 && 0x5c != 0xa4);
        assertTrue(containsLeU32(adapter, 0x48000810)); // GPIOC_IDR
        assertTrue(containsLeU32(adapter,
                InterphoneProbe.FRAME_ADAPTER_TIMEOUT_SNAPSHOT_BUFFER_ADDRESS));
        assertTrue(containsLeU32(adapter,
                InterphoneProbe.FRAME_ADAPTER_TIMEOUT_SNAPSHOT_STAGE_ADDRESS));
        assertTrue(containsLeU32(adapter,
                InterphoneProbe.FRAME_ADAPTER_TIMEOUT_SNAPSHOT_RESPONSE_ADDRESS));
        assertTrue(containsLeU32(adapter,
                InterphoneProbe.FRAME_ADAPTER_TIMEOUT_SNAPSHOT_COMMIT_ADDRESS));
        assertTrue(containsLeU32(adapter,
                InterphoneProbe.FRAME_ADAPTER_TIMEOUT_SNAPSHOT_COMMIT));
        assertTrue(containsLeU32(adapter,
                InterphoneProbe.FRAME_ADAPTER_TIMEOUT_HOLD_BEFORE_ADDRESS));
        assertTrue(containsLeU32(adapter,
                InterphoneProbe.FRAME_ADAPTER_TIMEOUT_HOLD_BEFORE_COMMIT));
        // v3.12释放分支只恢复原厂SysTick，切速由Android侧一次性桩完成。
        assertFalse(containsLeU32(adapter,
                InterphoneProbe.FRAME_ADAPTER_EVIDENCE_HOLD_RELEASED_MAGIC));
        assertFalse(containsLeU32(adapter, 0x08010a01));
        assertFalse(containsLeU32(adapter, 57600));
        assertFalse(containsLeU32(adapter, 0x080105c9));
        assertTrue(containsLeU32(adapter,
                InterphoneProbe.STOCK_HPI_TRANSACTION_CODE_ADDRESS | 1));
        // 禁止旧布局入口混入新二进制
        assertFalse(containsLeU32(adapter, 0x20001ecd));
        assertFalse(containsLeU32(adapter, 0x20001ffb));
        assertFalse(containsLeU32(adapter, 0x20001f4d));
        assertFalse(containsLeU32(adapter, 0x20002041)); // 旧 ARM 入口
        assertTrue(containsLeU32(adapter, InterphoneProbe.FRAME_ADAPTER_THREAD_ENTRY));
        assertTrue(containsLeU32(adapter,
                InterphoneProbe.FRAME_ADAPTER_MAIN_LOOP_SAFE_START));
        assertTrue(containsLeU32(adapter,
                InterphoneProbe.FRAME_ADAPTER_MAIN_LOOP_SAFE_END));
        assertFalse(containsLeU32(adapter, 0x10000000));
        assertFalse(containsLeU32(adapter, 0x20000038));
        assertFalse(containsLeU32(adapter, 0xe000ed22));
        assertEquals(1, InterphoneProbe.FRAME_ADAPTER_USART1_ENTRY & 1);
        assertEquals(1, InterphoneProbe.FRAME_ADAPTER_SYSTICK_ENTRY & 1);
        assertEquals(1, InterphoneProbe.FRAME_ADAPTER_ARM_ENTRY & 1);

        assertTrue(InterphoneProbe.FRAME_ADAPTER_CODE_LENGTH
                <= InterphoneProbe.FRAME_ADAPTER_BUFFER_ADDRESS
                - InterphoneProbe.FRAME_ADAPTER_CODE_ADDRESS);
        assertTrue(InterphoneProbe.FRAME_ADAPTER_CODE_ADDRESS
                + InterphoneProbe.FRAME_ADAPTER_CODE_LENGTH
                <= InterphoneProbe.FRAME_ADAPTER_BUFFER_ADDRESS);
        assertTrue(InterphoneProbe.FRAME_ADAPTER_BUFFER_ADDRESS
                + InterphoneProbe.FRAME_ADAPTER_BUFFER_LENGTH
                <= InterphoneProbe.FRAME_ADAPTER_TELEMETRY_ADDRESS);
        assertEquals(InterphoneProbe.FRAME_ADAPTER_RANGE_ADDRESS
                        + InterphoneProbe.FRAME_ADAPTER_RANGE_LENGTH,
                InterphoneProbe.FRAME_ADAPTER_THREAD_RESERVED_ADDRESS
                        + InterphoneProbe.FRAME_ADAPTER_THREAD_RESERVED_LENGTH);
        assertEquals(InterphoneProbe.FRAME_ADAPTER_STICKY_TELEMETRY_ADDRESS
                        + InterphoneProbe.FRAME_ADAPTER_TELEMETRY_LENGTH,
                InterphoneProbe.FRAME_ADAPTER_TIMEOUT_SNAPSHOT_BUFFER_ADDRESS);
        assertEquals(InterphoneProbe.FRAME_ADAPTER_TIMEOUT_SNAPSHOT_BUFFER_ADDRESS
                        + InterphoneProbe.FRAME_ADAPTER_BUFFER_LENGTH,
                InterphoneProbe.FRAME_ADAPTER_TIMEOUT_SNAPSHOT_STAGE_ADDRESS);
        assertEquals(InterphoneProbe.FRAME_ADAPTER_TIMEOUT_SNAPSHOT_STAGE_ADDRESS
                        + InterphoneProbe.FRAME_ADAPTER_STAGE_SLOT_LENGTH,
                InterphoneProbe.FRAME_ADAPTER_TIMEOUT_SNAPSHOT_RESPONSE_ADDRESS);
        assertTrue(InterphoneProbe.FRAME_ADAPTER_TIMEOUT_SNAPSHOT_RESPONSE_ADDRESS
                        + InterphoneProbe.FRAME_ADAPTER_RESPONSE_STATE_LENGTH
                <= InterphoneProbe.FRAME_ADAPTER_CODE_ADDRESS);
        assertTrue(InterphoneProbe.FRAME_ADAPTER_TIMEOUT_SNAPSHOT_COMMIT_ADDRESS
                        + 4
                <= InterphoneProbe.FRAME_ADAPTER_TIMEOUT_HOLD_BEFORE_ADDRESS);

        byte[] wire = InterphoneProbe.createAnalogVoiceInPcmFrame(
                InterphoneProbe.createTonePcm8kS16le(800, 4000, 640), 0);
        byte[] prefix = Arrays.copyOf(wire,
                InterphoneProbe.FRAME_ADAPTER_TRUNCATED_BYTES);
        byte[] buffer = new byte[InterphoneProbe.FRAME_ADAPTER_BUFFER_LENGTH];
        System.arraycopy(prefix, 0, buffer, 0, prefix.length);
        byte[] telemetry = new byte[InterphoneProbe.FRAME_ADAPTER_TELEMETRY_LENGTH];
        putU32ForV219(telemetry, 0x00, InterphoneProbe.FRAME_ADAPTER_MAGIC);
        putU32ForV219(telemetry, 0x04, InterphoneProbe.FRAME_ADAPTER_SCHEMA);
        putU32ForV219(telemetry, 0x0c,
                InterphoneProbe.FRAME_ADAPTER_PHASE_TIMEOUT);
        putU32ForV219(telemetry, 0x10,
                InterphoneProbe.FRAME_ADAPTER_ERROR_TIMEOUT);
        putU32ForV219(telemetry, 0x14, prefix.length);
        putU32ForV219(telemetry, 0x20, buffer.length);
        putU32ForV219(telemetry, 0x24, prefix.length);
        putU32ForV219(telemetry, 0x30, 1);
        putU32ForV219(telemetry, 0x60,
                InterphoneProbe.FRAME_ADAPTER_MARKER_TIMEOUT);
        putU32ForV219(telemetry, 0x68,
                InterphoneProbe.FRAME_ADAPTER_ORIGINAL_USART1);
        putU32ForV219(telemetry, 0x6c, 0x08021ddd);
        putU32ForV219(telemetry, 0x74,
                InterphoneProbe.FRAME_ADAPTER_COMMIT);
        assertTrue(InterphoneProbe.frameAdapterTruncatedOutcomeValid(
                telemetry, buffer, prefix,
                InterphoneProbe.FRAME_ADAPTER_ORIGINAL_USART1,
                0x08021ddd, 0));

        putU32ForV219(telemetry, 0x44, 1);
        assertFalse(InterphoneProbe.frameAdapterTruncatedOutcomeValid(
                telemetry, buffer, prefix,
                InterphoneProbe.FRAME_ADAPTER_ORIGINAL_USART1,
                0x08021ddd, 0));
        putU32ForV219(telemetry, 0x44, 0);
        buffer[7] ^= 1;
        assertFalse(InterphoneProbe.frameAdapterTruncatedOutcomeValid(
                telemetry, buffer, prefix,
                InterphoneProbe.FRAME_ADAPTER_ORIGINAL_USART1,
                0x08021ddd, 0));
    }

    private static void putU32ForV219(byte[] bytes, int offset, int value) {
        bytes[offset] = (byte) value;
        bytes[offset + 1] = (byte) (value >>> 8);
        bytes[offset + 2] = (byte) (value >>> 16);
        bytes[offset + 3] = (byte) (value >>> 24);
    }

    private static boolean containsLeU32(byte[] bytes, int value) {
        byte b0 = (byte) value;
        byte b1 = (byte) (value >>> 8);
        byte b2 = (byte) (value >>> 16);
        byte b3 = (byte) (value >>> 24);
        for (int i = 0; i + 3 < bytes.length; i += 4) {
            if (bytes[i] == b0 && bytes[i + 1] == b1
                    && bytes[i + 2] == b2 && bytes[i + 3] == b3) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsAscii(byte[] bytes, String value) {
        byte[] pattern = value.getBytes(StandardCharsets.US_ASCII);
        for (int i = 0; i + pattern.length <= bytes.length; i++) {
            boolean matched = true;
            for (int j = 0; j < pattern.length; j++) {
                if (bytes[i + j] != pattern[j]) {
                    matched = false;
                    break;
                }
            }
            if (matched) return true;
        }
        return false;
    }

    @Test
    public void v221FrameAdapterCompleteOutcomeAndData36Contract()
            throws Exception {
        byte[] wire = InterphoneProbe.createAnalogVoiceInPcmFrame(
                InterphoneProbe.createTonePcm8kS16le(800, 4000, 640), 0);
        byte[] telemetry = new byte[InterphoneProbe.FRAME_ADAPTER_TELEMETRY_LENGTH];
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(wire);
        putU32ForV219(telemetry, 0x00, InterphoneProbe.FRAME_ADAPTER_MAGIC);
        putU32ForV219(telemetry, 0x04, InterphoneProbe.FRAME_ADAPTER_SCHEMA);
        putU32ForV219(telemetry, 0x0c,
                InterphoneProbe.FRAME_ADAPTER_PHASE_DONE);
        putU32ForV219(telemetry, 0x20, wire.length);
        putU32ForV219(telemetry, 0x24, wire.length);
        putU32ForV219(telemetry, 0x28, (int) ~crc.getValue());
        putU32ForV219(telemetry, 0x2c, (int) crc.getValue());
        putU32ForV219(telemetry, 0x30, wire.length);
        putU32ForV219(telemetry, 0x44, 1);
        putU32ForV219(telemetry, 0x48, 1);
        putU32ForV219(telemetry, 0x4c, wire.length);
        putU32ForV219(telemetry, 0x50, wire.length);
        putU32ForV219(telemetry, 0x54, 2);
        putU32ForV219(telemetry, 0x60,
                InterphoneProbe.FRAME_ADAPTER_MARKER_DONE);
        putU32ForV219(telemetry, 0x68,
                InterphoneProbe.FRAME_ADAPTER_ORIGINAL_USART1);
        putU32ForV219(telemetry, 0x6c, 0x08021ddd);
        putU32ForV219(telemetry, 0x74,
                InterphoneProbe.FRAME_ADAPTER_COMMIT);
        assertTrue(InterphoneProbe.frameAdapterCompleteOutcomeValid(
                telemetry, wire.clone(), wire,
                InterphoneProbe.FRAME_ADAPTER_ORIGINAL_USART1,
                0x08021ddd, 0));
        assertTrue(InterphoneProbe.frameAdapterCompleteOutcomeValidDuringEvidenceHold(
                telemetry, wire.clone(), wire,
                InterphoneProbe.FRAME_ADAPTER_ORIGINAL_USART1,
                InterphoneProbe.FRAME_ADAPTER_SYSTICK_ENTRY, 0));
        assertFalse(InterphoneProbe.frameAdapterCompleteOutcomeValidDuringEvidenceHold(
                telemetry, wire.clone(), wire,
                InterphoneProbe.FRAME_ADAPTER_ORIGINAL_USART1,
                0x08021ddd, 0));
        telemetry[0x44] = 0;
        assertFalse(InterphoneProbe.frameAdapterCompleteOutcomeValid(
                telemetry, wire.clone(), wire,
                InterphoneProbe.FRAME_ADAPTER_ORIGINAL_USART1,
                0x08021ddd, 0));

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] expected = new byte[36];
        byte[] payload = new byte[38];
        payload[0] = 0x01;
        payload[1] = 36;
        for (int index = 0; index < expected.length; index++) {
            expected[index] = (byte) (index + 1);
            payload[index + 2] = expected[index];
        }
        output.write(invokeCreateHpiFrame(0x20, payload));
        assertArrayEquals(expected,
                InterphoneProbe.extractFirstStrictData36FromAdapterOutput(
                        output.toByteArray()));
        ByteArrayOutputStream duplicate = new ByteArrayOutputStream();
        duplicate.write(output.toByteArray());
        duplicate.write(output.toByteArray());
        assertNull(InterphoneProbe.extractFirstStrictData36FromAdapterOutput(
                duplicate.toByteArray()));
        ByteArrayOutputStream mixed27 = new ByteArrayOutputStream();
        byte[] payload27 = new byte[29];
        payload27[0] = 0x01;
        payload27[1] = 27;
        mixed27.write(invokeCreateHpiFrame(0x20, payload27));
        mixed27.write(output.toByteArray());
        assertNull(InterphoneProbe.extractFirstStrictData36FromAdapterOutput(
                mixed27.toByteArray()));
        byte[] malformed = Arrays.copyOf(output.toByteArray(),
                output.size() - 1);
        assertNull(InterphoneProbe.extractFirstStrictData36FromAdapterOutput(
                malformed));
    }

    @Test
    public void v221LaunchModesAreStrictlySeparated() {
        String noRf = "hpi_frame_adapter_complete_tone_no_rf";
        assertNull(MainActivity.validateLaunch(noRf, false, false, -1));
        assertNotNull(MainActivity.validateLaunch(noRf, true, false, -1));
        String rf = "hpi_frame_adapter_tone_to_dmr_low_power";
        assertNotNull(MainActivity.validateLaunch(rf, false, true, -1));
        assertNotNull(MainActivity.validateLaunch(rf, true, false, -1));
        assertNotNull(MainActivity.validateLaunch(rf, true, true, 1000));
        assertNull(MainActivity.validateLaunch(rf, true, true, -1));
        assertTrue(MainActivity.isPotentialRfMode(rf));
    }

    @Test
    public void v352LaunchContractRequiresRunAndUniqueSession() throws Exception {
        String mode = "launch_contract_selftest_no_device";
        String session = "0123456789abcdef0123456789abcdef";
        assertTrue(MainActivity.isKnownProbeMode(mode));
        assertTrue(MainActivity.isValidLaunchSessionId(session));
        assertFalse(MainActivity.isValidLaunchSessionId("short"));
        assertNotNull(MainActivity.validateLaunchRequest(mode, false, session,
                false, false, -1));
        assertNotNull(MainActivity.validateLaunchRequest(mode, true, "short",
                false, false, -1));
        assertNotNull(MainActivity.validateLaunchRequest("unknown", true,
                session, false, false, -1));
        assertNull(MainActivity.validateLaunchRequest(mode, true, session,
                false, false, -1));

        Path directory = Files.createTempDirectory("h13-launch-contract-");
        try {
            InterphoneProbe probe = new InterphoneProbe(directory.toFile());
            probe.setLaunchSessionId(session);
            ProbeResult result = probe.runLaunchContractSelftest();
            assertTrue(result.success);
            String atomic = new String(Files.readAllBytes(directory.resolve(
                    "probe_session_result.txt")), StandardCharsets.UTF_8);
            assertTrue(atomic.contains("launch_session_id=" + session));
            assertTrue(atomic.contains("transport=NO_DEVICE_IO"));
            assertTrue(atomic.contains("serial_opened=false"));
            assertTrue(atomic.contains("sram_written=false"));
            assertTrue(atomic.contains("hpi_written=false"));
            assertTrue(atomic.contains("rf_command_count=0"));
        } finally {
            File[] files = directory.toFile().listFiles();
            if (files != null) {
                for (File file : files) {
                    assertTrue(file.delete());
                }
            }
            assertTrue(directory.toFile().delete());
        }
    }

    private static byte[] littleEndianWords(int... words) {
        byte[] out = new byte[words.length * 4];
        for (int index = 0; index < words.length; index++) {
            int value = words[index];
            out[index * 4] = (byte) value;
            out[index * 4 + 1] = (byte) (value >>> 8);
            out[index * 4 + 2] = (byte) (value >>> 16);
            out[index * 4 + 3] = (byte) (value >>> 24);
        }
        return out;
    }

    private static byte[] invokeCreateHpiFrame(int packetType, byte[] payload)
            throws Exception {
        Method method = InterphoneProbe.class.getDeclaredMethod(
                "createHpiFrame", int.class, byte[].class);
        method.setAccessible(true);
        return (byte[]) method.invoke(null, packetType, payload);
    }

    @Test
    public void v218HobibObserverArtifactAndAddressContract() throws Exception {
        byte[] handler = littleEndianWords(
                InterphoneProbe.HOBIB_OBSERVER_HANDLER_WORDS);
        assertEquals(InterphoneProbe.HOBIB_OBSERVER_CODE_LENGTH, handler.length);
        InterphoneProbe.validateHobibObserverHandler(handler);
        assertEquals(0x20001600, InterphoneProbe.HOBIB_OBSERVER_CODE_ADDRESS);
        assertEquals(0x200016a0,
                InterphoneProbe.HOBIB_OBSERVER_METADATA_ADDRESS);
        assertEquals(0x200016c0,
                InterphoneProbe.HOBIB_OBSERVER_METADATA_ADDRESS
                        + InterphoneProbe.HOBIB_OBSERVER_METADATA_LENGTH);
        assertTrue(InterphoneProbe.HOBIB_OBSERVER_CODE_ADDRESS
                + InterphoneProbe.HOBIB_OBSERVER_CODE_LENGTH
                <= InterphoneProbe.HOBIB_OBSERVER_METADATA_ADDRESS);
        assertTrue(InterphoneProbe.HOBIB_OBSERVER_METADATA_ADDRESS
                + InterphoneProbe.HOBIB_OBSERVER_METADATA_LENGTH
                <= 0x20001700);
    }

    @Test
    public void v218HobibObserverRejectsForbiddenLiteral() throws Exception {
        byte[] handler = littleEndianWords(
                InterphoneProbe.HOBIB_OBSERVER_HANDLER_WORDS);
        handler[0] = 0x61;
        handler[1] = 0x05;
        handler[2] = 0x01;
        handler[3] = 0x08;
        try {
            InterphoneProbe.validateHobibObserverHandler(handler);
            fail("必须拒绝被篡改的HPI写函数常量");
        } catch (java.io.IOException expected) {
            assertTrue(expected.getMessage().contains("哈希")
                    || expected.getMessage().contains("禁止"));
        }
    }

    @Test
    public void v218HobibLevelReadsOnlyPf10() throws Exception {
        assertEquals(0, InterphoneProbe.hobibLevel(
                littleEndianWords(0x00000000)));
        assertEquals(1, InterphoneProbe.hobibLevel(
                littleEndianWords(0x00000400)));
        assertEquals(0, InterphoneProbe.hobibLevel(
                littleEndianWords(0xfffffbff)));
    }

    @Test
    public void v218HobibObservationAcceptsConsistentCounts() throws Exception {
        byte[] metadata = littleEndianWords(0, 100, 70, 30, 4, 1, 0,
                InterphoneProbe.HOBIB_OBSERVER_MARKER);
        InterphoneProbe.validateHobibObservation(metadata, 100,
                0x08021ddd, 0);
    }

    @Test
    public void v218HobibObservationRejectsCountMismatch() throws Exception {
        byte[] metadata = littleEndianWords(0, 100, 70, 29, 4, 1, 0,
                InterphoneProbe.HOBIB_OBSERVER_MARKER);
        try {
            InterphoneProbe.validateHobibObservation(metadata, 100,
                    0x08021ddd, 0);
            fail("必须拒绝高低计数和样本总数不一致");
        } catch (java.io.IOException expected) {
            assertTrue(expected.getMessage().contains("统计"));
        }
    }

    @Test
    public void v218HobibPreimageNeedNotBeZeroButMustBeStable() throws Exception {
        byte[] flag = {0};
        byte[] vector = littleEndianWords(0x08021ddd);
        byte[] code = new byte[InterphoneProbe.HOBIB_OBSERVER_CODE_LENGTH];
        byte[] metadata = new byte[InterphoneProbe.HOBIB_OBSERVER_METADATA_LENGTH];
        Arrays.fill(code, (byte) 0x5a);
        Arrays.fill(metadata, (byte) 0xa5);
        InterphoneProbe.requireHobibStablePreimage(flag, flag.clone(), vector,
                vector.clone(), code, code.clone(), metadata, metadata.clone());
        byte[] changed = metadata.clone();
        changed[3] ^= 1;
        try {
            InterphoneProbe.requireHobibStablePreimage(flag, flag.clone(),
                    vector, vector.clone(), code, code.clone(), metadata,
                    changed);
            fail("必须拒绝动态原像");
        } catch (java.io.IOException expected) {
            assertTrue(expected.getMessage().contains("不稳定"));
        }
    }

    private static byte[] hpiFrame(int packetType, byte[] payload) {
        byte[] frame = new byte[6 + payload.length];
        frame[0] = (byte) 0x84;
        frame[1] = (byte) 0xa9;
        frame[2] = 0x61;
        frame[3] = (byte) (payload.length >>> 8);
        frame[4] = (byte) payload.length;
        frame[5] = (byte) packetType;
        System.arraycopy(payload, 0, frame, 6, payload.length);
        return frame;
    }

    private static byte[] hpiWireFrame(int packetType, byte[] payload) {
        byte[] frame = hpiFrame(packetType, payload);
        return (frame.length & 1) == 0
                ? frame : java.util.Arrays.copyOf(frame, frame.length + 1);
    }

    private static byte[] hexBytes(String text) {
        String compact = text.replace(" ", "").replace("\n", "")
                .replace("\r", "").replace("\t", "");
        if ((compact.length() & 1) != 0) {
            throw new IllegalArgumentException("十六进制字符数必须为偶数");
        }
        byte[] result = new byte[compact.length() / 2];
        for (int index = 0; index < result.length; index++) {
            result[index] = (byte) Integer.parseInt(
                    compact.substring(index * 2, index * 2 + 2), 16);
        }
        return result;
    }

    private static final class FakeClock
            implements InterphoneProbe.MonotonicClock {
        long now;

        @Override
        public long nowMs() {
            return now;
        }

        @Override
        public void sleepMs(long durationMs) {
            now += durationMs;
        }
    }

    private static final class ScheduledInputStream extends InputStream {
        private final FakeClock clock;
        private final long[] arrivalMs;
        private int index;

        ScheduledInputStream(FakeClock clock, long... arrivalMs) {
            this.clock = clock;
            this.arrivalMs = arrivalMs.clone();
        }

        @Override
        public int available() {
            int count = 0;
            while (index + count < arrivalMs.length
                    && arrivalMs[index + count] <= clock.now) {
                count++;
            }
            return count;
        }

        @Override
        public int read() {
            if (available() == 0) {
                return -1;
            }
            return (index++ + 1) & 0xff;
        }

        @Override
        public int read(byte[] target, int offset, int length) {
            int count = Math.min(length, available());
            if (count == 0) {
                return -1;
            }
            for (int item = 0; item < count; item++) {
                target[offset + item] = (byte) ((index++ + 1) & 0xff);
            }
            return count;
        }
    }

    private static InterphoneProbe.QuietDrainResult verifiedQuietDrain() {
        FakeClock clock = new FakeClock();
        try {
            return InterphoneProbe.drainUntilQuietCapture(
                    new ByteArrayInputStream(new byte[0]), 150, 300, clock);
        } catch (Exception error) {
            throw new AssertionError("离线静默证明夹具建立失败", error);
        }
    }

    private static byte[] plainFrame() {
        try {
            return InterphoneProbe.requirePlainExternalTxFrameUnit0();
        } catch (IOException error) {
            throw new AssertionError("冻结AMBE明文单元夹具构造失败", error);
        }
    }

    private static byte[] encryptedFrame() {
        try {
            return InterphoneProbe.requireHostEncryptedExternalTxFrameUnit0();
        } catch (IOException error) {
            throw new AssertionError("主机privacy密文夹具构造失败", error);
        }
    }

    private static final class Step4FakeIo
            implements InterphoneProbe.ExternalDmrStep4OneUnitIo {
        InterphoneProbe.QuietDrainResult quiet =
                verifiedQuietDrain();
        byte[] response = hpiFrame(0x20, new byte[] {0x01, 0x00});
        IOException quietError;
        IOException writeError;
        IOException flushError;
        IOException readError;
        int writes;
        int flushes;
        int reads;
        int quietDrains;
        byte[] written;
        long nowMs = 150L;
        long flushCompletionMs = 151L;
        long responseCaptureMs = 152L;

        @Override
        public InterphoneProbe.QuietDrainResult establishQuietWindow()
                throws Exception {
            quietDrains++;
            if (quietError != null) {
                throw quietError;
            }
            return quiet;
        }

        @Override
        public void write(byte[] frame) throws Exception {
            writes++;
            written = frame.clone();
            if (writeError != null) {
                throw writeError;
            }
        }

        @Override
        public void flush() throws Exception {
            flushes++;
            if (flushError != null) {
                throw flushError;
            }
            nowMs = flushCompletionMs;
        }

        @Override
        public byte[] readCredit() throws Exception {
            reads++;
            if (readError != null) {
                throw readError;
            }
            nowMs = responseCaptureMs;
            return response == null ? null : response.clone();
        }

        @Override
        public long nowMs() {
            return nowMs;
        }
    }

    private static final class Step4Evidence
            implements InterphoneProbe.ExternalDmrStep4OneUnitEvidence {
        boolean throwOnWriteAttempt;
        int drains;
        int attempts;
        int completions;
        int responses;

        @Override
        public void onDrain(byte[] raw, long elapsedMs) {
            drains++;
        }

        @Override
        public void onWriteAttempt(byte[] frame) throws Exception {
            attempts++;
            if (throwOnWriteAttempt) {
                throw new IOException("证据写入失败");
            }
        }

        @Override
        public void onWriteComplete() {
            completions++;
        }

        @Override
        public void onResponse(byte[] raw) {
            responses++;
        }
    }

    private static Path workspacePath(String relative) throws IOException {
        File current = new File(System.getProperty("user.dir"))
                .getCanonicalFile();
        while (current != null) {
            File candidate = new File(current,
                    relative.replace('/', File.separatorChar));
            if (candidate.exists()) {
                return candidate.toPath();
            }
            current = current.getParentFile();
        }
        throw new IOException("工作区文件不存在：" + relative);
    }

    private static InterphoneProbe.ExternalDmrStep0Contract groupStep0Contract(
            int outputSequence) {
        return InterphoneProbe.ExternalDmrStep0Contract.forProducer080177b0(
                13, 99,
                InterphoneProbe.ExternalDmrStep0Contract.CALL_TYPE_GROUP,
                0, 0, true, outputSequence, 1,
                5, 0xa3, new byte[] {0x11, 0x22, 0x33, 0x44});
    }

    private static InterphoneProbe.ExternalDmrTxModel.ExchangeWindow
            openExpectedDmrWindow(InterphoneProbe.ExternalDmrTxModel model) {
        byte[] request = model.expectedRequest();
        assertTrue(request != null);
        InterphoneProbe.ExternalDmrTxModel.ExchangeWindow window =
                model.openPostSendWindow(request,
                        InterphoneProbe.offlineQuietWindowProof());
        assertTrue(window != null);
        assertNull(model.expectedRequest());
        assertFalse(model.canEmitRequest());
        return window;
    }

    private static void acceptExpectedSetup(
            InterphoneProbe.ExternalDmrTxModel model,
            InterphoneProbe.ExternalDmrStep0Contract contract, int index) {
        InterphoneProbe.ExternalDmrTxModel.ExchangeWindow window =
                openExpectedDmrWindow(model);
        assertTrue(model.acceptSetupExchange(window,
                hpiFrame(contract.setupPacketType(index), new byte[] {
                        (byte) contract.setupAckField(index), 0x00
                })));
    }

    private static byte[] calibrationRecord() {
        byte[] record = new byte[0x19c];
        putU32Le(record, 0, 0x47a41d5bL);
        record[5] = 8;
        record[6] = 8;
        for (int index = 0; index < 8; index++) {
            putPowerAnchor(record, 0x018 + index * 8,
                    400_000_000L + index * 10_000_000L, 2000 + index);
            putPowerAnchor(record, 0x058 + index * 8,
                    400_000_000L + index * 10_000_000L, 1500 + index);
        }
        return record;
    }

    private static void putPowerAnchor(byte[] target, int offset,
            long frequencyHz, int dacCode) {
        putU32Le(target, offset, frequencyHz);
        target[offset + 4] = (byte) dacCode;
        target[offset + 5] = (byte) (dacCode >>> 8);
    }

    private static void putU32Le(byte[] target, int offset, long value) {
        target[offset] = (byte) value;
        target[offset + 1] = (byte) (value >>> 8);
        target[offset + 2] = (byte) (value >>> 16);
        target[offset + 3] = (byte) (value >>> 24);
    }

    @Test
    public void sramPreflightRequiresExactCleanBaseline() {
        byte[] flag = new byte[] {0};
        byte[] vector = new byte[] {(byte) 0xdd, 0x1d, 0x02, 0x08};
        byte[] code = new byte[64];
        byte[] counter = new byte[4];
        byte[] marker = new byte[4];
        assertTrue(InterphoneProbe.isCleanSramPreflight(
                flag, vector, code, counter, marker));

        code[0] = 1;
        assertFalse(InterphoneProbe.isCleanSramPreflight(
                flag, vector, code, counter, marker));
        code[0] = 0;
        marker[0] = 1;
        assertFalse(InterphoneProbe.isCleanSramPreflight(
                flag, vector, code, counter, marker));
    }

    @Test
    public void localChanDInputFramePreservesNative27ByteUnit() {
        byte[] source = new byte[54];
        for (int index = 0; index < source.length; index++) {
            source[index] = (byte) index;
        }
        byte[] frame = InterphoneProbe.createChanDInputFrame(source, 1);
        assertEquals(36, frame.length);
        assertArrayEquals(new byte[] {
                (byte) 0x84, (byte) 0xa9, 0x61, 0x00, 0x1d, 0x03,
                0x01, 0x1b
        }, java.util.Arrays.copyOf(frame, 8));
        assertArrayEquals(java.util.Arrays.copyOfRange(source, 27, 54),
                java.util.Arrays.copyOfRange(frame, 8, 35));
        assertEquals(0, frame[35]);
    }

    @Test
    public void shortHpiResponseScannerFindsAckAfterDataFrame() throws Exception {
        Method create = InterphoneProbe.class.getDeclaredMethod(
                "createHpiFrame", int.class, byte[].class);
        create.setAccessible(true);
        byte[] data = (byte[]) create.invoke(null, 0x30, new byte[323]);
        byte[] ack = (byte[]) create.invoke(null, 3, new byte[] {0x01, 0x00});
        byte[] combined = new byte[data.length + ack.length];
        System.arraycopy(data, 0, combined, 0, data.length);
        System.arraycopy(ack, 0, combined, data.length, ack.length);
        assertTrue(InterphoneProbe.containsShortHpiResponse(combined));
        assertFalse(InterphoneProbe.containsShortHpiResponse(data));
    }

    @Test
    public void localChanDBackpressureRecognizesVendorDmrT2Response() {
        byte[] vendorResponse = new byte[] {
                (byte) 0x84, (byte) 0xa9, 0x61, 0x00, 0x01, 0x20, 0x01
        };
        assertTrue(InterphoneProbe.containsLocalChanDBackpressure(
                vendorResponse));
        assertFalse(InterphoneProbe.containsShortHpiResponse(vendorResponse));
        assertFalse(InterphoneProbe.containsLocalChanDBackpressure(new byte[] {
                (byte) 0x84, (byte) 0xa9, 0x61, 0x00, 0x01, 0x20, 0x00
        }));
    }

    @Test
    public void vendorInputOnlyRouteMatchesDmrT2Transcript() throws Exception {
        assertPrivateFrame("HPI_VOCODER_IO_LOCAL_CHAN_D_INPUT_ONLY", new byte[] {
                (byte) 0x84, (byte) 0xa9, 0x61, 0x00, 0x02, 0x00, 0x3e, 0x08
        });
    }

    @Test
    public void externalEncodedTxRouteMatchesVendorControlFrame() throws Exception {
        assertPrivateFrame("HPI_VOCODER_IO_EXT_ENC_TX", new byte[] {
                (byte) 0x84, (byte) 0xa9, 0x61, 0x00, 0x02, 0x00, 0x3e, 0x60
        });
    }

    @Test
    public void externalEncodedTxFrameIsPrivateAndFrozenEntryPreservesUnit()
            throws Exception {
        Method generic = InterphoneProbe.class.getDeclaredMethod(
                "createExternalEncodedTxFrame", byte[].class, int.class);
        assertTrue(Modifier.isPrivate(generic.getModifiers()));

        byte[] frame = InterphoneProbe.createFrozenExternalEncodedTxFrame(1);
        assertEquals(44, frame.length);
        assertArrayEquals(new byte[] {
                (byte) 0x84, (byte) 0xa9, 0x61, 0x00, 0x26, 0x03,
                0x01, 0x24
        }, java.util.Arrays.copyOf(frame, 8));
        assertArrayEquals(java.util.Arrays.copyOfRange(
                        InterphoneProbe.REAL_RX_AMBE_108_BYTES, 36, 72),
                java.util.Arrays.copyOfRange(frame, 8, 44));
    }

    @Test
    public void dmrVlcSessionFramesMatchProductionType5Contract() {
        InterphoneProbe.ExternalDmrStep0Contract contract =
                groupStep0Contract(InterphoneProbe.ExternalDmrStep0Contract.OUTPUT_SLOT1);
        byte[] vlc1 = contract.vlcFrame(0);
        byte[] vlc2 = contract.vlcFrame(1);
        byte[] vlc3 = contract.vlcFrame(2);
        byte[] vlc4 = contract.vlcFrame(3);
        byte[] vlc5 = contract.vlcFrame(4);

        assertArrayEquals(new byte[] {
                (byte) 0x84, (byte) 0xa9, 0x61, 0x00, 0x0c, 0x05,
                0x43, 0x01, 0x09,
                0x00, 0x00, 0x40, 0x00, 0x00, 0x63, 0x00, 0x00, 0x0d
        }, vlc1);
        assertArrayEquals(vlc1, vlc2);

        assertArrayEquals(new byte[] {
                (byte) 0x84, (byte) 0xa9, 0x61, 0x00, 0x0d, 0x05,
                0x43, 0x00, 0x0a,
                0x05, 0x10, (byte) 0xa3, 0x11, 0x22, 0x33, 0x44,
                0x00, 0x00, 0x63, 0x00
        }, vlc3);

        assertArrayEquals(new byte[] {
                (byte) 0x84, (byte) 0xa9, 0x61, 0x00, 0x05, 0x05,
                0x43, 0x1f, 0x02,
                0x05, 0x1d, 0x00
        }, vlc4);
        assertArrayEquals(contract.vlcPayload(0), contract.vlcPayload(4));
        assertEquals(0x11, vlc5[7] & 0xff);
        assertEquals(0x81, contract.callSlot());
    }

    @Test
    public void externalDmrModesUseOutputSequenceNotColorCode() {
        InterphoneProbe.ExternalDmrStep0Contract slot2 =
                groupStep0Contract(InterphoneProbe.ExternalDmrStep0Contract.OUTPUT_SLOT2);
        assertEquals(0x82, slot2.callSlot());
        assertEquals(0x81, slot2.vlcMode(0));
        assertEquals(0x80, slot2.vlcMode(2));
        assertEquals(0x9f, slot2.vlcMode(3));
    }

    @Test
    public void externalDmrFiveModesMatchFixedFirmwareTransformSequence() {
        InterphoneProbe.ExternalDmrStep0Contract slot1 =
                groupStep0Contract(InterphoneProbe.ExternalDmrStep0Contract.OUTPUT_SLOT1);
        InterphoneProbe.ExternalDmrStep0Contract slot2 =
                groupStep0Contract(InterphoneProbe.ExternalDmrStep0Contract.OUTPUT_SLOT2);
        int[] expectedSlot1 = {0x01, 0x01, 0x00, 0x1f, 0x11};
        int[] expectedSlot2 = {0x81, 0x81, 0x80, 0x9f, 0x91};
        for (int index = 0; index < expectedSlot1.length; index++) {
            assertEquals(expectedSlot1[index], slot1.vlcMode(index));
            assertEquals(expectedSlot2[index], slot2.vlcMode(index));
        }
    }

    @Test
    public void externalDmrAllCallUsesType3AndAllCallBit() {
        InterphoneProbe.ExternalDmrStep0Contract contract =
                InterphoneProbe.ExternalDmrStep0Contract.forProducer080177b0(
                        13, 0xffffff,
                        InterphoneProbe.ExternalDmrStep0Contract.CALL_TYPE_ALL,
                        0, 0, true,
                        InterphoneProbe.ExternalDmrStep0Contract.OUTPUT_SLOT1,
                        1, 5, 0xa3, new byte[] {0x11, 0x22, 0x33, 0x44});
        assertEquals(0x03, contract.vlcPayload(0)[0] & 0xff);
        assertEquals(0x20 | 5, contract.vlcPayload(2)[0] & 0xff);
    }

    @Test
    public void externalDmrContractRejectsPrivacyOffAndUnknownSnapshot() {
        try {
            InterphoneProbe.ExternalDmrStep0Contract.forProducer080177b0(
                    13, 99,
                    InterphoneProbe.ExternalDmrStep0Contract.CALL_TYPE_GROUP,
                    0, 0, true,
                    InterphoneProbe.ExternalDmrStep0Contract.OUTPUT_SLOT1,
                    0, 5, 0xa3, new byte[] {0x11, 0x22, 0x33, 0x44});
            fail("Privacy-off CH1 must not serialize the stock external branch");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("privacy"));
        }
        try {
            InterphoneProbe.ExternalDmrStep0Contract.forProducer080177b0(
                    13, 99,
                    InterphoneProbe.ExternalDmrStep0Contract.CALL_TYPE_GROUP,
                    0, 0, true,
                    InterphoneProbe.ExternalDmrStep0Contract.OUTPUT_SLOT1,
                    1, null, 0xa3, new byte[] {0x11, 0x22, 0x33, 0x44});
            fail("Unknown signaling byte must be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("snapshot"));
        }
    }

    @Test
    public void reassembleAmbeBlocksToData36PreservesRealRxAmbeSequence() throws Exception {
        byte[] realRxAmbe = InterphoneProbe.REAL_RX_AMBE_108_BYTES;
        assertEquals(108, realRxAmbe.length);
        byte[] data36Units = InterphoneProbe.reassembleAmbeBlocksToData36(realRxAmbe);
        assertEquals(108, data36Units.length);
        assertArrayEquals(realRxAmbe, data36Units);

        Method sha256Method = InterphoneProbe.class.getDeclaredMethod("sha256", byte[].class);
        sha256Method.setAccessible(true);
        String hash = (String) sha256Method.invoke(null, (Object) data36Units);
        assertEquals(InterphoneProbe.REAL_RX_AMBE_108_SHA256, hash);
    }

    @Test
    public void step2FrozenData36Unit0AndExternalFrameMatchOfflineHashes()
            throws Exception {
        Method sha256Method = InterphoneProbe.class.getDeclaredMethod(
                "sha256", byte[].class);
        sha256Method.setAccessible(true);

        byte[] unit0 = InterphoneProbe.extractFrozenData36Unit(
                InterphoneProbe.REAL_RX_AMBE_108_BYTES, 0);
        assertEquals(36, unit0.length);
        assertEquals(InterphoneProbe.REAL_RX_DATA36_UNIT0_SHA256,
                sha256Method.invoke(null, (Object) unit0));

        byte[] frame = InterphoneProbe.createFrozenExternalEncodedTxFrame(0);
        assertEquals(44, frame.length);
        assertArrayEquals(new byte[] {
                (byte) 0x84, (byte) 0xa9, 0x61, 0x00, 0x26, 0x03, 0x01, 0x24
        }, java.util.Arrays.copyOf(frame, 8));
        assertArrayEquals(unit0, java.util.Arrays.copyOfRange(frame, 8, 44));
        assertEquals(InterphoneProbe.REAL_RX_EXTERNAL_TX_FRAME_UNIT0_SHA256,
                sha256Method.invoke(null, (Object) frame));

        // 三个 data36 单元拼回 108 字节必须等于冻结资产
        byte[] rebuilt = new byte[108];
        for (int index = 0; index < 3; index++) {
            System.arraycopy(
                    InterphoneProbe.extractFrozenData36Unit(
                            InterphoneProbe.REAL_RX_AMBE_108_BYTES, index),
                    0, rebuilt, index * 36, 36);
        }
        assertArrayEquals(InterphoneProbe.REAL_RX_AMBE_108_BYTES, rebuilt);
    }

    @Test
    public void step2FrozenAssetGateRejectsSyntheticSources() {
        try {
            InterphoneProbe.extractFrozenData36Unit(new byte[108], 0);
            fail("合成 108 字节不得通过冻结门");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("冻结"));
        }
        try {
            InterphoneProbe.extractFrozenData36Unit(
                    InterphoneProbe.REAL_RX_AMBE_108_BYTES, 3);
            fail("越界 unitIndex 必须失败");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("unitIndex"));
        }
    }

    @Test
    public void step2DiskAssetsAndDecodeHashesMatchFrozenConstants()
            throws Exception {
        Path directory = workspacePath(
                InterphoneProbe.REAL_RX_STEP2_ASSET_DIRECTORY);
        byte[] source = Files.readAllBytes(directory.resolve(
                "00_source_h13_chan_d_stream.bin"));
        byte[] path27 = Files.readAllBytes(directory.resolve(
                "10_frames_from_4x27_boundary_path.bin"));
        byte[] path36 = Files.readAllBytes(directory.resolve(
                "11_frames_from_3x36_boundary_path.bin"));
        byte[] pcm27 = Files.readAllBytes(directory.resolve(
                "08_pcm_from_4x27_boundary_path.s16le"));
        byte[] pcm36 = Files.readAllBytes(directory.resolve(
                "09_pcm_from_3x36_boundary_path.s16le"));
        byte[] wav27 = Files.readAllBytes(directory.resolve(
                "06_decode_from_4x27_boundary_path.wav"));
        byte[] wav36 = Files.readAllBytes(directory.resolve(
                "07_decode_from_3x36_boundary_path.wav"));

        Method sha256Method = InterphoneProbe.class.getDeclaredMethod(
                "sha256", byte[].class);
        sha256Method.setAccessible(true);
        assertEquals(6588, source.length);
        assertEquals(InterphoneProbe.REAL_RX_CHAN_D_STREAM_SHA256,
                sha256Method.invoke(null, (Object) source));
        assertArrayEquals(InterphoneProbe.REAL_RX_AMBE_108_BYTES,
                java.util.Arrays.copyOf(source, 108));
        assertArrayEquals(path27, path36);
        assertArrayEquals(InterphoneProbe.REAL_RX_AMBE_108_BYTES, path27);
        assertEquals(3840, pcm27.length);
        assertArrayEquals(pcm27, pcm36);
        assertEquals(InterphoneProbe.REAL_RX_AMBE_108_PCM_S16LE_SHA256,
                sha256Method.invoke(null, (Object) pcm27));
        assertArrayEquals(wav27, wav36);
        assertEquals(InterphoneProbe.REAL_RX_AMBE_108_WAV_SHA256,
                sha256Method.invoke(null, (Object) wav27));
        assertTrue(Files.isRegularFile(directory.resolve("MANIFEST.json")));
        assertTrue(Files.isRegularFile(directory.resolve("MANIFEST.txt")));
    }

    @Test
    public void vlcAckMatcherAcceptsType5OrType20Body43WithOptionalTrailer() {
        // 夹具：HPI type 5, body [0x43]
        byte[] validVlcAck = hpiWireFrame(5, new byte[] {0x43});
        assertTrue(InterphoneProbe.containsDmrVlcSessionAck(validVlcAck));

        // 2026-08-10 真机：type 0x20 body 0x43
        byte[] deviceType20Ack = hpiWireFrame(0x20, new byte[] {0x43});
        assertTrue(InterphoneProbe.containsDmrVlcSessionAck(deviceType20Ack));

        // 真机 vlc0 多帧：ack + 后续 type0x20 事件（边界完整）
        byte[] deviceMulti = concatBytes(
                hpiWireFrame(0x20, new byte[] {0x43}),
                hpiWireFrame(0x20, new byte[] {
                        0x01, 0x1b, (byte) 0xc8, 0x09, (byte) 0xec, 0x18, 0x04,
                        (byte) 0xaf, (byte) 0xdf, (byte) 0x83, 0x21, (byte) 0xb8,
                        0x09, (byte) 0xc5, (byte) 0x93, (byte) 0xa5, (byte) 0x9b,
                        (byte) 0xf5, 0x03, 0x21, (byte) 0xb8, 0x07, (byte) 0xd0,
                        (byte) 0xdd, (byte) 0x80, (byte) 0x91, (byte) 0x87,
                        (byte) 0x8b, 0x21
                }),
                hpiWireFrame(0x20, new byte[] {0x17, 0x0a}));
        assertTrue(InterphoneProbe.containsDmrVlcSessionAck(deviceMulti));

        // 真机 v1.67 vlc1：先 CHAN_D/事件，后 type0x20 body 0x43（ACK 非首帧）
        byte[] ackAfterChanD = concatBytes(
                hpiWireFrame(0x20, new byte[] {
                        0x01, 0x1b, (byte) 0x98, 0x02, (byte) 0xb9, 0x4f,
                        (byte) 0xa4, (byte) 0xd3, (byte) 0xdf, (byte) 0x8b,
                        0x25, (byte) 0xea, 0x1d, 0x4b, (byte) 0x9e, 0x42,
                        (byte) 0xe3, (byte) 0xdf, (byte) 0x8b, 0x25, (byte) 0xad,
                        0x02, (byte) 0xf2, (byte) 0x9d, 0x54, 0x3a, (byte) 0xdd
                }),
                hpiWireFrame(0x20, new byte[] {0x43}),
                hpiWireFrame(0x20, new byte[] {0x17, 0x0a}));
        assertTrue(InterphoneProbe.containsDmrVlcSessionAck(ackAfterChanD));

        // Wrong HPI type 3 instead of 5/0x20
        byte[] wrongTypeAck = hpiWireFrame(3, new byte[] {0x43});
        assertFalse(InterphoneProbe.containsDmrVlcSessionAck(wrongTypeAck));

        // Wrong body 0x00 instead of 0x43
        byte[] wrongBodyAck = hpiWireFrame(5, new byte[] {0x00});
        assertFalse(InterphoneProbe.containsDmrVlcSessionAck(wrongBodyAck));

        byte[] validWithExtraBody = hpiFrame(5,
                new byte[] {0x43, 0x55});
        assertFalse(InterphoneProbe.containsDmrVlcSessionAck(
                validWithExtraBody));

        byte[] missingPadding = hpiFrame(5, new byte[] {0x43});
        assertFalse(InterphoneProbe.containsDmrVlcSessionAck(missingPadding));
        byte[] nonzeroPadding = hpiWireFrame(5, new byte[] {0x43});
        nonzeroPadding[nonzeroPadding.length - 1] = 0x55;
        assertFalse(InterphoneProbe.containsDmrVlcSessionAck(nonzeroPadding));
        // 两个完整 type5 ack 粘连：首帧仍是合法确认
        byte[] duplicate = new byte[validVlcAck.length * 2];
        System.arraycopy(validVlcAck, 0, duplicate, 0, validVlcAck.length);
        System.arraycopy(validVlcAck, 0, duplicate, validVlcAck.length,
                validVlcAck.length);
        assertTrue(InterphoneProbe.containsDmrVlcSessionAck(duplicate));
    }

    private static byte[] concatBytes(byte[]... parts) {
        int n = 0;
        for (byte[] p : parts) {
            n += p.length;
        }
        byte[] out = new byte[n];
        int o = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, o, p.length);
            o += p.length;
        }
        return out;
    }

    @Test
    public void vlcAckMatcherRequiresCompleteDeclaredFrameBoundaries() {
        byte[] valid = hpiWireFrame(5, new byte[] {0x43});
        byte[] leadingNoise = new byte[valid.length + 1];
        leadingNoise[0] = 0x55;
        System.arraycopy(valid, 0, leadingNoise, 1, valid.length);
        assertFalse(InterphoneProbe.containsDmrVlcSessionAck(leadingNoise));

        byte[] outerPayload = hpiFrame(3, valid);
        assertFalse(InterphoneProbe.containsDmrVlcSessionAck(outerPayload));

        byte[] truncatedThenFake = new byte[] {
                (byte) 0x84, (byte) 0xa9, 0x61, 0x00, 0x20, 0x03,
                (byte) 0x84, (byte) 0xa9, 0x61, 0x00, 0x01, 0x05, 0x43
        };
        assertFalse(InterphoneProbe.containsDmrVlcSessionAck(
                truncatedThenFake));

        byte[] validWithTrailingNoise = java.util.Arrays.copyOf(valid,
                valid.length + 1);
        validWithTrailingNoise[valid.length] = 0x55;
        assertFalse(InterphoneProbe.containsDmrVlcSessionAck(
                validWithTrailingNoise));
    }


    @Test
    public void dmrCallSlotAckRequiresType5Field6fAndStatusZero() {
        byte[] valid = hpiFrame(5, new byte[] {0x6f, 0x00});
        assertTrue(InterphoneProbe.containsControlStatusAck(valid, 5, 0x6f));
        assertFalse(InterphoneProbe.containsControlStatusAck(
                hpiFrame(0, new byte[] {0x6f, 0x00}), 5, 0x6f));
        assertFalse(InterphoneProbe.containsControlStatusAck(
                hpiFrame(5, new byte[] {0x6f, 0x01}), 5, 0x6f));
    }

    @Test(expected = IllegalArgumentException.class)
    public void reassembleAmbeBlocksRejectsNonMultiple27() {
        InterphoneProbe.reassembleAmbeBlocksToData36(new byte[26]);
    }

    @Test
    public void externalEncodedTxCreditDoesNotAcceptVlcAck() {
        assertFalse(InterphoneProbe.containsExternalEncodedTxCredit(new byte[] {
                (byte) 0x84, (byte) 0xa9, 0x61, 0x00, 0x01, 0x05, 0x43
        }));
    }


    @Test
    public void externalEncodedTxCreditUsesVendorShortResponseContract() {
        byte[] data = hpiFrame(3, new byte[38]);
        byte[] credit = new byte[] {
                (byte) 0x84, (byte) 0xa9, 0x61, 0x00, 0x02, 0x20,
                0x01, 0x00
        };
        byte[] combined = new byte[data.length + credit.length];
        System.arraycopy(data, 0, combined, 0, data.length);
        System.arraycopy(credit, 0, combined, data.length, credit.length);
        assertTrue(InterphoneProbe.containsExternalEncodedTxCredit(combined));
        assertFalse(InterphoneProbe.containsExternalEncodedTxCredit(new byte[] {
                (byte) 0x84, (byte) 0xa9, 0x61, 0x00, 0x01, 0x20, 0x02
        }));
        assertFalse(InterphoneProbe.containsExternalEncodedTxCredit(new byte[] {
                (byte) 0x84, (byte) 0xa9, 0x61, 0x00, 0x02, 0x20, 0x01
        }));
    }

    @Test
    public void externalEncodedTxCreditRequiresRealFrameBoundaries() {
        byte[] embeddedCredit = hpiFrame(0x20, new byte[] {0x01, 0x00});
        byte[] payload = new byte[40];
        System.arraycopy(embeddedCredit, 0, payload, 9, embeddedCredit.length);
        byte[] dataOnly = hpiFrame(0x30, payload);
        assertFalse(InterphoneProbe.containsExternalEncodedTxCredit(dataOnly));

        byte[] truncated = java.util.Arrays.copyOf(dataOnly, 18);
        byte[] fakeAfterTruncation = new byte[truncated.length
                + embeddedCredit.length];
        System.arraycopy(truncated, 0, fakeAfterTruncation, 0,
                truncated.length);
        System.arraycopy(embeddedCredit, 0, fakeAfterTruncation,
                truncated.length, embeddedCredit.length);
        assertFalse(InterphoneProbe.containsExternalEncodedTxCredit(
                fakeAfterTruncation));

        byte[] paddedData = hpiFrame(0x30, new byte[] {0x7f});
        byte[] framedCredit = new byte[paddedData.length + 1
                + embeddedCredit.length];
        System.arraycopy(paddedData, 0, framedCredit, 0, paddedData.length);
        System.arraycopy(embeddedCredit, 0, framedCredit,
                paddedData.length + 1, embeddedCredit.length);
        assertTrue(InterphoneProbe.containsExternalEncodedTxCredit(
                framedCredit));
        framedCredit[paddedData.length] = 0x55;
        assertFalse(InterphoneProbe.containsExternalEncodedTxCredit(
                framedCredit));
    }

    @Test
    public void externalDmrTxModelRequiresExactSetupAndVlcOrder() {
        InterphoneProbe.ExternalDmrStep0Contract contract =
                groupStep0Contract(InterphoneProbe.ExternalDmrStep0Contract.OUTPUT_SLOT1);
        InterphoneProbe.ExternalDmrTxModel model =
                new InterphoneProbe.ExternalDmrTxModel(contract);
        assertEquals(InterphoneProbe.ExternalDmrTxModel.Phase.SETUP,
                model.phase());

        for (int index = 0; index < contract.setupCount(); index++) {
            byte[] request = contract.setupFrame(index);
            assertArrayEquals(request, model.expectedRequest());
            acceptExpectedSetup(model, contract, index);
        }
        assertEquals(InterphoneProbe.ExternalDmrTxModel.Phase.VLC_SESSION,
                model.phase());

        for (int index = 0; index < 5; index++) {
            assertArrayEquals(contract.vlcFrame(index), model.expectedRequest());
            InterphoneProbe.ExternalDmrTxModel.ExchangeWindow window =
                    openExpectedDmrWindow(model);
            assertEquals(6 + index, window.sequence());
            assertTrue(model.acceptVlcExchange(window,
                    hpiWireFrame(5, new byte[] {0x43})));
        }
        assertEquals(InterphoneProbe.ExternalDmrTxModel.Phase.COMPLETE,
                model.phase());
        assertEquals(5, model.setupAcks());
        assertEquals(5, model.vlcAcks());
        assertNull(model.expectedRequest());
    }

    @Test
    public void externalDmrTxModelFailsOnOutOfOrderRequest() {
        InterphoneProbe.ExternalDmrStep0Contract contract =
                groupStep0Contract(InterphoneProbe.ExternalDmrStep0Contract.OUTPUT_SLOT1);
        InterphoneProbe.ExternalDmrTxModel model =
                new InterphoneProbe.ExternalDmrTxModel(contract);
        assertNull(model.openPostSendWindow(contract.setupFrame(1),
                InterphoneProbe.offlineQuietWindowProof()));
        assertEquals(InterphoneProbe.ExternalDmrTxModel.Phase.FAILED,
                model.phase());
        assertNull(model.expectedRequest());
    }

    @Test
    public void externalDmrSetupFramesMatchStep0WireContract() {
        InterphoneProbe.ExternalDmrStep0Contract contract =
                groupStep0Contract(InterphoneProbe.ExternalDmrStep0Contract.OUTPUT_SLOT1);
        assertArrayEquals(hpiFrame(0, new byte[] {0x19, 0x00}),
                contract.setupFrame(0));
        assertArrayEquals(hpiFrame(0, new byte[] {0x02, 0x18}),
                contract.setupFrame(1));
        assertArrayEquals(hpiFrame(0, new byte[] {0x3e, 0x60}),
                contract.setupFrame(2));
        assertArrayEquals(hpiFrame(5, new byte[] {0x6f, (byte) 0x81}),
                contract.setupFrame(3));
        assertArrayEquals(hpiFrame(0, new byte[] {0x18, 0x02, 0x00, 0x00}),
                contract.setupFrame(4));
    }

    @Test
    public void externalDmrTxModelRejectsNullAckWrongStatusAndStaleVlcDuringSetup() {
        InterphoneProbe.ExternalDmrStep0Contract contract =
                groupStep0Contract(InterphoneProbe.ExternalDmrStep0Contract.OUTPUT_SLOT1);
        InterphoneProbe.ExternalDmrTxModel model =
                new InterphoneProbe.ExternalDmrTxModel(contract);
        InterphoneProbe.ExternalDmrTxModel.ExchangeWindow window =
                openExpectedDmrWindow(model);
        assertFalse(model.acceptSetupExchange(window, null));
        assertEquals(InterphoneProbe.ExternalDmrTxModel.Phase.FAILED,
                model.phase());
        assertNull(model.expectedRequest());
        assertFalse(model.canEmitRequest());

        model = new InterphoneProbe.ExternalDmrTxModel(contract);
        window = openExpectedDmrWindow(model);
        assertFalse(model.acceptSetupExchange(window,
                hpiFrame(0, new byte[] {0x19, 0x01})));
        assertEquals(InterphoneProbe.ExternalDmrTxModel.Phase.FAILED,
                model.phase());

        model = new InterphoneProbe.ExternalDmrTxModel(contract);
        window = openExpectedDmrWindow(model);
        assertFalse(model.acceptSetupExchange(window,
                hpiWireFrame(5, new byte[] {0x43})));
        assertEquals(InterphoneProbe.ExternalDmrTxModel.Phase.FAILED,
                model.phase());
        assertNull(model.expectedRequest());
    }

    @Test
    public void externalDmrTxModelFirstFailureBlocksAllLaterExchanges() {
        InterphoneProbe.ExternalDmrStep0Contract contract =
                groupStep0Contract(InterphoneProbe.ExternalDmrStep0Contract.OUTPUT_SLOT1);
        InterphoneProbe.ExternalDmrTxModel model =
                new InterphoneProbe.ExternalDmrTxModel(contract);
        InterphoneProbe.ExternalDmrTxModel.ExchangeWindow window =
                openExpectedDmrWindow(model);
        assertFalse(model.acceptTimeout(window));
        assertEquals(InterphoneProbe.ExternalDmrTxModel.Phase.FAILED,
                model.phase());
        assertNull(model.expectedRequest());
        assertNull(model.openPostSendWindow(contract.setupFrame(0),
                InterphoneProbe.offlineQuietWindowProof()));
        assertFalse(model.acceptSetupExchange(window,
                hpiFrame(0, new byte[] {0x19, 0x00})));
        assertFalse(model.acceptVlcExchange(window,
                hpiWireFrame(5, new byte[] {0x43})));
        assertEquals(0, model.setupAcks());
        assertEquals(0, model.vlcAcks());
        assertFalse(model.canSerializeData36());
    }

    @Test
    public void setup0EmptyRetryAbandonsWindowOnceThenAcceptsSecondSend() {
        InterphoneProbe.ExternalDmrStep0Contract contract =
                groupStep0Contract(InterphoneProbe.ExternalDmrStep0Contract.OUTPUT_SLOT1);
        InterphoneProbe.ExternalDmrTxModel model =
                new InterphoneProbe.ExternalDmrTxModel(contract);
        InterphoneProbe.ExternalDmrTxModel.ExchangeWindow window =
                openExpectedDmrWindow(model);
        assertTrue(model.abandonActiveWindowForSetup0EmptyRetry(window));
        assertTrue(model.setup0EmptyRetryConsumed());
        assertEquals(InterphoneProbe.ExternalDmrTxModel.Phase.SETUP,
                model.phase());
        assertEquals(0, model.setupAcks());
        // 第二次原样写出后严格确认
        window = openExpectedDmrWindow(model);
        assertTrue(model.acceptSetupExchange(window,
                hpiFrame(0, new byte[] {0x19, 0x00})));
        assertEquals(1, model.setupAcks());
        // 禁止第三次空重试
        window = openExpectedDmrWindow(model);
        assertFalse(model.abandonActiveWindowForSetup0EmptyRetry(window));
        assertEquals(InterphoneProbe.ExternalDmrTxModel.Phase.FAILED,
                model.phase());
    }

    @Test
    public void setup0EmptyRetryRejectedAfterAnyProgressOrSecondUse() {
        InterphoneProbe.ExternalDmrStep0Contract contract =
                groupStep0Contract(InterphoneProbe.ExternalDmrStep0Contract.OUTPUT_SLOT1);
        InterphoneProbe.ExternalDmrTxModel model =
                new InterphoneProbe.ExternalDmrTxModel(contract);
        // 首次严格成功后不得再“空重试”
        InterphoneProbe.ExternalDmrTxModel.ExchangeWindow window =
                openExpectedDmrWindow(model);
        assertTrue(model.acceptSetupExchange(window,
                hpiFrame(0, new byte[] {0x19, 0x00})));
        window = openExpectedDmrWindow(model);
        assertFalse(model.abandonActiveWindowForSetup0EmptyRetry(window));
        assertEquals(InterphoneProbe.ExternalDmrTxModel.Phase.FAILED,
                model.phase());

        // 畸形帧走 fail，不走空重试路径（模型侧已 FAILED）
        model = new InterphoneProbe.ExternalDmrTxModel(contract);
        window = openExpectedDmrWindow(model);
        assertFalse(model.acceptSetupExchange(window,
                hpiFrame(0, new byte[] {0x19, 0x01})));
        assertFalse(model.abandonActiveWindowForSetup0EmptyRetry(window));
    }

    @Test
    public void setup0OnlyBudgetFitsBridgeWithOneEmptyRetry() {
        // v1.92：settle2000 + 2*(800+400) + 2000 = 2000+2400+2000 = 6400
        assertEquals(2000L, InterphoneProbe.postBridgeSettleMs(true));
        assertEquals(500L, InterphoneProbe.postBridgeSettleMs(false));
        assertEquals(6400L, InterphoneProbe.externalDmrSetup0OnlyWorstCaseMs());
        assertTrue(InterphoneProbe.externalDmrSetup0OnlyBudgetFitsBridgeWindow());
        assertTrue(InterphoneProbe.externalDmrSetup0OnlyWorstCaseMs() + 2000L
                < 18000L);
        // v2.03实时路径：500 + (800+400) + 800 + 4*800 = 5700，
        // 小于fullprep 5000+1200门，故不改变整体15520ms预算。
        assertEquals(5700L,
                InterphoneProbe.externalDmrRealtimeSetupPassWorstCaseMs());
        assertTrue(InterphoneProbe.externalDmrRealtimeSetupFitsPrepGate());
    }

    @Test
    public void externalDmrTxModelSetupRejectsTailButVlcAllowsDeviceTrailer() {
        InterphoneProbe.ExternalDmrStep0Contract contract =
                groupStep0Contract(InterphoneProbe.ExternalDmrStep0Contract.OUTPUT_SLOT1);
        InterphoneProbe.ExternalDmrTxModel model =
                new InterphoneProbe.ExternalDmrTxModel(contract);
        InterphoneProbe.ExternalDmrTxModel.ExchangeWindow window =
                openExpectedDmrWindow(model);
        // setup 仍要求精确单帧 status ack
        byte[] setupAck = hpiFrame(0, new byte[] {0x19, 0x00});
        byte[] setupWithError = new byte[setupAck.length * 2];
        System.arraycopy(setupAck, 0, setupWithError, 0, setupAck.length);
        System.arraycopy(hpiFrame(0, new byte[] {0x17, 0x06}), 0,
                setupWithError, setupAck.length, setupAck.length);
        assertFalse(model.acceptSetupExchange(window, setupWithError));

        // VLC：真机 type0x20 + 后续完整事件帧应接受
        model = new InterphoneProbe.ExternalDmrTxModel(contract);
        for (int index = 0; index < contract.setupCount(); index++) {
            acceptExpectedSetup(model, contract, index);
        }
        window = openExpectedDmrWindow(model);
        byte[] deviceVlc0 = concatBytes(
                hpiWireFrame(0x20, new byte[] {0x43}),
                hpiWireFrame(0x20, new byte[] {0x17, 0x0a}));
        assertTrue(model.acceptVlcExchange(window, deviceVlc0));
        assertEquals(1, model.vlcAcks());
    }

    @Test
    public void externalDmrTxModelRejectsWrongVlcAckAndDuplicateAfterComplete() {
        InterphoneProbe.ExternalDmrStep0Contract contract =
                groupStep0Contract(InterphoneProbe.ExternalDmrStep0Contract.OUTPUT_SLOT1);
        InterphoneProbe.ExternalDmrTxModel model =
                new InterphoneProbe.ExternalDmrTxModel(contract);
        for (int index = 0; index < contract.setupCount(); index++) {
            acceptExpectedSetup(model, contract, index);
        }
        InterphoneProbe.ExternalDmrTxModel.ExchangeWindow window =
                openExpectedDmrWindow(model);
        assertFalse(model.acceptVlcExchange(window,
                hpiWireFrame(5, new byte[] {0x00})));
        assertEquals(InterphoneProbe.ExternalDmrTxModel.Phase.FAILED,
                model.phase());

        model = new InterphoneProbe.ExternalDmrTxModel(contract);
        for (int index = 0; index < contract.setupCount(); index++) {
            acceptExpectedSetup(model, contract, index);
        }
        InterphoneProbe.ExternalDmrTxModel.ExchangeWindow completedWindow = null;
        for (int index = 0; index < 5; index++) {
            completedWindow = openExpectedDmrWindow(model);
            assertTrue(model.acceptVlcExchange(completedWindow,
                    hpiWireFrame(5, new byte[] {0x43})));
        }
        assertEquals(InterphoneProbe.ExternalDmrTxModel.Phase.COMPLETE,
                model.phase());
        assertFalse(model.acceptVlcExchange(completedWindow,
                hpiWireFrame(5, new byte[] {0x43})));
        assertEquals(InterphoneProbe.ExternalDmrTxModel.Phase.FAILED,
                model.phase());
        assertNull(model.expectedRequest());
        assertFalse(model.canSerializeData36());
    }

    @Test
    public void externalDmrTxModelRejectsUndrainedReusedAndStaleWindows() {
        InterphoneProbe.ExternalDmrStep0Contract contract =
                groupStep0Contract(InterphoneProbe.ExternalDmrStep0Contract.OUTPUT_SLOT1);
        InterphoneProbe.ExternalDmrTxModel model =
                new InterphoneProbe.ExternalDmrTxModel(contract);
        assertNull(model.openPostSendWindow(model.expectedRequest(), null));
        assertEquals(InterphoneProbe.ExternalDmrTxModel.Phase.FAILED,
                model.phase());

        model = new InterphoneProbe.ExternalDmrTxModel(contract);
        InterphoneProbe.ExternalDmrTxModel.ExchangeWindow first =
                openExpectedDmrWindow(model);
        assertEquals(1, first.sequence());
        assertTrue(model.acceptSetupExchange(first,
                hpiFrame(0, new byte[] {0x19, 0x00})));
        InterphoneProbe.ExternalDmrTxModel.ExchangeWindow second =
                openExpectedDmrWindow(model);
        assertEquals(2, second.sequence());
        assertFalse(model.acceptSetupExchange(first,
                hpiFrame(0, new byte[] {0x02, 0x00})));
        assertEquals(InterphoneProbe.ExternalDmrTxModel.Phase.FAILED,
                model.phase());
        assertNull(model.expectedRequest());
        assertFalse(model.acceptSetupExchange(second,
                hpiFrame(0, new byte[] {0x02, 0x00})));
    }

    @Test
    public void step1OfflineHandshakeProofPassesWithoutSerial() {
        ProbeResult result =
                InterphoneProbe.evaluateExternalDmrStep1OfflineHandshake();
        assertTrue(result.success);
        assertTrue(result.report.contains("Serial opened: false"));
        assertTrue(result.report.contains("通过"));
        assertTrue(result.report.contains("callslot: 0x81")
                || result.report.contains("0x81"));
        assertTrue(result.report.contains("Data36: 禁止")
                || result.report.contains("禁止"));
        assertTrue(result.report.contains("setupAcks: 5"));
        assertTrue(result.report.contains("vlcAcks: 5"));
    }

    @Test
    public void step0ContractRejectsPrivacyOff() {
        try {
            InterphoneProbe.ExternalDmrStep0Contract.forProducer080177b0(
                    13, 99,
                    InterphoneProbe.ExternalDmrStep0Contract.CALL_TYPE_GROUP,
                    0, 0, true,
                    InterphoneProbe.ExternalDmrStep0Contract.OUTPUT_SLOT1,
                    0, 5, 0xa3, new byte[] {0x11, 0x22, 0x33, 0x44});
            fail("privacy-off 不得构造合同");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("privacy"));
        }
    }

    @Test
    public void step3PinnedRuntimeContractMatchesOnlyExactCandidate() {
        InterphoneProbe.ExternalDmrStep0Contract contract = InterphoneProbe
                .createPinnedExternalDmrRuntimeContract();
        assertTrue(contract != null);
        // 离线夹具合同：自洽于 flags[1]/[2] 与 session[5..8]。
        assertTrue(contract.matchesPrivacyRuntime(
                new byte[] {1, 1, 1, 1},
                new byte[] {1},
                new byte[] {0x12, 0x34, 0x56, 0x78, (byte) 0x90,
                        0x12, 0x34, 0x56, 0x78}));
        // 真机预检形态：flags[3]=0、dataGate=0 仍可自洽。
        assertTrue(contract.matchesPrivacyRuntime(
                new byte[] {1, 1, 1, 0},
                new byte[] {0},
                new byte[] {0x12, 0x34, 0x56, 0x78, (byte) 0x90,
                        0x12, 0x34, 0x56, 0x78}));
        // 任意前缀会话字节亦可，只要 [5..8] 与合同 sessionAddress 一致。
        assertTrue(contract.matchesPrivacyRuntime(
                new byte[] {1, 1, 1, 1},
                new byte[] {1},
                new byte[] {(byte) 0xaa, (byte) 0xbb, (byte) 0xcc, (byte) 0xdd,
                        (byte) 0xee, 0x12, 0x34, 0x56, 0x78}));
        assertFalse(contract.matchesPrivacyRuntime(
                new byte[] {1, 1, 0, 1}, new byte[] {1}, new byte[9]));
        assertFalse(contract.matchesPrivacyRuntime(
                new byte[] {1, 1, 1, 1}, new byte[] {0},
                new byte[] {0x12, 0x34, 0x56, 0x78, (byte) 0x90,
                        0x00, 0x00, 0x00, 0x00}));
    }

    @Test
    public void measuredRuntimeContractMatchesDeviceSnapshotNotFixture() {
        // 模拟本机实测：flags/session 不是 01 01…/12 34… 合成图案。
        byte[] measured = new byte[] {
                0x01, 0x05, (byte) 0xa3, 0x01, 0x01,
                0x11, 0x22, 0x33, 0x44, 0x55,
                0x66, 0x77, (byte) 0x88, (byte) 0x99
        };
        InterphoneProbe.ExternalDmrStep0Contract contract =
                InterphoneProbe.createContractFromMeasuredRuntime(measured);
        assertTrue(contract.matchesPrivacyRuntime(
                java.util.Arrays.copyOfRange(measured, 0, 4),
                java.util.Arrays.copyOfRange(measured, 4, 5),
                java.util.Arrays.copyOfRange(measured, 5, 14)));
        // 合成夹具图案不得冒充该实测合同。
        assertFalse(contract.matchesPrivacyRuntime(
                new byte[] {1, 1, 1, 1},
                new byte[] {1},
                new byte[] {0x12, 0x34, 0x56, 0x78, (byte) 0x90,
                        0x12, 0x34, 0x56, 0x78}));
    }

    @Test
    public void h13DevicePrivacyOnSnapshotFromFirstPreflightIsContractable() {
        // 2026-08-10 第一次真机预检 privacy-on 三轮稳定值。
        byte[] deviceOn = new byte[] {
                0x01, 0x01, 0x01, 0x00, 0x00,
                0x12, 0x34, 0x56, 0x78, (byte) 0x90,
                0x12, 0x34, 0x56, 0x78
        };
        InterphoneProbe.ExternalDmrStep0Contract contract =
                InterphoneProbe.createContractFromMeasuredRuntime(deviceOn);
        assertTrue(contract.matchesPrivacyRuntime(
                java.util.Arrays.copyOfRange(deviceOn, 0, 4),
                java.util.Arrays.copyOfRange(deviceOn, 4, 5),
                java.util.Arrays.copyOfRange(deviceOn, 5, 14)));
    }

    @Test
    public void privacyOffRestoreAcceptsStableResidualNotEqualBaseline()
            throws Exception {
        byte[] baseline = new byte[] {
                0x00, 0x00, 0x00, 0x01, 0x00,
                0x00, 0x00, 0x00, 0x00, 0x00,
                0x00, 0x00, 0x00, 0x00
        };
        byte[] residual = new byte[] {
                0x01, 0x01, 0x01, 0x00, 0x00,
                0x12, 0x34, 0x56, 0x78, (byte) 0x90,
                0x12, 0x34, 0x56, 0x78
        };
        assertTrue(InterphoneProbe.acceptExternalDmrPrivacyOffRuntimeRestore(
                baseline, new byte[][] {baseline, baseline.clone(),
                        baseline.clone()}));
        assertFalse(InterphoneProbe.acceptExternalDmrPrivacyOffRuntimeRestore(
                baseline, new byte[][] {residual, residual.clone(),
                        residual.clone()}));
        try {
            InterphoneProbe.acceptExternalDmrPrivacyOffRuntimeRestore(
                    baseline, new byte[][] {residual, baseline, residual});
            fail("不稳定残留必须拒绝");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("不稳定")
                    || expected.getMessage().contains("不完整"));
        }
        // 严格 API 仍要求逐字节
        try {
            InterphoneProbe.requireExternalDmrPrivacyOffRuntimeRestored(
                    baseline, new byte[][] {residual, residual.clone(),
                            residual.clone()});
            fail("严格恢复不得接受残留");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("未逐字节"));
        }
    }

    @Test
    public void externalDmrLowPowerChannelAndPreflightAttestationArePinned()
            throws Exception {
        InterphoneProbe.requirePinnedExternalDmrLowPowerChannel();
        byte[] measured = InterphoneProbe.offlineFixturePrivacyRuntime14();
        byte[] attestation =
                InterphoneProbe.externalDmrPreflightAttestationBytes(measured);
        String text = new String(attestation, StandardCharsets.UTF_8);
        assertTrue(text.contains(
                "构建=" + net.elfradio.h13interphoneprobe.BuildConfig.VERSION_NAME));
        assertTrue(text.contains(
                "构建号=" + net.elfradio.h13interphoneprobe.BuildConfig.VERSION_CODE));
        assertTrue(text.contains("目标频道=433550000,433550000,13,99,"
                + "12345678,directmode,group,slot1,slot1,on,low,8,2,2,99"));
        assertTrue(text.contains("恢复频道=433550000,433550000,13,99,"
                + "12345678,directmode,group,slot1,slot1,off,low,8,2,2,99"));
        assertTrue(text.contains("运行快照实测=0101010101123456789012345678"));
        assertTrue(text.contains("运行快照SHA-256="));
        assertTrue(text.contains("快照为本机实测"));

        // 非夹具实测也可嵌入凭证。
        byte[] otherMeasured = new byte[] {
                0x01, 0x05, (byte) 0xa3, 0x01, 0x01,
                0x11, 0x22, 0x33, 0x44, 0x55,
                0x66, 0x77, (byte) 0x88, (byte) 0x99
        };
        byte[] otherAttestation =
                InterphoneProbe.externalDmrPreflightAttestationBytes(
                        otherMeasured);
        String otherText = new String(otherAttestation, StandardCharsets.UTF_8);
        assertTrue(otherText.contains("运行快照实测=0105a30101112233445566778899"));
        assertArrayEquals(otherMeasured,
                InterphoneProbe.parseMeasuredRuntimeFromAttestationBytes(
                        otherAttestation));

        // 缺构建号的旧凭证必须拒绝（禁止改码后复用）。
        int code = net.elfradio.h13interphoneprobe.BuildConfig.VERSION_CODE;
        String withoutCode = text.replaceFirst(
                "构建号=" + code + "\\n", "");
        assertNull(InterphoneProbe.parseMeasuredRuntimeFromAttestationBytes(
                withoutCode.getBytes(StandardCharsets.UTF_8)));
        String wrongCode = text.replace(
                "构建号=" + code, "构建号=240");
        assertNull(InterphoneProbe.parseMeasuredRuntimeFromAttestationBytes(
                wrongCode.getBytes(StandardCharsets.UTF_8)));

        Path directory = Files.createTempDirectory("h13-v241-attestation-");
        InterphoneProbe probe = new InterphoneProbe(directory.toFile());
        assertFalse(probe.hasValidExternalDmrPreflightAttestation());
        Method persist = InterphoneProbe.class.getDeclaredMethod(
                "persistExternalDmrPreflightAttestation",
                StringBuilder.class, byte[].class);
        persist.setAccessible(true);
        persist.invoke(probe, new StringBuilder(), otherMeasured);
        assertTrue(probe.hasValidExternalDmrPreflightAttestation());
        assertArrayEquals(otherMeasured,
                probe.loadMeasuredRuntimeFromPreflightAttestation());
        File[] files = directory.toFile().listFiles();
        assertTrue(files != null && files.length == 1);
        java.nio.file.attribute.FileTime oldTime =
                java.nio.file.attribute.FileTime.fromMillis(1000L);
        Files.setLastModifiedTime(files[0].toPath(), oldTime);
        persist.invoke(probe, new StringBuilder(), otherMeasured);
        assertTrue("相同内容的每次成功预检也必须刷新固定凭证",
                Files.getLastModifiedTime(files[0].toPath()).toMillis() > 1000L);
        assertArrayEquals(otherAttestation,
                Files.readAllBytes(files[0].toPath()));
        Files.write(files[0].toPath(), new byte[] {0x01});
        assertFalse(probe.hasValidExternalDmrPreflightAttestation());
    }

    @Test
    public void runtimeMirrorComparisonUsesOnlySixVlcConstructionFields() {
        byte[] preflight = new byte[] {
                0x01, 0x01, 0x01, 0x00, 0x00,
                0x12, 0x34, 0x56, 0x78, (byte) 0x90,
                0x12, 0x34, 0x56, 0x78
        };
        byte[] mirror = new byte[] {
                0x01, 0x01, 0x12, 0x34, 0x56, 0x78,
                0x01, 0x01, 0x4d, 0x49, 0x52, 0x52
        };
        assertTrue(InterphoneProbe.runtimeMirrorVlcFieldsMatchPreflight(
                preflight, mirror));

        byte[] wrongVlcField = mirror.clone();
        wrongVlcField[5] ^= 0x01;
        assertFalse(InterphoneProbe.runtimeMirrorVlcFieldsMatchPreflight(
                preflight, wrongVlcField));

        byte[] differentFullprepState = mirror.clone();
        differentFullprepState[6] = 0;
        differentFullprepState[7] = 0;
        assertTrue(InterphoneProbe.runtimeMirrorVlcFieldsMatchPreflight(
                preflight, differentFullprepState));
        assertFalse(InterphoneProbe.runtimeMirrorVlcFieldsMatchPreflight(
                new byte[13], mirror));
        assertFalse(InterphoneProbe.runtimeMirrorVlcFieldsMatchPreflight(
                preflight, new byte[11]));
    }

    @Test
    public void sctReadyReadonlyAcceptsOnlyStableExpectedBaseline() {
        byte[] baseline = new byte[22];
        baseline[0] = 2;
        baseline[1] = 0;
        baseline[2] = 0;
        baseline[3] = (byte) 0xdd;
        baseline[4] = 0x1d;
        baseline[5] = 0x02;
        baseline[6] = 0x08;
        baseline[7] = 0;
        byte[][] rounds = new byte[][] {
                baseline.clone(), baseline.clone(), baseline.clone()
        };
        assertTrue(InterphoneProbe.isStableSctReadyReadonlySnapshots(rounds));
        assertTrue(InterphoneProbe.isExpectedSctReadyReadonlyBaseline(baseline));
        assertTrue(InterphoneProbe.isAcceptedSctReadyReadonlyEvidence(rounds));
    }

    @Test
    public void sctReadyReadonlyRejectsStableWrongCriticalValues() {
        byte[] baseline = new byte[22];
        baseline[0] = 2;
        baseline[3] = (byte) 0xdd;
        baseline[4] = 0x1d;
        baseline[5] = 0x02;
        baseline[6] = 0x08;
        for (int[] mutation : new int[][] {
                {0, 0}, {1, 2}, {2, 1}, {3, 0}, {7, 1}
        }) {
            byte[] changed = baseline.clone();
            changed[mutation[0]] = (byte) mutation[1];
            byte[][] rounds = new byte[][] {
                    changed.clone(), changed.clone(), changed.clone()
            };
            assertFalse(InterphoneProbe.isExpectedSctReadyReadonlyBaseline(changed));
            assertFalse(InterphoneProbe.isAcceptedSctReadyReadonlyEvidence(rounds));
        }
    }

    @Test
    public void sctReadyReadonlyRejectsUnstableOrIncompleteEvidence() {
        byte[] baseline = new byte[22];
        baseline[0] = 2;
        baseline[3] = (byte) 0xdd;
        baseline[4] = 0x1d;
        baseline[5] = 0x02;
        baseline[6] = 0x08;
        byte[] changed = baseline.clone();
        changed[8] = 1;
        assertFalse(InterphoneProbe.isAcceptedSctReadyReadonlyEvidence(
                new byte[][] {baseline.clone(), changed, baseline.clone()}));
        assertFalse(InterphoneProbe.isStableSctReadyReadonlySnapshots(
                new byte[][] {baseline.clone(), baseline.clone()}));
        assertFalse(InterphoneProbe.isExpectedSctReadyReadonlyBaseline(
                new byte[21]));
    }

    @Test
    public void sctReadyReadonlyWritesAtomicResultOnlyAfterClose()
            throws Exception {
        String source = new String(Files.readAllBytes(workspacePath(
                "app/src/main/java/net/elfradio/h13interphoneprobe/InterphoneProbe.java")),
                StandardCharsets.UTF_8);
        int start = source.indexOf(
                "ProbeResult runExternalDmrSctReadyReadonly57600()");
        int end = source.indexOf(
                "ProbeResult runExternalDmrPrivacyContractPreflight57600()",
                start);
        assertTrue(start >= 0 && end > start);
        String method = source.substring(start, end);
        int close = method.lastIndexOf("close();");
        int atomic = method.lastIndexOf("writeAtomicProbeSessionResult(");
        int finish = method.lastIndexOf("return finish(");
        assertTrue(close >= 0 && atomic > close && finish > atomic);
        assertTrue(method.contains("\"TEXT_CONFIRMED\", true,"
                + " controlPlaneRestored"));
    }

    @Test
    public void sendSessionReadyHardGateRequiresDataGateAndClearedQueues()
            throws Exception {
        byte[] gateOn = new byte[] {1};
        byte[] state1 = new byte[] {1};
        byte[] zeroWord = new byte[4];
        byte[] flag0 = new byte[] {0};
        byte[] queueA = new byte[112];
        byte[] queueB = new byte[168];
        assertTrue(InterphoneProbe.isExternalDmrSendSessionReady(
                gateOn, state1, zeroWord, zeroWord, flag0, zeroWord,
                queueA, queueB));
        InterphoneProbe.requireExternalDmrSendSessionReady(
                gateOn, state1, zeroWord, zeroWord, flag0, zeroWord,
                queueA, queueB);

        // 预检空闲 dataGate=0 不得进入 VLC。
        assertFalse(InterphoneProbe.isExternalDmrSendSessionReady(
                new byte[] {0}, state1, zeroWord, zeroWord, flag0, zeroWord,
                queueA, queueB));
        try {
            InterphoneProbe.requireExternalDmrSendSessionReady(
                    new byte[] {0}, state1, zeroWord, zeroWord, flag0, zeroWord,
                    queueA, queueB);
            fail("dataGate=0 必须硬失败");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("发送会话状态未就绪"));
        }

        queueA[0] = 1;
        assertFalse(InterphoneProbe.isExternalDmrSendSessionReady(
                gateOn, state1, zeroWord, zeroWord, flag0, zeroWord,
                queueA, queueB));
    }

    @Test
    public void forVlcPhaseAfterSetupSkipsSetupAndAcceptsType20Ack() {
        InterphoneProbe.ExternalDmrStep0Contract contract =
                groupStep0Contract(
                        InterphoneProbe.ExternalDmrStep0Contract.OUTPUT_SLOT1);
        InterphoneProbe.ExternalDmrTxModel model =
                InterphoneProbe.ExternalDmrTxModel.forVlcPhaseAfterSetup(
                        contract);
        assertEquals(InterphoneProbe.ExternalDmrTxModel.Phase.VLC_SESSION,
                model.phase());
        assertEquals(contract.setupCount(), model.setupAcks());
        assertEquals(0, model.vlcAcks());
        assertArrayEquals(contract.vlcFrame(0), model.expectedRequest());
        InterphoneProbe.ExternalDmrTxModel.ExchangeWindow window =
                openExpectedDmrWindow(model);
        assertTrue(model.acceptVlcExchange(window,
                hpiWireFrame(0x20, new byte[] {0x43})));
        assertEquals(1, model.vlcAcks());
    }

    @Test
    public void privacyRuntimeSnapshotRequiresExactStableFourteenBytes()
            throws Exception {
        byte[] expected = new byte[] {1, 1, 1, 1, 1,
                0x12, 0x34, 0x56, 0x78, (byte) 0x90,
                0x12, 0x34, 0x56, 0x78};
        // 离线夹具回归：稳定且等于夹具14字节。
        InterphoneProbe.requireExternalDmrPrivacyRuntimeSnapshots(
                new byte[][] {expected, expected.clone(), expected.clone()});
        // 设备路径：任意稳定14字节即可冻结。
        byte[] arbitrary = new byte[] {
                0x01, 0x05, (byte) 0xa3, 0x01, 0x01,
                0x11, 0x22, 0x33, 0x44, 0x55,
                0x66, 0x77, (byte) 0x88, (byte) 0x99
        };
        assertArrayEquals(arbitrary,
                InterphoneProbe.requireStableExternalDmrRuntimeSnapshots(
                        new byte[][] {arbitrary, arbitrary.clone(),
                                arbitrary.clone()}));
        byte[] wrong = expected.clone();
        wrong[8] ^= 1;
        try {
            InterphoneProbe.requireExternalDmrPrivacyRuntimeSnapshots(
                    new byte[][] {expected, wrong, expected.clone()});
            fail("不稳定快照必须拒绝");
        } catch (IOException expectedError) {
            assertTrue(expectedError.getMessage().contains("不稳定")
                    || expectedError.getMessage().contains("不符"));
        }

        byte[] wrongDataGate = expected.clone();
        wrongDataGate[4] = 0;
        try {
            InterphoneProbe.requireExternalDmrPrivacyRuntimeSnapshots(
                    new byte[][] {wrongDataGate, wrongDataGate.clone(),
                            wrongDataGate.clone()});
            fail("稳定但与离线夹具不符的快照必须拒绝");
        } catch (IOException expectedError) {
            assertTrue(expectedError.getMessage().contains("不符"));
        }
        // 设备路径允许稳定的非夹具 dataGate=0 快照本身被冻结（合同自洽另论）。
        assertArrayEquals(wrongDataGate,
                InterphoneProbe.requireStableExternalDmrRuntimeSnapshots(
                        new byte[][] {wrongDataGate, wrongDataGate.clone(),
                                wrongDataGate.clone()}));

        byte[] changedDataGate = expected.clone();
        changedDataGate[4] = 0;
        try {
            InterphoneProbe.requireExternalDmrPrivacyRuntimeSnapshots(
                    new byte[][] {expected, changedDataGate, expected.clone()});
            fail("privacy数据门跨轮变化必须拒绝");
        } catch (IOException expectedError) {
            assertTrue(expectedError.getMessage().contains("不稳定")
                    || expectedError.getMessage().contains("不符"));
        }

        try {
            InterphoneProbe.requireExternalDmrPrivacyRuntimeSnapshots(
                    new byte[][] {new byte[13], new byte[13], new byte[13]});
            fail("缺少privacy数据门或短一字节的快照必须拒绝");
        } catch (IOException expectedError) {
            assertTrue(expectedError.getMessage().contains("不完整"));
        }
    }

    @Test
    public void privacyControlTransactionGatesBridgeAndRequiresExactRestore()
            throws Exception {
        InterphoneProbe.ExternalDmrPrivacyControlModel model =
                new InterphoneProbe.ExternalDmrPrivacyControlModel();
        assertFalse(model.canEnterBridge());
        assertFalse(model.requiresRestore());
        model.baselineVerified();
        model.beforePrivacySet();
        assertTrue(model.requiresRestore());
        model.privacySetAck(true);
        model.privacyChannelVerified(true);
        assertFalse(model.canEnterBridge());
        model.privacyRuntimeVerified(true);
        assertTrue(model.canEnterBridge());
        model.beforeRestoreSet();
        model.restoreSetAck(true);
        model.restoreChannelVerified(true);
        assertFalse(model.requiresRestore());
        assertEquals(InterphoneProbe.ExternalDmrPrivacyControlModel.Phase.RESTORED,
                model.phase());
    }

    @Test
    public void privacyOffRuntimeRestoreRequiresThreeExactFourteenByteRounds()
            throws Exception {
        byte[] baseline = new byte[] {
                0, 0, 0, 1, 0,
                0, 0, 0, 0, 0, 0, 0, 0, 0
        };
        InterphoneProbe.requireExternalDmrPrivacyOffRuntimeRestored(baseline,
                new byte[][] {baseline.clone(), baseline.clone(),
                        baseline.clone()});

        byte[] wrong = baseline.clone();
        wrong[4] = 1;
        try {
            InterphoneProbe.requireExternalDmrPrivacyOffRuntimeRestored(baseline,
                    new byte[][] {wrong, wrong.clone(), wrong.clone()});
            fail("稳定但未恢复到基线的数据门必须拒绝");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("逐字节恢复"));
        }

        try {
            InterphoneProbe.requireExternalDmrPrivacyOffRuntimeRestored(baseline,
                    new byte[][] {baseline, wrong, baseline.clone()});
            fail("恢复快照跨轮变化必须拒绝");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("不稳定"));
        }

        try {
            InterphoneProbe.requireExternalDmrPrivacyOffRuntimeRestored(
                    new byte[13], new byte[][] {baseline, baseline, baseline});
            fail("短基线必须拒绝");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("基线长度"));
        }
    }

    @Test
    public void privacyControlFailuresNeverEnterBridgeAndStillDemandRestore()
            throws Exception {
        for (int failurePoint = 0; failurePoint < 3; failurePoint++) {
            InterphoneProbe.ExternalDmrPrivacyControlModel model =
                    new InterphoneProbe.ExternalDmrPrivacyControlModel();
            model.baselineVerified();
            model.beforePrivacySet();
            try {
                if (failurePoint == 0) {
                    model.privacySetAck(false);
                } else {
                    model.privacySetAck(true);
                    if (failurePoint == 1) {
                        model.privacyChannelVerified(false);
                    } else {
                        model.privacyChannelVerified(true);
                        model.privacyRuntimeVerified(false);
                    }
                }
                fail("privacy故障点必须失败关闭");
            } catch (IOException expected) {
                assertFalse(model.canEnterBridge());
                assertTrue(model.requiresRestore());
            }
            model.beforeRestoreSet();
            model.restoreSetAck(true);
            model.restoreChannelVerified(true);
            assertFalse(model.requiresRestore());
        }
    }

    @Test
    public void privacyRestoreAckOrReadbackFailureCannotBeAccepted()
            throws Exception {
        for (boolean failSetAck : new boolean[] {true, false}) {
            InterphoneProbe.ExternalDmrPrivacyControlModel model =
                    new InterphoneProbe.ExternalDmrPrivacyControlModel();
            model.baselineVerified();
            model.beforePrivacySet();
            model.privacySetAck(true);
            model.privacyChannelVerified(true);
            model.privacyRuntimeVerified(true);
            model.beforeRestoreSet();
            try {
                model.restoreSetAck(!failSetAck);
                if (!failSetAck) {
                    model.restoreChannelVerified(false);
                }
                fail("恢复ACK或完整回读失败不得通过");
            } catch (IOException expected) {
                assertTrue(model.requiresRestore());
                assertFalse(model.phase()
                        == InterphoneProbe.ExternalDmrPrivacyControlModel.Phase.RESTORED);
            }
        }
    }

    @Test
    public void runtimeFirmwareSlicesMatchPinnedImageAndRejectOneBitChange()
            throws Exception {
        byte[] firmware = Files.readAllBytes(workspacePath(
                "../h13_radio/Module_current_0.3.66_full_flash_256k.bin"));
        byte[][] slices = new byte[][] {
                java.util.Arrays.copyOfRange(firmware, 0x1568c, 0x1568c + 88),
                java.util.Arrays.copyOfRange(firmware, 0x1db08, 0x1db08 + 80),
                java.util.Arrays.copyOfRange(firmware, 0x202a0, 0x202a0 + 232),
                java.util.Arrays.copyOfRange(firmware, 0x24dc0, 0x24dc0 + 0x374),
                java.util.Arrays.copyOfRange(firmware, 0x2680c, 0x2680c + 0x220),
                java.util.Arrays.copyOfRange(firmware, 0x1d834, 0x1d834 + 0x2d4),
                java.util.Arrays.copyOfRange(firmware, 0x2314c, 0x2314c + 0x46c),
                java.util.Arrays.copyOfRange(firmware, 0x235c8, 0x235c8 + 0xe8)
        };
        InterphoneProbe.requireExternalDmrFirmwareSlices(slices);
        for (int index = 0; index < slices.length; index++) {
            slices[index][slices[index].length / 2] ^= 1;
            try {
                InterphoneProbe.requireExternalDmrFirmwareSlices(slices);
                fail("每个运行固件切片的单bit变化都必须拒绝 index=" + index);
            } catch (IOException expected) {
                assertTrue(expected.getMessage().contains("哈希"));
            }
            slices[index][slices[index].length / 2] ^= 1;
        }
        InterphoneProbe.requireExternalDmrFirmwareSlices(slices);
    }

    @Test
    public void runtimeSnapshotRequiresThreeIdenticalCompleteRounds() {
        byte[] first = new byte[] {
                0x01, 0x02, 0x03, 0x04,
                0x01,
                0x10, 0x11, 0x12, 0x13, 0x14, 0x15, 0x16, 0x17, 0x18
        };
        assertTrue(InterphoneProbe.isStableExternalDmrRuntimeSnapshots(
                new byte[][] {first, first.clone(), first.clone()}));

        byte[] changed = first.clone();
        changed[6] ^= 0x01;
        assertFalse(InterphoneProbe.isStableExternalDmrRuntimeSnapshots(
                new byte[][] {first, changed, first.clone()}));
        assertFalse(InterphoneProbe.isStableExternalDmrRuntimeSnapshots(
                new byte[][] {first, first.clone()}));
        assertFalse(InterphoneProbe.isStableExternalDmrRuntimeSnapshots(
                new byte[][] {new byte[13], new byte[13], new byte[13]}));
        assertFalse(InterphoneProbe.isStableExternalDmrRuntimeSnapshots(null));
    }

    @Test
    public void runtimeSnapshotLaunchIsReadOnlyAndNeedsNoRfOrPowerFlag() {
        assertNull(MainActivity.validateLaunch(
                "dmr_external_runtime_snapshot_57600", false, false, -1));
        assertTrue(MainActivity.validateLaunch(
                "dmr_external_vlc_session_no_rf", false, false, -1)
                .contains("allow_potential_rf"));
        assertTrue(MainActivity.validateLaunch(
                "dmr_external_privacy_contract_preflight_57600",
                false, false, -1).contains("allow_channel_mutation"));
        assertNull(MainActivity.validateLaunch(
                "dmr_external_privacy_contract_preflight_57600",
                false, true, -1));
    }

    @Test
    public void step3TimeoutHandlerHashMatchesRecordedV061() {
        assertEquals(InterphoneProbe.UART_HPI_TIMEOUT_HANDLER_SHA256,
                InterphoneProbe.uartHpiTimeoutHandlerSha256());
    }

    @Test
    public void step3ArmGateRejectsDirtyFlagWrongVectorAndBadHandler()
            throws Exception {
        Field wordsField = InterphoneProbe.class.getDeclaredField(
                "UART_HPI_TIMEOUT_WORDS");
        wordsField.setAccessible(true);
        int[] words = (int[]) wordsField.get(null);
        Method wordsToBytes = InterphoneProbe.class.getDeclaredMethod(
                "wordsToBytes", int[].class);
        wordsToBytes.setAccessible(true);
        byte[] handler = (byte[]) wordsToBytes.invoke(null, (Object) words);
        // Clean baseline must pass.
        InterphoneProbe.UartHpiTimeoutArmGate.validateCleanBaseline(
                new byte[] {0},
                new byte[] {(byte) 0xdd, 0x1d, 0x02, 0x08},
                new byte[handler.length],
                new byte[4],
                new byte[4],
                handler);

        try {
            InterphoneProbe.UartHpiTimeoutArmGate.validateCleanBaseline(
                    new byte[] {1},
                    new byte[] {(byte) 0xdd, 0x1d, 0x02, 0x08},
                    new byte[handler.length],
                    new byte[4],
                    new byte[4],
                    handler);
            fail("脏 bridge flag 应被拒绝");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("bridge"));
        }

        try {
            InterphoneProbe.UartHpiTimeoutArmGate.validateCleanBaseline(
                    new byte[] {0},
                    new byte[] {0x00, 0x00, 0x00, 0x00},
                    new byte[handler.length],
                    new byte[4],
                    new byte[4],
                    handler);
            fail("错误 SysTick 应被拒绝");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().toLowerCase()
                    .contains("systick")
                    || expected.getMessage().contains("SysTick"));
        }

        byte[] bad = handler.clone();
        bad[0] ^= 0x5a;
        try {
            InterphoneProbe.UartHpiTimeoutArmGate.validateCleanBaseline(
                    new byte[] {0},
                    new byte[] {(byte) 0xdd, 0x1d, 0x02, 0x08},
                    new byte[handler.length],
                    new byte[4],
                    new byte[4],
                    bad);
            fail("错误 handler 哈希应被拒绝");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().toLowerCase().contains("hash")
                    || expected.getMessage().contains("handler"));
        }

        try {
            InterphoneProbe.UartHpiTimeoutArmGate.validateUploadReadback(
                    handler, bad);
            fail("上传回读不匹配应被拒绝");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("mismatch"));
        }

        InterphoneProbe.UartHpiTimeoutArmGate.validatePostTimeoutRecovery(
                new byte[] {0},
                new byte[] {(byte) 0xdd, 0x1d, 0x02, 0x08},
                new byte[4],
                new byte[] {0x42, 0x52, 0x44, 0x47});
        try {
            InterphoneProbe.UartHpiTimeoutArmGate.validatePostTimeoutRecovery(
                    new byte[] {1},
                    new byte[] {(byte) 0xdd, 0x1d, 0x02, 0x08},
                    new byte[4],
                    new byte[] {0x42, 0x52, 0x44, 0x47});
            fail("超时后 flag 非零应被拒绝");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("flag")
                    || expected.getMessage().contains("Bridge"));
        }
    }

    @Test
    public void step3InfrastructureOfflinePassesWithoutSerialOrData36() {
        ProbeResult result =
                InterphoneProbe.evaluateExternalDmrStep3InfrastructureOffline();
        assertTrue(result.report, result.success);
        assertTrue(result.report.contains("Serial opened: false"));
        assertTrue(result.report.contains("Data36: 禁止"));
        assertTrue(result.report.contains("setupAcks: 5"));
        assertTrue(result.report.contains("vlcAcks: 5"));
        assertTrue(result.report.contains("Total control requests: 10"));
        assertTrue(result.report.contains("ASCII recovery while bridged: 禁止"));
        assertTrue(result.report.contains(
                InterphoneProbe.UART_HPI_TIMEOUT_HANDLER_SHA256));
        assertTrue(result.report.contains("Clean baseline arm gate: true"));
        assertTrue(result.report.contains("Dirty flag rejected"));
        assertTrue(result.report.contains("Bad handler rejected"));
    }

    @Test
    public void step3SessionRequestsNeverLookLikeData36() {
        InterphoneProbe.ExternalDmrStep0Contract contract =
                groupStep0Contract(
                        InterphoneProbe.ExternalDmrStep0Contract.OUTPUT_SLOT1);
        InterphoneProbe.ExternalDmrTxModel model =
                new InterphoneProbe.ExternalDmrTxModel(contract);
        for (int index = 0; index < contract.setupCount(); index++) {
            byte[] request = model.expectedRequest();
            assertFalse(InterphoneProbe.looksLikeExternalData36Frame(request));
            acceptExpectedSetup(model, contract, index);
        }
        for (int index = 0; index < 5; index++) {
            byte[] request = model.expectedRequest();
            assertFalse(InterphoneProbe.looksLikeExternalData36Frame(request));
            InterphoneProbe.ExternalDmrTxModel.ExchangeWindow window =
                    openExpectedDmrWindow(model);
            assertTrue(model.acceptVlcExchange(window,
                    hpiWireFrame(5, new byte[] {0x43})));
        }
        assertFalse(model.canSerializeData36());
        assertNull(model.expectedRequest());
        // 冻结 44 字节 unit0 必须被识别为 data36 形状。
        assertTrue(InterphoneProbe.looksLikeExternalData36Frame(
                InterphoneProbe.createFrozenExternalEncodedTxFrame(0)));
    }

    @Test
    public void step3DeviceExecutorIsPrivateAndNotBypassableFromUiPackage()
            throws Exception {
        // 仅 boolean 入口；禁止外部喂入合成合同绕过预检凭证。
        Method execute = InterphoneProbe.class.getDeclaredMethod(
                "executeExternalDmrVlcSessionNoRf",
                boolean.class, boolean.class, boolean.class, boolean.class,
                boolean.class);
        assertTrue(Modifier.isPrivate(execute.getModifiers()));
        assertFalse(Modifier.isPublic(execute.getModifiers()));
        assertFalse(Modifier.isProtected(execute.getModifiers()));
        Method fixedAssetExecute = InterphoneProbe.class.getDeclaredMethod(
                "executeExternalDmrVlcSessionNoRf",
                boolean.class, boolean.class, boolean.class, boolean.class,
                boolean.class, byte[].class);
        assertTrue(Modifier.isPrivate(fixedAssetExecute.getModifiers()));
        try {
            InterphoneProbe.class.getDeclaredMethod(
                    "executeExternalDmrVlcSessionNoRf",
                    InterphoneProbe.ExternalDmrStep0Contract.class);
            fail("不得再暴露可直接喂合同的执行入口");
        } catch (NoSuchMethodException expected) {
            // 期望：合同喂入口已删除
        }
        try {
            InterphoneProbe.class.getDeclaredMethod(
                    "executeExternalDmrVlcSessionNoRf",
                    InterphoneProbe.ExternalDmrStep0Contract.class,
                    boolean.class);
            fail("不得再暴露合同+标志双参执行入口");
        } catch (NoSuchMethodException expected) {
            // 期望
        }
    }

    @Test
    public void realtimeRelayUsesActualV200VlcFramesAndMeasuredPrivacy()
            throws Exception {
        byte[] vlc0 = hexBytes(
                "84 a9 61 00 01 20 43 00 "
                + "84 a9 61 00 1d 20 01 1b "
                + "cf 02 f0 0c 70 cb 97 8f 21 d8 03 ac 9f a4 cb 07 "
                + "8f 21 b8 01 b1 7e 46 df 1d 0b 25 00 "
                + "84 a9 61 00 02 20 17 0a");
        byte[] vlc1 = hexBytes(
                "84 a9 61 00 1d 20 01 1b "
                + "98 02 b9 4f a4 d3 df 87 21 aa 1d 42 9e 43 43 9e "
                + "8b 25 2c 0b ab 17 a2 6d 17 8b 25 00 "
                + "84 a9 61 00 01 20 43 00 "
                + "84 a9 61 00 1d 20 01 1b "
                + "e8 03 20 36 47 8b 17 8f 21 e8 02 63 e9 67 6b 97 "
                + "8b 25 e8 0b 46 80 87 9e 9d 0b 21 00 "
                + "84 a9 61 00 02 20 17 0a");
        assertEquals("fe1c3f29180c097998976a96d798864cc985df878137b5f83f6ea53d7c2b43cd",
                sha256Hex(vlc0));
        assertEquals("0ae3b6a62ea48d847643ef19443c70a6495bd1f471407011e59cb89a32f0a47e",
                sha256Hex(vlc1));

        byte[] stream = new byte[81];
        byte[] fromVlc0 = InterphoneProbe.extractRealtimeDmrData27Payloads(vlc0);
        byte[] fromVlc1 = InterphoneProbe.extractRealtimeDmrData27Payloads(vlc1);
        assertEquals(27, fromVlc0.length);
        assertEquals(54, fromVlc1.length);
        System.arraycopy(fromVlc0, 0, stream, 0, fromVlc0.length);
        System.arraycopy(fromVlc1, 0, stream, fromVlc0.length,
                fromVlc1.length);
        assertEquals("298a4a8dc9074507c68be0d980327841ba864e06384eb37020aa569fc4f8aebe",
                sha256Hex(stream));

        byte[] plain36 = InterphoneProbe.createFirstRealtimeRelayPlain36(stream);
        assertArrayEquals(java.util.Arrays.copyOf(stream, 36), plain36);
        byte[] measuredRuntime14 = hexBytes(
                "01 01 01 00 00 12 34 56 78 90 12 34 56 78");
        byte[] request = InterphoneProbe.createRealtimeRelayEncryptedData36Frame(
                plain36, measuredRuntime14);
        assertEquals(44, request.length);
        assertArrayEquals(hexBytes("84 a9 61 00 26 03 01 24"),
                java.util.Arrays.copyOf(request, 8));
        assertFalse(java.util.Arrays.equals(plain36,
                java.util.Arrays.copyOfRange(request, 8, 44)));

        byte[] frozenPlain = InterphoneProbe.extractFrozenData36Unit(
                InterphoneProbe.REAL_RX_AMBE_108_BYTES, 0);
        assertArrayEquals(InterphoneProbe.requireHostEncryptedExternalTxFrameUnit0(),
                InterphoneProbe.createRealtimeRelayEncryptedData36Frame(
                        frozenPlain, measuredRuntime14));
    }

    @Test
    public void realtimeRelayRejectsTruncatedCaptureAndWrongUnitCount()
            throws Exception {
        try {
            InterphoneProbe.extractRealtimeDmrData27Payloads(
                    hexBytes("84 a9 61 00 1d 20 01 1b 00"));
            fail("截断HPI捕获必须拒绝");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("完整HPI"));
        }
        try {
            InterphoneProbe.createFirstRealtimeRelayPlain36(new byte[54]);
            fail("少于三个27字节单元必须拒绝");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("三个27字节"));
        }
    }

    @Test
    public void v205FixedAssetPayloadIsIndependentFromRealtimeTrigger()
            throws Exception {
        byte[] assetBytes = fullFrozenAmbeAsset();
        InterphoneProbe.FrozenAmbeAsset asset =
                InterphoneProbe.FrozenAmbeAsset.require(assetBytes);
        byte[] realtime81 = hexBytes(
                "cf02f00c70cb978f21d803ac9fa4cb078f21b801b17e46df1d0b25"
                + "9802b94fa4d3df8721aa1d429e43439e8b252c0bab17a26d178b25"
                + "e8032036478b178f21e80263e9676b978b25e80b4680879e9d0b21");
        byte[] realtimeCandidate =
                InterphoneProbe.createFirstRealtimeRelayPlain36(realtime81);
        byte[] fixed = asset.data36(0);
        assertFalse("固定资产必须与本次实时前36字节不同",
                java.util.Arrays.equals(fixed, realtimeCandidate));

        byte[] runtime14 = measuredPrivacyRuntime14();
        byte[] request = InterphoneProbe.createRealtimeRelayEncryptedData36Frame(
                fixed, runtime14);
        assertArrayEquals(fixed,
                java.util.Arrays.copyOf(assetBytes, 36));
        assertFalse(java.util.Arrays.equals(realtimeCandidate, fixed));
        assertArrayEquals(java.util.Arrays.copyOfRange(request, 8, 44),
                InterphoneProbe.DmrPrivacyStreamContext
                        .fromMeasuredRuntime(runtime14).encryptData36(fixed));
        assertFalse(java.util.Arrays.equals(
                java.util.Arrays.copyOfRange(request, 8, 44),
                InterphoneProbe.DmrPrivacyStreamContext
                        .fromMeasuredRuntime(runtime14)
                        .encryptData36(realtimeCandidate)));
    }

    @Test
    public void v205FixedAssetRejectsTamperBoundsAndEqualRealtimeCandidate()
            throws Exception {
        byte[] asset = fullFrozenAmbeAsset();
        asset[0] ^= 1;
        try {
            InterphoneProbe.FrozenAmbeAsset.require(asset);
            fail("篡改资产必须拒绝");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("SHA-256"));
        }
        InterphoneProbe.FrozenAmbeAsset valid =
                InterphoneProbe.FrozenAmbeAsset.require(fullFrozenAmbeAsset());
        try {
            valid.range(6580, 36);
            fail("错误偏移必须拒绝");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("越界"));
        }
        byte[] fixed = valid.data36(0);
        assertTrue(java.util.Arrays.equals(fixed,
                java.util.Arrays.copyOf(fixed, 36)));
        String source = new String(Files.readAllBytes(workspacePath(
                "app/src/main/java/net/elfradio/h13interphoneprobe/InterphoneProbe.java")),
                StandardCharsets.UTF_8);
        assertTrue("相等候选必须由真机上下文的写前独立性门拒绝",
                source.contains("无法证明音源独立性，禁止写出"));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    @Test
    public void v205DeviceContextWritesFixedAssetInsteadOfRealtimeCandidate()
            throws Exception {
        byte[] realtime81 = hexBytes(
                "cf02f00c70cb978f21d803ac9fa4cb078f21b801b17e46df1d0b25"
                + "9802b94fa4d3df8721aa1d429e43439e8b252c0bab17a26d178b25"
                + "e8032036478b178f21e80263e9676b978b25e80b4680879e9d0b21");
        ByteArrayOutputStream wire = new ByteArrayOutputStream();
        java.util.List evidence = new java.util.ArrayList();
        InterphoneProbe.ExternalDmrRealtimeRelayContext context =
                new InterphoneProbe.ExternalDmrRealtimeRelayContext(
                        wire, evidence, new ByteArrayOutputStream(), 0L,
                        new StringBuilder(), measuredPrivacyRuntime14(),
                        fullFrozenAmbeAsset());
        for (int unit = 0; unit < 3; unit++) {
            byte[] payload = new byte[29];
            payload[0] = 0x01;
            payload[1] = 0x1b;
            System.arraycopy(realtime81, unit * 27, payload, 2, 27);
            context.acceptCompleteCapture(hpiWireFrame(0x20, payload),
                    "离线单元" + unit);
        }
        byte[] fixed = InterphoneProbe.FrozenAmbeAsset
                .require(fullFrozenAmbeAsset()).data36(0);
        byte[] expected = InterphoneProbe.createRealtimeRelayEncryptedData36Frame(
                fixed, measuredPrivacyRuntime14());
        assertTrue(context.fixedAssetPayload());
        assertArrayEquals(java.util.Arrays.copyOf(realtime81, 36),
                context.realtimeCandidate36());
        assertArrayEquals(fixed, context.plain36());
        assertArrayEquals(expected, wire.toByteArray());
        assertFalse(java.util.Arrays.equals(context.plain36(),
                context.realtimeCandidate36()));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    @Test
    public void v205DeviceContextRejectsEqualCandidateBeforeWrite()
            throws Exception {
        byte[] asset = fullFrozenAmbeAsset();
        ByteArrayOutputStream wire = new ByteArrayOutputStream();
        InterphoneProbe.ExternalDmrRealtimeRelayContext context =
                new InterphoneProbe.ExternalDmrRealtimeRelayContext(
                        wire, new java.util.ArrayList(),
                        new ByteArrayOutputStream(), 0L, new StringBuilder(),
                        measuredPrivacyRuntime14(), asset);
        boolean rejected = false;
        try {
            for (int unit = 0; unit < 3; unit++) {
                byte[] payload = new byte[29];
                payload[0] = 0x01;
                payload[1] = 0x1b;
                System.arraycopy(asset, unit * 27, payload, 2, 27);
                context.acceptCompleteCapture(hpiWireFrame(0x20, payload),
                        "相等候选" + unit);
            }
        } catch (IOException expected) {
            rejected = true;
            assertTrue(expected.getMessage().contains("音源独立性"));
        }
        assertTrue(rejected);
        assertEquals("写前独立性门失败时不得上线任何data36", 0, wire.size());
        assertEquals(0, context.writeCount());
    }

    @Test
    public void step3ArmGateRejectsNonEmptyTemporaryWindows() throws Exception {
        Field wordsField = InterphoneProbe.class.getDeclaredField(
                "UART_HPI_TIMEOUT_WORDS");
        wordsField.setAccessible(true);
        int[] words = (int[]) wordsField.get(null);
        Method wordsToBytes = InterphoneProbe.class.getDeclaredMethod(
                "wordsToBytes", int[].class);
        wordsToBytes.setAccessible(true);
        byte[] handler = (byte[]) wordsToBytes.invoke(null, (Object) words);
        byte[] vector = new byte[] {(byte) 0xdd, 0x1d, 0x02, 0x08};

        byte[] dirtyCode = new byte[handler.length];
        dirtyCode[0] = 1;
        try {
            InterphoneProbe.UartHpiTimeoutArmGate.validateCleanBaseline(
                    new byte[] {0}, vector, dirtyCode, new byte[4],
                    new byte[4], handler);
            fail("非空 code 窗口应被拒绝");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("code")
                    || expected.getMessage().contains("Temporary"));
        }

        byte[] dirtyCounter = new byte[] {1, 0, 0, 0};
        try {
            InterphoneProbe.UartHpiTimeoutArmGate.validateCleanBaseline(
                    new byte[] {0}, vector, new byte[handler.length],
                    dirtyCounter, new byte[4], handler);
            fail("非空 counter 应被拒绝");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("counter")
                    || expected.getMessage().contains("Counter"));
        }

        byte[] dirtyMarker = new byte[] {1, 0, 0, 0};
        try {
            InterphoneProbe.UartHpiTimeoutArmGate.validateCleanBaseline(
                    new byte[] {0}, vector, new byte[handler.length],
                    new byte[4], dirtyMarker, handler);
            fail("非空 marker 应被拒绝");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("marker")
                    || expected.getMessage().contains("Marker"));
        }
    }

    @Test
    public void step3InfrastructureLabelsFixtureNotLiveSnapshot() {
        ProbeResult result =
                InterphoneProbe.evaluateExternalDmrStep3InfrastructureOffline();
        assertTrue(result.success);
        assertTrue(result.report.contains("离线夹具")
                || result.report.contains("非本机运行时"));
        assertTrue(result.report.contains("64-byte")
                || result.report.contains("0x20001600")
                || result.report.contains(
                InterphoneProbe.UART_HPI_TIMEOUT_HANDLER_SHA256));
    }

    @Test
    public void step3TransportTrackerCoversEveryPartialHandlerWrite()
            throws Exception {
        Field wordsField = InterphoneProbe.class.getDeclaredField(
                "UART_HPI_TIMEOUT_WORDS");
        wordsField.setAccessible(true);
        int[] words = (int[]) wordsField.get(null);
        Method wordsToBytes = InterphoneProbe.class.getDeclaredMethod(
                "wordsToBytes", int[].class);
        wordsToBytes.setAccessible(true);
        byte[] handler = (byte[]) wordsToBytes.invoke(null, (Object) words);

        for (int failingWord = 0; failingWord < 16; failingWord++) {
            InterphoneProbe.Step3TransportTracker tracker =
                    new InterphoneProbe.Step3TransportTracker();
            final int injectedFailure = failingWord;
            final int[] attemptedWrites = {0};
            try {
                InterphoneProbe.uploadStep3TimeoutHandler(tracker, handler,
                        (address, value) -> {
                            assertEquals(
                                    InterphoneProbe.Step3TransportState.SRAM_MAY_BE_DIRTY,
                                    tracker.state());
                            int current = attemptedWrites[0]++;
                            if (current == injectedFailure) {
                                throw new IOException("注入word故障 " + current);
                            }
                        });
                fail("应在word=" + failingWord + "注入故障");
            } catch (IOException expected) {
                assertTrue(expected.getMessage().contains("注入word故障"));
            }
            assertEquals(failingWord + 1, attemptedWrites[0]);
            assertEquals("word=" + failingWord,
                    InterphoneProbe.Step3TransportState.SRAM_MAY_BE_DIRTY,
                    tracker.state());
            assertTrue(tracker.canAttemptAsciiRestore());
            assertFalse(tracker.maySendHpiCleanup());
            tracker.temporaryStateRestored();
            assertEquals(
                    InterphoneProbe.Step3TransportState.TEXT_AFTER_TIMEOUT_CONFIRMED,
                    tracker.state());
        }
    }

    @Test
    public void step3UncertainFlagWriteForbidsBothImmediateDataPlanes()
            throws Exception {
        InterphoneProbe.Step3TransportTracker tracker =
                new InterphoneProbe.Step3TransportTracker();
        tracker.beforeTemporarySramWrite();
        try {
            InterphoneProbe.activateStep3Bridge(tracker, () -> {
                assertEquals(
                        InterphoneProbe.Step3TransportState.BRIDGE_MAY_BE_ACTIVE,
                        tracker.state());
                throw new IOException("注入flag响应丢失");
            });
            fail("flag响应丢失必须抛错");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("flag响应丢失"));
        }
        assertEquals(InterphoneProbe.Step3TransportState.BRIDGE_MAY_BE_ACTIVE,
                tracker.state());
        assertTrue(tracker.needsTimeoutWait());
        assertFalse(tracker.maySendHpiCleanup());
        assertFalse(tracker.canAttemptAsciiRestore());

        tracker.commandModeConfirmedFromFlag(new byte[] {0});
        assertEquals(
                InterphoneProbe.Step3TransportState.TEXT_AFTER_TIMEOUT_CONFIRMED,
                tracker.state());
        assertFalse(tracker.maySendHpiCleanup());
        assertTrue(tracker.canAttemptAsciiRestore());
    }

    @Test
    public void step3ConfirmedBridgeAllowsHpiUntilFlagZeroOnly()
            throws Exception {
        InterphoneProbe.Step3TransportTracker tracker =
                new InterphoneProbe.Step3TransportTracker();
        tracker.beforeTemporarySramWrite();
        InterphoneProbe.activateStep3Bridge(tracker, () -> { });
        assertEquals(InterphoneProbe.Step3TransportState.BRIDGE_ACTIVE,
                tracker.state());
        assertTrue(tracker.maySendHpiCleanup());
        assertFalse(tracker.canAttemptAsciiRestore());

        tracker.commandModeConfirmedFromFlag(new byte[] {0});
        assertEquals(
                InterphoneProbe.Step3TransportState.TEXT_AFTER_TIMEOUT_CONFIRMED,
                tracker.state());
        assertFalse(tracker.maySendHpiCleanup());
        assertTrue(tracker.canAttemptAsciiRestore());
    }

    @Test
    public void step3NonzeroTimeoutFlagMakesRecoveryUncertain()
            throws Exception {
        InterphoneProbe.Step3TransportTracker tracker =
                new InterphoneProbe.Step3TransportTracker();
        tracker.beforeTemporarySramWrite();
        tracker.beforeBridgeFlagWrite();
        tracker.bridgeFlagWriteReturned();
        try {
            tracker.commandModeConfirmedFromFlag(new byte[] {1});
            fail("非零flag必须阻断恢复");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("flag"));
        }
        assertEquals(InterphoneProbe.Step3TransportState.RECOVERY_UNCERTAIN,
                tracker.state());
        assertFalse(tracker.maySendHpiCleanup());
        assertFalse(tracker.canAttemptAsciiRestore());
    }

    @Test
    public void step3BridgeBudgetHasCalculatedTwoSecondMargin() {
        assertEquals(6000L, InterphoneProbe.externalDmrStep3aWorstCaseMs());
        assertEquals(6320L, InterphoneProbe.externalDmrStep3bWorstCaseMs());
        // v1.77：纳入 5000+1200 prep 等待后的锚定路径
        // max(10320, 5000+1200+5*800+250+70+2000=12520) = 12520
        assertEquals(12520L, InterphoneProbe.externalDmrStep3WorstCaseMs());
        assertTrue(InterphoneProbe.externalDmrStep3BudgetFitsBridgeWindow());
        assertTrue(InterphoneProbe.externalDmrStep3WorstCaseMs() + 2000L
                < 18000L);
        // 12520 + 300 + 1500 + 500 = 14820
        assertEquals(14820L, InterphoneProbe.externalDmrStep4WorstCaseMs());
        assertTrue(InterphoneProbe.externalDmrStep4BudgetFitsBridgeWindow());
        // 12520 + 1000(vlc2前接收窗) + 1500 + 500 = 15520
        assertEquals(15520L,
                InterphoneProbe.externalDmrRealtimeRelayWorstCaseMs());
        assertTrue(InterphoneProbe
                .externalDmrRealtimeRelayBudgetFitsBridgeWindow());
        assertTrue(InterphoneProbe.externalDmrRealtimeRelayWorstCaseMs()
                + 2000L < 18000L);
    }

    @Test
    public void step3TransportAllowsSecondBridgeAfterTextRestore()
            throws Exception {
        InterphoneProbe.Step3TransportTracker tracker =
                new InterphoneProbe.Step3TransportTracker();
        tracker.beforeTemporarySramWrite();
        InterphoneProbe.activateStep3Bridge(tracker, () -> { });
        tracker.commandModeConfirmedFromFlag(new byte[] {0});
        tracker.temporaryStateRestored();
        assertEquals(
                InterphoneProbe.Step3TransportState.TEXT_AFTER_TIMEOUT_CONFIRMED,
                tracker.state());
        tracker.readyForSecondBridgePhase();
        assertEquals(InterphoneProbe.Step3TransportState.TEXT_CONFIRMED,
                tracker.state());
        // 第二阶段允许再次写临时 SRAM 并 arm bridge
        tracker.beforeTemporarySramWrite();
        InterphoneProbe.activateStep3Bridge(tracker, () -> { });
        assertEquals(InterphoneProbe.Step3TransportState.BRIDGE_ACTIVE,
                tracker.state());
        assertTrue(tracker.maySendHpiCleanup());
    }

    @Test
    public void quietDrainRequiresContinuousSilenceAndPreservesLateBytes()
            throws Exception {
        FakeClock emptyClock = new FakeClock();
        InterphoneProbe.QuietDrainResult empty =
                InterphoneProbe.drainUntilQuietCapture(
                        new ByteArrayInputStream(new byte[0]), 150, 300,
                        emptyClock);
        assertEquals(150L, empty.elapsedMs);
        assertEquals(0, empty.raw.length);
        assertTrue(empty.proof.isVerifiedCapture());
        assertEquals(150L, empty.proof.establishedAtMs());
        assertEquals(150L, empty.proof.quietDurationMs());

        FakeClock lateClock = new FakeClock();
        InterphoneProbe.QuietDrainResult late =
                InterphoneProbe.drainUntilQuietCapture(
                        new ScheduledInputStream(lateClock, 0, 100),
                        150, 300, lateClock);
        assertArrayEquals(new byte[] {1, 2}, late.raw);
        assertEquals(250L, late.elapsedMs);
        assertTrue(late.proof.isVerifiedCapture());
        assertEquals(250L, late.proof.establishedAtMs());
        assertEquals(150L, late.proof.quietDurationMs());
    }

    @Test
    public void quietDrainFailsClosedWhenInputNeverSettles() throws Exception {
        FakeClock clock = new FakeClock();
        try {
            InterphoneProbe.drainUntilQuietCapture(
                    new ScheduledInputStream(clock, 0, 100, 200, 295),
                    150, 300, clock);
            fail("持续输入必须阻断发送前窗口");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("静默窗口"));
        }
    }

    @Test
    public void step3DeviceSourceAdvancesStateBeforeMutations() throws Exception {
        Path sourcePath = workspacePath(
                "app/src/main/java/net/elfradio/h13interphoneprobe/InterphoneProbe.java");
        String source = new String(Files.readAllBytes(sourcePath),
                StandardCharsets.UTF_8);
        int methodStart = source.indexOf(
                "private ProbeResult executeExternalDmrVlcSessionNoRf(");
        int methodEnd = source.indexOf(
                "步骤1纯离线握手证明", methodStart);
        assertTrue(methodStart >= 0 && methodEnd > methodStart);
        String method = source.substring(methodStart, methodEnd);

        // v1.84：新窗 + 禁清零生产区 + SysTick 计时 + 隔离无 VLC + 真 pre-image
        assertTrue(method.contains("beforeTemporarySramWrite()")
                || method.contains("transport.beforeTemporarySramWrite()"));
        assertTrue(method.contains("activateStep3Bridge(transport"));
        assertTrue(method.contains("runOneExternalDmrControlExchange"));
        assertTrue(method.contains("exact-known-artifact")
                || method.contains("clean-zero"));
        assertTrue(method.contains("never zero")
                || method.contains("禁止清零"));
        assertTrue(method.contains("assertNotForbiddenProductionWindow"));
        assertFalse("must not arm after zeroing residual",
                method.contains("audited word-zero then arm"));
        assertFalse("must not blind force-zero",
                method.contains("force-zero text baseline"));
        assertTrue(method.contains("setup4 before prep deadline")
                || method.contains("setup 完成过晚"));
        assertTrue(method.contains("no text-memread while bridged")
                || method.contains("post-exit DGAT")
                || method.contains("TIMING_PREP_COMPLETE_POST_EXIT"));
        assertFalse("must not text-memread DGAT while raw-bridged",
                method.contains("prep marker before VLC/cleanup"));
        assertTrue(method.contains("TIMING_SYSTICK_ARM_BEGIN")
                || method.contains("systick_arm_begin"));
        assertTrue(method.contains("full pre-arm queue memread"));
        assertTrue(method.contains("runVlcSession")
                || method.contains("ISOLATION_NO_VLC")
                || method.contains("skipped isolation"));
        assertTrue(method.contains("setup0Only")
                && (method.contains("setup0_attempt1")
                || method.contains("SETUP0_FIRST_OK")
                || method.contains("SETUP0_EMPTY_RETRY")));
        // 空重试释放窗口在 runOne 内；状态机方法在 ExternalDmrTxModel 内
        assertTrue(source.contains("abandonActiveWindowForSetup0EmptyRetry"));
        assertTrue(source.contains("allowSetup0StrictEmptyRetry")
                || source.contains("SETUP0_STRICT_EMPTY_RETRY_ELIGIBLE"));
        assertTrue("实时主动路径必须复用一次严格双空重试门",
                method.contains("sendRealtimeRelayData36 && model.setupAcks() == 0")
                        && method.contains("setup0_retry1")
                        && method.contains("REALTIME_SETUP0_EMPTY_RETRY"));
        assertTrue("生产同构RF必须复用一次严格双空重试门",
                method.contains("softwareProductionRf && model.setupAcks() == 0")
                        && method.contains("setup0Attempt2Label")
                        && method.contains("setup0_retry1"));
        assertTrue("生产同构RF必须把setup0重试轨迹写入原子结果",
                method.contains("setup0Only || sendRealtimeRelayData36")
                        && method.contains("|| softwareProductionRf"));
        assertTrue(method.contains(
                "setup0-only禁止任何后续HPI请求，静默等待绝对退桥")
                || method.contains(
                "setup0-4-only禁止VLC/清理帧，静默等待绝对退桥"));
        assertTrue(method.contains("SETUP04_ONLY_ACK_ACCEPTED")
                || method.contains("setup04Only"));
        assertTrue(source.contains(
                "runExternalDmrSetup04OnlyNoRfProof"));
        assertTrue(method.contains(
                "append(report, \"Setup1-4 sent\", false)")
                || method.contains(
                "append(report, \"Setup1-4 sent\", true)"));
        assertTrue(method.contains(
                "append(report, \"HPI cleanup sent\", false)"));
        assertTrue(method.contains("restoreCombinedFullPrepState"));
        assertTrue(method.contains("writeAtomicProbeSessionResult"));
        assertTrue(method.contains("TIMING_SETUP_DONE")
                || method.contains("TIMING_BRIDGE_ARMED"));
        assertFalse(method.contains("prepareExternalDmrSendSessionState"));
        assertFalse(method.contains("readyForSecondBridgePhase"));
        assertFalse("must not hand off SysTick to UART_HPI_TIMEOUT_ENTRY",
                method.contains("UART_HPI_TIMEOUT_ENTRY"));
        assertTrue("must arm combined dual-counter entry",
                method.contains("COMBINED_DUAL_COUNTER_ENTRY"));
        assertTrue(source.contains("0x20002f00")
                || source.contains("0x20002F00"));
        assertTrue(source.contains("FORBIDDEN_PROD_WINDOW_BEGIN"));
        assertTrue(source.contains(
                "runExternalDmrSetupFullprepIsolationNoRfProof"));
        assertTrue(source.contains(
                "runExternalDmrCombinedWindowReadonlyDump57600"));
        assertEquals("f998b11bc800891ada9f9653d0ee567bbf233f450691f42ba8f779305f873226",
                InterphoneProbe.COMBINED_DUAL_COUNTER_SHA256);
        int privacyPrepare = method.indexOf(
                "prepareExternalDmrPrivacyRuntime(input, output");
        int sramWrite = method.indexOf(
                "beforeTemporarySramWrite()", privacyPrepare);
        int bridgeArm = method.indexOf(
                "activateStep3Bridge(transport", sramWrite);
        int setupLoop = method.indexOf(
                "while (model.phase() == ExternalDmrTxModel.Phase.SETUP)",
                bridgeArm);
        int prepWait = method.indexOf(
                "mid-bridge dataGate prep wait done", setupLoop);
        int vlcLoop = method.indexOf(
                "while (model.phase() == ExternalDmrTxModel.Phase.VLC_SESSION)",
                prepWait);
        assertTrue(privacyPrepare >= 0 && sramWrite > privacyPrepare
                && bridgeArm > sramWrite
                && setupLoop > bridgeArm
                && prepWait > setupLoop
                && vlcLoop > prepWait);
        assertTrue(source.contains("COMBINED_DUAL_COUNTER_SHA256"));
        // 失败路径 finally 必须恢复 combined+dataGate
        assertTrue(method.contains("finally.emergencySramRestore")
                || method.contains("restoreCombinedDualCounterAndDataGate"));
        assertTrue(source.contains("probe_session_result.txt"));

        int preflightGate = method.indexOf(
                "loadMeasuredRuntimeFromPreflightAttestation()");
        int serialPermission = method.indexOf(
                "if (!device.canRead() || !device.canWrite())");
        int serialOpen = method.indexOf("openSerialLocked(device, BAUD_RATE");
        assertTrue(preflightGate >= 0 && serialPermission > preflightGate
                && serialOpen > serialPermission);

        int loadAttestation = method.indexOf(
                "loadMeasuredRuntimeFromPreflightAttestation()");
        int originalTemporaryState = method.indexOf(
                "originalFlag = readMemory(input, output", privacyPrepare);
        int privacyBridgeGate = method.indexOf(
                "if (!privacyTransaction.control.canEnterBridge()",
                privacyPrepare);
        int timeoutUpload = method.indexOf(
                "beforeTemporarySramWrite()",
                privacyBridgeGate);
        assertTrue(loadAttestation >= 0 && privacyPrepare > loadAttestation);
        assertTrue(method.contains("expectedMeasuredRuntime14"));
        assertTrue(privacyPrepare >= 0
                && originalTemporaryState > privacyPrepare
                && privacyBridgeGate > originalTemporaryState
                && timeoutUpload > privacyBridgeGate);
        // 禁止步骤3/4 再硬喂合成夹具合同。
        assertFalse(method.contains(
                "createPinnedExternalDmrRuntimeContract()"));

        int prepareStart = source.indexOf(
                "private ExternalDmrStep0Contract prepareExternalDmrPrivacyRuntime(");
        int prepareEnd = source.indexOf(
                "private void restoreExternalDmrPrivacyOffChannel(",
                prepareStart);
        String prepare = source.substring(prepareStart, prepareEnd);
        int restoreResponsibility = prepare.indexOf(
                "transaction.control.beforePrivacySet()");
        int baselineCapture = prepare.indexOf(
                "Privacy-off baseline runtime");
        int baselineVerified = prepare.indexOf(
                "transaction.control.baselineVerified()");
        int setCommand = prepare.indexOf(
                "String setPrivacy = query(input, output");
        int channelNormalize = prepare.indexOf(
                "Privacy channel before normalize");
        int baselineSet = prepare.indexOf("SET_EXPECTED_DIGITAL_CHANNEL");
        assertTrue(channelNormalize >= 0 && baselineSet > channelNormalize);
        assertTrue(baselineCapture >= 0
                && baselineCapture > baselineSet
                && baselineVerified > baselineCapture
                && restoreResponsibility > baselineVerified
                && setCommand > restoreResponsibility);
        int privacyRuntimeCapture = prepare.indexOf(
                "Privacy-on runtime");
        int stableFreeze = prepare.indexOf(
                "requireStableExternalDmrRuntimeSnapshots(");
        int equalExpected = prepare.indexOf(
                "requireExternalDmrRuntimeSnapshotsEqual(");
        int runtimeMatch = prepare.indexOf("contract.matchesPrivacyRuntime(");
        int runtimeVerified = prepare.indexOf(
                "transaction.control.privacyRuntimeVerified(true)");
        assertTrue(privacyRuntimeCapture > setCommand
                && stableFreeze > privacyRuntimeCapture
                && equalExpected > privacyRuntimeCapture
                && runtimeMatch > privacyRuntimeCapture
                && runtimeVerified > runtimeMatch);
        assertTrue(prepare.contains("expectedMeasuredRuntime14"));
        assertTrue(prepare.contains("createContractFromMeasuredRuntime"));

        int captureStart = source.indexOf(
                "private byte[][] captureExternalDmrRuntimeRounds(");
        int captureEnd = source.indexOf(
                "private void verifyExternalDmrPrivacyOffRuntimeRestored(",
                captureStart);
        String capture = source.substring(captureStart, captureEnd);
        assertTrue(capture.contains("EXTERNAL_DMR_SIGNALING_FLAGS_ADDRESS"));
        assertTrue(capture.contains("EXTERNAL_DMR_PRIVACY_DATA_GATE_ADDRESS"));
        assertTrue(capture.contains("EXTERNAL_DMR_SESSION_RECORD_ADDRESS"));

        int restoreStart = source.indexOf(
                "private void restoreExternalDmrPrivacyOffChannel(");
        int restoreEnd = source.indexOf(
                "static ProbeResult evaluateExternalDmrStep4OfflineOneUnitCredit()",
                restoreStart);
        String restore = source.substring(restoreStart, restoreEnd);
        int channelReadback = restore.indexOf(
                "String channelOff = query(input, output");
        int acceptRuntime = restore.indexOf(
                "acceptExternalDmrPrivacyOffRuntimeRestored(");
        int escalateReload = restore.indexOf(
                "reloadSctBaselineTrafficPlane(input, output, report, 1)");
        int restoredState = restore.indexOf(
                "transaction.control.restoreChannelVerified(true)");
        assertTrue(channelReadback >= 0
                && acceptRuntime > channelReadback
                && escalateReload > acceptRuntime
                && restoredState > escalateReload);
        assertTrue(restore.contains("运行态稳定残留已接受")
                || restore.contains("residual accepted")
                || restore.contains("稳定残留"));

        int successReload = method.indexOf(
                "reloadSctBaselineTrafficPlane(input, output, report, 1)");
        int successRuntimeRestore = method.indexOf(
                "verifyExternalDmrPrivacyOffRuntimeRestored(input, output",
                successReload);
        int successRestoredState = method.indexOf(
                "privacyTransaction.control.restoredByValidatedReload()",
                successRuntimeRestore);
        assertTrue(successReload >= 0
                && successRuntimeRestore > successReload
                && successRestoredState > successRuntimeRestore);

        int uploadHelper = source.indexOf(
                "static void uploadStep3TimeoutHandler(");
        int beforeSram = source.indexOf(
                "transport.beforeTemporarySramWrite()", uploadHelper);
        int handlerLoop = source.indexOf(
                "for (int offset = 0; offset < handler.length; offset += 4)",
                uploadHelper);
        assertTrue(uploadHelper >= 0 && beforeSram > uploadHelper
                && handlerLoop > beforeSram);

        int flagHelper = source.indexOf("static void activateStep3Bridge(");
        int beforeFlag = source.indexOf(
                "transport.beforeBridgeFlagWrite()", flagHelper);
        int flagWrite = source.indexOf("writer.write()", flagHelper);
        int flagReturned = source.indexOf(
                "transport.bridgeFlagWriteReturned()", flagHelper);
        assertTrue(flagHelper >= 0 && beforeFlag > flagHelper
                && flagWrite > beforeFlag);
        assertTrue(flagWrite < flagReturned);

        int flagRead = method.indexOf(
                "byte[] flagAfter = readMemory(input, output,");
        int textConfirmed = method.indexOf(
                "transport.commandModeConfirmedFromFlag(flagAfter)", flagRead);
        int vectorRead = method.indexOf(
                "byte[] vectorAfter = readMemory(input, output", flagRead);
        assertTrue(flagRead >= 0 && flagRead < textConfirmed);
        assertTrue(textConfirmed < vectorRead);

        int firstPersist = method.indexOf("persistStep3Evidence(");
        int safeWait = method.indexOf("long safeReadAt = bridgeArmedAt");
        assertTrue(firstPersist > safeWait);
        assertTrue(method.contains(
                "queueStep3SramSnapshot(evidenceRecords, \"pre\""));
        assertTrue(method.contains(
                "queueStep3SramSnapshot(evidenceRecords, \"uploaded\""));
        assertTrue(method.contains(
                "queueStep3SramSnapshot(evidenceRecords, \"postexit\""));
        assertTrue(source.contains(
                "queueStep3SramSnapshot(evidenceRecords, \"restored\""));
        assertTrue(method.contains(
                "queueStep3SramEvidence(evidenceRecords, \"armed\""));
        assertTrue(method.contains("COMBINED_PREP_MARKER_ADDRESS, 0x18"));
        int finallyWait = method.indexOf(
                "if (transport.needsTimeoutWait()", firstPersist);
        int finallyPostexit = method.indexOf(
                "queueStep3SramSnapshot(evidenceRecords, \"postexit\"",
                finallyWait);
        int finallyRestore = method.indexOf(
                "stateRestored = restoreCombinedFullPrepState(",
                finallyPostexit);
        assertTrue(finallyWait > firstPersist
                && finallyPostexit > finallyWait
                && finallyRestore > finallyPostexit);
        assertFalse(method.contains("sessionRaw = concatBytes"));
        assertTrue(method.contains(
                "reloadSctBaselineTrafficPlane(input, output, report, 1)"));
        assertTrue(method.contains("bestEffortStep3ControlPlaneReload"));
        assertTrue(method.contains("restoreStep3TemporaryStateAndVerify"));
    }

    @Test
    public void v198RuntimeMirrorArtifactRecoveryAndEvidenceArePinned()
            throws Exception {
        Field mainField = InterphoneProbe.class.getDeclaredField(
                "COMBINED_DUAL_COUNTER_WORDS");
        Field helperField = InterphoneProbe.class.getDeclaredField(
                "RUNTIME_MIRROR_HELPER_WORDS");
        mainField.setAccessible(true);
        helperField.setAccessible(true);
        int[] mainWords = (int[]) mainField.get(null);
        int[] helperWords = (int[]) helperField.get(null);
        Method wordsToBytes = InterphoneProbe.class.getDeclaredMethod(
                "wordsToBytes", int[].class);
        wordsToBytes.setAccessible(true);
        byte[] main = (byte[]) wordsToBytes.invoke(null, (Object) mainWords);
        byte[] helper = (byte[]) wordsToBytes.invoke(null, (Object) helperWords);
        assertEquals(336, main.length);
        assertEquals(112, helper.length);
        assertEquals(InterphoneProbe.COMBINED_DUAL_COUNTER_SHA256,
                sha256Hex(main));
        assertEquals(InterphoneProbe.RUNTIME_MIRROR_HELPER_SHA256,
                sha256Hex(helper));
        assertTrue(java.util.Arrays.stream(mainWords)
                .anyMatch(value -> value == 0x20003081));
        for (int required : new int[] {
                0x2000040d, 0x20003068, 0x20004cd3, 0x2000045c,
                0x20000410, 0x5252494d, 0x20003050, 0x54414744,
                0x20003060, 0x08021ddd
        }) {
            final int expected = required;
            assertTrue("辅助桩缺少常量0x" + Integer.toHexString(required),
                    java.util.Arrays.stream(helperWords)
                            .anyMatch(value -> value == expected));
        }
        for (byte[] code : new byte[][] {main, helper}) {
            for (int offset = 0; offset + 1 < code.length; offset += 2) {
                int halfword = (code[offset] & 0xff)
                        | ((code[offset + 1] & 0xff) << 8);
                int prefix = halfword & 0xf800;
                assertFalse("禁止Thumb-2半字前缀 @" + offset,
                        prefix == 0xe800 || prefix == 0xf000
                                || prefix == 0xf800);
                assertFalse("禁止BLX寄存器 @" + offset,
                        (halfword & 0xff87) == 0x4780);
            }
        }

        String source = new String(Files.readAllBytes(workspacePath(
                "app/src/main/java/net/elfradio/h13interphoneprobe/InterphoneProbe.java")),
                StandardCharsets.UTF_8);
        int methodStart = source.indexOf(
                "private ProbeResult executeExternalDmrVlcSessionNoRf(");
        int methodEnd = source.indexOf(
                "ProbeResult runExternalDmrStep1OfflineHandshakeProof()",
                methodStart);
        String method = source.substring(methodStart, methodEnd);
        int preMirrorRead = method.indexOf(
                "originalRuntimeMirror = readMemory");
        int beforeSramWrite = method.indexOf("beforeTemporarySramWrite()");
        int helperUpload = method.indexOf(
                "RUNTIME_MIRROR_HELPER_ADDRESS + offset");
        int restoreResponsibility = method.indexOf(
                "combinedWindowRestoreRequired = true");
        int helperReadback = method.indexOf("uploadedMirrorHelper = readMemory");
        int vectorArm = method.indexOf(
                "writeMemoryWord(input, output, RAM_VECTOR_SYSTICK");
        assertTrue(preMirrorRead >= 0 && preMirrorRead < beforeSramWrite);
        assertTrue(restoreResponsibility > beforeSramWrite
                && helperUpload > restoreResponsibility
                && helperReadback > helperUpload && vectorArm > helperReadback);
        assertTrue(method.contains("u32le(runtimeMirrorAfter, 8)"
                + " != RUNTIME_MIRROR_MARKER"));
        assertTrue(method.contains(
                "runtimeMirrorVlcFieldsMatchPreflight("));
        assertFalse(method.contains("expectedMeasuredRuntime14[4]"));
        assertTrue(method.contains("runtimeMirrorAfter[6] & 0xff) != 1"));
        assertTrue(method.contains("runtimeMirrorAfter[7] & 0xff) != 1"));
        assertTrue(method.contains("originalMirrorHelper, originalRuntimeMirror"));
        assertTrue(method.contains("&& combinedWindowRestoreRequired"));
        assertTrue(source.contains(
                "restoreWords(input, output, RUNTIME_MIRROR_HELPER_ADDRESS"));
        assertTrue(source.contains(
                "restoreWords(input, output, RUNTIME_MIRROR_ADDRESS"));
        assertTrue(source.contains("data36_flush_completed_ms="));
        assertTrue(source.contains("data36_response_captured_ms="));

        String host = new String(Files.readAllBytes(workspacePath(
                "../h13_radio/tools/h13_probe_session.ps1")),
                StandardCharsets.UTF_8);
        assertTrue(host.contains("mirror_code = 112"));
        assertTrue(host.contains("runtime_mirror = 12"));
        assertTrue(host.contains(
                "F998B11BC800891ADA9F9653D0EE567BBF233F450691F42BA8F779305F873226"));
        assertTrue(host.contains(
                "40326A970D244EEDE132EE4A05E6F078FFDC09D992F60B688061384809D6E503"));
        assertTrue(host.contains("证据门行为自测全部通过"));
        String gradle = new String(Files.readAllBytes(workspacePath(
                "app/build.gradle")), StandardCharsets.UTF_8);
        assertTrue(gradle.contains("enableV1Signing true"));
        assertTrue(gradle.contains("enableV2Signing true"));
        assertTrue(gradle.contains("enableV3Signing true"));
        assertTrue(gradle.contains("enableV4Signing false"));
    }

    @Test
    public void v190EvidenceRepairAndHostAcceptanceGateArePresent()
            throws Exception {
        String source = new String(Files.readAllBytes(workspacePath(
                "app/src/main/java/net/elfradio/h13interphoneprobe/InterphoneProbe.java")),
                StandardCharsets.UTF_8);
        for (String field : new String[] {
                "combined_code", "prep_marker", "prep_counter",
                "abs_counter", "exit_marker", "prep_phase", "prep_cursor",
                "mirror_code", "runtime_mirror",
                "data_gate", "session_state", "session_ptr_a",
                "session_ptr_b", "session_flag_1c", "session_count",
                "queue_a", "queue_b", "systick_vector", "bridge_flag",
                "boot_ready", "boot_mode"
        }) {
            assertTrue("缺少SRAM字段证据：" + field,
                    source.contains("\"" + field + "\""));
        }
        assertTrue(source.contains("record.sramStage"));
        assertTrue(source.contains("record.sramAddress"));
        assertTrue(source.contains("record.label + \" SHA-256\""));

        String host = new String(Files.readAllBytes(workspacePath(
                "../h13_radio/tools/h13_probe_session.ps1")),
                StandardCharsets.UTF_8);
        assertTrue(host.contains("exec-out run-as $ProbePkg cat $path"));
        assertTrue(host.contains("function Test-Step3SramEvidenceComplete"));
        assertTrue(host.contains("四阶段SRAM原始证据验收"));
        assertTrue(host.contains("宿主强制改判FAIL"));
        assertTrue(host.contains("pre/$field"));
        assertTrue(host.contains("restored/$field"));
        assertTrue(host.contains("function Get-ProbeArtifactPaths"));
        assertTrue(host.contains(
                "find $appFiles -maxdepth 1 -type f -print"));
        assertTrue(host.contains("function Test-SctReadyEvidenceComplete"));
        assertTrue(host.contains("function Test-AtomicResultEvidenceComplete"));
        assertTrue(host.contains(
                "function Test-ExternalDmrVlcSessionEvidenceComplete"));
        assertTrue(host.contains(
                "function Test-ExternalDmrData36EvidenceComplete"));
        assertTrue(host.contains("五前序+五VLC严格原始证据验收"));
        assertTrue(host.contains("单data36严格原始证据验收"));
        assertTrue(host.contains("dmr_external_vlc_session_no_rf"));
        assertTrue(host.contains("dmr_external_one_data36_credit_no_rf"));
        assertTrue(host.contains("原子结果版本不匹配"));
        assertFalse(host.contains(
                "if ($late -match \"PHASE DONE_PASS|Result: PASS\")"));
        assertTrue(host.contains(
                "throw \"Probe session not accepted: $sessionFailure\""));
        assertTrue(host.contains("boot_ready = 1"));
        assertTrue(host.contains("boot_mode = 1"));
        assertTrue(host.contains("hard isolation tty sample="));
        assertTrue(host.contains("hard isolation startup tty sample="));
        assertTrue(host.contains("$startupProbeSoleTtyOwnerObserved"));
        assertTrue(host.contains("SESSION ERROR："));
        assertTrue(host.contains(
                "异常路径设备原件归档失败"));
        assertTrue(host.contains(
                "Probe sole tty owner was never observed during session"));
    }

    @Test
    public void privacyPreflightCannotReachHpiOrDataPlaneAndRequiresRestore()
            throws Exception {
        String source = new String(Files.readAllBytes(workspacePath(
                "app/src/main/java/net/elfradio/h13interphoneprobe/InterphoneProbe.java")),
                StandardCharsets.UTF_8);
        int start = source.indexOf(
                "ProbeResult runExternalDmrPrivacyContractPreflight57600()");
        int end = source.indexOf(
                "static boolean isStableExternalDmrRuntimeSnapshots", start);
        assertTrue(start >= 0 && end > start);
        String method = source.substring(start, end);
        assertTrue(method.contains("prepareExternalDmrPrivacyRuntime"));
        assertTrue(method.contains("restoreExternalDmrPrivacyOffChannel"));
        assertTrue(method.contains("persistExternalDmrPreflightAttestation"));
        assertTrue(method.contains("transaction.measuredRuntime14"));
        assertTrue(method.contains(
                "success && !transaction.privacyOffRestored"));
        assertTrue(method.indexOf("persistExternalDmrPreflightAttestation")
                > method.indexOf("success && !transaction.privacyOffRestored"));
        // 预检必须测量模式（expected=null），禁止钉死合成合同。
        assertTrue(method.contains("report, null)"));
        assertFalse(method.contains(
                "createPinnedExternalDmrRuntimeContract()"));
        assertFalse(method.contains("activateStep3Bridge"));
        assertFalse(method.contains("uploadStep3TimeoutHandler"));
        assertFalse(method.contains("executeExternalDmrStep4OneUnit"));
        assertFalse(method.contains("writeMemoryWord"));
        assertFalse(method.contains("HPI_WORK_MODE_"));
    }

    @Test
    public void step3HostWrapperHasHardRecoveryGates() throws Exception {
        Path scriptPath = workspacePath(
                "../h13_radio/tools/h13_probe_session.ps1");
        String script = new String(Files.readAllBytes(scriptPath),
                StandardCharsets.UTF_8);
        assertTrue(script.contains("Assert-ProductionTtyOwner"));
        assertTrue(script.contains("Assert-GpioBaseline"));
        assertTrue(script.contains("Assert-WifiBaseline"));
        assertTrue(script.contains("$ExpectedFingerprint"));
        assertTrue(script.contains(
                "Production recovery verification failed"));
        assertTrue(script.contains("/proc/(\\d+)/fd/"));
        assertTrue(script.contains("lsof /dev/ttyHS0"));
        assertFalse(script.contains("readlink "));
        assertTrue(script.contains("STEP3_REBOOT_REQUIRED"));
        assertTrue(script.contains("$requiresRebootRecovery"));
        assertTrue(script.contains("RESTORE BLOCKED"));
        assertTrue(script.contains(
                "pm disable-user --user 0 $ProbePkg"));
        assertTrue(script.contains(
                "Probe process still running after double stop"));
        assertTrue(script.contains(
                "Probe package is not disabled-user after restore"));
    }

    @Test
    public void probeEntryRejectsUnknownAndUnapprovedPotentialRfModes() {
        assertNull(MainActivity.validateLaunch(
                "dmr_external_step1_offline_handshake", false, false, -1));
        assertTrue(MainActivity.validateLaunch(
                "拼错的模式", false, false, -1)
                .contains("清单"));
        assertTrue(MainActivity.validateLaunch(
                "dmr_external_step4_one_data36_credit", false, false, -1)
                .contains("allow_potential_rf"));
        assertTrue(MainActivity.validateLaunch(
                "dmr_external_step4_one_data36_credit", true, false, -1)
                .contains("allow_channel_mutation"));
        assertNull(MainActivity.validateLaunch(
                "dmr_external_step4_one_data36_credit", true, true, -1));
        assertTrue(MainActivity.validateLaunch(
                "dmr_external_step4_one_data36_credit", true, true, 0)
                .contains("固定使用频道low"));
        assertTrue(MainActivity.validateLaunch(
                "dmr_external_privacy_contract_preflight_57600",
                false, true, 100).contains("固定使用频道low"));
        assertTrue(MainActivity.validateLaunch(
                "analog_rf_carrier_pulse", true, false, -1)
                .contains("power_code"));
        assertNull(MainActivity.validateLaunch(
                "analog_rf_carrier_pulse", true, false, 100));
        String hobibMode = "hpi_hobib_observer_no_hpi_no_rf";
        assertNull(MainActivity.validateLaunch(hobibMode, false, false, -1));
        assertFalse(MainActivity.isPotentialRfMode(hobibMode));
        assertTrue(MainActivity.validateLaunch(hobibMode, true, false, -1)
                .contains("禁止allow_potential_rf"));
        assertTrue(MainActivity.validateLaunch(hobibMode, false, false, 0)
                .contains("禁止power_code"));
    }

    @Test
    public void manifestAndHostScriptEnforceAdbAndPotentialRfGates()
            throws Exception {
        String manifest = new String(Files.readAllBytes(workspacePath(
                "app/src/main/AndroidManifest.xml")), StandardCharsets.UTF_8);
        assertTrue(manifest.contains(
                "android:permission=\"android.permission.DUMP\""));

        String script = new String(Files.readAllBytes(workspacePath(
                "../h13_radio/tools/h13_probe_session.ps1")),
                StandardCharsets.UTF_8);
        assertTrue(script.contains("[switch]$AllowPotentialRf"));
        assertTrue(script.contains("[switch]$AllowChannelMutation"));
        assertTrue(script.contains("$KnownProbeModes"));
        assertTrue(script.contains("$PotentialRfModes"));
        assertTrue(script.contains("allow_potential_rf"));
        assertTrue(script.contains("allow_channel_mutation"));
        int knownStart = script.indexOf("$KnownProbeModes = @(");
        int potentialStart = script.indexOf("$PotentialRfModes = @(");
        int mutationStart = script.indexOf("$ChannelMutationModes = @(");
        int powerStart = script.indexOf("$PowerCodeModes = @(");
        int fixedLowStart = script.indexOf("$FixedLowPowerDmrModes = @(");
        assertTrue(knownStart >= 0 && potentialStart > knownStart
                && mutationStart > potentialStart && powerStart > mutationStart
                && fixedLowStart > powerStart);
        String knownModes = script.substring(knownStart, potentialStart);
        String potentialModes = script.substring(potentialStart, mutationStart);
        String mutationModes = script.substring(mutationStart, powerStart);
        assertTrue(knownModes.contains(
                "\"dmr_external_runtime_snapshot_57600\""));
        assertTrue(knownModes.contains(
                "\"dmr_external_privacy_contract_preflight_57600\""));
        assertTrue(knownModes.contains(
                "\"vocoder_encoder_no_input_baseline_230400_no_rf\""));
        assertFalse(potentialModes.contains(
                "\"dmr_external_runtime_snapshot_57600\""));
        assertFalse(potentialModes.contains(
                "\"dmr_external_privacy_contract_preflight_57600\""));
        assertFalse(potentialModes.contains(
                "\"vocoder_encoder_no_input_baseline_230400_no_rf\""));
        assertTrue(mutationModes.contains(
                "\"dmr_external_privacy_contract_preflight_57600\""));
        assertTrue(mutationModes.contains(
                "\"vocoder_encoder_no_input_baseline_230400_no_rf\""));
        int forbiddenPowerStart = script.indexOf(
                "$PowerCodeForbiddenModes = @(", fixedLowStart);
        String fixedLowModes = script.substring(fixedLowStart,
                forbiddenPowerStart);
        String forbiddenPowerModes = script.substring(forbiddenPowerStart,
                script.indexOf("# 所有模式门必须", forbiddenPowerStart));
        assertTrue(fixedLowModes.contains(
                "\"dmr_external_vlc_session_no_rf\""));
        assertTrue(fixedLowModes.contains(
                "\"dmr_external_step4_one_data36_credit\""));
        assertFalse(fixedLowModes.contains(
                "\"vocoder_encoder_no_input_baseline_230400_no_rf\""));
        assertTrue(forbiddenPowerModes.contains(
                "\"vocoder_encoder_no_input_baseline_230400_no_rf\""));
        assertTrue(script.contains(
                "$ModeName -in $FixedLowPowerDmrModes -and"));
        assertTrue(script.contains("$RequestedPowerCode -ne -1"));
        int modeGate = script.indexOf("$ModeName -notin $KnownProbeModes");
        int adbResolve = script.indexOf("$script:AdbPath = Resolve-Adb");
        assertTrue(modeGate >= 0 && adbResolve > modeGate);

        String activity = new String(Files.readAllBytes(workspacePath(
                "app/src/main/java/net/elfradio/h13interphoneprobe/MainActivity.java")),
                StandardCharsets.UTF_8);
        assertTrue(activity.contains("AtomicBoolean runInProgress"));
        assertTrue(activity.contains("compareAndSet(false, true)"));
        assertTrue(activity.contains("handleLaunchIntent(getIntent(), \"onCreate\")"));
        assertTrue(activity.contains("final String sessionId = intent.getStringExtra("
                + "\"launch_session_id\")"));
        assertTrue(activity.contains("runProbe(mode, route, requestedPower, rfAllowed,"));
        assertFalse(activity.contains("runButton.setEnabled(true);\n"
                + "            runButton.post(this::runProbe)"));
        assertNotNull(MainActivity.validateLaunch(
                "vocoder_encoder_no_input_baseline_230400_no_rf",
                false, false, -1));
        assertNull(MainActivity.validateLaunch(
                "vocoder_encoder_no_input_baseline_230400_no_rf",
                false, true, -1));
        assertNotNull(MainActivity.validateLaunch(
                "vocoder_encoder_no_input_baseline_230400_no_rf",
                true, true, -1));
        assertNotNull(MainActivity.validateLaunch(
                "vocoder_encoder_no_input_baseline_230400_no_rf",
                false, true, 0));
        assertFalse(MainActivity.isPotentialRfMode(
                "vocoder_encoder_no_input_baseline_230400_no_rf"));
    }

    @Test
    public void hardwareEncoderAcceptsOneMeasuredChannelAndRejectsAmbiguity() {
        String payload = "410012500,410012500,1,1,00000000,directmode,group,"
                + "slot1,slot1,off,low,0,8,0,1";
        assertEquals(payload, InterphoneProbe.extractDigitalChannelPayload(
                "\r\n+DMOGETDIGITALCH:" + payload + "\r\n"));
        assertNull(InterphoneProbe.extractDigitalChannelPayload(
                "+DMOGETDIGITALCH:" + payload + "\n"
                        + "+DMOGETDIGITALCH:" + payload));
        assertNull(InterphoneProbe.extractDigitalChannelPayload(
                "+DMOGETDIGITALCH:410012500,410012500,1"));
        assertNull(InterphoneProbe.extractDigitalChannelPayload(
                "+DMOGETDIGITALCH:" + payload + ";AT+DMOPTT=1"));
        assertNull(InterphoneProbe.extractDigitalChannelPayload(null));
    }

    @Test
    public void step4FrozenUnit0FrameHashIsPinned() throws Exception {
        byte[] frame = InterphoneProbe.requirePlainExternalTxFrameUnit0();
        assertEquals(44, frame.length);
        assertEquals(InterphoneProbe.REAL_RX_EXTERNAL_TX_FRAME_UNIT0_SHA256,
                InterphoneProbe.createFrozenExternalEncodedTxFrame(0) == null
                        ? "" : sha256Hex(frame));
        assertArrayEquals(new byte[] {
                (byte) 0x84, (byte) 0xa9, 0x61, 0x00, 0x26, 0x03, 0x01, 0x24
        }, java.util.Arrays.copyOf(frame, 8));
        assertEquals(InterphoneProbe.REAL_RX_EXTERNAL_TX_FRAME_UNIT0_SHA256,
                sha256Hex(frame));
    }

    @Test
    public void step4PrivacyRc4VectorIsSymmetricAndTouchesOnlyFortyNineBits()
            throws Exception {
        byte[] plain = InterphoneProbe.extractFrozenData36Unit(
                InterphoneProbe.REAL_RX_AMBE_108_BYTES, 0);
        byte[] key = new byte[] {0x12, 0x34, 0x56, 0x78, (byte) 0x90};
        byte[] mi = new byte[] {0x12, 0x34, 0x56, 0x78};
        byte[] encrypted = InterphoneProbe.applyDmrPrivacyRc4(plain, key, mi);
        assertArrayEquals(plain,
                InterphoneProbe.applyDmrPrivacyRc4(encrypted, key, mi));
        for (int frame = 0; frame < 4; frame++) {
            int offset = frame * 9;
            assertEquals(plain[offset + 6] & 0x7f,
                    encrypted[offset + 6] & 0x7f);
            assertEquals(plain[offset + 7], encrypted[offset + 7]);
            assertEquals(plain[offset + 8], encrypted[offset + 8]);
        }
    }

    @Test
    public void step4PrivacyMiLateEntryAndFinalWireHashesArePinned()
            throws Exception {
        byte[] evolved = InterphoneProbe.evolveDmrPrivacyMi(
                new byte[] {0x12, 0x34, 0x56, 0x78});
        assertArrayEquals(new byte[] {(byte) 0xb4, 0x68, (byte) 0xe0, 0x67},
                evolved);
        assertArrayEquals(new byte[] {(byte) 0xb, 8, 6, 4, 0xe, 7, 6, 0,
                        3, 0xa, 4, 0, 9, 0xf, 0xb, 9, 0xb, 5},
                InterphoneProbe.createDmrLateEntryFragments(evolved));
        byte[] frame =
                InterphoneProbe.requireHostEncryptedExternalTxFrameUnit0();
        assertEquals(44, frame.length);
        assertEquals(InterphoneProbe.EXTERNAL_DMR_PRIVACY_FRAME_UNIT0_SHA256,
                sha256Hex(frame));
        assertEquals(InterphoneProbe.EXTERNAL_DMR_PRIVACY_DATA36_UNIT0_SHA256,
                sha256Hex(java.util.Arrays.copyOfRange(frame, 8, 44)));
        assertFalse(java.util.Arrays.equals(frame,
                InterphoneProbe.requirePlainExternalTxFrameUnit0()));
    }

    @Test
    public void step4ExecutorRejectsPlainFrameBeforeAnyIo()
            throws Exception {
        Step4FakeIo io = new Step4FakeIo();
        try {
            InterphoneProbe.executeExternalDmrStep4OneUnit(true, true, true,
                    plainFrame(), io,
                    new Step4Evidence(),
                    new InterphoneProbe.ExternalDmrStep4OneUnitExecution());
            fail("明文帧不得进入raw bridge步骤4执行器");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("明文"));
        }
        assertEquals(0, io.writes);
        assertEquals(0, io.flushes);
        assertEquals(0, io.reads);
        assertEquals(0, io.quietDrains);
    }

    @Test
    public void step4ExecutorRejectsCorruptedEncryptedFrameBeforeAnyIo()
            throws Exception {
        Step4FakeIo io = new Step4FakeIo();
        byte[] corrupted = encryptedFrame();
        corrupted[8] ^= 0x01;
        try {
            InterphoneProbe.executeExternalDmrStep4OneUnit(true, true, true,
                    corrupted, io,
                    new Step4Evidence(),
                    new InterphoneProbe.ExternalDmrStep4OneUnitExecution());
            fail("损坏密文不得进入raw bridge步骤4执行器");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("固定哈希密文"));
        }
        assertEquals(0, io.writes);
        assertEquals(0, io.flushes);
        assertEquals(0, io.reads);
        assertEquals(0, io.quietDrains);
    }

    @Test
    public void step4OfflineOneUnitCreditPassesWithoutSerial() {
        ProbeResult result =
                InterphoneProbe.evaluateExternalDmrStep4OfflineOneUnitCredit();
        assertTrue(result.report, result.success);
        assertTrue(result.report.contains("Serial opened: false"));
        assertTrue(result.report.contains("Write-only rejected: true"));
        assertTrue(result.report.contains("Undrained window rejected: true"));
        assertTrue(result.report.contains("VLC-as-credit rejected: true"));
        assertTrue(result.report.contains("Leading-non-credit rejected: true"));
        assertTrue(result.report.contains("Empty credit rejected: true"));
        assertTrue(result.report.contains("Credit shape accepted: true"));
        assertTrue(result.report.contains("Credit strict first-frame gate: true")
                || result.report.contains("strict"));
        assertTrue(result.report.contains("Second data36 rejected: true"));
        assertTrue(result.report.contains("非注入")
                || result.report.contains("非射频"));
    }

    @Test
    public void step4OneUnitModelRejectsWriteOnlyAndWrongCredit()
            throws Exception {
        byte[] frame =
                InterphoneProbe.requireHostEncryptedExternalTxFrameUnit0();
        InterphoneProbe.ExternalDmrStep4OneUnitModel model =
                InterphoneProbe.ExternalDmrStep4OneUnitModel
                        .forVerifiedHostEncryptedUnit0();
        assertNull(model.expectedFrame());
        model.markHandshakeComplete();
        assertArrayEquals(frame, model.expectedFrame());

        InterphoneProbe.ExternalDmrStep4OneUnitModel.ExchangeWindow window =
                model.openPostSendWindow(frame,
                        InterphoneProbe.offlineQuietWindowProof());
        assertTrue(window != null);
        assertFalse(model.acceptWriteCompletedWithoutCredit(window));
        assertEquals(InterphoneProbe.ExternalDmrStep4OneUnitModel.Phase.FAILED,
                model.phase());

        model = InterphoneProbe.ExternalDmrStep4OneUnitModel
                .forVerifiedHostEncryptedUnit0();
        model.markHandshakeComplete();
        window = model.openPostSendWindow(frame,
                InterphoneProbe.offlineQuietWindowProof());
        assertFalse(model.acceptCredit(window,
                hpiWireFrame(5, new byte[] {0x43})));
        assertEquals(InterphoneProbe.ExternalDmrStep4OneUnitModel.Phase.FAILED,
                model.phase());

        model = InterphoneProbe.ExternalDmrStep4OneUnitModel
                .forVerifiedHostEncryptedUnit0();
        model.markHandshakeComplete();
        window = model.openPostSendWindow(frame,
                InterphoneProbe.offlineQuietWindowProof());
        byte[] credit = hpiFrame(0x20, new byte[] {0x01, 0x00});
        assertTrue(InterphoneProbe.isStrictPostSendExternalEncodedTxCredit(
                credit));
        assertTrue(model.acceptCredit(window, credit));
        assertEquals(InterphoneProbe.ExternalDmrStep4OneUnitModel.Phase.ACCEPTED,
                model.phase());
        assertNull(model.expectedFrame());
        assertArrayEquals(credit,
                InterphoneProbe.extractFirstExternalEncodedTxCreditFrame(
                        credit));
        // 单单元：完成后禁止再发
        assertNull(model.openPostSendWindow(frame,
                InterphoneProbe.offlineQuietWindowProof()));
        assertEquals(InterphoneProbe.ExternalDmrStep4OneUnitModel.Phase.FAILED,
                model.phase());
    }

    @Test
    public void step4StrictCreditRejectsLeadingNonCreditAndAcceptsFirstFrame() {
        byte[] credit = hpiFrame(0x20, new byte[] {0x01, 0x00});
        byte[] paddedOneByteCredit = hpiWireFrame(0x20,
                new byte[] {0x01});
        byte[] leading = hpiFrame(0, new byte[] {0x3e, 0x00});
        byte[] combined = new byte[leading.length + credit.length];
        System.arraycopy(leading, 0, combined, 0, leading.length);
        System.arraycopy(credit, 0, combined, leading.length, credit.length);
        assertTrue(InterphoneProbe.containsExternalEncodedTxCredit(combined));
        assertFalse(InterphoneProbe.isStrictPostSendExternalEncodedTxCredit(
                combined));
        assertTrue(InterphoneProbe.isStrictPostSendExternalEncodedTxCredit(
                credit));
        assertTrue(InterphoneProbe.isStrictPostSendExternalEncodedTxCredit(
                paddedOneByteCredit));
        assertFalse(InterphoneProbe.isStrictPostSendExternalEncodedTxCredit(
                hpiFrame(0x20, new byte[] {0x01})));
        assertFalse(InterphoneProbe.isStrictPostSendExternalEncodedTxCredit(
                new byte[0]));
        assertFalse(InterphoneProbe.isStrictPostSendExternalEncodedTxCredit(
                hpiWireFrame(5, new byte[] {0x43})));
    }

    @Test
    public void step4StrictCreditExposesExactFieldsAndPaddingShape() {
        byte[] oneByte = hpiWireFrame(0x7e, new byte[] {0x01});
        InterphoneProbe.ExternalEncodedTxCreditShape one =
                InterphoneProbe.parseStrictExternalEncodedTxCredit(oneByte);
        assertTrue(one != null);
        assertEquals(0x7e, one.packetType);
        assertEquals(1, one.payloadLength);
        assertEquals(1, one.responseValue);
        assertEquals(-1, one.secondPayloadByte);
        assertEquals(7, one.declaredFrameLength);
        assertEquals(8, one.wireLength);
        assertTrue(one.paddingPresent);

        byte[] twoByte = hpiWireFrame(0x31, new byte[] {0x00, (byte) 0xa5});
        InterphoneProbe.ExternalEncodedTxCreditShape two =
                InterphoneProbe.parseStrictExternalEncodedTxCredit(twoByte);
        assertTrue(two != null);
        assertEquals(0x31, two.packetType);
        assertEquals(2, two.payloadLength);
        assertEquals(0, two.responseValue);
        assertEquals(0xa5, two.secondPayloadByte);
        assertEquals(8, two.declaredFrameLength);
        assertEquals(8, two.wireLength);
        assertFalse(two.paddingPresent);

        byte[] nonzeroPadding = oneByte.clone();
        nonzeroPadding[nonzeroPadding.length - 1] = 0x55;
        assertNull(InterphoneProbe.parseStrictExternalEncodedTxCredit(
                hpiFrame(0x7e, new byte[] {0x01})));
        assertNull(InterphoneProbe.parseStrictExternalEncodedTxCredit(
                nonzeroPadding));
        assertNull(InterphoneProbe.parseStrictExternalEncodedTxCredit(
                new byte[0]));
    }

    @Test
    public void step4OneUnitModelRejectsUndrainedWindow() throws Exception {
        byte[] frame =
                InterphoneProbe.requireHostEncryptedExternalTxFrameUnit0();
        InterphoneProbe.ExternalDmrStep4OneUnitModel model =
                InterphoneProbe.ExternalDmrStep4OneUnitModel
                        .forVerifiedHostEncryptedUnit0();
        model.markHandshakeComplete();
        assertNull(model.openPostSendWindow(frame, null));
        assertEquals(InterphoneProbe.ExternalDmrStep4OneUnitModel.Phase.FAILED,
                model.phase());
    }

    @Test
    public void quietWindowProofIsPrivateSingleUseAndOwnerBound()
            throws Exception {
        java.lang.reflect.Constructor<?>[] constructors =
                InterphoneProbe.QuietWindowProof.class.getDeclaredConstructors();
        assertTrue(constructors.length >= 1);
        for (java.lang.reflect.Constructor<?> constructor : constructors) {
            assertFalse(Modifier.isPublic(constructor.getModifiers()));
            assertFalse(Modifier.isProtected(constructor.getModifiers()));
        }

        byte[] frame =
                InterphoneProbe.requireHostEncryptedExternalTxFrameUnit0();
        InterphoneProbe.QuietWindowProof proof =
                InterphoneProbe.offlineQuietWindowProof();
        InterphoneProbe.ExternalDmrStep4OneUnitModel first =
                InterphoneProbe.ExternalDmrStep4OneUnitModel
                        .forVerifiedHostEncryptedUnit0();
        first.markHandshakeComplete();
        assertTrue(first.openPostSendWindow(frame, proof) != null);

        InterphoneProbe.ExternalDmrStep4OneUnitModel second =
                InterphoneProbe.ExternalDmrStep4OneUnitModel
                        .forVerifiedHostEncryptedUnit0();
        second.markHandshakeComplete();
        assertNull(second.openPostSendWindow(frame, proof));
        assertEquals(InterphoneProbe.ExternalDmrStep4OneUnitModel.Phase.FAILED,
                second.phase());
    }

    @Test
    public void step4BudgetFitsBridgeWindowWithTwoSecondMargin() {
        // v1.77：step3 锚定 prep 后 step4 = 12520 + 300 + 1500 + 500
        assertEquals(14820L,
                InterphoneProbe.externalDmrStep4WorstCaseMs());
        assertTrue(InterphoneProbe.externalDmrStep4BudgetFitsBridgeWindow());
        assertTrue(InterphoneProbe.externalDmrStep4WorstCaseMs() + 2000L
                < 18000L);
    }

    @Test
    public void step4ExecutorWritesExactlyOneFrozenUnitAfterAllGates()
            throws Exception {
        Step4FakeIo io = new Step4FakeIo();
        Step4Evidence evidence = new Step4Evidence();
        InterphoneProbe.ExternalDmrStep4OneUnitExecution execution =
                new InterphoneProbe.ExternalDmrStep4OneUnitExecution();
        InterphoneProbe.executeExternalDmrStep4OneUnit(true, true, true,
                encryptedFrame(), io,
                evidence, execution);
        assertEquals(1, io.writes);
        assertEquals(1, io.flushes);
        assertEquals(1, io.reads);
        assertArrayEquals(
                InterphoneProbe.requireHostEncryptedExternalTxFrameUnit0(),
                io.written);
        assertTrue(execution.writeAttempted());
        assertTrue(execution.writeCompleted());
        assertTrue(execution.creditShapeAccepted());
        assertEquals(151L, execution.flushCompletedAtMs());
        assertEquals(152L, execution.responseCapturedAtMs());
        assertEquals(1L, execution.responseAfterFlushMs());
        assertEquals(0x20, execution.creditShape().packetType);
        assertEquals(2, execution.creditShape().payloadLength);
        assertEquals(1, execution.creditShape().responseValue);
        assertEquals(0, execution.creditShape().secondPayloadByte);
        assertEquals(8, execution.creditShape().declaredFrameLength);
        assertEquals(8, execution.creditShape().wireLength);
        assertFalse(execution.creditShape().paddingPresent);
        assertEquals(1, evidence.drains);
        assertEquals(1, evidence.attempts);
        assertEquals(1, evidence.completions);
        assertEquals(1, evidence.responses);

        try {
            InterphoneProbe.executeExternalDmrStep4OneUnit(true, true, true,
                    encryptedFrame(), io,
                    evidence, execution);
            fail("同一执行对象不得发送第二帧");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("第二次发送"));
        }
        assertEquals(1, io.writes);
    }

    @Test
    public void step4ExecutorPreconditionsAndEvidenceFailBeforeWrite()
            throws Exception {
        Step4FakeIo io = new Step4FakeIo();
        Step4Evidence evidence = new Step4Evidence();
        try {
            InterphoneProbe.executeExternalDmrStep4OneUnit(false, true, true,
                    encryptedFrame(), io,
                    evidence,
                    new InterphoneProbe.ExternalDmrStep4OneUnitExecution());
            fail("bridge门必须阻断");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("bridge"));
        }
        try {
            InterphoneProbe.executeExternalDmrStep4OneUnit(true, false, true,
                    encryptedFrame(), io,
                    evidence,
                    new InterphoneProbe.ExternalDmrStep4OneUnitExecution());
            fail("握手门必须阻断");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("握手"));
        }
        try {
            InterphoneProbe.executeExternalDmrStep4OneUnit(true, true, false,
                    encryptedFrame(), io,
                    evidence,
                    new InterphoneProbe.ExternalDmrStep4OneUnitExecution());
            fail("privacy运行态门必须阻断");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("privacy运行态"));
        }
        assertEquals(0, io.quietDrains);
        assertEquals(0, io.writes);
        assertEquals(0, io.flushes);
        assertEquals(0, io.reads);
        evidence.throwOnWriteAttempt = true;
        try {
            InterphoneProbe.executeExternalDmrStep4OneUnit(true, true, true,
                    encryptedFrame(), io,
                    evidence,
                    new InterphoneProbe.ExternalDmrStep4OneUnitExecution());
            fail("证据落盘失败必须阻断物理写入");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("证据"));
        }
        assertEquals(0, io.writes);
    }

    @Test
    public void step4ExecutorPreservesWriteAndFlushFailureFacts()
            throws Exception {
        Step4FakeIo writeIo = new Step4FakeIo();
        writeIo.writeError = new IOException("写失败");
        InterphoneProbe.ExternalDmrStep4OneUnitExecution writeExecution =
                new InterphoneProbe.ExternalDmrStep4OneUnitExecution();
        try {
            InterphoneProbe.executeExternalDmrStep4OneUnit(true, true, true,
                    encryptedFrame(), writeIo, new Step4Evidence(), writeExecution);
            fail("写异常必须失败");
        } catch (IOException expected) {
            assertTrue(writeExecution.writeAttempted());
            assertFalse(writeExecution.writeCompleted());
            assertEquals(0, writeIo.reads);
        }

        Step4FakeIo flushIo = new Step4FakeIo();
        flushIo.flushError = new IOException("flush失败");
        InterphoneProbe.ExternalDmrStep4OneUnitExecution flushExecution =
                new InterphoneProbe.ExternalDmrStep4OneUnitExecution();
        try {
            InterphoneProbe.executeExternalDmrStep4OneUnit(true, true, true,
                    encryptedFrame(), flushIo, new Step4Evidence(), flushExecution);
            fail("flush异常必须失败");
        } catch (IOException expected) {
            assertTrue(flushExecution.writeAttempted());
            assertFalse(flushExecution.writeCompleted());
            assertEquals(0, flushIo.reads);
        }
    }

    @Test
    public void softwareStep4ExecutorAcceptsApprovedNonHistoricalEnvelope()
            throws Exception {
        byte[] softwareFrame = encryptedFrame();
        softwareFrame[43] ^= 0x5a;
        assertTrue(InterphoneProbe.looksLikeExternalData36Frame(softwareFrame));
        assertFalse(java.util.Arrays.equals(softwareFrame, encryptedFrame()));

        Step4FakeIo io = new Step4FakeIo();
        Step4Evidence evidence = new Step4Evidence();
        InterphoneProbe.ExternalDmrStep4OneUnitExecution execution =
                new InterphoneProbe.ExternalDmrStep4OneUnitExecution();
        InterphoneProbe.executeExternalDmrStep4OneUnit(true, true, true,
                softwareFrame, io, evidence, execution, false);

        assertEquals(1, io.writes);
        assertEquals(1, io.flushes);
        assertEquals(1, io.reads);
        assertArrayEquals(softwareFrame, io.written);
        assertTrue(execution.writeAttempted());
        assertTrue(execution.writeCompleted());
        assertTrue(execution.creditShapeAccepted());
    }

    @Test
    public void step4ExecutorEnforcesMonotonicFlushAndResponseOrder()
            throws Exception {
        Step4FakeIo equalIo = new Step4FakeIo();
        equalIo.responseCaptureMs = equalIo.flushCompletionMs;
        InterphoneProbe.ExternalDmrStep4OneUnitExecution equalExecution =
                new InterphoneProbe.ExternalDmrStep4OneUnitExecution();
        InterphoneProbe.executeExternalDmrStep4OneUnit(true, true, true,
                encryptedFrame(), equalIo, new Step4Evidence(), equalExecution);
        assertEquals(0L, equalExecution.responseAfterFlushMs());

        Step4FakeIo earlyResponseIo = new Step4FakeIo();
        earlyResponseIo.responseCaptureMs = 150L;
        InterphoneProbe.ExternalDmrStep4OneUnitExecution earlyExecution =
                new InterphoneProbe.ExternalDmrStep4OneUnitExecution();
        try {
            InterphoneProbe.executeExternalDmrStep4OneUnit(true, true, true,
                    encryptedFrame(), earlyResponseIo, new Step4Evidence(),
                    earlyExecution);
            fail("响应时刻早于flush必须失败");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("早于flush"));
        }
        assertTrue(earlyExecution.writeCompleted());
        assertEquals(151L, earlyExecution.flushCompletedAtMs());
        assertEquals(150L, earlyExecution.responseCapturedAtMs());
        assertEquals(-1L, earlyExecution.responseAfterFlushMs());
        assertArrayEquals(earlyResponseIo.response, earlyExecution.responseRaw());
        assertFalse(earlyExecution.creditShapeAccepted());

        Step4FakeIo earlyFlushIo = new Step4FakeIo();
        earlyFlushIo.flushCompletionMs = 149L;
        InterphoneProbe.ExternalDmrStep4OneUnitExecution earlyFlushExecution =
                new InterphoneProbe.ExternalDmrStep4OneUnitExecution();
        try {
            InterphoneProbe.executeExternalDmrStep4OneUnit(true, true, true,
                    encryptedFrame(), earlyFlushIo, new Step4Evidence(),
                    earlyFlushExecution);
            fail("flush时刻早于静默证明必须失败");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("静默窗口"));
        }
        assertTrue(earlyFlushExecution.writeCompleted());
        assertEquals(149L, earlyFlushExecution.flushCompletedAtMs());
        assertEquals(0, earlyFlushIo.reads);

        Step4FakeIo readErrorIo = new Step4FakeIo();
        readErrorIo.readError = new IOException("读失败");
        InterphoneProbe.ExternalDmrStep4OneUnitExecution readErrorExecution =
                new InterphoneProbe.ExternalDmrStep4OneUnitExecution();
        try {
            InterphoneProbe.executeExternalDmrStep4OneUnit(true, true, true,
                    encryptedFrame(), readErrorIo, new Step4Evidence(),
                    readErrorExecution);
            fail("读异常必须失败");
        } catch (IOException expected) {
            assertTrue(readErrorExecution.writeCompleted());
            assertEquals(151L, readErrorExecution.flushCompletedAtMs());
            assertEquals(-1L, readErrorExecution.responseCapturedAtMs());
            assertEquals(-1L, readErrorExecution.responseAfterFlushMs());
        }
    }

    @Test
    public void step4ExecutorRejectsQuietFailureEmptyAndMalformedResponses()
            throws Exception {
        Step4FakeIo quietIo = new Step4FakeIo();
        quietIo.quietError = new IOException("无静默窗口");
        try {
            InterphoneProbe.executeExternalDmrStep4OneUnit(true, true, true,
                    encryptedFrame(), quietIo, new Step4Evidence(),
                    new InterphoneProbe.ExternalDmrStep4OneUnitExecution());
            fail("静默失败必须阻断");
        } catch (IOException expected) {
            assertEquals(0, quietIo.writes);
        }

        byte[] credit = hpiFrame(0x20, new byte[] {0x01, 0x00});
        byte[] error = hpiFrame(0, new byte[] {0x17, 0x06});
        byte[] creditThenError = new byte[credit.length + error.length];
        System.arraycopy(credit, 0, creditThenError, 0, credit.length);
        System.arraycopy(error, 0, creditThenError, credit.length,
                error.length);
        byte[] duplicateCredit = new byte[credit.length * 2];
        System.arraycopy(credit, 0, duplicateCredit, 0, credit.length);
        System.arraycopy(credit, 0, duplicateCredit, credit.length,
                credit.length);
        byte[] nonzeroPadding = hpiWireFrame(0x20, new byte[] {0x01});
        nonzeroPadding[nonzeroPadding.length - 1] = 0x55;
        byte[][] rejected = {
                new byte[0],
                hpiFrame(0x20, new byte[] {0x01}),
                nonzeroPadding,
                error,
                creditThenError,
                duplicateCredit,
                java.util.Arrays.copyOf(credit, credit.length + 1)
        };
        for (byte[] response : rejected) {
            Step4FakeIo io = new Step4FakeIo();
            io.response = response;
            InterphoneProbe.ExternalDmrStep4OneUnitExecution execution =
                    new InterphoneProbe.ExternalDmrStep4OneUnitExecution();
            try {
                InterphoneProbe.executeExternalDmrStep4OneUnit(true, true, true,
                        encryptedFrame(), io, new Step4Evidence(), execution);
                fail("畸形或非唯一短响应必须失败");
            } catch (IOException expected) {
                assertEquals(1, io.writes);
                assertEquals(1, io.reads);
                assertTrue(execution.writeCompleted());
                assertFalse(execution.creditShapeAccepted());
            }
        }
    }

    @Test
    public void step4DeviceSourceSendsData36OnlyAfterCompleteAndGatesCredit()
            throws Exception {
        Path sourcePath = workspacePath(
                "app/src/main/java/net/elfradio/h13interphoneprobe/InterphoneProbe.java");
        String source = new String(Files.readAllBytes(sourcePath),
                StandardCharsets.UTF_8);
        int methodStart = source.indexOf(
                "private ProbeResult executeExternalDmrVlcSessionNoRf(");
        assertTrue(methodStart >= 0);
        assertTrue(source.indexOf("boolean setup04Only", methodStart) > methodStart);
        assertTrue(source.contains(
                "loadMeasuredRuntimeFromPreflightAttestation()"));
        int completeGate = source.indexOf(
                "model.phase() != ExternalDmrTxModel.Phase.COMPLETE",
                methodStart);
        int data36Block = source.indexOf("if (sendOneHostEncryptedData36)",
                completeGate);
        int deviceEncryptedFrame = source.indexOf(
                "requireHostEncryptedExternalTxFrameUnit0()", data36Block);
        int helperCall = source.indexOf("executeExternalDmrStep4OneUnit(",
                data36Block);
        int helperIo = source.indexOf("new ExternalDmrStep4OneUnitIo()",
                helperCall);
        int helperStart = source.indexOf(
                "static ExternalDmrStep4OneUnitExecution "
                        + "executeExternalDmrStep4OneUnit(");
        int writeCall = source.indexOf("io.write(frame.clone())", helperStart);
        int privacyGate = source.indexOf("if (!privacyRuntimeVerified)",
                helperStart);
        int plainRejectGate = source.indexOf(
                "requirePlainExternalTxFrameUnit0()", helperStart);
        int encryptedFrameGate = source.indexOf(
                "requireHostEncryptedExternalTxFrameUnit0()", helperStart);
        int quietCall = source.indexOf("io.establishQuietWindow()", helperStart);
        int creditGate = source.indexOf("model.acceptCredit(window, response)",
                helperStart);
        int helperEnd = source.indexOf("static final class AnalogVoiceInTxModel",
                helperStart);
        int cleanup = source.indexOf("hpiExitTrafficPlaneWhileBridged",
                helperCall);
        assertTrue(completeGate >= 0 && data36Block > completeGate);
        assertTrue(deviceEncryptedFrame > data36Block
                && helperCall > deviceEncryptedFrame
                && cleanup > helperCall);
        assertFalse(source.substring(data36Block, helperCall).contains(
                "requirePlainExternalTxFrameUnit0()"));
        assertTrue(helperStart >= 0 && writeCall > helperStart);
        assertTrue(privacyGate > helperStart
                && plainRejectGate > privacyGate
                && encryptedFrameGate > plainRejectGate
                && quietCall > encryptedFrameGate
                && writeCall > quietCall);
        assertTrue(creditGate > writeCall);
        assertTrue(helperEnd > helperStart);
        String helper = source.substring(helperStart, helperEnd);
        assertTrue(helper.contains("quiet.proof"));
        assertTrue(helper.contains("flushCompletedAtMs"));
        assertTrue(helper.contains("responseCapturedAtMs"));
        assertFalse(helper.contains("offlineQuietWindowProof"));
        assertFalse(source.contains("openPostSendWindow(request, true)"));
        assertFalse(source.contains("openPostSendWindow(frame, true)"));
        assertTrue(helperIo > helperCall);
        assertTrue(source.substring(helperCall, helperIo).contains(
                "privacyTransaction.privacyOnVerified"));
        assertTrue(source.substring(helperCall, helperIo).contains(
                "privacyTransaction.control.canEnterBridge()"));
        assertTrue(source.contains(
                "runExternalDmrOneData36CreditNoRfProof"));
        assertTrue(source.contains(
                "evaluateExternalDmrStep4OfflineOneUnitCredit"));
    }

    private static String sha256Hex(byte[] data) throws Exception {
        java.security.MessageDigest digest =
                java.security.MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(data);
        StringBuilder sb = new StringBuilder(hash.length * 2);
        for (byte value : hash) {
            sb.append(String.format(java.util.Locale.US, "%02x", value & 0xff));
        }
        return sb.toString();
    }

    @Test
    public void analogVoiceInPcmFrameSwapsWordsAndPadsOddWireLength() {
        byte[] pcm = new byte[2560];
        for (int index = 0; index < pcm.length; index++) {
            pcm[index] = (byte) index;
        }
        byte[] original = pcm.clone();
        byte[] frame = InterphoneProbe.createAnalogVoiceInPcmFrame(pcm, 1);
        assertEquals(1290, frame.length);
        assertArrayEquals(new byte[] {
                (byte) 0x84, (byte) 0xa9, 0x61, 0x05, 0x03, 0x03,
                0x00, 0x02, (byte) 0x80,
                0x01, 0x00, 0x03, 0x02
        }, java.util.Arrays.copyOf(frame, 13));
        assertEquals((byte) 0xff, frame[1287]);
        assertEquals((byte) 0xfe, frame[1288]);
        assertEquals(0, frame[1289]);
        assertArrayEquals(original, pcm);
    }

    @Test
    public void analogOneBlockToneIsExact8kPcmAnd1290ByteWireFrame() {
        byte[] pcm = InterphoneProbe.createTonePcm8kS16le(1000, 2000, 640);
        assertEquals(1280, pcm.length);
        assertArrayEquals(new byte[] {
                0x00, 0x00, (byte) 0x86, 0x05,
                (byte) 0xd0, 0x07, (byte) 0x86, 0x05,
                0x00, 0x00, 0x7a, (byte) 0xfa,
                0x30, (byte) 0xf8, 0x7a, (byte) 0xfa
        }, java.util.Arrays.copyOf(pcm, 16));
        byte[] frame = InterphoneProbe.createAnalogVoiceInPcmFrame(pcm, 0);
        assertEquals(1290, frame.length);
        assertArrayEquals(new byte[] {
                (byte) 0x84, (byte) 0xa9, 0x61, 0x05, 0x03, 0x03,
                0x00, 0x02, (byte) 0x80, 0x00, 0x00, 0x05, (byte) 0x86
        }, java.util.Arrays.copyOf(frame, 13));
    }

    @Test
    public void analogVoiceInControlFramesMatchH13Parameters() {
        byte[][] codec = InterphoneProbe.createAnalogTxCodecFrames(0x0a);
        assertArrayEquals(hpiFrame(0x40,
                new byte[] {0, 1, 0x10, 0x40, 0, 0}), codec[0]);
        assertArrayEquals(hpiFrame(0x40,
                new byte[] {0, 1, 0x3b, 0x11, 0, 0}), codec[1]);
        assertArrayEquals(hpiFrame(0x40,
                new byte[] {0, 0, 0x56, (byte) 0xf3, 0, 0}), codec[2]);
        assertArrayEquals(hpiFrame(0x40,
                new byte[] {0, 0, 0x57, (byte) 0xba, 0, 0}), codec[3]);
        assertArrayEquals(hpiFrame(0x40,
                new byte[] {0, 0, 0x58, 0x0a, 0, 0}), codec[4]);
        assertArrayEquals(hpiFrame(0, new byte[] {0x3e, (byte) 0x80}),
                InterphoneProbe.createAnalogVoiceInRouteFrame());
        assertArrayEquals(hpiFrame(0, new byte[] {0x1a, (byte) 0x80}),
                InterphoneProbe.createAnalogVoiceInProcessModeFrame());
        assertArrayEquals(hpiFrame(0, new byte[] {0x18, 0x02, 0x00, 0x00}),
                InterphoneProbe.createAnalogVoiceInWorkModeFrame());
        assertArrayEquals(new byte[] {
                (byte) 0x84, (byte) 0xa9, 0x61, 0x00, 0x03, 0x00,
                0x26, 0x04, 0x00, 0x00
        }, InterphoneProbe.createAnalogSubaudioFrame(0, 0, 0, 0));
        assertArrayEquals(new byte[] {
                (byte) 0x84, (byte) 0xa9, 0x61, 0x00, 0x03, 0x00,
                0x26, 0x44, 0x08, 0x00
        }, InterphoneProbe.createAnalogSubaudioFrame(1, 7, 0, 0));
        assertArrayEquals(new byte[] {
                (byte) 0x84, (byte) 0xa9, 0x61, 0x00, 0x03, 0x00,
                0x26, (byte) 0xa4, 0x0c, 0x00
        }, InterphoneProbe.createAnalogSubaudioFrame(2, 0, 11, 1));
        assertArrayEquals(new byte[] {
                (byte) 0x84, (byte) 0xa9, 0x61, 0x00, 0x01, 0x00,
                0x78, 0x00
        }, InterphoneProbe.createAnalogCallStartFrame());
        assertArrayEquals(new byte[] {
                (byte) 0x84, (byte) 0xa9, 0x61, 0x00, 0x01, 0x00,
                0x21, 0x00
        }, InterphoneProbe.createAnalogStopCallFrame());
        assertArrayEquals(hpiFrame(0, new byte[] {0x18, 0x00, 0x00, 0x00}),
                InterphoneProbe.createAnalogWorkModeIdleFrame());
    }

    @Test
    public void analogVoiceInControlAcksAreExactAndNeverDataCredit() {
        byte[][] requests = {
                InterphoneProbe.createAnalogVoiceInRouteFrame(),
                InterphoneProbe.createAnalogVoiceInProcessModeFrame(),
                InterphoneProbe.createAnalogVoiceInWorkModeFrame(),
                InterphoneProbe.createAnalogSubaudioFrame(0, 0, 0, 0),
                InterphoneProbe.createAnalogCallStartFrame()
        };
        int[] fields = {0x3e, 0x1a, 0x18, 0x26, 0x78};
        for (int index = 0; index < requests.length; index++) {
            byte[] ack = hpiFrame(0, new byte[] {(byte) fields[index], 0x00});
            assertArrayEquals(ack,
                    InterphoneProbe.createAnalogControlAckFrame(requests[index]));
            assertTrue(InterphoneProbe.containsAnalogControlAck(ack,
                    requests[index]));
            assertFalse(InterphoneProbe.containsAnalogVoiceInCredit(ack));

            assertFalse(InterphoneProbe.containsAnalogControlAck(
                    hpiFrame(0, new byte[] {(byte) fields[index], 0x01}),
                    requests[index]));
            assertFalse(InterphoneProbe.containsAnalogControlAck(
                    hpiFrame(0, new byte[] {(byte) (fields[index] ^ 1), 0x00}),
                    requests[index]));
        }
        assertTrue(InterphoneProbe.containsAnalogVoiceInCredit(
                hpiFrame(0x20, new byte[] {0x01, 0x00})));
    }

    @Test
    public void analogControlAckParserUsesDeclaredFrameBoundaries() {
        byte[] request = InterphoneProbe.createAnalogVoiceInRouteFrame();
        byte[] ack = InterphoneProbe.createAnalogControlAckFrame(request);
        byte[] embeddedAck = hpiFrame(0x30, ack);
        assertFalse(InterphoneProbe.containsAnalogControlAck(embeddedAck, request));

        byte[] truncated = java.util.Arrays.copyOf(embeddedAck, 10);
        byte[] fakeAfterTruncation = new byte[truncated.length + ack.length];
        System.arraycopy(truncated, 0, fakeAfterTruncation, 0, truncated.length);
        System.arraycopy(ack, 0, fakeAfterTruncation, truncated.length, ack.length);
        assertFalse(InterphoneProbe.containsAnalogControlAck(
                fakeAfterTruncation, request));

        byte[] oddFrame = hpiFrame(0x30, new byte[] {0x7f});
        byte[] framedAck = new byte[oddFrame.length + 1 + ack.length];
        System.arraycopy(oddFrame, 0, framedAck, 0, oddFrame.length);
        System.arraycopy(ack, 0, framedAck, oddFrame.length + 1, ack.length);
        assertTrue(InterphoneProbe.containsAnalogControlAck(framedAck, request));
        framedAck[oddFrame.length] = 0x55;
        assertFalse(InterphoneProbe.containsAnalogControlAck(framedAck, request));
    }

    @Test
    public void analogCodecAckParserUsesDeclaredFrameBoundaries() {
        byte[] ack = InterphoneProbe.createAnalogCodecWriteAckFrame();
        assertArrayEquals(hpiFrame(0x40, new byte[] {0x17, 0x00}), ack);
        assertTrue(InterphoneProbe.containsAnalogCodecWriteAck(ack));
        assertFalse(InterphoneProbe.containsAnalogCodecWriteAck(
                hpiFrame(0x40, new byte[] {0x17, 0x01})));
        assertFalse(InterphoneProbe.containsAnalogCodecWriteAck(new byte[] {
                (byte) 0x84, (byte) 0xa9, 0x61, 0x00, 0x08, 0x03,
                (byte) 0x84, (byte) 0xa9, 0x61, 0x00, 0x02, 0x40, 0x17, 0x00
        }));
        assertFalse(InterphoneProbe.containsAnalogCodecWriteAck(new byte[] {
                (byte) 0x84, (byte) 0xa9, 0x61, 0x00, 0x03, 0x03, 1, 2, 3,
                0x7f, (byte) 0x84, (byte) 0xa9, 0x61, 0x00, 0x02, 0x40, 0x17, 0
        }));
    }

    @Test
    public void analogCodecGainUsesLivePresetAndRejectsInvalidSnapshots() {
        byte[] table = {0, 10, 20};
        assertEquals(0, InterphoneProbe.selectAnalogCodecGain(0, 0, table));
        assertEquals(10, InterphoneProbe.selectAnalogCodecGain(7, 1, table));
        assertEquals(20, InterphoneProbe.selectAnalogCodecGain(29, 2, table));
        try {
            InterphoneProbe.selectAnalogCodecGain(30, 1, table);
            fail("Out-of-range active profile must fail");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("profile"));
        }
        try {
            InterphoneProbe.selectAnalogCodecGain(0, 3, table);
            fail("Out-of-range mic preset must fail");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("preset"));
        }
        try {
            InterphoneProbe.selectAnalogCodecGain(0, 1, new byte[] {0, 10});
            fail("Truncated gain table must fail");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("3 bytes"));
        }
    }

    @Test
    public void independentPaOffWatchdogUsesArmedHandshakeWithoutBackgroundShell() {
        String command = InterphoneProbe.createIndependentPaOffWatchdogCommand();
        int marker = command.indexOf("echo H13_PA_OFF_WATCHDOG_ARMED");
        int sleep = command.indexOf("sleep 4");
        assertTrue(command.contains("[ -w /sys/boptt/pa_enable ]"));
        assertTrue(command.contains("[ -w /sys/boptt/audio_switch ]"));
        assertTrue(marker >= 0 && sleep > marker);
        assertTrue(command.contains("echo 0 > /sys/boptt/pa_enable"));
        assertTrue(command.contains("echo 0 > /sys/boptt/audio_switch"));
        assertFalse(command.trim().endsWith("&"));
        assertFalse(command.contains(">/dev/null"));
    }

    @Test
    public void analogVoiceInRequiresMoreThan115200BaudAt8N1() {
        assertEquals(161250, InterphoneProbe.minimumAnalogVoiceInBaud8N1());
        assertFalse(InterphoneProbe.supportsAnalogVoiceInStreamingAtBaud(57600));
        assertFalse(InterphoneProbe.supportsAnalogVoiceInStreamingAtBaud(115200));
        assertTrue(InterphoneProbe.supportsAnalogVoiceInStreamingAtBaud(230400));
        try {
            InterphoneProbe.supportsAnalogVoiceInStreamingAtBaud(0);
            fail("Non-positive baud must be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("positive"));
        }
    }

    @Test
    public void noRfEncoderModesWaitBeforeFirstRawHpiControl() {
        assertEquals(250L, InterphoneProbe.noRfEncoderBridgeSettleMs(1));
        assertEquals(250L, InterphoneProbe.noRfEncoderBridgeSettleMs(2));
        assertEquals(250L, InterphoneProbe.noRfEncoderBridgeSettleMs(3));
        assertEquals(0L, InterphoneProbe.noRfEncoderBridgeSettleMs(0));
        assertEquals(0L, InterphoneProbe.noRfEncoderBridgeSettleMs(4));
    }

    @Test
    public void noRfEncoderPreBridgeRequiresStartupReconnectAndVersion() {
        byte[] startup = "\r\n+DMOSTARTUP:0\r\n"
                .getBytes(StandardCharsets.US_ASCII);
        String connect = "\r\n+DMOCONNECT:0\r\n";
        String version = "\r\n+DMOGETSOFTVERSION:0.3.66;V2.01.07I3\r\n";
        assertTrue(InterphoneProbe.noRfEncoderPreBridgeReady(
                startup, connect, version));
        assertFalse(InterphoneProbe.noRfEncoderPreBridgeReady(
                new byte[0], connect, version));
        assertFalse(InterphoneProbe.noRfEncoderPreBridgeReady(
                startup, "", version));
        assertFalse(InterphoneProbe.noRfEncoderPreBridgeReady(
                startup, connect, ""));
    }

    @Test
    public void mode0WakeKickRequiresCompletelyEmptyInitialResponse() {
        assertTrue(InterphoneProbe.shouldWakeKickMode0(new byte[0]));
        assertFalse(InterphoneProbe.shouldWakeKickMode0(
                "\r".getBytes(StandardCharsets.US_ASCII)));
        assertFalse(InterphoneProbe.shouldWakeKickMode0(
                "\r\n+DMOSTARTUP:0\r\n"
                        .getBytes(StandardCharsets.US_ASCII)));
        assertFalse(InterphoneProbe.shouldWakeKickMode0(null));
    }

    @Test
    public void analogVoiceInWorstCaseSuccessfulPathFitsThreeSecondBridge() {
        // 5 codec + 5 VoiceIn ACKs, one 1290-byte block on 230400 8N1, 250 ms credit
        // window + 2 teardown ACKs, with every ACK consuming its full 150 ms.
        assertEquals(2106L,
                InterphoneProbe.analogVoiceInSuccessfulPathWorstCaseMs());
        assertTrue(InterphoneProbe.analogVoiceInSuccessfulPathFitsBridgeWindow());
    }

    @Test
    public void analogSubaudioRejectsInvalidProfileValues() {
        try {
            InterphoneProbe.createAnalogSubaudioFrame(3, 0, 0, 0);
            fail("Unknown output tone type must be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("mode"));
        }
        try {
            InterphoneProbe.createAnalogSubaudioFrame(1, 0xff, 0, 0);
            fail("CTCSS index + 1 must not wrap");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("CTCSS"));
        }
        try {
            InterphoneProbe.createAnalogSubaudioFrame(2, 0, 0xff, 0);
            fail("DCS index + 1 must not wrap");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("DCS"));
        }
    }

    @Test
    public void analogVoiceInTxModelAllowsFirstBlockThenRequiresCredit() {
        InterphoneProbe.AnalogVoiceInTxModel model =
                new InterphoneProbe.AnalogVoiceInTxModel(2, 0, 11, 1);
        byte[] pcm = new byte[2560];
        byte[] credit = hpiFrame(0x20, new byte[] {0x01, 0x00});

        assertFalse(model.acceptControlResponse(
                InterphoneProbe.createAnalogVoiceInProcessModeFrame(),
                hpiFrame(0, new byte[] {0x1a, 0x00})));
        for (int index = 0; index < 5; index++) {
            assertTrue(acceptExpectedAnalogControl(model));
        }
        assertTrue(acceptExpectedAnalogControl(model));
        assertTrue(acceptExpectedAnalogControl(model));
        assertTrue(acceptExpectedAnalogControl(model));
        assertArrayEquals(InterphoneProbe.createAnalogSubaudioFrame(2, 0, 11, 1),
                model.expectedControlFrame());
        assertTrue(acceptExpectedAnalogControl(model));
        assertFalse(model.acceptDataCredit(credit));
        assertTrue(acceptExpectedAnalogControl(model));
        assertEquals(InterphoneProbe.AnalogVoiceInTxModel.Phase.FIRST_BLOCK_READY,
                model.phase());

        assertArrayEquals(InterphoneProbe.createAnalogVoiceInPcmFrame(pcm, 0),
                model.serializeNextBlock(pcm, 0));
        assertEquals(InterphoneProbe.AnalogVoiceInTxModel.Phase.WAITING_DATA_CREDIT,
                model.phase());
        try {
            model.serializeNextBlock(pcm, 1);
            fail("Second analogue PCM block must require credit");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("credit"));
        }
        assertTrue(model.acceptDataCredit(credit));
        assertFalse(model.acceptDataCredit(credit));
        model.serializeNextBlock(pcm, 1);
        assertEquals(2, model.serializedBlocks());
        assertTrue(model.requestStop());
        assertEquals(InterphoneProbe.AnalogVoiceInTxModel.Phase.STOP_CALL,
                model.phase());
        assertArrayEquals(InterphoneProbe.createAnalogStopCallFrame(),
                model.expectedControlFrame());
        assertTrue(acceptExpectedAnalogControl(model));
        assertEquals(InterphoneProbe.AnalogVoiceInTxModel.Phase.WORK_MODE_IDLE,
                model.phase());
        assertTrue(acceptExpectedAnalogControl(model));
        assertEquals(InterphoneProbe.AnalogVoiceInTxModel.Phase.STOPPED,
                model.phase());
        assertFalse(model.acceptDataCredit(credit));
        assertFalse(model.requestStop());
        model.recover();
        assertEquals(InterphoneProbe.AnalogVoiceInTxModel.Phase.CODEC_PAGE1_REG10,
                model.phase());
        assertEquals(0, model.serializedBlocks());
    }

    @Test
    public void analogVoiceInTxModelCreditsFiveBlocksAndFitsBoundedWindow() {
        InterphoneProbe.AnalogVoiceInTxModel model =
                new InterphoneProbe.AnalogVoiceInTxModel(0, 0, 0, 0);
        while (model.phase()
                != InterphoneProbe.AnalogVoiceInTxModel.Phase.FIRST_BLOCK_READY) {
            assertTrue(acceptExpectedAnalogControl(model));
        }
        byte[] pcm = new byte[5 * 1280];
        byte[] observedShortCredit = hpiFrame(0x03, new byte[] {0, 0});
        for (int blockIndex = 0; blockIndex < 5; blockIndex++) {
            assertEquals(1290,
                    model.serializeNextBlock(pcm, blockIndex).length);
            assertTrue(model.acceptDataCredit(observedShortCredit));
        }
        assertEquals(5, model.serializedBlocks());
        assertTrue(model.requestStop());
        assertTrue(InterphoneProbe.analogVoiceInFiveBlockPathFitsBridgeWindow());
        assertEquals(4626,
                InterphoneProbe.analogVoiceInSuccessfulPathWorstCaseMs(5, 300));
    }

    @Test
    public void analogVoiceInTxModelControlFailureRequiresRecovery() {
        InterphoneProbe.AnalogVoiceInTxModel model =
                new InterphoneProbe.AnalogVoiceInTxModel();
        byte[] expectedRequest = model.expectedControlFrame();
        assertFalse(model.acceptControlResponse(expectedRequest,
                hpiFrame(0, new byte[] {0x3e, 0x01})));
        assertEquals(InterphoneProbe.AnalogVoiceInTxModel.Phase.FAILED,
                model.phase());
        assertFalse(model.acceptControlResponse(
                InterphoneProbe.createAnalogVoiceInRouteFrame(),
                hpiFrame(0, new byte[] {0x3e, 0x00})));
        model.recover();
        assertTrue(acceptExpectedAnalogControl(model));
    }

    @Test
    public void analogVoiceInVendorExtensionRequiresExactRouteAck() {
        InterphoneProbe.AnalogVoiceInTxModel model =
                new InterphoneProbe.AnalogVoiceInTxModel();
        assertEquals(
                InterphoneProbe.AnalogVoiceInTxModel.Compatibility
                        .VENDOR_EXTENSION_UNCONFIRMED,
                model.compatibility());

        while (model.phase() != InterphoneProbe.AnalogVoiceInTxModel.Phase
                .VOICE_IN_ROUTE) {
            assertTrue(acceptExpectedAnalogControl(model));
        }

        byte[] route = model.expectedControlFrame();
        assertFalse(model.acceptControlResponse(route, hpiFrame(0,
                new byte[] {0x3e, 0x01})));
        assertEquals(InterphoneProbe.AnalogVoiceInTxModel.Phase.FAILED,
                model.phase());
        assertEquals(
                InterphoneProbe.AnalogVoiceInTxModel.Compatibility
                        .VENDOR_EXTENSION_UNCONFIRMED,
                model.compatibility());
    }

    @Test
    public void analogVoiceInRouteAckDoesNotClaimDataPlaneSupport() {
        InterphoneProbe.AnalogVoiceInTxModel model =
                new InterphoneProbe.AnalogVoiceInTxModel();
        while (model.phase() != InterphoneProbe.AnalogVoiceInTxModel.Phase
                .VOICE_IN_ROUTE) {
            assertTrue(acceptExpectedAnalogControl(model));
        }

        assertTrue(acceptExpectedAnalogControl(model));
        assertEquals(InterphoneProbe.AnalogVoiceInTxModel.Phase.PROCESS_MODE,
                model.phase());
        assertEquals(
                InterphoneProbe.AnalogVoiceInTxModel.Compatibility
                        .CONTROL_ACKED_DATA_PLANE_UNCONFIRMED,
                model.compatibility());

        model.recover();
        assertEquals(
                InterphoneProbe.AnalogVoiceInTxModel.Compatibility
                        .VENDOR_EXTENSION_UNCONFIRMED,
                model.compatibility());
    }

    private static boolean acceptExpectedAnalogControl(
            InterphoneProbe.AnalogVoiceInTxModel model) {
        byte[] request = model.expectedControlFrame();
        return model.acceptControlResponse(request,
                (request[5] & 0xff) == 0x40
                        ? InterphoneProbe.createAnalogCodecWriteAckFrame()
                        : InterphoneProbe.createAnalogControlAckFrame(request));
    }

    @Test
    public void powerCalibrationParserSelectsLowAndHighMidpointAnchors() {
        byte[] record = calibrationRecord();
        InterphoneProbe.PowerCalibrationAnchor[] high =
                InterphoneProbe.parsePowerCalibrationTable(record, true);
        InterphoneProbe.PowerCalibrationAnchor[] low =
                InterphoneProbe.parsePowerCalibrationTable(record, false);
        assertEquals(8, high.length);
        assertEquals(400_000_000L, high[0].frequencyHz);
        assertEquals(2000, high[0].dacCode);
        assertEquals(1500, low[0].dacCode);
        assertEquals(1500,
                InterphoneProbe.selectNearestPowerCode(low, 390_000_000L));
        assertEquals(1500,
                InterphoneProbe.selectNearestPowerCode(low, 405_000_000L));
        assertEquals(1501,
                InterphoneProbe.selectNearestPowerCode(low, 405_000_001L));
        assertEquals(1507,
                InterphoneProbe.selectNearestPowerCode(low, 480_000_000L));
    }

    @Test
    public void powerCalibrationParserRejectsInvalidSnapshotStructure() {
        byte[] record = calibrationRecord();
        record[0] = 0;
        try {
            InterphoneProbe.parsePowerCalibrationTable(record, false);
            fail("Bad magic must fail");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("magic"));
        }

        record = calibrationRecord();
        record[6] = 9;
        try {
            InterphoneProbe.parsePowerCalibrationTable(record, false);
            fail("Bad count must fail");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("count"));
        }

        record = calibrationRecord();
        putU32Le(record, 0x058 + 8, 399_000_000L);
        try {
            InterphoneProbe.parsePowerCalibrationTable(record, false);
            fail("Unsorted anchors must fail");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("increase"));
        }
    }

    @Test
    public void customAnalogPowerCodeIsBoundedByVerifiedFactoryLow() {
        assertEquals(0, InterphoneProbe.validateCustomAnalogPowerCode(0, 2030));
        assertEquals(1024,
                InterphoneProbe.validateCustomAnalogPowerCode(1024, 2030));
        assertEquals(2030,
                InterphoneProbe.validateCustomAnalogPowerCode(2030, 2030));

        for (int invalid : new int[] {-1, 2031, 4095}) {
            try {
                InterphoneProbe.validateCustomAnalogPowerCode(invalid, 2030);
                fail("Out-of-policy power code must fail: " + invalid);
            } catch (IllegalArgumentException expected) {
                assertTrue(expected.getMessage().contains("0 and factory low"));
            }
        }
        try {
            InterphoneProbe.validateCustomAnalogPowerCode(1000, 2029);
            fail("Unexpected factory-low baseline must fail");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("baseline"));
        }
    }

    @Test
    public void customAnalogPowerCodeUsesLittleEndianRuntimeBytes() {
        assertArrayEquals(new byte[] {0, 0},
                InterphoneProbe.powerCodeBytesLe(0));
        assertArrayEquals(new byte[] {(byte) 0xee, 0x07},
                InterphoneProbe.powerCodeBytesLe(2030));
        assertArrayEquals(new byte[] {(byte) 0xff, (byte) 0xff},
                InterphoneProbe.powerCodeBytesLe(0xffff));
        try {
            InterphoneProbe.powerCodeBytesLe(0x10000);
            fail("Out-of-u16 code must fail");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("u16"));
        }
    }

    @Test
    public void exactUploadVerifierRetriesShortReadThenPasses() throws Exception {
        byte[] expected = new byte[] {1, 2, 3, 4};
        int[] calls = {0};
        List<String> outcomes = new ArrayList<>();
        byte[] actual = InterphoneProbe.requireExactBytesWithRetry(expected, 3,
                attempt -> {
                    calls[0]++;
                    return attempt == 1 ? new byte[] {1, 2} : expected.clone();
                },
                (attempt, bytes, error, mismatch) -> outcomes.add(
                        attempt + ":" + (bytes == null ? -1 : bytes.length)
                                + ":" + mismatch));
        assertArrayEquals(expected, actual);
        assertEquals(2, calls[0]);
        assertEquals("1:2:2", outcomes.get(0));
        assertEquals("2:4:-1", outcomes.get(1));
    }

    @Test
    public void exactUploadVerifierRetriesFullMismatchAndReportsOffset()
            throws Exception {
        byte[] expected = new byte[] {10, 11, 12, 13};
        List<Integer> offsets = new ArrayList<>();
        byte[] actual = InterphoneProbe.requireExactBytesWithRetry(expected, 2,
                attempt -> attempt == 1
                        ? new byte[] {10, 11, 99, 13} : expected.clone(),
                (attempt, bytes, error, mismatch) -> offsets.add(mismatch));
        assertArrayEquals(expected, actual);
        assertEquals(Integer.valueOf(2), offsets.get(0));
        assertEquals(Integer.valueOf(-1), offsets.get(1));
    }

    @Test
    public void exactUploadVerifierRetriesReadErrorThenPasses() throws Exception {
        byte[] expected = new byte[] {7, 8};
        List<String> errors = new ArrayList<>();
        byte[] actual = InterphoneProbe.requireExactBytesWithRetry(expected, 2,
                attempt -> {
                    if (attempt == 1) {
                        throw new IOException("short/invalid memread");
                    }
                    return expected.clone();
                },
                (attempt, bytes, error, mismatch) -> errors.add(
                        error == null ? "ok" : error.getMessage()));
        assertArrayEquals(expected, actual);
        assertEquals("short/invalid memread", errors.get(0));
        assertEquals("ok", errors.get(1));
    }

    @Test
    public void exactUploadVerifierNeverReturnsPersistentMismatch() throws Exception {
        byte[] expected = new byte[] {1, 2, 3};
        int[] calls = {0};
        try {
            InterphoneProbe.requireExactBytesWithRetry(expected, 3,
                    attempt -> {
                        calls[0]++;
                        return new byte[] {1, 9, 3};
                    },
                    (attempt, bytes, error, mismatch) -> { });
            fail("persistent mismatch must fail");
        } catch (IOException expectedFailure) {
            assertTrue(expectedFailure.getMessage().contains("3 attempts"));
            assertTrue(expectedFailure.getMessage().contains("0x01"));
        }
        assertEquals(3, calls[0]);
    }

    @Test
    public void mode0VocoderLoopbackControlFramesMatchVendorTool() throws Exception {
        assertPrivateFrame("HPI_VOCODER_IO_VOICE_IN_HPI", new byte[] {
                (byte) 0x84, (byte) 0xa9, 0x61, 0x00, 0x02,
                0x00, 0x3e, (byte) 0x80
        });
        assertPrivateFrame("HPI_VOCODER_IO_PCM_TO_AMBE_HPI", new byte[] {
                (byte) 0x84, (byte) 0xa9, 0x61, 0x00, 0x02,
                0x00, 0x3e, (byte) 0x90
        });
        assertPrivateFrame("HPI_VOCODER_IO_VOICE_OUT_HPI", new byte[] {
                (byte) 0x84, (byte) 0xa9, 0x61, 0x00, 0x02,
                0x00, 0x3e, 0x01
        });
        assertPrivateFrame("HPI_VOCODER_IO_LOCAL_CHAN_D_TO_VOICE_OUT", new byte[] {
                (byte) 0x84, (byte) 0xa9, 0x61, 0x00, 0x02,
                0x00, 0x3e, 0x09
        });
        // Vendor Voice OUT both = bits 1:0 value 2 (0x02), not 0x03.
        assertPrivateFrame("HPI_VOCODER_IO_VOICE_OUT_BOTH", new byte[] {
                (byte) 0x84, (byte) 0xa9, 0x61, 0x00, 0x02,
                0x00, 0x3e, 0x02
        });
        assertPrivateFrame("HPI_PROCESS_MODE_2", new byte[] {
                (byte) 0x84, (byte) 0xa9, 0x61, 0x00, 0x02,
                0x00, 0x1a, 0x02
        });
        assertPrivateFrame("HPI_PROCESS_MODE_3", new byte[] {
                (byte) 0x84, (byte) 0xa9, 0x61, 0x00, 0x02,
                0x00, 0x1a, 0x03
        });
        assertPrivateFrame("HPI_WORK_MODE_VOCODER_LOOPBACK_FULL_DUPLEX", new byte[] {
                (byte) 0x84, (byte) 0xa9, 0x61, 0x00, 0x04,
                0x00, 0x18, 0x03, 0x03, 0x00
        });
        assertPrivateFrame("HPI_WORK_MODE_VOCODER_LOOPBACK_SIMPLEX", new byte[] {
                (byte) 0x84, (byte) 0xa9, 0x61, 0x00, 0x04,
                0x00, 0x18, 0x02, 0x03, 0x00
        });
        assertPrivateFrame("HPI_PROCESS_MODE_ACK", new byte[] {
                (byte) 0x84, (byte) 0xa9, 0x61, 0x00, 0x02,
                0x00, 0x1a, 0x00
        });
        assertPrivateFrame("HPI_WORK_MODE_ACK", new byte[] {
                (byte) 0x84, (byte) 0xa9, 0x61, 0x00, 0x02,
                0x00, 0x18, 0x00
        });
    }

    @Test
    public void singleZeroPcmFrameMatchesVendorTransport() {
        byte[] frame = InterphoneProbe.createAnalogVoiceInPcmFrame(
                new byte[1280], 0);
        assertEquals(1290, frame.length);
        assertArrayEquals(new byte[] {
                (byte) 0x84, (byte) 0xa9, 0x61, 0x05, 0x03,
                0x03, 0x00, 0x02, (byte) 0x80
        }, java.util.Arrays.copyOf(frame, 9));
        for (int index = 9; index < 1289; index++) {
            assertEquals(0, frame[index]);
        }
        assertEquals(0, frame[1289]);
        assertEquals(0x05, frame[3] & 0xff);
        assertEquals(0x03, frame[4] & 0xff);
    }

    @Test
    public void oneZeroPcmModeRequiresOnlyChannelMutationPermission() {
        String mode = "vocoder_encoder_one_zero_pcm_230400_no_rf";
        assertNull(MainActivity.validateLaunch(mode, false, true, -1));
        assertTrue(MainActivity.validateLaunch(mode, false, false, -1)
                .contains("allow_channel_mutation"));
        assertTrue(MainActivity.validateLaunch(mode, true, true, -1)
                .contains("禁止allow_potential_rf"));
        assertTrue(MainActivity.validateLaunch(mode, false, true, 0)
                .contains("禁止power_code"));
        assertFalse(MainActivity.isPotentialRfMode(mode));
    }

    @Test
    public void recognizesOnlyCompleteShortPcmBackpressureFrames() throws Exception {
        Method method = InterphoneProbe.class.getDeclaredMethod(
                "isShortPcmBackpressureResponse", byte[].class);
        method.setAccessible(true);
        assertTrue((Boolean) method.invoke(null, (Object) new byte[] {
                (byte) 0x84, (byte) 0xa9, 0x61, 0x00, 0x02,
                0x03, 0x00, 0x00
        }));
        assertTrue((Boolean) method.invoke(null, (Object) new byte[] {
                (byte) 0x84, (byte) 0xa9, 0x61, 0x00, 0x02,
                0x02, 0x00, 0x00
        }));
        assertTrue((Boolean) method.invoke(null, (Object) new byte[] {
                (byte) 0x84, (byte) 0xa9, 0x61, 0x00, 0x02,
                0x05, 0x00, 0x00
        }));
        assertFalse((Boolean) method.invoke(null, (Object) new byte[] {
                (byte) 0x84, (byte) 0xa9, 0x61, 0x00, 0x02,
                0x03, 0x00
        }));
        assertFalse((Boolean) method.invoke(null, (Object) new byte[] {
                (byte) 0x84, (byte) 0xa9, 0x61, 0x00, 0x02,
                0x30, 0x1b, 0x0c
        }));
    }

    @Test
    public void acceptsBothMode0ReloadResponseForms() throws Exception {
        Method method = InterphoneProbe.class.getDeclaredMethod(
                "mode0ReloadAccepted", byte[].class);
        method.setAccessible(true);
        assertTrue((Boolean) method.invoke(null, (Object)
                "+DMOSTARTUP:0".getBytes(StandardCharsets.US_ASCII)));
        assertTrue((Boolean) method.invoke(null, (Object)
                "sct3258 change protocol cmp".getBytes(StandardCharsets.US_ASCII)));
        assertFalse((Boolean) method.invoke(null, (Object)
                "unknown".getBytes(StandardCharsets.US_ASCII)));
    }

    @Test
    public void finallyControlReloadOnlyWhenNotAlreadyDone() {
        assertTrue(InterphoneProbe.shouldAttemptFinallyControlReload(false));
        assertFalse(InterphoneProbe.shouldAttemptFinallyControlReload(true));
    }

    @Test
    public void plausibleHpiHeaderRejectsBareSyncInPayload() {
        // Accidental 84 A9 61 with garbage length/type must not count.
        byte[] junk = new byte[] {
                (byte) 0x84, (byte) 0xa9, 0x61, (byte) 0xff, (byte) 0xff,
                0x55, 0x00, 0x00, 0x00
        };
        assertFalse(InterphoneProbe.isPlausibleHpiHeader(junk, 0));
    }

    @Test
    public void plausibleHpiHeaderAcceptsSpeech323Count160() {
        byte[] speech = new byte[] {
                (byte) 0x84, (byte) 0xa9, 0x61, 0x01, 0x43, // len 323
                0x30, 0x00, 0x00, (byte) 0xa0 // type30 field0 count160
        };
        assertTrue(InterphoneProbe.isPlausibleHpiHeader(speech, 0));
    }

    @Test
    public void recoverProductPcmPadsWire328To160Samples() {
        // One SPEECH header + 318 sample bytes + pad 0x00 + next SPEECH header.
        byte[] raw = new byte[328 + 9];
        raw[0] = (byte) 0x84;
        raw[1] = (byte) 0xa9;
        raw[2] = 0x61;
        raw[3] = 0x01;
        raw[4] = 0x43;
        raw[5] = 0x30;
        raw[6] = 0x00;
        raw[7] = 0x00;
        raw[8] = (byte) 0xa0;
        // 318 sample bytes of 0x01,0x00 pattern then pad 0x00 before next header
        for (int i = 0; i < 318; i++) {
            raw[9 + i] = (byte) ((i & 1) == 0 ? 0x01 : 0x00);
        }
        raw[9 + 318] = 0x00; // pad to 328
        // next header at 328
        raw[328] = (byte) 0x84;
        raw[329] = (byte) 0xa9;
        raw[330] = 0x61;
        raw[331] = 0x01;
        raw[332] = 0x43;
        raw[333] = 0x30;
        raw[334] = 0x00;
        raw[335] = 0x00;
        raw[336] = (byte) 0xa0;

        InterphoneProbe.ProductPcmRecovery rec = InterphoneProbe.recoverProductPcm(raw);
        assertEquals(1, rec.speechFrames);
        assertEquals(1, rec.wire328Frames);
        assertEquals(1, rec.gapFrames);
        assertEquals(1, rec.missingSamplesTotal);
        assertEquals(320, rec.productPcm.length);
        assertEquals(318, rec.availablePcm.length);
        // last product sample is zero pad
        assertEquals(0, rec.productPcm[318]);
        assertEquals(0, rec.productPcm[319]);
    }

    @Test
    public void resolveWireLengthFollowsPlausibleEarlySpeechHeader() throws Exception {
        // Two SPEECH headers 328 apart; LENGTH implies 330.
        byte[] raw = new byte[700];
        // Frame 0
        raw[0] = (byte) 0x84;
        raw[1] = (byte) 0xa9;
        raw[2] = 0x61;
        raw[3] = 0x01;
        raw[4] = 0x43;
        raw[5] = 0x30;
        raw[6] = 0x00;
        raw[7] = 0x00;
        raw[8] = (byte) 0xa0;
        // Next SPEECH at +328
        int next = 328;
        raw[next] = (byte) 0x84;
        raw[next + 1] = (byte) 0xa9;
        raw[next + 2] = 0x61;
        raw[next + 3] = 0x01;
        raw[next + 4] = 0x43;
        raw[next + 5] = 0x30;
        raw[next + 6] = 0x00;
        raw[next + 7] = 0x00;
        raw[next + 8] = (byte) 0xa0;
        Method method = InterphoneProbe.class.getDeclaredMethod(
                "resolveHpiFrameWireLength",
                byte[].class, int.class, int.class, int.class);
        method.setAccessible(true);
        int paper = 6 + 323;
        if ((paper & 1) != 0) {
            paper++;
        }
        int wire = (Integer) method.invoke(null, raw, 0, 323, paper);
        assertEquals(328, wire);
    }

    @Test
    public void p0ReportKeysDoNotUseMisleadingTrafficPlaneLabel() throws Exception {
        // Guard against regressing the v0.86 false "Traffic-plane baseline restored"
        // label that only proved CONNECT/version/CH1.
        java.nio.file.Path source = java.nio.file.Paths.get(
                "src/main/java/net/elfradio/h13interphoneprobe/InterphoneProbe.java");
        if (!java.nio.file.Files.exists(source)) {
            source = java.nio.file.Paths.get(
                    "app/src/main/java/net/elfradio/h13interphoneprobe/InterphoneProbe.java");
        }
        if (!java.nio.file.Files.exists(source)) {
            // Working directory may be module root or app root during Gradle tests.
            source = java.nio.file.Paths.get(
                    System.getProperty("user.dir"),
                    "src/main/java/net/elfradio/h13interphoneprobe/InterphoneProbe.java");
        }
        String text = new String(java.nio.file.Files.readAllBytes(source),
                StandardCharsets.UTF_8);
        assertFalse("must not reintroduce misleading traffic-plane restored label",
                text.contains("Traffic-plane baseline restored"));
        assertTrue(text.contains("controlPlaneRestored"));
        assertTrue(text.contains("finallyReloadSkipped"));
        assertTrue(text.contains("rxTrafficPlaneHealthy"));
    }

    @Test
    public void countsDistinctStatusZeroControlResponses() throws Exception {
        Method method = InterphoneProbe.class.getDeclaredMethod(
                "countOccurrences", byte[].class, byte[].class);
        method.setAccessible(true);
        byte[] ack = new byte[] {
                (byte) 0x84, (byte) 0xa9, 0x61, 0x00, 0x02,
                0x00, 0x3e, 0x00
        };
        byte[] raw = new byte[ack.length * 2 + 1];
        System.arraycopy(ack, 0, raw, 0, ack.length);
        raw[ack.length] = 0x55;
        System.arraycopy(ack, 0, raw, ack.length + 1, ack.length);
        assertEquals(2, method.invoke(null, raw, ack));
    }

    private static void assertPrivateFrame(String name, byte[] expected) throws Exception {
        Field field = InterphoneProbe.class.getDeclaredField(name);
        field.setAccessible(true);
        assertArrayEquals(expected, (byte[]) field.get(null));
    }

    @Test
    public void vocoderIoOffFrameMatchesVendorTool() throws Exception {
        Field field = InterphoneProbe.class.getDeclaredField("HPI_VOCODER_IO_OFF");
        field.setAccessible(true);
        assertArrayEquals(new byte[] {
                (byte) 0x84, (byte) 0xa9, 0x61, 0x00, 0x02,
                0x00, 0x3e, 0x00
        }, (byte[]) field.get(null));
    }

    @Test
    public void fixedReadMemTemplateMatchesVendorXml() throws Exception {
        Field field = InterphoneProbe.class.getDeclaredField("HPI_READ_MEM_FIXED");
        field.setAccessible(true);
        assertArrayEquals(new byte[] {
                (byte) 0x84, (byte) 0xa9, 0x61, 0x00, 0x03,
                0x20, 0x3f, 0x53, 0x1e
        }, (byte[]) field.get(null));

        Field window = InterphoneProbe.class.getDeclaredField(
                "SCT_READ_MEM_BRIDGE_MS");
        window.setAccessible(true);
        assertEquals(12000L, window.getLong(null));
    }

    @Test
    public void mode0FlashDumpUsesBounded57600Bridge() throws Exception {
        Field baud = InterphoneProbe.class.getDeclaredField("BAUD_RATE");
        baud.setAccessible(true);
        Field window = InterphoneProbe.class.getDeclaredField(
                "SCT_FLASH_DUMP_BRIDGE_MS");
        window.setAccessible(true);
        assertEquals(57600, baud.getInt(null));
        assertEquals(600000L, window.getLong(null));
    }

    @Test
    public void vendorFlashBridgeUsesFirmwareCommandAndBaudRate() throws Exception {
        Field command = InterphoneProbe.class.getDeclaredField("SCT_DSP_UPDATE_COMMAND");
        command.setAccessible(true);
        Field baud = InterphoneProbe.class.getDeclaredField("HPI_BRIDGE_BAUD_RATE");
        baud.setAccessible(true);
        assertEquals("sct3258dspupdate", command.get(null));
        assertEquals(115200, baud.getInt(null));
    }

    @Test
    public void buildsVendorFlashWireFramesWithPreambleAndPadding()
            throws Exception {
        Method method = InterphoneProbe.class.getDeclaredMethod(
                "createPaddedHpiFrame", int.class, byte[].class);
        method.setAccessible(true);
        assertArrayEquals(new byte[] {
                (byte) 0x84, (byte) 0xa9, 0x61, 0x00, 0x01, 0x23,
                (byte) 0x92, 0x00
        }, (byte[]) method.invoke(null, 0x23,
                new byte[] {(byte) 0x92}));
        assertArrayEquals(new byte[] {
                (byte) 0x84, (byte) 0xa9, 0x61, 0x00, 0x05, 0x23,
                (byte) 0x94, 0x03, (byte) 0xf0, 0x00, 0x40, 0x00
        }, (byte[]) method.invoke(null, 0x23,
                new byte[] {(byte) 0x94, 0x03, (byte) 0xf0, 0x00, 0x40}));
    }

    @Test
    public void probesOfficialTemplateAddressBeforeFlashZero() throws Exception {
        Field field = InterphoneProbe.class.getDeclaredField(
                "SCT_FLASH_PROOF_ADDRESSES");
        field.setAccessible(true);
        assertArrayEquals(new int[] {
                0x03f000, 0x03f000, 0x000000, 0x000000
        }, (int[]) field.get(null));
    }

    @Test
    public void vendorStateAdapterMatchesAuditedBinary() throws Exception {
        Field field = InterphoneProbe.class.getDeclaredField(
                "HPI_LOADER_VENDOR_STATE_SYSTICK_WORDS");
        field.setAccessible(true);
        int[] words = (int[]) field.get(null);
        Method wordsToBytes = InterphoneProbe.class.getDeclaredMethod(
                "wordsToBytes", int[].class);
        wordsToBytes.setAccessible(true);
        byte[] actual = (byte[]) wordsToBytes.invoke(null, (Object) words);
        Method sha256 = InterphoneProbe.class.getDeclaredMethod(
                "sha256", byte[].class);
        sha256.setAccessible(true);
        assertEquals(424, actual.length);
        assertEquals("6d9661f00cc94b51b4c8f13268a519d5059968101eaa09d1127524b337e1de57",
                sha256.invoke(null, (Object) actual));
        assertArrayEquals(new byte[] {
                0x70, (byte) 0xb5, 0x53, 0x48,
                0x46, 0x41, 0x49, 0x4c
        }, new byte[] {
                actual[0], actual[1], actual[2], actual[3],
                actual[420], actual[421], actual[422], actual[423]
        });
    }

    @Test
    public void hpiLoaderAckAdapterMatchesAuditedBinary() throws Exception {
        Field field = InterphoneProbe.class.getDeclaredField(
                "HPI_LOADER_ACK_SYSTICK_WORDS");
        field.setAccessible(true);
        int[] words = (int[]) field.get(null);
        Method wordsToBytes = InterphoneProbe.class.getDeclaredMethod(
                "wordsToBytes", int[].class);
        wordsToBytes.setAccessible(true);
        byte[] actual = (byte[]) wordsToBytes.invoke(null, (Object) words);
        Method sha256 = InterphoneProbe.class.getDeclaredMethod(
                "sha256", byte[].class);
        sha256.setAccessible(true);
        assertEquals(384, actual.length);
        assertEquals("a5fb1f63297699024ec3fb287188fe6b26884d3707413b585cdd0f169916fc1d",
                sha256.invoke(null, (Object) actual));
    }

    @Test
    public void recognizesObservedHpiLoaderReadyAck() throws Exception {
        Field field = InterphoneProbe.class.getDeclaredField(
                "SCT_HPI_LOADER_READY_ACK");
        field.setAccessible(true);
        byte[] expected = (byte[]) field.get(null);
        assertArrayEquals(new byte[] {
                (byte) 0x84, (byte) 0xa9, 0x61, 0x00,
                0x02, 0x00, 0x17, 0x00
        }, expected);
    }

    @Test
    public void hpiLoader57600AdapterMatchesAuditedBinary() throws Exception {
        Field field = InterphoneProbe.class.getDeclaredField(
                "HPI_LOADER_ACK_57600_SYSTICK_WORDS");
        field.setAccessible(true);
        int[] words = (int[]) field.get(null);
        Method wordsToBytes = InterphoneProbe.class.getDeclaredMethod(
                "wordsToBytes", int[].class);
        wordsToBytes.setAccessible(true);
        byte[] actual = (byte[]) wordsToBytes.invoke(null, (Object) words);
        Method sha256 = InterphoneProbe.class.getDeclaredMethod(
                "sha256", byte[].class);
        sha256.setAccessible(true);
        assertEquals(372, actual.length);
        assertEquals("d466914286a5c41a67b14823a27f99c9874f606690314476d148170f6f62c42f",
                sha256.invoke(null, (Object) actual));
    }

    @Test
    public void systemDpmrLoaderAdapterMatchesAuditedBinary() throws Exception {
        Field field = InterphoneProbe.class.getDeclaredField(
                "SYSTEM_DPMR_LOADER_SYSTICK_WORDS");
        field.setAccessible(true);
        int[] words = (int[]) field.get(null);
        Method wordsToBytes = InterphoneProbe.class.getDeclaredMethod(
                "wordsToBytes", int[].class);
        wordsToBytes.setAccessible(true);
        byte[] actual = (byte[]) wordsToBytes.invoke(null, (Object) words);
        Method sha256 = InterphoneProbe.class.getDeclaredMethod(
                "sha256", byte[].class);
        sha256.setAccessible(true);
        assertEquals(416, actual.length);
        assertEquals("c08d0341eedf3d84d88f3d50ca6991355a75b19b3dd9ea8dbb8553f662519610",
                sha256.invoke(null, (Object) actual));

        Field window = InterphoneProbe.class.getDeclaredField(
                "SYSTEM_DPMR_LOADER_WINDOW_LENGTH");
        window.setAccessible(true);
        Field phase = InterphoneProbe.class.getDeclaredField(
                "SYSTEM_DPMR_LOADER_PHASE_ADDRESS");
        phase.setAccessible(true);
        assertEquals(0x290, window.getInt(null));
        assertTrue(0x20001d00 + actual.length <= phase.getInt(null));
    }

    @Test
    public void systemDpmrVendorReadyAdapterMatchesAuditedBinary() throws Exception {
        Field field = InterphoneProbe.class.getDeclaredField(
                "SYSTEM_DPMR_VENDOR_READY_SYSTICK_WORDS");
        field.setAccessible(true);
        int[] words = (int[]) field.get(null);
        Method wordsToBytes = InterphoneProbe.class.getDeclaredMethod(
                "wordsToBytes", int[].class);
        wordsToBytes.setAccessible(true);
        byte[] actual = (byte[]) wordsToBytes.invoke(null, (Object) words);
        Method sha256 = InterphoneProbe.class.getDeclaredMethod(
                "sha256", byte[].class);
        sha256.setAccessible(true);
        assertEquals(492, actual.length);
        assertEquals("65c7e7eb8598628a682c94a20934acb42cc4d46dc8673c10e3570ae8ab1e0d27",
                sha256.invoke(null, (Object) actual));

        Field window = InterphoneProbe.class.getDeclaredField(
                "SYSTEM_DPMR_VENDOR_READY_WINDOW_LENGTH");
        window.setAccessible(true);
        Field phase = InterphoneProbe.class.getDeclaredField(
                "SYSTEM_DPMR_LOADER_PHASE_ADDRESS");
        phase.setAccessible(true);
        assertEquals(0x290, window.getInt(null));
        assertTrue(0x20001d00 + actual.length <= phase.getInt(null));
    }

    @Test
    public void recognizesDocumentedApplicationReadyAck() throws Exception {
        Field field = InterphoneProbe.class.getDeclaredField(
                "SCT_APPLICATION_READY_ACK");
        field.setAccessible(true);
        assertArrayEquals(new byte[] {
                (byte) 0x84, (byte) 0xa9, 0x61, 0x00,
                0x02, 0x00, 0x17, 0x01
        }, (byte[]) field.get(null));
    }

    @Test
    public void parsesCompleteMemreadPayload() throws Exception {
        assertArrayEquals(new byte[] {0x01, 0x23, (byte) 0xab, (byte) 0xff},
                parse("memread 0x20001c00 0x4\r\n01 23 ab ff \r\n", 4));
    }

    @Test
    public void findsEchoAfterAsynchronousPrefix() throws Exception {
        assertArrayEquals(new byte[] {(byte) 0xd4, (byte) 0xc3, (byte) 0xb2, (byte) 0xa1},
                parse("DSP event\r\nmemread 0x20001c00 0x4\r\nd4 c3 b2 a1", 4));
    }

    @Test
    public void rejectsTruncatedPayload() throws Exception {
        assertEquals(0, parse("memread 0x20001c00 0x4\r\n01 23 ab", 4).length);
    }

    @Test
    public void tapeMetadataIgnoresOnlyCountByte() throws Exception {
        byte[] first = new byte[44];
        byte[] second = new byte[44];
        first[0x28] = 3;
        second[0x28] = 21;
        assertTrue(sameTapeMetadata(first, second));
        second[0x12] = 1;
        assertFalse(sameTapeMetadata(first, second));
    }

    @Test
    public void acceptsObservedDmrChanDShape() throws Exception {
        byte[] data = new byte[27];
        data[0] = 1;
        data[8] = 0x0a;
        data[17] = 0x03;
        data[26] = 0x0f;
        assertTrue(looksLikeDmrChanD(data));
    }

    @Test
    public void rejectsNonzeroHighNibbleInDmrFrameTail() throws Exception {
        byte[] data = new byte[9];
        data[0] = 1;
        data[8] = 0x10;
        assertFalse(looksLikeDmrChanD(data));
    }

    @Test
    public void rejectsClearedResidualBuffer() throws Exception {
        assertFalse(looksLikeDmrChanD(new byte[27]));
    }

    @Test
    public void pendSvUploadMatchesOfflineAssembly() throws Exception {
        Field field = InterphoneProbe.class.getDeclaredField("RAM_PENDSV_PROOF_WORDS");
        field.setAccessible(true);
        int[] words = (int[]) field.get(null);
        Method method = InterphoneProbe.class.getDeclaredMethod("wordsToBytes", int[].class);
        method.setAccessible(true);
        byte[] actual = (byte[]) method.invoke(null, (Object) words);
        assertArrayEquals(new byte[] {
                0x03, 0x48, 0x04, 0x49, 0x01, 0x60, 0x04, 0x48,
                0x04, 0x49, 0x01, 0x60, 0x70, 0x47, 0x00, (byte) 0xbf,
                0x38, 0x00, 0x00, 0x20, (byte) 0x87, 0x33, 0x01, 0x08,
                0x40, 0x1d, 0x00, 0x20, 0x50, 0x4e, 0x44, 0x31
        }, actual);
    }

    @Test
    public void findsStartupTokenAcrossCapturedBytes() throws Exception {
        Method method = InterphoneProbe.class.getDeclaredMethod(
                "containsBytes", byte[].class, byte[].class);
        method.setAccessible(true);
        assertTrue((Boolean) method.invoke(null,
                "echo\r\n+DMOSTARTUP:0\r\n".getBytes(StandardCharsets.US_ASCII),
                "+DMOSTARTUP:0".getBytes(StandardCharsets.US_ASCII)));
        assertFalse((Boolean) method.invoke(null,
                "echo only\r\n".getBytes(StandardCharsets.US_ASCII),
                "+DMOSTARTUP:0".getBytes(StandardCharsets.US_ASCII)));
    }

    @Test
    public void hpiLoaderUploadMatchesOfflineAssembly() throws Exception {
        Field field = InterphoneProbe.class.getDeclaredField("HPI_LOADER_WORDS");
        field.setAccessible(true);
        int[] words = (int[]) field.get(null);
        Method method = InterphoneProbe.class.getDeclaredMethod("wordsToBytes", int[].class);
        method.setAccessible(true);
        byte[] actual = (byte[]) method.invoke(null, (Object) words);
        assertArrayEquals(new byte[] {
                0x10, (byte) 0xb5, 0x0c, 0x48, 0x0c, 0x49, 0x01, 0x60,
                0x0c, 0x4c, 0x0d, 0x49, 0x21, 0x60, 0x0e, 0x4b,
                (byte) 0x98, 0x47, 0x0f, 0x48, 0x0f, 0x49, 0x0d, 0x4b,
                (byte) 0x98, 0x47, 0x05, (byte) 0xa0, 0x02, 0x21, 0x0b, 0x4b,
                (byte) 0x98, 0x47, 0x0d, 0x48, 0x0d, 0x49, 0x09, 0x4b,
                (byte) 0x98, 0x47, 0x06, 0x49, 0x21, 0x60, 0x10, (byte) 0xbd,
                (byte) 0xf2, (byte) 0xcb, (byte) 0xc0, 0x46,
                0x38, 0x00, 0x00, 0x20, (byte) 0x87, 0x33, 0x01, 0x08,
                (byte) 0x80, 0x1d, 0x00, 0x20, 0x48, 0x50, 0x49, 0x52,
                0x48, 0x50, 0x49, 0x44, (byte) 0x91, 0x02, 0x01, 0x08,
                0x61, 0x05, 0x01, 0x08, (byte) 0x8a, (byte) 0xcb, 0x02, 0x08,
                0x5c, 0x04, 0x00, 0x00, (byte) 0xe8, (byte) 0xcf, 0x02, 0x08,
                (byte) 0xa2, 0x03, 0x00, 0x00
        }, actual);
    }

    @Test
    public void hpiLoaderSysTickUploadMatchesDerivedBinary() throws Exception {
        Field field = InterphoneProbe.class.getDeclaredField(
                "HPI_LOADER_SYSTICK_WORDS");
        field.setAccessible(true);
        int[] words = (int[]) field.get(null);
        Method method = InterphoneProbe.class.getDeclaredMethod(
                "wordsToBytes", int[].class);
        method.setAccessible(true);
        byte[] actual = (byte[]) method.invoke(null, (Object) words);
        assertArrayEquals(new byte[] {
                0x10, (byte) 0xb5, 0x0c, 0x48, 0x0c, 0x49, 0x01, 0x60,
                0x0c, 0x4c, 0x0d, 0x49, 0x21, 0x60, 0x0e, 0x4b,
                (byte) 0x98, 0x47, 0x0f, 0x48, 0x0f, 0x49, 0x0d, 0x4b,
                (byte) 0x98, 0x47, 0x05, (byte) 0xa0, 0x02, 0x21, 0x0b, 0x4b,
                (byte) 0x98, 0x47, 0x0d, 0x48, 0x0d, 0x49, 0x09, 0x4b,
                (byte) 0x98, 0x47, 0x06, 0x49, 0x21, 0x60, 0x10, (byte) 0xbd,
                (byte) 0xf2, (byte) 0xcb, (byte) 0xc0, 0x46,
                0x3c, 0x00, 0x00, 0x20, (byte) 0xdd, 0x1d, 0x02, 0x08,
                (byte) 0x80, 0x1d, 0x00, 0x20, 0x48, 0x50, 0x49, 0x52,
                0x48, 0x50, 0x49, 0x44, (byte) 0x91, 0x02, 0x01, 0x08,
                0x61, 0x05, 0x01, 0x08, (byte) 0x8a, (byte) 0xcb, 0x02, 0x08,
                0x5c, 0x04, 0x00, 0x00, (byte) 0xe8, (byte) 0xcf, 0x02, 0x08,
                (byte) 0xa2, 0x03, 0x00, 0x00
        }, actual);
    }

    @Test
    public void hpiLoaderStepSysTickUploadMatchesAuditedBinary() throws Exception {
        Field field = InterphoneProbe.class.getDeclaredField(
                "HPI_LOADER_STEP_SYSTICK_WORDS");
        field.setAccessible(true);
        int[] words = (int[]) field.get(null);
        Method wordsToBytes = InterphoneProbe.class.getDeclaredMethod(
                "wordsToBytes", int[].class);
        wordsToBytes.setAccessible(true);
        byte[] actual = (byte[]) wordsToBytes.invoke(null, (Object) words);
        Method sha256 = InterphoneProbe.class.getDeclaredMethod(
                "sha256", byte[].class);
        sha256.setAccessible(true);
        assertEquals(360, actual.length);
        assertEquals("af2b7ac089b34c57c5e4b6e18b7fdf8861e2c2c19949f3e64c638772edeec81f",
                sha256.invoke(null, (Object) actual));
        assertArrayEquals(new byte[] {
                0x70, (byte) 0xb5, 0x48, 0x48,
                0x48, 0x50, 0x49, 0x44
        }, new byte[] {
                actual[0], actual[1], actual[2], actual[3],
                actual[356], actual[357], actual[358], actual[359]
        });
    }

    @Test
    public void formatsBoundedSctSend2SegmentWithoutReordering() throws Exception {
        Method method = InterphoneProbe.class.getDeclaredMethod(
                "formatSctSend2Command", byte[].class, int.class, int.class);
        method.setAccessible(true);
        byte[] value = new byte[40];
        for (int index = 0; index < value.length; index++) {
            value[index] = (byte) index;
        }
        assertEquals("sct3258send2 0405060708090a0b0c0d0e0f10111213",
                method.invoke(null, value, 4, 16));
    }

    @Test
    public void concatenatesWakeKickResponseWithoutChangingBytes() throws Exception {
        Method method = InterphoneProbe.class.getDeclaredMethod(
                "concatBytes", byte[].class, byte[].class);
        method.setAccessible(true);
        assertArrayEquals(new byte[] {1, 2, 3, 4},
                (byte[]) method.invoke(null,
                        new byte[] {1, 2}, new byte[] {3, 4}));
    }

    @Test
    public void uartHpiTimeoutUploadMatchesOfflineAssembly() throws Exception {
        Field field = InterphoneProbe.class.getDeclaredField("UART_HPI_TIMEOUT_WORDS");
        field.setAccessible(true);
        int[] words = (int[]) field.get(null);
        Method method = InterphoneProbe.class.getDeclaredMethod("wordsToBytes", int[].class);
        method.setAccessible(true);
        byte[] actual = (byte[]) method.invoke(null, (Object) words);
        assertArrayEquals(new byte[] {
                0x09, 0x48, 0x01, 0x68, 0x00, 0x29, 0x02, (byte) 0xd0,
                0x49, 0x1e, 0x01, 0x60, 0x08, (byte) 0xd1, 0x07, 0x48,
                0x00, 0x21, 0x01, 0x70, 0x06, 0x48, 0x07, 0x49,
                0x01, 0x60, 0x07, 0x48, 0x07, 0x49, 0x01, 0x60,
                0x04, 0x4b, 0x18, 0x47, 0x00, (byte) 0xbf, 0x00, (byte) 0xbf,
                (byte) 0x80, 0x16, 0x00, 0x20, 0x5c, 0x01, 0x00, 0x20,
                0x3c, 0x00, 0x00, 0x20, (byte) 0xdd, 0x1d, 0x02, 0x08,
                (byte) 0x84, 0x16, 0x00, 0x20, 0x42, 0x52, 0x44, 0x47
        }, actual);
    }

    @Test
    public void buildsVendorHpiFrameWithoutPaddingOrReordering() throws Exception {
        Method method = InterphoneProbe.class.getDeclaredMethod(
                "createHpiFrame", int.class, byte[].class);
        method.setAccessible(true);
        assertArrayEquals(new byte[] {
                (byte) 0x84, (byte) 0xa9, 0x61, 0x00, 0x04, 0x00,
                0x18, 0x00, 0x00, 0x07
        }, (byte[]) method.invoke(null, 0,
                new byte[] {0x18, 0x00, 0x00, 0x07}));
    }

    @Test
    public void classifiesRealtimeHpiDirectionsAndBlockLengths() throws Exception {
        Method create = InterphoneProbe.class.getDeclaredMethod(
                "createHpiFrame", int.class, byte[].class);
        create.setAccessible(true);
        Method createPadded = InterphoneProbe.class.getDeclaredMethod(
                "createPaddedHpiFrame", int.class, byte[].class);
        createPadded.setAccessible(true);
        byte[] control = (byte[]) create.invoke(null, 0x30, new byte[] {0x01});
        byte[] pcm = (byte[]) create.invoke(null, 0x30, new byte[1283]);
        byte[] raw = new byte[3 + control.length + pcm.length];
        raw[0] = 0x55;
        raw[1] = 0x66;
        raw[2] = 0x77;
        System.arraycopy(control, 0, raw, 3, control.length);
        System.arraycopy(pcm, 0, raw, 3 + control.length, pcm.length);
        Method summarize = InterphoneProbe.class.getDeclaredMethod(
                "summarizeHpiCapture", byte[].class);
        summarize.setAccessible(true);
        Object summary = summarize.invoke(null, (Object) raw);
        assertEquals(2, getInt(summary, "frames"));
        assertEquals(2, getInt(summary, "type30"));
        assertEquals(1, getInt(summary, "controlFrames"));
        assertEquals(1, getInt(summary, "long1280"));
        assertEquals(3, getInt(summary, "unframedBytes"));
    }

    @Test
    public void baudOneShotStubsMatchOfflineAssemblies() throws Exception {
        Field keepWords = InterphoneProbe.class.getDeclaredField(
                "BAUD_KEEP_57600_WORDS");
        keepWords.setAccessible(true);
        Field setWords = InterphoneProbe.class.getDeclaredField(
                "BAUD_SET_230400_WORDS");
        setWords.setAccessible(true);
        Field restoreWords = InterphoneProbe.class.getDeclaredField(
                "BAUD_RESTORE_57600_WORDS");
        restoreWords.setAccessible(true);
        Field keepHash = InterphoneProbe.class.getDeclaredField(
                "BAUD_KEEP_57600_SHA256");
        keepHash.setAccessible(true);
        Field setHash = InterphoneProbe.class.getDeclaredField(
                "BAUD_SET_230400_SHA256");
        setHash.setAccessible(true);
        Field restoreHash = InterphoneProbe.class.getDeclaredField(
                "BAUD_RESTORE_57600_SHA256");
        restoreHash.setAccessible(true);
        Method wordsToBytes = InterphoneProbe.class.getDeclaredMethod(
                "wordsToBytes", int[].class);
        wordsToBytes.setAccessible(true);
        Method sha = InterphoneProbe.class.getDeclaredMethod(
                "sha256", byte[].class);
        sha.setAccessible(true);
        Method assertSafe = InterphoneProbe.class.getDeclaredMethod(
                "assertStackSafeBaudStub", byte[].class, String.class);
        assertSafe.setAccessible(true);

        byte[] keep = (byte[]) wordsToBytes.invoke(null,
                (Object) keepWords.get(null));
        byte[] set = (byte[]) wordsToBytes.invoke(null,
                (Object) setWords.get(null));
        byte[] restore = (byte[]) wordsToBytes.invoke(null,
                (Object) restoreWords.get(null));
        // v0.68: 56-byte stack-safe stubs
        assertEquals(56, keep.length);
        assertEquals(56, set.length);
        assertEquals(56, restore.length);
        assertEquals(keepHash.get(null), sha.invoke(null, keep));
        assertEquals(setHash.get(null), sha.invoke(null, set));
        assertEquals(restoreHash.get(null), sha.invoke(null, restore));
        // first halfword must be push {r4,lr} = 0xb510, NOT mov r2,lr = 0x4672
        assertEquals(0x10, set[0] & 0xff);
        assertEquals(0xb5, set[1] & 0xff);
        assertEquals(0x10, keep[0] & 0xff);
        assertEquals(0xb5, keep[1] & 0xff);
        assertEquals(0x10, restore[0] & 0xff);
        assertEquals(0xb5, restore[1] & 0xff);
        // blx r1 at offset 6, then pop {r2,r3}=0xbc0c at offset 8
        assertEquals(0x88, set[6] & 0xff);
        assertEquals(0x47, set[7] & 0xff);
        assertEquals(0x0c, set[8] & 0xff);
        assertEquals(0xbc, set[9] & 0xff);
        // mov r4,r2 then mov lr,r3
        assertEquals(0x14, set[10] & 0xff);
        assertEquals(0x46, set[11] & 0xff);
        assertEquals(0x9e, set[12] & 0xff);
        assertEquals(0x46, set[13] & 0xff);
        // baud immediates in little-endian literal pool (offset 32 after 8 code words)
        assertEquals(57600, le32(keep, 32));
        assertEquals(230400, le32(set, 32));
        assertEquals(57600, le32(restore, 32));
        assertEquals(0x08010a01, le32(set, 36));
        assertEquals(0x5045454B, le32(keep, 52)); // 'KEEP'
        assertEquals(0x44554142, le32(set, 52)); // 'BAUD'
        assertEquals(0x36373542, le32(restore, 52)); // 'B576'
        assertSafe.invoke(null, keep, "keep");
        assertSafe.invoke(null, set, "set");
        assertSafe.invoke(null, restore, "restore");
        // Gate must reject v0.66 r2-save pattern
        byte[] forbidden = new byte[56];
        System.arraycopy(set, 0, forbidden, 0, 56);
        forbidden[0] = 0x72;
        forbidden[1] = 0x46;
        try {
            assertSafe.invoke(null, forbidden, "forbidden");
            fail("assertStackSafeBaudStub must reject mov r2,lr");
        } catch (java.lang.reflect.InvocationTargetException expected) {
            assertTrue(expected.getCause() instanceof java.io.IOException);
        }
    }

    @Test
    public void analogRfHelperStubsAreStackSafeAndCallOnlyStockShortHelpers()
            throws Exception {
        Field prepWords = InterphoneProbe.class.getDeclaredField(
                "ANALOG_RF_PREP_WORDS");
        prepWords.setAccessible(true);
        Field offWords = InterphoneProbe.class.getDeclaredField(
                "ANALOG_RF_OFF_WORDS");
        offWords.setAccessible(true);
        Field prepHash = InterphoneProbe.class.getDeclaredField(
                "ANALOG_RF_PREP_SHA256");
        prepHash.setAccessible(true);
        Field offHash = InterphoneProbe.class.getDeclaredField(
                "ANALOG_RF_OFF_SHA256");
        offHash.setAccessible(true);
        Method wordsToBytes = InterphoneProbe.class.getDeclaredMethod(
                "wordsToBytes", int[].class);
        wordsToBytes.setAccessible(true);
        Method sha = InterphoneProbe.class.getDeclaredMethod(
                "sha256", byte[].class);
        sha.setAccessible(true);
        Method assertSafe = InterphoneProbe.class.getDeclaredMethod(
                "assertStackSafeBaudStub", byte[].class, String.class);
        assertSafe.setAccessible(true);

        byte[] prep = (byte[]) wordsToBytes.invoke(null,
                (Object) prepWords.get(null));
        byte[] off = (byte[]) wordsToBytes.invoke(null,
                (Object) offWords.get(null));
        assertEquals(56, prep.length);
        assertEquals(56, off.length);
        assertEquals(prepHash.get(null), sha.invoke(null, prep));
        assertEquals(offHash.get(null), sha.invoke(null, off));
        assertEquals(0, le32(prep, 32));
        assertEquals(0x0801ff4b, le32(prep, 36));
        assertEquals(0x0801ff35, le32(off, 36));
        assertEquals(0x50455250, le32(prep, 52)); // 'PREP'
        assertEquals(0x2146464f, le32(off, 52)); // 'OFF!'
        assertSafe.invoke(null, prep, "analogue-rf-prepare");
        assertSafe.invoke(null, off, "analogue-rf-off");
    }

    private static int le32(byte[] data, int offset) {
        return (data[offset] & 0xff)
                | ((data[offset + 1] & 0xff) << 8)
                | ((data[offset + 2] & 0xff) << 16)
                | ((data[offset + 3] & 0xff) << 24);
    }

    @Test
    public void step5ExitAckRequiresOneExactFrameWithoutTrailingBytes() {
        byte[] vocoderOff = hpiFrame(0, new byte[] {0x3e, 0x00});
        byte[] idle = hpiFrame(0, new byte[] {0x18, 0x00});
        assertTrue(InterphoneProbe.isExactSingleControlStatusAck(
                vocoderOff, 0, 0x3e));
        assertTrue(InterphoneProbe.isExactSingleControlStatusAck(
                idle, 0, 0x18));
        assertFalse(InterphoneProbe.isExactSingleControlStatusAck(
                concatBytes(vocoderOff, idle), 0, 0x3e));
        assertFalse(InterphoneProbe.isExactSingleControlStatusAck(
                concatBytes(vocoderOff, new byte[] {0x00}), 0, 0x3e));
        assertFalse(InterphoneProbe.isExactSingleControlStatusAck(
                hpiFrame(0, new byte[] {0x3e, 0x01}), 0, 0x3e));
    }

    private static byte[] le32Bytes(int value) {
        return new byte[] {
                (byte) value,
                (byte) (value >>> 8),
                (byte) (value >>> 16),
                (byte) (value >>> 24)
        };
    }

    @Test
    public void classifiesV061ChanDPcmAndSlotFoundFrames() throws Exception {
        Method createPadded = InterphoneProbe.class.getDeclaredMethod(
                "createPaddedHpiFrame", int.class, byte[].class);
        createPadded.setAccessible(true);
        byte[] chanPayload = new byte[29];
        chanPayload[0] = 0x01;
        chanPayload[1] = 27;
        for (int i = 0; i < 27; i++) {
            chanPayload[2 + i] = (byte) (0x10 + i);
        }
        byte[] pcmPayload = new byte[323];
        pcmPayload[0] = 0x00;
        pcmPayload[1] = 0x00;
        pcmPayload[2] = (byte) 0xa0;
        for (int i = 0; i < 320; i++) {
            pcmPayload[3 + i] = (byte) i;
        }
        byte[] chanD = (byte[]) createPadded.invoke(null, 0x30, chanPayload);
        byte[] pcm160 = (byte[]) createPadded.invoke(null, 0x30, pcmPayload);
        byte[] slotFound = (byte[]) createPadded.invoke(null, 0x30,
                new byte[] {0x7f, 0x00});
        byte[] raw = new byte[chanD.length + pcm160.length + slotFound.length];
        System.arraycopy(chanD, 0, raw, 0, chanD.length);
        System.arraycopy(pcm160, 0, raw, chanD.length, pcm160.length);
        System.arraycopy(slotFound, 0, raw, chanD.length + pcm160.length,
                slotFound.length);
        Method summarize = InterphoneProbe.class.getDeclaredMethod(
                "summarizeHpiCapture", byte[].class);
        summarize.setAccessible(true);
        Object summary = summarize.invoke(null, (Object) raw);
        assertEquals(3, getInt(summary, "frames"));
        assertEquals(1, getInt(summary, "chanD27"));
        assertEquals(1, getInt(summary, "pcm160"));
        assertEquals(1, getInt(summary, "dmrSlotFound"));
        assertEquals(0, getInt(summary, "long1280"));
        assertEquals(0, getInt(summary, "short36"));
        assertTrue((Boolean) summary.getClass()
                .getDeclaredMethod("hasRealtimeDataPlane").invoke(summary));
    }

    @Test
    public void padsOddLengthFlashRequestLikeVendorTransport() throws Exception {
        Method method = InterphoneProbe.class.getDeclaredMethod(
                "createPaddedHpiFrame", int.class, byte[].class);
        method.setAccessible(true);
        assertArrayEquals(new byte[] {
                (byte) 0x84, (byte) 0xa9, 0x61, 0x00, 0x05, 0x23,
                (byte) 0x94, 0x03, (byte) 0xf0, 0x00, 0x40, 0x00
        }, (byte[]) method.invoke(null, 0x23,
                new byte[] {(byte) 0x94, 0x03, (byte) 0xf0, 0x00, 0x40}));
    }

    @Test
    public void extractsStrict64ByteFlashResponse() throws Exception {
        byte[] raw = new byte[3 + 71 + 1];
        raw[0] = 0x55;
        raw[1] = 0x66;
        raw[2] = 0x77;
        int offset = 3;
        raw[offset] = (byte) 0x84;
        raw[offset + 1] = (byte) 0xa9;
        raw[offset + 2] = 0x61;
        raw[offset + 3] = 0x00;
        raw[offset + 4] = 0x41;
        raw[offset + 5] = 0x23;
        raw[offset + 6] = (byte) 0x94;
        byte[] expected = new byte[64];
        for (int index = 0; index < expected.length; index++) {
            expected[index] = (byte) index;
            raw[offset + 7 + index] = (byte) index;
        }
        Method method = InterphoneProbe.class.getDeclaredMethod(
                "extractFlashData64", byte[].class);
        method.setAccessible(true);
        assertArrayEquals(expected, (byte[]) method.invoke(null, (Object) raw));
        raw[offset + 5] = 0x22;
        assertEquals(null, method.invoke(null, (Object) raw));
    }

    private static byte[] parse(String response, int expectedLength) throws Exception {
        Method method = InterphoneProbe.class.getDeclaredMethod(
                "parseMemreadPayload", byte[].class, String.class, int.class);
        method.setAccessible(true);
        return (byte[]) method.invoke(null,
                response.getBytes(StandardCharsets.US_ASCII),
                "memread 0x20001c00 0x4", expectedLength);
    }

    private static boolean sameTapeMetadata(byte[] first, byte[] second) throws Exception {
        Method method = InterphoneProbe.class.getDeclaredMethod(
                "sameTapeMetadata", byte[].class, byte[].class);
        method.setAccessible(true);
        return (Boolean) method.invoke(null, first, second);
    }

    private static boolean looksLikeDmrChanD(byte[] data) throws Exception {
        Method method = InterphoneProbe.class.getDeclaredMethod(
                "looksLikeDmrChanD", byte[].class);
        method.setAccessible(true);
        return (Boolean) method.invoke(null, (Object) data);
    }

    private static int getInt(Object value, String name) throws Exception {
        Field field = value.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.getInt(value);
    }

    @Test
    public void vocoderEncoderSessionSerializesExactPcmInputFrame() {
        byte[] pcm = new byte[1280];
        for (int i = 0; i < pcm.length; i++) {
            pcm[i] = (byte) (i & 0xff);
        }
        byte[] pcmFrame = InterphoneProbe.createAnalogVoiceInPcmFrame(pcm, 0);
        assertEquals(1290, pcmFrame.length);
        assertArrayEquals(new byte[] {
                (byte) 0x84, (byte) 0xa9, 0x61, 0x05, 0x03, 0x03,
                0x00, 0x02, (byte) 0x80
        }, java.util.Arrays.copyOf(pcmFrame, 9));
    }

    @Test
    public void parsesType20Data36AmbeFrameFromHpiResponse() throws Exception {
        byte[] ambe36Payload = new byte[36];
        for (int i = 0; i < 36; i++) {
            ambe36Payload[i] = (byte) (0x10 + i);
        }
        byte[] payload = new byte[38];
        payload[0] = 0x01;
        payload[1] = 0x24; // 36 bytes length
        System.arraycopy(ambe36Payload, 0, payload, 2, 36);
        byte[] hpiFrame = hpiFrame(0x20, payload);

        Method extract = InterphoneProbe.class.getDeclaredMethod(
                "extractType20Data36AmbeFrame", byte[].class);
        extract.setAccessible(true);
        byte[] extracted = (byte[]) extract.invoke(null, (Object) hpiFrame);
        assertArrayEquals(ambe36Payload, extracted);
    }

    @Test
    public void vocoderEncoderRouteUsesGenericStatusAckAndRejectsFieldError() {
        byte[] route90 = hpiFrame(0, new byte[] {0x3e, (byte) 0x90});
        byte[] statusZero = hpiFrame(0, new byte[] {0x3e, 0x00});
        assertTrue(InterphoneProbe.containsAnalogControlAck(statusZero, route90));
        assertFalse(InterphoneProbe.containsAnalogControlAck(
                hpiFrame(0, new byte[] {0x17, 0x06}), route90));
    }

    @Test
    public void data36ParserRejectsCreditsWrongShapesAndEmbeddedFrames() {
        byte[] data36 = new byte[36];
        byte[] payload36 = new byte[38];
        payload36[0] = 0x01;
        payload36[1] = 0x24;
        System.arraycopy(data36, 0, payload36, 2, data36.length);
        byte[] valid = hpiFrame(0x20, payload36);

        assertNull(InterphoneProbe.extractType20Data36AmbeFrame(
                hpiFrame(0x20, new byte[] {0x01, 0x00})));
        assertNull(InterphoneProbe.extractType20Data36AmbeFrame(
                hpiFrame(0x20, new byte[29])));
        assertNull(InterphoneProbe.extractType20Data36AmbeFrame(
                hpiFrame(0x20, new byte[50])));
        assertNull(InterphoneProbe.extractType20Data36AmbeFrame(
                hpiFrame(0x30, payload36)));
        assertNull(InterphoneProbe.extractType20Data36AmbeFrame(
                hpiFrame(0x30, valid)));

        byte[] first = hpiFrame(0x20, new byte[] {0x01, 0x00});
        byte[] twoFrames = new byte[first.length + valid.length];
        System.arraycopy(first, 0, twoFrames, 0, first.length);
        System.arraycopy(valid, 0, twoFrames, first.length, valid.length);
        assertArrayEquals(data36,
                InterphoneProbe.extractType20Data36AmbeFrame(twoFrames));
    }

    @Test
    public void replaysImmutableV130HardwareEncoderCapturesWithoutLoss()
            throws Exception {
        String base = "research/h13_radio/captures/2026-08-09/"
                + "v130-r2-no-rf-pcm-to-ambe-20260809_1750/probe_files/"
                + "h13_v130_r2_evidence_20260809_1750/";
        byte[] complete = Files.readAllBytes(workspacePath(base
                + "hpi_pcm_to_ambe_zero_1786261889541.bin"));
        byte[] workResponse = Files.readAllBytes(workspacePath(base
                + "h13_pcm_to_ambe_control_3_1786261885251.bin"));
        byte[] postPcm = Files.readAllBytes(workspacePath(base
                + "h13_pcm_to_ambe_zero_response_1786261887681.bin"));

        assertEquals(496, complete.length);
        assertEquals("578DEE6760B3052F42F0B44B4493CE9602386574858756AB7BE3DEA61BEFE1EB",
                testSha256(complete));
        assertEquals(312, workResponse.length);
        assertEquals("CC576B369B1B06C95B660110A35E03126ADC48F78112A8B52F7331D8CB58B4D4",
                testSha256(workResponse));
        assertEquals(144, postPcm.length);
        assertEquals("DF6F2550C841352DA87F74252BB8C8117B5CB104A1A1FB5A62F12488FDB78940",
                testSha256(postPcm));

        InterphoneProbe.HpiEvidenceParse completeParse =
                InterphoneProbe.parseHpiEvidence(complete);
        assertEquals(20, completeParse.events.size());
        assertEquals(6, completeParse.controlAcks);
        assertEquals(0, completeParse.inputCredits);
        assertEquals(12, completeParse.candidate27);
        assertEquals(0, completeParse.candidate36);
        assertEquals(2, completeParse.asyncEvents);
        assertEquals(0, completeParse.malformedEvents);
        assertEquals(36, completeParse.ambeFrames9.size());
        assertEquals(324, completeParse.ambePayloadStream().length);
        assertArrayEquals(complete, concatenateEvidenceWire(completeParse));
        InterphoneProbe.HpiEvidenceEvent firstCandidate =
                completeParse.events.get(5);
        assertEquals(40, firstCandidate.offset);
        assertEquals(27, firstCandidate.declaredDataLength);
        assertEquals(1, firstCandidate.paddingLength);
        assertEquals(36, firstCandidate.wireFrame.length);
        assertEquals(3, firstCandidate.ambeFrames9.length);
        assertArrayEquals(java.util.Arrays.copyOfRange(
                firstCandidate.payload, 0, 9), firstCandidate.ambeFrames9[0]);

        InterphoneProbe.HpiEvidenceParse workParse =
                InterphoneProbe.parseHpiEvidence(workResponse);
        assertEquals(11, workParse.events.size());
        assertEquals(1, workParse.controlAcks);
        assertEquals(8, workParse.candidate27);
        assertEquals(2, workParse.asyncEvents);
        assertEquals(24, workParse.ambeFrames9.size());
        assertEquals(216, workParse.ambePayloadStream().length);
        assertArrayEquals(workResponse, concatenateEvidenceWire(workParse));

        InterphoneProbe.HpiEvidenceParse postParse =
                InterphoneProbe.parseHpiEvidence(postPcm);
        assertEquals(4, postParse.events.size());
        assertEquals(0, postParse.controlAcks);
        assertEquals(4, postParse.candidate27);
        assertEquals(0, postParse.asyncEvents);
        assertEquals(12, postParse.ambeFrames9.size());
        assertEquals(108, postParse.ambePayloadStream().length);
        assertArrayEquals(postPcm, concatenateEvidenceWire(postParse));
    }

    @Test
    public void replaysImmutableV209PcmResponseAsNoCreditMalformedFailure()
            throws Exception {
        String path = "research/h13_radio/captures/2026-08-12/"
                + "v209-hardware-encoder-one-zero-pcm-20260812_2209/"
                + "device_pull/h13_hw_encoder_pcm_response_1786536678820.bin";
        byte[] raw = Files.readAllBytes(workspacePath(path));
        assertEquals(831, raw.length);
        assertEquals("7215358A48FF1540DCFF3CF3787070B81F49CCFE3CC9347661F3B7589B89B838",
                testSha256(raw));

        InterphoneProbe.HpiEvidenceParse parsed =
                InterphoneProbe.parseHpiEvidence(raw);
        assertEquals(23, parsed.events.size());
        assertEquals(22, parsed.candidate27);
        assertEquals(0, parsed.candidate36);
        assertEquals(0, parsed.inputCredits);
        assertEquals(0, parsed.controlAcks);
        assertEquals(1, parsed.malformedEvents);
        assertEquals(66, parsed.ambeFrames9.size());
        assertEquals(594, parsed.ambePayloadStream().length);
        assertArrayEquals(raw, concatenateEvidenceWire(parsed));
        assertEquals(792, parsed.events.get(22).offset);
        assertEquals(39, parsed.events.get(22).wireFrame.length);
        assertEquals("单块全零PCM响应存在1个畸形事件",
                InterphoneProbe.hardwareEncoderPcmFailureReason(
                        parsed.malformedEvents, parsed.controlAcks,
                        parsed.inputCredits, -1, false));
    }

    @Test
    public void uartDrainGuardUsesElapsedLineTimeInsteadOfWaitingTwice()
            throws Exception {
        String source = new String(Files.readAllBytes(workspacePath(
                "app/src/main/native/uart_diag.c")), StandardCharsets.UTF_8);
        assertTrue(source.contains(
                "int64_t guard_deadline = values[1] + guard_nanos;"));
        assertTrue(source.contains(
                "int64_t guard_remaining = guard_deadline - guard_now;"));
        assertTrue(source.contains("guard_deadline > deadline"));
        assertTrue(source.contains("sleep_nanos(guard_remaining)"));
        assertFalse(source.contains("sleep_nanos(guard_nanos)"));
        assertFalse(source.contains("monotonic_nanos() + guard_nanos"));
    }

    @Test
    public void hardwareEncoderPcmFailureReasonKeepsFirstFailurePriority() {
        assertEquals("单块全零PCM响应存在1个畸形事件",
                InterphoneProbe.hardwareEncoderPcmFailureReason(
                        1, 2, 0, -1, false));
        assertEquals("单块全零PCM响应混入2个控制确认",
                InterphoneProbe.hardwareEncoderPcmFailureReason(
                        0, 2, 0, -1, false));
        assertEquals("单块全零PCM响应厂家短信用数量为0，要求恰好一个",
                InterphoneProbe.hardwareEncoderPcmFailureReason(
                        0, 0, 0, -1, false));
        assertEquals("单块全零PCM信用没有可证明的写后时间归属",
                InterphoneProbe.hardwareEncoderPcmFailureReason(
                        0, 0, 1, -1, false));
        assertEquals("单块全零PCM状态机拒绝响应窗口",
                InterphoneProbe.hardwareEncoderPcmFailureReason(
                        0, 0, 1, 25, false));
        assertEquals("", InterphoneProbe.hardwareEncoderPcmFailureReason(
                0, 0, 1, 25, true));
    }

    @Test
    public void parsesCandidateAmbe27And36IntoNineByteFrames() {
        byte[] data27 = new byte[27];
        byte[] data36 = new byte[36];
        for (int index = 0; index < data27.length; index++) {
            data27[index] = (byte) (0x20 + index);
        }
        for (int index = 0; index < data36.length; index++) {
            data36[index] = (byte) (0x60 + index);
        }
        byte[] payload27 = new byte[29];
        payload27[0] = 0x01;
        payload27[1] = 27;
        System.arraycopy(data27, 0, payload27, 2, data27.length);
        byte[] payload36 = new byte[38];
        payload36[0] = 0x01;
        payload36[1] = 36;
        System.arraycopy(data36, 0, payload36, 2, data36.length);
        byte[] wire27 = hpiWireFrame(0x20, payload27);
        byte[] wire36 = hpiWireFrame(0x20, payload36);
        byte[] credit = hpiWireFrame(0x7e, new byte[] {0x00, 0x00});
        byte[] async = hpiWireFrame(0x30, new byte[] {0x1b, 0x0c});
        byte[] raw = concatForTest(wire27, wire36, credit, async);

        InterphoneProbe.HpiEvidenceParse parsed =
                InterphoneProbe.parseHpiEvidence(raw);
        assertEquals(4, parsed.events.size());
        assertEquals(0, parsed.controlAcks);
        assertEquals(1, parsed.inputCredits);
        assertEquals(1, parsed.candidate27);
        assertEquals(1, parsed.candidate36);
        assertEquals(1, parsed.asyncEvents);
        assertEquals(0, parsed.malformedEvents);
        assertEquals(7, parsed.ambeFrames9.size());
        assertArrayEquals(concatForTest(data27, data36),
                parsed.ambePayloadStream());
        assertEquals(1, parsed.events.get(0).paddingLength);
        assertEquals(0, parsed.events.get(1).paddingLength);
        assertArrayEquals(new byte[] {0}, parsed.events.get(0).padding);
        for (byte[] frame : parsed.ambeFrames9) {
            assertEquals(9, frame.length);
        }
    }

    @Test
    public void hardwareEncoderEvidenceParserFailsClosedOnMalformedBoundaries() {
        byte[] embedded = new byte[27];
        embedded[5] = (byte) 0x84;
        embedded[6] = (byte) 0xa9;
        embedded[7] = 0x61;
        embedded[8] = 0x00;
        embedded[9] = 0x02;
        embedded[10] = 0x00;
        embedded[11] = 0x18;
        embedded[12] = 0x00;
        byte[] body = new byte[29];
        body[0] = 0x01;
        body[1] = 27;
        System.arraycopy(embedded, 0, body, 2, embedded.length);
        byte[] candidate = hpiWireFrame(0x20, body);
        byte[] noisy = concatForTest(new byte[] {0x55, 0x66}, candidate,
                new byte[] {0x77});
        InterphoneProbe.HpiEvidenceParse noisyParse =
                InterphoneProbe.parseHpiEvidence(noisy);
        assertEquals(1, noisyParse.candidate27);
        assertEquals(2, noisyParse.malformedEvents);
        assertEquals(3, noisyParse.ambeFrames9.size());

        byte[] wrongCountBody = body.clone();
        wrongCountBody[1] = 28;
        InterphoneProbe.HpiEvidenceParse wrongCount =
                InterphoneProbe.parseHpiEvidence(
                        hpiWireFrame(0x20, wrongCountBody));
        assertEquals(0, wrongCount.candidate27);
        assertEquals(1, wrongCount.malformedEvents);

        byte[] truncated = java.util.Arrays.copyOf(candidate,
                candidate.length - 2);
        InterphoneProbe.HpiEvidenceParse truncatedParse =
                InterphoneProbe.parseHpiEvidence(truncated);
        assertEquals(0, truncatedParse.candidate27);
        assertEquals(1, truncatedParse.malformedEvents);

        byte[] badPadding = candidate.clone();
        badPadding[badPadding.length - 1] = 1;
        InterphoneProbe.HpiEvidenceParse badPaddingParse =
                InterphoneProbe.parseHpiEvidence(badPadding);
        assertEquals(0, badPaddingParse.candidate27);
        assertTrue(badPaddingParse.malformedEvents >= 1);

        InterphoneProbe.HpiEvidenceParse empty =
                InterphoneProbe.parseHpiEvidence(null);
        assertEquals(0, empty.events.size());
        assertEquals(0, empty.ambePayloadStream().length);
    }

    @Test
    public void hpiEvidenceParserMergesSplitWindowsBeforeClassifying() {
        byte[] data27 = new byte[27];
        data27[0] = 0x11;
        data27[26] = 0x22;
        byte[] body = new byte[29];
        body[0] = 0x01;
        body[1] = 27;
        System.arraycopy(data27, 0, body, 2, data27.length);
        byte[] wire = hpiWireFrame(0x20, body);
        assertTrue(wire.length > 8);
        int split = 8;
        byte[] first = java.util.Arrays.copyOf(wire, split);
        byte[] second = java.util.Arrays.copyOfRange(wire, split, wire.length);

        InterphoneProbe.HpiEvidenceParse left =
                InterphoneProbe.parseHpiEvidence(first);
        InterphoneProbe.HpiEvidenceParse right =
                InterphoneProbe.parseHpiEvidence(second);
        assertEquals(0, left.candidate27 + right.candidate27);
        assertTrue(left.malformedEvents + right.malformedEvents >= 1);

        InterphoneProbe.HpiEvidenceParse merged =
                InterphoneProbe.parseHpiEvidenceWindows(first, second);
        assertEquals(1, merged.candidate27);
        assertEquals(0, merged.malformedEvents);
        assertEquals(3, merged.ambeFrames9.size());
    }

    @Test
    public void hpiEvidenceParserReplaysV336StyleLeftoverThenNextWindow() {
        // v3.36：输出窗 0x30 起出现 0x31=ff 残帧；下一窗若接上完整同步字，
        // 不得用前一窗 candidate27=0 代表全会话。
        byte[] leftover = new byte[] {
                0x31, (byte) 0xff, 0x00, 0x00
        };
        byte[] credit = hpiWireFrame(0x7e, new byte[] {0x00, 0x00});
        InterphoneProbe.HpiEvidenceParse leftoverOnly =
                InterphoneProbe.parseHpiEvidence(leftover);
        assertEquals(0, leftoverOnly.candidate27);
        assertTrue(leftoverOnly.malformedEvents >= 1);

        InterphoneProbe.HpiEvidenceParse merged =
                InterphoneProbe.parseHpiEvidenceWindows(leftover, credit);
        assertEquals(1, merged.inputCredits);
        assertEquals(0, merged.candidate27);
        assertEquals(1, merged.malformedEvents);
        assertEquals(2, merged.events.size());
    }

    @Test
    public void hardwareEncoderCreditUsesVendorBodyShapeNotPacketType() {
        for (int packetType : new int[] {0, 1, 2, 3, 5, 0x20, 0x31, 0x7e}) {
            InterphoneProbe.HpiEvidenceParse one = InterphoneProbe.parseHpiEvidence(
                    hpiWireFrame(packetType, new byte[] {0x00}));
            assertEquals(1, one.inputCredits);
            assertEquals(0, one.malformedEvents);
            InterphoneProbe.HpiEvidenceParse two = InterphoneProbe.parseHpiEvidence(
                    hpiWireFrame(packetType, new byte[] {0x01, (byte) 0xa5}));
            assertEquals(1, two.inputCredits);
            assertEquals(0, two.malformedEvents);
        }
        assertEquals(0, InterphoneProbe.parseHpiEvidence(
                hpiWireFrame(3, new byte[] {0x02})).inputCredits);
        assertEquals(0, InterphoneProbe.parseHpiEvidence(
                hpiWireFrame(3, new byte[] {0x00, 0x00, 0x00})).inputCredits);
    }

    @Test
    public void hardwareEncoderNoInputModelRequiresFourOrderedSingleAcksAndRecovery()
            throws Exception {
        InterphoneProbe.HardwareEncoderNoInputModel model =
                new InterphoneProbe.HardwareEncoderNoInputModel();
        assertArrayEquals(hexBytes("84a9610002003e00"),
                model.expectedRequest());
        int[] fields = {0x3e, 0x3e, 0x1a, 0x18};
        for (int index = 0; index < fields.length; index++) {
            int field = fields[index];
            byte[] request = model.expectedRequest();
            assertNotNull(request);
            if (index == 1) {
                assertArrayEquals(hexBytes("84a9610002003e80"), request);
            }
            byte[] response = hpiWireFrame(0,
                    new byte[] {(byte) field, 0x00});
            assertTrue(model.acceptControlExchange(request, response));
        }
        assertEquals(4, model.requestCount());
        assertEquals(4, model.ackCount());
        assertNull(model.expectedRequest());
        assertTrue(model.acceptBaselineWindow(new byte[0]));
        assertTrue(model.acceptBaselineWindow(hpiWireFrame(0x30,
                new byte[] {0x7f, 0x01})));
        byte[] ambe27 = new byte[29];
        ambe27[0] = 0x01;
        ambe27[1] = 27;
        assertTrue(model.acceptBaselineWindow(hpiWireFrame(0x20, ambe27)));
        assertEquals(3, model.baselineWindowCount());
        assertEquals(1, model.candidate27());
        assertTrue(model.beginRecovery());
        assertTrue(model.finishRecovery(true));
        assertEquals(InterphoneProbe.HardwareEncoderNoInputModel.Phase.RECOVERED,
                model.phase());
    }

    @Test
    public void v310Step5ControlModelUsesExactDebugSpeechHpiAsFourthRequest() {
        InterphoneProbe.HardwareEncoderNoInputModel model =
                new InterphoneProbe.HardwareEncoderNoInputModel(
                        false, false, true);
        byte[][] requests = new byte[][] {
                hexBytes("84a9610002003e00"),
                hexBytes("84a9610002003e80"),
                hexBytes("84a9610002001a03"),
                hexBytes("84a96100040018000007")
        };
        int[] ackFields = new int[] {0x3e, 0x3e, 0x1a, 0x18};
        for (int index = 0; index < requests.length; index++) {
            assertArrayEquals(requests[index], model.expectedRequest());
            assertTrue(model.acceptControlExchange(requests[index],
                    hpiWireFrame(0, new byte[] {
                            (byte) ackFields[index], 0x00
                    })));
        }
        assertNull(model.expectedRequest());
        assertEquals(InterphoneProbe.HardwareEncoderNoInputModel.Phase.BASELINE,
                model.phase());
    }

    @Test
    public void hardwareEncoderNoInputModelFailsClosedOnOrderAckAndForbiddenEvents()
            throws Exception {
        InterphoneProbe.HardwareEncoderNoInputModel model =
                new InterphoneProbe.HardwareEncoderNoInputModel();
        byte[] wrong = hpiWireFrame(0, new byte[] {0x1a, 0x03});
        assertFalse(model.acceptControlExchange(wrong,
                hpiWireFrame(0, new byte[] {0x1a, 0x00})));
        assertEquals(InterphoneProbe.HardwareEncoderNoInputModel.Phase.FAILED,
                model.phase());

        model = new InterphoneProbe.HardwareEncoderNoInputModel();
        byte[] request = model.expectedRequest();
        byte[] duplicateAck = concatForTest(
                hpiWireFrame(0, new byte[] {0x3e, 0x00}),
                hpiWireFrame(0, new byte[] {0x3e, 0x00}));
        assertFalse(model.acceptControlExchange(request, duplicateAck));

        model = new InterphoneProbe.HardwareEncoderNoInputModel();
        for (int field : new int[] {0x3e, 0x3e, 0x1a, 0x18}) {
            assertTrue(model.acceptControlExchange(model.expectedRequest(),
                    hpiWireFrame(0, new byte[] {(byte) field, 0x00})));
        }
        assertFalse(model.acceptBaselineWindow(
                hpiWireFrame(0x7e, new byte[] {0x01})));
        assertEquals(InterphoneProbe.HardwareEncoderNoInputModel.Phase.FAILED,
                model.phase());
    }

    @Test
    public void hardwareEncoderOnePcmModelRequiresExactlyOneCreditAndThreeWindows()
            throws Exception {
        InterphoneProbe.HardwareEncoderNoInputModel model =
                new InterphoneProbe.HardwareEncoderNoInputModel();
        for (int field : new int[] {0x3e, 0x3e, 0x1a, 0x18}) {
            assertTrue(model.acceptControlExchange(model.expectedRequest(),
                    hpiWireFrame(0, new byte[] {(byte) field, 0x00})));
        }
        byte[] before = new byte[29];
        before[0] = 0x01;
        before[1] = 27;
        assertTrue(model.acceptBaselineWindow(hpiWireFrame(0x20, before)));
        byte[] after = concatForTest(
                hpiWireFrame(0x7e, new byte[] {0x01}),
                hpiWireFrame(0x20, before));
        assertTrue(model.acceptPcmExchange(after));
        assertFalse(model.acceptPcmExchange(after));
        assertEquals(InterphoneProbe.HardwareEncoderNoInputModel.Phase.FAILED,
                model.phase());

        model = new InterphoneProbe.HardwareEncoderNoInputModel();
        for (int field : new int[] {0x3e, 0x3e, 0x1a, 0x18}) {
            assertTrue(model.acceptControlExchange(model.expectedRequest(),
                    hpiWireFrame(0, new byte[] {(byte) field, 0x00})));
        }
        assertTrue(model.acceptBaselineWindow(new byte[0]));
        assertFalse(model.acceptPcmExchange(new byte[0]));

        model = new InterphoneProbe.HardwareEncoderNoInputModel();
        for (int field : new int[] {0x3e, 0x3e, 0x1a, 0x18}) {
            assertTrue(model.acceptControlExchange(model.expectedRequest(),
                    hpiWireFrame(0, new byte[] {(byte) field, 0x00})));
        }
        assertTrue(model.acceptBaselineWindow(new byte[0]));
        byte[] duplicateCredit = concatForTest(
                hpiWireFrame(0x7e, new byte[] {0x01}),
                hpiWireFrame(0x7e, new byte[] {0x00}));
        assertFalse(model.acceptPcmExchange(duplicateCredit));
    }

    @Test
    public void hardwareEncoderOneZeroPcmWireIsFrozenAndSingleBlock() throws Exception {
        byte[] pcm = new byte[1280];
        byte[] wire = InterphoneProbe.createAnalogVoiceInPcmFrame(pcm, 0);
        assertEquals(1290, wire.length);
        assertArrayEquals(hexBytes("84a961050303000280"),
                Arrays.copyOfRange(wire, 0, 9));
        assertEquals(
                "ac050cef21b82c75cf5422d79e4d579d4972d65584d5def189f7e64fd5313ea8",
                sha256Hex(wire));
        try {
            InterphoneProbe.createAnalogVoiceInPcmFrame(pcm, 1);
            fail("1280字节输入不得构造第二块PCM");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("outside"));
        }
    }

    @Test
    public void hardwareEncoderTonePcmWireAndControlRouteAreFrozen()
            throws Exception {
        byte[] pcm = InterphoneProbe.createTonePcm8kS16le(800, 4000, 640);
        byte[] wire = InterphoneProbe.createAnalogVoiceInPcmFrame(pcm, 0);
        assertEquals("0c87924e046fe66fdaa7ab20cc977ebb0597970b0871dd8decb1bb39f8baff5f",
                sha256Hex(pcm));
        assertEquals("0cca15f8390d005b3b8ce3cc2d13e0d44372e070fa9cc37ce7443a542f572123",
                sha256Hex(wire));
        InterphoneProbe.HardwareEncoderNoInputModel model =
                new InterphoneProbe.HardwareEncoderNoInputModel(true);
        assertArrayEquals(hexBytes("84a9610002003e00"), model.expectedRequest());
        assertTrue(model.acceptControlExchange(model.expectedRequest(),
                hpiWireFrame(0, new byte[] {0x3e, 0x00})));
        assertArrayEquals(hexBytes("84a9610002003e90"), model.expectedRequest());
    }

    @Test
    public void hardwareEncoderToneContentModelDoesNotRequireShortCredit()
            throws Exception {
        InterphoneProbe.HardwareEncoderNoInputModel model =
                new InterphoneProbe.HardwareEncoderNoInputModel(true);
        for (int field : new int[] {0x3e, 0x3e, 0x1a, 0x18}) {
            assertTrue(model.acceptControlExchange(model.expectedRequest(),
                    hpiWireFrame(0, new byte[] {(byte) field, 0x00})));
        }
        assertTrue(model.acceptBaselineWindow(new byte[0]));
        byte[] candidate = new byte[29];
        candidate[0] = 1;
        candidate[1] = 27;
        assertTrue(model.acceptPcmContentExchange(
                hpiWireFrame(0x20, candidate)));
        assertTrue(model.acceptPcmContentWindow(new byte[0]));
        assertTrue(model.acceptPcmContentWindow(new byte[0]));
        assertEquals(1, model.candidate27());
        assertTrue(model.beginRecovery());
        assertTrue(model.finishRecovery(true));
    }

    @Test
    public void hardwareEncoderTwoStageRouteRequiresDrainedPcmThenFreshAck()
            throws Exception {
        InterphoneProbe.HardwareEncoderNoInputModel model =
                new InterphoneProbe.HardwareEncoderNoInputModel(false, true);
        byte[][] expected = {
                hexBytes("84a9610002003e00"),
                hexBytes("84a9610002003e80"),
                hexBytes("84a9610002001a03"),
                hexBytes("84a96100040018030300")
        };
        int[] fields = {0x3e, 0x3e, 0x1a, 0x18};
        for (int index = 0; index < expected.length; index++) {
            assertArrayEquals(expected[index], model.expectedRequest());
            assertTrue(model.acceptControlExchange(model.expectedRequest(),
                    hpiWireFrame(0, new byte[] {(byte) fields[index], 0x00})));
        }
        assertTrue(model.acceptBaselineWindow(new byte[0]));
        assertNull(model.expectedOutputSwitchRequest());
        assertTrue(model.acceptTwoStagePcmInputWindow(new byte[0], true));
        byte[] switchRequest = model.expectedOutputSwitchRequest();
        assertArrayEquals(hexBytes("84a9610002003e90"), switchRequest);
        byte[] switchAck = hpiWireFrame(0, new byte[] {0x3e, 0x00});
        assertTrue(model.acceptOutputSwitchExchange(switchRequest, switchAck));
        byte[] candidate = new byte[29];
        candidate[0] = 1;
        candidate[1] = 27;
        assertTrue(model.acceptPcmContentExchange(
                hpiWireFrame(0x20, candidate)));
        assertTrue(model.acceptPcmContentWindow(new byte[0]));
        assertTrue(model.acceptPcmContentWindow(new byte[0]));
        assertEquals(5, model.requestCount());
        assertEquals(5, model.ackCount());
        assertEquals(3, model.baselineWindowCount());
        assertTrue(model.beginRecovery());
        assertTrue(model.finishRecovery(true));
    }

    @Test
    public void hardwareEncoderTwoStageRejectsUndrainedOrContaminatedInput()
            throws Exception {
        InterphoneProbe.HardwareEncoderNoInputModel undrained =
                twoStageModelAtBaseline();
        assertFalse(undrained.acceptTwoStagePcmInputWindow(new byte[0], false));

        InterphoneProbe.HardwareEncoderNoInputModel staleAck =
                twoStageModelAtBaseline();
        assertFalse(staleAck.acceptTwoStagePcmInputWindow(
                hpiWireFrame(0, new byte[] {0x3e, 0x00}), true));

        InterphoneProbe.HardwareEncoderNoInputModel prematureCandidate =
                twoStageModelAtBaseline();
        byte[] candidate = new byte[29];
        candidate[0] = 1;
        candidate[1] = 27;
        assertFalse(prematureCandidate.acceptTwoStagePcmInputWindow(
                hpiWireFrame(0x20, candidate), true));
    }

    @Test
    public void hardwareEncoderTwoStageRejectsWrongMissingDuplicateOrMixedAck()
            throws Exception {
        byte[] ack = hpiWireFrame(0, new byte[] {0x3e, 0x00});
        InterphoneProbe.HardwareEncoderNoInputModel wrongRequest =
                twoStageModelAfterPcm();
        assertFalse(wrongRequest.acceptOutputSwitchExchange(
                hexBytes("84a9610002003e20"), ack));

        InterphoneProbe.HardwareEncoderNoInputModel missing =
                twoStageModelAfterPcm();
        assertFalse(missing.acceptOutputSwitchExchange(
                missing.expectedOutputSwitchRequest(), new byte[0]));

        InterphoneProbe.HardwareEncoderNoInputModel duplicate =
                twoStageModelAfterPcm();
        assertFalse(duplicate.acceptOutputSwitchExchange(
                duplicate.expectedOutputSwitchRequest(),
                concatForTest(ack, ack)));

        InterphoneProbe.HardwareEncoderNoInputModel mixed =
                twoStageModelAfterPcm();
        byte[] candidate = new byte[29];
        candidate[0] = 1;
        candidate[1] = 27;
        assertFalse(mixed.acceptOutputSwitchExchange(
                mixed.expectedOutputSwitchRequest(),
                concatForTest(ack, hpiWireFrame(0x20, candidate))));
    }

    @Test
    public void hardwareEncoderOutputSwitchSeparatesAckFromImmediateOutput() {
        byte[] ack = hpiWireFrame(0, new byte[] {0x3e, 0x00});
        byte[] candidate = hpiWireFrame(0x20, new byte[] {1, 27, 0});
        byte[] joined = concatForTest(ack, candidate);
        assertEquals(8,
                InterphoneProbe.hardwareEncoderOutputSwitchAckPrefixLength(joined));
        assertEquals(-1,
                InterphoneProbe.hardwareEncoderOutputSwitchAckPrefixLength(candidate));
        assertEquals(-1,
                InterphoneProbe.hardwareEncoderOutputSwitchAckPrefixLength(
                new byte[0]));
    }

    @Test
    public void stockHpiRereadStubIsStackSafeAndHasFrozenHash() throws Exception {
        byte[] stub = new byte[InterphoneProbe.STOCK_HPI_REREAD_WORDS.length * 4];
        for (int i = 0; i < InterphoneProbe.STOCK_HPI_REREAD_WORDS.length; i++) {
            int word = InterphoneProbe.STOCK_HPI_REREAD_WORDS[i];
            stub[i * 4] = (byte) word;
            stub[i * 4 + 1] = (byte) (word >>> 8);
            stub[i * 4 + 2] = (byte) (word >>> 16);
            stub[i * 4 + 3] = (byte) (word >>> 24);
        }
        assertEquals(56, stub.length);
        assertEquals((byte) 0x10, stub[0]);
        assertEquals((byte) 0xb5, stub[1]);
        assertEquals(InterphoneProbe.STOCK_HPI_REREAD_SHA256,
                sha256Hex(stub));
        assertEquals(0x20002829, InterphoneProbe.STOCK_HPI_REREAD_WORDS[9]);
    }

    @Test
    public void vocoderIo0x90IsCompleteVoiceInPlusVocoderOutBitmap() {
        assertEquals(0x80, InterphoneProbe.vocoderIoVoiceInFromHpiBit());
        assertEquals(0x10, InterphoneProbe.vocoderIoVocoderOutToHpiBit());
        assertEquals(0x90, InterphoneProbe.vocoderIoPcmToAmbeBitmap());
        assertEquals(0x90, 0x80 | 0x10);
        assertFalse(0x10 == (0x80 | 0x10));
    }

    @Test
    public void creditPacedSenderSendsFirstActivelyThenOnePacketPerCredit() {
        InterphoneProbe.CreditPacedPcmSender sender =
                new InterphoneProbe.CreditPacedPcmSender(2);
        assertFalse(sender.acceptCreditAndSendNext(new byte[] {0x00, 0x00}));
        assertTrue(sender.sendFirstActively());
        assertFalse(sender.sendFirstActively());
        assertEquals(1, sender.sent());
        assertTrue(sender.acceptCreditAndSendNext(new byte[] {0x00, 0x00}));
        assertEquals(2, sender.sent());
        assertEquals(0, sender.queued());
        assertFalse(sender.acceptCreditAndSendNext(new byte[] {0x01, 0x00}));
        assertEquals(2, sender.sent());
    }

    @Test
    public void v336OutputStageReplayFindsFirstMalformationAt0x30AndFfAt0x31() {
        byte[] stage = hexBytes(
                "84a961000220000084a9610002200100"
                        + "84a961000220000084a9610002200100"
                        + "84a961000220000084a9610002200100"
                        + "84ffa9ff0002ff20ff0084a961000220");
        assertEquals((byte) 0x84, stage[0x30]);
        assertEquals((byte) 0xff, stage[0x31]);
        InterphoneProbe.HpiEvidenceParse parsed =
                InterphoneProbe.parseHpiEvidence(stage);
        assertEquals(6, parsed.inputCredits);
        assertEquals(0, parsed.candidate27);
        assertEquals(0, parsed.candidate36);
        assertTrue(parsed.malformedEvents > 0);
        assertEquals(0x30, parsed.firstMalformedOffset);
    }

    @Test
    public void hardwareEncoderToneContentRequiresAtLeastOneCandidate() {
        assertFalse(InterphoneProbe.hardwareEncoderToneContentComplete(
                true, true, 0, 0));
        assertFalse(InterphoneProbe.hardwareEncoderToneContentComplete(
                false, true, 1, 0));
        assertFalse(InterphoneProbe.hardwareEncoderToneContentComplete(
                true, false, 1, 0));
        assertTrue(InterphoneProbe.hardwareEncoderToneContentComplete(
                true, true, 1, 0));
        assertTrue(InterphoneProbe.hardwareEncoderToneContentComplete(
                true, true, 0, 1));
    }

    private static InterphoneProbe.HardwareEncoderNoInputModel
            twoStageModelAtBaseline() {
        InterphoneProbe.HardwareEncoderNoInputModel model =
                new InterphoneProbe.HardwareEncoderNoInputModel(false, true);
        for (int field : new int[] {0x3e, 0x3e, 0x1a, 0x18}) {
            assertTrue(model.acceptControlExchange(model.expectedRequest(),
                    hpiWireFrame(0, new byte[] {(byte) field, 0x00})));
        }
        assertTrue(model.acceptBaselineWindow(new byte[0]));
        return model;
    }

    private static InterphoneProbe.HardwareEncoderNoInputModel
            twoStageModelAfterPcm() {
        InterphoneProbe.HardwareEncoderNoInputModel model =
                twoStageModelAtBaseline();
        assertTrue(model.acceptTwoStagePcmInputWindow(new byte[0], true));
        return model;
    }

    @Test
    public void completeHpiPrefixLeavesOnlyTruncatedTail() {
        byte[] first = hpiWireFrame(0x20, new byte[] {1, 27, 0, 0});
        byte[] second = hpiWireFrame(0x20, new byte[] {1, 27, 1, 2, 3});
        byte[] combined = concatForTest(first,
                Arrays.copyOf(second, second.length - 2));
        assertEquals(first.length,
                InterphoneProbe.completeHpiPrefixLength(combined));
        assertEquals(first.length,
                InterphoneProbe.completeHpiPrefixLength(first));
        assertTrue(InterphoneProbe.isValidTruncatedHpiTail(
                Arrays.copyOfRange(combined, first.length, combined.length)));
        assertTrue(InterphoneProbe.isValidTruncatedHpiTail(new byte[0]));
        assertTrue(InterphoneProbe.isValidTruncatedHpiTail(
                new byte[] {(byte) 0x84, (byte) 0xa9}));
        assertFalse(InterphoneProbe.isValidTruncatedHpiTail(
                new byte[] {(byte) 0xff}));
        assertFalse(InterphoneProbe.isValidTruncatedHpiTail(second));
        assertFalse(InterphoneProbe.isValidTruncatedHpiTail(
                new byte[] {(byte) 0x84, (byte) 0xa9, 0x61, 0x20, 0x01}));
    }

    @Test
    public void hardwareEncoderPcmWriteChunksAreTwenty64AndOne10()
            throws Exception {
        byte[] pcm = new byte[1280];
        byte[] wire = InterphoneProbe.createAnalogVoiceInPcmFrame(pcm, 0);
        List<byte[]> chunks = InterphoneProbe.hardwareEncoderPcmWriteChunks(wire);
        assertEquals(21, chunks.size());
        ByteArrayOutputStream rebuilt = new ByteArrayOutputStream();
        for (int index = 0; index < chunks.size(); index++) {
            assertEquals(index < 20 ? 64 : 10, chunks.get(index).length);
            rebuilt.write(chunks.get(index), 0, chunks.get(index).length);
        }
        assertArrayEquals(wire, rebuilt.toByteArray());
        assertEquals(
                "ac050cef21b82c75cf5422d79e4d579d4972d65584d5def189f7e64fd5313ea8",
                sha256Hex(rebuilt.toByteArray()));
        assertEquals(64, InterphoneProbe.HARDWARE_ENCODER_PCM_WRITE_CHUNK_BYTES);
        assertEquals(2, InterphoneProbe.HARDWARE_ENCODER_PCM_WRITE_GAP_MS);
        assertEquals(20, InterphoneProbe.HARDWARE_ENCODER_PCM_WRITE_GAP_MAX_MS);
    }

    @Test
    public void hardwareEncoderPcmWriteChunksRejectEmptyInput() {
        try {
            InterphoneProbe.hardwareEncoderPcmWriteChunks(new byte[0]);
            fail("空PCM线帧必须拒绝");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("为空"));
        }
    }

    @Test
    public void hardwareEncoderSramGatesSeparateDynamicReadOnlyAndWrittenState() {
        InterphoneProbe.HardwareEncoderSramState baseline =
                hardwareEncoderSramState(0, 0, 0, 0);
        InterphoneProbe.HardwareEncoderSramState ringMoved =
                hardwareEncoderSramState(1, 0, 0, 0);
        assertTrue(InterphoneProbe.sameHardwareEncoderSramState(
                baseline, ringMoved));

        InterphoneProbe.HardwareEncoderSramState queueMoved =
                hardwareEncoderSramState(0, 1, 0, 0);
        assertFalse(InterphoneProbe.sameHardwareEncoderSramState(
                baseline, queueMoved));
        assertTrue(InterphoneProbe.hardwareEncoderWrittenStateRestored(
                baseline, queueMoved));

        InterphoneProbe.HardwareEncoderSramState sessionMoved =
                hardwareEncoderSramState(0, 0, 1, 0);
        assertTrue(InterphoneProbe.hardwareEncoderWrittenStateRestored(
                baseline, sessionMoved));

        InterphoneProbe.HardwareEncoderSramState timeoutNotRestored =
                hardwareEncoderSramState(0, 0, 0, 1);
        assertFalse(InterphoneProbe.hardwareEncoderWrittenStateRestored(
                baseline, timeoutNotRestored));
    }

    @Test
    public void hardwareEncoderCountdownAcceptsOnlyPositiveMonotonicProgress() {
        assertTrue(InterphoneProbe.hardwareEncoderCountdownProgressValid(
                700, 605, 590));
        assertTrue(InterphoneProbe.hardwareEncoderCountdownProgressValid(
                700, 700, 700));
        assertFalse(InterphoneProbe.hardwareEncoderCountdownProgressValid(
                700, 701, 600));
        assertFalse(InterphoneProbe.hardwareEncoderCountdownProgressValid(
                700, 600, 601));
        assertFalse(InterphoneProbe.hardwareEncoderCountdownProgressValid(
                700, 600, 0));
        assertFalse(InterphoneProbe.hardwareEncoderCountdownProgressValid(
                0, 0, 0));
    }

    @Test
    public void hardwareEncoderRemainingTimeUsesFreshCheckpoint() {
        assertEquals(5900L,
                InterphoneProbe.hardwareEncoderRemainingMillis(590, 100.0));
        assertEquals(1L,
                InterphoneProbe.hardwareEncoderRemainingMillis(1, 1000.0));
        for (double invalidRate : new double[] {0.0, 9.99, 100000.01}) {
            try {
                InterphoneProbe.hardwareEncoderRemainingMillis(590,
                        invalidRate);
                fail("非法tick频率必须拒绝：" + invalidRate);
            } catch (IllegalArgumentException expected) {
                // 预期拒绝。
            }
        }
        try {
            InterphoneProbe.hardwareEncoderRemainingMillis(0, 100.0);
            fail("零检查点必须拒绝");
        } catch (IllegalArgumentException expected) {
            // 预期拒绝。
        }
    }

    private static InterphoneProbe.HardwareEncoderSramState
            hardwareEncoderSramState(int ringValue, int queueValue,
            int sessionValue, int timeoutValue) {
        byte[] ring = new byte[8];
        ring[0] = (byte) ringValue;
        byte[] queueA = new byte[112];
        queueA[0] = (byte) queueValue;
        byte[] timeoutCode = new byte[64];
        timeoutCode[0] = (byte) timeoutValue;
        byte[] session = new byte[] {(byte) sessionValue};
        return new InterphoneProbe.HardwareEncoderSramState(
                new byte[56], new byte[4], timeoutCode, new byte[4],
                new byte[4], hexBytes("dd1d0208"), new byte[1], ring,
                new byte[4], new byte[1], session, new byte[4], new byte[4],
                new byte[1], new byte[4], queueA, new byte[168], new byte[9],
                new byte[] {2}, new byte[1]);
    }

    private static byte[] concatenateEvidenceWire(
            InterphoneProbe.HpiEvidenceParse parsed) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        for (InterphoneProbe.HpiEvidenceEvent event : parsed.events) {
            output.write(event.wireFrame);
        }
        return output.toByteArray();
    }

    private static byte[] concatForTest(byte[]... values) {
        int length = 0;
        for (byte[] value : values) {
            length += value.length;
        }
        byte[] result = new byte[length];
        int offset = 0;
        for (byte[] value : values) {
            System.arraycopy(value, 0, result, offset, value.length);
            offset += value.length;
        }
        return result;
    }

    private static String testSha256(byte[] value) throws Exception {
        byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                .digest(value);
        StringBuilder output = new StringBuilder(64);
        for (byte item : digest) {
            output.append(String.format(java.util.Locale.US,
                    "%02X", item & 0xff));
        }
        return output.toString();
    }

    private static byte[] fullFrozenAmbeAsset() throws Exception {
        return Files.readAllBytes(workspacePath(
                InterphoneProbe.REAL_RX_STEP2_ASSET_DIRECTORY
                        + "/00_source_h13_chan_d_stream.bin"));
    }

    private static byte[] measuredPrivacyRuntime14() {
        return hexBytes("01 01 01 00 00 12 34 56 78 90 12 34 56 78");
    }

    private static InterphoneProbe.ContinuousData36TxModel
            readyContinuousModel(int unitCount) throws Exception {
        InterphoneProbe.ContinuousData36TxModel model =
                new InterphoneProbe.ContinuousData36TxModel(
                        InterphoneProbe.FrozenAmbeAsset.require(
                                fullFrozenAmbeAsset()),
                        measuredPrivacyRuntime14(), 0, unitCount);
        model.onRealtimeData27();
        model.onRealtimeData27();
        model.onRealtimeData27();
        assertEquals(InterphoneProbe.ContinuousData36TxModel.Phase.READY,
                model.phase());
        return model;
    }

    @Test
    public void v204FrozenAssetLedgerAndGroupingAreExact() throws Exception {
        byte[] source = fullFrozenAmbeAsset();
        byte[] packaged = Files.readAllBytes(workspacePath(
                "app/src/main/assets/v090_chan_d_244units.bin"));
        InterphoneProbe.FrozenAmbeAsset asset =
                InterphoneProbe.FrozenAmbeAsset.require(source);
        assertEquals(6588, source.length);
        assertEquals(732, asset.frameCount());
        assertEquals(183, asset.data36Count());
        assertEquals(InterphoneProbe.REAL_RX_CHAN_D_STREAM_SHA256,
                asset.sha256());
        assertArrayEquals(source, packaged);
        for (int unit = 0; unit < asset.data36Count(); unit++) {
            assertArrayEquals(java.util.Arrays.copyOfRange(source,
                    unit * 36, unit * 36 + 36), asset.data36(unit));
            for (int frame = 0; frame < 4; frame++) {
                assertArrayEquals(asset.frame9(unit * 4 + frame),
                        java.util.Arrays.copyOfRange(asset.data36(unit),
                                frame * 9, frame * 9 + 9));
            }
        }
        byte original = source[0];
        source[0] ^= 0x55;
        assertEquals(original, asset.frame9(0)[0]);
    }

    @Test
    public void v204FrozenAssetRejectsLengthHashAndBounds() throws Exception {
        byte[] source = fullFrozenAmbeAsset();
        try {
            InterphoneProbe.FrozenAmbeAsset.require(
                    java.util.Arrays.copyOf(source, source.length - 1));
            fail("截断资产必须失败");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("6588"));
        }
        source[100] ^= 1;
        try {
            InterphoneProbe.FrozenAmbeAsset.require(source);
            fail("哈希不符资产必须失败");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("SHA-256"));
        }
        InterphoneProbe.FrozenAmbeAsset asset =
                InterphoneProbe.FrozenAmbeAsset.require(fullFrozenAmbeAsset());
        try {
            asset.data36(183);
            fail("data36越界必须失败");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("越界"));
        }
        try {
            asset.frame9(732);
            fail("AMBE帧越界必须失败");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("越界"));
        }
    }

    @Test
    public void v204PrivacyChunkingIsInvariantAcrossThreeData36Units()
            throws Exception {
        InterphoneProbe.FrozenAmbeAsset asset =
                InterphoneProbe.FrozenAmbeAsset.require(fullFrozenAmbeAsset());
        byte[] source108 = asset.range(0, 108);
        byte[] runtime = measuredPrivacyRuntime14();
        InterphoneProbe.DmrPrivacyStreamContext whole =
                InterphoneProbe.DmrPrivacyStreamContext
                        .fromMeasuredRuntime(runtime);
        byte[] encryptedWhole = whole.encrypt(source108);
        String wholeState = whole.stateSummary();

        InterphoneProbe.DmrPrivacyStreamContext chunked =
                InterphoneProbe.DmrPrivacyStreamContext
                        .fromMeasuredRuntime(runtime);
        byte[] encryptedChunked = new byte[108];
        for (int unit = 0; unit < 3; unit++) {
            System.arraycopy(chunked.encryptData36(asset.data36(unit)), 0,
                    encryptedChunked, unit * 36, 36);
        }
        assertArrayEquals(encryptedWhole, encryptedChunked);
        assertEquals(wholeState, chunked.stateSummary());
        assertEquals(12, chunked.globalFrameIndex());
        assertEquals(12, chunked.frameInSuperframe());
        assertEquals(3, chunked.completedData36Units());
    }

    @Test
    public void v204FirstUnitMatchesV203AcceptedPrivacyVector()
            throws Exception {
        InterphoneProbe.FrozenAmbeAsset asset =
                InterphoneProbe.FrozenAmbeAsset.require(fullFrozenAmbeAsset());
        byte[] runtime = measuredPrivacyRuntime14();
        byte[] continuous = InterphoneProbe.DmrPrivacyStreamContext
                .fromMeasuredRuntime(runtime).encryptData36(asset.data36(0));
        byte[] legacyRequest =
                InterphoneProbe.createRealtimeRelayEncryptedData36Frame(
                        asset.data36(0), runtime);
        assertArrayEquals(java.util.Arrays.copyOfRange(legacyRequest, 8, 44),
                continuous);
        InterphoneProbe.ContinuousData36TxModel model =
                readyContinuousModel(1);
        InterphoneProbe.ContinuousData36Evidence first =
                model.writeNext(1000);
        assertArrayEquals(InterphoneProbe.requireHostEncryptedExternalTxFrameUnit0(),
                first.request44);
    }

    @Test
    public void v204PrivacyCrossesFrame18InsideFifthData36() throws Exception {
        InterphoneProbe.FrozenAmbeAsset asset =
                InterphoneProbe.FrozenAmbeAsset.require(fullFrozenAmbeAsset());
        byte[] runtime = measuredPrivacyRuntime14();
        byte[] initialMi = java.util.Arrays.copyOfRange(runtime, 10, 14);
        byte[] evolvedMi = InterphoneProbe.evolveDmrPrivacyMi(initialMi);
        InterphoneProbe.DmrPrivacyStreamContext context =
                InterphoneProbe.DmrPrivacyStreamContext
                        .fromMeasuredRuntime(runtime);
        context.encrypt(asset.range(0, 16 * 9));
        assertEquals(16, context.frameInSuperframe());
        assertArrayEquals(initialMi, context.currentMi());
        byte[] firstHalf = context.encrypt(asset.range(16 * 9, 2 * 9));
        assertEquals(0, context.frameInSuperframe());
        assertArrayEquals(evolvedMi, context.currentMi());
        byte[] secondHalf = context.encrypt(asset.range(18 * 9, 2 * 9));
        assertEquals(2, context.frameInSuperframe());
        assertArrayEquals(evolvedMi, context.currentMi());

        InterphoneProbe.DmrPrivacyStreamContext direct =
                InterphoneProbe.DmrPrivacyStreamContext
                        .fromMeasuredRuntime(runtime);
        byte[] firstFour = direct.encrypt(asset.range(0, 16 * 9));
        byte[] fifth = direct.encryptData36(asset.data36(4));
        assertArrayEquals(firstHalf,
                java.util.Arrays.copyOfRange(fifth, 0, 18));
        assertArrayEquals(secondHalf,
                java.util.Arrays.copyOfRange(fifth, 18, 36));
        assertEquals(20, direct.globalFrameIndex());
        assertEquals(2, direct.frameInSuperframe());
        assertEquals(144, firstFour.length);
    }

    @Test
    public void v204PrivacyRejectsPerUnitResetFrameShiftAndLateEntryShift()
            throws Exception {
        InterphoneProbe.FrozenAmbeAsset asset =
                InterphoneProbe.FrozenAmbeAsset.require(fullFrozenAmbeAsset());
        byte[] runtime = measuredPrivacyRuntime14();
        byte[] key = java.util.Arrays.copyOfRange(runtime, 5, 10);
        byte[] mi = java.util.Arrays.copyOfRange(runtime, 10, 14);
        byte[] plain = asset.range(0, 108);
        byte[] correct = new InterphoneProbe.DmrPrivacyStreamContext(key, mi)
                .encrypt(plain);

        byte[] resetEachUnit = new byte[108];
        for (int unit = 0; unit < 3; unit++) {
            byte[] wrong = new InterphoneProbe.DmrPrivacyStreamContext(key, mi)
                    .encrypt(asset.data36(unit));
            System.arraycopy(wrong, 0, resetEachUnit, unit * 36, 36);
        }
        assertFalse(java.util.Arrays.equals(correct, resetEachUnit));
        try {
            InterphoneProbe.requireMatchingContinuousPrivacy(plain,
                    resetEachUnit, key, mi);
            fail("每单元重置RC4必须被拒绝");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("不一致"));
        }

        byte[] frameShift = correct.clone();
        System.arraycopy(correct, 9, frameShift, 0, correct.length - 9);
        try {
            InterphoneProbe.requireMatchingContinuousPrivacy(plain,
                    frameShift, key, mi);
            fail("错一帧密文必须被拒绝");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("不一致"));
        }

        byte[] lateEntryShift = correct.clone();
        InterphoneProbe.setDmrC3HighNibble(lateEntryShift, 0,
                (InterphoneProbe.createDmrLateEntryFragments(
                        InterphoneProbe.evolveDmrPrivacyMi(mi))[1] ^ 0x0f)
                        & 0x0f);
        try {
            InterphoneProbe.requireMatchingContinuousPrivacy(plain,
                    lateEntryShift, key, mi);
            fail("Late Entry片段错位必须被拒绝");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("不一致"));
        }
    }

    @Test
    public void v204ContinuousCreditModelPassesOneThreeFiveTwentyFiveSixty()
            throws Exception {
        byte[] credit = hpiWireFrame(0x20, new byte[] {0x01, 0x00});
        for (int unitCount : new int[] {1, 3, 5, 25, 60}) {
            InterphoneProbe.ContinuousData36TxModel model =
                    readyContinuousModel(unitCount);
            long now = 1000;
            for (int unit = 0; unit < unitCount; unit++) {
                InterphoneProbe.ContinuousData36Evidence record =
                        model.writeNext(now);
                assertEquals(unit, record.unitIndex);
                assertEquals(unit * 4, record.firstGlobalFrame);
                assertEquals(44, record.request44.length);
                assertTrue(InterphoneProbe.looksLikeExternalData36Frame(
                        record.request44));
                assertTrue(model.acceptCredit(unit + 1L, credit, now + 7));
                now += 20;
            }
            assertEquals(unitCount, model.writtenUnitCount());
            assertEquals(unitCount, model.acceptedCreditCount());
            assertEquals(InterphoneProbe.ContinuousData36TxModel.Phase
                    .RECOVERY_REQUIRED, model.phase());
            assertTrue(model.finishRecovery(true));
            assertEquals(InterphoneProbe.ContinuousData36TxModel.Phase.RECOVERED,
                    model.phase());
            assertEquals(unitCount, model.evidence().size());
        }
    }

    @Test
    public void v204ContinuousCreditModelFailsClosedOnCreditDefects()
            throws Exception {
        byte[] credit = hpiWireFrame(0x20, new byte[] {0x01, 0x00});
        byte[] vlc = hpiWireFrame(0x20, new byte[] {0x43});

        InterphoneProbe.ContinuousData36TxModel model = readyContinuousModel(3);
        model.writeNext(100);
        assertFalse(model.acceptTimeout());
        assertEquals(InterphoneProbe.ContinuousData36TxModel.Phase.FAILED,
                model.phase());

        model = readyContinuousModel(3);
        model.writeNext(100);
        assertTrue(model.acceptCredit(1, credit, 110));
        assertFalse(model.acceptCredit(1, credit, 111));
        assertEquals(InterphoneProbe.ContinuousData36TxModel.Phase.FAILED,
                model.phase());

        model = new InterphoneProbe.ContinuousData36TxModel(
                InterphoneProbe.FrozenAmbeAsset.require(fullFrozenAmbeAsset()),
                measuredPrivacyRuntime14(), 0, 1);
        assertFalse(model.acceptCredit(1, credit, 10));
        assertEquals(InterphoneProbe.ContinuousData36TxModel.Phase.FAILED,
                model.phase());

        model = readyContinuousModel(1);
        model.writeNext(100);
        assertFalse(model.acceptCredit(1, vlc, 110));
        assertEquals(InterphoneProbe.ContinuousData36TxModel.Phase.FAILED,
                model.phase());

        model = readyContinuousModel(1);
        model.writeNext(100);
        byte[] garbage = java.util.Arrays.copyOf(credit, credit.length + 1);
        garbage[garbage.length - 1] = 0x55;
        assertFalse(model.acceptCredit(1, garbage, 110));
        assertEquals(InterphoneProbe.ContinuousData36TxModel.Phase.FAILED,
                model.phase());
    }

    @Test
    public void v204ContinuousCreditModelRejectsParallelBoundsOrderAndRecovery()
            throws Exception {
        byte[] credit = hpiWireFrame(0x20, new byte[] {0x01, 0x00});
        InterphoneProbe.ContinuousData36TxModel model = readyContinuousModel(2);
        model.writeNext(100);
        try {
            model.writeNext(101);
            fail("未确认时第二写出必须失败");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("禁止"));
        }
        assertEquals(InterphoneProbe.ContinuousData36TxModel.Phase.FAILED,
                model.phase());

        try {
            new InterphoneProbe.ContinuousData36TxModel(
                    InterphoneProbe.FrozenAmbeAsset.require(
                            fullFrozenAmbeAsset()), measuredPrivacyRuntime14(),
                    182, 2);
            fail("资产越界必须失败");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("无效"));
        }

        model = readyContinuousModel(2);
        model.writeNext(100);
        assertTrue(model.acceptCredit(2, credit, 110));
        model.writeNext(120);
        assertFalse(model.acceptCredit(1, credit, 130));
        assertEquals(InterphoneProbe.ContinuousData36TxModel.Phase.FAILED,
                model.phase());

        model = readyContinuousModel(1);
        model.writeNext(100);
        assertFalse(model.acceptCredit(1, credit, 99));
        assertEquals(InterphoneProbe.ContinuousData36TxModel.Phase.FAILED,
                model.phase());

        model = readyContinuousModel(1);
        model.writeNext(100);
        assertTrue(model.acceptCredit(1, credit, 110));
        try {
            model.writeNext(120);
            fail("超过单元上限必须失败");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("禁止"));
        }

        model = readyContinuousModel(1);
        model.writeNext(100);
        assertTrue(model.acceptCredit(1, credit, 110));
        assertFalse(model.finishRecovery(false));
        assertEquals(InterphoneProbe.ContinuousData36TxModel.Phase.FAILED,
                model.phase());
    }

    @Test
    public void packFourAmbe9FromType20StreamIsMechanicalCandidateOnly() {
        // 两帧 type0x20/27：每帧 8 字节头后 27 字节载荷；打包取 27+9=36。
        byte[] unitA = new byte[27];
        byte[] unitB = new byte[27];
        for (int i = 0; i < 27; i++) {
            unitA[i] = (byte) (0x10 + i);
            unitB[i] = (byte) (0x40 + i);
        }
        byte[] frameA = hpiWireFrame(0x20, concatAmbePayload(unitA));
        byte[] frameB = hpiWireFrame(0x20, concatAmbePayload(unitB));
        byte[] stream = new byte[frameA.length + frameB.length];
        System.arraycopy(frameA, 0, stream, 0, frameA.length);
        System.arraycopy(frameB, 0, stream, frameA.length, frameB.length);

        byte[] packed = InterphoneProbe.packFourAmbe9FromType20Stream(stream);
        assertNotNull(packed);
        assertEquals(36, packed.length);
        byte[] expected = new byte[36];
        System.arraycopy(unitA, 0, expected, 0, 27);
        System.arraycopy(unitB, 0, expected, 27, 9);
        assertArrayEquals(expected, packed);

        // 不足两帧不得伪装 36B 产物。
        assertNull(InterphoneProbe.packFourAmbe9FromType20Stream(frameA));
        assertNull(InterphoneProbe.packFourAmbe9FromType20Stream(new byte[0]));
        assertNull(InterphoneProbe.packFourAmbe9FromType20Stream(null));
    }

    @Test
    public void zeroAndToneWireShaAreDistinctForDiffBaseline() throws Exception {
        byte[] zeroPcm = new byte[1280];
        byte[] tonePcm = InterphoneProbe.createTonePcm8kS16le(800, 4000, 640);
        byte[] zeroWire = InterphoneProbe.createAnalogVoiceInPcmFrame(zeroPcm, 0);
        byte[] toneWire = InterphoneProbe.createAnalogVoiceInPcmFrame(tonePcm, 0);
        Method sha = InterphoneProbe.class.getDeclaredMethod("sha256", byte[].class);
        sha.setAccessible(true);
        String zeroSha = (String) sha.invoke(null, (Object) zeroWire);
        String toneSha = (String) sha.invoke(null, (Object) toneWire);
        assertEquals(
                "ac050cef21b82c75cf5422d79e4d579d4972d65584d5def189f7e64fd5313ea8",
                zeroSha);
        assertEquals(
                "0cca15f8390d005b3b8ce3cc2d13e0d44372e070fa9cc37ce7443a542f572123",
                toneSha);
        assertFalse(zeroSha.equals(toneSha));
    }

    /** 厂家 PCM 输入短信用：载荷 0/1，长度 1/2；不限制 packet type。 */
    @Test
    public void parseHpiEvidenceAcceptsVendorPcmInputCreditAnyPacketType() {
        // 历史 data36 后短信用：84 a9 61 00 02 03 01 00
        byte[] credit = hexBytes("84 a9 61 00 02 03 01 00");
        InterphoneProbe.HpiEvidenceParse p = InterphoneProbe.parseHpiEvidence(credit);
        assertEquals(1, p.inputCredits);
        assertEquals(0, p.malformedEvents);
        assertEquals(0, p.controlAcks);
        assertEquals(0, p.candidate27);
    }

    /** type20 之后仍有 credit 时必须检出（旧 isShortPcmBackpressure 会漏检）。 */
    @Test
    public void parseHpiEvidenceFindsCreditAfterType20Frame() {
        byte[] type20 = hexBytes(
                "84 a9 61 00 1d 20 01 1b"
                        + " 00 00 00 00 00 00 00 00 00 00"
                        + " 00 00 00 00 00 00 00 00 00 00"
                        + " 00 00 00 00 00 00 00 00");
        // 补齐 27 字节 AMBE + 零补位使 wire 偶数
        byte[] ambe = new byte[27];
        System.arraycopy(ambe, 0, type20, 8, 27);
        // rebuild proper type20 frame with padding 00
        type20 = new byte[36];
        type20[0] = (byte) 0x84;
        type20[1] = (byte) 0xa9;
        type20[2] = 0x61;
        type20[3] = 0x00;
        type20[4] = 0x1d;
        type20[5] = 0x20;
        type20[6] = 0x01;
        type20[7] = 0x1b;
        // remaining 27 ambe zeros + pad 0 at end of declared 29 body? body=01 1b +27 =29, +pad0 =30, wire=36
        type20[35] = 0x00;
        byte[] credit = hexBytes("84 a9 61 00 02 03 01 00");
        byte[] stream = new byte[type20.length + credit.length];
        System.arraycopy(type20, 0, stream, 0, type20.length);
        System.arraycopy(credit, 0, stream, type20.length, credit.length);
        InterphoneProbe.HpiEvidenceParse p = InterphoneProbe.parseHpiEvidence(stream);
        assertEquals(1, p.candidate27);
        assertEquals(1, p.inputCredits);
        assertEquals(0, p.malformedEvents);
    }

    /** v2.89 输出窗尾：84 a9 61 ff 1d... 必须判畸形。 */
    @Test
    public void parseHpiEvidenceFlagsV289FfInjectedTailAsMalformed() {
        byte[] twoClean = hexBytes(
                "84 a9 61 00 1d 20 01 1b fd c4 82 55 54 11 3c c7 fb ce e7 a2 75 77 14 4c a7 bb df e6 a2 75 77 14 7c 87 ab 00"
                        + " 84 a9 61 00 1d 20 01 1b cc f7 80 55 55 20 3c c3 af df e7 a2 75 77 14 4c a7 bb df e6 a2 75 77 14 7c 87 ab 00");
        byte[] badTail = hexBytes(
                "84 a9 61 ff 1d 20 01 1b ff ec c5 82 55 ff 54 ff 3c c7 ff fb ff e6 a2 ff 75 ff 14 5c ff a7 ff ec d5 82 57 ff 24 6c c3 af ff 00");
        byte[] stream = new byte[twoClean.length + badTail.length];
        System.arraycopy(twoClean, 0, stream, 0, twoClean.length);
        System.arraycopy(badTail, 0, stream, twoClean.length, badTail.length);
        InterphoneProbe.HpiEvidenceParse p = InterphoneProbe.parseHpiEvidence(stream);
        assertEquals(2, p.candidate27);
        assertTrue(p.malformedEvents > 0);
        assertEquals(0, p.inputCredits);
    }

    /** v2.90 输出：同步头第二字节 ff，必须判畸形。 */
    @Test
    public void parseHpiEvidenceFlagsV290BrokenSyncAsMalformed() {
        byte[] bad = hexBytes(
                "84 ff a9 61 00 1d ff 20 01 1b cc ff e7 80 55 55 ff 20 3c c3 af df e7 a2 ff 77 14 5c a7 bb ce ff f6 ff 75 77 14 6c ff 87 ab 00");
        InterphoneProbe.HpiEvidenceParse p = InterphoneProbe.parseHpiEvidence(bad);
        assertTrue(p.malformedEvents > 0);
        assertEquals(0, p.inputCredits);
        assertEquals(0, p.candidate27);
    }

    /** 首载荷非 0/1 的短帧不得记为 credit。 */
    @Test
    public void parseHpiEvidenceRejectsShortFrameWithNonCreditPayload() {
        byte[] notCredit = hexBytes("84 a9 61 00 02 03 02 00");
        InterphoneProbe.HpiEvidenceParse p = InterphoneProbe.parseHpiEvidence(notCredit);
        assertEquals(0, p.inputCredits);
        assertEquals(1, p.asyncEvents);
        assertEquals(0, p.malformedEvents);
    }

    /** v2.96+辅助桩的四阶段状态必须可拆分并满足准备、恢复合同。 */
    @Test
    public void stockHpiStateSnapshotsAcceptAuditedPrepareAndExactRestore() {
        byte[] before = new byte[InterphoneProbe.STOCK_HPI_RX_STATE_LENGTH];
        for (int index = 0; index < before.length; index++) {
            before[index] = (byte) (0x40 + index);
        }
        before[0x06] = 0x05;
        byte[] prepared = InterphoneProbe.prepareStockHpiReceiverState(before);
        assertEquals(0x05, prepared[0x06] & 0xff);
        byte[] afterRead = prepared.clone();
        afterRead[0x02] = 0x02;
        afterRead[0x04] = 0x24;

        byte[] snapshots = new byte[
                InterphoneProbe.STOCK_HPI_STATE_SNAPSHOTS_LENGTH];
        System.arraycopy(before, 0, snapshots, 0, before.length);
        System.arraycopy(prepared, 0, snapshots, before.length,
                prepared.length);
        System.arraycopy(afterRead, 0, snapshots, before.length * 2,
                afterRead.length);
        System.arraycopy(before, 0, snapshots, before.length * 3,
                before.length);

        byte[][] split = InterphoneProbe.splitStockHpiStateSnapshots(snapshots);
        assertArrayEquals(before, split[0]);
        assertArrayEquals(prepared, split[1]);
        assertArrayEquals(afterRead, split[2]);
        assertArrayEquals(before, split[3]);
        assertTrue(InterphoneProbe.stockHpiStateSnapshotsValid(snapshots));
    }

    /** 准备字段或恢复字节任一错误都必须失败关闭。 */
    @Test
    public void stockHpiStateSnapshotsRejectBadPrepareOrRestore() {
        byte[] before = new byte[InterphoneProbe.STOCK_HPI_RX_STATE_LENGTH];
        before[0x06] = 0x05;
        byte[] prepared = InterphoneProbe.prepareStockHpiReceiverState(before);
        byte[] snapshots = new byte[
                InterphoneProbe.STOCK_HPI_STATE_SNAPSHOTS_LENGTH];
        System.arraycopy(before, 0, snapshots, 0, before.length);
        System.arraycopy(prepared, 0, snapshots, before.length,
                prepared.length);
        System.arraycopy(prepared, 0, snapshots, before.length * 2,
                prepared.length);
        System.arraycopy(before, 0, snapshots, before.length * 3,
                before.length);

        snapshots[InterphoneProbe.STOCK_HPI_STATE_SNAPSHOT_LENGTH + 0x06] = 0;
        assertFalse(InterphoneProbe.stockHpiStateSnapshotsValid(snapshots));
        snapshots[InterphoneProbe.STOCK_HPI_STATE_SNAPSHOT_LENGTH + 0x06] = 0x05;
        snapshots[InterphoneProbe.STOCK_HPI_STATE_SNAPSHOT_LENGTH * 3] = 1;
        assertFalse(InterphoneProbe.stockHpiStateSnapshotsValid(snapshots));
        assertFalse(InterphoneProbe.stockHpiStateSnapshotsValid(new byte[20]));
    }

    /** v2.96+主流程不得再经文本面写入或强制恢复固件活跃HPI状态。 */
    @Test
    public void v296MainFlowLeavesActiveStockHpiStateReadOnly()
            throws Exception {
        String source = new String(Files.readAllBytes(workspacePath(
                "app/src/main/java/net/elfradio/h13interphoneprobe/InterphoneProbe.java")),
                StandardCharsets.UTF_8).replace("\r\n", "\n");
        int start = source.indexOf(
                "private ProbeResult runFrameAdapterPcmNoRf(boolean completeFrame,");
        int end = source.indexOf(
                "static boolean frameAdapterTruncatedTelemetryValid", start);
        assertTrue(start >= 0 && end > start);
        String method = source.substring(start, end);
        assertTrue(method.contains("STOCK_HPI_TRANSACTION_CODE_ADDRESS + offset"));
        assertTrue(method.contains("STOCK_HPI_STATE_SNAPSHOTS_ADDRESS + offset"));
        assertFalse(method.contains(
                "writeMemoryByte(input, output, STOCK_HPI_RX_STATE_ADDRESS"));
        assertFalse(method.contains(
                "restoreWords(input, output, STOCK_HPI_RX_STATE_ADDRESS"));

        String host = new String(Files.readAllBytes(workspacePath(
                "../h13_radio/tools/h13_probe_session.ps1")),
                StandardCharsets.UTF_8);
        assertTrue(host.contains("$completeRangeLength = 2872"));
        assertTrue(host.contains("$completeRangeLength -eq 3144"));
        assertTrue(host.contains("$completeRangeLength -eq 4920"));
        assertTrue(host.contains("$completeRangeLength -eq 4936"));
        assertTrue(host.contains("h13_stock_hpi_transaction_code_*.bin"));
        assertTrue(host.contains("h13_stock_hpi_state_before_*.bin"));
        assertTrue(host.contains("h13_stock_hpi_state_prepared_*.bin"));
        assertTrue(host.contains("h13_stock_hpi_state_after_read_*.bin"));
        assertTrue(host.contains("h13_stock_hpi_state_after_restore_*.bin"));
        assertTrue(host.contains("Test-StockHpiStateSnapshotContract"));
        assertFalse(host.contains(
                "$stockReadEvidence = $completeExpectLen -eq 1268"));
    }

    /** v2.97只有确认ARM入口已运行且剩余控制窗充足，才允许置bridge。 */
    @Test
    public void v297PreBridgeArmConfirmationFailsClosedAndPrecedesBridgeWrite()
            throws Exception {
        byte[] vector = le32Bytes(InterphoneProbe.FRAME_ADAPTER_ARM_ENTRY);
        byte[] bridge = new byte[] {0};
        byte[] delay = le32Bytes(480);
        assertTrue(InterphoneProbe.preBridgeArmConfirmationValid(
                vector, bridge, delay, 500, 430));
        assertFalse(InterphoneProbe.preBridgeArmConfirmationValid(
                vector, bridge, le32Bytes(500), 500, 430));
        assertFalse(InterphoneProbe.preBridgeArmConfirmationValid(
                vector, bridge, le32Bytes(429), 500, 430));
        assertFalse(InterphoneProbe.preBridgeArmConfirmationValid(
                le32Bytes(InterphoneProbe.FRAME_ADAPTER_SYSTICK_ENTRY),
                bridge, delay, 500, 430));
        assertFalse(InterphoneProbe.preBridgeArmConfirmationValid(
                vector, new byte[] {1}, delay, 500, 430));

        String source = new String(Files.readAllBytes(workspacePath(
                "app/src/main/java/net/elfradio/h13interphoneprobe/InterphoneProbe.java")),
                StandardCharsets.UTF_8);
        int start = source.indexOf("armedAtMs = SystemClock.elapsedRealtime();");
        int end = source.indexOf("HardwareEncoderNoInputModel controlModel", start);
        String armBlock = source.substring(start, end);
        int vectorWrite = armBlock.indexOf(
                "writeMemoryWord(s.input, s.output, RAM_VECTOR_SYSTICK");
        int wait = armBlock.indexOf("FRAME_ADAPTER_PREBRIDGE_ARM_WAIT_MS");
        int vectorRead = armBlock.indexOf(
                "\"frame_adapter_prebridge_systick\"");
        int bridgeRead = armBlock.indexOf(
                "\"frame_adapter_prebridge_bridge\"");
        int delayRead = armBlock.indexOf(
                "\"frame_adapter_prebridge_arm_delay\"");
        int confirmation = armBlock.indexOf(
                "preBridgeArmConfirmationValid(");
        int bridgeWrite = armBlock.indexOf(
                "writeMemoryByte(s.input, s.output, UART_HPI_BRIDGE_FLAG_ADDRESS, 1)");
        assertTrue(vectorWrite >= 0 && wait > vectorWrite
                && vectorRead > wait && bridgeRead > vectorRead
                && delayRead > bridgeRead && confirmation > delayRead
                && bridgeWrite > confirmation);

        String host = new String(Files.readAllBytes(workspacePath(
                "../h13_radio/tools/h13_probe_session.ps1")),
                StandardCharsets.UTF_8);
        for (String token : new String[] {
                "2.97-prebridge-arm-confirm-step5-no-rf",
                "sram_preflight_frame_adapter_prebridge_systick_*.bin",
                "sram_preflight_frame_adapter_prebridge_bridge_*.bin",
                "sram_preflight_frame_adapter_prebridge_arm_delay_*.bin",
                "prebridge_arm_confirmed", "prebridge_systick_vector",
                "prebridge_bridge_flag", "prebridge_arm_delay_initial",
                "prebridge_arm_delay_observed",
                "prebridge_arm_delay_minimum"
        }) {
            assertTrue("宿主v2.97证据门缺少：" + token,
                    host.contains(token));
        }
    }

    /** v3.00必须实测退桥波特率，确认文本面后才可取证或恢复。 */
    @Test
    public void v300TimeoutSnapshotUsesConfirmedPostExitBaudAndHostGate()
            throws Exception {
        String assembly = new String(Files.readAllBytes(workspacePath(
                "../h13_radio/tools/h13_mcu_frame_hpi_adapter.S")),
                StandardCharsets.UTF_8);
        int commitBlock = assembly.indexOf("timeout_commit:");
        int snapshotCall = assembly.indexOf(
                "bl snapshot_runtime_before_baud_restore", commitBlock);
        int copyRoutine = assembly.indexOf(
                "snapshot_runtime_before_baud_restore:");
        int snapshotCommit = assembly.indexOf(
                "str r0, [r4]", copyRoutine);
        String systickBlock = assembly.substring(
                assembly.indexOf("h13_frame_hpi_systick:"),
                assembly.indexOf(".size h13_frame_hpi_systick"));
        assertTrue(commitBlock >= 0 && snapshotCall > commitBlock
                && copyRoutine > 0
                && snapshotCommit > copyRoutine);
        assertFalse(systickBlock.contains("STOCK_SET_BAUD"));
        String releaseBlock = systickBlock.substring(
                systickBlock.indexOf("systick_hold_release_now:"),
                systickBlock.indexOf("systick_timeout_expired:"));
        assertFalse(releaseBlock.contains("baud_57600_systick"));
        assertFalse(releaseBlock.contains("set_baud_systick"));
        assertFalse(releaseBlock.contains("blx r3"));
        for (String token : new String[] {
                "TIMEOUT_SNAPSHOT_TELEMETRY", "TIMEOUT_SNAPSHOT_BUFFER",
                "TIMEOUT_SNAPSHOT_STAGE", "TIMEOUT_SNAPSHOT_RESPONSE",
                "TIMEOUT_SNAPSHOT_HPI_STATE", "TIMEOUT_SNAPSHOT_COMMIT",
                "TIMEOUT_SNAPSHOT_MAGIC"
        }) {
            assertTrue("汇编缺少整体快照字段：" + token,
                    assembly.contains(token));
        }

        String source = new String(Files.readAllBytes(workspacePath(
                "app/src/main/java/net/elfradio/h13interphoneprobe/InterphoneProbe.java")),
                StandardCharsets.UTF_8);
        assertTrue(source.contains("timeoutSnapshotValid"));
        assertTrue(source.contains("frame_adapter_timeout_snapshot_commit"));
        assertTrue(source.contains("adapterOutcome = adapterOutcome"
                + " && timeoutSnapshotValid"));
        assertTrue(source.contains("mcuBaud = BAUD_RATE_230400"));
        assertTrue(source.contains("restore-57600-after-evidence"));
        assertTrue(source.contains("postExitTextFaceConfirmed"));
        assertTrue(source.contains("退桥230400探测失败，改探测57600"));
        assertTrue(source.contains("退桥文本波特率未经响应确认，禁止盲写"));
        assertFalse(source.contains("清粘性遥测窗，避免残留影响后续会话"));

        String host = new String(Files.readAllBytes(workspacePath(
                "../h13_radio/tools/h13_probe_session.ps1")),
                StandardCharsets.UTF_8);
        for (String token : new String[] {
                "3.00-postexit-baud-probe-step5-no-rf", "4920",
                "sram_preflight_frame_adapter_timeout_snapshot_commit_*.bin",
                "0x50414e53",
                "sram_preflight_frame_adapter_posttimeout_live_buffer_*.bin",
                "sram_preflight_frame_adapter_posttimeout_live_stage_snapshot_*.bin",
                "sram_preflight_frame_adapter_posttimeout_live_hpi_response_state_*.bin",
                "sram_preflight_frame_adapter_posttimeout_live_hpi_state_snapshots_*.bin"
        }) {
            assertTrue("宿主v2.98证据门缺少：" + token,
                    host.contains(token));
        }
    }

    /** v3.01必须在原厂清理前保持证据，并具备显式和自动释放闭环。 */
    @Test
    public void v301EvidenceHoldPrecedesStockCleanupAndIsHostGated()
            throws Exception {
        String assembly = new String(Files.readAllBytes(workspacePath(
                "../h13_radio/tools/h13_mcu_frame_hpi_adapter.S")),
                StandardCharsets.UTF_8);
        String timeoutBlock = assembly.substring(
                assembly.indexOf("systick_timeout_expired:"),
                assembly.indexOf("systick_tail:"));
        int snapshot = timeoutBlock.indexOf(
                "bl snapshot_runtime_before_baud_restore");
        int active = timeoutBlock.indexOf(
                "ldr r1, evidence_hold_active_magic_systick");
        int exceptionReturn = timeoutBlock.indexOf(
                "b systick_exception_return");
        assertTrue(snapshot >= 0 && active > snapshot
                && exceptionReturn > active);
        assertFalse(timeoutBlock.contains(
                "str r1, [r0]\n    ldr r0, pendsv_vector_word\n"
                        + "    ldr r1, [r4, #T_ORIGINAL_PENDSV]\n"
                        + "    str r1, [r0]\n    ldr r0, systick_vector"));
        for (String token : new String[] {
                "EVIDENCE_HOLD_RELEASE", "EVIDENCE_HOLD_REMAINING",
                "EVIDENCE_HOLD_RELEASE_DELAY", "EVIDENCE_HOLD_STATE",
                "EVIDENCE_HOLD_RELEASE_MAGIC", "EVIDENCE_HOLD_ACTIVE_MAGIC",
                "systick_hold_release_requested:",
                "systick_hold_release_now:", "systick_exception_return:"
        }) {
            assertTrue("汇编缺少证据保持闭环：" + token,
                    assembly.contains(token));
        }

        String source = new String(Files.readAllBytes(workspacePath(
                "app/src/main/java/net/elfradio/h13interphoneprobe/InterphoneProbe.java")),
                StandardCharsets.UTF_8);
        int postRange = source.indexOf(
                "byte[] postRange = readMemoryChunkedEvidence");
        int release = source.indexOf(
                "FRAME_ADAPTER_EVIDENCE_HOLD_RELEASE_MAGIC", postRange);
        int restore = source.indexOf(
                "restoreWords(s.input, s.output, FRAME_ADAPTER_RANGE_ADDRESS",
                release);
        assertTrue(postRange >= 0 && release > postRange && restore > release);
        assertTrue(source.contains(
                "frameAdapterCompleteOutcomeValidDuringEvidenceHold"));
        assertTrue(source.contains("evidence_hold_valid"));
        assertTrue(source.contains("证据保持释放后实测文本波特率"));

        String host = new String(Files.readAllBytes(workspacePath(
                "../h13_radio/tools/h13_probe_session.ps1")),
                StandardCharsets.UTF_8);
        for (String token : new String[] {
                "3.01-evidence-hold-step5-no-rf", "1464", "0x20002067",
                "Test-ExactlyOneTimestampedFile",
                "sram_preflight_frame_adapter_evidence_hold_release_",
                "sram_preflight_frame_adapter_evidence_hold_remaining_",
                "sram_preflight_frame_adapter_evidence_hold_release_delay_",
                "sram_preflight_frame_adapter_evidence_hold_state_",
                "h13_frame_adapter_evidence_release_response_*.bin",
                "h13_frame_adapter_postrelease_late_57600_*.bin",
                "0x444c4f48"
        }) {
            assertTrue("宿主v3.01证据门缺少：" + token,
                    host.contains(token));
        }
    }

    /** v3.06保持窗必须直接在230400首条memread取证，禁止前置命令清场。 */
    @Test
    public void v306ReadsEvidenceDirectlyAt230400BeforeOtherTextCommands()
            throws Exception {
        String source = new String(Files.readAllBytes(workspacePath(
                "app/src/main/java/net/elfradio/h13interphoneprobe/InterphoneProbe.java")),
                StandardCharsets.UTF_8).replace("\r\n", "\n");
        int start = source.indexOf("// v3.06：保持态现在阻止原厂SysTick尾链");
        int end = source.indexOf("byte[] evidenceHoldRelease =", start);
        assertTrue(start >= 0 && end > start);
        String postExitBlock = source.substring(start, end);
        int direct230400 = postExitBlock.indexOf(
                "openSerialLocked(s.device, BAUD_RATE_230400, s.report);");
        assertTrue(direct230400 >= 0);
        assertTrue(postExitBlock.contains("if (guardedThreadProfile)"));
        String completeToneBlock = postExitBlock.substring(
                postExitBlock.indexOf("if (guardedThreadProfile)"),
                postExitBlock.indexOf("} else {"));
        assertFalse(completeToneBlock.contains("resyncTextFace"));
        assertFalse(completeToneBlock.contains("LOG_ENABLE_COMMAND"));
        assertFalse(completeToneBlock.contains("AT+DMOCONNECT"));
        assertFalse(postExitBlock.contains("等待证据保持自动释放毫秒"));
        assertTrue(source.contains("boolean evidenceHoldValid = guardedThreadProfile\n"
                + "                    && s.mcuBaud == BAUD_RATE_230400"));
        assertTrue(source.contains("取证完成但证据保持态或230400文本面丢失"));
        assertTrue(source.contains("保持窗首条memread证明230400文本面"));
        assertTrue(source.indexOf("byte[] evidenceHoldRelease =", start)
                < source.indexOf("byte[] stickyTelemetry =", start));

        String host = new String(Files.readAllBytes(workspacePath(
                "../h13_radio/tools/h13_probe_session.ps1")),
                StandardCharsets.UTF_8);
        assertTrue(host.contains("$stockReadV306"));
        assertTrue(host.contains(
                "3.06-timeout-hold-reinit-step5-no-rf"));
        assertTrue(host.contains(
                "sram_preflight_frame_adapter_timeout_hold_before_"));
        assertTrue(host.contains("Test-ExactlyOneTimestampedFile"));
        assertTrue(host.contains("0x30444248"));

        String specs = new String(Files.readAllBytes(workspacePath(
                "../h13_radio/tools/h13_frame_adapter_evidence_specs.tsv")),
                StandardCharsets.UTF_8);
        assertTrue(specs.contains("hpi_frame_adapter_complete_tone_no_rf\t306\t1508\t"
                + "DDFD5D56624822F9A93596014563A234DA6A01632275E03608319B11D011966A"));
        assertTrue(source.contains(
                "adapter_sram_range=0x20001600..0x200029b3"));
    }

    /** v3.05保持门失败时必须先保存SNAP/live现场，再严格失败且不得写释放字。 */
    @Test
    public void v305HoldFailureCapturesReadonlyEvidenceBeforeRejecting()
            throws Exception {
        String source = new String(Files.readAllBytes(workspacePath(
                "app/src/main/java/net/elfradio/h13interphoneprobe/InterphoneProbe.java")),
                StandardCharsets.UTF_8).replace("\r\n", "\n");
        int holdFields = source.indexOf("byte[] evidenceHoldRelease =");
        int sticky = source.indexOf("byte[] stickyTelemetry =", holdFields);
        int live = source.indexOf("byte[] liveTelemetry =", sticky);
        int stage = source.indexOf("byte[] liveStageSnapshot =", live);
        int hpiState = source.indexOf("byte[] liveStockHpiStateSnapshots =", stage);
        int postRange = source.indexOf(
                "byte[] postRange = readMemoryChunkedEvidence", hpiState);
        int reject = source.indexOf("已完成失败现场只读取证", postRange);
        int release = source.indexOf(
                "FRAME_ADAPTER_EVIDENCE_HOLD_RELEASE_MAGIC", reject);
        assertTrue(holdFields >= 0 && sticky > holdFields && live > sticky);
        assertTrue(stage > live && hpiState > stage && postRange > hpiState);
        assertTrue(reject > postRange && release > reject);
        assertFalse(source.substring(holdFields, reject).contains(
                "FRAME_ADAPTER_EVIDENCE_HOLD_RELEASE_MAGIC"));
        assertTrue(source.contains("保持门失败后只读取证"));
    }

    /** v3.06必须先保存HPI后的保持旧值，再从代码常量重建并发布保持态。 */
    @Test
    public void v306ReinitializesHoldAtTimeoutAndArchivesPreReinitValues()
            throws Exception {
        String assembly = new String(Files.readAllBytes(workspacePath(
                "../h13_radio/tools/h13_mcu_frame_hpi_adapter.S")),
                StandardCharsets.UTF_8).replace("\r\n", "\n");
        int timeout = assembly.indexOf("timeout_commit:");
        int holdSource = assembly.indexOf(
                "ldr r0, evidence_hold_release_systick", timeout);
        int holdDestination = assembly.indexOf(
                "ldr r1, timeout_hold_before_systick", holdSource);
        int oldValues = assembly.indexOf(
                "ldmia r0!, {r2, r3, r5, r6}", holdDestination);
        int oldCommit = assembly.indexOf(
                "ldr r2, timeout_hold_before_magic_systick", oldValues);
        int resetRemaining = assembly.indexOf(
                "ldr r1, evidence_hold_initial_ticks_systick", oldCommit);
        int resetDelay = assembly.indexOf(
                "ldr r1, evidence_hold_initial_delay_systick", resetRemaining);
        int snapshot = assembly.indexOf(
                "bl snapshot_runtime_before_baud_restore", resetDelay);
        int active = assembly.indexOf(
                "ldr r1, evidence_hold_active_magic_systick", snapshot);
        assertTrue(timeout >= 0 && holdSource > timeout
                && holdDestination > holdSource && oldValues > holdDestination
                && oldCommit > oldValues && resetRemaining > oldCommit
                && resetDelay > resetRemaining && snapshot > resetDelay
                && active > snapshot);
        for (String token : new String[] {
                ".equ TIMEOUT_HOLD_BEFORE,        0x2000299c",
                ".equ TIMEOUT_HOLD_BEFORE_COMMIT, 0x200029ac",
                ".equ TIMEOUT_HOLD_BEFORE_MAGIC,  0x30444248",
                ".equ EVIDENCE_HOLD_INITIAL_TICKS, 9000",
                ".equ EVIDENCE_HOLD_INITIAL_DELAY, 40"
        }) {
            assertTrue("v3.06汇编缺少冻结常量：" + token,
                    assembly.contains(token));
        }

        String source = new String(Files.readAllBytes(workspacePath(
                "app/src/main/java/net/elfradio/h13interphoneprobe/InterphoneProbe.java")),
                StandardCharsets.UTF_8).replace("\r\n", "\n");
        int holdRead = source.indexOf(
                "byte[] timeoutHoldBefore = readMemoryEvidence");
        int holdCommitRead = source.indexOf(
                "byte[] timeoutHoldBeforeCommitBytes = readMemoryEvidence",
                holdRead);
        int snapshotValid = source.indexOf(
                "boolean timeoutSnapshotValid", holdCommitRead);
        assertTrue(holdRead >= 0 && holdCommitRead > holdRead
                && snapshotValid > holdCommitRead);
        assertTrue(source.substring(snapshotValid,
                source.indexOf("append(s.report, \"timeout_snapshot_commit\"",
                        snapshotValid)).contains(
                "FRAME_ADAPTER_TIMEOUT_HOLD_BEFORE_COMMIT"));

        String build = new String(Files.readAllBytes(workspacePath(
                "../h13_radio/tools/build_frame_hpi_adapter.ps1")),
                StandardCharsets.UTF_8);
        assertTrue(build.contains("0x2000299C, 0x30444248"));
        assertTrue(build.contains("超时点保持控制原值=0x2000299C..0x200029AF"));
    }

    /** v3.15保持线程态HPI，补齐共享缓冲恢复和无频道mode0重载。 */
    @Test
    public void v314RestoresStockUsartAndRequiresMode0ReloadWithoutChannelChange()
            throws Exception {
        String assembly = new String(Files.readAllBytes(workspacePath(
                "../h13_radio/tools/h13_mcu_frame_hpi_adapter.S")),
                StandardCharsets.UTF_8).replace("\r\n", "\n");
        for (String token : new String[] {
                ".equ STOCK_USART1,            0x0802251d",
                ".equ STOCK_SYSTICK,           0x08021ddd",
                ".equ MAIN_LOOP_SAFE_START,     0x080278f6",
                ".equ MAIN_LOOP_SAFE_END,       0x08027916",
                ".equ MAIN_LOOP_RESTART,        0x080278f7",
                ".equ STAGE_RETURN_MAGIC,      0x4e525448",
                "stock_usart1_vector_systick:       .word STOCK_USART1",
                "stock_systick_vector_systick:      .word STOCK_SYSTICK",
                ".global h13_frame_hpi_thread"
        }) {
            assertTrue("v3.09汇编缺少线程态合同：" + token,
                    assembly.contains(token));
        }
        assertFalse(assembly.contains("h13_frame_hpi_pendsv"));
        assertFalse(assembly.contains("PENDSV_PRIORITY"));
        assertFalse(assembly.contains("pendsvset"));

        String timeout = assembly.substring(
                assembly.indexOf("systick_timeout_expired:"),
                assembly.indexOf("timeout_commit:"));
        assertTrue(timeout.contains("ldr r1, stock_usart1_vector_systick"));
        assertFalse(timeout.contains("T_ORIGINAL_USART_VECTOR"));

        String release = assembly.substring(
                assembly.indexOf("systick_hold_release_now:"),
                assembly.indexOf("systick_timeout_expired:"));
        assertTrue(release.contains("ldr r1, stock_systick_vector_systick"));
        assertFalse(release.contains("baud_57600_systick"));
        assertFalse(release.contains("set_baud_systick"));
        assertFalse(release.contains("blx r3"));
        assertFalse(release.contains("T_ORIGINAL_SYSTICK"));
        assertFalse(release.contains("evidence_hold_released_magic_systick"));

        String tail = assembly.substring(assembly.indexOf("systick_tail:"),
                assembly.indexOf("systick_exception_return:"));
        assertTrue(tail.contains("ldr r3, stock_systick_vector_systick"));
        assertFalse(tail.contains("T_ORIGINAL_SYSTICK"));

        String systick = assembly.substring(
                assembly.indexOf("h13_frame_hpi_systick:"),
                assembly.indexOf(".size h13_frame_hpi_systick"));
        for (String token : new String[] {
                "cmp r0, #PHASE_THREAD_PENDING", "tst r0, r1",
                "add r5, sp, #40", "ldr r1, main_loop_safe_start_systick",
                "ldr r1, main_loop_safe_end_systick",
                "str r3, [r5]", "movs r0, #PHASE_THREAD_RUNNING"
        }) {
            assertTrue("SysTick缺少安全重定向门：" + token,
                    systick.contains(token));
        }
        assertFalse(systick.contains("stock_hpi_word"));
        assertFalse(systick.contains("stock_hpi_transaction_word"));

        String thread = assembly.substring(
                assembly.indexOf("h13_frame_hpi_thread:"),
                assembly.indexOf(".size h13_frame_hpi_thread"));
        assertTrue(thread.contains("mrs r0, ipsr"));
        assertTrue(thread.contains("ldr r3, stock_hpi_word"));
        assertEquals(1, countOccurrences(thread,
                "ldr r3, stock_hpi_transaction_word"));
        assertTrue(thread.contains("thread_clear_credit_record:"));
        assertFalse(thread.contains("thread_hpi_chunk_loop:"));
        assertFalse(thread.contains("stock_delay_us_word"));
        assertTrue(thread.contains("bl snapshot_runtime_before_baud_restore"));
        assertTrue(thread.contains("ldr r0, usart1_vector_thread"));
        assertTrue(thread.contains("ldr r1, stock_usart1_vector_thread"));
        assertTrue(thread.contains("ldr r3, main_loop_restart_word"));
        assertTrue(thread.indexOf("bl snapshot_runtime_before_baud_restore")
                < thread.indexOf("ldr r0, usart1_vector_thread"));
        assertTrue(thread.indexOf("ldr r1, stock_usart1_vector_thread")
                < thread.indexOf("ldr r3, main_loop_restart_word"));
        assertTrue(thread.indexOf("str r1, [r5, #STAGE_RETURN_OFF]")
                < thread.indexOf("str r1, [r5, #STAGE_COMMIT_OFF]"));

        String source = new String(Files.readAllBytes(workspacePath(
                "app/src/main/java/net/elfradio/h13interphoneprobe/InterphoneProbe.java")),
                StandardCharsets.UTF_8).replace("\r\n", "\n");
        assertTrue(source.contains("static final int FRAME_ADAPTER_USART1_ENTRY = 0x20001bd5;"));
        assertTrue(source.contains("static final int FRAME_ADAPTER_THREAD_ENTRY = 0x20001edf;"));
        assertTrue(source.contains("+ Integer.toHexString(FRAME_ADAPTER_USART1_ENTRY)"));
        assertTrue(source.contains(
                "new HardwareEncoderNoInputModel("));
        assertTrue(source.contains(
                "BuildConfig.VERSION_CODE >= 328"));
        assertTrue(source.contains(
                "BuildConfig.VERSION_CODE < 329"));
        assertTrue(source.contains("frameAdapterDebugSpeechHpiWritten = true;"));
        assertTrue(source.contains("step5_control_profile="));
        assertTrue(source.contains("DEBUG_SPEECH_HPI"));
        assertTrue(source.contains("VOCODER_LOOPBACK"));
        assertTrue(source.contains("h13_frame_adapter_step5_exit_"));
        assertTrue(source.contains("step5_exit_vocoder_off_acked="));
        assertTrue(source.contains("step5_exit_idle_acked="));
        assertTrue(source.contains("step5_mode0_reload_proven="));
        assertTrue(source.contains("step5_mode0_reload_command_count="));
        assertTrue(source.contains("step5_shared_hpi_buffers_restored="));
        assertTrue(source.contains("channel_mutated=false"));
        int outputWindow = source.indexOf(
                "h13_frame_adapter_output_window_");
        int exitRequest = source.indexOf(
                "h13_frame_adapter_step5_exit_", outputWindow);
        int timeoutWait = source.indexOf("long safeReadAt = s.armedAtMs",
                exitRequest);
        assertTrue(outputWindow >= 0 && exitRequest > outputWindow
                && timeoutWait > exitRequest);
        assertFalse(source.contains("adapter_usart1_entry=0x20001d01"));
        int preBridgeReserved = source.indexOf(
                "frame_adapter_prebridge_thread_reserved");
        int preBridgeShpr3 = source.indexOf("frame_adapter_prebridge_shpr3");
        int bridgeWrite = source.indexOf(
                "writeMemoryByte(s.input, s.output, UART_HPI_BRIDGE_FLAG_ADDRESS, 1)",
                preBridgeShpr3);
        int postReserved = source.indexOf(
                "frame_adapter_posttimeout_thread_reserved");
        int postRange = source.indexOf(
                "byte[] postRange = readMemoryChunkedEvidence", postReserved);
        int threadReject = source.indexOf(
                "先释放保持态并完成恢复，再按整体合同判失败", postRange);
        int threadRelease = source.indexOf(
                "FRAME_ADAPTER_EVIDENCE_HOLD_RELEASE_MAGIC", threadReject);
        assertTrue(preBridgeReserved >= 0 && preBridgeShpr3 > preBridgeReserved
                && bridgeWrite > preBridgeShpr3);
        assertTrue(postReserved >= 0 && postRange > postReserved
                && threadReject > postRange && threadRelease > threadReject);

        Field adapterField = InterphoneProbe.class.getDeclaredField(
                "FRAME_ADAPTER_BASE64");
        adapterField.setAccessible(true);
        byte[] embedded = java.util.Base64.getDecoder().decode(
                (String) adapterField.get(null));
        // tools目录保存当前正在构建的步骤6适配器；历史v3.14通用制品
        // 只按自身冻结长度和哈希验收，禁止再与当前工作制品跨版本比较。
        assertEquals(1520, embedded.length);
        assertEquals("4a8424c7dceed1219964c569f19761493305c3323742d2c97a947b263d18a172",
                sha256Hex(embedded));

        String host = new String(Files.readAllBytes(workspacePath(
                "../h13_radio/tools/h13_probe_session.ps1")),
                StandardCharsets.UTF_8);
        for (String token : new String[] {
                "$stockReadV309", "3.09-thread-mode-stock-hpi-step5-no-rf",
                "$stockReadV310", "3.10-debug-speech-hpi-step5-no-rf",
                "$stockReadV312", "3.12-deferred-oneshot-baud-restore-step5-no-rf",
                "$stockReadV313", "3.13-vendor-exit-chain-step5-no-rf",
                "$stockReadV314", "3.14-step5-mode0-reload-no-rf",
                "$stockReadV315",
                "3.15-step5-shared-hpi-buffer-restore-no-rf",
                "1520", "0x20001fdf", "0x20001BD5",
                "$codeOffset = 0x5d4", "$bufferOffset = 0xc70",
                "$telemetryOffset = 0x1180", "0x1228", "0x12c8",
                "0x1328"
        }) {
            assertTrue("宿主v3.09证据门缺少：" + token,
                    host.contains(token));
        }
        assertTrue(host.contains(
                "[byte[]](0x84,0xa9,0x61,0x00,0x04,0x00,0x18,0x00,0x00,0x07)"));
        assertTrue(host.contains("debug_speech_hpi_written"));
        assertTrue(host.contains("step5_exit_vocoder_off_acked"));
        assertTrue(host.contains("step5_exit_idle_acked"));
        assertTrue(host.contains("step5_mode0_reload_proven"));
        assertTrue(host.contains("step5_mode0_reload_command_count"));
        assertTrue(host.contains("step5_shared_hpi_buffers_restored"));
        assertTrue(host.contains(
                "frame_adapter_step5_shared_hpi_rx_buffer_restored_"));
        assertTrue(host.contains(
                "frame_adapter_step5_shared_hpi_payload_buffer_restored_"));
        assertTrue(host.contains("h13_frame_adapter_step5_mode0_reload_request_"));
        assertTrue(host.contains("$timedOutVendorCredit"));
        assertTrue(host.contains(
                "Test-ExactlyOneTimestampedFile -Directory $devicePull"));

        String specs = new String(Files.readAllBytes(workspacePath(
                "../h13_radio/tools/h13_frame_adapter_evidence_specs.tsv")),
                StandardCharsets.UTF_8);
        assertTrue(specs.contains("hpi_frame_adapter_complete_tone_no_rf\t309\t1516\t"
                + "2432CAB14DFE78083B695469F5A5FBC84CE806A8BD5D1FB03F98713190F7BFA7"));
        assertTrue(specs.contains("hpi_frame_adapter_complete_tone_no_rf\t310\t1516\t"
                + "2432CAB14DFE78083B695469F5A5FBC84CE806A8BD5D1FB03F98713190F7BFA7"));
        assertTrue(specs.contains("hpi_frame_adapter_complete_tone_no_rf\t311\t1520\t"
                + "295D4F938AD83EDE5363A7D25AAB41A01B560C5786ED2596C070840966B2C485"));
        assertTrue(specs.contains("hpi_frame_adapter_complete_tone_no_rf\t312\t1504\t"
                + "4C157B44B1B530CFD542B6E03E700ED9F25A9684165135BC3D1E5809E2A10A83"));
        assertTrue(specs.contains("hpi_frame_adapter_complete_tone_no_rf\t313\t1520\t"
                + "4A8424C7DCEED1219964C569F19761493305C3323742D2C97A947B263D18A172"));
        assertTrue(specs.contains("hpi_frame_adapter_complete_tone_no_rf\t314\t1520\t"
                + "4A8424C7DCEED1219964C569F19761493305C3323742D2C97A947B263D18A172"));
        assertTrue(specs.contains("hpi_frame_adapter_complete_tone_no_rf\t315\t1520\t"
                + "4A8424C7DCEED1219964C569F19761493305C3323742D2C97A947B263D18A172"));

        int earlyCreditDecision = source.indexOf(
                "creditShapeAccepted = isStrictTimedOutStockHpiPcmCredit");
        int releaseWrite = source.indexOf(
                "FRAME_ADAPTER_EVIDENCE_HOLD_RELEASE_MAGIC",
                earlyCreditDecision);
        int reopen230400 = source.indexOf(
                "openSerialLocked(s.device, BAUD_RATE_230400, s.report)", releaseWrite);
        int restoreSharedRx = source.indexOf(
                "restoreMemoryExact(s.input, s.output, STOCK_HPI_RX_BUFFER_ADDRESS",
                reopen230400);
        int restoreSharedPayload = source.indexOf(
                "restoreMemoryExact(s.input, s.output,\n"
                        + "                        STOCK_HPI_PAYLOAD_BUFFER_ADDRESS",
                restoreSharedRx);
        int sharedReadback = source.indexOf(
                "frame_adapter_step5_shared_hpi_rx_buffer_restored",
                restoreSharedPayload);
        int mode0Reload = source.indexOf(
                "reloadStep5Mode0WithoutChannelMutation(s.input, s.output, s.report)",
                sharedReadback);
        int contentDecision = source.indexOf(
                "s.creditShapeAccepted = s.creditShapeAccepted && hasCredit",
                mode0Reload);
        int oneShotRestore = source.indexOf(
                "armBaudOneShot(s.input, s.output, BAUD_RESTORE_57600_WORDS",
                contentDecision);
        assertTrue(earlyCreditDecision >= 0 && releaseWrite > earlyCreditDecision
                && reopen230400 > releaseWrite
                && restoreSharedRx > reopen230400
                && restoreSharedPayload > restoreSharedRx
                && sharedReadback > restoreSharedPayload
                && mode0Reload > sharedReadback
                && contentDecision > mode0Reload
                && oneShotRestore > contentDecision);
        assertTrue(source.contains(
                "Arrays.equals(s.originalHpiRxState, finalHpiRxState)"));
        assertTrue(source.contains(
                "Arrays.equals(s.originalHpiRxBuffer, finalHpiRxBuffer)"));
        assertTrue(source.contains(
                "Arrays.equals(s.originalHpiPayloadBuffer,"));

        int reloadHelper = source.indexOf(
                "private void reloadStep5Mode0WithoutChannelMutation");
        int helperEnd = source.indexOf(
                "private byte[] readMemoryChunkedEvidence", reloadHelper);
        assertTrue(reloadHelper >= 0 && helperEnd > reloadHelper);
        String helperSource = source.substring(reloadHelper, helperEnd);
        assertTrue(helperSource.contains("LOG_ENABLE_COMMAND"));
        assertTrue(helperSource.contains("SCT_RELOAD_BASELINE_COMMAND"));
        assertTrue(helperSource.contains("AT+DMOCONNECT"));
        assertTrue(helperSource.contains("AT+DMOGETSOFTVERSION"));
        assertFalse(helperSource.contains("AT+DMOSETDIGITALCH"));
        assertTrue(helperSource.contains(
                "frameAdapterStep5Mode0ReloadCommandCount++"));

        byte[] stage = new byte[InterphoneProbe.FRAME_ADAPTER_STAGE_SLOT_LENGTH];
        putU32ForV219(stage, 0, 0x08027916);
        putU32ForV219(stage, 4, InterphoneProbe.FRAME_ADAPTER_THREAD_CODE);
        putU32ForV219(stage, 8, InterphoneProbe.FRAME_ADAPTER_STAGE_COMMIT_MAGIC);
        putU32ForV219(stage, 12, InterphoneProbe.FRAME_ADAPTER_STAGE_RETURN_MAGIC);
        byte[] telemetry = new byte[InterphoneProbe.FRAME_ADAPTER_TELEMETRY_LENGTH];
        putU32ForV219(telemetry, 0x18, 0);
        putU32ForV219(telemetry, 0x54, 1);
        putU32ForV219(telemetry, 0x58, 0x08027916);
        putU32ForV219(telemetry, 0x5c, InterphoneProbe.FRAME_ADAPTER_THREAD_CODE);
        assertTrue(InterphoneProbe.frameAdapterThreadStageValid(stage,
                stage.clone(), telemetry, 0x08013387));
        putU32ForV219(stage, 0, 0x08027914); // 32位BL后半字，必须拒绝。
        assertFalse(InterphoneProbe.frameAdapterThreadStageValid(stage,
                stage.clone(), telemetry, 0x08013387));
    }

    /** v3.16仅在v3.15冻结路径后增加一次生产同构模块重启。 */
    @Test
    public void v316RequiresOneProductionLikeModuleRestartAndStrictEvidence()
            throws Exception {
        assertTrue(InterphoneProbe.isExactDmrSwitchValue(
                "1\n".getBytes(StandardCharsets.US_ASCII), 1));
        assertTrue(InterphoneProbe.isExactDmrSwitchValue(
                "0".getBytes(StandardCharsets.US_ASCII), 0));
        assertFalse(InterphoneProbe.isExactDmrSwitchValue(
                "10".getBytes(StandardCharsets.US_ASCII), 1));
        assertFalse(InterphoneProbe.isExactDmrSwitchValue(null, 1));

        assertTrue(InterphoneProbe.step5ModuleRestartStrictlyProven(
                true, true, true, true, true, true, 1));
        for (int falseIndex = 0; falseIndex < 6; falseIndex++) {
            boolean[] fields = {true, true, true, true, true, true};
            fields[falseIndex] = false;
            assertFalse(InterphoneProbe.step5ModuleRestartStrictlyProven(
                    fields[0], fields[1], fields[2], fields[3], fields[4],
                    fields[5], 1));
        }
        assertFalse(InterphoneProbe.step5ModuleRestartStrictlyProven(
                true, true, true, true, true, true, 0));
        assertFalse(InterphoneProbe.step5ModuleRestartStrictlyProven(
                true, true, true, true, true, true, 2));

        String source = new String(Files.readAllBytes(workspacePath(
                "app/src/main/java/net/elfradio/h13interphoneprobe/InterphoneProbe.java")),
                StandardCharsets.UTF_8).replace("\r\n", "\n");
        int sharedRestore = source.indexOf(
                "frame_adapter_step5_shared_hpi_rx_buffer_restored");
        int mode0 = source.indexOf(
                "reloadStep5Mode0WithoutChannelMutation(s.input, s.output, s.report)",
                sharedRestore);
        int restart = source.indexOf(
                "restartStep5ModuleLikeProduction(s.device, s.report)", mode0);
        int bridgeRead = source.indexOf(
                "frame_adapter_step5_mode0_postreload_bridge", restart);
        assertTrue(sharedRestore >= 0 && mode0 > sharedRestore
                && restart > mode0 && bridgeRead > restart);
        String restartSource = source.substring(source.indexOf(
                "private void restartStep5ModuleLikeProduction"),
                source.indexOf("private byte[] readStep5DmrSwitchEvidence"));
        for (String token : new String[] {
                "writeStep5DmrSwitch(0)", "SystemClock.sleep(100)",
                "writeStep5DmrSwitch(1)", "+DMOSTARTUP:0",
                "AT+DMOCONNECT", "AT+DMOGETSOFTVERSION",
                "frameAdapterStep5ModuleRestartCount++",
                "step5ModuleRestartStrictlyProven",
                // v3.18唯一变量：模块重启前恢复57600生产文本面
                "step5-module-restart-pre-57600",
                "BAUD_RESTORE_57600_WORDS",
                "BAUD_ONESHOT_MARKER_57600"
        }) {
            assertTrue("v3.18模块重启缺少：" + token,
                    restartSource.contains(token));
        }
        assertFalse(restartSource.contains("AT+DMOSETDIGITALCH"));
        assertTrue(source.contains(
                "step5_module_restart_final_on_confirmed="));
        assertTrue(source.contains(
                "h13_frame_adapter_step5_module_restart_dmr_switch_emergency_on_"));

        String host = new String(Files.readAllBytes(workspacePath(
                "../h13_radio/tools/h13_probe_session.ps1")),
                StandardCharsets.UTF_8);
        for (String token : new String[] {
                "$stockReadV319",
                "3.19-post-restart-print1-before-memread-no-rf",
                "$stockReadV320",
                "3.20-bridge-settle-2000ms-no-rf",
                "$stockReadV321",
                "3.21-control-window-9000ms-no-rf",
                "$stockReadV322",
                "3.22-prebridge-clean-mode0-no-rf",
                "$stockReadV323",
                "3.23-prebridge-print1-then-mode0-no-rf",
                "$stockReadV324",
                "3.24-prebridge-print1-nofail-mode0-no-rf",
                "$stockReadV325",
                "3.25-step6-vocoder-out-after-credit-no-rf",
                "$stockReadV326",
                "3.26-step6-vocoder-out-before-pcm-no-rf",
                "$stockReadV327",
                "3.27-step6-vocoder-out-after-controls-no-rf",
                "$stockReadV328",
                "3.28-step6-control1-0x90-no-rf",
                "$stockReadV329",
                "3.29-step6-loopback-keep-0x90-no-rf",
                "$stockReadV330",
                "$stockReadV337",
                "$stockReadV338",
                "$stockReadV339",
                "$stockReadV340",
                "$stockReadV341",
                "$stockReadV342",
                "$stockReadV343",
                "$stockReadV344",
                "$stockReadV345",
                "$stockReadV346",
                "$stockReadV347",
                "3.48-step6-v130-800hz-57600-no-rf",
                "3.49-step6-encoder-atomic-contract-no-rf",
                "3.50-step6-stock-hpi-write-v331-plane-no-rf",
                "launch_contract_selftest_no_device",
                "writeLaunchAtomic",
                "encoder atomic result write failed",
                "结果合同缺失",
                "STOCK_HPI_WRITE_STUB_SHA256",
                "0x08010561",
                "h13_pcm_to_ambe_tone_outbound_",
                "3.42-step6-one-stock-zero-pcm-no-rf",
                "3.43-step6-one-stock-800hz-pcm-no-rf",
                "3.44-step6-read-tx-chand-queue-no-rf",
                "3.45-step6-read-tape-chand-no-rf",
                "3.46-step6-read-legacy-chand-queue-no-rf",
                "3.47-step6-keep-postread-rx-state-no-rf",
                "3.30-step6-loopback-skip-pcm-no-rf",
                "3.31-hwenc-raw-bridge-loopback-0x90-no-rf",
                "3.32-hwenc-one-zero-pcm-0x90-no-rf",
                "3.33-hwenc-one-zero-pcm-0x80-no-rf",
                "3.34-hwenc-zero-pcm-then-0x90-no-rf",
                "3.35-hwenc-zero-pcm-emit-0x90-no-rf",
                "3.36-hwenc-0x90-then-second-zero-pcm-no-rf",
                "3.37-stock-hpi-reread-after-credit-no-rf",
                "3.38-stock-hpi-reread-after-credit-no-rf",
                "3.39-stock-hpi-reread-session-locals-no-rf",
                "3.40-inbridge-stock-hpi-reread-no-rf",
                "3.41-inbridge-one-extra-keep-first-no-rf",
                "h13_stock_hpi_reread_tape_",
                "maybeRunStockHpiReadOnlyLoopAfterCredit",
                "FrameAdapterSession",
                "skipFrameAdapterPcmForVersion",
                "ZeroThen90",
                "0x90确认后第二块全零PCM请求",
                "output_stage_first_malformed_offset",
                "vocoderIoPcmToAmbeBitmap",
                "CreditPacedPcmSender",
                "STOCK_HPI_REREAD_SHA256",
                "runStockHpiReadOnlyLoopAfterCredit",
                "normalizeStep5PreBridgeCleanMode0",
                "步骤5进桥前clean-mode0前print1",
                "guardedThreadProfile ? 5000L",
                "guardedThreadProfile ? 500",
                "post_module_restart",
                "Test-Step5ModuleRestartEvidence",
                "Invoke-Step5ModuleRestartEvidenceSelfTest",
                "step5_module_restart_count",
                "step5_module_restart_dmr_switch_final_"
        }) {
            assertTrue("v3.20宿主/探针证据门缺少：" + token,
                    host.contains(token) || source.contains(token));
        }

        String specs = new String(Files.readAllBytes(workspacePath(
                "../h13_radio/tools/h13_frame_adapter_evidence_specs.tsv")),
                StandardCharsets.UTF_8);
        assertTrue(specs.contains(
                "hpi_frame_adapter_complete_tone_no_rf\t319\t1520\t"
                        + "4A8424C7DCEED1219964C569F19761493305C3323742D2C97A947B263D18A172"));
        assertTrue(specs.contains(
                "hpi_frame_adapter_complete_tone_no_rf\t320\t1520\t"
                        + "4A8424C7DCEED1219964C569F19761493305C3323742D2C97A947B263D18A172"));
        assertTrue(specs.contains(
                "hpi_frame_adapter_complete_tone_no_rf\t321\t1520\t"
                        + "4A8424C7DCEED1219964C569F19761493305C3323742D2C97A947B263D18A172"));
        assertTrue(specs.contains(
                "hpi_frame_adapter_complete_tone_no_rf\t322\t1520\t"
                        + "4A8424C7DCEED1219964C569F19761493305C3323742D2C97A947B263D18A172"));
        assertTrue(specs.contains(
                "hpi_frame_adapter_complete_tone_no_rf\t323\t1520\t"
                        + "4A8424C7DCEED1219964C569F19761493305C3323742D2C97A947B263D18A172"));
        assertTrue(specs.contains(
                "hpi_frame_adapter_complete_tone_no_rf\t324\t1520\t"
                        + "4A8424C7DCEED1219964C569F19761493305C3323742D2C97A947B263D18A172"));
        assertTrue(specs.contains(
                "hpi_frame_adapter_complete_tone_no_rf\t325\t1520\t"
                        + "4A8424C7DCEED1219964C569F19761493305C3323742D2C97A947B263D18A172"));
        assertTrue(specs.contains(
                "hpi_frame_adapter_complete_tone_no_rf\t326\t1520\t"
                        + "4A8424C7DCEED1219964C569F19761493305C3323742D2C97A947B263D18A172"));
        assertTrue(specs.contains(
                "hpi_frame_adapter_complete_tone_no_rf\t327\t1520\t"
                        + "4A8424C7DCEED1219964C569F19761493305C3323742D2C97A947B263D18A172"));
        assertTrue(specs.contains(
                "hpi_frame_adapter_complete_tone_no_rf\t328\t1520\t"
                        + "4A8424C7DCEED1219964C569F19761493305C3323742D2C97A947B263D18A172"));
        assertTrue(specs.contains(
                "hpi_frame_adapter_complete_tone_no_rf\t329\t1520\t"
                        + "4A8424C7DCEED1219964C569F19761493305C3323742D2C97A947B263D18A172"));
        assertTrue(specs.contains(
                "hpi_frame_adapter_complete_tone_no_rf\t330\t1520\t"
                        + "4A8424C7DCEED1219964C569F19761493305C3323742D2C97A947B263D18A172"));
        assertTrue(specs.contains(
                "hpi_frame_adapter_complete_tone_no_rf\t337\t1520\t"
                        + "4A8424C7DCEED1219964C569F19761493305C3323742D2C97A947B263D18A172"));
        assertTrue(specs.contains(
                "hpi_frame_adapter_complete_tone_no_rf\t338\t1520\t"
                        + "4A8424C7DCEED1219964C569F19761493305C3323742D2C97A947B263D18A172"));
        assertTrue(specs.contains(
                "hpi_frame_adapter_complete_tone_no_rf\t339\t1520\t"
                        + "4A8424C7DCEED1219964C569F19761493305C3323742D2C97A947B263D18A172"));
        assertTrue(specs.contains(
                "hpi_frame_adapter_complete_tone_no_rf\t340\t1572\t"
                        + "9670717494A2B47E6514FA8B47236314614A54E3B6913BD4A0F908C54258F63D"));
        assertTrue(specs.contains(
                "hpi_frame_adapter_complete_tone_no_rf\t341\t1604\t"
                        + "7E80C94F19DF2E9F41DF35D307E3D6E6EFF52DF7CCEDD94FD416E1CEE4F540D5"));
        assertTrue(specs.contains(
                "hpi_frame_adapter_complete_tone_no_rf\t342\t1520\t"
                        + "4A8424C7DCEED1219964C569F19761493305C3323742D2C97A947B263D18A172"));
        assertTrue(specs.contains(
                "hpi_frame_adapter_complete_tone_no_rf\t343\t1520\t"
                        + "4A8424C7DCEED1219964C569F19761493305C3323742D2C97A947B263D18A172"));
        assertTrue(specs.contains(
                "hpi_frame_adapter_complete_tone_no_rf\t344\t1520\t"
                        + "4A8424C7DCEED1219964C569F19761493305C3323742D2C97A947B263D18A172"));
        assertTrue(specs.contains(
                "hpi_frame_adapter_complete_tone_no_rf\t345\t1520\t"
                        + "4A8424C7DCEED1219964C569F19761493305C3323742D2C97A947B263D18A172"));
        assertTrue(source.contains("VERSION_CODE == 342"));
        assertTrue(source.contains("VERSION_CODE == 348"));
        assertTrue(source.contains("h13_pcm_to_ambe_tone_outbound_"));
        assertTrue("v3.49编码器路径必须在finally写原子结果",
                source.contains("encoder atomic result write failed"));
        assertTrue("v3.49原子PASS不得只凭短信用",
                source.contains("短信用单独不算步骤6")
                        && source.contains("step6_candidate27=")
                        && source.contains("credit_is_not_pcm_consume=true")
                        && source.contains("spontaneous_27_is_not_encoder=true"));
        assertTrue("v3.49宿主必须在串口已空时短宽限后结束",
                host.contains("结果合同缺失"));
        assertTrue(source.contains("VERSION_CODE >= 350"));
        assertTrue(source.contains("STOCK_HPI_WRITE_STUB_SHA256"));
        assertTrue(source.contains("3.50-step6-stock-hpi-write-v331-plane-no-rf"));
        assertTrue(source.contains("writeLaunchAtomic"));
        assertTrue(host.contains("am start args="));
        assertTrue(host.contains("launch_session_id"));
        assertTrue(host.contains("2026-08-17起真机只允许USB ADB序列号0"));
        assertTrue(host.contains("ANDROID_ADB_SERVER_PORT = \"5038\""));
        assertTrue(host.contains("纯启动合同自检后生产状态逐字节一致；未调用"
                + "Restore-Interphone"));
        assertTrue(host.contains("$launchContractPostInterphonePid -ne"));
        assertFalse(host.contains("if ($disabled -or $true)"));
        assertTrue(source.contains("maybeSnapshotTxChanDQueue"));
        assertTrue(source.contains("maybeSnapshotTapeChanD"));
        assertTrue(source.contains("maybeSnapshotLegacyChanDQueue"));
        assertTrue(source.contains("VERSION_CODE < 344"));
        assertTrue(source.contains("VERSION_CODE < 345"));
        assertTrue(source.contains("VERSION_CODE < 346"));
        assertTrue(specs.contains(
                "hpi_frame_adapter_complete_tone_no_rf\t346\t1520\t"
                        + "4A8424C7DCEED1219964C569F19761493305C3323742D2C97A947B263D18A172"));
        assertTrue(specs.contains(
                "hpi_frame_adapter_complete_tone_no_rf\t347\t1520\t"
                        + "4A8424C7DCEED1219964C569F19761493305C3323742D2C97A947B263D18A172"));
        assertTrue(specs.contains(
                "A29D109900E05373D5D6003D9FFB5305D0401977CD48E5F68817121C2DFAF9E8"));
        assertTrue(source.contains("VERSION_CODE >= 328"));
        assertTrue(source.contains("VERSION_CODE < 329"));
        assertTrue(source.contains("VERSION_CODE >= 330"));
        assertTrue(source.contains("VERSION_CODE >= 331"));
        assertTrue(source.contains("VERSION_CODE < 333"));
        assertTrue(source.contains("VERSION_CODE >= 334"));
        assertTrue(source.contains("VERSION_CODE >= 336"));
        assertTrue(source.contains("VERSION_CODE >= 337"));
        assertTrue(source.contains("expectedSwitchFrame"));
        assertTrue(source.contains("HPI_VOCODER_IO_PCM_TO_AMBE_HPI"));
        assertTrue(source.contains("OFF+0x90+PROCESS(3)+DEBUG_SPEECH_HPI"));
        assertTrue(source.contains("OFF+0x90+PROCESS(3)+VOCODER_LOOPBACK"));
        assertTrue(source.contains("v330_loopback_observe_skip_pcm_stock_write"));
        int fourAck = source.indexOf("四控制严格确认");
        int step6AfterControls = source.indexOf(
                "VERSION_CODE == 327", fourAck);
        int usartWait = source.indexOf("usartReadyAt", step6AfterControls);
        int preSpeech = source.indexOf("pre_speech_silence_ok", usartWait);
        int step6AfterSilence = source.indexOf(
                "VERSION_CODE == 326", preSpeech);
        int writeSpeech = source.indexOf("控制窗结束后写SPEECH", step6AfterSilence);
        int step6AfterCredit = source.indexOf(
                "VERSION_CODE == 325", writeSpeech);
        assertTrue(fourAck >= 0 && step6AfterControls > fourAck
                && usartWait > step6AfterControls
                && preSpeech > usartWait
                && step6AfterSilence > preSpeech
                && writeSpeech > step6AfterSilence
                && step6AfterCredit > writeSpeech);
        int prebridgeMode0 = source.indexOf(
                "normalizeStep5PreBridgeCleanMode0(s.input, s.output, s.report)");
        int print1 = source.indexOf(
                "h13_frame_adapter_print1_", prebridgeMode0);
        int dualRead = source.indexOf(
                "frame_adapter_pre_range_r1", print1);
        assertTrue(prebridgeMode0 >= 0 && print1 > prebridgeMode0
                && dualRead > print1);
    }

    /** 同步接收器三份证据一致时，必须重建完整厂家短信用。 */
    @Test
    public void reconstructStockHpiReadResponseAcceptsConsistentCredit() {
        byte[] state = stockHpiResponseState(2, 0x03,
                InterphoneProbe.STOCK_HPI_PAYLOAD_BUFFER_ADDRESS, 0);
        byte[] raw = new byte[InterphoneProbe.STOCK_HPI_RX_BUFFER_LENGTH];
        byte[] payload = new byte[InterphoneProbe.STOCK_HPI_PAYLOAD_BUFFER_LENGTH];
        byte[] credit = hexBytes("84 a9 61 00 02 03 01 00");
        System.arraycopy(credit, 0, raw, 0, credit.length);
        payload[0] = 0x01;
        payload[1] = 0x00;
        assertArrayEquals(credit,
                InterphoneProbe.reconstructStockHpiReadResponse(
                        state, raw, payload));
    }

    /** v3.10原件证明：-2可携带唯一完整type3/00 00厂家PCM信用。 */
    @Test
    public void reconstructStockHpiReadResponseAcceptsExactTimedOutPcmCredit() {
        byte[] state = stockHpiResponseState(2, 0x03,
                InterphoneProbe.STOCK_HPI_PAYLOAD_BUFFER_ADDRESS, -2);
        byte[] raw = new byte[InterphoneProbe.STOCK_HPI_RX_BUFFER_LENGTH];
        byte[] payload = new byte[InterphoneProbe.STOCK_HPI_PAYLOAD_BUFFER_LENGTH];
        byte[] credit = hexBytes("84 a9 61 00 02 03 00 00");
        System.arraycopy(credit, 0, raw, 0, credit.length);
        assertArrayEquals(credit,
                InterphoneProbe.reconstructStockHpiReadResponse(
                        state, raw, payload));
        assertTrue(InterphoneProbe.isStrictTimedOutStockHpiPcmCredit(
                -2, 2, 3, InterphoneProbe.STOCK_HPI_PAYLOAD_BUFFER_ADDRESS,
                credit));
    }

    /** 超时、错误正文指针及正文不一致都必须失败关闭。 */
    @Test
    public void reconstructStockHpiReadResponseRejectsInvalidEvidence() {
        byte[] raw = new byte[InterphoneProbe.STOCK_HPI_RX_BUFFER_LENGTH];
        byte[] payload = new byte[InterphoneProbe.STOCK_HPI_PAYLOAD_BUFFER_LENGTH];
        byte[] credit = hexBytes("84 a9 61 00 02 03 01 00");
        System.arraycopy(credit, 0, raw, 0, credit.length);
        payload[0] = 0x01;
        payload[1] = 0x00;

        byte[] timeout = stockHpiResponseState(2, 0x03,
                InterphoneProbe.STOCK_HPI_PAYLOAD_BUFFER_ADDRESS, -1);
        assertEquals(0, InterphoneProbe.reconstructStockHpiReadResponse(
                timeout, raw, payload).length);

        byte[] wrongTimedOutBody = stockHpiResponseState(2, 0x03,
                InterphoneProbe.STOCK_HPI_PAYLOAD_BUFFER_ADDRESS, -2);
        assertEquals(0, InterphoneProbe.reconstructStockHpiReadResponse(
                wrongTimedOutBody, raw, payload).length);
        assertFalse(InterphoneProbe.isStrictTimedOutStockHpiPcmCredit(
                -2, 2, 3, InterphoneProbe.STOCK_HPI_PAYLOAD_BUFFER_ADDRESS,
                credit));

        byte[] wrongPointer = stockHpiResponseState(2, 0x03,
                InterphoneProbe.STOCK_HPI_PAYLOAD_BUFFER_ADDRESS + 4, 0);
        assertEquals(0, InterphoneProbe.reconstructStockHpiReadResponse(
                wrongPointer, raw, payload).length);

        byte[] success = stockHpiResponseState(2, 0x03,
                InterphoneProbe.STOCK_HPI_PAYLOAD_BUFFER_ADDRESS, 0);
        payload[1] = 0x01;
        assertEquals(0, InterphoneProbe.reconstructStockHpiReadResponse(
                success, raw, payload).length);
    }

    /** 固定256字节正文缓冲不允许声明超长响应。 */
    @Test
    public void reconstructStockHpiReadResponseRejectsOversizePayload() {
        byte[] state = stockHpiResponseState(251, 0x20,
                InterphoneProbe.STOCK_HPI_PAYLOAD_BUFFER_ADDRESS, 0);
        assertEquals(0, InterphoneProbe.reconstructStockHpiReadResponse(
                state,
                new byte[InterphoneProbe.STOCK_HPI_RX_BUFFER_LENGTH],
                new byte[InterphoneProbe.STOCK_HPI_PAYLOAD_BUFFER_LENGTH]).length);
    }

    @Test
    public void v359Step6AdapterArtifactMatchesFrozenBinary() throws Exception {
        Field field = InterphoneProbe.class.getDeclaredField(
                "STEP6_FRAME_ADAPTER_V359_BASE64");
        field.setAccessible(true);
        byte[] adapter = java.util.Base64.getDecoder().decode(
                (String) field.get(null));
        assertEquals(1644, adapter.length);
        assertEquals(InterphoneProbe.STEP6_FRAME_ADAPTER_CODE_LENGTH,
                adapter.length);
        assertEquals(InterphoneProbe.STEP6_FRAME_ADAPTER_SHA256,
                sha256Hex(adapter));
        assertEquals(0x2000204b,
                InterphoneProbe.STEP6_FRAME_ADAPTER_ARM_ENTRY);
        assertTrue(containsLeU32(adapter, 0x08010561));
        assertTrue(containsLeU32(adapter, 0x08023a29));
    }

    @Test
    public void v361Step6AdapterAddsExactlyOneFirstCreditRead() throws Exception {
        Field field = InterphoneProbe.class.getDeclaredField(
                "STEP6_FRAME_ADAPTER_V361_BASE64");
        field.setAccessible(true);
        byte[] adapter = java.util.Base64.getDecoder().decode(
                (String) field.get(null));
        assertEquals(InterphoneProbe.STEP6_SYNC_FRAME_ADAPTER_CODE_LENGTH,
                adapter.length);
        assertEquals(InterphoneProbe.STEP6_SYNC_FRAME_ADAPTER_SHA256,
                sha256Hex(adapter));
        assertEquals(0x20002055,
                InterphoneProbe.STEP6_SYNC_FRAME_ADAPTER_ARM_ENTRY);
        assertTrue(containsLeU32(adapter, 0x08010561));
        assertTrue(containsLeU32(adapter,
                InterphoneProbe.STOCK_HPI_TRANSACTION_CODE_ADDRESS | 1));
    }

    @Test
    public void v353Step6UsesFixedVoiceInOnlyFourControlProfile() {
        InterphoneProbe.HardwareEncoderNoInputModel model =
                new InterphoneProbe.HardwareEncoderNoInputModel(
                        false, false, false);
        byte[][] expected = new byte[][] {
                hexBytes("84 a9 61 00 02 00 3e 00"),
                hexBytes("84 a9 61 00 02 00 3e 80"),
                hexBytes("84 a9 61 00 02 00 1a 03"),
                hexBytes("84 a9 61 00 04 00 18 03 03 00")
        };
        for (byte[] request : expected) {
            assertArrayEquals(request, model.expectedRequest());
            byte[] response = hpiFrame(0,
                    new byte[] {request[6], 0});
            assertTrue(model.acceptControlExchange(request, response));
        }
        assertNull(model.expectedRequest());
        assertEquals(4, model.requestCount());
        assertEquals(4, model.ackCount());
    }

    @Test
    public void v353Step6RejectsNoCandidateAndAcceptsStrict27() {
        assertFalse(InterphoneProbe.step6SingleStockOutputAccepted(
                true, 1, 1, 0, 1290, 1290, new byte[0]));
        assertFalse(InterphoneProbe.step6SingleStockOutputAccepted(
                true, 1, 1, 0, 1290, 1290,
                hexBytes("84 a9 61 00 02 03 00 00")));
        byte[] candidate27 = hpiWireFrame(0x20,
                concatAmbePayload(new byte[27]));
        assertTrue(InterphoneProbe.step6SingleStockOutputAccepted(
                true, 1, 1, 0, 1290, 1290, candidate27));
        assertFalse(InterphoneProbe.step6SingleStockOutputAccepted(
                true, 1, 1, 0, 1289, 1290, candidate27));
    }

    @Test
    public void v370Step6ExportsFirstCreditBeforeRestoringActiveState()
            throws Exception {
        assertEquals(15000L,
                InterphoneProbe.FRAME_ADAPTER_OUTPUT_CAPTURE_MS);
        String assembly = new String(Files.readAllBytes(workspacePath(
                "../h13_radio/tools/h13_mcu_frame_hpi_adapter.S")),
                StandardCharsets.UTF_8).replace("\r\n", "\n");
        String thread = assembly.substring(
                assembly.indexOf("h13_frame_hpi_thread:"),
                assembly.indexOf(".size h13_frame_hpi_thread"));
        assertTrue(thread.contains("ldr r3, stock_hpi_word"));
        assertEquals(1, countOccurrences(thread,
                "ldr r3, stock_hpi_transaction_word"));
        assertTrue(thread.contains("thread_clear_credit_record:"));
        assertFalse(thread.contains("thread_hpi_chunk_loop:"));
        assertFalse(thread.contains("CHUNK_BYTES"));
        assertFalse(thread.contains("chunk_gap_us_word"));
        assertFalse(thread.contains("stock_delay_us_word"));
        assertEquals(1, countOccurrences(thread, "ldr r3, stock_hpi_word"));
        assertTrue(thread.contains("ldr r2, first_write_begin_word"));
        assertTrue(thread.contains("ldr r2, first_write_return_word"));
        assertTrue(thread.contains("str r0, [r4, #T_HPI_COMPLETED_BYTES]"));
        assertTrue(thread.contains("movs r0, #1"));

        Field adapterField = InterphoneProbe.class.getDeclaredField(
                "STEP6_FRAME_ADAPTER_V363_BASE64");
        adapterField.setAccessible(true);
        byte[] adapter = java.util.Base64.getDecoder().decode(
                (String) adapterField.get(null));
        assertEquals(InterphoneProbe.STEP6_THREE_CREDIT_FRAME_ADAPTER_CODE_LENGTH,
                adapter.length);
        assertEquals(InterphoneProbe.STEP6_THREE_CREDIT_FRAME_ADAPTER_SHA256,
                sha256Hex(adapter));
        assertEquals(0x20002001,
                InterphoneProbe.STEP6_THREE_CREDIT_FRAME_ADAPTER_ARM_ENTRY);
        assertTrue(containsLeU32(adapter,
                InterphoneProbe.STOCK_HPI_TRANSACTION_CODE_ADDRESS | 1));

        Field transactionField = InterphoneProbe.class.getDeclaredField(
                "STOCK_HPI_TRANSACTION_V370_BASE64");
        transactionField.setAccessible(true);
        byte[] transactionBytes = java.util.Base64.getDecoder().decode(
                (String) transactionField.get(null));
        assertEquals(248, transactionBytes.length);
        assertEquals(InterphoneProbe.STOCK_HPI_TRANSACTION_CODE_LENGTH,
                transactionBytes.length);
        assertEquals(InterphoneProbe.STOCK_HPI_TRANSACTION_SHA256,
                sha256Hex(transactionBytes));
        assertTrue(containsLeU32(transactionBytes, 0x080105c9));
        assertFalse(containsLeU32(transactionBytes, 0x08010561));
        assertTrue(containsLeU32(transactionBytes, 0x08010b7d));
        assertTrue(containsAscii(transactionBytes, "R1BG"));
        assertTrue(containsAscii(transactionBytes, "R1RT"));
        assertFalse(containsAscii(transactionBytes, "W2BG"));
        assertFalse(containsAscii(transactionBytes, "W2OK"));
        assertTrue(containsLeU32(transactionBytes,
                InterphoneProbe.FRAME_ADAPTER_TIMEOUT_SNAPSHOT_HPI_STATE_ADDRESS));

        String transaction = new String(Files.readAllBytes(workspacePath(
                "../h13_radio/tools/h13_mcu_stock_hpi_read_transaction.S")),
                StandardCharsets.UTF_8).replace("\r\n", "\n");
        assertTrue(transaction.contains(".equ CREDIT_RECORD,            0x20002208"));
        assertFalse(transaction.contains("EXPECTED_POINTER"));
        assertFalse(transaction.contains("EXPECTED_RETURN"));
        assertFalse(transaction.contains("EXPECTED_RAW0"));
        assertFalse(transaction.contains("EXPECTED_RAW1"));
        assertFalse(transaction.contains("stock_hpi_write_word"));
        assertFalse(transaction.contains("CHUNK_BYTES"));
        assertFalse(transaction.contains("CHUNK_GAP_US"));
        assertFalse(transaction.contains("STOCK_DELAY_US"));
        assertTrue(transaction.contains("ldr r1, read_begin_base_word"));
        assertEquals(1, countOccurrences(transaction, "blx r7"));
        assertEquals(4, countOccurrences(transaction, "blx r3"));
        assertEquals(4, countOccurrences(transaction,
                "ldr r3, stock_uart_write_word"));
        assertTrue(transaction.indexOf("adr r0, uart_marker_r1_begin")
                < transaction.indexOf("ldr r7, stock_hpi_read_word"));
        assertTrue(transaction.indexOf("ldr r7, stock_hpi_read_word")
                < transaction.indexOf("adr r0, uart_marker_r1_return"));
        assertTrue(transaction.indexOf("adr r0, uart_marker_r1_return")
                < transaction.indexOf("bl copy20", transaction.indexOf(
                "adr r0, uart_marker_r1_return")));
        assertTrue(transaction.contains("movs r6, #4\nexport_response_word:"));
        assertTrue(transaction.contains("movs r6, #2\nexport_raw_word:"));
        assertEquals(2, countOccurrences(transaction, "movs r1, #4\n"
                + "    ldr r3, stock_uart_write_word\n"
                + "    blx r3\n"
                + "    adds r5, #4\n"
                + "    subs r6, #1\n"
                + "    bne export_"));
        int restoreActiveState = transaction.indexOf("mov r0, sp",
                transaction.indexOf("adr r0, uart_marker_r1_return"));
        assertTrue(restoreActiveState > 0);
        assertTrue(transaction.indexOf("export_response_word:")
                < restoreActiveState);
        assertTrue(transaction.indexOf("export_raw_word:")
                < restoreActiveState);
        assertFalse(transaction.contains("clear_credit_record:"));

        String source = new String(Files.readAllBytes(workspacePath(
                "app/src/main/java/net/elfradio/h13interphoneprobe/InterphoneProbe.java")),
                StandardCharsets.UTF_8).replace("\r\n", "\n");
        int start = source.indexOf(
                "ProbeResult runHardwareEncoderOneZeroPcm230400NoRf()");
        int end = source.indexOf(
                "ProbeResult runHardwareEncoderOneTonePcm230400NoRf()", start);
        String entry = source.substring(start, end);
        assertTrue(entry.contains("runFrameAdapterPcmNoRf(true, true, false, false,"));
        assertTrue(entry.contains("false, true"));
        assertFalse(entry.contains("runHardwareEncoder230400NoRf"));
        assertTrue(source.contains("步骤6历史判废队列地址读取\", \"未执行"));
        assertTrue(source.contains("stock_sync_read_called=\""));
        assertTrue(source.contains("step6_single_stock_write"));
        assertTrue(source.contains("stock_write_gap_us=\""));
        assertTrue(source.contains("step6CreditDrivenThreeBlock"));
        assertTrue(source.contains("FRAME_ADAPTER_CREDIT_RECORD_LENGTH"));
        assertTrue(source.contains("STEP6_FRAME_ADAPTER_V363_BASE64"));
        assertTrue(source.contains("STOCK_HPI_TRANSACTION_V370_BASE64"));
        assertTrue(source.contains("step6StrictCreditBitmap"));
        assertFalse(thread.contains("data36"));
        assertFalse(transaction.contains("data36"));
        assertTrue(source.contains(
                "已记录；先释放保持态并完成恢复，再按整体合同判失败"));
    }

    @Test
    public void v364Step6CapturesCreditEvidenceBeforeAnyExitRequest()
            throws Exception {
        String source = new String(Files.readAllBytes(workspacePath(
                "app/src/main/java/net/elfradio/h13interphoneprobe/InterphoneProbe.java")),
                StandardCharsets.UTF_8).replace("\r\n", "\n");
        int outputCapture = source.indexOf(
                "if (s.completeToneHpi && !step6CreditDrivenThreeBlock) {\n"
                        + "                if (BuildConfig.VERSION_CODE == 325)");
        int safeRead = source.indexOf(
                "long safeReadAt = s.armedAtMs", outputCapture);
        int creditRead = source.indexOf(
                "三轮信用原始记录带", safeRead);
        assertTrue(outputCapture > 0);
        assertTrue(safeRead > outputCapture);
        assertTrue(creditRead > safeRead);
        String preEvidenceSegment = source.substring(outputCapture, safeRead);
        assertTrue(preEvidenceSegment.contains(
                "保持窗取证前厂家退出链\",\n"
                        + "                        \"禁止发送；先等待自动退桥并保存信用现场"));
        assertTrue(source.contains(
                "if (s.completeToneHpi && !step6CreditDrivenThreeBlock)"));
        assertEquals(1, countOccurrences(source,
                "if (s.completeToneHpi && !step6CreditDrivenThreeBlock)"));
        assertFalse(preEvidenceSegment.contains(
                "if (s.completeToneHpi) {\n"
                        + "                if (BuildConfig.VERSION_CODE == 325)"));
    }

    @Test
    public void v354Step6ToneEntryReusesSingleStockPath() throws Exception {
        String source = new String(Files.readAllBytes(workspacePath(
                "app/src/main/java/net/elfradio/h13interphoneprobe/InterphoneProbe.java")),
                StandardCharsets.UTF_8).replace("\r\n", "\n");
        int start = source.indexOf(
                "ProbeResult runFrameAdapterCompleteTonePcmNoRf()");
        int end = source.indexOf("ProbeResult runFrameAdapterToneToDmrLowPower()",
                start);
        String entry = source.substring(start, end);
        assertTrue(entry.contains(
                "runFrameAdapterPcmNoRf(true, true, false, false, false,"));
        assertTrue(entry.contains("true, true"));
        assertTrue(source.contains(
                "boolean zeroPcm = (step6SingleStockWrite && !step6TonePcm)"));
        assertTrue(source.contains(
                "0cca15f8390d005b3b8ce3cc2d13e0d44372e070fa9cc37ce7443a542f572123"));
    }

    @Test
    public void v355Step6UsesPcmToAmbeFourControlProfile() {
        InterphoneProbe.HardwareEncoderNoInputModel model =
                new InterphoneProbe.HardwareEncoderNoInputModel(
                        true, false, false);
        byte[][] expected = new byte[][] {
                hexBytes("84 a9 61 00 02 00 3e 00"),
                hexBytes("84 a9 61 00 02 00 3e 90"),
                hexBytes("84 a9 61 00 02 00 1a 03"),
                hexBytes("84 a9 61 00 04 00 18 03 03 00")
        };
        for (byte[] request : expected) {
            assertArrayEquals(request, model.expectedRequest());
            byte[] response = hpiFrame(0,
                    new byte[] {request[6], 0});
            assertTrue(model.acceptControlExchange(request, response));
        }
        assertNull(model.expectedRequest());
        assertEquals(4, model.requestCount());
        assertEquals(4, model.ackCount());
    }

    @Test
    public void v360Step6UsesVendorSimplexVocoderLoopback() {
        InterphoneProbe.HardwareEncoderNoInputModel model =
                new InterphoneProbe.HardwareEncoderNoInputModel(
                        true, false, false, true);
        byte[][] expected = new byte[][] {
                hexBytes("84 a9 61 00 02 00 3e 00"),
                hexBytes("84 a9 61 00 02 00 3e 90"),
                hexBytes("84 a9 61 00 02 00 1a 03"),
                hexBytes("84 a9 61 00 04 00 18 02 03 00")
        };
        for (byte[] request : expected) {
            assertArrayEquals(request, model.expectedRequest());
            assertTrue(model.acceptControlExchange(request,
                    hpiFrame(0, new byte[] {request[6], 0})));
        }
        assertNull(model.expectedRequest());
        assertEquals(4, model.requestCount());
        assertEquals(4, model.ackCount());
    }

    @Test
    public void v355Step6KeepsNonzeroPrePcmBackgroundForComparison()
            throws Exception {
        String source = new String(Files.readAllBytes(workspacePath(
                "app/src/main/java/net/elfradio/h13interphoneprobe/InterphoneProbe.java")),
                StandardCharsets.UTF_8).replace("\r\n", "\n");
        assertTrue(source.contains("step6PcmToAmbeRoute"));
        assertTrue(source.contains("pre_speech_background_captured"));
        assertTrue(source.contains(
                "h13_frame_adapter_pre_speech_background_"));
        assertTrue(source.contains(
                "!step6PcmToAmbeRoute && silenceWin.length != 0"));
        assertTrue(source.contains(
                "adapter_allow3_0x90_single_0x08010561_background_compare"));
    }

    @Test
    public void v356Step6TreatsUnusedHpiReceiveScratchAsDynamicReadOnly()
            throws Exception {
        String source = new String(Files.readAllBytes(workspacePath(
                "app/src/main/java/net/elfradio/h13interphoneprobe/InterphoneProbe.java")),
                StandardCharsets.UTF_8).replace("\r\n", "\n");
        assertTrue(source.contains(
                "步骤6共享HPI接收区处理"));
        assertTrue(source.contains(
                "步骤6共享HPI接收区恢复写入"));
        assertTrue(source.contains(
                "(!step6SingleStockWrite\n"
                        + "                    && (!Arrays.equals(hpiRxState1, hpiRxState2)"));
        assertTrue(source.contains(
                "if (!step6SingleStockWrite) {\n"
                        + "                restoreMemoryExact(s.input, s.output, STOCK_HPI_RX_STATE_ADDRESS"));
        assertTrue(source.contains(
                "&& (step6SingleStockWrite\n"
                        + "                    || (Arrays.equals(s.originalHpiRxState, finalHpiRxState)"));
    }

    @Test
    public void v373MeasuredPrivacyModeRequiresOnlyChannelMutationPermission() {
        String mode = "software_ambe_measured_privacy_no_rf";
        assertTrue(MainActivity.isKnownProbeMode(mode));
        assertFalse(MainActivity.isPotentialRfMode(mode));
        assertNotNull(MainActivity.validateLaunch(mode, false, false, -1));
        assertNull(MainActivity.validateLaunch(mode, false, true, -1));
        assertNotNull(MainActivity.validateLaunch(mode, true, true, -1));
        assertNotNull(MainActivity.validateLaunch(mode, false, true, 0));
    }

    @Test
    public void v373MeasuredPrivacyEntryCannotWriteHpiOrData36() throws Exception {
        String source = new String(Files.readAllBytes(workspacePath(
                "app/src/main/java/net/elfradio/h13interphoneprobe/InterphoneProbe.java")),
                StandardCharsets.UTF_8).replace("\r\n", "\n");
        int start = source.indexOf(
                "ProbeResult runSoftwareAmbeMeasuredPrivacyNoRf(");
        int end = source.indexOf(
                "static boolean isStableExternalDmrRuntimeSnapshots", start);
        assertTrue(start > 0 && end > start);
        String entry = source.substring(start, end);
        assertTrue(entry.contains("prepareExternalDmrPrivacyRuntime"));
        assertTrue(entry.contains("createSoftwareAmbeExternalEncodedTxFrame"));
        assertTrue(entry.contains("restoreExternalDmrPrivacyOffChannel"));
        assertTrue(entry.contains("persistExternalDmrPreflightAttestation"));
        assertTrue(entry.contains("transaction.measuredRuntime14"));
        assertTrue(entry.contains(
                "success && !transaction.privacyOffRestored"));
        assertTrue(entry.indexOf("persistExternalDmrPreflightAttestation")
                > entry.indexOf("success && !transaction.privacyOffRestored"));
        assertTrue(entry.contains(
                "软件AMBE绑定通过但同版本凭证未安全落盘"));
        assertFalse(entry.contains("requireHpiPlaneForBinaryWrite"));
        assertFalse(entry.contains("writeExternalDmrData36"));
        assertFalse(entry.contains("WORK_MODE_TX"));
        assertFalse(entry.contains("output.write("));
        assertTrue(entry.contains("data36_written=false"));
        assertTrue(entry.contains("rf_command_count=0"));

        String host = new String(Files.readAllBytes(workspacePath(
                "../h13_radio/tools/h13_probe_session.ps1")),
                StandardCharsets.UTF_8).replace("\r\n", "\n");
        int mandatoryStart = host.indexOf(
                "function Get-MandatoryFreshArtifactNames");
        int mandatoryEnd = host.indexOf(
                "function Get-RemoteProbeArtifactMetadata", mandatoryStart);
        assertTrue(mandatoryStart > 0 && mandatoryEnd > mandatoryStart);
        String mandatory = host.substring(mandatoryStart, mandatoryEnd);
        assertTrue(mandatory.contains("software_ambe_measured_privacy_no_rf"));

        int evidenceStart = host.indexOf(
                "function Test-MandatoryFreshArtifactEvidenceComplete");
        int evidenceEnd = host.indexOf("function ", evidenceStart + 10);
        assertTrue(evidenceStart > 0 && evidenceEnd > evidenceStart);
        String evidence = host.substring(evidenceStart, evidenceEnd);
        assertTrue(evidence.contains("software_ambe_measured_privacy_no_rf"));
    }

    @Test
    public void v374SessionReadyModeRejectsPotentialRfAndPowerOverrides() {
        String mode = "software_ambe_session_ready_no_rf";
        assertTrue(MainActivity.isKnownProbeMode(mode));
        assertFalse(MainActivity.isPotentialRfMode(mode));
        assertNotNull(MainActivity.validateLaunch(mode, false, false, -1));
        assertNull(MainActivity.validateLaunch(mode, false, true, -1));
        assertNotNull(MainActivity.validateLaunch(mode, true, true, -1));
        assertNotNull(MainActivity.validateLaunch(mode, false, true, 0));
    }

    @Test
    public void v374SessionReadyEntryUsesHardStopExecutorContract()
            throws Exception {
        Method execute = InterphoneProbe.class.getDeclaredMethod(
                "executeExternalDmrVlcSessionNoRf",
                boolean.class, boolean.class, boolean.class, boolean.class,
                boolean.class, byte[].class, byte[].class, byte[].class,
                byte[].class);
        assertTrue(Modifier.isPrivate(execute.getModifiers()));

        String source = new String(Files.readAllBytes(workspacePath(
                "app/src/main/java/net/elfradio/h13interphoneprobe/InterphoneProbe.java")),
                StandardCharsets.UTF_8).replace("\r\n", "\n");
        int entryStart = source.indexOf(
                "ProbeResult runSoftwareAmbeSessionReadyNoRf(");
        int entryEnd = source.indexOf(
                "ProbeResult runExternalDmrSetup0OnlyNoRfProof()", entryStart);
        assertTrue(entryStart > 0 && entryEnd > entryStart);
        String entry = source.substring(entryStart, entryEnd);
        assertTrue(entry.contains(
                "executeExternalDmrVlcSessionNoRf(false, true, false, false,"));
        assertTrue(entry.contains(
                "false, null, null, pcmS16le, plainAmbe)"));

        int executorStart = source.indexOf(
                "byte[] generatedPlain36, byte[] softwarePcmS16le,");
        int executorEnd = source.indexOf(
                "ProbeResult runExternalDmrStep1OfflineHandshakeProof()",
                executorStart);
        assertTrue(executorStart > 0 && executorEnd > executorStart);
        String executor = source.substring(executorStart, executorEnd);
        assertTrue(executor.contains(
                "SOFTWARE_AMBE_HARD_STOP_BEFORE_DATA36"));
        assertTrue(executor.contains("softwareHardStopReached = true"));
        assertTrue(executor.contains("data36Execution.writeAttempted()"));
        assertTrue(executor.contains("software_session_ready="));
        assertTrue(executor.contains("software_hard_stop_before_data36="));
        assertTrue(executor.contains("data36_written="));
        assertTrue(executor.contains(
                "atomicExtraBuilder.append(\"rf_command_count=\")"));
    }

    @Test
    public void v381SoftwareLowPowerRfLegacyEntryIsPermanentlyBlocked() {
        String mode = "software_ambe_one_data36_low_power_rf";
        assertTrue(MainActivity.isKnownProbeMode(mode));
        assertTrue(MainActivity.isPotentialRfMode(mode));
        assertNotNull(MainActivity.validateLaunch(mode, false, true, -1));
        assertNotNull(MainActivity.validateLaunch(mode, true, false, -1));
        assertNotNull(MainActivity.validateLaunch(mode, true, true, -1));
        assertNotNull(MainActivity.validateLaunch(mode, true, true, 0));
    }

    @Test
    public void v381SoftwareLowPowerRfLegacyExecutorDoesNotOpenSerial()
            throws Exception {
        String source = new String(Files.readAllBytes(workspacePath(
                "app/src/main/java/net/elfradio/h13interphoneprobe/InterphoneProbe.java")),
                StandardCharsets.UTF_8).replace("\r\n", "\n");
        int start = source.indexOf(
                "ProbeResult runSoftwareAmbeOneData36LowPowerRf(");
        int end = source.indexOf(
                "ProbeResult runSoftwareAmbeFullMorseLowPowerRf(", start);
        assertTrue(start > 0 && end > start);
        String executor = source.substring(start, end);
        assertTrue(executor.contains("blockedLegacySoftwareAmbeRfMode("));
        assertFalse(executor.contains("executeExternalDmrVlcSessionNoRf("));
        assertFalse(executor.contains("AT+DMOPTT"));
    }

    @Test
    public void v382FullMorseRfLegacyEntryIsPermanentlyBlocked() {
        String mode = "software_ambe_full_morse_low_power_rf";
        assertTrue(MainActivity.isKnownProbeMode(mode));
        assertTrue(MainActivity.isPotentialRfMode(mode));
        assertNotNull(MainActivity.validateLaunch(mode, false, true, -1));
        assertNotNull(MainActivity.validateLaunch(mode, true, false, -1));
        assertNotNull(MainActivity.validateLaunch(mode, true, true, -1));
        assertNotNull(MainActivity.validateLaunch(mode, true, true, 0));
    }

    @Test
    public void v382FullMorseContractAndHardWatchdogArePresent() throws Exception {
        assertEquals(30000L,
                InterphoneProbe.SOFTWARE_AMBE_PTT_WATCHDOG_TIMEOUT_MS);
        assertEquals(2000L,
                InterphoneProbe.SOFTWARE_AMBE_PTT_WATCHDOG_TAKEOVER_LEAD_MS);
        assertEquals(104, InterphoneProbe.SOFTWARE_AMBE_MORSE_FRAME_COUNT);
        assertEquals(26, InterphoneProbe.SOFTWARE_AMBE_MORSE_DATA36_COUNT);
        assertEquals(2000L,
                InterphoneProbe.SOFTWARE_AMBE_MORSE_BRIDGE_EXIT_GUARD_MS);
        assertEquals(16000L,
                InterphoneProbe.softwareAmbeFullMorseSendDeadlineOffsetMs());
        assertEquals(320L,
                InterphoneProbe.softwareAmbeFullMorseCleanupAllowanceMs());
        assertTrue(InterphoneProbe
                .softwareAmbeFullMorseBudgetFitsBridgeAndWatchdog());
        String source = new String(Files.readAllBytes(workspacePath(
                "app/src/main/java/net/elfradio/h13interphoneprobe/InterphoneProbe.java")),
                StandardCharsets.UTF_8).replace("\r\n", "\n");
        assertTrue(source.contains("new PttHardWatchdog(device)"));
        assertTrue(source.contains("pttWatchdog.arm(pttOnAtMs)"));
        assertTrue(source.contains("h13-ptt-hard-watchdog"));
        assertTrue(source.contains("完整摩尔斯data36实际写出数"));
        assertTrue(source.contains("完整摩尔斯发送截止单调毫秒"));
        assertTrue(source.contains("fullMorseSendDeadlineMs"));
        assertTrue(source.contains("h13_software_morse_data36_"));
        assertTrue(source.contains("+ \"_pace_window_\""));
        assertTrue(source.contains("pacing_mode=fixed_80ms"));
        assertTrue(source.contains("禁止追赶式突发"));
        assertTrue(source.contains("flushAt < targetMs"));
        assertTrue(source.contains("paceLateMs = flushAt - targetMs"));
        assertTrue(source.contains("watchdog_timeout_ms="));
        assertTrue(source.contains("watchdog_takeover_lead_ms="));
        assertTrue(source.contains("watchdog_absolute_deadline_met="));
        assertTrue(source.contains("watchdog_takeover_started="));
        assertTrue(source.contains("watchdog_bridge_safety_satisfied="));
        assertTrue(source.contains("bridgeSafetyDeadlineSatisfied"));
        assertTrue(source.contains("softwareAmbeFullMorseSendDeadlineOffsetMs()"));
        assertTrue(source.contains("完整摩尔斯预算语义"));
        assertTrue(source.contains("software_data36_written_count="));
        assertTrue(source.contains("!(pttOffAccepted && txEndObserved)"));
        assertTrue(source.contains("!pttWatchdog.ownsSerial()"));
        int priorityOff = source.indexOf("文本面首次恢复后的优先PTT释放请求");
        int emergencyRestore = source.indexOf("finally.emergencySramRestore");
        assertTrue(priorityOff > 0 && emergencyRestore > priorityOff);
        int watchdogStart = source.indexOf("private void runDeadline()");
        int watchdogEnd = source.indexOf("Operator-facing big-banner phases",
                watchdogStart);
        String watchdog = source.substring(watchdogStart, watchdogEnd);
        assertTrue(watchdog.indexOf("writeStep5DmrSwitch(0)")
                < watchdog.indexOf("modulePowerAfterRaw = readBoundedFile"));
        assertFalse(watchdog.contains("modulePowerBeforeRaw = readBoundedFile"));
        assertTrue(source.contains("if (takeoverStarted) {\n                return;"));
        assertTrue(watchdog.contains("synchronized (this)"));
    }

    @Test
    public void v382ActiveHpiRouterRecognizesOnlyCompleteFrameBoundaries()
            throws Exception {
        byte[] credit = hexBytes("84 a9 61 00 02 20 01 00");
        assertEquals(0, InterphoneProbe.ActiveHpiStreamRouter
                .firstCompleteFrameLength(Arrays.copyOf(credit, 7)));
        assertEquals(8, InterphoneProbe.ActiveHpiStreamRouter
                .firstCompleteFrameLength(credit));
        try {
            InterphoneProbe.ActiveHpiStreamRouter.firstCompleteFrameLength(
                    hexBytes("00 a9 61 00 02 20 01 00"));
            fail("失去HPI同步必须失败关闭");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("帧同步"));
        }
    }

    @Test
    public void v375SoftwareOneData36RequiresPotentialRfAndChannelGates() {
        String mode = "software_ambe_one_data36_no_rf";
        assertTrue(MainActivity.isKnownProbeMode(mode));
        assertTrue(MainActivity.isPotentialRfMode(mode));
        assertNotNull(MainActivity.validateLaunch(mode, false, true, -1));
        assertNotNull(MainActivity.validateLaunch(mode, true, false, -1));
        assertNull(MainActivity.validateLaunch(mode, true, true, -1));
        assertNotNull(MainActivity.validateLaunch(mode, true, true, 0));
    }

    @Test
    public void v375SoftwareOneData36UsesOnlyPreparedFirstRequest()
            throws Exception {
        String source = new String(Files.readAllBytes(workspacePath(
                "app/src/main/java/net/elfradio/h13interphoneprobe/InterphoneProbe.java")),
                StandardCharsets.UTF_8).replace("\r\n", "\n");
        int entryStart = source.indexOf(
                "ProbeResult runSoftwareAmbeOneData36NoRf(");
        int entryEnd = source.indexOf(
                "ProbeResult runExternalDmrSetup0OnlyNoRfProof()", entryStart);
        assertTrue(entryStart > 0 && entryEnd > entryStart);
        String entry = source.substring(entryStart, entryEnd);
        assertTrue(entry.contains(
                "executeExternalDmrVlcSessionNoRf(true, true, false, false,"));
        assertTrue(entry.contains(
                "false, null, null, pcmS16le, plainAmbe)"));

        int executorStart = source.indexOf(
                "byte[] generatedPlain36, byte[] softwarePcmS16le,");
        int executorEnd = source.indexOf(
                "ProbeResult runExternalDmrStep1OfflineHandshakeProof()",
                executorStart);
        String executor = source.substring(executorStart, executorEnd);
        assertTrue(executor.contains(
                "byte[] data36Frame = softwareOneData36\n"
                        + "                        ? softwareFirstRequest"));
        assertTrue(executor.contains("SOFTWARE_AMBE_ONE_DATA36_READY"));
        assertTrue(executor.contains("software_one_data36="));
        assertTrue(executor.contains("executeExternalDmrStep4OneUnit("));
        assertTrue(executor.contains(
                "软件AMBE第一条data36唯一写入、flush、"));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    @Test
    public void v377SoftwareRequestUsesProvenThirdRealtimeUnitTrigger()
            throws Exception {
        byte[] realtime81 = hexBytes(
                "cf02f00c70cb978f21d803ac9fa4cb078f21b801b17e46df1d0b25"
                + "9802b94fa4d3df8721aa1d429e43439e8b252c0bab17a26d178b25"
                + "e8032036478b178f21e80263e9676b978b25e80b4680879e9d0b21");
        byte[] softwarePlain = new byte[36];
        for (int i = 0; i < softwarePlain.length; i++) {
            softwarePlain[i] = (byte) (0xa0 + i);
        }
        byte[] softwareRequest =
                InterphoneProbe.createRealtimeRelayEncryptedData36Frame(
                        softwarePlain, measuredPrivacyRuntime14());
        ByteArrayOutputStream wire = new ByteArrayOutputStream();
        InterphoneProbe.ExternalDmrRealtimeRelayContext context =
                new InterphoneProbe.ExternalDmrRealtimeRelayContext(
                        wire, new java.util.ArrayList(),
                        new ByteArrayOutputStream(), 0L, new StringBuilder(),
                        measuredPrivacyRuntime14(), null, softwarePlain,
                        softwareRequest);
        for (int unit = 0; unit < 3; unit++) {
            byte[] payload = new byte[29];
            payload[0] = 0x01;
            payload[1] = 0x1b;
            System.arraycopy(realtime81, unit * 27, payload, 2, 27);
            context.acceptCompleteCapture(hpiWireFrame(0x20, payload),
                    "软件实时触发单元" + unit);
        }
        assertTrue(context.writeAttempted());
        assertEquals(1, context.writeCount());
        assertEquals("software_ambe", context.payloadSource());
        assertArrayEquals(softwarePlain, context.plain36());
        assertArrayEquals(softwareRequest, wire.toByteArray());
        assertFalse(java.util.Arrays.equals(context.plain36(),
                context.realtimeCandidate36()));

        context.acceptCompleteCapture(hpiWireFrame(0x03,
                new byte[] {0x01, 0x00}), "软件写后credit");
        context.recordPreVlc2Wait(new byte[0], 0L, false);
        context.onVlcWireTx(2, Long.MAX_VALUE);
        context.recordPostObservation(new byte[0], 1500L);
        context.finalizeEvidence();
        context.requireStrictPass();
        assertTrue(context.creditAccepted());
    }

    @Test
    public void v377SoftwareRealtimeTriggerModeHasStrictLaunchGates() {
        String mode = "software_ambe_realtime_trigger_one_data36_no_rf";
        assertTrue(MainActivity.isKnownProbeMode(mode));
        assertTrue(MainActivity.isPotentialRfMode(mode));
        assertNotNull(MainActivity.validateLaunch(mode, false, true, -1));
        assertNotNull(MainActivity.validateLaunch(mode, true, false, -1));
        assertNull(MainActivity.validateLaunch(mode, true, true, -1));
        assertNotNull(MainActivity.validateLaunch(mode, true, true, 0));
    }

    private static int countOccurrences(String text, String needle) {
        int count = 0;
        int offset = 0;
        while ((offset = text.indexOf(needle, offset)) >= 0) {
            count++;
            offset += needle.length();
        }
        return count;
    }

    private static boolean containsBytes(byte[] haystack, byte[] needle) {
        if (needle.length == 0) {
            return true;
        }
        for (int start = 0; start <= haystack.length - needle.length; start++) {
            int index = 0;
            while (index < needle.length
                    && haystack[start + index] == needle[index]) {
                index++;
            }
            if (index == needle.length) {
                return true;
            }
        }
        return false;
    }

    @Test
    public void v385ProductionRfInfrastructurePreflightHasStrictLaunchGates() {
        String mode =
                "software_ambe_production_rf_infrastructure_preflight_no_rf";
        assertTrue(MainActivity.isKnownProbeMode(mode));
        assertFalse(MainActivity.isPotentialRfMode(mode));
        assertNull(MainActivity.validateLaunch(mode, false, false, -1));
        assertNull(MainActivity.validateLaunch(mode, false, true, -1));
        assertNotNull(MainActivity.validateLaunch(mode, true, false, -1));
        assertNotNull(MainActivity.validateLaunch(mode, false, false, 0));
    }

    @Test
    public void v387ExternalDmrRfArtifactsAreFrozenAndExceptionSafe()
            throws Exception {
        Field prepField = InterphoneProbe.class.getDeclaredField(
                "EXTERNAL_DMR_RF_PREP_WORDS");
        Field offField = InterphoneProbe.class.getDeclaredField(
                "EXTERNAL_DMR_RF_OFF_WORDS");
        Method combinedMethod = InterphoneProbe.class.getDeclaredMethod(
                "buildExternalDmrRfCombinedWords");
        prepField.setAccessible(true);
        offField.setAccessible(true);
        combinedMethod.setAccessible(true);
        int[] prepWords = (int[]) prepField.get(null);
        int[] offWords = (int[]) offField.get(null);
        int[] combinedWords = (int[]) combinedMethod.invoke(null);
        byte[] prep = wordsToLeBytes(prepWords);
        byte[] off = wordsToLeBytes(offWords);
        byte[] combined = wordsToLeBytes(combinedWords);
        assertEquals(52, prep.length);
        assertEquals(60, off.length);
        assertEquals(336, combined.length);
        assertEquals(InterphoneProbe.EXTERNAL_DMR_RF_PREP_SHA256,
                sha256Hex(prep));
        assertEquals(InterphoneProbe.EXTERNAL_DMR_RF_OFF_SHA256,
                sha256Hex(off));
        assertEquals(InterphoneProbe.EXTERNAL_DMR_RF_COMBINED_SHA256,
                sha256Hex(combined));
        assertEquals(1, countLeU32(combined, 0x20003101));
        assertEquals(0, countLeU32(combined, 0x20003081));
        assertEquals(1, countLeU32(prep, 0x200031c0));
        assertEquals(1, countLeU32(prep, 0x50465221));
        assertArrayEquals(new byte[] {
                0x0c, (byte) 0xbc, 0x14, 0x46, (byte) 0x9e, 0x46
        }, java.util.Arrays.copyOfRange(off, 14, 20));
        assertFalse(containsBytes(off, new byte[] {
                0x14, (byte) 0xbc, (byte) 0x96, 0x46
        }));
    }

    @Test
    public void v387NoRfInfrastructurePreflightPersistsMemreadAndNeverExecutesRfPrepare()
            throws Exception {
        String source = new String(Files.readAllBytes(workspacePath(
                "app/src/main/java/net/elfradio/h13interphoneprobe/InterphoneProbe.java")),
                StandardCharsets.UTF_8).replace("\r\n", "\n");
        int start = source.indexOf(
                "ProbeResult runSoftwareAmbeProductionRfInfrastructurePreflightNoRf()");
        int end = source.indexOf(
                "private ProbeResult blockedLegacySoftwareAmbeRfMode", start);
        assertTrue(start > 0 && end > start);
        String entry = source.substring(start, end);
        int handshake = entry.indexOf("57600文本基线失败");
        int logFlag = entry.indexOf("logLevelMayBeEnabled = true", handshake);
        int printOne = entry.indexOf("writeCommand(output, LOG_ENABLE_COMMAND)",
                logFlag);
        int printResponse = entry.indexOf("隐藏memread日志启用响应", printOne);
        int firstMemread = entry.indexOf(
                "originalPrep = readMemoryEvidenceBytes(input, output",
                printResponse);
        assertTrue(handshake >= 0 && logFlag > handshake);
        assertTrue(printOne > logFlag && printResponse > printOne);
        assertTrue(firstMemread > printResponse);
        assertTrue(entry.contains("EXTERNAL_DMR_RF_OFF_ENTRY"));
        assertTrue(entry.contains("u32le(gateBeforeOff, 0) != 0"));
        assertTrue(entry.contains("rf_prepare_executed=false"));
        assertTrue(entry.contains("dmoptt_forbidden=true"));
        assertTrue(entry.contains("\"rf_marker_after_off\""));
        assertTrue(entry.contains("\"rf_gate_after_off\""));
        assertTrue(entry.contains("\"rf_vector_after_off\""));
        assertTrue(entry.contains("\"rf_emergency_marker_restored\""));
        assertFalse(entry.contains("EXTERNAL_DMR_RF_PREP_ENTRY"));
        assertFalse(entry.contains("AT+DMOPTT"));
        assertFalse(entry.contains("HPI_WORK_MODE_TX"));
        assertFalse(entry.contains("step3Output.write"));
    }

    @Test
    public void v387GeneratorRejectsKnownBrokenExceptionReturnSequence()
            throws Exception {
        String source = new String(Files.readAllBytes(workspacePath(
                "../h13_radio/tools/build_external_dmr_rf_stubs_v007.py")),
                StandardCharsets.UTF_8).replace("\r\n", "\n");
        assertTrue(source.contains("pop {{r2, r3}}"));
        assertTrue(source.contains("mov r4, r2"));
        assertTrue(source.contains("mov lr, r3"));
        assertTrue(source.contains("\\x14\\xbc\\x96\\x46"));
        assertTrue(source.contains("RF关闭桩包含已禁止的异常返回序列"));
    }

    @Test
    public void v385ProductionRfUsesEarlyReleaseAttestationAndNoDmoptt()
            throws Exception {
        String source = new String(Files.readAllBytes(workspacePath(
                "app/src/main/java/net/elfradio/h13interphoneprobe/InterphoneProbe.java")),
                StandardCharsets.UTF_8).replace("\r\n", "\n");
        int entryStart = source.indexOf(
                "ProbeResult runSoftwareAmbeProductionFullMorseLowPowerRf(");
        int entryEnd = source.indexOf(
                "ProbeResult runSoftwareAmbeProductionRfInfrastructurePreflightNoRf()",
                entryStart);
        String entry = source.substring(entryStart, entryEnd);
        assertTrue(entry.contains("false, true, true"));
        assertFalse(entry.contains("AT+DMOPTT"));
        int offConfirmed = source.indexOf(
                "生产同构完整RF关闭确认");
        int releaseAttestation = source.indexOf(
                "persistProductionRfReleaseAttestation(rfOffMarkerAfter",
                offConfirmed);
        int longEvidence = source.indexOf(
                "byte[] vectorAfter = readMemory", releaseAttestation);
        assertTrue(offConfirmed > 0);
        assertTrue(releaseAttestation > offConfirmed);
        assertTrue(longEvidence > releaseAttestation);
        assertTrue(source.contains(
                "production_rf_release_attestation.txt"));
        assertTrue(source.contains("launch_session_id="));
        assertTrue(source.contains("rf_active_gate=0"));
        assertTrue(source.contains("dmoptt_forbidden=true"));
    }

    @Test
    public void v389ProductionLaunchBoundariesAreClosedBeforeRf() throws Exception {
        String source = new String(Files.readAllBytes(workspacePath(
                "app/src/main/java/net/elfradio/h13interphoneprobe/InterphoneProbe.java")),
                StandardCharsets.UTF_8).replace("\r\n", "\n");
        int executorStart = source.indexOf(
                "private ProbeResult executeExternalDmrVlcSessionNoRf(");
        int executorEnd = source.indexOf(
                "ProbeResult runExternalDmrStep1OfflineHandshakeProof()",
                executorStart);
        assertTrue(executorStart > 0 && executorEnd > executorStart);
        String executor = source.substring(executorStart, executorEnd);
        assertTrue(executor.contains("finishExternalDmrLaunchFailure("));
        assertTrue(executor.contains("缺少同版本独立无HPI privacy预检"));
        assertTrue(source.contains("startup_rejected=true\\n"));
        assertTrue(source.contains("rf_prepare_executed=false\\n"));
        assertTrue(source.contains("work_mode_tx_sent=false\\n"));
        assertTrue(source.contains("vlc_sent=false\\n"));
        assertTrue(source.contains("data36_written=false\\n"));
        assertTrue(source.contains("rf_command_count=0\\n"));

        String host = new String(Files.readAllBytes(workspacePath(
                "../h13_radio/tools/h13_probe_session.ps1")),
                StandardCharsets.UTF_8).replace("\r\n", "\n");
        int installedIdentity = host.indexOf("$probeIdentity = "
                + "Get-InstalledProbeIdentity");
        int privacyGate = host.indexOf(
                "Assert-ProductionPrivacyAttestationBeforeIsolation",
                installedIdentity);
        int stopProduction = host.indexOf("Stop-Interphone -HardIsolation",
                installedIdentity);
        assertTrue(installedIdentity > 0);
        assertTrue(privacyGate > installedIdentity);
        assertTrue(stopProduction > privacyGate);
        assertTrue(host.contains("Clear-ProductionRfLaunchArtifacts"));
        assertTrue(host.contains("生产启动拒绝原子结果严格验收"));
        assertTrue(host.contains("独立RF硬截止达到绝对期限并强制确认"
                + "dmr_switch=0；本轮禁止PASS"));

        String hardStop = new String(Files.readAllBytes(workspacePath(
                "../h13_radio/tools/h13_external_dmr_rf_hard_stop.ps1")),
                StandardCharsets.UTF_8).replace("\r\n", "\n");
        assertTrue(hardStop.contains("startup_rejection_confirmed="));
        assertTrue(hardStop.contains("launch_session_id=$LaunchSessionId"));
        assertTrue(hardStop.contains("离线自测全部通过：5/5"));
    }

    @Test
    public void v390ProductionActivityRejectsBeforeRfWithCompleteAtomicFields()
            throws Exception {
        String mode = "software_ambe_production_full_morse_low_power_rf";
        String extra = MainActivity.launchRejectionExtra(mode,
                "asset_preflight", true, true, true, "ambe_asset");
        assertTrue(extra.contains("startup_rejected=true\n"));
        assertTrue(extra.contains("rf_prepare_executed=false\n"));
        assertTrue(extra.contains("work_mode_tx_sent=false\n"));
        assertTrue(extra.contains("vlc_sent=false\n"));
        assertTrue(extra.contains("data36_written=false\n"));
        assertTrue(extra.contains("watchdog_expired=false\n"));
        assertTrue(extra.contains("rf_command_count=0\n"));
        assertEquals(1, countOccurrences(extra, "rf_command_count=0\n"));

        String noRf = MainActivity.launchRejectionExtra(
                "software_ambe_measured_privacy_no_rf", "runProbe", true,
                true, false, "launch_validation");
        assertFalse(noRf.contains("startup_rejected="));
        assertFalse(noRf.contains("rf_prepare_executed="));

        String activity = new String(Files.readAllBytes(workspacePath(
                "app/src/main/java/net/elfradio/h13interphoneprobe/MainActivity.java")),
                StandardCharsets.UTF_8).replace("\r\n", "\n");
        int handler = activity.indexOf("private void handleLaunchIntent(");
        int concurrentGate = activity.indexOf(
                "if (acceptedLaunchSessionId != null || runInProgress.get())",
                handler);
        int validation = activity.indexOf("validateLaunchRequest(", handler);
        int firstSessionAssignment = activity.indexOf(
                "probe.setLaunchSessionId(sessionId)", handler);
        int acceptedSession = activity.indexOf(
                "acceptedLaunchSessionId = sessionId", handler);
        assertTrue(handler >= 0 && concurrentGate > handler);
        assertTrue(validation > concurrentGate);
        assertTrue(firstSessionAssignment > concurrentGate);
        assertTrue(acceptedSession > firstSessionAssignment);
        assertTrue(activity.contains(
                "launchRejectionExtra(probeMode, \"asset_preflight\""));
    }

    @Test
    public void v394HostCutoffUsesEarliestPossibleRfTime()
            throws Exception {
        String host = new String(Files.readAllBytes(workspacePath(
                "../h13_radio/tools/h13_probe_session.ps1")),
                StandardCharsets.UTF_8).replace("\r\n", "\n");
        assertTrue(host.contains("$rfMayStartEpoch + 31"));
        assertTrue(host.contains(
                "device_marker=rf_preparation_observed=true"));
        assertTrue(host.contains("确认时间超过RF期限"));
        assertTrue(host.contains("截止器退出凭证8/8"));
        assertEquals(1, countOccurrences(host, "宿主最终判定=PASS"));
        int hardStopArbitration = host.indexOf(
                "独立RF硬截止进程结束 exit=");
        int finalVerdict = host.indexOf("宿主最终判定=PASS");
        assertTrue(hardStopArbitration > 0);
        assertTrue(finalVerdict > hardStopArbitration);
        int saveOutput = host.indexOf(
                "function Save-ProductionRfHardStopProcessOutput");
        int normalExitCheck = host.indexOf(
                "function Test-ProductionRfHardStopNormalExitLog", saveOutput);
        assertTrue(saveOutput > 0 && normalExitCheck > saveOutput);
        String saveOutputBody = host.substring(saveOutput, normalExitCheck);
        assertTrue(saveOutputBody.contains(
                "$Process.HardStopStdoutTask.IsCompleted"));
        assertTrue(saveOutputBody.contains(
                "$Process.HardStopStderrTask.IsCompleted"));

        String hardStop = new String(Files.readAllBytes(workspacePath(
                "../h13_radio/tools/h13_external_dmr_rf_hard_stop.ps1")),
                StandardCharsets.UTF_8).replace("\r\n", "\n");
        assertTrue(hardStop.contains("Test-ExactSingleHardStopField"));
        assertTrue(hardStop.contains("Test-HardStopReleaseEvidence"));
        assertTrue(hardStop.contains("Test-HardStopStartupRejectionEvidence"));
        assertTrue(hardStop.contains("if ($stdoutTask.IsCompleted)"));
        assertTrue(hardStop.contains("if ($stderrTask.IsCompleted)"));
    }

    @Test
    public void v394RfWindowAttestationsHaveDistinctExactFields() {
        String session = "0123456789abcdef0123456789abcdef";
        String preparation =
                InterphoneProbe.buildProductionRfPreparationAttestation(
                        session, 1787097605L, 123456L);
        assertTrue(preparation.contains(
                "build=3.94-production-rf-state-attestation\n"));
        assertTrue(preparation.contains("versionCode=394\n"));
        assertTrue(preparation.contains("launch_session_id=" + session + "\n"));
        assertTrue(preparation.contains("rf_preparation_armed=true\n"));
        assertTrue(preparation.contains("rf_may_start_epoch=1787097605\n"));
        assertTrue(preparation.contains(
                "rf_may_start_monotonic_ms=123456\n"));
        assertFalse(preparation.contains("rf_window_started=true\n"));
        String text = InterphoneProbe.buildProductionRfWindowStartAttestation(
                session, 1787097605L, 123456L);
        assertTrue(text.contains("build=3.94-production-rf-state-attestation\n"));
        assertTrue(text.contains("versionCode=394\n"));
        assertTrue(text.contains("launch_session_id=" + session + "\n"));
        assertTrue(text.contains("rf_window_started=true\n"));
        assertTrue(text.contains("rf_window_started_epoch=1787097605\n"));
        assertTrue(text.contains("rf_window_started_monotonic_ms=123456\n"));
        assertTrue(text.contains("rf_timeout_sec=30\n"));
        assertEquals(1, countOccurrences(text, "launch_session_id="));
        assertEquals(1, countOccurrences(text, "rf_window_started_epoch="));
        assertEquals(1, countOccurrences(text, "rf_timeout_sec="));
    }

    @Test
    public void v394RfWindowAttestationRejectsInvalidInputs() {
        String valid = "0123456789abcdef0123456789abcdef";
        for (String session : new String[] {null, "", "ABCDEF", valid + "0"}) {
            try {
                InterphoneProbe.buildProductionRfWindowStartAttestation(
                        session, 1L, 0L);
                fail("非法会话号未拒绝");
            } catch (IllegalArgumentException expected) {
                // 预期拒绝。
            }
        }
        try {
            InterphoneProbe.buildProductionRfWindowStartAttestation(
                    valid, 0L, 0L);
            fail("非法epoch未拒绝");
        } catch (IllegalArgumentException expected) {
            // 预期拒绝。
        }
        try {
            InterphoneProbe.buildProductionRfWindowStartAttestation(
                    valid, 1L, -1L);
            fail("非法单调时钟未拒绝");
        } catch (IllegalArgumentException expected) {
            // 预期拒绝。
        }
    }

    @Test
    public void v394RfPreparationPrecedesSysTickArmAndStartFollowsPrepWait()
            throws Exception {
        String source = new String(Files.readAllBytes(workspacePath(
                "app/src/main/java/net/elfradio/h13interphoneprobe/InterphoneProbe.java")),
                StandardCharsets.UTF_8).replace("\r\n", "\n");
        int productionBranch = source.indexOf("if (softwareProductionRf) {");
        int watchdog = source.indexOf("pttWatchdog.arm(pttOnAtMs);",
                productionBranch);
        int preparation = source.indexOf(
                "persistProductionRfPreparationAttestation(", watchdog);
        int systickWrite = source.indexOf(
                "writeMemoryWord(input, output, RAM_VECTOR_SYSTICK,",
                preparation);
        int prepWait = source.indexOf(
                "TIMING_PREP_WAIT_DONE_NO_BRIDGED_MEMREAD", systickWrite);
        int startAttestation = source.indexOf(
                "persistProductionRfWindowStartAttestation(", prepWait);
        int vlcBegin = source.indexOf("if (runVlcSession) {", startAttestation);
        assertTrue(productionBranch > 0);
        assertTrue(watchdog > productionBranch);
        assertTrue(preparation > watchdog);
        assertTrue(systickWrite > preparation);
        assertTrue(prepWait > systickWrite);
        assertTrue(startAttestation > prepWait);
        assertTrue(vlcBegin > startAttestation);
        assertTrue(source.substring(productionBranch, preparation).contains(
                "productionRfMayStartMonotonicMs = systickArmBeginAt"));
        assertTrue(source.substring(productionBranch, preparation).contains(
                "pttWatchdog.arm(pttOnAtMs)"));
    }

    @Test
    public void v394HostRfInfrastructureGateUsesMarked52BytePrepStub()
            throws Exception {
        String host = new String(Files.readAllBytes(workspacePath(
                "../h13_radio/tools/h13_probe_session.ps1")),
                StandardCharsets.UTF_8).replace("\r\n", "\n");
        assertTrue(host.contains("Length=52"));
        assertTrue(host.contains(
                "114AB48907B7811A36DCB65B0068FCCEC1E0529C8491CE56B0DF2D8FB6A73568"));
        assertTrue(host.contains("0x200031c0,0x50465221"));
        assertTrue(host.contains("旧40字节准备桩必须拒绝"));
    }

    @Test
    public void v394RfDeadlineStartsAtPlannedRfTime() {
        assertEquals(1787097605L,
                InterphoneProbe.epochSecondsForMonotonicTarget(
                        1787097600000L, 1000L, 6000L));
        try {
            InterphoneProbe.epochSecondsForMonotonicTarget(1L, 2L, 1L);
            fail("倒退的单调目标未拒绝");
        } catch (IllegalArgumentException expected) {
            // 预期拒绝。
        }
    }

    @Test
    public void v394ControlWriteProgressSeparatesFlushFromAck() {
        InterphoneProbe.ExternalDmrControlTxProgress progress =
                new InterphoneProbe.ExternalDmrControlTxProgress();
        progress.recordWriteCompleted(true, 0);
        assertFalse(progress.workModeTxSent());
        progress.recordWriteCompleted(true, 4);
        assertTrue(progress.workModeTxSent());
        assertFalse(progress.vlcSent());
        progress.recordWriteCompleted(false, 0);
        progress.recordWriteCompleted(false, 1);
        assertTrue(progress.vlcSent());
        assertEquals(2, progress.vlcSentCount());
    }

    @Test
    public void productionSetupKeepsVerifiedQuietBeforeActiveStream() {
        assertFalse(InterphoneProbe.shouldUseActiveFrameBoundary(true, true));
        assertFalse(InterphoneProbe.shouldUseActiveFrameBoundary(true, false));
        assertTrue(InterphoneProbe.shouldUseActiveFrameBoundary(false, true));
        assertFalse(InterphoneProbe.shouldUseActiveFrameBoundary(false, false));
    }

    private static byte[] wordsToLeBytes(int[] words) {
        byte[] out = new byte[words.length * 4];
        for (int index = 0; index < words.length; index++) {
            int value = words[index];
            out[index * 4] = (byte) value;
            out[index * 4 + 1] = (byte) (value >>> 8);
            out[index * 4 + 2] = (byte) (value >>> 16);
            out[index * 4 + 3] = (byte) (value >>> 24);
        }
        return out;
    }

    private static int countLeU32(byte[] bytes, int value) {
        int count = 0;
        for (int offset = 0; offset + 4 <= bytes.length; offset += 4) {
            int actual = (bytes[offset] & 0xff)
                    | ((bytes[offset + 1] & 0xff) << 8)
                    | ((bytes[offset + 2] & 0xff) << 16)
                    | ((bytes[offset + 3] & 0xff) << 24);
            if (actual == value) {
                count++;
            }
        }
        return count;
    }

    private static byte[] stockHpiResponseState(int length, int field,
            int pointer, int returnValue) {
        byte[] state = new byte[InterphoneProbe.FRAME_ADAPTER_RESPONSE_STATE_LENGTH];
        state[0] = (byte) length;
        state[1] = (byte) (length >>> 8);
        state[2] = (byte) field;
        putU32ForV219(state, 4, pointer);
        putU32ForV219(state, 8, returnValue);
        return state;
    }

    private static byte[] concatAmbePayload(byte[] ambe27) {
        // payload: type marker 0x01, len 0x1b, then 27 AMBE bytes => 29 bytes
        byte[] payload = new byte[29];
        payload[0] = 0x01;
        payload[1] = 0x1b;
        System.arraycopy(ambe27, 0, payload, 2, 27);
        return payload;
    }
}
