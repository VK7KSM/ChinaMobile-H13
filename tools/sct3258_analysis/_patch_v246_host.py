from pathlib import Path
p = Path(r"C:\Dev\H13_D22\research\h13_radio\tools\h13_probe_session.ps1")
t = p.read_text(encoding="utf-8")
mode = "hpi_frame_adapter_truncated_pcm_hpi_stage_no_rf"
old_mode = "hpi_frame_adapter_hpi_stage_no_pcm_no_rf"
# Insert after each list occurrence of old_mode in mode arrays (not all if conditions)
replacements = 0
# KnownProbeModes / HardIsolation / PowerCodeForbidden / PotentialRfPermissionForbidden
for anchor in [
    '    "hpi_frame_adapter_hpi_stage_no_pcm_no_rf",\n    "hpi_frame_adapter_truncated_pcm_no_hpi_no_rf",',
    '    "hpi_frame_adapter_hpi_stage_no_pcm_no_rf",\n    "hpi_frame_adapter_truncated_pcm_no_hpi_no_rf",',
]:
    new = '    "hpi_frame_adapter_hpi_stage_no_pcm_no_rf",\n    "hpi_frame_adapter_truncated_pcm_hpi_stage_no_rf",\n    "hpi_frame_adapter_truncated_pcm_no_hpi_no_rf",'
    if anchor in t:
        t = t.replace(anchor, new)
        replacements += t.count(new)  # rough

# Ensure all four array blocks got it - count occurrences of new mode
if t.count(mode) < 4:
    # more careful per-list insert after old_mode line when next is truncated_pcm_no_hpi
    lines = t.splitlines(keepends=True)
    out = []
    i = 0
    while i < len(lines):
        out.append(lines[i])
        if (old_mode in lines[i] and mode not in lines[i]
                and i+1 < len(lines)
                and "hpi_frame_adapter_truncated_pcm_no_hpi_no_rf" in lines[i+1]
                and mode not in lines[i+1]):
            indent = lines[i][:len(lines[i]) - len(lines[i].lstrip())]
            out.append(f'{indent}"{mode}",\n')
        i += 1
    t = ''.join(out)

# Required fields block after hpi_stage block
needle = '''    if ($Mode -eq "hpi_frame_adapter_hpi_stage_no_pcm_no_rf") {
        $required += @("adapter_sha256", "adapter_code_bytes",
                "adapter_sram_range", "adapter_sram_range_bytes",
                "adapter_usart1_entry", "adapter_systick_entry",
                "adapter_arm_entry", "adapter_expected_len",
                "adapter_tx_len", "adapter_rx_len", "adapter_first_error",
                "adapter_hpi_attempted", "adapter_hpi_completed",
                "adapter_isr_count", "adapter_uart_error_count",
                "adapter_timeout_initial_ticks", "pcm_written",
                "generated_data36", "data36_written", "work_mode_written",
                "ptt_written", "pa_written", "rf_command_count")
    }
'''
insert = '''    if ($Mode -eq "hpi_frame_adapter_hpi_stage_no_pcm_no_rf") {
        $required += @("adapter_sha256", "adapter_code_bytes",
                "adapter_sram_range", "adapter_sram_range_bytes",
                "adapter_usart1_entry", "adapter_systick_entry",
                "adapter_arm_entry", "adapter_expected_len",
                "adapter_tx_len", "adapter_rx_len", "adapter_first_error",
                "adapter_hpi_attempted", "adapter_hpi_completed",
                "adapter_isr_count", "adapter_uart_error_count",
                "adapter_timeout_initial_ticks", "pcm_written",
                "generated_data36", "data36_written", "work_mode_written",
                "ptt_written", "pa_written", "rf_command_count")
    }
    if ($Mode -eq "hpi_frame_adapter_truncated_pcm_hpi_stage_no_rf") {
        $required += @("adapter_sha256", "adapter_code_bytes",
                "adapter_sram_range", "adapter_sram_range_bytes",
                "adapter_usart1_entry", "adapter_systick_entry",
                "adapter_arm_entry", "adapter_expected_len",
                "adapter_tx_len", "adapter_rx_len", "adapter_first_error",
                "adapter_hpi_attempted", "adapter_hpi_completed",
                "adapter_isr_count", "adapter_uart_error_count",
                "adapter_timeout_initial_ticks", "pcm_written",
                "pcm_truncated_prefix_written", "generated_data36",
                "data36_written", "work_mode_written", "ptt_written",
                "pa_written", "rf_command_count")
    }
'''
if needle not in t:
    raise SystemExit('required fields needle missing')
if mode not in t[t.find('if ($Mode -eq "hpi_frame_adapter_hpi_stage'):t.find('if ($Mode -eq "hpi_frame_adapter_arm_ready')]:
    t = t.replace(needle, insert, 1)

# PASS atomic field checks
pass_needle = '''    if ($Mode -eq "hpi_frame_adapter_hpi_stage_no_pcm_no_rf" -and $values["result"] -eq "PASS") {
        if ($values["adapter_sha256"] -ne
                    "2832d509c40e3d331c94e518b2385a9d8ad09e025999963d975c485848e84109" -or
                $values["adapter_code_bytes"] -ne "1212" -or
                $values["adapter_sram_range_bytes"] -ne "2856" -or
                $values["adapter_tx_len"] -ne "0" -or
                $values["adapter_hpi_attempted"] -ne "1" -or
                $values["adapter_hpi_completed"] -ne "1" -or
                $values["pcm_written"] -ne "false" -or
                $values["generated_data36"] -ne "false" -or
                $values["rf_command_count"] -ne "0" -or
                $values["transport"] -ne "TEXT_CONFIRMED" -or
                $values["sram_restored"] -ne "true" -or
                $values["control_plane_restored"] -ne "true" -or
                $values["reboot_required"] -ne "false") {
            Write-Step "最短HPI阶段无PCM模式原子字段与PASS矛盾"
            $ok = $false
        }
    }
'''
pass_insert = pass_needle + '''    if ($Mode -eq "hpi_frame_adapter_truncated_pcm_hpi_stage_no_rf" -and $values["result"] -eq "PASS") {
        if ($values["adapter_sha256"] -ne
                    "2832d509c40e3d331c94e518b2385a9d8ad09e025999963d975c485848e84109" -or
                $values["adapter_code_bytes"] -ne "1212" -or
                $values["adapter_sram_range_bytes"] -ne "2856" -or
                $values["adapter_tx_len"] -ne "64" -or
                $values["adapter_hpi_attempted"] -ne "1" -or
                $values["adapter_hpi_completed"] -ne "1" -or
                $values["pcm_written"] -ne "true" -or
                $values["pcm_truncated_prefix_written"] -ne "true" -or
                $values["generated_data36"] -ne "false" -or
                $values["rf_command_count"] -ne "0" -or
                $values["transport"] -ne "TEXT_CONFIRMED" -or
                $values["sram_restored"] -ne "true" -or
                $values["control_plane_restored"] -ne "true" -or
                $values["reboot_required"] -ne "false") {
            Write-Step "截断PCM+最短HPI模式原子字段与PASS矛盾"
            $ok = $false
        }
        $rx = 0
        if ($values.ContainsKey("adapter_rx_len")) {
            [void][int]::TryParse($values["adapter_rx_len"], [ref]$rx)
        }
        if ($rx -lt 64) {
            Write-Step "截断PCM+最短HPI模式 adapter_rx_len 不足64"
            $ok = $false
        }
    }
'''
if pass_needle not in t:
    raise SystemExit('pass needle missing')
if '截断PCM+最短HPI模式原子字段与PASS矛盾' not in t:
    t = t.replace(pass_needle, pass_insert, 1)

# Evidence gate at end
ev_needle = '''        if ($Mode -eq "hpi_frame_adapter_hpi_stage_no_pcm_no_rf") {
            $hpiStageEvidenceComplete =
                    Test-FrameAdapterHpiStageEvidence `
                    -Directory $CaptureDir -AtomicValues $atomicMap
            if ($pass -and -not $hpiStageEvidenceComplete) {
                Write-Step '设备虽报告PASS，但最短HPI阶段原始证据未通过；宿主强制改判FAIL'
                $pass = $false
                $fail = $true
                $sessionFailure = '最短HPI阶段原始证据不完整或相互矛盾，禁止高标准验收'
            }
        }
'''
ev_insert = ev_needle + '''        if ($Mode -eq "hpi_frame_adapter_truncated_pcm_hpi_stage_no_rf") {
            $hpiStageEvidenceComplete =
                    Test-FrameAdapterHpiStageEvidence `
                    -Directory $CaptureDir -AtomicValues $atomicMap
            $truncTx = @(Get-ChildItem -LiteralPath (Join-Path $CaptureDir 'device_pull') -File -ErrorAction SilentlyContinue |
                    Where-Object { $_.Name -like 'h13_frame_adapter_truncated_tx_*.bin' -and $_.Length -eq 64 })
            if ($truncTx.Count -ne 1) {
                Write-Step ("截断PCM写出证据缺失或重复 count={0}" -f $truncTx.Count)
                $hpiStageEvidenceComplete = $false
            }
            if ($pass -and -not $hpiStageEvidenceComplete) {
                Write-Step '设备虽报告PASS，但截断PCM+最短HPI原始证据未通过；宿主强制改判FAIL'
                $pass = $false
                $fail = $true
                $sessionFailure = '截断PCM+最短HPI原始证据不完整或相互矛盾，禁止高标准验收'
            }
        }
'''
if ev_needle not in t:
    raise SystemExit('evidence needle missing')
if '截断PCM+最短HPI原始证据未通过' not in t:
    t = t.replace(ev_needle, ev_insert, 1)

p.write_text(t, encoding='utf-8')
print('mode_count', t.count(mode))
print('sha', __import__('hashlib').sha256(p.read_bytes()).hexdigest())
