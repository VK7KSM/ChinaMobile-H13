from pathlib import Path

probe = Path(
    r"C:/Dev/H13_D22/research/h13_interphone_probe/app/src/main/java/"
    r"net/elfradio/h13interphoneprobe/InterphoneProbe.java"
)
main = Path(
    r"C:/Dev/H13_D22/research/h13_interphone_probe/app/src/main/java/"
    r"net/elfradio/h13interphoneprobe/MainActivity.java"
)
gradle = Path(
    r"C:/Dev/H13_D22/research/h13_interphone_probe/app/build.gradle"
)
host = Path(r"C:/Dev/H13_D22/research/h13_radio/tools/h13_probe_session.ps1")

t = probe.read_text(encoding="utf-8")

# skipPcm: no-PCM stage keeps enableHpi=false; complete+stage uses enableHpi=true as write flag
t = t.replace(
    "boolean skipPcm = armReadyOnly || (hpiStageOnly && completeFrame);",
    "boolean skipPcm = armReadyOnly || (hpiStageOnly && completeFrame && !enableHpi);",
)
if "boolean skipPcm = armReadyOnly || (hpiStageOnly && completeFrame && !enableHpi);" not in t:
    if "boolean skipPcm = armReadyOnly;" in t:
        t = t.replace(
            "boolean skipPcm = armReadyOnly;",
            "boolean skipPcm = armReadyOnly || (hpiStageOnly && completeFrame && !enableHpi);",
            1,
        )

if "runFrameAdapterCompletePcmHpiStageNoRf" not in t:
    needle = (
        "    ProbeResult runFrameAdapterTruncatedPcmHpiStageNoRf() {\n"
        "        return runFrameAdapterPcmNoRf(false, false, false, true);\n"
        "    }"
    )
    insert = (
        needle
        + """

    /**
     * v2.50：allow=2 + 完整1290字节PCM（USART 收满后最短 HPI）。
     * enableHpi 在此仅作“写出完整PCM”开关；HPI 允许值仍由 hpiStageOnly 固定为 2。
     */
    ProbeResult runFrameAdapterCompletePcmHpiStageNoRf() {
        return runFrameAdapterPcmNoRf(true, true, false, true);
    }"""
    )
    if needle not in t:
        raise SystemExit("trunc entry missing")
    t = t.replace(needle, insert, 1)

# complete PCM gate after trunc gate
if "completePcmHpiStage" not in t:
    marker = "if (adapterOutcome && truncPcmHpiStage) {"
    i = t.find(marker)
    if i < 0:
        raise SystemExit("trunc gate missing")
    # skip this whole if-block by brace matching
    brace = t.find("{", i)
    depth = 0
    j = brace
    while j < len(t):
        if t[j] == "{":
            depth += 1
        elif t[j] == "}":
            depth -= 1
            if depth == 0:
                j += 1
                break
        j += 1
    complete_gate = """
                boolean completePcmHpiStage = hpiStageOnly && completeFrame && enableHpi;
                if (adapterOutcome && completePcmHpiStage) {
                    boolean pcmGate = pcmWriteCompleted
                            && rxLength >= FRAME_ADAPTER_BUFFER_LENGTH;
                    append(report, "完整PCM叠加门",
                            "pcmWriteCompleted=" + pcmWriteCompleted
                                    + " rx_len=" + rxLength
                                    + " need>=" + FRAME_ADAPTER_BUFFER_LENGTH
                                    + " ok=" + pcmGate);
                    if (!pcmGate) {
                        throw new IOException("完整PCM叠加门失败：pcmWriteCompleted="
                                + pcmWriteCompleted + " rx_len=" + rxLength);
                    }
                }
"""
    t = t[:j] + complete_gate + t[j:]

# success status for complete stage
t = t.replace(
    '? (truncPcmHpiStage\n'
    '                        ? "通过：早写64字节截断PCM且rx>=64+PendSV最短HPI 8字节+粘性阶段门；恢复完整"\n'
    '                        : "通过：PendSV最短HPI真实包装返回8字节+粘性阶段门；恢复完整")',
    '? (truncPcmHpiStage\n'
    '                        ? "通过：64字节截断PCM且rx>=64+allow=2最短HPI；恢复完整"\n'
    '                        : ((completeFrame && enableHpi)\n'
    '                        ? "通过：1290完整PCM且rx>=1290+allow=2最短HPI；恢复完整"\n'
    '                        : "通过：allow=2最短HPI真实包装返回8字节+粘性阶段门；恢复完整"))',
)

probe.write_text(t, encoding="utf-8")

# MainActivity mode
mt = main.read_text(encoding="utf-8")
if "hpi_frame_adapter_complete_pcm_hpi_stage_no_rf" not in mt:
    mt = mt.replace(
        '"hpi_frame_adapter_truncated_pcm_hpi_stage_no_rf",',
        '"hpi_frame_adapter_truncated_pcm_hpi_stage_no_rf",\n'
        '            "hpi_frame_adapter_complete_pcm_hpi_stage_no_rf",',
        1,
    )
    mt = mt.replace(
        '} else if ("hpi_frame_adapter_truncated_pcm_hpi_stage_no_rf".equals(\n'
        "                    probeMode)) {\n"
        "                result = probe.runFrameAdapterTruncatedPcmHpiStageNoRf();",
        '} else if ("hpi_frame_adapter_truncated_pcm_hpi_stage_no_rf".equals(\n'
        "                    probeMode)) {\n"
        "                result = probe.runFrameAdapterTruncatedPcmHpiStageNoRf();\n"
        '            } else if ("hpi_frame_adapter_complete_pcm_hpi_stage_no_rf".equals(\n'
        "                    probeMode)) {\n"
        "                result = probe.runFrameAdapterCompletePcmHpiStageNoRf();",
        1,
    )
    main.write_text(mt, encoding="utf-8")

g = gradle.read_text(encoding="utf-8")
g = g.replace(
    "versionCode 249\n        versionName '2.49-trunc-pcm-usart-rx-asm'",
    "versionCode 250\n        versionName '2.50-complete-pcm-hpi-stage'",
)
gradle.write_text(g, encoding="utf-8")

# host mode lists: add after truncated_pcm_hpi_stage
ht = host.read_text(encoding="utf-8")
mode = "hpi_frame_adapter_complete_pcm_hpi_stage_no_rf"
if mode not in ht:
    ht = ht.replace(
        '"hpi_frame_adapter_truncated_pcm_hpi_stage_no_rf",\n    "hpi_frame_adapter_truncated_pcm_no_hpi_no_rf",',
        '"hpi_frame_adapter_truncated_pcm_hpi_stage_no_rf",\n'
        f'    "{mode}",\n'
        '    "hpi_frame_adapter_truncated_pcm_no_hpi_no_rf",',
    )
    # PASS atomic for complete
    block = '''
    if ($Mode -eq "hpi_frame_adapter_complete_pcm_hpi_stage_no_rf" -and $values["result"] -eq "PASS") {
        if ($values["adapter_code_bytes"] -ne "1012" -or
                $values["adapter_tx_len"] -ne "1290" -or
                $values["adapter_hpi_attempted"] -ne "1" -or
                $values["adapter_hpi_completed"] -ne "1" -or
                $values["pcm_written"] -ne "true" -or
                $values["generated_data36"] -ne "false" -or
                $values["rf_command_count"] -ne "0" -or
                $values["transport"] -ne "TEXT_CONFIRMED" -or
                $values["sram_restored"] -ne "true" -or
                $values["control_plane_restored"] -ne "true" -or
                $values["reboot_required"] -ne "false") {
            Write-Step "完整PCM+最短HPI模式原子字段与PASS矛盾"
            $ok = $false
        }
        $rx = 0
        if ($values.ContainsKey("adapter_rx_len")) {
            [void][int]::TryParse($values["adapter_rx_len"], [ref]$rx)
        }
        if ($rx -lt 1290) {
            Write-Step "完整PCM+最短HPI模式 adapter_rx_len 不足1290"
            $ok = $false
        }
    }
'''
    anchor = 'if ($Mode -eq "hpi_frame_adapter_truncated_pcm_hpi_stage_no_rf" -and $values["result"] -eq "PASS")'
    # append after truncated PASS block end - rough: after first reboot_required check following truncated
    idx = ht.find(anchor)
    if idx < 0:
        raise SystemExit("trunc pass block missing")
    # find next 'if ($Mode -eq' after this block
    nxt = ht.find("\n    if ($Mode -eq", idx + 10)
    ht = ht[:nxt] + block + ht[nxt:]
    # evidence reuse
    ev = '''
        if ($Mode -eq "hpi_frame_adapter_complete_pcm_hpi_stage_no_rf") {
            $hpiStageEvidenceComplete =
                    Test-FrameAdapterHpiStageEvidence `
                    -Directory $CaptureDir -AtomicValues $atomicMap
            if ($pass -and -not $hpiStageEvidenceComplete) {
                Write-Step '设备虽报告PASS，但完整PCM+最短HPI原始证据未通过；宿主强制改判FAIL'
                $pass = $false
                $fail = $true
                $sessionFailure = '完整PCM+最短HPI原始证据不完整或相互矛盾，禁止高标准验收'
            }
        }
'''
    if "完整PCM+最短HPI原始证据未通过" not in ht:
        ht = ht.replace(
            'if ($Mode -eq "hpi_frame_adapter_arm_ready_no_hpi_no_rf") {',
            ev + '\n        if ($Mode -eq "hpi_frame_adapter_arm_ready_no_hpi_no_rf") {',
            1,
        )
    host.write_text(ht, encoding="utf-8")

print("v250 patches applied")
