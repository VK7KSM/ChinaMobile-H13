# One-shot extractor: split runFrameAdapterPcmNoRf post-timeout block.
from pathlib import Path

src_path = Path(
    r"C:\Dev\H13_D22\research\h13_interphone_probe"
    r"\app\src\main\java\net\elfradio\h13interphoneprobe\InterphoneProbe.java"
)
text = src_path.read_text(encoding="utf-8")
lines = text.splitlines(keepends=True)

# 1-based inclusive line range to extract (post-timeout evidence + classify)
start = 10685  # if (!completeToneHpi) { writeCommand print1
end = 11467    # end of completeToneHpi content classification

block = "".join(lines[start - 1 : end])
# Dedent 4 spaces so it sits in a new method at class-member indent? Keep 12-space body.
# The original block is indented 12 spaces (inside try). New method body uses 8.

helper = []
helper.append("    private void collectFrameAdapterPostTimeoutEvidence(\n")
helper.append("            FrameAdapterPostTimeout w) throws Exception {\n")
helper.append("        InputStream input = w.input;\n")
helper.append("        OutputStream output = w.output;\n")
helper.append("        StringBuilder report = w.report;\n")
helper.append("        boolean completeToneHpi = w.completeToneHpi;\n")
helper.append("        boolean completeFrame = w.completeFrame;\n")
helper.append("        boolean enableHpi = w.enableHpi;\n")
helper.append("        boolean hpiStageOnly = w.hpiStageOnly;\n")
helper.append("        boolean armReadyOnly = w.armReadyOnly;\n")
helper.append("        boolean skipPcm = w.skipPcm;\n")
helper.append("        boolean truncPcmHpiStage = w.truncPcmHpiStage;\n")
helper.append("        boolean pcmWriteCompleted = w.pcmWriteCompleted;\n")
helper.append("        boolean stockUartSpeechPath = w.stockUartSpeechPath;\n")
helper.append("        byte[] adapterOutput = w.adapterOutput;\n")
helper.append("        byte[] originalShpr3 = w.originalShpr3;\n")
helper.append("        long evidenceHoldInitialTicks = w.evidenceHoldInitialTicks;\n")
helper.append("        long evidenceReleaseDelayTicks = w.evidenceReleaseDelayTicks;\n")
helper.append("        File device = w.device;\n")
helper.append("        byte[] wire = w.wire;\n")
helper.append("        byte[] transmitted = w.transmitted;\n")
helper.append("        boolean creditShapeAccepted = w.creditShapeAccepted;\n")
helper.append("        boolean contentAccepted = w.contentAccepted;\n")
helper.append("        String contentFailure = w.contentFailure;\n")
helper.append("        int hpiReadReturn = w.hpiReadReturn;\n")
helper.append("        int hpiReadLength = w.hpiReadLength;\n")
helper.append("        int hpiReadField = w.hpiReadField;\n")
helper.append("        int hpiReadPointer = w.hpiReadPointer;\n")
helper.append("        byte[] internalHpiResponse = w.internalHpiResponse;\n")
helper.append("        int rxLength = w.rxLength;\n")
helper.append("        int firstError = w.firstError;\n")
helper.append("        int hpiAttempted = w.hpiAttempted;\n")
helper.append("        int hpiCompleted = w.hpiCompleted;\n")
helper.append("        int isrCount = w.isrCount;\n")
helper.append("        int uartErrorCount = w.uartErrorCount;\n")
helper.append("        int mcuBaud = w.mcuBaud;\n")
helper.append("        int androidBaud = w.androidBaud;\n")
helper.append("        boolean postExitTextFaceConfirmed = w.postExitTextFaceConfirmed;\n")
helper.append("        boolean evidenceHoldActive = w.evidenceHoldActive;\n")
# The extracted block currently starts with 12-space indent; keep it.
helper.append(block)
helper.append("        w.input = input;\n")
helper.append("        w.output = output;\n")
helper.append("        w.adapterOutput = adapterOutput;\n")
helper.append("        w.creditShapeAccepted = creditShapeAccepted;\n")
helper.append("        w.contentAccepted = contentAccepted;\n")
helper.append("        w.contentFailure = contentFailure;\n")
helper.append("        w.hpiReadReturn = hpiReadReturn;\n")
helper.append("        w.hpiReadLength = hpiReadLength;\n")
helper.append("        w.hpiReadField = hpiReadField;\n")
helper.append("        w.hpiReadPointer = hpiReadPointer;\n")
helper.append("        w.internalHpiResponse = internalHpiResponse;\n")
helper.append("        w.rxLength = rxLength;\n")
helper.append("        w.firstError = firstError;\n")
helper.append("        w.hpiAttempted = hpiAttempted;\n")
helper.append("        w.hpiCompleted = hpiCompleted;\n")
helper.append("        w.isrCount = isrCount;\n")
helper.append("        w.uartErrorCount = uartErrorCount;\n")
helper.append("        w.mcuBaud = mcuBaud;\n")
helper.append("        w.androidBaud = androidBaud;\n")
helper.append("        w.postExitTextFaceConfirmed = postExitTextFaceConfirmed;\n")
helper.append("        w.evidenceHoldActive = evidenceHoldActive;\n")
helper.append("        w.postRange = postRange;\n")
helper.append("        w.stockHpiStateTransactionValid = stockHpiStateTransactionValid;\n")
helper.append("        w.adapterOutcome = adapterOutcome;\n")
helper.append("        w.backgroundType20Present = backgroundType20Present;\n")
helper.append("        w.bufferMatchesWire = bufferMatchesWire;\n")
helper.append("        w.timeoutSnapshotValid = timeoutSnapshotValid;\n")
helper.append("        w.evidenceHoldValid = evidenceHoldValid;\n")
helper.append("    }\n\n")
helper.append("    static final class FrameAdapterPostTimeout {\n")
helper.append("        InputStream input;\n")
helper.append("        OutputStream output;\n")
helper.append("        StringBuilder report;\n")
helper.append("        File device;\n")
helper.append("        boolean completeToneHpi;\n")
helper.append("        boolean completeFrame;\n")
helper.append("        boolean enableHpi;\n")
helper.append("        boolean hpiStageOnly;\n")
helper.append("        boolean armReadyOnly;\n")
helper.append("        boolean skipPcm;\n")
helper.append("        boolean truncPcmHpiStage;\n")
helper.append("        boolean pcmWriteCompleted;\n")
helper.append("        boolean stockUartSpeechPath;\n")
helper.append("        byte[] adapterOutput;\n")
helper.append("        byte[] originalShpr3;\n")
helper.append("        byte[] wire;\n")
helper.append("        byte[] transmitted;\n")
helper.append("        long evidenceHoldInitialTicks;\n")
helper.append("        long evidenceReleaseDelayTicks;\n")
helper.append("        boolean creditShapeAccepted;\n")
helper.append("        boolean contentAccepted = true;\n")
helper.append("        String contentFailure;\n")
helper.append("        int hpiReadReturn;\n")
helper.append("        int hpiReadLength = -1;\n")
helper.append("        int hpiReadField = -1;\n")
helper.append("        int hpiReadPointer;\n")
helper.append("        byte[] internalHpiResponse = new byte[0];\n")
helper.append("        int rxLength = -1;\n")
helper.append("        int firstError = -1;\n")
helper.append("        int hpiAttempted = -1;\n")
helper.append("        int hpiCompleted = -1;\n")
helper.append("        int isrCount = -1;\n")
helper.append("        int uartErrorCount = -1;\n")
helper.append("        int mcuBaud;\n")
helper.append("        int androidBaud;\n")
helper.append("        boolean postExitTextFaceConfirmed;\n")
helper.append("        boolean evidenceHoldActive;\n")
helper.append("        byte[] postRange;\n")
helper.append("        boolean stockHpiStateTransactionValid;\n")
helper.append("        boolean adapterOutcome;\n")
helper.append("        boolean backgroundType20Present;\n")
helper.append("        boolean bufferMatchesWire;\n")
helper.append("        boolean timeoutSnapshotValid;\n")
helper.append("        boolean evidenceHoldValid;\n")
helper.append("    }\n\n")

replacement = (
    "            FrameAdapterPostTimeout postTimeout = new FrameAdapterPostTimeout();\n"
    "            postTimeout.input = input;\n"
    "            postTimeout.output = output;\n"
    "            postTimeout.report = report;\n"
    "            postTimeout.device = device;\n"
    "            postTimeout.completeToneHpi = completeToneHpi;\n"
    "            postTimeout.completeFrame = completeFrame;\n"
    "            postTimeout.enableHpi = enableHpi;\n"
    "            postTimeout.hpiStageOnly = hpiStageOnly;\n"
    "            postTimeout.armReadyOnly = armReadyOnly;\n"
    "            postTimeout.skipPcm = skipPcm;\n"
    "            postTimeout.truncPcmHpiStage = truncPcmHpiStage;\n"
    "            postTimeout.pcmWriteCompleted = pcmWriteCompleted;\n"
    "            postTimeout.stockUartSpeechPath = stockUartSpeechPath;\n"
    "            postTimeout.adapterOutput = adapterOutput;\n"
    "            postTimeout.originalShpr3 = originalShpr3;\n"
    "            postTimeout.wire = wire;\n"
    "            postTimeout.transmitted = transmitted;\n"
    "            postTimeout.evidenceHoldInitialTicks = evidenceHoldInitialTicks;\n"
    "            postTimeout.evidenceReleaseDelayTicks = evidenceReleaseDelayTicks;\n"
    "            postTimeout.creditShapeAccepted = creditShapeAccepted;\n"
    "            postTimeout.contentAccepted = contentAccepted;\n"
    "            postTimeout.contentFailure = contentFailure;\n"
    "            postTimeout.hpiReadReturn = hpiReadReturn;\n"
    "            postTimeout.hpiReadLength = hpiReadLength;\n"
    "            postTimeout.hpiReadField = hpiReadField;\n"
    "            postTimeout.hpiReadPointer = hpiReadPointer;\n"
    "            postTimeout.internalHpiResponse = internalHpiResponse;\n"
    "            postTimeout.rxLength = rxLength;\n"
    "            postTimeout.firstError = firstError;\n"
    "            postTimeout.hpiAttempted = hpiAttempted;\n"
    "            postTimeout.hpiCompleted = hpiCompleted;\n"
    "            postTimeout.isrCount = isrCount;\n"
    "            postTimeout.uartErrorCount = uartErrorCount;\n"
    "            postTimeout.mcuBaud = mcuBaud;\n"
    "            postTimeout.androidBaud = androidBaud;\n"
    "            postTimeout.postExitTextFaceConfirmed = postExitTextFaceConfirmed;\n"
    "            postTimeout.evidenceHoldActive = evidenceHoldActive;\n"
    "            collectFrameAdapterPostTimeoutEvidence(postTimeout);\n"
    "            input = postTimeout.input;\n"
    "            output = postTimeout.output;\n"
    "            adapterOutput = postTimeout.adapterOutput;\n"
    "            creditShapeAccepted = postTimeout.creditShapeAccepted;\n"
    "            contentAccepted = postTimeout.contentAccepted;\n"
    "            contentFailure = postTimeout.contentFailure;\n"
    "            hpiReadReturn = postTimeout.hpiReadReturn;\n"
    "            hpiReadLength = postTimeout.hpiReadLength;\n"
    "            hpiReadField = postTimeout.hpiReadField;\n"
    "            hpiReadPointer = postTimeout.hpiReadPointer;\n"
    "            internalHpiResponse = postTimeout.internalHpiResponse;\n"
    "            rxLength = postTimeout.rxLength;\n"
    "            firstError = postTimeout.firstError;\n"
    "            hpiAttempted = postTimeout.hpiAttempted;\n"
    "            hpiCompleted = postTimeout.hpiCompleted;\n"
    "            isrCount = postTimeout.isrCount;\n"
    "            uartErrorCount = postTimeout.uartErrorCount;\n"
    "            mcuBaud = postTimeout.mcuBaud;\n"
    "            androidBaud = postTimeout.androidBaud;\n"
    "            postExitTextFaceConfirmed = postTimeout.postExitTextFaceConfirmed;\n"
    "            evidenceHoldActive = postTimeout.evidenceHoldActive;\n"
    "            byte[] postRange = postTimeout.postRange;\n"
    "            boolean stockHpiStateTransactionValid = postTimeout.stockHpiStateTransactionValid;\n"
    "            boolean adapterOutcome = postTimeout.adapterOutcome;\n"
    "            boolean backgroundType20Present = postTimeout.backgroundType20Present;\n"
    "            boolean bufferMatchesWire = postTimeout.bufferMatchesWire;\n"
    "            boolean timeoutSnapshotValid = postTimeout.timeoutSnapshotValid;\n"
    "            boolean evidenceHoldValid = postTimeout.evidenceHoldValid;\n"
    "            if (completeToneHpi) {\n"
    "                adapterOutcome = adapterOutcome && stockHpiStateTransactionValid;\n"
    "            }\n"
    "            if (!adapterOutcome) {\n"
    "                throw new IOException(\n"
    "                        \"帧级适配器遥测、缓存或自动退桥不符\"\n"
    "                                + \" hpi_attempted=\" + hpiAttempted\n"
    "                                + \" first_error=\" + firstError\n"
    "                                + (backgroundType20Present\n"
    "                                ? \" background_type20=1\" : \"\"));\n"
    "            }\n"
    "            append(report, \"posttimeout full range SHA-256\", sha256(postRange));\n"
)

# Insert helper just before runFrameAdapterPcmNoRf
anchor = "    private ProbeResult runFrameAdapterPcmNoRf(boolean completeFrame,\n            boolean enableHpi, boolean armReadyOnly, boolean hpiStageOnly) {\n"
if anchor not in text:
    raise SystemExit("anchor not found")

new_lines = lines[: start - 1] + [replacement] + lines[end:]
new_text = "".join(new_lines)
# The 4-arg overload is the first runFrameAdapterPcmNoRf; insert helper before the 5-arg one instead
idx = new_text.find("    private ProbeResult runFrameAdapterPcmNoRf(boolean completeFrame,\n            boolean enableHpi, boolean armReadyOnly, boolean hpiStageOnly,\n            boolean preArmVocoder90) {")
if idx < 0:
    raise SystemExit("5-arg method not found")
new_text = new_text[:idx] + "".join(helper) + new_text[idx:]
src_path.write_text(new_text, encoding="utf-8")
print("extracted", start, end, "helper_chars", len("".join(helper)))
