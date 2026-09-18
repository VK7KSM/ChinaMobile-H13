package net.elfradio.h13dmrtx;

import java.util.Arrays;

final class TxStateMachine {
    static final int FULLPREP_MARKER = 0x54414744;
    static final int FIRST_BRIDGE_EXIT_MARKER = 0x54495845;
    static final int RUNTIME_MIRROR_MARKER = 0x5252494d;
    static final int RF_PREP_MARKER = 0x50465221;
    static final int RF_OFF_MARKER = 0x21464f52;
    static final int ORIGINAL_SYSTICK = 0x08021ddd;
    static final int SECOND_BRIDGE_SYSTICK = 0x20001601;
    static final long WATCHDOG_MS = 30000;

    enum Phase {
        FIRST_BRIDGE_SETUP,
        WAIT_FULLPREP_DEADLINE,
        FIRST_BRIDGE_VLC,
        WAIT_RELAY_COMPLETION,
        WAIT_HPI_TERMINATION,
        WAIT_HPI_CLEANUP,
        WAIT_FIRST_BRIDGE_EXIT,
        WAIT_RF_ATTESTATION,
        WAIT_SECOND_BRIDGE,
        DATA,
        WAIT_SECOND_BRIDGE_EXIT,
        WAIT_RF_OFF_ATTESTATION,
        COMPLETE,
        FAILED
    }

    private final DmrProtocol.Session session;
    private final TxPlan plan;
    private final boolean relayOneUnit;
    private final boolean firstBridgeHpiCleanup;
    private final boolean relayAfterAllVlc;
    private Phase phase = Phase.FIRST_BRIDGE_SETUP;
    private int setupIndex;
    private int vlcIndex;
    private int cleanupIndex;
    private boolean terminationAcked;
    private int dataIndex;
    private byte[] pending;
    private long fullprepDeadlineMs = -1;
    private long rfActualAtMs = -1;
    private long firstDataAtMs = -1;
    private long watchdogAtMs = -1;
    private boolean setup0EmptyRetryConsumed;
    private boolean relayComplete;

    TxStateMachine(DmrProtocol.Session session, TxPlan plan) {
        this(session, plan, false, false, false);
    }

    TxStateMachine(DmrProtocol.Session session, boolean relayOneUnit) {
        this(session, null, relayOneUnit, false, false);
    }

    TxStateMachine(DmrProtocol.Session session, boolean relayOneUnit,
            boolean firstBridgeHpiCleanup) {
        this(session, null, relayOneUnit, firstBridgeHpiCleanup, false);
    }

    TxStateMachine(DmrProtocol.Session session, boolean relayOneUnit,
            boolean firstBridgeHpiCleanup, boolean relayAfterAllVlc) {
        this(session, null, relayOneUnit, firstBridgeHpiCleanup,
                relayAfterAllVlc);
    }

    private TxStateMachine(DmrProtocol.Session session, TxPlan plan,
            boolean relayOneUnit, boolean firstBridgeHpiCleanup,
            boolean relayAfterAllVlc) {
        if (session == null || (plan == null) == !relayOneUnit) {
            throw new IllegalArgumentException("发送会话依赖不完整");
        }
        if (firstBridgeHpiCleanup && !relayOneUnit) {
            throw new IllegalArgumentException("桥内HPI收尾只用于第一桥实时relay");
        }
        if (relayAfterAllVlc && !relayOneUnit) {
            throw new IllegalArgumentException("VLC后relay必须使用实时relay状态机");
        }
        this.session = session;
        this.plan = plan;
        this.relayOneUnit = relayOneUnit;
        this.firstBridgeHpiCleanup = firstBridgeHpiCleanup;
        this.relayAfterAllVlc = relayAfterAllVlc;
    }

    Phase phase() {
        return phase;
    }

    byte[] nextControlRequest() {
        requireNotPending();
        if (phase == Phase.FIRST_BRIDGE_SETUP) {
            pending = session.setup(setupIndex);
        } else if (phase == Phase.FIRST_BRIDGE_VLC) {
            if (relayOneUnit && !relayAfterAllVlc && vlcIndex == 1
                    && !relayComplete) {
                throw fail("vlc1前必须完成实时relay首包写出和信用");
            }
            pending = session.vlc(vlcIndex);
        } else if (phase == Phase.WAIT_HPI_TERMINATION) {
            pending = session.terminationVlc();
        } else if (phase == Phase.WAIT_HPI_CLEANUP) {
            pending = session.cleanup(cleanupIndex);
        } else {
            throw fail("当前阶段禁止控制请求");
        }
        return pending.clone();
    }

    void acceptControlResponse(byte[] response) {
        if (pending == null) {
            throw fail("没有待确认的控制请求");
        }
        if (phase == Phase.FIRST_BRIDGE_SETUP) {
            if (!Arrays.equals(pending, session.setup(setupIndex))
                    || !DmrProtocol.exactStatusAck(response,
                    session.setupPacketType(setupIndex),
                    session.setupField(setupIndex))) {
                throw fail("setup严格确认失败");
            }
            pending = null;
            setupIndex++;
            if (setupIndex == DmrProtocol.SETUP_COUNT) {
                phase = Phase.WAIT_FULLPREP_DEADLINE;
            }
            return;
        }
        if (phase == Phase.FIRST_BRIDGE_VLC) {
            if (!Arrays.equals(pending, session.vlc(vlcIndex))
                    || !DmrProtocol.containsVlcAck(response)) {
                throw fail("VLC严格确认失败");
            }
            pending = null;
            vlcIndex++;
            if (vlcIndex == DmrProtocol.VLC_COUNT) {
                phase = relayOneUnit ? Phase.WAIT_RELAY_COMPLETION
                        : firstBridgeHpiCleanup ? Phase.WAIT_HPI_CLEANUP
                        : Phase.WAIT_FIRST_BRIDGE_EXIT;
            }
            return;
        }
        if (phase == Phase.WAIT_HPI_TERMINATION) {
            if (!Arrays.equals(pending, session.terminationVlc())
                    || !DmrProtocol.containsVlcAck(response)) {
                throw fail("终止VLC严格确认失败");
            }
            pending = null;
            terminationAcked = true;
            phase = firstBridgeHpiCleanup
                    ? Phase.WAIT_HPI_CLEANUP
                    : Phase.WAIT_FIRST_BRIDGE_EXIT;
            return;
        }
        if (phase == Phase.WAIT_HPI_CLEANUP) {
            if (!Arrays.equals(pending, session.cleanup(cleanupIndex))
                    || !DmrProtocol.containsControlStatusAck(response,
                    session.cleanupPacketType(cleanupIndex),
                    session.cleanupField(cleanupIndex))) {
                throw fail("桥内HPI收尾确认失败");
            }
            pending = null;
            cleanupIndex++;
            if (cleanupIndex == DmrProtocol.CLEANUP_COUNT) {
                phase = Phase.WAIT_FIRST_BRIDGE_EXIT;
            }
            return;
        }
        throw fail("控制响应阶段错误");
    }

    boolean abandonSetup0ForStrictEmptyRetry(byte[] response) {
        if (phase != Phase.FIRST_BRIDGE_SETUP || setupIndex != 0
                || pending == null || response == null || response.length != 0
                || setup0EmptyRetryConsumed) {
            return false;
        }
        pending = null;
        setup0EmptyRetryConsumed = true;
        return true;
    }

    void markRealtimeRelayComplete() {
        boolean legacyBoundary = !relayAfterAllVlc
                && phase == Phase.FIRST_BRIDGE_VLC && vlcIndex <= 1;
        boolean postVlcBoundary = relayAfterAllVlc
                && phase == Phase.WAIT_RELAY_COMPLETION
                && vlcIndex == DmrProtocol.VLC_COUNT && pending == null;
        if (!relayOneUnit || (!legacyBoundary && !postVlcBoundary)
                || relayComplete) {
            throw fail("实时relay完成标记阶段错误");
        }
        // 软件替换首包可在尚未accept的vlc0响应窗内到达。
        if (!relayAfterAllVlc && pending != null && vlcIndex >= 1) {
            throw fail("vlc1在途时禁止补标实时relay完成");
        }
        relayComplete = true;
        dataIndex = 1;
    }

    void noteAdditionalRelayUnitWritten() {
        if (!relayOneUnit || !relayComplete
                || (phase != Phase.FIRST_BRIDGE_VLC
                && phase != Phase.WAIT_RELAY_COMPLETION)) {
            throw fail("连续relay后续单元标记阶段错误");
        }
        dataIndex++;
    }

    void markRealtimeRelaySequenceComplete() {
        if (!relayOneUnit || !relayComplete || dataIndex <= 0
                || phase != Phase.WAIT_RELAY_COMPLETION || pending != null) {
            throw fail("实时relay全序列完成标记阶段错误");
        }
        phase = Phase.WAIT_HPI_TERMINATION;
    }

    boolean relayComplete() {
        return relayComplete;
    }

    boolean relayOneUnit() {
        return relayOneUnit;
    }

    void stopAfterSetup0() {
        if (phase != Phase.FIRST_BRIDGE_SETUP || setupIndex != 1
                || pending != null) {
            throw fail("setup0单独模式停止边界错误");
        }
        phase = Phase.WAIT_FIRST_BRIDGE_EXIT;
    }

    void registerFullprepDeadline(long deadlineMs) {
        if ((phase != Phase.FIRST_BRIDGE_SETUP
                && phase != Phase.WAIT_FULLPREP_DEADLINE)
                || deadlineMs < 0 || fullprepDeadlineMs >= 0) {
            throw fail("fullprep截止参数或阶段错误");
        }
        fullprepDeadlineMs = deadlineMs;
    }

    /**
     * 第一桥内不能用文本面回读MCU标记；这里只允许由已登记的绝对截止到时
     * 放行VLC，真正的DGAT/MIRR证据必须在自动退桥后单独验收。
     */
    void beginVlcAfterFullprepDeadline(long nowMs) {
        if (phase != Phase.WAIT_FULLPREP_DEADLINE
                || fullprepDeadlineMs < 0 || nowMs < fullprepDeadlineMs) {
            throw fail("fullprep绝对截止尚未到达");
        }
        phase = Phase.FIRST_BRIDGE_VLC;
    }

    void attestFirstBridgeExit(int fullprepMarker, int exitMarker,
            int bridgeFlag, int sysTickVector, byte[] runtimeMirror) {
        if (phase != Phase.WAIT_FIRST_BRIDGE_EXIT || pending != null
                || fullprepMarker != FULLPREP_MARKER
                || exitMarker != FIRST_BRIDGE_EXIT_MARKER
                || bridgeFlag != 0 || sysTickVector != ORIGINAL_SYSTICK
                || runtimeMirror == null || runtimeMirror.length != 12
                || Bytes.u32le(runtimeMirror, 8) != RUNTIME_MIRROR_MARKER
                || (runtimeMirror[6] & 0xff) != 1
                || (runtimeMirror[7] & 0xff) != 1) {
            throw fail("第一桥退桥或fullprep证据不成立");
        }
        phase = Phase.WAIT_RF_ATTESTATION;
    }

    /**
     * 计划时间不能调用本方法。参数必须来自MCU标记和active gate的同次原始证据。
     */
    void attestActualRf(int prepMarker, int activeGate, long observedAtMs) {
        if (phase != Phase.WAIT_RF_ATTESTATION || pending != null
                || prepMarker != RF_PREP_MARKER || activeGate != 1
                || observedAtMs < 0) {
            throw fail("真实RF准备证据不成立");
        }
        rfActualAtMs = observedAtMs;
        watchdogAtMs = observedAtMs + WATCHDOG_MS;
        phase = Phase.WAIT_SECOND_BRIDGE;
    }

    void attestSecondBridgeArmed(int vectorBeforeFlag, long enteredAtMs) {
        if (phase != Phase.WAIT_SECOND_BRIDGE
                || vectorBeforeFlag != SECOND_BRIDGE_SYSTICK
                || enteredAtMs < rfActualAtMs || enteredAtMs >= watchdogAtMs) {
            throw fail("第二桥武装证据不成立");
        }
        phase = Phase.DATA;
    }

    byte[] nextDataUnit(long nowMs) {
        requireNotPending();
        if (phase != Phase.DATA || dataIndex >= TxPlan.EXPECTED_UNIT_COUNT) {
            throw fail("当前阶段禁止data36");
        }
        long scheduled = dataIndex == 0 ? nowMs
                : firstDataAtMs + dataIndex * TxPlan.UNIT_INTERVAL_MS;
        if (nowMs < scheduled) {
            throw new IllegalStateException("尚未到data36绝对节拍");
        }
        if (nowMs > scheduled + TxPlan.MAX_LATE_MS || nowMs >= watchdogAtMs) {
            throw fail("data36迟到或超过看门狗期限");
        }
        pending = plan.wireUnits().get(dataIndex);
        return pending.clone();
    }

    void recordDataWriteComplete(byte[] written, long completedAtMs) {
        if (phase != Phase.DATA || pending == null
                || !Arrays.equals(pending, written)
                || completedAtMs < rfActualAtMs
                || completedAtMs >= watchdogAtMs) {
            throw fail("data36写完成边界错误");
        }
        if (dataIndex == 0) {
            firstDataAtMs = completedAtMs;
        }
        pending = null;
        dataIndex++;
        if (dataIndex == TxPlan.EXPECTED_UNIT_COUNT) {
            phase = Phase.WAIT_SECOND_BRIDGE_EXIT;
        }
    }


    void attestSecondBridgeExit(int bridgeFlag, int sysTickVector,
            int exitMarker) {
        if (phase != Phase.WAIT_SECOND_BRIDGE_EXIT
                || bridgeFlag != 0 || sysTickVector != ORIGINAL_SYSTICK
                || exitMarker != 0x47445242) {
            throw fail("第二桥自动退桥证据不成立");
        }
        phase = Phase.WAIT_RF_OFF_ATTESTATION;
    }

    void completeNoRfAfterFirstBridge() {
        if (phase != Phase.WAIT_RF_ATTESTATION) {
            throw fail("无射频结束阶段错误");
        }
        phase = Phase.COMPLETE;
    }

    /**
     * 第一桥射频路径：桥内收尾 ACK 已收下，phase4 保持窗到期后已调用
     * 0x0801FF34。标记为 ROF!、gate 为 0。completion 只作观察。
     */
    void attestFirstBridgeRfWithoutSecondBridge(int prepMarker,
            int activeGate, int completion, int slotCount,
            long observedAtMs) {
        if (phase != Phase.WAIT_RF_ATTESTATION || pending != null
                || !firstBridgeHpiCleanup
                || !terminationAcked
                || cleanupIndex != DmrProtocol.CLEANUP_COUNT
                || prepMarker != RF_OFF_MARKER
                || activeGate != 0
                || observedAtMs < 0) {
            throw fail("第一桥射频窗证据不成立");
        }
        if (activeGate < 0 || activeGate > 255 || completion < 0
                || completion > 255 || slotCount < 0 || slotCount > 65535) {
            throw fail("第一桥射频窗观察值范围错误");
        }
        rfActualAtMs = observedAtMs;
        watchdogAtMs = observedAtMs + WATCHDOG_MS;
        phase = Phase.WAIT_RF_OFF_ATTESTATION;
    }

    void attestRfOff(int offMarker, int activeGate) {
        if (phase != Phase.WAIT_RF_OFF_ATTESTATION
                || offMarker != RF_OFF_MARKER || activeGate != 0) {
            throw fail("RF关闭证据不成立");
        }
        phase = Phase.COMPLETE;
    }

    void deadlineCheck(long nowMs) {
        if (watchdogAtMs >= 0 && nowMs >= watchdogAtMs
                && phase != Phase.COMPLETE) {
            throw fail("30秒看门狗期限到达");
        }
    }

    int dataWritten() {
        return dataIndex;
    }

    int setupAcks() {
        return setupIndex;
    }

    int vlcAcks() {
        return vlcIndex;
    }

    int cleanupAcks() {
        return cleanupIndex;
    }

    int terminationAcks() {
        return terminationAcked ? 1 : 0;
    }

    boolean setup0RetryUsed() {
        return setup0EmptyRetryConsumed;
    }

    private void requireNotPending() {
        if (pending != null) {
            throw fail("上一请求尚未完成");
        }
    }

    private IllegalStateException fail(String message) {
        phase = Phase.FAILED;
        pending = null;
        return new IllegalStateException(message);
    }
}
