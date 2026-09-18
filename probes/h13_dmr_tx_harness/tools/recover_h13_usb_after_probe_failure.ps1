param(
    [string]$Reason = 'probe_failure'
)

$ErrorActionPreference = 'Stop'
$Adb = 'C:\Dev\android-sdk\platform-tools\adb.exe'
$AdbPort = '5038'
$Serial = '0'
$ProbePackage = 'net.elfradio.h13dmrtx'
$ProductionPackage = 'net.elfradio.h13interphone'
$ProductionActivity =
        'net.elfradio.h13interphone/com.bozhou.interphone.ui.talk.MainActivity'
$ExpectedFingerprint =
        'CMCC/msm8909/msm8909:8.1.0/OPM1.171019.026/build11020953:user/test-keys'
$Project = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..')).Path
$DatePart = Get-Date -Format 'yyyy-MM-dd'
$Stamp = Get-Date -Format 'yyyyMMdd_HHmmss'
$Capture = Join-Path $Project "research\h13_radio\captures\$DatePart\h13-usb-recovery-$Reason-$Stamp"

function Invoke-Adb {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Arguments)
    $PreviousError = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $Output = & $Adb -P $AdbPort -s $Serial @Arguments 2>&1 |
                ForEach-Object { $_.ToString() }
        $ExitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $PreviousError
    }
    if ($ExitCode -ne 0) {
        throw "ADB命令失败：$($Arguments -join ' ')；$($Output -join ' ')"
    }
    return @($Output)
}

function Save-Text {
    param([string]$Name, [object]$Value)
    $Value | Out-File -LiteralPath (Join-Path $Capture $Name) -Encoding utf8
}

function Get-GpioState {
    $Rows = @()
    foreach ($Name in @('dmr_switch','audio_switch','pa_enable','ptt_d',
            'freq_section')) {
        $Value = (Invoke-Adb -Arguments @(
                'shell','su','-c',"cat /sys/boptt/$Name")) -join ''
        $Rows += "$Name=$($Value.Trim())"
    }
    return $Rows -join "`n"
}

function Get-WifiState {
    $Enabled = ((Invoke-Adb shell settings get global wifi_on) -join '').Trim()
    $State = ((Invoke-Adb shell cat /sys/class/net/wlan0/operstate) -join '').Trim()
    return "wifi_on=$Enabled`noperstate=$State"
}

if (-not (Test-Path -LiteralPath $Adb -PathType Leaf)) {
    throw "找不到ADB：$Adb"
}
$env:ANDROID_ADB_SERVER_PORT = $AdbPort
$PreviousError = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
try {
    & $Adb -P $AdbPort start-server | Out-Null
} finally {
    $ErrorActionPreference = $PreviousError
}
New-Item -ItemType Directory -Path $Capture | Out-Null

Save-Text '恢复原因.txt' $Reason
$PreviousError = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
try {
    $DevicesBefore = & $Adb -P $AdbPort devices -l 2>&1 |
            ForEach-Object { $_.ToString() }
} finally {
    $ErrorActionPreference = $PreviousError
}
Save-Text 'adb_devices_before.txt' $DevicesBefore
$PreviousError = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
try {
    $ProcessBefore = & $Adb -P $AdbPort -s $Serial shell ps -A 2>&1 |
            ForEach-Object { $_.ToString() }
    $TtyBefore = & $Adb -P $AdbPort -s $Serial shell su -c 'lsof /dev/ttyHS0 2>&1' |
            ForEach-Object { $_.ToString() }
} finally {
    $ErrorActionPreference = $PreviousError
}
Save-Text 'process_before.txt' $ProcessBefore
Save-Text 'tty_owner_before.txt' $TtyBefore

Invoke-Adb reboot | Out-Null
& $Adb -P $AdbPort -s $Serial wait-for-device | Out-Null
if ($LASTEXITCODE -ne 0) {
    throw '重启后等待USB ADB失败。'
}

$Booted = $false
for ($Attempt = 1; $Attempt -le 90; $Attempt++) {
    Start-Sleep -Seconds 1
    $Boot = (& $Adb -P $AdbPort -s $Serial shell getprop sys.boot_completed 2>$null |
            Out-String).Trim()
    if ($Boot -eq '1') {
        $Booted = $true
        break
    }
}
if (-not $Booted) {
    throw '重启后Android未在90秒内完成启动。'
}

Invoke-Adb shell am force-stop $ProbePackage | Out-Null
Save-Text 'disable_probe.txt' (Invoke-Adb -Arguments @(
        'shell','pm','disable-user','--user','0',$ProbePackage))
Invoke-Adb -Arguments @('logcat','-c') | Out-Null
Save-Text 'enable_production.txt' (Invoke-Adb shell pm enable $ProductionPackage)
Save-Text 'production_start.txt' (Invoke-Adb -Arguments @(
        'shell','am','start','-W','-n',$ProductionActivity))

$Ready = $false
$ProductionPid = ''
$RestoreLog = ''
for ($Attempt = 1; $Attempt -le 25; $Attempt++) {
    Start-Sleep -Seconds 1
    $ProductionPid = ((Invoke-Adb shell pidof $ProductionPackage) -join ' ').Trim()
    $RestoreLog = (Invoke-Adb -Arguments @(
            'logcat','-d','-v','threadtime')) -join "`n"
    $Owner = (Invoke-Adb -Arguments @(
            'shell','su','-c','lsof /dev/ttyHS0 2>&1')) -join "`n"
    if ($ProductionPid -match '^\d+$' -and
            $Owner -match "\s$ProductionPid\s" -and
            $RestoreLog -match '\+DMOCONNECT:0' -and
            $RestoreLog -match '\+DMOGETSOFTVERSION:0\.3\.66;V2\.01\.07I3' -and
            $RestoreLog -match '\+DMOSETDIGITALCH:0') {
        $Ready = $true
        break
    }
}

$PreviousError = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
try {
    $DevicesAfter = & $Adb -P $AdbPort devices -l 2>&1 |
            ForEach-Object { $_.ToString() }
} finally {
    $ErrorActionPreference = $PreviousError
}
Save-Text 'adb_devices_after.txt' $DevicesAfter
$Fingerprint = ((Invoke-Adb shell getprop ro.build.fingerprint) -join '').Trim()
$Boot = ((Invoke-Adb shell getprop sys.boot_completed) -join '').Trim()
Save-Text 'build_and_boot_after.txt' "fingerprint=$Fingerprint`nboot_completed=$Boot"
$PreviousError = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
try {
    $PackageStateAfter = & $Adb -P $AdbPort -s $Serial shell pm list packages -d 2>&1 |
            ForEach-Object { $_.ToString() }
    $ProcessAfter = & $Adb -P $AdbPort -s $Serial shell ps -A 2>&1 |
            ForEach-Object { $_.ToString() }
    $TtyAfter = & $Adb -P $AdbPort -s $Serial shell su -c 'lsof /dev/ttyHS0 2>&1' |
            ForEach-Object { $_.ToString() }
} finally {
    $ErrorActionPreference = $PreviousError
}
Save-Text 'package_state_after.txt' $PackageStateAfter
Save-Text 'process_after.txt' $ProcessAfter
Save-Text 'tty_owner_after.txt' $TtyAfter
Save-Text 'production_pid_after.txt' $ProductionPid
Save-Text 'logcat_full_after.txt' $RestoreLog
$Decisive = $RestoreLog -split "`r?`n" | Where-Object {
    $_ -match 'DMOSTARTUP|DMOCONNECT|DMOGETSOFTVERSION|DMOSETPWRSAVELV|DMOSETDIGITALCH'
}
Save-Text 'logcat_decisive_after.txt' $Decisive
$Gpio = Get-GpioState
$Wifi = Get-WifiState
Save-Text 'gpio_after.txt' $Gpio
Save-Text 'wifi_after.txt' $Wifi

if (-not $Ready -or $Fingerprint -ne $ExpectedFingerprint -or $Boot -ne '1' -or
        $Gpio -ne "dmr_switch=1`naudio_switch=0`npa_enable=0`nptt_d=in`nfreq_section=4" -or
        $Wifi -ne "wifi_on=1`noperstate=up") {
    throw '重启后的生产基线不完整，证据已保存。'
}

$Rows = @("相对路径`t字节数`tSHA-256`t文件时间")
foreach ($File in Get-ChildItem -LiteralPath $Capture -File | Sort-Object Name) {
    $Hash = (Get-FileHash -LiteralPath $File.FullName -Algorithm SHA256).Hash
    $Rows += "$($File.Name)`t$($File.Length)`t$Hash`t$($File.LastWriteTimeUtc.ToString('o'))"
}
$Rows | Out-File -LiteralPath (Join-Path $Capture 'artifacts_sha256.tsv') -Encoding utf8
Write-Output "H13生产基线恢复通过。捕获目录：$Capture"
