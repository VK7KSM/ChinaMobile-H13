package net.elfradio.h13dmrtx;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;

import org.junit.Test;

public final class DmrContractTest {
    private static final byte[] RUNTIME14 = OfflineContract.FROZEN_RUNTIME14;

    @Test
    public void postVlcThreeUnitModeIsRoutedToNoRfWorker() {
        String mode = DmrTxController.MODE_POST_VLC_THREE_LIVE_SOFTWARE_ONE_NO_RF;
        assertTrue(DeviceModePolicy.autoStartsWithoutRf(mode));
        assertTrue(DeviceModePolicy.showsRelayStatus(mode));
        assertTrue(DeviceModePolicy.autoStartsWithoutRf(
                DmrTxController.MODE_ACK_PACED_VLC_SOFTWARE_ONE_NO_RF));
        assertTrue(DeviceModePolicy.showsRelayStatus(
                DmrTxController.MODE_ACK_PACED_VLC_SOFTWARE_ONE_NO_RF));
        assertTrue(DeviceModePolicy.autoStartsWithoutRf(
                DmrTxController.MODE_ACK_PACED_VLC_SOFTWARE_TRIPLE_SOS_NO_RF));
        assertTrue(DeviceModePolicy.showsRelayStatus(
                DmrTxController.MODE_ACK_PACED_VLC_SOFTWARE_TRIPLE_SOS_NO_RF));
        assertTrue(DeviceModePolicy.autoStartsWithoutRf(
                DmrTxController.MODE_SETUP0_ONLY));
        assertFalse(DeviceModePolicy.showsRelayStatus(
                DmrTxController.MODE_SETUP0_ONLY));
        assertFalse(DeviceModePolicy.autoStartsWithoutRf(
                DmrTxController.MODE_RELAY_SOFTWARE_PRIVACY_TRIPLE_SOS_LOW_POWER_RF));
        assertTrue(DmrTxController.usesAckPacedTripleSos(
                DmrTxController.MODE_ACK_PACED_VLC_SOFTWARE_TRIPLE_SOS_NO_RF));
        assertTrue(DmrTxController.usesAckPacedTripleSos(
                DmrTxController.MODE_RELAY_SOFTWARE_PRIVACY_TRIPLE_SOS_LOW_POWER_RF));
        assertTrue(DmrTxController.requestsLowPowerRf(
                DmrTxController.MODE_RELAY_SOFTWARE_PRIVACY_TRIPLE_SOS_LOW_POWER_RF));
        assertFalse(DmrTxController.requestsLowPowerRf(
                DmrTxController.MODE_ACK_PACED_VLC_SOFTWARE_TRIPLE_SOS_NO_RF));
        assertFalse(DeviceModePolicy.autoStartsWithoutRf("unknown_mode"));
        // v0.87 短素材：无射频变体必须能自动启动，低功率发射变体必须不在
        // 这条路径上（它要走带rf_permission的射频分支）。历史上v0.75和
        // v0.87都因为新模式没在设备侧分派登记而空等整轮再超时，这条断言
        // 把那类问题挡在上机之前。
        assertTrue(DeviceModePolicy.autoStartsWithoutRf(
                DmrTxController.MODE_SPEECH_SHORT_ABC_NO_RF));
        assertTrue(DeviceModePolicy.showsRelayStatus(
                DmrTxController.MODE_SPEECH_SHORT_ABC_NO_RF));
        assertFalse(DeviceModePolicy.autoStartsWithoutRf(
                DmrTxController.MODE_SPEECH_SHORT_ABC_LOW_POWER_RF));
        assertTrue(DmrTxController.requestsLowPowerRf(
                DmrTxController.MODE_SPEECH_SHORT_ABC_LOW_POWER_RF));
        assertFalse(DmrTxController.requestsLowPowerRf(
                DmrTxController.MODE_SPEECH_SHORT_ABC_NO_RF));
        assertTrue(DmrTxController.usesAckPacedTripleSos(
                DmrTxController.MODE_SPEECH_SHORT_ABC_LOW_POWER_RF));
        // 两个短素材模式必须用 27 字节 DMR 格式：DMR 每突发 3 帧 27 字节
        // 60 毫秒；36 字节/80 毫秒是接口文档里 dPMR 的定义。2026-09-19 用
        // 36 字节格式发射两次，射频与呼叫参数全对但对端只听到机械噪音。
        assertEquals(DmrProtocol.VoiceFormat.CHAN_D27_TYPE3,
                DmrTxController.voiceFormatForMode(
                        DmrTxController.MODE_SPEECH_SHORT_ABC_NO_RF));
        assertEquals(DmrProtocol.VoiceFormat.CHAN_D27_TYPE3,
                DmrTxController.voiceFormatForMode(
                        DmrTxController.MODE_SPEECH_SHORT_ABC_LOW_POWER_RF));
        // 素材帧数必须同时被 3 和 4 整除，两种格式都不补位。
        assertEquals(0, RealtimeRelay.SPEECH_SHORT_ABC_FRAMES % 3);
        assertEquals(0, RealtimeRelay.SPEECH_SHORT_ABC_FRAMES % 4);
        assertEquals(68, DmrTxController.expectedUnitsFor(
                RealtimeRelay.SPEECH_SHORT_ABC_BYTES,
                DmrProtocol.VoiceFormat.CHAN_D27_TYPE3));
    }

    @Test
    public void codecWriteFramesMatchStockChain() {
        // 原厂外部DMR配置链在工作模式之后写的五个页/寄存器，线上字节
        // 与旧探针 createAnalogCodecWriteFrame 逐字节相同。见 2.8.35。
        // 第 0 条：PROCESS_MODE(2)，厂商外部编码 DMR 开呼在工作模式之后发它。
        assertEquals("84 a9 61 00 02 00 1a 02",
                Bytes.hex(DmrProtocol.codec(0, 0x14)));
        assertEquals(0, DmrProtocol.codecPacketType(0));
        assertEquals(0x1a, DmrProtocol.codecAckField(0));
        assertEquals(0x40, DmrProtocol.codecPacketType(1));
        assertEquals(0x17, DmrProtocol.codecAckField(1));
        assertEquals("84 a9 61 00 06 40 00 01 10 40 00 00",
                Bytes.hex(DmrProtocol.codec(1, 0x14)));
        assertEquals("84 a9 61 00 06 40 00 01 3b 11 00 00",
                Bytes.hex(DmrProtocol.codec(2, 0x14)));
        assertEquals("84 a9 61 00 06 40 00 00 56 f3 00 00",
                Bytes.hex(DmrProtocol.codec(3, 0x14)));
        assertEquals("84 a9 61 00 06 40 00 00 57 ba 00 00",
                Bytes.hex(DmrProtocol.codec(4, 0x14)));
        // 最后一条是增益，随信道档位而变，不是常量。
        assertEquals("84 a9 61 00 06 40 00 00 58 14 00 00",
                Bytes.hex(DmrProtocol.codec(5, 0x14)));
        assertEquals("84 a9 61 00 06 40 00 00 58 00 00 00",
                Bytes.hex(DmrProtocol.codec(5, 0x00)));
        // 确认帧：包类型 0x40、正文 {0x17, 0x00}。
        byte[] ack = HpiCodec.frame(DmrProtocol.CODEC_PACKET_TYPE,
                new byte[] {(byte) DmrProtocol.CODEC_ACK_FIELD, 0x00});
        assertEquals("84 a9 61 00 02 40 17 00", Bytes.hex(ack));
        assertTrue(DmrProtocol.exactStatusAck(ack,
                DmrProtocol.CODEC_PACKET_TYPE,
                DmrProtocol.CODEC_ACK_FIELD));
        // 麦克风增益档位取自信道配置第14字段。
        assertEquals(2, DmrProtocol.micGainPreset(
                "433550000,433550000,13,99,12345678,directmode,group,"
                + "slot1,slot1,on,low,8,2,2,99"));
    }

    @Test
    public void privacyScramblingWouldGarbleAClearReceiver() {
        // 2026-09-19 首次真机发射的根因固化：encryptOnOff=off 时固件不建立
        // 密钥记录、不做隐私处理，主机送什么比特就发什么。若继续走加扰
        // 通路，空口跑的是 RC4 加扰后的 49 位参数，而对端按明文解——实测
        // 表现为功率、时序、呼叫参数全对，唯独语音是机械噪音。
        // 这条断言证明加扰确实改变了大量比特，因此"明文接收端拿到的不是
        // 原始语音参数"是必然而非偶然。
        // 每帧 9 字节承载 49 位：前 6 字节全是数据，第 7 字节只有最高位
        // 是数据，其余填充位必须为零，否则 privacy 输入校验会拒绝。
        byte[] plain49 = new byte[9 * 20];
        for (int frame = 0; frame < 20; frame++) {
            int base = frame * 9;
            for (int b = 0; b < 6; b++) {
                plain49[base + b] = (byte) (frame * 31 + b * 37 + 11);
            }
            plain49[base + 6] = (byte) ((frame % 2) == 0 ? 0x80 : 0x00);
            plain49[base + 7] = 0;
            plain49[base + 8] = 0;
        }
        DmrPrivacy privacy = DmrPrivacy.fromRuntime14(RUNTIME14);
        byte[] scrambled = privacy.encrypt49BitParameters(plain49);
        int differing = 0;
        int total = 0;
        for (int frame = 0; frame < plain49.length / 9; frame++) {
            for (int bit = 0; bit < 49; bit++) {
                int offset = frame * 9 + bit / 8;
                int mask = 1 << (7 - bit % 8);
                total++;
                if ((plain49[offset] & mask) != (scrambled[offset] & mask)) {
                    differing++;
                }
            }
        }
        // 加扰必须改变可观比例的比特；若接近 0 则说明密钥流退化成空操作。
        assertTrue("加扰改变的比特比例过低: " + differing + "/" + total,
                differing > total / 5);
        // 自己解扰必须能还原，确认差异来自加扰而不是通路本身有损。
        DmrPrivacy again = DmrPrivacy.fromRuntime14(RUNTIME14);
        assertArrayEquals(plain49, again.decrypt49BitParameters(scrambled));
    }

    @Test
    public void protocolMatchesProvenControlFrames() {
        assertTrue(DmrTxController.firmwareContractWellFormed());
        assertEquals(500, DmrTxController.POST_BRIDGE_SETTLE_MS);
        assertEquals(500, DmrTxController.RELAY_VLC_ACK_DURATION_MS);
        assertEquals(1000, DmrTxController.RELAY_PRE_VLC2_WAIT_MS);
        assertEquals(1500, DmrTxController.RELAY_CREDIT_TIMEOUT_MS);
        assertTrue(DmrTxController.firstBridgeBudgetWellFormed());
        assertTrue(DmrTxController.firstBridgeRelayBudgetWellFormed());
        assertTrue(DmrTxController.firstBridgeRfCleanupBudgetWellFormed());
        assertTrue(DmrTxController.rfHoldWindowWellFormed());
        assertTrue(DmrTxController.ackPacedActiveRfBudgetWellFormed());
        assertTrue(DmrTxController.secondBridgeBudgetWellFormed());
        // speech_az09素材：480个27字节单元，每单元60毫秒（1440帧AMBE÷3）。
        // v0.85之前FIRST_BRIDGE_EXIT_MS=33500，下面这条断言会失败——
        // 这正是2026-09-19定位到的"供数中途第一桥被设备侧自己的绝对退出
        // 计数器摘除、模块回到文本命令模式"的根因。修复后必须为真。
        assertTrue(DmrTxController.firstBridgeBudgetWellFormedFor(480, 60L));
        // 旧的三遍SOS素材（78个36字节/104个27字节单元）换算后的通用版本
        // 结果要和historical写死版本一致，确认新旧算式没有分叉。
        assertTrue(DmrTxController.firstBridgeBudgetWellFormedFor(
                RealtimeRelay.TRIPLE_SOS_UNITS, TxPlan.UNIT_INTERVAL_MS));
        // speech_short_abc素材（v0.87首次真机发射验证）：50个历史36字节
        // 单元、每单元80毫秒（200帧AMBE÷4）。既要撑得到第一桥收尾，也要
        // 满足RF专用的保持窗和30秒单次发射硬上限——这是发射前必须为真的
        // 门槛，不是事后补的回归测试。
        assertTrue(DmrTxController.firstBridgeBudgetWellFormedFor(68, 60L));
        assertTrue(DmrTxController.ackPacedActiveRfBudgetWellFormedFor(
                68, 60L));
        // 旧三遍SOS的RF专用通用版本同样要和写死版本一致。
        assertTrue(DmrTxController.ackPacedActiveRfBudgetWellFormedFor(
                RealtimeRelay.TRIPLE_SOS_UNITS, TxPlan.UNIT_INTERVAL_MS));
        // 30秒硬上限是操作安全约束：换算成分钟级的假想单元数必须被算法
        // 正确拒绝，防止以后有人不小心把素材做得太长又没人发现。
        assertFalse(DmrTxController.ackPacedActiveRfBudgetWellFormedFor(
                400, 80L));
        assertArrayEquals(new byte[] {
                0x01, 0x01, 0x01, 0x00, 0x00,
                0x12, 0x34, 0x56, 0x78, (byte) 0x90,
                0x12, 0x34, 0x56, 0x78
        }, RUNTIME14);
        DmrProtocol.Session session = DmrProtocol.session(13, 99, 0, RUNTIME14);
        assertEquals("84 a9 61 00 02 00 19 00",
                Bytes.hex(session.setup(0)));
        assertEquals("84 a9 61 00 02 00 02 18",
                Bytes.hex(session.setup(1)));
        assertEquals("84 a9 61 00 02 00 3e 60",
                Bytes.hex(session.setup(2)));
        assertEquals("84 a9 61 00 02 05 6f 81",
                Bytes.hex(session.setup(3)));
        assertEquals("84 a9 61 00 04 00 18 02 00 00",
                Bytes.hex(session.setup(4)));
        assertEquals("84 a9 61 00 02 00 3e 00",
                Bytes.hex(session.cleanup(0)));
        assertEquals("84 a9 61 00 04 00 18 00 00 00",
                Bytes.hex(session.cleanup(1)));
        assertEquals(0x3e, session.cleanupField(0));
        assertEquals(0x18, session.cleanupField(1));
        assertEquals(2, DmrProtocol.CLEANUP_COUNT);

        assertEquals("84 a9 61 00 0c 05 43 01 09 00 00 40 00 00 63 00 00 0d",
                Bytes.hex(session.vlc(0)));
        assertEquals("84 a9 61 00 0d 05 43 00 0a 01 10 01 12 34 56 78 00 00 63 00",
                Bytes.hex(session.vlc(2)));
        assertEquals("84 a9 61 00 05 05 43 1f 02 00 09 00",
                Bytes.hex(session.vlc(3)));
        assertEquals("84 a9 61 00 0c 05 43 02 09 00 00 40 00 00 63 00 00 0d",
                Bytes.hex(session.terminationVlc()));
        assertEquals(1, DmrProtocol.TERMINATION_COUNT);
    }

    @Test
    public void continuousBodyMustFinishBeforeRemainingVlc() {
        assertEquals(0, DmrTxController.relayUnitsRequiredBeforeVlc(0, 78));
        for (int index = 1; index < DmrProtocol.VLC_COUNT; index++) {
            assertEquals(78,
                    DmrTxController.relayUnitsRequiredBeforeVlc(index, 78));
        }
        assertThrows(IllegalArgumentException.class,
                () -> DmrTxController.relayUnitsRequiredBeforeVlc(-1, 78));
        assertThrows(IllegalArgumentException.class,
                () -> DmrTxController.relayUnitsRequiredBeforeVlc(0, 0));
    }

    @Test
    public void tripleSosPlayoutWindowPreservesAllSeventyEightUnits() {
        assertEquals(6240L, DmrTxController.TRIPLE_SOS_PLAYOUT_MS);
        assertEquals(16240L,
                DmrTxController.postVlcPlayoutTargetAt(10000L, 78));
        assertThrows(IllegalArgumentException.class,
                () -> DmrTxController.postVlcPlayoutTargetAt(0, 78));
        assertThrows(IllegalArgumentException.class,
                () -> DmrTxController.postVlcPlayoutTargetAt(10000L, 0));
    }

    @Test
    public void strictAckRejectsTrailingAndWrongField() {
        byte[] good = HpiCodec.frame(0, new byte[] {0x19, 0x00});
        assertTrue(DmrProtocol.exactStatusAck(good, 0, 0x19));
        assertFalse(DmrProtocol.exactStatusAck(
                HpiCodec.join(Arrays.asList(good, good)), 0, 0x19));
        assertFalse(DmrProtocol.exactStatusAck(
                HpiCodec.frame(0, new byte[] {0x19, 0x01}), 0, 0x19));
        byte[] vocoderOffAck = HpiCodec.frame(0, new byte[] {0x3e, 0x00});
        byte[] idleAck = HpiCodec.frame(0, new byte[] {0x18, 0x00});
        assertTrue(DmrProtocol.containsControlStatusAck(vocoderOffAck, 0, 0x3e));
        assertTrue(DmrProtocol.containsControlStatusAck(idleAck, 0, 0x18));
        assertTrue(DmrProtocol.containsControlStatusAck(
                HpiCodec.join(Arrays.asList(vocoderOffAck, idleAck)), 0, 0x3e));
        assertTrue(DmrProtocol.containsControlStatusAck(
                HpiCodec.join(Arrays.asList(vocoderOffAck, idleAck)), 0, 0x18));
        assertFalse(DmrProtocol.containsControlStatusAck(vocoderOffAck, 0, 0x18));
        assertFalse(DmrProtocol.exactStatusAck(
                HpiCodec.join(Arrays.asList(vocoderOffAck, idleAck)), 0, 0x3e));
    }

    @Test
    public void sessionRebuildGateRecognizesObservedPrewriteSignals() {
        assertEquals("hpi_application_ready_17_01",
                DmrProtocol.sessionRebuildSignal(new byte[] {
                        0x00, 0x00, (byte) 0x84, (byte) 0xa9, 0x61,
                        0x00, 0x02, 0x20, 0x17, 0x01
                }));
        assertEquals("hpi_chip_lowpwr_15_00",
                DmrProtocol.sessionRebuildSignal(HpiCodec.frame(0,
                        new byte[] {0x15, 0x00})));
        assertEquals("ascii_dmostartup",
                DmrProtocol.sessionRebuildSignal(
                        "\r\n+DMOSTARTUP:0\r\n".getBytes(
                                java.nio.charset.StandardCharsets.US_ASCII)));
    }

    @Test
    public void sessionRebuildGateDoesNotRejectEmptyOrApprovedAck() {
        assertEquals(null, DmrProtocol.sessionRebuildSignal(new byte[0]));
        assertEquals(null, DmrProtocol.sessionRebuildSignal(
                HpiCodec.frame(0, new byte[] {0x19, 0x00})));
        assertEquals(null, DmrProtocol.sessionRebuildSignal(
                HpiCodec.frame(0x20, new byte[] {0x17, 0x00})));
    }

    @Test
    public void memoryWritesReturnTheExactTransportResponse()
            throws Exception {
        byte[] response = HpiCodec.frame(0, new byte[] {0x15, 0x00});
        McuMemory memory = new McuMemory((command, timeoutMs) ->
                response.clone());
        assertArrayEquals(response, memory.writeByte(0x2000015c, 1));
        assertArrayEquals(response, memory.writeWord(0x2000003c,
                0x08021ddd));
    }

    @Test
    public void powerSaveContractAcceptsStableZeroAndOnePreimages()
            throws Exception {
        assertEquals(0, DmrTxController.requireStablePowerSaveValue(
                new byte[] {0}, new byte[] {0}, "入口"));
        assertEquals(1, DmrTxController.requireStablePowerSaveValue(
                new byte[] {1}, new byte[] {1}, "入口"));
    }

    @Test
    public void powerSaveContractRejectsDynamicUnknownAndMalformedValues() {
        assertThrows(java.io.IOException.class,
                () -> DmrTxController.requireStablePowerSaveValue(
                        new byte[] {0}, new byte[] {1}, "入口"));
        assertThrows(java.io.IOException.class,
                () -> DmrTxController.requireStablePowerSaveValue(
                        new byte[] {2}, new byte[] {2}, "入口"));
        assertThrows(java.io.IOException.class,
                () -> DmrTxController.requireStablePowerSaveValue(
                        new byte[0], new byte[] {0}, "入口"));
    }

    @Test
    public void vlcAckCanFollowCompleteAsynchronousFrames() {
        byte[] chan = HpiCodec.paddedFrame(0x20, new byte[29]);
        byte[] ack = HpiCodec.paddedFrame(0x20, new byte[] {0x43});
        assertTrue(DmrProtocol.containsVlcAck(
                HpiCodec.join(Arrays.asList(chan, ack))));
        assertFalse(DmrProtocol.containsVlcAck(chan));
    }

    @Test
    public void strictParserRequiresZeroPaddingForOddDeclaredLength() {
        byte[] padded = HpiCodec.paddedFrame(0x20, new byte[] {0x43});
        assertNotNull(HpiCodec.parseComplete(padded));
        assertEquals(null, HpiCodec.parseComplete(
                Arrays.copyOf(padded, padded.length - 1)));
        byte[] nonZeroPadding = padded.clone();
        nonZeroPadding[nonZeroPadding.length - 1] = 1;
        assertEquals(null, HpiCodec.parseComplete(nonZeroPadding));
    }

    @Test
    public void activeRouterRecognizesOnlyCompletePaddedFrames()
            throws Exception {
        byte[] frame = HpiCodec.paddedFrame(0x20, new byte[] {0x43});
        assertEquals(frame.length,
                ActiveHpiStreamRouter.firstCompleteFrameLength(frame));
        assertEquals(0, ActiveHpiStreamRouter.firstCompleteFrameLength(
                Arrays.copyOf(frame, frame.length - 1)));
        byte[] bad = frame.clone();
        bad[bad.length - 1] = 1;
        assertThrows(java.io.IOException.class,
                () -> ActiveHpiStreamRouter.firstCompleteFrameLength(bad));
    }

    @Test
    public void activeRouterPreservesCrossReadFragmentAndSeparatesAck()
            throws Exception {
        byte[] asynchronous = HpiCodec.paddedFrame(0x20, new byte[29]);
        byte[] ack = HpiCodec.paddedFrame(0x20, new byte[] {0x43});
        ActiveHpiStreamRouter router = new ActiveHpiStreamRouter();
        int split = ack.length - 2;
        byte[] first = HpiCodec.join(Arrays.asList(asynchronous,
                Arrays.copyOf(ack, split)));
        ActiveHpiStreamRouter.Read timedOut = router.readUntil(
                new ByteArrayInputStream(first), 10,
                DmrProtocol::containsVlcAck);
        assertArrayEquals(asynchronous, timedOut.observed);
        assertEquals(null, timedOut.matchedFrame);
        assertArrayEquals(Arrays.copyOf(ack, split), timedOut.carry);

        ActiveHpiStreamRouter.Read completed = router.readUntil(
                new ByteArrayInputStream(Arrays.copyOfRange(ack, split,
                        ack.length)), 50, DmrProtocol::containsVlcAck);
        assertArrayEquals(ack, completed.observed);
        assertArrayEquals(ack, completed.matchedFrame);
        assertEquals(0, completed.carry.length);
    }

    @Test
    public void clearChannelWirePreservesEveryEncodedAmbeByte()
            throws Exception {
        byte[] plain = Files.readAllBytes(asset(
                "morse_sos.ambe9_sequence.bin").toPath());
        TxPlan plan = TxPlan.create(plain);
        assertEquals(104, plan.plainAmbe().length / 9);
        assertEquals(26, plan.wireUnits().size());
        assertArrayEquals(plain, plan.channelAmbe());
        assertEquals(OfflineContract.GOLDEN_CLEAR_WIRE_SHA256,
                Bytes.sha256(HpiCodec.join(plan.wireUnits())));
        byte[] reconstructed = new byte[plain.length];
        int offset = 0;
        for (byte[] unit : plan.wireUnits()) {
            assertEquals(44, unit.length);
            assertEquals("84 a9 61 00 26 03 01 24",
                    Bytes.hex(Arrays.copyOf(unit, 8)));
            System.arraycopy(unit, 8, reconstructed, offset, 36);
            offset += 36;
        }
        assertEquals(plain.length, offset);
        assertArrayEquals(plain, reconstructed);
    }

    @Test
    public void plannedTimeCannotMasqueradeAsRfExecution() throws Exception {
        TxStateMachine machine = readyForRf();
        assertEquals(TxStateMachine.Phase.WAIT_RF_ATTESTATION,
                machine.phase());
        assertThrows(IllegalStateException.class,
                () -> machine.nextDataUnit(999999));
        assertEquals(TxStateMachine.Phase.FAILED, machine.phase());
    }

    @Test
    public void deadlineArmRequestIsBoundToCompletedNoRfBridge() {
        assertEquals("launch_session_id=20260821_010203_004_rf\n"
                        + "first_bridge_exit_confirmed=true\n"
                        + "rf_prepare_executed=false\n"
                        + "rf_may_be_active=false\n"
                        + "rf_timeout_sec=30\n",
                DmrTxController.deadlineArmRequestCredential(
                        "20260821_010203_004_rf"));
        assertThrows(IllegalArgumentException.class,
                () -> DmrTxController.deadlineArmRequestCredential(""));
    }

    @Test
    public void firstBridgeDeadlineArmRequestPrecedesRfMutation() {
        assertEquals("launch_session_id=20260823_010203_004_rf\n"
                        + "first_bridge_exit_confirmed=false\n"
                        + "rf_prepare_executed=false\n"
                        + "rf_may_be_active=false\n"
                        + "rf_window_kind=first_bridge\n"
                        + "rf_timeout_sec=30\n",
                DmrTxController.firstBridgeDeadlineArmRequestCredential(
                        "20260823_010203_004_rf"));
        assertThrows(IllegalArgumentException.class,
                () -> DmrTxController.firstBridgeDeadlineArmRequestCredential(""));
    }

    @Test
    public void markerAndGateBothRequired() throws Exception {
        TxStateMachine wrongMarker = readyForRf();
        assertThrows(IllegalStateException.class,
                () -> wrongMarker.attestActualRf(0, 1, 1000));
        TxStateMachine wrongGate = readyForRf();
        assertThrows(IllegalStateException.class,
                () -> wrongGate.attestActualRf(
                        TxStateMachine.RF_PREP_MARKER, 0, 1000));

        TxStateMachine accepted = readyForRf();
        accepted.attestActualRf(TxStateMachine.RF_PREP_MARKER, 1, 1000);
        assertEquals(TxStateMachine.Phase.WAIT_SECOND_BRIDGE,
                accepted.phase());
        accepted.attestSecondBridgeArmed(
                TxStateMachine.SECOND_BRIDGE_SYSTICK, 1010);
        byte[] first = accepted.nextDataUnit(1010);
        assertNotNull(first);
        accepted.recordDataWriteComplete(first, 1011);
        assertEquals(1, accepted.dataWritten());
    }

    @Test
    public void dataPacingRejectsCatchUpBurst() throws Exception {
        TxStateMachine machine = readyForRf();
        machine.attestActualRf(TxStateMachine.RF_PREP_MARKER, 1, 1000);
        machine.attestSecondBridgeArmed(
                TxStateMachine.SECOND_BRIDGE_SYSTICK, 1010);
        byte[] first = machine.nextDataUnit(1010);
        machine.recordDataWriteComplete(first, 1011);
        assertThrows(IllegalStateException.class,
                () -> machine.nextDataUnit(1132));
        assertEquals(TxStateMachine.Phase.FAILED, machine.phase());
    }

    @Test
    public void firstDataFlushDefinesPacingOriginNotRfPrepTime()
            throws Exception {
        TxStateMachine machine = readyForRf();
        machine.attestActualRf(TxStateMachine.RF_PREP_MARKER, 1, 1000);
        machine.attestSecondBridgeArmed(
                TxStateMachine.SECOND_BRIDGE_SYSTICK, 4000);
        byte[] first = machine.nextDataUnit(4000);
        machine.recordDataWriteComplete(first, 4002);
        byte[] second = machine.nextDataUnit(4082);
        machine.recordDataWriteComplete(second, 4083);
        assertEquals(2, machine.dataWritten());
        assertEquals(TxStateMachine.Phase.DATA, machine.phase());
    }

    @Test
    public void completeTwoBridgeSequenceRequiresBothExitAndRfOffEvidence()
            throws Exception {
        TxStateMachine machine = readyForRf();
        machine.attestActualRf(TxStateMachine.RF_PREP_MARKER, 1, 1000);
        machine.attestSecondBridgeArmed(
                TxStateMachine.SECOND_BRIDGE_SYSTICK, 1100);
        long firstFlush = 1101;
        for (int index = 0; index < TxPlan.EXPECTED_UNIT_COUNT; index++) {
            long call = index == 0 ? 1100
                    : firstFlush + index * TxPlan.UNIT_INTERVAL_MS;
            byte[] request = machine.nextDataUnit(call);
            machine.recordDataWriteComplete(request,
                    index == 0 ? firstFlush : call + 1);
        }
        assertEquals(TxStateMachine.Phase.WAIT_SECOND_BRIDGE_EXIT,
                machine.phase());
        machine.attestSecondBridgeExit(0, TxStateMachine.ORIGINAL_SYSTICK,
                0x47445242);
        assertEquals(TxStateMachine.Phase.WAIT_RF_OFF_ATTESTATION,
                machine.phase());
        machine.attestRfOff(TxStateMachine.RF_OFF_MARKER, 0);
        assertEquals(TxStateMachine.Phase.COMPLETE, machine.phase());
    }

    @Test
    public void setup0StrictEmptyAllowsExactlyOneIdenticalRetry()
            throws Exception {
        DmrProtocol.Session session = DmrProtocol.session(13, 99, 0,
                RUNTIME14);
        byte[] plain = Files.readAllBytes(asset(
                "morse_sos.ambe9_sequence.bin").toPath());
        TxStateMachine machine = new TxStateMachine(session,
                TxPlan.create(plain));
        byte[] first = machine.nextControlRequest();
        assertTrue(machine.abandonSetup0ForStrictEmptyRetry(new byte[0]));
        assertArrayEquals(first, machine.nextControlRequest());
        assertFalse(machine.abandonSetup0ForStrictEmptyRetry(new byte[0]));
        machine.acceptControlResponse(HpiCodec.frame(0,
                new byte[] {0x19, 0x00}));
        assertEquals(1, machine.setupAcks());
        assertTrue(machine.setup0RetryUsed());
    }

    @Test
    public void setup0OnlyStopsBeforeSetup1AndCanCloseAfterBridge()
            throws Exception {
        DmrProtocol.Session session = DmrProtocol.session(13, 99, 0,
                RUNTIME14);
        byte[] plain = Files.readAllBytes(asset(
                "morse_sos.ambe9_sequence.bin").toPath());
        TxStateMachine machine = new TxStateMachine(session,
                TxPlan.create(plain));
        assertArrayEquals(session.setup(0), machine.nextControlRequest());
        machine.acceptControlResponse(HpiCodec.frame(0,
                new byte[] {0x19, 0x00}));
        machine.stopAfterSetup0();
        assertThrows(IllegalStateException.class,
                machine::nextControlRequest);
    }

    @Test
    public void allFrozenPcmAndAmbeVectorsArePinnedAndDistinct()
            throws Exception {
        byte[] silencePcm = Files.readAllBytes(asset(
                "silence.pcm_s16le").toPath());
        byte[] tonePcm = Files.readAllBytes(asset(
                "tone_800hz.pcm_s16le").toPath());
        byte[] silenceAmbe = Files.readAllBytes(asset(
                "silence.ambe9_sequence.bin").toPath());
        byte[] toneAmbe = Files.readAllBytes(asset(
                "tone_800hz.ambe9_sequence.bin").toPath());
        assertEquals("AF06C95B066477742F815814F36B6C986CECA43765BC7B5FA978052EC736844F",
                Bytes.sha256(silencePcm));
        assertEquals("9A6147F4AE15E7D2F106CC929A9956A86971D382D1AC1D1A685407D4EB384CAC",
                Bytes.sha256(tonePcm));
        assertEquals("118DFE909D6CDEFE1A3FBA1ECB715AB7E55DB2979EEE9C169BC2D8824DC11B0D",
                Bytes.sha256(silenceAmbe));
        assertEquals("234EE9C8C7956943548352A0B40C8279511CA6E2E8215DF07A7249256BDF470A",
                Bytes.sha256(toneAmbe));
        assertFalse(Arrays.equals(silencePcm, tonePcm));
        assertFalse(Arrays.equals(silenceAmbe, toneAmbe));
        byte[] silence36 = Arrays.copyOf(silenceAmbe, 36);
        byte[] tone36 = Arrays.copyOf(toneAmbe, 36);
        assertEquals(RealtimeRelay.ENCODE_DMR_SILENCE_36_SHA256,
                Bytes.sha256(silence36));
        assertEquals(RealtimeRelay.ENCODE_DMR_TONE800_36_SHA256,
                Bytes.sha256(tone36));
        assertFalse(Arrays.equals(silence36, tone36));
        byte[] morseAmbe = Files.readAllBytes(asset(
                "morse_sos.ambe9_sequence.bin").toPath());
        byte[] morse36 = Arrays.copyOf(morseAmbe, 36);
        assertArrayEquals(tone36, morse36);
        assertEquals(RealtimeRelay.ENCODE_DMR_TONE800_36_SHA256,
                Bytes.sha256(morse36));
        assertThrows(IllegalArgumentException.class,
                () -> RealtimeRelay.requireFrozenEncodeDmrTone80036(silenceAmbe));
    }

    @Test
    public void precomputedEncodingAllowsOneSchedulerOutlier() {
        long[] frameUs = new long[104];
        Arrays.fill(frameUs, 7000L);
        frameUs[57] = 25806L;
        assertTrue(DmrTxController.encodingTimingAcceptable(frameUs));
    }

    @Test
    public void precomputedEncodingRejectsSustainedDeadlineFailure() {
        long[] frameUs = new long[104];
        Arrays.fill(frameUs, 7000L);
        for (int index = 0; index < 6; index++) {
            frameUs[index] = 25000L;
        }
        assertFalse(DmrTxController.encodingTimingAcceptable(frameUs));
    }

    @Test
    public void historicalVlc0FiftyTwoByteWindowKeepsUnitAfterFirstAck() {
        byte[] vlc0 = hex("84 a9 61 00 01 20 43 00 "
                + "84 a9 61 00 1d 20 01 1b "
                + "c8 05 e2 1c 07 68 87 8f 21 e8 03 b7 53 a7 27 17 8f 21 "
                + "e8 07 44 01 a7 34 f5 0f 21 00 "
                + "84 a9 61 00 02 20 17 0a");
        assertEquals(52, vlc0.length);
        assertTrue(DmrProtocol.containsVlcAck(vlc0));
        assertEquals("84 a9 61 00 01 20 43 00",
                Bytes.hex(DmrProtocol.firstVlcAckFrame(vlc0)));
        RealtimeRelay full = new RealtimeRelay(RUNTIME14);
        full.acceptRaw(vlc0);
        assertEquals(1, full.data27Count());

        RealtimeRelay truncated = new RealtimeRelay(RUNTIME14);
        truncated.acceptRaw(Arrays.copyOf(vlc0, 8));
        assertEquals(0, truncated.data27Count());
        assertTrue(DmrProtocol.containsVlcAck(Arrays.copyOf(vlc0, 8)));
    }

    @Test
    public void historicalVlc1WindowHasTwoUnitsAfterAck() {
        byte[] vlc1 = hex("84 a9 61 00 1d 20 01 1b "
                + "98 02 b9 4f a4 d3 df 8f 21 da 1d 4b 9e 41 f3 9e 8b 25 "
                + "89 03 d9 08 95 98 4f 8b 25 00 "
                + "84 a9 61 00 01 20 43 00 "
                + "84 a9 61 00 1d 20 01 1b "
                + "d8 05 fb 9f a2 61 97 8b 21 c8 02 40 5d e7 02 97 8b 21 "
                + "c8 07 67 2c 67 13 9d 0b 21 00 "
                + "84 a9 61 00 02 20 17 0a");
        assertEquals(88, vlc1.length);
        assertTrue(DmrProtocol.containsVlcAck(vlc1));
        RealtimeRelay relay = new RealtimeRelay(RUNTIME14);
        relay.acceptRaw(hex("84 a9 61 00 01 20 43 00 "
                + "84 a9 61 00 1d 20 01 1b "
                + "c8 05 e2 1c 07 68 87 8f 21 e8 03 b7 53 a7 27 17 8f 21 "
                + "e8 07 44 01 a7 34 f5 0f 21 00 "
                + "84 a9 61 00 02 20 17 0a"));
        relay.acceptRaw(vlc1);
        assertEquals(3, relay.data27Count());
        assertTrue(relay.readyToWrite());
        relay.takeRequest44();
        relay.markFlushed();
        relay.acceptRaw(hex("84 a9 61 00 02 03 01 00"));
        assertTrue(relay.creditAccepted());
    }

    @Test
    public void stateMachineAcceptsFirstWriteThenPausesThroughLaterVlc()
            throws Exception {
        DmrProtocol.Session session = DmrProtocol.session(13, 99, 0, RUNTIME14);
        TxStateMachine machine = new TxStateMachine(session, true);
        for (int index = 0; index < DmrProtocol.SETUP_COUNT; index++) {
            machine.nextControlRequest();
            machine.acceptControlResponse(HpiCodec.frame(
                    session.setupPacketType(index),
                    new byte[] {(byte) session.setupField(index), 0x00}));
        }
        machine.registerFullprepDeadline(1000);
        machine.beginVlcAfterFullprepDeadline(1000);
        assertArrayEquals(session.vlc(0), machine.nextControlRequest());
        machine.acceptControlResponse(hex("84 a9 61 00 01 20 43 00 "
                + "84 a9 61 00 1d 20 01 1b "
                + "c8 05 e2 1c 07 68 87 8f 21 e8 03 b7 53 a7 27 17 8f 21 "
                + "e8 07 44 01 a7 34 f5 0f 21 00 "
                + "84 a9 61 00 02 20 17 0a"));
        machine.markRealtimeRelayComplete();
        for (int index = 1; index < DmrProtocol.VLC_COUNT; index++) {
            assertArrayEquals(session.vlc(index), machine.nextControlRequest());
            machine.acceptControlResponse(HpiCodec.paddedFrame(
                    0x20, new byte[] {0x43}));
        }
        machine.noteAdditionalRelayUnitWritten();
        machine.noteAdditionalRelayUnitWritten();
        assertEquals(3, machine.dataWritten());
    }

    @Test
    public void frozenAssetUnit0MatchesV205ContractAndDiffersFromLiveRelay()
            throws Exception {
        byte[] asset = Files.readAllBytes(assetFile(
                "v090_chan_d_244units.bin").toPath());
        byte[] unit0 = RealtimeRelay.requireFrozenUnit0(asset);
        assertEquals(RealtimeRelay.FROZEN_UNIT0_SHA256, Bytes.sha256(unit0));
        assertEquals("99 ce a1 36 ef bd 08 ea 08 8a ff 81 56 a8 ce 08 "
                + "da 09 53 c2 9e 31 0e cc c1 83 06 ac fb a6 36 88 42 29 d0 05",
                Bytes.hex(unit0));
        byte[] vlc0 = hex("84 a9 61 00 1d 20 01 1b "
                + "c8 05 e2 1c 07 68 87 8f 21 e8 03 b7 53 a7 27 17 8f 21 "
                + "e8 07 44 01 a7 34 f5 0f 21 00 "
                + "84 a9 61 00 01 20 43 00");
        byte[] vlc1 = hex("84 a9 61 00 1d 20 01 1b "
                + "98 02 b9 4f a4 d3 df 8f 21 da 1d 4b 9e 41 f3 9e 8b 25 "
                + "89 03 d9 08 95 98 4f 8b 25 00 "
                + "84 a9 61 00 01 20 43 00 "
                + "84 a9 61 00 1d 20 01 1b "
                + "d8 05 fb 9f a2 61 97 8b 21 c8 02 40 5d e7 02 97 8b 21 "
                + "c8 07 67 2c 67 13 9d 0b 21 00");
        RealtimeRelay live = new RealtimeRelay(RUNTIME14);
        live.acceptRaw(vlc0);
        live.acceptRaw(vlc1);
        live.takeRequest44();
        assertEquals("realtime_stream", live.payloadSource());
        assertFalse(Arrays.equals(unit0, live.plain36()));

        RealtimeRelay fixed = new RealtimeRelay(RUNTIME14, unit0);
        fixed.acceptRaw(vlc0);
        fixed.acceptRaw(vlc1);
        byte[] request = fixed.takeRequest44();
        assertEquals("fixed_asset", fixed.payloadSource());
        assertArrayEquals(unit0, fixed.plain36());
        assertFalse(Arrays.equals(fixed.candidate36(), fixed.plain36()));
        assertEquals("0C7F9E861155A1EFBE3D04475000CE3AC354A713E7E9F18F2C05369D239FC497",
                Bytes.sha256(request));
        byte[] chanD108 = RealtimeRelay.requireFrozenChanD108(asset);
        assertEquals(RealtimeRelay.FROZEN_108_SHA256, Bytes.sha256(chanD108));
        assertEquals(RealtimeRelay.FROZEN_UNIT1_SHA256,
                Bytes.sha256(Arrays.copyOfRange(chanD108, 36, 72)));
        assertEquals(RealtimeRelay.FROZEN_UNIT2_SHA256,
                Bytes.sha256(Arrays.copyOfRange(chanD108, 72, 108)));
        RealtimeRelay.requireChanDLastNibbleZero(chanD108);
        RealtimeRelay threeChanD = new RealtimeRelay(RUNTIME14, chanD108,
                DmrTxController.RELAY_SOURCE_FIXED_ASSET_THREE);
        threeChanD.acceptRaw(vlc0);
        threeChanD.acceptRaw(vlc1);
        threeChanD.takeRequest44();
        threeChanD.markFlushed();
        threeChanD.acceptRaw(hex("84 a9 61 00 02 03 01 00"));
        threeChanD.consumeCredit();
        threeChanD.takeRequest44();
        threeChanD.markFlushed();
        threeChanD.acceptRaw(hex("84 a9 61 00 02 03 01 00"));
        threeChanD.consumeCredit();
        threeChanD.takeRequest44();
        threeChanD.markFlushed();
        threeChanD.acceptRaw(hex("84 a9 61 00 02 03 01 00"));
        threeChanD.consumeCredit();
        assertEquals("fixed_asset_three", threeChanD.payloadSource());
        assertEquals(3, threeChanD.unitsWritten());
        assertEquals(3, threeChanD.creditsConsumed());
        assertThrows(IllegalStateException.class, threeChanD::takeRequest44);
        assertEquals(DmrTxController.MODE_RELAY_FIXED_ASSET_THREE_NO_RF,
                "realtime_relay_fixed_asset_three_data36_no_rf");
        byte[] chanD180 = RealtimeRelay.requireFrozenChanD180(asset);
        assertEquals(RealtimeRelay.FROZEN_180_SHA256, Bytes.sha256(chanD180));
        assertEquals(RealtimeRelay.FROZEN_UNIT3_SHA256,
                Bytes.sha256(Arrays.copyOfRange(chanD180, 108, 144)));
        assertEquals(RealtimeRelay.FROZEN_UNIT4_SHA256,
                Bytes.sha256(Arrays.copyOfRange(chanD180, 144, 180)));
        DmrPrivacy hostChanD = DmrPrivacy.fromRuntime14(RUNTIME14);
        byte[] hostChanD180 = hostChanD.encrypt(chanD180);
        byte[] hostChanDUnit4 = Arrays.copyOfRange(hostChanD180, 144, 180);
        byte[] hostChanDRequest4 = DmrProtocol.data36(hostChanDUnit4);
        RealtimeRelay fiveChanD = new RealtimeRelay(RUNTIME14, chanD180,
                DmrTxController.RELAY_SOURCE_FIXED_ASSET_FIVE);
        fiveChanD.acceptRaw(vlc0);
        fiveChanD.acceptRaw(vlc1);
        byte[] fifthChanD = null;
        for (int index = 0; index < 5; index++) {
            byte[] next = fiveChanD.takeRequest44();
            if (index == 4) {
                fifthChanD = next;
            }
            fiveChanD.markFlushed();
            fiveChanD.acceptRaw(hex("84 a9 61 00 02 03 01 00"));
            fiveChanD.consumeCredit();
        }
        assertArrayEquals(hostChanDRequest4, fifthChanD);
        assertEquals("fixed_asset_five", fiveChanD.payloadSource());
        assertEquals(5, fiveChanD.unitsWritten());
        assertEquals(5, fiveChanD.creditsConsumed());
        assertEquals(DmrTxController.MODE_RELAY_FIXED_ASSET_FIVE_NO_RF,
                "realtime_relay_fixed_asset_five_data36_no_rf");
        byte[] chanD900 = RealtimeRelay.requireFrozenChanD900(asset);
        assertEquals(RealtimeRelay.FROZEN_SPEECH_900_SHA256,
                Bytes.sha256(chanD900));
        assertEquals(900, chanD900.length);
        assertEquals(25, RealtimeRelay.SPEECH_UNITS);
        assertFalse(RealtimeRelay.FROZEN_UNIT0_SHA256.equals(
                Bytes.sha256(Arrays.copyOf(chanD900, 36))));
        assertFalse(Arrays.equals(Arrays.copyOf(chanD900, 180), chanD180));
        DmrPrivacy hostSpeech = DmrPrivacy.fromRuntime14(RUNTIME14);
        RealtimeRelay twentyFive = new RealtimeRelay(RUNTIME14, chanD900,
                DmrTxController.RELAY_SOURCE_FIXED_ASSET_TWENTYFIVE);
        twentyFive.acceptRaw(vlc0);
        twentyFive.acceptRaw(vlc1);
        for (int index = 0; index < 25; index++) {
            byte[] next = twentyFive.takeRequest44();
            byte[] plain = Arrays.copyOfRange(chanD900, index * 36,
                    (index + 1) * 36);
            assertEquals(RealtimeRelay.FROZEN_SPEECH_UNIT_SHA256[index],
                    Bytes.sha256(plain));
            assertArrayEquals(plain, twentyFive.plain36());
            byte[] expected = DmrProtocol.data36(hostSpeech.encrypt(plain));
            assertArrayEquals(expected, next);
            assertEquals(RealtimeRelay.FROZEN_SPEECH_REQUEST_SHA256[index],
                    Bytes.sha256(next));
            twentyFive.markFlushed();
            twentyFive.acceptRaw(hex("84 a9 61 00 02 03 01 00"));
            twentyFive.consumeCredit();
        }
        assertEquals(100, hostSpeech.globalFrame());
        assertEquals("fixed_asset_twentyfive", twentyFive.payloadSource());
        assertEquals(25, twentyFive.unitsWritten());
        assertEquals(25, twentyFive.creditsConsumed());
        assertThrows(IllegalStateException.class, twentyFive::takeRequest44);
        assertEquals(DmrTxController.MODE_RELAY_FIXED_ASSET_TWENTYFIVE_NO_RF,
                "realtime_relay_fixed_asset_twentyfive_data36_no_rf");
        assertThrows(IllegalArgumentException.class, () ->
                new RealtimeRelay(RUNTIME14, Arrays.copyOf(asset, 899),
                        DmrTxController.RELAY_SOURCE_FIXED_ASSET_TWENTYFIVE));
        byte[] encodeDmrSilence = Files.readAllBytes(asset(
                "silence.ambe9_sequence.bin").toPath());
        byte[] silence36 = RealtimeRelay.requireFrozenEncodeDmrSilence36(
                encodeDmrSilence);
        assertEquals(RealtimeRelay.ENCODE_DMR_SILENCE_36_SHA256,
                Bytes.sha256(silence36));
        assertFalse(RealtimeRelay.FROZEN_UNIT0_SHA256.equals(
                Bytes.sha256(silence36)));
        assertEquals((byte) 0x6f, silence36[8]);
        RealtimeRelay encoded = new RealtimeRelay(RUNTIME14, silence36,
                DmrTxController.RELAY_SOURCE_ENCODE_DMR_SILENCE);
        encoded.acceptRaw(vlc0);
        encoded.acceptRaw(vlc1);
        encoded.takeRequest44();
        assertEquals("encode_dmr_silence", encoded.payloadSource());
        assertArrayEquals(silence36, encoded.plain36());
        assertFalse(Arrays.equals(encoded.candidate36(), encoded.plain36()));
        byte[] encodeDmrTone = Files.readAllBytes(asset(
                "tone_800hz.ambe9_sequence.bin").toPath());
        byte[] tone36 = RealtimeRelay.requireFrozenEncodeDmrTone80036(
                encodeDmrTone);
        assertEquals(RealtimeRelay.ENCODE_DMR_TONE800_36_SHA256,
                Bytes.sha256(tone36));
        assertFalse(RealtimeRelay.ENCODE_DMR_SILENCE_36_SHA256.equals(
                Bytes.sha256(tone36)));
        assertFalse(RealtimeRelay.FROZEN_UNIT0_SHA256.equals(
                Bytes.sha256(tone36)));
        RealtimeRelay encodedTone = new RealtimeRelay(RUNTIME14, tone36,
                DmrTxController.RELAY_SOURCE_ENCODE_DMR_TONE800);
        encodedTone.acceptRaw(vlc0);
        encodedTone.acceptRaw(vlc1);
        encodedTone.takeRequest44();
        assertEquals("encode_dmr_tone800", encodedTone.payloadSource());
        assertArrayEquals(tone36, encodedTone.plain36());
        byte[] encodeDmrMorse = Files.readAllBytes(asset(
                "morse_sos.ambe9_sequence.bin").toPath());
        byte[] morseUnique36 = RealtimeRelay
                .requireFrozenEncodeDmrMorseUnique36(encodeDmrMorse);
        assertEquals(RealtimeRelay.ENCODE_DMR_MORSE_UNIQUE_36_SHA256,
                Bytes.sha256(morseUnique36));
        assertFalse(RealtimeRelay.ENCODE_DMR_TONE800_36_SHA256.equals(
                Bytes.sha256(morseUnique36)));
        assertFalse(RealtimeRelay.ENCODE_DMR_SILENCE_36_SHA256.equals(
                Bytes.sha256(morseUnique36)));
        assertEquals(RealtimeRelay.ENCODE_DMR_TONE800_36_SHA256,
                Bytes.sha256(Arrays.copyOf(encodeDmrMorse, 36)));
        RealtimeRelay encodedMorse = new RealtimeRelay(RUNTIME14, morseUnique36,
                DmrTxController.RELAY_SOURCE_ENCODE_DMR_MORSE_UNIQUE);
        encodedMorse.acceptRaw(vlc0);
        encodedMorse.acceptRaw(vlc1);
        encodedMorse.takeRequest44();
        assertEquals("encode_dmr_morse_unique", encodedMorse.payloadSource());
        assertArrayEquals(morseUnique36, encodedMorse.plain36());
        assertEquals(DmrTxController.MODE_RELAY_ENCODE_DMR_MORSE_UNIQUE_NO_RF,
                "realtime_relay_encode_dmr_morse_unique_one_data36_no_rf");
        byte[] morse108 = RealtimeRelay
                .requireFrozenEncodeDmrMorseUnique108(encodeDmrMorse);
        assertEquals(RealtimeRelay.ENCODE_DMR_MORSE_UNIQUE_108_SHA256,
                Bytes.sha256(morse108));
        assertArrayEquals(morseUnique36, Arrays.copyOf(morse108, 36));
        assertEquals(RealtimeRelay.ENCODE_DMR_MORSE_UNIQUE_UNIT2_SHA256,
                Bytes.sha256(Arrays.copyOfRange(morse108, 36, 72)));
        assertEquals(RealtimeRelay.ENCODE_DMR_MORSE_UNIQUE_UNIT3_SHA256,
                Bytes.sha256(Arrays.copyOfRange(morse108, 72, 108)));
        assertFalse(RealtimeRelay.ENCODE_DMR_TONE800_108_SHA256.equals(
                Bytes.sha256(morse108)));
        RealtimeRelay encodedMorseThree = new RealtimeRelay(RUNTIME14, morse108,
                DmrTxController.RELAY_SOURCE_ENCODE_DMR_MORSE_UNIQUE_THREE);
        encodedMorseThree.acceptRaw(vlc0);
        encodedMorseThree.acceptRaw(vlc1);
        encodedMorseThree.takeRequest44();
        encodedMorseThree.markFlushed();
        encodedMorseThree.acceptRaw(hex("84 a9 61 00 02 03 01 00"));
        encodedMorseThree.consumeCredit();
        encodedMorseThree.takeRequest44();
        encodedMorseThree.markFlushed();
        encodedMorseThree.acceptRaw(hex("84 a9 61 00 02 03 01 00"));
        encodedMorseThree.consumeCredit();
        encodedMorseThree.takeRequest44();
        encodedMorseThree.markFlushed();
        encodedMorseThree.acceptRaw(hex("84 a9 61 00 02 03 01 00"));
        encodedMorseThree.consumeCredit();
        assertTrue(encodedMorseThree.creditAccepted());
        assertEquals(3, encodedMorseThree.unitsWritten());
        assertEquals(3, encodedMorseThree.creditsConsumed());
        assertEquals("encode_dmr_morse_unique_three",
                encodedMorseThree.payloadSource());
        assertEquals(DmrTxController.MODE_RELAY_ENCODE_DMR_MORSE_UNIQUE_THREE_NO_RF,
                "realtime_relay_encode_dmr_morse_unique_three_data36_no_rf");
        assertThrows(IllegalStateException.class,
                encodedMorseThree::takeRequest44);
        byte[] morse180 = RealtimeRelay
                .requireFrozenEncodeDmrMorseUnique180(encodeDmrMorse);
        assertEquals(RealtimeRelay.ENCODE_DMR_MORSE_UNIQUE_180_SHA256,
                Bytes.sha256(morse180));
        assertArrayEquals(morse108, Arrays.copyOf(morse180, 108));
        assertEquals(RealtimeRelay.ENCODE_DMR_MORSE_UNIQUE_UNIT4_SHA256,
                Bytes.sha256(Arrays.copyOfRange(morse180, 108, 144)));
        assertEquals(RealtimeRelay.ENCODE_DMR_MORSE_UNIQUE_UNIT5_SHA256,
                Bytes.sha256(Arrays.copyOfRange(morse180, 144, 180)));
        assertFalse(RealtimeRelay.ENCODE_DMR_TONE800_180_SHA256.equals(
                Bytes.sha256(morse180)));
        DmrPrivacy morseHost = DmrPrivacy.fromRuntime14(RUNTIME14);
        byte[] morseHost180 = morseHost.encrypt(morse180);
        byte[] morseHostUnit4 = Arrays.copyOfRange(morseHost180, 144, 180);
        byte[] morseHostRequest4 = DmrProtocol.data36(morseHostUnit4);
        DmrPrivacy morseReset = DmrPrivacy.fromRuntime14(RUNTIME14);
        assertFalse(Arrays.equals(morseHostUnit4,
                morseReset.encrypt(Arrays.copyOfRange(morse180, 144, 180))));
        RealtimeRelay encodedMorseFive = new RealtimeRelay(RUNTIME14, morse180,
                DmrTxController.RELAY_SOURCE_ENCODE_DMR_MORSE_UNIQUE_FIVE);
        encodedMorseFive.acceptRaw(vlc0);
        encodedMorseFive.acceptRaw(vlc1);
        byte[] morseFifth = null;
        for (int index = 0; index < 5; index++) {
            byte[] next = encodedMorseFive.takeRequest44();
            if (index == 4) {
                morseFifth = next;
            }
            encodedMorseFive.markFlushed();
            encodedMorseFive.acceptRaw(hex("84 a9 61 00 02 03 01 00"));
            encodedMorseFive.consumeCredit();
        }
        assertArrayEquals(morseHostRequest4, morseFifth);
        assertTrue(encodedMorseFive.creditAccepted());
        assertEquals(5, encodedMorseFive.unitsWritten());
        assertEquals(5, encodedMorseFive.creditsConsumed());
        assertEquals("encode_dmr_morse_unique_five",
                encodedMorseFive.payloadSource());
        assertEquals(DmrTxController.MODE_RELAY_ENCODE_DMR_MORSE_UNIQUE_FIVE_NO_RF,
                "realtime_relay_encode_dmr_morse_unique_five_data36_no_rf");
        assertEquals(DmrTxController.MODE_RELAY_ENCODE_DMR_MORSE_UNIQUE_FIVE_LOW_POWER_RF,
                "realtime_relay_encode_dmr_morse_unique_five_low_power_rf");
        assertThrows(IllegalStateException.class,
                encodedMorseFive::takeRequest44);
        assertFalse(Arrays.equals(encodedTone.candidate36(),
                encodedTone.plain36()));
        assertTrue(DmrTxController.isEncodeDmrRelaySource(
                DmrTxController.RELAY_SOURCE_ENCODE_DMR_TONE800));
        assertTrue(DmrTxController.isEncodeDmrRelaySource(
                DmrTxController.RELAY_SOURCE_ENCODE_DMR_TONE800_THREE));
        assertTrue(DmrTxController.isEncodeDmrRelaySource(
                DmrTxController.RELAY_SOURCE_ENCODE_DMR_TONE800_FIVE));
        assertTrue(DmrTxController.isEncodeDmrRelaySource(
                DmrTxController.RELAY_SOURCE_ENCODE_DMR_MORSE_UNIQUE));
        assertTrue(DmrTxController.isEncodeDmrRelaySource(
                DmrTxController.RELAY_SOURCE_ENCODE_DMR_MORSE_UNIQUE_THREE));
        assertTrue(DmrTxController.isEncodeDmrRelaySource(
                DmrTxController.RELAY_SOURCE_ENCODE_DMR_MORSE_UNIQUE_FIVE));
        assertFalse(DmrTxController.isEncodeDmrRelaySource("software_49bit"));
        byte[] tone108 = RealtimeRelay.requireFrozenEncodeDmrTone800108(
                encodeDmrTone);
        assertEquals(RealtimeRelay.ENCODE_DMR_TONE800_108_SHA256,
                Bytes.sha256(tone108));
        DmrPrivacy once = DmrPrivacy.fromRuntime14(RUNTIME14);
        DmrPrivacy chunked = DmrPrivacy.fromRuntime14(RUNTIME14);
        byte[] whole = once.encrypt(tone108);
        byte[] parts = new byte[108];
        System.arraycopy(chunked.encrypt(Arrays.copyOf(tone108, 36)),
                0, parts, 0, 36);
        System.arraycopy(chunked.encrypt(Arrays.copyOfRange(tone108, 36, 72)),
                0, parts, 36, 36);
        System.arraycopy(chunked.encrypt(Arrays.copyOfRange(tone108, 72, 108)),
                0, parts, 72, 36);
        assertArrayEquals(whole, parts);
        RealtimeRelay three = new RealtimeRelay(RUNTIME14, tone108,
                DmrTxController.RELAY_SOURCE_ENCODE_DMR_TONE800_THREE);
        three.acceptRaw(vlc0);
        three.acceptRaw(vlc1);
        three.takeRequest44();
        three.markFlushed();
        assertThrows(IllegalStateException.class, three::takeRequest44);
        three.acceptRaw(hex("84 a9 61 00 02 03 01 00"));
        assertTrue(three.lastUnitCreditPending());
        assertFalse(three.creditAccepted());
        three.consumeCredit();
        three.takeRequest44();
        three.markFlushed();
        three.acceptRaw(hex("84 a9 61 00 02 03 01 00"));
        three.consumeCredit();
        three.takeRequest44();
        three.markFlushed();
        three.acceptRaw(hex("84 a9 61 00 02 03 01 00"));
        three.consumeCredit();
        assertTrue(three.creditAccepted());
        assertEquals(3, three.unitsWritten());
        assertEquals(3, three.creditsConsumed());
        assertThrows(IllegalStateException.class, three::takeRequest44);

        byte[] tone180 = RealtimeRelay.requireFrozenEncodeDmrTone800180(
                encodeDmrTone);
        assertEquals(RealtimeRelay.ENCODE_DMR_TONE800_180_SHA256,
                Bytes.sha256(tone180));
        DmrPrivacy host = DmrPrivacy.fromRuntime14(RUNTIME14);
        byte[] host180 = host.encrypt(tone180);
        byte[] hostUnit4 = Arrays.copyOfRange(host180, 144, 180);
        byte[] hostRequest4 = DmrProtocol.data36(hostUnit4);
        DmrPrivacy reset = DmrPrivacy.fromRuntime14(RUNTIME14);
        assertFalse(Arrays.equals(hostUnit4,
                reset.encrypt(Arrays.copyOfRange(tone180, 144, 180))));
        DmrPrivacy split = DmrPrivacy.fromRuntime14(RUNTIME14);
        split.encrypt(Arrays.copyOf(tone180, 144));
        assertEquals(16, split.globalFrame());
        assertEquals(16, split.frameInSuperframe());
        byte[] crossed = new byte[36];
        System.arraycopy(split.encrypt(Arrays.copyOfRange(tone180, 144, 162)),
                0, crossed, 0, 18);
        assertEquals(18, split.globalFrame());
        assertEquals(0, split.frameInSuperframe());
        System.arraycopy(split.encrypt(Arrays.copyOfRange(tone180, 162, 180)),
                0, crossed, 18, 18);
        assertArrayEquals(hostUnit4, crossed);
        RealtimeRelay five = new RealtimeRelay(RUNTIME14, tone180,
                DmrTxController.RELAY_SOURCE_ENCODE_DMR_TONE800_FIVE);
        five.acceptRaw(vlc0);
        five.acceptRaw(vlc1);
        byte[] fifth = null;
        for (int index = 0; index < 5; index++) {
            byte[] next = five.takeRequest44();
            if (index == 4) {
                fifth = next;
            }
            five.markFlushed();
            five.acceptRaw(hex("84 a9 61 00 02 03 01 00"));
            five.consumeCredit();
        }
        assertArrayEquals(hostRequest4, fifth);
        assertTrue(five.creditAccepted());
        assertEquals(5, five.unitsWritten());
        assertEquals(5, five.creditsConsumed());
        assertThrows(IllegalStateException.class, five::takeRequest44);

        RealtimeRelay same = new RealtimeRelay(RUNTIME14, live.plain36());
        same.acceptRaw(vlc0);
        same.acceptRaw(vlc1);
        assertThrows(IllegalStateException.class, same::takeRequest44);
    }

    @Test
    public void chanDLastNibbleGateAcceptsH13ShapeAndRejectsDialectC() {
        byte[] packed = new byte[36];
        packed[8] = 0x05;
        packed[17] = 0x00;
        packed[26] = 0x0f;
        packed[35] = 0x08;
        RealtimeRelay.requireChanDLastNibbleZero(packed);
        packed[8] = 0x21;
        assertThrows(IllegalArgumentException.class,
                () -> RealtimeRelay.requireChanDLastNibbleZero(packed));
        packed[8] = 0x6f;
        packed[17] = 0x00;
        assertThrows(IllegalArgumentException.class,
                () -> RealtimeRelay.requireChanDLastNibbleZero(packed));
        assertThrows(IllegalArgumentException.class,
                () -> RealtimeRelay.requireChanDLastNibbleZero(new byte[8]));
    }

    @Test
    public void software49BitReplacementIsIndependentOfLiveRelay() {
        byte[] packed = new byte[36];
        packed[0] = (byte) 0xaa;
        packed[8] = 0x01;
        packed[9] = (byte) 0xbb;
        packed[17] = 0x02;
        packed[18] = (byte) 0xcc;
        packed[26] = 0x03;
        packed[27] = (byte) 0xdd;
        packed[35] = 0x04;
        RealtimeRelay.requireChanDLastNibbleZero(packed);
        byte[] vlc0 = hex("84 a9 61 00 1d 20 01 1b "
                + "c8 05 e2 1c 07 68 87 8f 21 e8 03 b7 53 a7 27 17 8f 21 "
                + "e8 07 44 01 a7 34 f5 0f 21 00 "
                + "84 a9 61 00 01 20 43 00");
        byte[] vlc1 = hex("84 a9 61 00 1d 20 01 1b "
                + "98 02 b9 4f a4 d3 df 8f 21 da 1d 4b 9e 41 f3 9e 8b 25 "
                + "89 03 d9 08 95 98 4f 8b 25 00 "
                + "84 a9 61 00 01 20 43 00 "
                + "84 a9 61 00 1d 20 01 1b "
                + "d8 05 fb 9f a2 61 97 8b 21 c8 02 40 5d e7 02 97 8b 21 "
                + "c8 07 67 2c 67 13 9d 0b 21 00");
        RealtimeRelay relay = new RealtimeRelay(RUNTIME14, packed,
                DmrTxController.RELAY_SOURCE_SOFTWARE_49BIT);
        relay.acceptRaw(vlc0);
        relay.acceptRaw(vlc1);
        byte[] request = relay.takeRequest44();
        assertEquals("software_49bit", relay.payloadSource());
        assertArrayEquals(packed, relay.plain36());
        assertFalse(Arrays.equals(relay.candidate36(), relay.plain36()));
        assertEquals("84 a9 61 00 26 03 01 24",
                Bytes.hex(Arrays.copyOf(request, 8)));
        assertEquals(DmrTxController.MODE_RELAY_SOFTWARE_49BIT_NO_RF,
                "realtime_relay_software_49bit_one_data36_no_rf");
        assertEquals(DmrTxController.MODE_RELAY_SOFTWARE_49BIT_TONE800_NO_RF,
                "realtime_relay_software_49bit_tone800_one_data36_no_rf");
        assertEquals(DmrTxController.MODE_RELAY_SOFTWARE_49BIT_MORSE_NO_RF,
                "realtime_relay_software_49bit_morse_one_data36_no_rf");
        assertEquals(3, DmrTxController.MORSE_49BIT_START_FRAME);
        assertEquals(DmrTxController.TONE800_49BIT_36_SHA256,
                "6A4C431FC334FAD68A48E214920E09773497F1FD36F6C2BFA4EED22334835992");
        assertEquals(DmrTxController.SILENCE_49BIT_36_SHA256,
                "EAE3B34A8967E93800F392DAFFE10EEE1DAFC0E6430003637B6E2BBCB1830F2B");
        RealtimeRelay tone = new RealtimeRelay(RUNTIME14, packed,
                DmrTxController.RELAY_SOURCE_SOFTWARE_49BIT_TONE800);
        tone.acceptRaw(vlc0);
        tone.acceptRaw(vlc1);
        tone.takeRequest44();
        assertEquals("software_49bit_tone800", tone.payloadSource());
        assertFalse(DmrTxController.SILENCE_49BIT_36_SHA256.equals(
                Bytes.sha256(tone.plain36())));
        assertThrows(IllegalArgumentException.class,
                () -> new RealtimeRelay(RUNTIME14, null, "software_49bit"));
    }

    @Test
    public void realtimeRelayWritesOnceBeforeVlc2AndAcceptsType3Credit() {
        byte[] vlc0 = hex("84 a9 61 00 1d 20 01 1b "
                + "c8 05 e2 1c 07 68 87 8f 21 e8 03 b7 53 a7 27 17 8f 21 "
                + "e8 07 44 01 a7 34 f5 0f 21 00 "
                + "84 a9 61 00 01 20 43 00");
        byte[] vlc1 = hex("84 a9 61 00 1d 20 01 1b "
                + "98 02 b9 4f a4 d3 df 8f 21 da 1d 4b 9e 41 f3 9e 8b 25 "
                + "89 03 d9 08 95 98 4f 8b 25 00 "
                + "84 a9 61 00 01 20 43 00 "
                + "84 a9 61 00 1d 20 01 1b "
                + "d8 05 fb 9f a2 61 97 8b 21 c8 02 40 5d e7 02 97 8b 21 "
                + "c8 07 67 2c 67 13 9d 0b 21 00");
        byte[] credit = hex("84 a9 61 00 02 03 01 00");
        RealtimeRelay relay = new RealtimeRelay(RUNTIME14);
        relay.acceptRaw(vlc0);
        assertEquals(1, relay.data27Count());
        assertFalse(relay.readyToWrite());
        relay.acceptRaw(vlc1);
        assertEquals(3, relay.data27Count());
        assertTrue(relay.readyToWrite());
        byte[] request = relay.takeRequest44();
        assertEquals(44, request.length);
        assertEquals("84 a9 61 00 26 03 01 24",
                Bytes.hex(Arrays.copyOf(request, 8)));
        assertFalse(Arrays.equals(relay.plain36(),
                Arrays.copyOfRange(request, 8, 44)));
        assertEquals(36, relay.plain36().length);
        assertArrayEquals(Arrays.copyOf(relay.combined81(), 36),
                relay.plain36());
        relay.markFlushed();
        relay.acceptRaw(credit);
        assertTrue(relay.creditAccepted());
        assertThrows(IllegalStateException.class, relay::takeRequest44);
    }

    @Test
    public void softwareReplacementCanUseExplicitOneUnitTriggerWithoutChangingDefault() {
        byte[] unit = hex("84 a9 61 00 1d 20 01 1b "
                + "c8 05 e2 1c 07 68 87 8f 21 e8 03 b7 53 a7 27 17 8f 21 "
                + "e8 07 44 01 a7 34 f5 0f 21 00");
        byte[] replacement = new byte[36];
        replacement[0] = 0x55;
        RealtimeRelay oneUnit = new RealtimeRelay(RUNTIME14, replacement,
                "one_unit_test", true,
                RealtimeRelay.SOFTWARE_REPLACEMENT_TRIGGER_UNITS);
        oneUnit.acceptRaw(unit);
        assertTrue(oneUnit.readyToWrite());
        assertEquals(RealtimeRelay.SOFTWARE_REPLACEMENT_TRIGGER_UNITS,
                oneUnit.requiredTriggerUnits());
        byte[] request = oneUnit.takeRequest44();
        assertEquals(44, request.length);
        assertEquals(27, oneUnit.combinedTrigger().length);
        assertEquals(0, oneUnit.combined81().length);
        assertArrayEquals(replacement, oneUnit.plain36());

        RealtimeRelay defaultRelay = new RealtimeRelay(RUNTIME14, replacement,
                "default_test", true);
        defaultRelay.acceptRaw(unit);
        defaultRelay.acceptRaw(unit);
        assertFalse(defaultRelay.readyToWrite());
        assertEquals(RealtimeRelay.REQUIRED_UNITS,
                defaultRelay.requiredTriggerUnits());
        assertThrows(IllegalArgumentException.class,
                () -> new RealtimeRelay(RUNTIME14, null, null, false,
                        RealtimeRelay.SOFTWARE_REPLACEMENT_TRIGGER_UNITS));
    }

    @Test
    public void externalSoftwareSourceCanWriteImmediatelyWithoutFakeChanD()
            throws Exception {
        byte[] replacement = new byte[36];
        replacement[0] = 0x55;
        replacement[35] = 0x21;
        RealtimeRelay relay = new RealtimeRelay(RUNTIME14, replacement,
                "ack_paced_external_source", true,
                RealtimeRelay.EXTERNAL_SOURCE_TRIGGER_UNITS);
        assertTrue(relay.readyToWrite());
        assertEquals(0, relay.data27Count());
        assertEquals(0, relay.combinedTrigger().length);
        byte[] request = relay.takeRequest44();
        assertArrayEquals(replacement, Arrays.copyOfRange(request, 8, 44));
        assertEquals(0, relay.combinedTrigger().length);
        relay.markFlushed();
        relay.acceptRaw(hex("84 a9 61 00 02 03 01 00"));
        assertTrue(relay.lastUnitCreditPending());
        relay.consumeCredit();
        assertEquals(1, relay.unitsWritten());
        assertEquals(1, relay.creditsConsumed());
        assertThrows(IllegalArgumentException.class,
                () -> new RealtimeRelay(RUNTIME14, null, null, false,
                        RealtimeRelay.EXTERNAL_SOURCE_TRIGGER_UNITS));
    }

    @Test
    public void postVlcTriggerExcludesAllPreArmUnitsAndWritesOnlyAtThirdFreshUnit()
            throws Exception {
        byte[] unit = hex("84 a9 61 00 1d 20 01 1b "
                + "c8 05 e2 1c 07 68 87 8f 21 e8 03 b7 53 a7 27 17 8f 21 "
                + "e8 07 44 01 a7 34 f5 0f 21 00");
        byte[] replacement = new byte[36];
        replacement[0] = 0x55;
        RealtimeRelay relay = new RealtimeRelay(RUNTIME14, replacement,
                "post_vlc_test", true, RealtimeRelay.REQUIRED_UNITS, true);

        relay.acceptRaw(unit);
        relay.acceptRaw(Arrays.copyOf(unit, 5));
        assertEquals(1, relay.preArmData27Count());
        assertFalse(relay.readyToWrite());
        relay.armPostVlcTrigger();
        assertEquals(41, relay.postVlcArmRawOffset());

        relay.acceptRaw(Arrays.copyOfRange(unit, 5, unit.length));
        assertEquals(2, relay.preArmData27Count());
        assertEquals(0, relay.data27Count());
        relay.acceptRaw(unit);
        assertFalse(relay.readyToWrite());
        relay.acceptRaw(unit);
        assertFalse(relay.readyToWrite());
        relay.acceptRaw(unit);
        assertTrue(relay.readyToWrite());
        assertEquals(3, relay.postArmData27Total());
        assertEquals(Arrays.asList(72L, 108L, 144L),
                relay.postArmData27StartOffsets());
        assertEquals(Arrays.asList(108L, 144L, 180L),
                relay.postArmData27EndOffsets());
        assertEquals(180, relay.acceptedRawStream().length);

        byte[] request = relay.takeRequest44();
        assertEquals(44, request.length);
        assertArrayEquals(replacement, Arrays.copyOfRange(request, 8, 44));
        relay.markFlushed();
        relay.acceptRaw(unit);
        relay.acceptRaw(hex("84 a9 61 00 02 03 01 00"));
        assertEquals(4, relay.postArmData27Total());
        assertEquals(1, relay.creditCount());
        assertEquals(224, relay.acceptedRawStream().length);
        assertFalse(relay.readyToWriteNext());
        assertThrows(IllegalStateException.class, relay::takeRequest44);
    }

    @Test
    public void postVlcObservationClassificationKeepsAllOutcomesDistinct() {
        assertEquals("POST_VLC_THREE_UNITS_NOT_REACHED",
                "POST_VLC_THREE_UNITS_NOT_REACHED");
        assertEquals("ONE_DATA36_SHORT_CREDIT",
                DmrTxController.classifyPostVlcOneData36Observation(
                        1, 0, 0, 8, false));
        assertEquals("ONE_DATA36_CHAN_D_CONTINUATION",
                DmrTxController.classifyPostVlcOneData36Observation(
                        0, 2, 0, 72, false));
        assertEquals("ONE_DATA36_MIXED_RESPONSE",
                DmrTxController.classifyPostVlcOneData36Observation(
                        1, 1, 0, 44, false));
        assertEquals("ONE_DATA36_MIXED_RESPONSE",
                DmrTxController.classifyPostVlcOneData36Observation(
                        0, 0, 1, 8, false));
        assertEquals("ONE_DATA36_SILENT",
                DmrTxController.classifyPostVlcOneData36Observation(
                        0, 0, 0, 0, false));
        assertEquals("MALFORMED_RESPONSE",
                DmrTxController.classifyPostVlcOneData36Observation(
                        0, 0, 0, 3, false));
        assertEquals("MALFORMED_RESPONSE",
                DmrTxController.classifyPostVlcOneData36Observation(
                        1, 0, 0, 8, true));
    }

    @Test
    public void postVlcStateMachineAllowsFiveVlcBeforeTheOnlyDataWrite() {
        DmrProtocol.Session session = DmrProtocol.session(13, 99, 0,
                RUNTIME14);
        TxStateMachine machine = new TxStateMachine(session, true, false,
                true);
        for (int index = 0; index < DmrProtocol.SETUP_COUNT; index++) {
            assertArrayEquals(session.setup(index), machine.nextControlRequest());
            machine.acceptControlResponse(HpiCodec.frame(
                    session.setupPacketType(index),
                    new byte[] {(byte) session.setupField(index), 0x00}));
        }
        machine.registerFullprepDeadline(1000);
        machine.beginVlcAfterFullprepDeadline(1000);
        for (int index = 0; index < DmrProtocol.VLC_COUNT; index++) {
            assertArrayEquals(session.vlc(index), machine.nextControlRequest());
            machine.acceptControlResponse(HpiCodec.paddedFrame(
                    0x20, new byte[] {0x43}));
            if (index < DmrProtocol.VLC_COUNT - 1) {
                assertFalse(machine.relayComplete());
                assertEquals(0, machine.dataWritten());
            }
        }
        assertEquals(TxStateMachine.Phase.WAIT_RELAY_COMPLETION,
                machine.phase());
        machine.markRealtimeRelayComplete();
        assertEquals(1, machine.dataWritten());
        machine.markRealtimeRelaySequenceComplete();
        assertArrayEquals(session.terminationVlc(),
                machine.nextControlRequest());
    }

    @Test
    public void realtimeRelayIgnoresVlcAckAndRequiresPostFlushCredit() {
        byte[] unit = hex("84 a9 61 00 1d 20 01 1b "
                + "c8 05 e2 1c 07 68 87 8f 21 e8 03 b7 53 a7 27 17 8f 21 "
                + "e8 07 44 01 a7 34 f5 0f 21 00");
        byte[] ack = HpiCodec.paddedFrame(0x20, new byte[] {0x43});
        byte[] credit = hex("84 a9 61 00 02 03 01 00");
        RealtimeRelay relay = new RealtimeRelay(RUNTIME14);
        relay.acceptRaw(Bytes.concat(unit, ack));
        relay.acceptRaw(unit);
        relay.acceptRaw(unit);
        byte[] request = relay.takeRequest44();
        assertEquals(44, request.length);
        relay.acceptRaw(credit);
        assertFalse(relay.creditAccepted());
        relay.markFlushed();
        relay.acceptRaw(ack);
        assertFalse(relay.creditAccepted());
        relay.acceptRaw(credit);
        assertTrue(relay.creditAccepted());
    }

    @Test
    public void relayVlcAckSurvivesSplitAcrossIncrementalReads() {
        byte[] ack = HpiCodec.paddedFrame(0x20, new byte[] {0x43});
        RealtimeRelay relay = new RealtimeRelay(RUNTIME14);
        int split = ack.length - 2;
        relay.acceptRaw(Arrays.copyOf(ack, split));
        int countBeforeWrite = relay.vlcAckCount();
        assertEquals(0, countBeforeWrite);
        assertArrayEquals(Arrays.copyOf(ack, split), relay.carrySnapshot());
        relay.acceptRaw(Arrays.copyOfRange(ack, split, ack.length));
        assertEquals(1, relay.vlcAckCount());
        assertEquals(0, relay.carrySnapshot().length);
        assertArrayEquals(ack, relay.requireSingleVlcAckAfter(countBeforeWrite));
    }

    @Test
    public void relayVlcAckBaselineWorksAcrossSuccessiveControlRequests() {
        byte[] ack = HpiCodec.paddedFrame(0x20, new byte[] {0x43});
        RealtimeRelay relay = new RealtimeRelay(RUNTIME14);
        relay.acceptRaw(ack);
        assertArrayEquals(ack, relay.requireSingleVlcAckAfter(0));
        int secondBeforeWrite = relay.vlcAckCount();
        relay.acceptRaw(Arrays.copyOf(ack, 5));
        assertEquals(secondBeforeWrite, relay.vlcAckCount());
        relay.acceptRaw(Arrays.copyOfRange(ack, 5, ack.length));
        assertArrayEquals(ack,
                relay.requireSingleVlcAckAfter(secondBeforeWrite));
    }

    @Test
    public void relayParserSeparatesGluedDataVlcAckAndCredit() {
        byte[] unit = hex("84 a9 61 00 1d 20 01 1b "
                + "c8 05 e2 1c 07 68 87 8f 21 e8 03 b7 53 a7 27 17 8f 21 "
                + "e8 07 44 01 a7 34 f5 0f 21 00");
        byte[] ack = HpiCodec.paddedFrame(0x20, new byte[] {0x43});
        byte[] credit = hex("84 a9 61 00 02 03 01 00");
        RealtimeRelay relay = new RealtimeRelay(RUNTIME14);
        relay.acceptRaw(Bytes.concat(unit, Bytes.concat(unit, unit)));
        assertTrue(relay.readyToWrite());
        relay.takeRequest44();
        relay.markFlushed();
        int countBeforeWrite = relay.vlcAckCount();
        relay.acceptRaw(Bytes.concat(ack, credit));
        assertArrayEquals(ack, relay.requireSingleVlcAckAfter(countBeforeWrite));
        assertEquals(1, relay.creditCount());
        assertTrue(relay.creditAccepted());
        assertArrayEquals(credit, relay.peekCredit());
    }

    @Test
    public void firstUnitAfterVlc0AckWritesAtFrameBoundaryBeforeVlc1() throws Exception {
        byte[] unit = hex("84 a9 61 00 1d 20 01 1b "
                + "c8 05 e2 1c 07 68 87 8f 21 e8 03 b7 53 a7 27 17 8f 21 "
                + "e8 07 44 01 a7 34 f5 0f 21 00");
        byte[] ack = HpiCodec.paddedFrame(0x20, new byte[] {0x43});
        byte[] replacement = new byte[36];
        replacement[0] = 0x55;
        RealtimeRelay relay = new RealtimeRelay(RUNTIME14, replacement,
                "incremental_boundary_test", true,
                RealtimeRelay.SOFTWARE_REPLACEMENT_TRIGGER_UNITS);
        relay.acceptRaw(ack);
        final int[] ackAtWrite = {-1};
        int writeOffset = DmrTxController.feedRelayIncrementally(relay,
                unit, Integer.MAX_VALUE, acceptedBytes -> {
                    if (!relay.readyToWrite()) {
                        return false;
                    }
                    ackAtWrite[0] = relay.vlcAckCount();
                    relay.takeRequest44();
                    relay.markFlushed();
                    return true;
                });
        assertEquals(36, writeOffset);
        assertEquals(1, ackAtWrite[0]);
        assertEquals(1, relay.vlcAckCount());
        assertEquals(1, relay.unitsWritten());
    }

    @Test
    public void vlc1AckBeforeFirstUnitFailsClosedWithoutDataWrite() throws Exception {
        byte[] unit = hex("84 a9 61 00 1d 20 01 1b "
                + "c8 05 e2 1c 07 68 87 8f 21 e8 03 b7 53 a7 27 17 8f 21 "
                + "e8 07 44 01 a7 34 f5 0f 21 00");
        byte[] ack = HpiCodec.paddedFrame(0x20, new byte[] {0x43});
        byte[] replacement = new byte[36];
        replacement[0] = 0x55;
        RealtimeRelay relay = new RealtimeRelay(RUNTIME14, replacement,
                "incremental_late_test", true,
                RealtimeRelay.SOFTWARE_REPLACEMENT_TRIGGER_UNITS);
        relay.acceptRaw(ack);
        assertThrows(IOException.class, () ->
                DmrTxController.feedRelayIncrementally(relay,
                        Bytes.concat(ack, unit), 1, acceptedBytes -> {
                            if (!relay.readyToWrite()) {
                                return false;
                            }
                            relay.takeRequest44();
                            relay.markFlushed();
                            return true;
                        }));
        assertEquals(0, relay.unitsWritten());
        assertFalse(relay.writeAttempted());
    }

    @Test
    public void relayVlcExchangeRejectsMissingOrDuplicateAck() {
        byte[] ack = HpiCodec.paddedFrame(0x20, new byte[] {0x43});
        RealtimeRelay relay = new RealtimeRelay(RUNTIME14);
        assertThrows(IllegalStateException.class,
                () -> relay.requireSingleVlcAckAfter(0));
        relay.acceptRaw(Bytes.concat(ack, ack));
        assertThrows(IllegalStateException.class,
                () -> relay.requireSingleVlcAckAfter(0));
    }

    @Test
    public void externalDmrRelayCreditRequiresExactFrozenWireFrame() {
        byte[] exact = hex("84 a9 61 00 02 03 01 00");
        assertTrue(DmrProtocol.isExternalDmrRelayCredit(exact));
        assertTrue(RealtimeRelay.isRelayCredit(exact));
        assertFalse(DmrProtocol.isExternalDmrRelayCredit(
                HpiCodec.paddedFrame(0x20, new byte[] {0x01, 0x00})));
        assertFalse(DmrProtocol.isExternalDmrRelayCredit(
                HpiCodec.paddedFrame(0x03, new byte[] {0x00})));
        assertFalse(DmrProtocol.isExternalDmrRelayCredit(
                HpiCodec.paddedFrame(0x03, new byte[] {0x00, 0x00})));
        assertFalse(DmrProtocol.isExternalDmrRelayCredit(
                HpiCodec.paddedFrame(0x03, new byte[] {0x01, 0x01})));
    }

    @Test
    public void privacyMatchesFrozenExternalUnit0Envelope() {
        byte[] plain = hex("99 ce a1 36 ef bd 08 ea 08 8a ff 81 56 a8 ce 08 "
                + "da 09 53 c2 9e 31 0e cc c1 83 06 ac fb a6 36 88 42 29 d0 05");
        assertEquals("83FA8EA05FB8DAB16E78944209F86B4FE5D06A567F82FFD290D7E3DD842DC0EB",
                Bytes.sha256(plain));
        byte[] encrypted = DmrPrivacy.fromRuntime14(RUNTIME14).encrypt(plain);
        assertEquals("7D27E71E86A26A2A5684F9BE5296C9569C0F198F28E7AF0D697B0A01869CE515",
                Bytes.sha256(encrypted));
        assertEquals("0C7F9E861155A1EFBE3D04475000CE3AC354A713E7E9F18F2C05369D239FC497",
                Bytes.sha256(DmrProtocol.data36(encrypted)));
    }

    @Test
    public void relayStateMachineRejectsVlc1BeforeFirstCredit() throws Exception {
        DmrProtocol.Session session = DmrProtocol.session(13, 99, 0, RUNTIME14);
        TxStateMachine blocked = relayAfterVlc0(session);
        assertThrows(IllegalStateException.class, blocked::nextControlRequest);
        assertEquals(TxStateMachine.Phase.FAILED, blocked.phase());

        TxStateMachine allowed = relayAfterVlc0(session);
        allowed.markRealtimeRelayComplete();
        assertEquals(1, allowed.dataWritten());
        assertArrayEquals(session.vlc(1), allowed.nextControlRequest());
    }

    private static TxStateMachine relayAfterVlc0(DmrProtocol.Session session) {
        TxStateMachine machine = new TxStateMachine(session, true);
        for (int index = 0; index < DmrProtocol.SETUP_COUNT; index++) {
            assertArrayEquals(session.setup(index), machine.nextControlRequest());
            machine.acceptControlResponse(HpiCodec.frame(
                    session.setupPacketType(index),
                    new byte[] {(byte) session.setupField(index), 0x00}));
        }
        machine.registerFullprepDeadline(5000);
        machine.beginVlcAfterFullprepDeadline(5000);
        assertArrayEquals(session.vlc(0), machine.nextControlRequest());
        machine.acceptControlResponse(HpiCodec.paddedFrame(
                0x20, new byte[] {0x43}));
        return machine;
    }

    @Test
    public void fiveVlcRequiresRelayCompletionAndTerminationBeforeExit()
            throws Exception {
        DmrProtocol.Session session = DmrProtocol.session(13, 99, 0, RUNTIME14);
        TxStateMachine machine = new TxStateMachine(session, true);
        for (int index = 0; index < DmrProtocol.SETUP_COUNT; index++) {
            machine.nextControlRequest();
            machine.acceptControlResponse(HpiCodec.frame(
                    session.setupPacketType(index),
                    new byte[] {(byte) session.setupField(index), 0x00}));
        }
        machine.registerFullprepDeadline(1000);
        machine.beginVlcAfterFullprepDeadline(1000);
        machine.nextControlRequest();
        machine.acceptControlResponse(HpiCodec.paddedFrame(
                0x20, new byte[] {0x43}));
        machine.markRealtimeRelayComplete();
        for (int index = 1; index < DmrProtocol.VLC_COUNT; index++) {
            machine.nextControlRequest();
            machine.acceptControlResponse(HpiCodec.paddedFrame(
                    0x20, new byte[] {0x43}));
        }
        assertEquals(TxStateMachine.Phase.WAIT_RELAY_COMPLETION,
                machine.phase());
        machine.markRealtimeRelaySequenceComplete();
        assertEquals(TxStateMachine.Phase.WAIT_HPI_TERMINATION,
                machine.phase());
        assertArrayEquals(session.terminationVlc(),
                machine.nextControlRequest());
        machine.acceptControlResponse(HpiCodec.paddedFrame(
                0x20, new byte[] {0x43}));
        assertEquals(1, machine.terminationAcks());
        assertEquals(TxStateMachine.Phase.WAIT_FIRST_BRIDGE_EXIT,
                machine.phase());
        byte[] mirror = new byte[12];
        mirror[6] = 1;
        mirror[7] = 1;
        mirror[8] = 0x4d;
        mirror[9] = 0x49;
        mirror[10] = 0x52;
        mirror[11] = 0x52;
        machine.attestFirstBridgeExit(TxStateMachine.FULLPREP_MARKER,
                TxStateMachine.FIRST_BRIDGE_EXIT_MARKER, 0,
                TxStateMachine.ORIGINAL_SYSTICK, mirror);
        machine.completeNoRfAfterFirstBridge();
        assertEquals(TxStateMachine.Phase.COMPLETE, machine.phase());
        assertEquals(1, machine.dataWritten());
        assertThrows(IllegalStateException.class,
                () -> machine.attestActualRf(TxStateMachine.RF_PREP_MARKER,
                        1, 1000));
    }

    @Test
    public void firstBridgeRfRequiresBridgedHpiCleanupNotSlotCompletion()
            throws Exception {
        assertThrows(IllegalArgumentException.class,
                () -> new TxStateMachine(
                        DmrProtocol.session(13, 99, 0, RUNTIME14),
                        false, true));
        TxStateMachine withoutCleanup = readyForRf();
        assertEquals(0, withoutCleanup.cleanupAcks());
        assertThrows(IllegalStateException.class,
                () -> withoutCleanup.attestFirstBridgeRfWithoutSecondBridge(
                        0, 0, 1, 0, 1000));
        TxStateMachine noRfRelay = readyForNoRfRelayExit();
        assertEquals(0, noRfRelay.cleanupAcks());
        assertThrows(IllegalStateException.class,
                () -> noRfRelay.attestFirstBridgeRfWithoutSecondBridge(
                        0, 0, 0xff, 0, 1000));
        TxStateMachine oneshotMarker = readyForFirstBridgeCleanup();
        assertThrows(IllegalStateException.class,
                () -> oneshotMarker.attestFirstBridgeRfWithoutSecondBridge(
                        TxStateMachine.RF_PREP_MARKER, 0, 1, 0, 1000));
        TxStateMachine holdNotFired = readyForFirstBridgeCleanup();
        assertThrows(IllegalStateException.class,
                () -> holdNotFired.attestFirstBridgeRfWithoutSecondBridge(
                        0, 0, 0xff, 0, 1000));
        TxStateMachine gateStillOn = readyForFirstBridgeCleanup();
        assertThrows(IllegalStateException.class,
                () -> gateStillOn.attestFirstBridgeRfWithoutSecondBridge(
                        TxStateMachine.RF_OFF_MARKER, 1, 0xff, 0, 1000));
        TxStateMachine acceptedFf = readyForFirstBridgeCleanup();
        acceptedFf.attestFirstBridgeRfWithoutSecondBridge(
                TxStateMachine.RF_OFF_MARKER, 0, 0xff, 0, 1000);
        assertEquals(TxStateMachine.Phase.WAIT_RF_OFF_ATTESTATION,
                acceptedFf.phase());
        acceptedFf.attestRfOff(TxStateMachine.RF_OFF_MARKER, 0);
        assertEquals(TxStateMachine.Phase.COMPLETE, acceptedFf.phase());
        TxStateMachine noSecond = readyForFirstBridgeCleanup();
        noSecond.attestFirstBridgeRfWithoutSecondBridge(
                TxStateMachine.RF_OFF_MARKER, 0, 1, 0, 1000);
        assertThrows(IllegalStateException.class,
                () -> noSecond.attestSecondBridgeArmed(
                        TxStateMachine.SECOND_BRIDGE_SYSTICK, 1010));
    }

    @Test
    public void ackPacedTripleSosLowPowerRfStatePathClosesInFirstBridge()
            throws Exception {
        DmrProtocol.Session session = DmrProtocol.session(13, 99, 0, RUNTIME14);
        TxStateMachine machine = new TxStateMachine(session, true, true, true);
        for (int index = 0; index < DmrProtocol.SETUP_COUNT; index++) {
            assertArrayEquals(session.setup(index), machine.nextControlRequest());
            machine.acceptControlResponse(HpiCodec.frame(
                    session.setupPacketType(index),
                    new byte[] {(byte) session.setupField(index), 0x00}));
        }
        machine.registerFullprepDeadline(10000);
        machine.beginVlcAfterFullprepDeadline(10000);
        for (int index = 0; index < DmrProtocol.VLC_COUNT; index++) {
            assertArrayEquals(session.vlc(index), machine.nextControlRequest());
            machine.acceptControlResponse(HpiCodec.paddedFrame(
                    0x20, new byte[] {0x43}));
        }
        machine.markRealtimeRelayComplete();
        for (int index = 1; index < RealtimeRelay.TRIPLE_SOS_UNITS; index++) {
            machine.noteAdditionalRelayUnitWritten();
        }
        assertEquals(RealtimeRelay.TRIPLE_SOS_UNITS, machine.dataWritten());
        machine.markRealtimeRelaySequenceComplete();
        assertArrayEquals(session.terminationVlc(), machine.nextControlRequest());
        machine.acceptControlResponse(HpiCodec.paddedFrame(
                0x20, new byte[] {0x43}));
        for (int index = 0; index < DmrProtocol.CLEANUP_COUNT; index++) {
            assertArrayEquals(session.cleanup(index), machine.nextControlRequest());
            machine.acceptControlResponse(HpiCodec.frame(
                    session.cleanupPacketType(index),
                    new byte[] {(byte) session.cleanupField(index), 0x00}));
        }
        byte[] mirror = new byte[12];
        mirror[6] = 1;
        mirror[7] = 1;
        mirror[8] = 0x4d;
        mirror[9] = 0x49;
        mirror[10] = 0x52;
        mirror[11] = 0x52;
        machine.attestFirstBridgeExit(TxStateMachine.FULLPREP_MARKER,
                TxStateMachine.FIRST_BRIDGE_EXIT_MARKER, 0,
                TxStateMachine.ORIGINAL_SYSTICK, mirror);
        machine.attestFirstBridgeRfWithoutSecondBridge(
                TxStateMachine.RF_OFF_MARKER, 0, 0xff, 0, 33500);
        machine.attestRfOff(TxStateMachine.RF_OFF_MARKER, 0);
        assertEquals(TxStateMachine.Phase.COMPLETE, machine.phase());
    }

    @Test
    public void bridgedCleanupAcceptsTrailingCompleteFrames() throws Exception {
        TxStateMachine machine = new TxStateMachine(
                DmrProtocol.session(13, 99, 0, RUNTIME14), true, true);
        driveSetupAndVlc(machine);
        assertEquals(TxStateMachine.Phase.WAIT_HPI_CLEANUP, machine.phase());
        DmrProtocol.Session session = DmrProtocol.session(13, 99, 0, RUNTIME14);
        assertArrayEquals(session.cleanup(0), machine.nextControlRequest());
        machine.acceptControlResponse(HpiCodec.join(Arrays.asList(
                HpiCodec.frame(0, new byte[] {0x3e, 0x00}),
                HpiCodec.frame(0x20, new byte[] {0x01, 0x00}))));
        assertArrayEquals(session.cleanup(1), machine.nextControlRequest());
        machine.acceptControlResponse(HpiCodec.frame(0, new byte[] {0x18, 0x00}));
        assertEquals(2, machine.cleanupAcks());
        assertEquals(TxStateMachine.Phase.WAIT_FIRST_BRIDGE_EXIT,
                machine.phase());
    }

    private static byte[] hex(String value) {
        String compact = value.replace(" ", "").replace("\n", "").replace("\r", "");
        if ((compact.length() & 1) != 0) {
            throw new IllegalArgumentException("hex长度必须为偶数");
        }
        byte[] result = new byte[compact.length() / 2];
        for (int index = 0; index < result.length; index++) {
            result[index] = (byte) Integer.parseInt(
                    compact.substring(index * 2, index * 2 + 2), 16);
        }
        return result;
    }

    private static void driveSetupAndVlc(TxStateMachine machine)
            throws Exception {
        DmrProtocol.Session session = DmrProtocol.session(13, 99, 0, RUNTIME14);
        for (int index = 0; index < DmrProtocol.SETUP_COUNT; index++) {
            assertArrayEquals(session.setup(index), machine.nextControlRequest());
            machine.acceptControlResponse(HpiCodec.frame(
                    session.setupPacketType(index),
                    new byte[] {(byte) session.setupField(index), 0x00}));
        }
        machine.registerFullprepDeadline(1000);
        machine.beginVlcAfterFullprepDeadline(1000);
        assertArrayEquals(session.vlc(0), machine.nextControlRequest());
        machine.acceptControlResponse(HpiCodec.paddedFrame(
                0x20, new byte[] {0x43}));
        machine.markRealtimeRelayComplete();
        for (int index = 1; index < DmrProtocol.VLC_COUNT; index++) {
            assertArrayEquals(session.vlc(index), machine.nextControlRequest());
            machine.acceptControlResponse(HpiCodec.paddedFrame(
                    0x20, new byte[] {0x43}));
        }
        assertEquals(TxStateMachine.Phase.WAIT_RELAY_COMPLETION,
                machine.phase());
        machine.markRealtimeRelaySequenceComplete();
        assertArrayEquals(session.terminationVlc(),
                machine.nextControlRequest());
        machine.acceptControlResponse(HpiCodec.paddedFrame(
                0x20, new byte[] {0x43}));
        assertEquals(1, machine.terminationAcks());
    }

    private static byte[] runtimeMirror() {
        byte[] mirror = new byte[12];
        mirror[6] = 1;
        mirror[7] = 1;
        mirror[8] = 0x4d;
        mirror[9] = 0x49;
        mirror[10] = 0x52;
        mirror[11] = 0x52;
        return mirror;
    }

    @Test
    public void raw49PrivacyRoundTripPreservesEveryBitBeforeC3Insertion() {
        byte[] raw49 = new byte[36];
        for (int frame = 0; frame < 4; frame++) {
            for (int index = 0; index < 6; index++) {
                raw49[frame * 9 + index] = (byte) (frame * 31 + index * 17);
            }
            raw49[frame * 9 + 6] = (byte) ((frame & 1) << 7);
        }
        byte[] encrypted = DmrPrivacy.fromRuntime14(RUNTIME14)
                .encrypt49BitParameters(raw49);
        assertFalse(Arrays.equals(raw49, encrypted));
        byte[] recovered = DmrPrivacy.fromRuntime14(RUNTIME14)
                .decrypt49BitParameters(encrypted);
        SoftwareDmrPrivacyPipeline.requireRoundTripExceptLateEntry(
                raw49, recovered);
        assertArrayEquals(raw49, recovered);
    }

    @Test
    public void raw49PrivacyStateAdvancesAcrossTwoSuperframes() {
        byte[] raw49 = new byte[40 * 9];
        for (int frame = 0; frame < 40; frame++) {
            for (int index = 0; index < 6; index++) {
                raw49[frame * 9 + index] = (byte) (frame * 13 + index * 29);
            }
            raw49[frame * 9 + 6] = (byte) ((frame & 1) << 7);
        }
        DmrPrivacy sender = DmrPrivacy.fromRuntime14(RUNTIME14);
        byte[] encrypted = sender.encrypt49BitParameters(raw49);
        assertEquals(40, sender.globalFrame());
        assertEquals(4, sender.frameInSuperframe());
        DmrPrivacy receiver = DmrPrivacy.fromRuntime14(RUNTIME14);
        assertArrayEquals(raw49,
                receiver.decrypt49BitParameters(encrypted));
        assertEquals(40, receiver.globalFrame());
        assertEquals(4, receiver.frameInSuperframe());
    }

    @Test
    public void roundTripGateOnlyAllowsUnprotectedC3DataBits() {
        byte[] expected = new byte[9];
        byte[] allowed = expected.clone();
        allowed[47 / 8] ^= (byte) (1 << (7 - 47 % 8));
        allowed[48 / 8] ^= (byte) (1 << (7 - 48 % 8));
        SoftwareDmrPrivacyPipeline.requireRoundTripExceptLateEntry(
                expected, allowed);
        byte[] forbidden = expected.clone();
        forbidden[23 / 8] ^= (byte) (1 << (7 - 23 % 8));
        assertThrows(IllegalStateException.class,
                () -> SoftwareDmrPrivacyPipeline
                        .requireRoundTripExceptLateEntry(expected, forbidden));
    }

    @Test
    public void audioMetricsRecognizeSyntheticEightHundredHertz() {
        short[] pcm = SoftwareDmrPrivacyPipeline.tone800Pcm(2000, 12000);
        AudioMetrics metrics = AudioMetrics.measure(pcm, 8000, 800.0);
        metrics.requireTone800();
        assertEquals(800.0, metrics.dominantFrequency, 0.1);
        assertTrue(metrics.coherentAmplitude > 11000.0);
    }

    @Test
    public void morseMetricsRecognizeDelayedFrozenSosEnvelope() throws Exception {
        short[] expected = TxPlan.decodePcmS16Le(Files.readAllBytes(asset(
                "morse_sos.pcm_s16le").toPath()));
        short[] decoded = new short[expected.length];
        int lagSamples = 4 * 160;
        System.arraycopy(expected, 0, decoded, lagSamples,
                expected.length - lagSamples);
        MorseMetrics metrics = MorseMetrics.measure(expected, decoded);
        metrics.requireRecognizableSos();
        metrics.requireRecognizableSosSegment();
        assertEquals(4, metrics.bestLagFrames);
        assertTrue(metrics.envelopeCorrelation > 0.99);
        assertTrue(metrics.activeInactiveRatio > 1000.0);
    }

    @Test
    public void morseMetricsRejectUnstructuredConstantAudio() throws Exception {
        short[] expected = TxPlan.decodePcmS16Le(Files.readAllBytes(asset(
                "morse_sos.pcm_s16le").toPath()));
        short[] unstructured = new short[expected.length];
        Arrays.fill(unstructured, (short) 2000);
        assertThrows(IllegalStateException.class,
                () -> MorseMetrics.measure(expected, unstructured)
                        .requireRecognizableSos());
        assertThrows(IllegalStateException.class,
                () -> MorseMetrics.measure(expected, unstructured)
                        .requireRecognizableSosSegment());
    }

    @Test
    public void tripleSosRepeatsEverySampleAndFitsSeventyEightUnits()
            throws Exception {
        short[] one = TxPlan.decodePcmS16Le(Files.readAllBytes(asset(
                "morse_sos.pcm_s16le").toPath()));
        short[] triple = DmrTxController.repeat(one, 3);
        assertEquals(312 * TxPlan.PCM_SAMPLES_PER_FRAME, triple.length);
        assertEquals(6240L, triple.length * 1000L / 8000L);
        for (int repetition = 0; repetition < 3; repetition++) {
            assertArrayEquals(one, Arrays.copyOfRange(triple,
                    repetition * one.length, (repetition + 1) * one.length));
        }
        byte[] payload = new byte[RealtimeRelay.TRIPLE_SOS_BYTES];
        RealtimeRelay relay = new RealtimeRelay(RUNTIME14, payload,
                DmrTxController.RELAY_SOURCE_SOFTWARE_PRIVACY_TRIPLE_SOS,
                true);
        assertEquals(78, relay.maximumUnits());
    }

    @Test
    public void activePacedTripleSosWritesAllUnitsWithoutFakeCredits() {
        byte[] payload = new byte[RealtimeRelay.TRIPLE_SOS_BYTES];
        for (int index = 0; index < payload.length; index++) {
            payload[index] = (byte) (index * 37 + 11);
        }
        RealtimeRelay relay = new RealtimeRelay(RUNTIME14, payload,
                DmrTxController.RELAY_SOURCE_ACK_PACED_SOFTWARE_TRIPLE_SOS,
                true, RealtimeRelay.EXTERNAL_SOURCE_TRIGGER_UNITS, false,
                true);
        for (int unit = 0; unit < RealtimeRelay.TRIPLE_SOS_UNITS; unit++) {
            byte[] request = relay.takeActivePacedRequest44();
            assertArrayEquals(Arrays.copyOfRange(payload, unit * 36,
                    (unit + 1) * 36), Arrays.copyOfRange(request, 8, 44));
            assertThrows(IllegalStateException.class,
                    relay::takeActivePacedRequest44);
            relay.completeActivePacedWriteAfterTransportFlush();
            assertEquals(unit + 1, relay.unitsWritten());
            assertEquals(0, relay.creditsConsumed());
            assertTrue(relay.activePacedState().contains(
                    "write_attempted=false flushed=false"));
        }
        assertTrue(relay.activePacedComplete());
        assertThrows(IllegalStateException.class,
                relay::takeActivePacedRequest44);
    }

    @Test
    public void activePacedScheduleUsesFirstFlushAbsoluteOrigin() {
        assertTrue(DmrTxController.ackPacedActiveBudgetWellFormed());
        assertEquals(10080L, DmrTxController.activePacedTargetAt(10000L, 1));
        assertEquals(16160L, DmrTxController.activePacedTargetAt(10000L, 77));
        assertThrows(IllegalArgumentException.class,
                () -> DmrTxController.activePacedTargetAt(10000L, 0));
        assertThrows(IllegalArgumentException.class,
                () -> DmrTxController.activePacedTargetAt(0L, 1));
    }

    @Test
    public void tripleSosWireGateRequiresFrozenClearAndFinalBytes()
            throws Exception {
        byte[] oneSos = Files.readAllBytes(asset(
                "morse_sos.ambe9_sequence.bin").toPath());
        byte[] clear = new byte[oneSos.length * 3];
        for (int repetition = 0; repetition < 3; repetition++) {
            System.arraycopy(oneSos, 0, clear,
                    repetition * oneSos.length, oneSos.length);
        }
        byte[] finalWire = Files.readAllBytes(testAsset(
                "morse_sos_triple_final_channel72.bin").toPath());

        DmrTxController.requireTripleSosWireHashes(clear, finalWire);

        byte[] damagedClear = clear.clone();
        damagedClear[damagedClear.length / 2] ^= 0x01;
        assertThrows(IllegalStateException.class,
                () -> DmrTxController.requireTripleSosWireHashes(
                        damagedClear, finalWire));

        byte[] damagedFinalWire = finalWire.clone();
        damagedFinalWire[damagedFinalWire.length / 2] ^= 0x01;
        assertThrows(IllegalStateException.class,
                () -> DmrTxController.requireTripleSosWireHashes(
                        clear, damagedFinalWire));

        assertEquals(312 * 9, clear.length);
        assertEquals(clear.length, finalWire.length);
        assertEquals(64,
                DmrTxController.TRIPLE_SOS_CLEAR_CHANNEL72_SHA256.length());
        assertEquals(64,
                DmrTxController.TRIPLE_SOS_FINAL_CHANNEL72_SHA256.length());
    }

    @Test
    public void lateEntryC3IsInsertedOnlyAfterChannelEncoding() {
        byte[] channel = new byte[36];
        Arrays.fill(channel, (byte) 0x5a);
        byte[] withC3 = DmrPrivacy.fromRuntime14(RUNTIME14)
                .applyLateEntryC3ToChannelFrames(channel);
        int[] positions = {21, 71, 69, 67};
        byte[] fragments = DmrPrivacy.lateEntryFragments(
                DmrPrivacy.evolveMi(Arrays.copyOfRange(RUNTIME14, 10, 14)));
        for (int frame = 0; frame < 4; frame++) {
            for (int bit = 0; bit < 72; bit++) {
                boolean c3Position = false;
                for (int position : positions) {
                    c3Position |= bit == position;
                }
                if (!c3Position) {
                    assertEquals(readBit(channel, frame * 72 + bit),
                            readBit(withC3, frame * 72 + bit));
                }
            }
            for (int index = 0; index < positions.length; index++) {
                assertEquals((fragments[frame] >>> (3 - index)) & 1,
                        readBit(withC3, frame * 72 + positions[index]));
            }
        }
    }

    private static int readBit(byte[] value, int bit) {
        return (value[bit / 8] >>> (7 - bit % 8)) & 1;
    }

    @Test
    public void finalSoftwareWirePayloadBypassesLegacyPostFecPrivacy() {
        byte[] channel72 = new byte[36];
        for (int index = 0; index < channel72.length; index++) {
            channel72[index] = (byte) (index * 29 + 7);
        }
        RealtimeRelay relay = new RealtimeRelay(RUNTIME14, channel72,
                DmrTxController.RELAY_SOURCE_SOFTWARE_PRIVACY_MORSE, true);
        relay.acceptRaw(hex("84 a9 61 00 1d 20 01 1b "
                + "c8 05 e2 1c 07 68 87 8f 21 e8 03 b7 53 a7 27 17 8f 21 "
                + "e8 07 44 01 a7 34 f5 0f 21 00"));
        relay.acceptRaw(hex("84 a9 61 00 1d 20 01 1b "
                + "98 02 b9 4f a4 d3 df 8f 21 da 1d 4b 9e 41 f3 9e 8b 25 "
                + "89 03 d9 08 95 98 4f 8b 25 00"));
        relay.acceptRaw(hex("84 a9 61 00 1d 20 01 1b "
                + "d8 05 fb 9f a2 61 97 8b 21 c8 02 40 5d e7 02 97 8b 21 "
                + "c8 07 67 2c 67 13 9d 0b 21 00"));
        byte[] request = relay.takeRequest44();
        assertArrayEquals(channel72, Arrays.copyOfRange(request, 8, 44));
    }

    @Test
    public void relayCadenceHonorsCreditBackpressureAndMinimumEightyMilliseconds() {
        assertEquals(1080L, DmrTxController.relayMinimumTargetAt(1000L));
        assertEquals(1080L,
                DmrTxController.relayCreditGatedTargetAt(1000L, 1015L));
        assertEquals(1125L,
                DmrTxController.relayCreditGatedTargetAt(1000L, 1125L));
        assertThrows(IllegalArgumentException.class,
                () -> DmrTxController.relayMinimumTargetAt(0L));
        assertThrows(IllegalArgumentException.class,
                () -> DmrTxController.relayCreditGatedTargetAt(1000L, 0L));
    }

    @Test
    public void relayAllowsOnlyFirstDataUnitDuringVlc0AndPausesLaterVlc() {
        assertTrue(DmrTxController.relayDataWriteAllowedDuringVlc(
                "vlc_0", 0, 0, 0, 0, 0));
        assertFalse(DmrTxController.relayDataWriteAllowedDuringVlc(
                "vlc_0", 0, 1, 1, 1, 1));
        for (String label : new String[] {
                "vlc_1", "vlc_2", "vlc_3", "vlc_4" }) {
            assertFalse(DmrTxController.relayDataWriteAllowedDuringVlc(
                    label, 2, 2, 5, 5, 1));
            assertFalse(DmrTxController.relayDataWriteAllowedDuringVlc(
                    label, 2, 3, 5, 5, 1));
            assertFalse(DmrTxController.relayDataWriteAllowedDuringVlc(
                    label, 2, 2, 5, 6, 1));
            assertFalse(DmrTxController.relayDataWriteAllowedDuringVlc(
                    label, 2, 3, 5, 6, 1));
            assertFalse(DmrTxController.relayDataWriteAllowedDuringVlc(
                    label, 2, 4, 5, 6, 1));
        }
        assertThrows(IllegalArgumentException.class,
                () -> DmrTxController.relayDataWriteAllowedDuringVlc(
                        "vlc_2", 2, 1, 5, 6, 1));
        assertThrows(IllegalArgumentException.class,
                () -> DmrTxController.relayDataWriteAllowedDuringVlc(
                        "vlc_2", 2, 3, 5, 4, 1));
        assertThrows(IllegalArgumentException.class,
                () -> DmrTxController.relayDataWriteAllowedDuringVlc(
                        "vlc_0", 0, 0, 0, 0, -1));
    }

    @Test
    public void relayHotPathEvidenceIsBoundedAndCopiesSerialBytes()
            throws Exception {
        RelayHotPathEvidence buffer = new RelayHotPathEvidence();
        byte[] request = new byte[44];
        request[0] = 0x55;
        for (int index = 0;
                index < RelayHotPathEvidence.MAX_EVENTS; index++) {
            buffer.addEvent("serial", "tx_hpi_write", request);
        }
        request[0] = 0;
        assertEquals(0x55, buffer.events().get(0).value[0] & 0xff);
        assertEquals(RelayHotPathEvidence.MAX_EVENTS,
                buffer.events().size());
        assertEquals(RelayHotPathEvidence.MAX_EVENTS * 44,
                buffer.eventBytes());
        assertThrows(IOException.class,
                () -> buffer.addEvent("serial", "tx_hpi_write",
                        new byte[44]));
    }

    @Test
    public void relayHotPathReadEvidenceRejectsOverflow() throws Exception {
        RelayHotPathEvidence buffer = new RelayHotPathEvidence();
        // 480包speech_az09素材投产时容量从512提到2048（同一批修复把
        // MAX_EVENTS从256提到1024），这条断言当时没跟着改，一直静默过着
        // 陈旧值——和本文件里刚补的第一桥预算断言是同一类问题：容量/时限
        // 常量换了，核对它们的断言没跟着换。
        assertEquals(2048, RelayHotPathEvidence.MAX_READ_SAMPLES);
        byte[] raw = new byte[] { 0x01, 0x02, 0x03 };
        for (int index = 0;
                index < RelayHotPathEvidence.MAX_READ_SAMPLES; index++) {
            buffer.addRead("vlc", index, index, 1000 + index,
                    80, 1001 + index, raw);
        }
        raw[0] = 0x7f;
        assertEquals(RelayHotPathEvidence.MAX_READ_SAMPLES,
                buffer.reads().size());
        assertEquals(0x01, buffer.reads().get(0).value[0] & 0xff);
        assertEquals(RelayHotPathEvidence.MAX_READ_SAMPLES * 3,
                buffer.readBytes());
        assertThrows(IOException.class,
                () -> buffer.addRead("vlc", 0, 0, 1, 1, 2,
                        new byte[0]));
    }

    @Test
    public void relayHotPathReadEvidenceRejectsByteOverflow() throws Exception {
        RelayHotPathEvidence buffer = new RelayHotPathEvidence();
        buffer.addRead("credit_wait", 1, 0, 1, 1500, 2,
                new byte[RelayHotPathEvidence.MAX_READ_BYTES]);
        assertEquals(RelayHotPathEvidence.MAX_READ_BYTES,
                buffer.readBytes());
        assertThrows(IOException.class,
                () -> buffer.addRead("credit_wait", 1, 0, 2, 1, 3,
                        new byte[1]));
    }

    private static TxStateMachine readyForNoRfRelayExit() throws Exception {
        TxStateMachine machine = new TxStateMachine(
                DmrProtocol.session(13, 99, 0, RUNTIME14), true);
        driveSetupAndVlc(machine);
        machine.attestFirstBridgeExit(TxStateMachine.FULLPREP_MARKER,
                TxStateMachine.FIRST_BRIDGE_EXIT_MARKER, 0,
                TxStateMachine.ORIGINAL_SYSTICK, runtimeMirror());
        return machine;
    }

    private static TxStateMachine readyForFirstBridgeCleanup()
            throws Exception {
        DmrProtocol.Session session = DmrProtocol.session(13, 99, 0, RUNTIME14);
        TxStateMachine machine = new TxStateMachine(session, true, true);
        driveSetupAndVlc(machine);
        assertEquals(TxStateMachine.Phase.WAIT_HPI_CLEANUP, machine.phase());
        assertArrayEquals(session.cleanup(0), machine.nextControlRequest());
        machine.acceptControlResponse(HpiCodec.frame(0, new byte[] {0x3e, 0x00}));
        assertArrayEquals(session.cleanup(1), machine.nextControlRequest());
        machine.acceptControlResponse(HpiCodec.frame(0, new byte[] {0x18, 0x00}));
        assertEquals(2, machine.cleanupAcks());
        machine.attestFirstBridgeExit(TxStateMachine.FULLPREP_MARKER,
                TxStateMachine.FIRST_BRIDGE_EXIT_MARKER, 0,
                TxStateMachine.ORIGINAL_SYSTICK, runtimeMirror());
        return machine;
    }

    private static TxStateMachine readyForRf() throws Exception {
        DmrProtocol.Session session = DmrProtocol.session(13, 99, 0, RUNTIME14);
        byte[] plain = Files.readAllBytes(asset(
                "morse_sos.ambe9_sequence.bin").toPath());
        TxStateMachine machine = new TxStateMachine(session,
                TxPlan.create(plain));
        for (int index = 0; index < DmrProtocol.SETUP_COUNT; index++) {
            assertArrayEquals(session.setup(index), machine.nextControlRequest());
            machine.acceptControlResponse(HpiCodec.frame(
                    session.setupPacketType(index),
                    new byte[] {(byte) session.setupField(index), 0x00}));
        }
        machine.registerFullprepDeadline(5000);
        machine.beginVlcAfterFullprepDeadline(5000);
        for (int index = 0; index < DmrProtocol.VLC_COUNT; index++) {
            assertArrayEquals(session.vlc(index), machine.nextControlRequest());
            machine.acceptControlResponse(HpiCodec.paddedFrame(
                    0x20, new byte[] {0x43}));
        }
        byte[] mirror = new byte[12];
        mirror[6] = 1;
        mirror[7] = 1;
        mirror[8] = 0x4d;
        mirror[9] = 0x49;
        mirror[10] = 0x52;
        mirror[11] = 0x52;
        machine.attestFirstBridgeExit(TxStateMachine.FULLPREP_MARKER,
                TxStateMachine.FIRST_BRIDGE_EXIT_MARKER, 0,
                TxStateMachine.ORIGINAL_SYSTICK, mirror);
        return machine;
    }

    private static File asset(String name) {
        File direct = new File("src/main/assets/software_ambe_vectors", name);
        if (direct.isFile()) {
            return direct;
        }
        return new File("app/src/main/assets/software_ambe_vectors", name);
    }

    private static File assetFile(String name) {
        File direct = new File("src/main/assets", name);
        if (direct.isFile()) {
            return direct;
        }
        return new File("app/src/main/assets", name);
    }

    private static File testAsset(String name) {
        File direct = new File("src/test/resources/software_ambe_vectors",
                name);
        if (direct.isFile()) {
            return direct;
        }
        return new File("app/src/test/resources/software_ambe_vectors", name);
    }
}
