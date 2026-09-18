# -*- coding: utf-8 -*-
from pathlib import Path
p = Path(r"C:\Dev\H13_D22\research\h13_interphone_probe\app\src\main\java\net\elfradio\h13interphoneprobe\InterphoneProbe.java")
t = p.read_text(encoding="utf-8")
# 1) dual-read pendsv preflight after systick1
old1 = """            byte[] systick1 = readMemoryEvidence(input, output,
                    \"frame_adapter_pre_systick_r1\", RAM_VECTOR_SYSTICK, 4,
                    report).parsed;
            byte[] bridge1 = readMemoryEvidence(input, output,
                    \"frame_adapter_pre_bridge_r1\",
                    UART_HPI_BRIDGE_FLAG_ADDRESS, 1, report).parsed;"""
new1 = """            byte[] systick1 = readMemoryEvidence(input, output,
                    \"frame_adapter_pre_systick_r1\", RAM_VECTOR_SYSTICK, 4,
                    report).parsed;
            byte[] pendsv1 = readMemoryEvidence(input, output,
                    \"frame_adapter_pre_pendsv_r1\", RAM_VECTOR_PENDSV, 4,
                    report).parsed;
            byte[] bridge1 = readMemoryEvidence(input, output,
                    \"frame_adapter_pre_bridge_r1\",
                    UART_HPI_BRIDGE_FLAG_ADDRESS, 1, report).parsed;"""
if old1 not in t:
    raise SystemExit("block1 missing")
t = t.replace(old1, new1, 1)
old2 = """            byte[] systick2 = readMemoryEvidence(input, output,
                    \"frame_adapter_pre_systick_r2\", RAM_VECTOR_SYSTICK, 4,
                    report).parsed;
            byte[] bridge2 = readMemoryEvidence(input, output,
                    \"frame_adapter_pre_bridge_r2\",
                    UART_HPI_BRIDGE_FLAG_ADDRESS, 1, report).parsed;"""
new2 = """            byte[] systick2 = readMemoryEvidence(input, output,
                    \"frame_adapter_pre_systick_r2\", RAM_VECTOR_SYSTICK, 4,
                    report).parsed;
            byte[] pendsv2 = readMemoryEvidence(input, output,
                    \"frame_adapter_pre_pendsv_r2\", RAM_VECTOR_PENDSV, 4,
                    report).parsed;
            byte[] bridge2 = readMemoryEvidence(input, output,
                    \"frame_adapter_pre_bridge_r2\",
                    UART_HPI_BRIDGE_FLAG_ADDRESS, 1, report).parsed;"""
if old2 not in t:
    raise SystemExit("block2 missing")
t = t.replace(old2, new2, 1)
old3 = """            if (!Arrays.equals(range1, range2)
                    || !Arrays.equals(usart1, usart2)
                    || !Arrays.equals(systick1, systick2)
                    || !Arrays.equals(bridge1, bridge2)
                    || !Arrays.equals(baudCode1, baudCode2)
                    || !Arrays.equals(baudMarker1, baudMarker2)) {
                throw new IOException(\"测前动态现场不稳定，禁止改写\");
            }
            if (u32le(usart2, 0) != FRAME_ADAPTER_ORIGINAL_USART1
                    || u32le(systick2, 0) != ORIGINAL_SYSTICK_HANDLER
                    || (bridge2[0] & 0xff) != 0) {
                throw new IOException(\"测前USART1/SysTick/bridge基线不符\");
            }
            originalRange = range2.clone();
            originalUsartVector = usart2.clone();
            originalSysTick = systick2.clone();
            originalBridge = bridge2.clone();"""
new3 = """            if (!Arrays.equals(range1, range2)
                    || !Arrays.equals(usart1, usart2)
                    || !Arrays.equals(systick1, systick2)
                    || !Arrays.equals(pendsv1, pendsv2)
                    || !Arrays.equals(bridge1, bridge2)
                    || !Arrays.equals(baudCode1, baudCode2)
                    || !Arrays.equals(baudMarker1, baudMarker2)) {
                throw new IOException(\"测前动态现场不稳定，禁止改写\");
            }
            if (u32le(usart2, 0) != FRAME_ADAPTER_ORIGINAL_USART1
                    || u32le(systick2, 0) != ORIGINAL_SYSTICK_HANDLER
                    || u32le(pendsv2, 0) != ORIGINAL_PENDSV_HANDLER
                    || (bridge2[0] & 0xff) != 0) {
                throw new IOException(\"测前USART1/SysTick/PendSV/bridge基线不符\");
            }
            originalRange = range2.clone();
            originalUsartVector = usart2.clone();
            originalSysTick = systick2.clone();
            originalPendSv = pendsv2.clone();
            originalBridge = bridge2.clone();"""
if old3 not in t:
    raise SystemExit("block3 missing")
t = t.replace(old3, new3, 1)
# declare originalPendSv near other originals - search for originalSysTick declaration
if "originalPendSv" not in t.split("runFrameAdapterPcmNoRf")[0]:
    # local vars inside method
    pass
# find byte[] originalSysTick in method
if "byte[] originalPendSv" not in t:
    t = t.replace(
        "byte[] originalSysTick = null;",
        "byte[] originalSysTick = null;\n        byte[] originalPendSv = null;",
        1,
    )
# restore write
old4 = """            writeMemoryWord(input, output, FRAME_ADAPTER_USART1_VECTOR_ADDRESS,
                    u32le(originalUsartVector, 0));
            writeMemoryWord(input, output, RAM_VECTOR_SYSTICK,
                    u32le(originalSysTick, 0));
            writeMemoryByte(input, output, UART_HPI_BRIDGE_FLAG_ADDRESS,
                    originalBridge[0] & 0xff);"""
new4 = """            writeMemoryWord(input, output, FRAME_ADAPTER_USART1_VECTOR_ADDRESS,
                    u32le(originalUsartVector, 0));
            writeMemoryWord(input, output, RAM_VECTOR_SYSTICK,
                    u32le(originalSysTick, 0));
            if (originalPendSv != null) {
                writeMemoryWord(input, output, RAM_VECTOR_PENDSV,
                        u32le(originalPendSv, 0));
            }
            writeMemoryByte(input, output, UART_HPI_BRIDGE_FLAG_ADDRESS,
                    originalBridge[0] & 0xff);"""
if old4 not in t:
    raise SystemExit("block4 missing")
t = t.replace(old4, new4, 1)
# final readback pendsv
old5 = """            byte[] finalSysTick = readMemoryEvidence(input, output,
                    \"frame_adapter_final_systick\", RAM_VECTOR_SYSTICK, 4,
                    report).parsed;
            byte[] finalBridge = readMemoryEvidence(input, output,
                    \"frame_adapter_final_bridge\", UART_HPI_BRIDGE_FLAG_ADDRESS,
                    1, report).parsed;"""
new5 = """            byte[] finalSysTick = readMemoryEvidence(input, output,
                    \"frame_adapter_final_systick\", RAM_VECTOR_SYSTICK, 4,
                    report).parsed;
            byte[] finalPendSv = readMemoryEvidence(input, output,
                    \"frame_adapter_final_pendsv\", RAM_VECTOR_PENDSV, 4,
                    report).parsed;
            byte[] finalBridge = readMemoryEvidence(input, output,
                    \"frame_adapter_final_bridge\", UART_HPI_BRIDGE_FLAG_ADDRESS,
                    1, report).parsed;"""
if old5 not in t:
    raise SystemExit("block5 missing")
t = t.replace(old5, new5, 1)
old6 = """            sramRestored = Arrays.equals(originalRange, finalRange)
                    && Arrays.equals(originalUsartVector, finalUsart)
                    && Arrays.equals(originalSysTick, finalSysTick)
                    && Arrays.equals(originalBridge, finalBridge)
                    && Arrays.equals(originalBaudCode, finalBaudCode)
                    && Arrays.equals(originalBaudMarker, finalBaudMarker);"""
new6 = """            sramRestored = Arrays.equals(originalRange, finalRange)
                    && Arrays.equals(originalUsartVector, finalUsart)
                    && Arrays.equals(originalSysTick, finalSysTick)
                    && (originalPendSv == null
                        || Arrays.equals(originalPendSv, finalPendSv))
                    && Arrays.equals(originalBridge, finalBridge)
                    && Arrays.equals(originalBaudCode, finalBaudCode)
                    && Arrays.equals(originalBaudMarker, finalBaudMarker);"""
if old6 not in t:
    raise SystemExit("block6 missing")
t = t.replace(old6, new6, 1)
p.write_text(t, encoding="utf-8")
print("patched pendsv preflight+restore")
print("has originalPendSv decl", "byte[] originalPendSv" in t)
