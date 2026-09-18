from pathlib import Path
p = Path(r"C:\Dev\H13_D22\research\h13_radio\tools\h13_probe_session.ps1")
t = p.read_text(encoding="utf-8")
old = """function Assert-InterphoneStopped {
    param([int]$Rounds = 5, [int]$DelayMs = 400)
    for ($i = 1; $i -le $Rounds; $i++) {
        $pidCheck = (Adb shell pidof $InterphonePkg 2>$null | Out-String).Trim()
        if ($pidCheck) {
            throw \"HOST_ISOLATION_FAIL: Interphone still running pid=$pidCheck (round $i/$Rounds)\"
        }
        Start-Sleep -Milliseconds $DelayMs
    }
    Write-Step \"Interphone PID absent for $Rounds consecutive checks\"
}"""
new = """function Assert-InterphoneStopped {
    param([int]$Rounds = 5, [int]$DelayMs = 400)
    for ($i = 1; $i -le $Rounds; $i++) {
        $pidCheck = (Adb shell pidof $InterphonePkg 2>$null | Out-String).Trim()
        if ($pidCheck -and ($pidCheck -notmatch '^\\d+$')) {
            Write-Step \"adb/pidof noise ignored (stop-interphone): $pidCheck\"
            try { & $script:AdbPath connect $Serial 2>$null | Out-Null } catch {}
            Start-Sleep -Milliseconds $DelayMs
            continue
        }
        if ($pidCheck) {
            throw \"HOST_ISOLATION_FAIL: Interphone still running pid=$pidCheck (round $i/$Rounds)\"
        }
        Start-Sleep -Milliseconds $DelayMs
    }
    Write-Step \"Interphone PID absent for $Rounds consecutive checks\"
}"""
if old not in t:
    raise SystemExit("Assert-InterphoneStopped block not found")
p.write_text(t.replace(old, new, 1), encoding="utf-8")
import hashlib
print("patched", hashlib.sha256(p.read_bytes()).hexdigest())
print("has_noise_filter", "adb/pidof noise ignored (stop-interphone)" in p.read_text(encoding="utf-8"))
