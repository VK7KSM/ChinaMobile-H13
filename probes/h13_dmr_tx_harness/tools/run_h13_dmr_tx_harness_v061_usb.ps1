param(
    [ValidateSet('clear_only', 'setup0_only', 'no_rf',
        'deadline_handshake_no_rf', 'realtime_relay_one_data36_no_rf',
        'realtime_relay_fixed_asset_one_data36_no_rf',
        'realtime_relay_fixed_asset_three_data36_no_rf',
        'realtime_relay_fixed_asset_five_data36_no_rf',
        'realtime_relay_fixed_asset_twentyfive_data36_no_rf',
        'realtime_relay_software_49bit_one_data36_no_rf',
        'realtime_relay_software_49bit_tone800_one_data36_no_rf',
        'realtime_relay_software_49bit_morse_one_data36_no_rf',
        'realtime_relay_encode_dmr_silence_one_data36_no_rf',
        'realtime_relay_encode_dmr_tone800_one_data36_no_rf',
        'realtime_relay_encode_dmr_tone800_three_data36_no_rf',
        'realtime_relay_encode_dmr_tone800_five_data36_no_rf',
        'realtime_relay_encode_dmr_morse_unique_one_data36_no_rf',
        'realtime_relay_encode_dmr_morse_unique_three_data36_no_rf',
        'realtime_relay_encode_dmr_morse_unique_five_data36_no_rf',
        'realtime_relay_software_privacy_morse_twentyfive_no_rf',
        'realtime_relay_software_privacy_triple_sos_no_rf')]
    [string]$Mode = 'no_rf',
    [switch]$AllowInstall,
    [switch]$AllowDisableInterphone,
    [switch]$AllowPotentialRf,
    [switch]$Setup0Stable,
    [string]$ClearPrecheckCapture = '',
    [int]$WaitSeconds = 900,
    [switch]$SelfTest
)

$ErrorActionPreference = 'Stop'
$Adb = 'C:\Dev\android-sdk\platform-tools\adb.exe'
$AdbPort = '5038'
$Serial = '0'
$Package = 'net.elfradio.h13dmrtx'
$ProductionPackage = 'net.elfradio.h13interphone'
$ProductionActivity = 'net.elfradio.h13interphone/com.bozhou.interphone.ui.talk.MainActivity'
$Activity = 'net.elfradio.h13dmrtx/.MainActivity'
$ExpectedFingerprint = 'CMCC/msm8909/msm8909:8.1.0/OPM1.171019.026/build11020953:user/test-keys'
$ExpectedVersionCode = 61
$ExpectedVersionName = '0.61-credit-backpressure-min80-no-rf-candidate'
$ExpectedApkSha256 = 'A3030E030D1C4EB15F539986DE245708178DE610393AE889919C9C9421BE30E2'
$Apk = Join-Path $PSScriptRoot '..\dist\H13_DMR_TX_Harness_v0.61_CreditBackpressureMin80NoRfCandidate.apk'
$DeadlineHelper = Join-Path $PSScriptRoot '..\..\h13_radio\tools\h13_external_dmr_rf_deadline_device.sh'
$RemoteDeadlineHelper = '/data/local/tmp/h13_dmr_tx_deadline.sh'
$ExpectedDeadlineHelperSha256 = '522792E4E515DAAF674F56DA953178FC4E1A71812D71DFFE2D3F486BD82B2110'
$Project = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..')).Path
$DatePart = Get-Date -Format 'yyyy-MM-dd'
$Stamp = Get-Date -Format 'yyyyMMdd_HHmmss'
$Capture = Join-Path $Project "research\h13_radio\captures\$DatePart\new-dmr-tx-harness-$Mode-$Stamp"

function Invoke-Adb {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Arguments)
    & $Adb -P $AdbPort -s $Serial @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "ADB命令失败：$($Arguments -join ' ')"
    }
}

function Invoke-AdbOptional {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Arguments)
    $PreviousErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $Output = & $Adb -P $AdbPort -s $Serial @Arguments 2>&1
        $ExitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $PreviousErrorActionPreference
    }
    [pscustomobject]@{
        ExitCode = $ExitCode
        Output = @($Output)
    }
}

function Save-Text {
    param([string]$Name, [object]$Value)
    $Value | Out-File -LiteralPath (Join-Path $Capture $Name) -Encoding utf8
}

function Get-TtyOwnerPids {
    $Attempt = Invoke-AdbOptional shell su -c 'lsof /dev/ttyHS0 2>&1'
    $Raw = $Attempt.Output -join "`n"
    $Pids = @()
    foreach ($Line in ($Raw -split "`r?`n")) {
        if ($Line -match '^\S+\s+(\d+)\s+') {
            $Pids += $Matches[1]
        }
    }
    return @($Pids | Sort-Object -Unique)
}

function Assert-TtyUnowned {
    $Pids = @(Get-TtyOwnerPids)
    if ($Pids.Count -ne 0) {
        throw "串口隔离失败：ttyHS0仍被PID占用：$($Pids -join ',')"
    }
}

function Get-PackagePid {
    param([string]$Name)
    $Attempt = Invoke-AdbOptional shell pidof $Name
    $Raw = ($Attempt.Output -join ' ').Trim()
    $Pids = @($Raw -split '\s+' | Where-Object { $_ -match '^\d+$' })
    if ($Pids.Count -eq 1) { return $Pids[0] }
    if ($Pids.Count -gt 1) { throw "包$Name存在多个进程：$Raw" }
    return ''
}

function Assert-ProductionOwner {
    $ProductionPid = Get-PackagePid $ProductionPackage
    $Owners = @(Get-TtyOwnerPids)
    if (-not $ProductionPid -or $Owners.Count -ne 1 -or
        $Owners[0] -ne $ProductionPid) {
        throw "生产基线失败：Interphone PID=$ProductionPid，ttyHS0所有者=$($Owners -join ',')"
    }
    return $ProductionPid
}

function Get-GpioState {
    $Rows = @()
    foreach ($Name in @('dmr_switch','audio_switch','pa_enable','ptt_d','freq_section')) {
        $Attempt = Invoke-AdbOptional shell su -c "cat /sys/boptt/$Name"
        if ($Attempt.ExitCode -ne 0) {
            throw "GPIO读取失败：$Name；$($Attempt.Output -join ' ')"
        }
        $Rows += "$Name=$(($Attempt.Output -join '').Trim())"
    }
    return $Rows -join "`n"
}

function Invoke-HardStop {
    $Write = Invoke-AdbOptional shell "su -c 'echo 0 > /sys/boptt/dmr_switch'"
    if ($Write.ExitCode -ne 0) {
        throw "紧急关闭写入失败：$($Write.Output -join ' ')"
    }
    $Read = Invoke-AdbOptional shell su -c 'cat /sys/boptt/dmr_switch'
    $Value = ($Read.Output -join '').Trim()
    if ($Read.ExitCode -ne 0 -or $Value -ne '0') {
        throw "紧急关闭回读失败：exit=$($Read.ExitCode)，value=$Value"
    }
    return "write_exit=$($Write.ExitCode)`nread_exit=$($Read.ExitCode)`ndmr_switch=$Value"
}

function Assert-GpioBaseline {
    param([string]$State)
    foreach ($Expected in @('dmr_switch=1','audio_switch=0','pa_enable=0','ptt_d=in','freq_section=4')) {
        if ($State -notmatch "(?m)^$([regex]::Escape($Expected))$") {
            throw "GPIO基线不匹配：缺少$Expected；实际=$State"
        }
    }
}

function Get-WifiState {
    $Enabled = ((Invoke-Adb shell settings get global wifi_on) -join '').Trim()
    $Link = ((Invoke-Adb shell cat /sys/class/net/wlan0/operstate) -join '').Trim()
    return "wifi_on=$Enabled`noperstate=$Link"
}

function Assert-WifiBaseline {
    param([string]$State)
    if ($State -notmatch '(?m)^wifi_on=1$') {
        throw "Wi-Fi基线不匹配：要求wifi_on=1，实际=$State"
    }
}

function Test-ResultAllowsProductionRestore {
    param([string]$ResultText)
    return $ResultText -match '(?m)^sram_restored=true\r?$' -and
        $ResultText -match '(?m)^debug_print_restored=true\r?$' -and
        $ResultText -match '(?m)^privacy_restored=true\r?$' -and
        $ResultText -match '(?m)^power_save_mutation_active=false\r?$' -and
        $ResultText -match '(?m)^power_save_restored=true\r?$' -and
        $ResultText -match '(?m)^text_recovery_confirmed=true\r?$' -and
        $ResultText -match '(?m)^recovery_error_count=0\r?$' -and
        $ResultText -match '(?m)^reboot_required=false\r?$' -and
        $ResultText -match '(?m)^rf_may_be_active=false\r?$'
}

function Test-ActivityStartAccepted {
    param([string]$StartText)
    return $StartText -match '(?m)^Status:\s+ok\r?$' -and
        $StartText -match '(?m)^Activity:\s+(?:net\.elfradio\.h13dmrtx/\.MainActivity|net\.elfradio\.h13dmrtx/net\.elfradio\.h13dmrtx\.MainActivity)\r?$' -and
        $StartText -notmatch '(?m)^Error(?:\s+type\s+\d+)?(?::|$)'
}

function Assert-ModeResult {
    param([string]$ResultText)
    $Expected = switch ($Mode) {
        'clear_only' { @{ Mode='clear_channel_only_no_rf'; Setup=0; Vlc=0; Data=0 } }
        'setup0_only' { @{ Mode='setup0_only_no_rf'; Setup=1; Vlc=0; Data=0 } }
        'no_rf' { @{ Mode='session_prepare_no_rf'; Setup=5; Vlc=5; Data=0 } }
        'deadline_handshake_no_rf' { @{
            Mode='deadline_handshake_no_rf'; Setup=5; Vlc=5; Data=0 } }
        'realtime_relay_one_data36_no_rf' { @{
            Mode='realtime_relay_one_data36_no_rf'; Setup=5; Vlc=5; Data=1 } }
        'realtime_relay_fixed_asset_one_data36_no_rf' { @{
            Mode='realtime_relay_fixed_asset_one_data36_no_rf'; Setup=5; Vlc=5; Data=1 } }
        'realtime_relay_fixed_asset_three_data36_no_rf' { @{
            Mode='realtime_relay_fixed_asset_three_data36_no_rf'; Setup=5; Vlc=5; Data=3 } }
        'realtime_relay_fixed_asset_five_data36_no_rf' { @{
            Mode='realtime_relay_fixed_asset_five_data36_no_rf'; Setup=5; Vlc=5; Data=5 } }
        'realtime_relay_fixed_asset_twentyfive_data36_no_rf' { @{
            Mode='realtime_relay_fixed_asset_twentyfive_data36_no_rf'; Setup=5; Vlc=5; Data=25 } }
        'realtime_relay_software_49bit_one_data36_no_rf' { @{
            Mode='realtime_relay_software_49bit_one_data36_no_rf'; Setup=5; Vlc=5; Data=1 } }
        'realtime_relay_software_49bit_tone800_one_data36_no_rf' { @{
            Mode='realtime_relay_software_49bit_tone800_one_data36_no_rf'; Setup=5; Vlc=5; Data=1 } }
        'realtime_relay_software_49bit_morse_one_data36_no_rf' { @{
            Mode='realtime_relay_software_49bit_morse_one_data36_no_rf'; Setup=5; Vlc=5; Data=1 } }
        'realtime_relay_encode_dmr_silence_one_data36_no_rf' { @{
            Mode='realtime_relay_encode_dmr_silence_one_data36_no_rf'; Setup=5; Vlc=5; Data=1 } }
        'realtime_relay_encode_dmr_tone800_one_data36_no_rf' { @{
            Mode='realtime_relay_encode_dmr_tone800_one_data36_no_rf'; Setup=5; Vlc=5; Data=1 } }
        'realtime_relay_encode_dmr_tone800_three_data36_no_rf' { @{
            Mode='realtime_relay_encode_dmr_tone800_three_data36_no_rf'; Setup=5; Vlc=5; Data=3 } }
        'realtime_relay_encode_dmr_tone800_five_data36_no_rf' { @{
            Mode='realtime_relay_encode_dmr_tone800_five_data36_no_rf'; Setup=5; Vlc=5; Data=5 } }
        'realtime_relay_encode_dmr_morse_unique_one_data36_no_rf' { @{
            Mode='realtime_relay_encode_dmr_morse_unique_one_data36_no_rf'; Setup=5; Vlc=5; Data=1 } }
        'realtime_relay_encode_dmr_morse_unique_three_data36_no_rf' { @{
            Mode='realtime_relay_encode_dmr_morse_unique_three_data36_no_rf'; Setup=5; Vlc=5; Data=3 } }
        'realtime_relay_encode_dmr_morse_unique_five_data36_no_rf' { @{
            Mode='realtime_relay_encode_dmr_morse_unique_five_data36_no_rf'; Setup=5; Vlc=5; Data=5 } }
        'realtime_relay_software_privacy_morse_twentyfive_no_rf' { @{
            Mode='realtime_relay_software_privacy_morse_twentyfive_no_rf'; Setup=5; Vlc=5; Data=25 } }
        'realtime_relay_software_privacy_triple_sos_no_rf' { @{
            Mode='realtime_relay_software_privacy_triple_sos_no_rf'; Setup=5; Vlc=5; Data=78 } }
        'realtime_relay_software_privacy_triple_sos_low_power_rf' { @{
            Mode='realtime_relay_software_privacy_triple_sos_low_power_rf'; Setup=5; Vlc=5; Data=78 } }
        'realtime_relay_encode_dmr_morse_unique_five_low_power_rf' { @{
            Mode='realtime_relay_encode_dmr_morse_unique_five_low_power_rf'; Setup=5; Vlc=5; Data=5 } }
        'low_power_rf' { @{ Mode='morse_low_power_rf'; Setup=5; Vlc=5; Data=26 } }
    }
    $RetryCount = if ($ResultText -match '(?m)^setup0_retry_used=true\r?$') { 1 } else { 0 }
    $CleanupCount = if ($Mode -in @(
            'realtime_relay_encode_dmr_morse_unique_five_low_power_rf',
            'realtime_relay_software_privacy_triple_sos_low_power_rf')) { 2 } else { 0 }
    $TerminationCount = if ($Expected.Data -gt 0) { 1 } else { 0 }
    $ExpectedControlWrites = $Expected.Setup + $Expected.Vlc + $TerminationCount + $CleanupCount + $RetryCount
    foreach ($Pair in @(
        @{ Name='mode'; Value=$Expected.Mode },
        @{ Name='setup_acks'; Value=[string]$Expected.Setup },
        @{ Name='vlc_acks'; Value=[string]$Expected.Vlc },
        @{ Name='termination_acks'; Value=[string]$TerminationCount },
        @{ Name='cleanup_acks'; Value=[string]$CleanupCount },
        @{ Name='data36_written'; Value=[string]$Expected.Data },
        @{ Name='control_write_attempts'; Value=[string]$ExpectedControlWrites },
        @{ Name='control_flush_completed'; Value=[string]$ExpectedControlWrites },
        @{ Name='data36_write_attempts'; Value=[string]$Expected.Data },
        @{ Name='data36_flush_completed'; Value=[string]$Expected.Data })) {
        if ($ResultText -notmatch "(?m)^$([regex]::Escape($Pair.Name))=$([regex]::Escape($Pair.Value))\r?$") {
            throw "模式语义门失败：要求$($Pair.Name)=$($Pair.Value)"
        }
    }
    if ($Mode -eq 'realtime_relay_fixed_asset_one_data36_no_rf' -and
            $ResultText -notmatch '(?m)^relay_payload_source=fixed_asset\r?$') {
        throw '模式语义门失败：要求relay_payload_source=fixed_asset'
    }
    if ($Mode -eq 'realtime_relay_fixed_asset_three_data36_no_rf' -and
            $ResultText -notmatch '(?m)^relay_payload_source=fixed_asset_three\r?$') {
        throw '模式语义门失败：要求relay_payload_source=fixed_asset_three'
    }
    if ($Mode -eq 'realtime_relay_fixed_asset_three_data36_no_rf' -and
            $ResultText -notmatch '(?m)^relay_units_written=3\r?$') {
        throw '模式语义门失败：要求relay_units_written=3'
    }
    if ($Mode -eq 'realtime_relay_fixed_asset_three_data36_no_rf' -and
            $ResultText -notmatch '(?m)^relay_credits_consumed=3\r?$') {
        throw '模式语义门失败：要求relay_credits_consumed=3'
    }
    if ($Mode -eq 'realtime_relay_fixed_asset_five_data36_no_rf' -and
            $ResultText -notmatch '(?m)^relay_payload_source=fixed_asset_five\r?$') {
        throw '模式语义门失败：要求relay_payload_source=fixed_asset_five'
    }
    if ($Mode -eq 'realtime_relay_fixed_asset_five_data36_no_rf' -and
            $ResultText -notmatch '(?m)^relay_units_written=5\r?$') {
        throw '模式语义门失败：要求relay_units_written=5'
    }
    if ($Mode -eq 'realtime_relay_fixed_asset_five_data36_no_rf' -and
            $ResultText -notmatch '(?m)^relay_credits_consumed=5\r?$') {
        throw '模式语义门失败：要求relay_credits_consumed=5'
    }
    if ($Mode -eq 'realtime_relay_fixed_asset_twentyfive_data36_no_rf' -and
            $ResultText -notmatch '(?m)^relay_payload_source=fixed_asset_twentyfive\r?$') {
        throw '模式语义门失败：要求relay_payload_source=fixed_asset_twentyfive'
    }
    if ($Mode -eq 'realtime_relay_fixed_asset_twentyfive_data36_no_rf' -and
            $ResultText -notmatch '(?m)^relay_units_written=25\r?$') {
        throw '模式语义门失败：要求relay_units_written=25'
    }
    if ($Mode -eq 'realtime_relay_fixed_asset_twentyfive_data36_no_rf' -and
            $ResultText -notmatch '(?m)^relay_credits_consumed=25\r?$') {
        throw '模式语义门失败：要求relay_credits_consumed=25'
    }
    if ($Mode -eq 'realtime_relay_one_data36_no_rf' -and
            $ResultText -notmatch '(?m)^relay_payload_source=realtime_stream\r?$') {
        throw '模式语义门失败：要求relay_payload_source=realtime_stream'
    }
    if ($Mode -eq 'realtime_relay_software_49bit_one_data36_no_rf' -and
            $ResultText -notmatch '(?m)^relay_payload_source=software_49bit\r?$') {
        throw '模式语义门失败：要求relay_payload_source=software_49bit'
    }
    if ($Mode -eq 'realtime_relay_software_49bit_tone800_one_data36_no_rf' -and
            $ResultText -notmatch '(?m)^relay_payload_source=software_49bit_tone800\r?$') {
        throw '模式语义门失败：要求relay_payload_source=software_49bit_tone800'
    }
    if ($Mode -eq 'realtime_relay_software_49bit_morse_one_data36_no_rf' -and
            $ResultText -notmatch '(?m)^relay_payload_source=software_49bit_morse\r?$') {
        throw '模式语义门失败：要求relay_payload_source=software_49bit_morse'
    }
    if ($Mode -eq 'realtime_relay_encode_dmr_silence_one_data36_no_rf' -and
            $ResultText -notmatch '(?m)^relay_payload_source=encode_dmr_silence\r?$') {
        throw '模式语义门失败：要求relay_payload_source=encode_dmr_silence'
    }
    if ($Mode -eq 'realtime_relay_encode_dmr_tone800_one_data36_no_rf' -and
            $ResultText -notmatch '(?m)^relay_payload_source=encode_dmr_tone800\r?$') {
        throw '模式语义门失败：要求relay_payload_source=encode_dmr_tone800'
    }
    if ($Mode -eq 'realtime_relay_encode_dmr_tone800_three_data36_no_rf' -and
            $ResultText -notmatch '(?m)^relay_payload_source=encode_dmr_tone800_three\r?$') {
        throw '模式语义门失败：要求relay_payload_source=encode_dmr_tone800_three'
    }
    if ($Mode -eq 'realtime_relay_encode_dmr_tone800_three_data36_no_rf' -and
            $ResultText -notmatch '(?m)^relay_units_written=3\r?$') {
        throw '模式语义门失败：要求relay_units_written=3'
    }
    if ($Mode -eq 'realtime_relay_encode_dmr_tone800_three_data36_no_rf' -and
            $ResultText -notmatch '(?m)^relay_credits_consumed=3\r?$') {
        throw '模式语义门失败：要求relay_credits_consumed=3'
    }
    if ($Mode -eq 'realtime_relay_encode_dmr_tone800_five_data36_no_rf' -and
            $ResultText -notmatch '(?m)^relay_payload_source=encode_dmr_tone800_five\r?$') {
        throw '模式语义门失败：要求relay_payload_source=encode_dmr_tone800_five'
    }
    if ($Mode -eq 'realtime_relay_encode_dmr_tone800_five_data36_no_rf' -and
            $ResultText -notmatch '(?m)^relay_units_written=5\r?$') {
        throw '模式语义门失败：要求relay_units_written=5'
    }
    if ($Mode -eq 'realtime_relay_encode_dmr_tone800_five_data36_no_rf' -and
            $ResultText -notmatch '(?m)^relay_credits_consumed=5\r?$') {
        throw '模式语义门失败：要求relay_credits_consumed=5'
    }
    if ($Mode -eq 'realtime_relay_encode_dmr_morse_unique_one_data36_no_rf' -and
            $ResultText -notmatch '(?m)^relay_payload_source=encode_dmr_morse_unique\r?$') {
        throw '模式语义门失败：要求relay_payload_source=encode_dmr_morse_unique'
    }
    if ($Mode -eq 'realtime_relay_encode_dmr_morse_unique_three_data36_no_rf' -and
            $ResultText -notmatch '(?m)^relay_payload_source=encode_dmr_morse_unique_three\r?$') {
        throw '模式语义门失败：要求relay_payload_source=encode_dmr_morse_unique_three'
    }
    if ($Mode -eq 'realtime_relay_encode_dmr_morse_unique_three_data36_no_rf' -and
            $ResultText -notmatch '(?m)^relay_units_written=3\r?$') {
        throw '模式语义门失败：要求relay_units_written=3'
    }
    if ($Mode -eq 'realtime_relay_encode_dmr_morse_unique_three_data36_no_rf' -and
            $ResultText -notmatch '(?m)^relay_credits_consumed=3\r?$') {
        throw '模式语义门失败：要求relay_credits_consumed=3'
    }
    if ($Mode -in @('realtime_relay_encode_dmr_morse_unique_five_data36_no_rf',
            'realtime_relay_encode_dmr_morse_unique_five_low_power_rf') -and
            $ResultText -notmatch '(?m)^relay_payload_source=encode_dmr_morse_unique_five\r?$') {
        throw '模式语义门失败：要求relay_payload_source=encode_dmr_morse_unique_five'
    }
    if ($Mode -in @('realtime_relay_encode_dmr_morse_unique_five_data36_no_rf',
            'realtime_relay_encode_dmr_morse_unique_five_low_power_rf') -and
            $ResultText -notmatch '(?m)^relay_units_written=5\r?$') {
        throw '模式语义门失败：要求relay_units_written=5'
    }
    if ($Mode -in @('realtime_relay_encode_dmr_morse_unique_five_data36_no_rf',
            'realtime_relay_encode_dmr_morse_unique_five_low_power_rf') -and
            $ResultText -notmatch '(?m)^relay_credits_consumed=5\r?$') {
        throw '模式语义门失败：要求relay_credits_consumed=5'
    }
    if ($Mode -eq 'realtime_relay_software_privacy_morse_twentyfive_no_rf' -and
            $ResultText -notmatch '(?m)^relay_payload_source=software_privacy_morse_channel72\r?$') {
        throw '模式语义门失败：要求软件AMBE与privacy分层载荷来源'
    }
    if ($Mode -eq 'realtime_relay_software_privacy_morse_twentyfive_no_rf' -and
            ($ResultText -notmatch '(?m)^relay_units_written=25\r?$' -or
            $ResultText -notmatch '(?m)^relay_credits_consumed=25\r?$')) {
        throw '模式语义门失败：要求软件AMBE路径写出并消费25单元'
    }
    if ($Mode -in @('realtime_relay_software_privacy_triple_sos_no_rf',
            'realtime_relay_software_privacy_triple_sos_low_power_rf') -and
            $ResultText -notmatch '(?m)^relay_payload_source=software_privacy_triple_sos_channel72\r?$') {
        throw '模式语义门失败：要求三遍SOS软件AMBE与privacy分层载荷来源'
    }
    if ($Mode -in @('realtime_relay_software_privacy_triple_sos_no_rf',
            'realtime_relay_software_privacy_triple_sos_low_power_rf') -and
            ($ResultText -notmatch '(?m)^relay_units_written=78\r?$' -or
            $ResultText -notmatch '(?m)^relay_credits_consumed=78\r?$')) {
        throw '模式语义门失败：要求三遍SOS路径写出并消费78单元'
    }
    $BooleanExpected = switch ($Mode) {
        'clear_only' { @{
            first_bridge_exit_confirmed='false'; second_bridge_exit_confirmed='false';
            rf_prepare_executed='false'; rf_off_confirmed='false';
            device_deadline_arm_requested='false';
            device_deadline_armed='false'; sram_transaction_started='false' } }
        'setup0_only' { @{
            first_bridge_exit_confirmed='true'; second_bridge_exit_confirmed='false';
            rf_prepare_executed='false'; rf_off_confirmed='false';
            device_deadline_arm_requested='false';
            device_deadline_armed='false'; sram_transaction_started='true' } }
        'no_rf' { @{
            first_bridge_exit_confirmed='true'; second_bridge_exit_confirmed='false';
            rf_prepare_executed='false'; rf_off_confirmed='false';
            device_deadline_arm_requested='false';
            device_deadline_armed='false'; sram_transaction_started='true' } }
        'realtime_relay_one_data36_no_rf' { @{
            first_bridge_exit_confirmed='true'; second_bridge_exit_confirmed='false';
            rf_prepare_executed='false'; rf_off_confirmed='false';
            device_deadline_arm_requested='false';
            device_deadline_armed='false'; sram_transaction_started='true' } }
        'realtime_relay_fixed_asset_one_data36_no_rf' { @{
            first_bridge_exit_confirmed='true'; second_bridge_exit_confirmed='false';
            rf_prepare_executed='false'; rf_off_confirmed='false';
            device_deadline_arm_requested='false';
            device_deadline_armed='false'; sram_transaction_started='true' } }
        'realtime_relay_fixed_asset_three_data36_no_rf' { @{
            first_bridge_exit_confirmed='true'; second_bridge_exit_confirmed='false';
            rf_prepare_executed='false'; rf_off_confirmed='false';
            device_deadline_arm_requested='false';
            device_deadline_armed='false'; sram_transaction_started='true' } }
        'realtime_relay_fixed_asset_five_data36_no_rf' { @{
            first_bridge_exit_confirmed='true'; second_bridge_exit_confirmed='false';
            rf_prepare_executed='false'; rf_off_confirmed='false';
            device_deadline_arm_requested='false';
            device_deadline_armed='false'; sram_transaction_started='true' } }
        'realtime_relay_fixed_asset_twentyfive_data36_no_rf' { @{
            first_bridge_exit_confirmed='true'; second_bridge_exit_confirmed='false';
            rf_prepare_executed='false'; rf_off_confirmed='false';
            device_deadline_arm_requested='false';
            device_deadline_armed='false'; sram_transaction_started='true' } }
        'realtime_relay_software_49bit_one_data36_no_rf' { @{
            first_bridge_exit_confirmed='true'; second_bridge_exit_confirmed='false';
            rf_prepare_executed='false'; rf_off_confirmed='false';
            device_deadline_arm_requested='false';
            device_deadline_armed='false'; sram_transaction_started='true' } }
        'realtime_relay_software_49bit_tone800_one_data36_no_rf' { @{
            first_bridge_exit_confirmed='true'; second_bridge_exit_confirmed='false';
            rf_prepare_executed='false'; rf_off_confirmed='false';
            device_deadline_arm_requested='false';
            device_deadline_armed='false'; sram_transaction_started='true' } }
        'realtime_relay_software_49bit_morse_one_data36_no_rf' { @{
            first_bridge_exit_confirmed='true'; second_bridge_exit_confirmed='false';
            rf_prepare_executed='false'; rf_off_confirmed='false';
            device_deadline_arm_requested='false';
            device_deadline_armed='false'; sram_transaction_started='true' } }
        'realtime_relay_encode_dmr_silence_one_data36_no_rf' { @{
            first_bridge_exit_confirmed='true'; second_bridge_exit_confirmed='false';
            rf_prepare_executed='false'; rf_off_confirmed='false';
            device_deadline_arm_requested='false';
            device_deadline_armed='false'; sram_transaction_started='true' } }
        'realtime_relay_encode_dmr_tone800_one_data36_no_rf' { @{
            first_bridge_exit_confirmed='true'; second_bridge_exit_confirmed='false';
            rf_prepare_executed='false'; rf_off_confirmed='false';
            device_deadline_arm_requested='false';
            device_deadline_armed='false'; sram_transaction_started='true' } }
        'realtime_relay_encode_dmr_tone800_three_data36_no_rf' { @{
            first_bridge_exit_confirmed='true'; second_bridge_exit_confirmed='false';
            rf_prepare_executed='false'; rf_off_confirmed='false';
            device_deadline_arm_requested='false';
            device_deadline_armed='false'; sram_transaction_started='true' } }
        'realtime_relay_encode_dmr_tone800_five_data36_no_rf' { @{
            first_bridge_exit_confirmed='true'; second_bridge_exit_confirmed='false';
            rf_prepare_executed='false'; rf_off_confirmed='false';
            device_deadline_arm_requested='false';
            device_deadline_armed='false'; sram_transaction_started='true' } }
        'realtime_relay_encode_dmr_morse_unique_one_data36_no_rf' { @{
            first_bridge_exit_confirmed='true'; second_bridge_exit_confirmed='false';
            rf_prepare_executed='false'; rf_off_confirmed='false';
            device_deadline_arm_requested='false';
            device_deadline_armed='false'; sram_transaction_started='true' } }
        'realtime_relay_encode_dmr_morse_unique_three_data36_no_rf' { @{
            first_bridge_exit_confirmed='true'; second_bridge_exit_confirmed='false';
            rf_prepare_executed='false'; rf_off_confirmed='false';
            device_deadline_arm_requested='false';
            device_deadline_armed='false'; sram_transaction_started='true' } }
        'realtime_relay_encode_dmr_morse_unique_five_data36_no_rf' { @{
            first_bridge_exit_confirmed='true'; second_bridge_exit_confirmed='false';
            rf_prepare_executed='false'; rf_off_confirmed='false';
            device_deadline_arm_requested='false';
            device_deadline_armed='false'; sram_transaction_started='true' } }
        'realtime_relay_software_privacy_morse_twentyfive_no_rf' { @{
            first_bridge_exit_confirmed='true'; second_bridge_exit_confirmed='false';
            rf_prepare_executed='false'; rf_off_confirmed='false';
            device_deadline_arm_requested='false';
            device_deadline_armed='false'; sram_transaction_started='true' } }
        'realtime_relay_software_privacy_triple_sos_no_rf' { @{
            first_bridge_exit_confirmed='true'; second_bridge_exit_confirmed='false';
            rf_prepare_executed='false'; rf_off_confirmed='false';
            device_deadline_arm_requested='false';
            device_deadline_armed='false'; sram_transaction_started='true' } }
        'realtime_relay_software_privacy_triple_sos_low_power_rf' { @{
            first_bridge_exit_confirmed='true'; second_bridge_exit_confirmed='false';
            rf_prepare_executed='true'; rf_off_confirmed='true';
            device_deadline_arm_requested='true';
            device_deadline_armed='true'; sram_transaction_started='true' } }
        'realtime_relay_encode_dmr_morse_unique_five_low_power_rf' { @{
            first_bridge_exit_confirmed='true'; second_bridge_exit_confirmed='false';
            rf_prepare_executed='true'; rf_off_confirmed='true';
            device_deadline_arm_requested='false';
            device_deadline_armed='false'; sram_transaction_started='true' } }
        'deadline_handshake_no_rf' { @{
            first_bridge_exit_confirmed='true'; second_bridge_exit_confirmed='false';
            rf_prepare_executed='false'; rf_off_confirmed='false';
            device_deadline_arm_requested='true';
            device_deadline_armed='true'; sram_transaction_started='true' } }
        'low_power_rf' { @{
            first_bridge_exit_confirmed='true'; second_bridge_exit_confirmed='true';
            rf_prepare_executed='true'; rf_off_confirmed='true';
            device_deadline_arm_requested='true';
            device_deadline_armed='true'; sram_transaction_started='true' } }
    }
    foreach ($Name in $BooleanExpected.Keys) {
        $Value = [string]$BooleanExpected[$Name]
        if ($ResultText -notmatch "(?m)^$([regex]::Escape($Name))=$Value\r?$") {
            throw "模式布尔语义门失败：要求$Name=$Value"
        }
    }
    $ExpectedPowerSaveDisabled = if ($Mode -eq 'clear_only') { 'false' } else { 'true' }
    foreach ($Pair in @(
        @{ Name='power_save_disabled'; Value=$ExpectedPowerSaveDisabled },
        @{ Name='power_save_mutation_active'; Value='false' },
        @{ Name='power_save_restored'; Value='true' })) {
        if ($ResultText -notmatch "(?m)^$([regex]::Escape($Pair.Name))=$($Pair.Value)\r?$") {
            throw "省电恢复语义门失败：要求$($Pair.Name)=$($Pair.Value)"
        }
    }
    if ($Mode -eq 'clear_only') {
        foreach ($Pair in @(
            @{ Name='power_save_entry_value'; Value='-1' },
            @{ Name='power_save_restored_value'; Value='-1' })) {
            if ($ResultText -notmatch "(?m)^$([regex]::Escape($Pair.Name))=$($Pair.Value)\r?$") {
                throw "省电原像语义门失败：要求$($Pair.Name)=$($Pair.Value)"
            }
        }
    } else {
        if ($ResultText -notmatch '(?m)^power_save_entry_value=([01])\r?$') {
            throw '省电原像语义门失败：入口值不是0或1。'
        }
        $EntryValue = $Matches[1]
        if ($ResultText -notmatch "(?m)^power_save_restored_value=$EntryValue\r?$") {
            throw '省电原像语义门失败：恢复值与入口值不一致。'
        }
    }
}

function Assert-ClearPrecheck {
    param([string]$Directory, [string]$ApkHash, [string]$Fingerprint)
    if (-not (Test-Path -LiteralPath $Directory -PathType Container)) {
        throw '缺少同APK的新鲜明文频道独立预检捕获目录。'
    }
    $Item = Get-Item -LiteralPath $Directory
    if (((Get-Date) - $Item.LastWriteTime).TotalMinutes -gt 30) {
        throw '明文频道独立预检超过30分钟，必须重新执行。'
    }
    $AtomicPath = Join-Path $Directory 'atomic_result_host.txt'
    $HashPath = Join-Path $Directory 'apk_sha256_local.txt'
    $FingerprintPath = Join-Path $Directory 'build_fingerprint_before.txt'
    $ManifestProof = Join-Path $Directory 'device_manifest_verification.txt'
    foreach ($Path in @($AtomicPath,$HashPath,$FingerprintPath,$ManifestProof)) {
        if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
            throw "明文频道预检证据缺失：$Path"
        }
    }
    if ((Get-Content -LiteralPath $ManifestProof -Raw) -notmatch '^通过：') {
        throw '明文频道预检设备证据清单没有通过逐项核验。'
    }
    $Atomic = Get-Content -LiteralPath $AtomicPath -Raw
    if ($Atomic -notmatch '(?m)^result=PASS\r?$' -or
        $Atomic -notmatch '(?m)^mode=clear_channel_only_no_rf\r?$' -or
        -not (Test-ResultAllowsProductionRestore $Atomic)) {
        throw '明文频道预检原子结果未通过严格恢复门。'
    }
    $PrecheckApkHash = (Get-Content -LiteralPath $HashPath -Raw).Trim().ToUpperInvariant()
    if ($PrecheckApkHash -ne $ApkHash.ToUpperInvariant()) {
        throw '明文频道预检与当前APK哈希不一致。'
    }
    if ((Get-Content -LiteralPath $FingerprintPath -Raw).Trim() -ne $Fingerprint) {
        throw '明文频道预检与当前设备构建指纹不一致。'
    }
    $ResolvedPrecheck = (Resolve-Path -LiteralPath $Directory).Path
    $ReferenceRoot = Join-Path $Project 'research\h13_radio\captures'
    $PriorReferences = @(Get-ChildItem -LiteralPath $ReferenceRoot -File `
        -Filter clear_channel_precheck_reference.txt -Recurse -ErrorAction SilentlyContinue |
        Where-Object {
            (Get-Content -LiteralPath $_.FullName -Raw) -match
                "(?m)^capture=$([regex]::Escape($ResolvedPrecheck))\r?$"
        })
    if ($PriorReferences.Count -ne 0) {
        throw "明文频道预检已经被业务尝试消费，禁止复用：$($PriorReferences[0].FullName)"
    }
}

function Invoke-HostContractSelfTest {
    $OriginalMode = $script:Mode
    try {
        $Cases = @(
            @{ Mode='clear_only'; DeviceMode='clear_channel_only_no_rf'; Setup=0; Vlc=0; Data=0;
                First='false'; Second='false'; Prep='false'; Off='false'; Arm='false'; Deadline='false'; Sram='false' },
            @{ Mode='setup0_only'; DeviceMode='setup0_only_no_rf'; Setup=1; Vlc=0; Data=0;
                First='true'; Second='false'; Prep='false'; Off='false'; Arm='false'; Deadline='false'; Sram='true' },
            @{ Mode='no_rf'; DeviceMode='session_prepare_no_rf'; Setup=5; Vlc=5; Data=0;
                First='true'; Second='false'; Prep='false'; Off='false'; Arm='false'; Deadline='false'; Sram='true' },
            @{ Mode='realtime_relay_one_data36_no_rf'; DeviceMode='realtime_relay_one_data36_no_rf'; Setup=5; Vlc=5; Data=1;
                First='true'; Second='false'; Prep='false'; Off='false'; Arm='false'; Deadline='false'; Sram='true' },
            @{ Mode='realtime_relay_fixed_asset_one_data36_no_rf'; DeviceMode='realtime_relay_fixed_asset_one_data36_no_rf'; Setup=5; Vlc=5; Data=1;
                First='true'; Second='false'; Prep='false'; Off='false'; Arm='false'; Deadline='false'; Sram='true' },
            @{ Mode='realtime_relay_fixed_asset_three_data36_no_rf'; DeviceMode='realtime_relay_fixed_asset_three_data36_no_rf'; Setup=5; Vlc=5; Data=3;
                First='true'; Second='false'; Prep='false'; Off='false'; Arm='false'; Deadline='false'; Sram='true' },
            @{ Mode='realtime_relay_fixed_asset_five_data36_no_rf'; DeviceMode='realtime_relay_fixed_asset_five_data36_no_rf'; Setup=5; Vlc=5; Data=5;
                First='true'; Second='false'; Prep='false'; Off='false'; Arm='false'; Deadline='false'; Sram='true' },
            @{ Mode='realtime_relay_fixed_asset_twentyfive_data36_no_rf'; DeviceMode='realtime_relay_fixed_asset_twentyfive_data36_no_rf'; Setup=5; Vlc=5; Data=25;
                First='true'; Second='false'; Prep='false'; Off='false'; Arm='false'; Deadline='false'; Sram='true' },
            @{ Mode='realtime_relay_software_49bit_one_data36_no_rf'; DeviceMode='realtime_relay_software_49bit_one_data36_no_rf'; Setup=5; Vlc=5; Data=1;
                First='true'; Second='false'; Prep='false'; Off='false'; Arm='false'; Deadline='false'; Sram='true' },
            @{ Mode='realtime_relay_software_49bit_tone800_one_data36_no_rf'; DeviceMode='realtime_relay_software_49bit_tone800_one_data36_no_rf'; Setup=5; Vlc=5; Data=1;
                First='true'; Second='false'; Prep='false'; Off='false'; Arm='false'; Deadline='false'; Sram='true' },
            @{ Mode='realtime_relay_software_49bit_morse_one_data36_no_rf'; DeviceMode='realtime_relay_software_49bit_morse_one_data36_no_rf'; Setup=5; Vlc=5; Data=1;
                First='true'; Second='false'; Prep='false'; Off='false'; Arm='false'; Deadline='false'; Sram='true' },
            @{ Mode='realtime_relay_encode_dmr_silence_one_data36_no_rf'; DeviceMode='realtime_relay_encode_dmr_silence_one_data36_no_rf'; Setup=5; Vlc=5; Data=1;
                First='true'; Second='false'; Prep='false'; Off='false'; Arm='false'; Deadline='false'; Sram='true' },
            @{ Mode='realtime_relay_encode_dmr_tone800_one_data36_no_rf'; DeviceMode='realtime_relay_encode_dmr_tone800_one_data36_no_rf'; Setup=5; Vlc=5; Data=1;
                First='true'; Second='false'; Prep='false'; Off='false'; Arm='false'; Deadline='false'; Sram='true' },
            @{ Mode='realtime_relay_encode_dmr_tone800_three_data36_no_rf'; DeviceMode='realtime_relay_encode_dmr_tone800_three_data36_no_rf'; Setup=5; Vlc=5; Data=3;
                First='true'; Second='false'; Prep='false'; Off='false'; Arm='false'; Deadline='false'; Sram='true' },
            @{ Mode='realtime_relay_encode_dmr_tone800_five_data36_no_rf'; DeviceMode='realtime_relay_encode_dmr_tone800_five_data36_no_rf'; Setup=5; Vlc=5; Data=5;
                First='true'; Second='false'; Prep='false'; Off='false'; Arm='false'; Deadline='false'; Sram='true' },
            @{ Mode='realtime_relay_encode_dmr_morse_unique_one_data36_no_rf'; DeviceMode='realtime_relay_encode_dmr_morse_unique_one_data36_no_rf'; Setup=5; Vlc=5; Data=1;
                First='true'; Second='false'; Prep='false'; Off='false'; Arm='false'; Deadline='false'; Sram='true' },
            @{ Mode='realtime_relay_encode_dmr_morse_unique_three_data36_no_rf'; DeviceMode='realtime_relay_encode_dmr_morse_unique_three_data36_no_rf'; Setup=5; Vlc=5; Data=3;
                First='true'; Second='false'; Prep='false'; Off='false'; Arm='false'; Deadline='false'; Sram='true' },
            @{ Mode='realtime_relay_encode_dmr_morse_unique_five_data36_no_rf'; DeviceMode='realtime_relay_encode_dmr_morse_unique_five_data36_no_rf'; Setup=5; Vlc=5; Data=5;
                First='true'; Second='false'; Prep='false'; Off='false'; Arm='false'; Deadline='false'; Sram='true' },
            @{ Mode='realtime_relay_software_privacy_morse_twentyfive_no_rf'; DeviceMode='realtime_relay_software_privacy_morse_twentyfive_no_rf'; Setup=5; Vlc=5; Data=25;
                First='true'; Second='false'; Prep='false'; Off='false'; Arm='false'; Deadline='false'; Sram='true' },
            @{ Mode='realtime_relay_software_privacy_triple_sos_no_rf'; DeviceMode='realtime_relay_software_privacy_triple_sos_no_rf'; Setup=5; Vlc=5; Data=78;
                First='true'; Second='false'; Prep='false'; Off='false'; Arm='false'; Deadline='false'; Sram='true' },
            @{ Mode='deadline_handshake_no_rf'; DeviceMode='deadline_handshake_no_rf'; Setup=5; Vlc=5; Data=0;
                First='true'; Second='false'; Prep='false'; Off='false'; Arm='true'; Deadline='true'; Sram='true' })
        foreach ($Case in $Cases) {
            $script:Mode = $Case.Mode
            $Cleanup = if ($Case.Mode -in @(
                    'realtime_relay_encode_dmr_morse_unique_five_low_power_rf',
                    'realtime_relay_software_privacy_triple_sos_low_power_rf')) { 2 } else { 0 }
            $Termination = if ($Case.Data -gt 0) { 1 } else { 0 }
            $Control = $Case.Setup + $Case.Vlc + $Termination + $Cleanup
            $PayloadSource = if ($Case.Mode -eq 'realtime_relay_fixed_asset_one_data36_no_rf') {
                'fixed_asset'
            } elseif ($Case.Mode -eq 'realtime_relay_fixed_asset_three_data36_no_rf') {
                'fixed_asset_three'
            } elseif ($Case.Mode -eq 'realtime_relay_fixed_asset_five_data36_no_rf') {
                'fixed_asset_five'
            } elseif ($Case.Mode -eq 'realtime_relay_fixed_asset_twentyfive_data36_no_rf') {
                'fixed_asset_twentyfive'
            } elseif ($Case.Mode -eq 'realtime_relay_one_data36_no_rf') {
                'realtime_stream'
            } elseif ($Case.Mode -eq 'realtime_relay_software_49bit_one_data36_no_rf') {
                'software_49bit'
            } elseif ($Case.Mode -eq 'realtime_relay_software_49bit_tone800_one_data36_no_rf') {
                'software_49bit_tone800'
            } elseif ($Case.Mode -eq 'realtime_relay_software_49bit_morse_one_data36_no_rf') {
                'software_49bit_morse'
            } elseif ($Case.Mode -eq 'realtime_relay_encode_dmr_silence_one_data36_no_rf') {
                'encode_dmr_silence'
            } elseif ($Case.Mode -eq 'realtime_relay_encode_dmr_tone800_one_data36_no_rf') {
                'encode_dmr_tone800'
            } elseif ($Case.Mode -eq 'realtime_relay_encode_dmr_tone800_three_data36_no_rf') {
                'encode_dmr_tone800_three'
            } elseif ($Case.Mode -eq 'realtime_relay_encode_dmr_tone800_five_data36_no_rf') {
                'encode_dmr_tone800_five'
            } elseif ($Case.Mode -eq 'realtime_relay_encode_dmr_morse_unique_one_data36_no_rf') {
                'encode_dmr_morse_unique'
            } elseif ($Case.Mode -eq 'realtime_relay_encode_dmr_morse_unique_three_data36_no_rf') {
                'encode_dmr_morse_unique_three'
            } elseif ($Case.Mode -eq 'realtime_relay_encode_dmr_morse_unique_five_data36_no_rf') {
                'encode_dmr_morse_unique_five'
            } elseif ($Case.Mode -eq 'realtime_relay_encode_dmr_morse_unique_five_low_power_rf') {
                'encode_dmr_morse_unique_five'
            } elseif ($Case.Mode -eq 'realtime_relay_software_privacy_morse_twentyfive_no_rf') {
                'software_privacy_morse_channel72'
            } elseif ($Case.Mode -in @(
                    'realtime_relay_software_privacy_triple_sos_no_rf',
                    'realtime_relay_software_privacy_triple_sos_low_power_rf')) {
                'software_privacy_triple_sos_channel72'
            } else {
                'none'
            }
            $Fixture = @(
                'result=PASS', "mode=$($Case.DeviceMode)",
                "setup_acks=$($Case.Setup)", 'setup0_retry_used=false',
                "vlc_acks=$($Case.Vlc)", "termination_acks=$Termination",
                "cleanup_acks=$Cleanup", "data36_written=$($Case.Data)",
                "control_write_attempts=$Control", "control_flush_completed=$Control",
                "data36_write_attempts=$($Case.Data)", "data36_flush_completed=$($Case.Data)",
                "relay_payload_source=$PayloadSource",
                "relay_units_written=$($Case.Data)",
                "relay_credits_consumed=$($Case.Data)",
                "first_bridge_exit_confirmed=$($Case.First)",
                "second_bridge_exit_confirmed=$($Case.Second)",
                "rf_prepare_executed=$($Case.Prep)", "rf_off_confirmed=$($Case.Off)",
                'rf_may_be_active=false',
                "device_deadline_arm_requested=$($Case.Arm)",
                "device_deadline_armed=$($Case.Deadline)",
                "sram_transaction_started=$($Case.Sram)", 'sram_restored=true',
                'debug_print_restored=true', 'privacy_restored=true',
                "power_save_disabled=$(if ($Case.Mode -eq 'clear_only') { 'false' } else { 'true' })",
                'power_save_mutation_active=false', 'power_save_restored=true',
                "power_save_entry_value=$(if ($Case.Mode -eq 'clear_only') { '-1' } else { '0' })",
                "power_save_restored_value=$(if ($Case.Mode -eq 'clear_only') { '-1' } else { '0' })",
                'text_recovery_confirmed=true', 'recovery_error_count=0',
                'reboot_required=false') -join "`n"
            Assert-ModeResult $Fixture
            $Rejected = $false
            try {
                Assert-ModeResult ($Fixture -replace
                    "vlc_acks=$($Case.Vlc)", 'vlc_acks=999')
            } catch { $Rejected = $true }
            if (-not $Rejected) {
                throw "宿主故障注入未拒绝错误VLC计数：$($Case.Mode)"
            }
            if ($Case.Mode -ne 'clear_only') {
                $Rejected = $false
                try {
                    Assert-ModeResult ($Fixture -replace
                        'power_save_restored_value=0',
                        'power_save_restored_value=1')
                } catch { $Rejected = $true }
                if (-not $Rejected) {
                    throw "宿主故障注入未拒绝省电原像恢复不一致：$($Case.Mode)"
                }
            }
        }
        $GoodShortStart = "Starting: Intent`nStatus: ok`nActivity: net.elfradio.h13dmrtx/.MainActivity`nComplete"
        $GoodFullStart = "Starting: Intent`nStatus: ok`nActivity: net.elfradio.h13dmrtx/net.elfradio.h13dmrtx.MainActivity`nComplete"
        $BadStart = "Starting: Intent`nError type 3`nError: Activity class does not exist."
        $WrongActivityStart = "Starting: Intent`nStatus: ok`nActivity: net.elfradio.other/.MainActivity`nComplete"
        if (-not (Test-ActivityStartAccepted $GoodShortStart) -or
            -not (Test-ActivityStartAccepted $GoodFullStart) -or
            (Test-ActivityStartAccepted $BadStart) -or
            (Test-ActivityStartAccepted $WrongActivityStart)) {
            throw 'Activity启动输出故障注入门失败。'
        }
        $DeadlineSource = Get-Content -LiteralPath $DeadlineHelper -Raw
        foreach ($Required in @(
                "grep -c '^session_restore_complete=true$'",
                'completion_reason=rf_release_after_restore',
                'completion_reason=atomic_result',
                "printf '0\n' > /sys/boptt/dmr_switch")) {
            if (-not $DeadlineSource.Contains($Required)) {
                throw "设备截止器恢复顺序合同缺失：$Required"
            }
        }
        if ($DeadlineSource.Contains('completion_reason=rf_release_attestation')) {
            throw '设备截止器仍接受恢复前RF关闭凭证，存在恢复竞态。'
        }
        if (-not (Test-ResultIsPass "result=PASS`nterminal_phase=DONE_PASS") -or
                (Test-ResultIsPass "result=FAIL`nterminal_phase=DONE_FAIL")) {
            throw '宿主PASS/FAIL证据分流自测失败。'
        }
        $RejectedUnknownResult = $false
        try {
            Test-ResultIsPass 'terminal_phase=DONE_FAIL' | Out-Null
        } catch {
            $RejectedUnknownResult = $true
        }
        if (-not $RejectedUnknownResult) {
            throw '宿主PASS/FAIL证据分流未拒绝缺少result字段的结果。'
        }
        $TempTiming = [IO.Path]::GetTempFileName()
        try {
            [IO.File]::WriteAllText($TempTiming, @"
call_ms=1000
flush_ms=1001
minimum_target_ms=1000
credit_ready_ms=1000
backpressure_ms=0
minimum_interval_late_ms=0
"@)
            $FirstFlush = Assert-RelayTimingFile -Path $TempTiming `
                -Index 0 -PreviousFlush 0
            [IO.File]::WriteAllText($TempTiming, @"
call_ms=1120
flush_ms=1121
minimum_target_ms=1081
credit_ready_ms=1120
backpressure_ms=39
minimum_interval_late_ms=39
"@)
            Assert-RelayTimingFile -Path $TempTiming -Index 1 `
                -PreviousFlush $FirstFlush | Out-Null
            $RejectedTiming = $false
            [IO.File]::WriteAllText($TempTiming,
                ([IO.File]::ReadAllText($TempTiming) -replace
                    'minimum_target_ms=1081', 'minimum_target_ms=1080'))
            try {
                Assert-RelayTimingFile -Path $TempTiming -Index 1 `
                    -PreviousFlush $FirstFlush | Out-Null
            } catch {
                $RejectedTiming = $true
            }
            if (-not $RejectedTiming) {
                throw '宿主时序门未拒绝错误的相邻最低80毫秒目标。'
            }
        } finally {
            Remove-Item -LiteralPath $TempTiming -Force `
                -ErrorAction SilentlyContinue
        }
        $Header = (Get-Content -LiteralPath $PSCommandPath -TotalCount 30) -join "`n"
        if ($Header -match "ValidateSet[\s\S]*low_power_rf") {
            throw 'v0.61公开参数白名单不得包含任何射频模式。'
        }
        $ScriptSource = Get-Content -LiteralPath $PSCommandPath -Raw
        $DeviceMap = [regex]::Match($ScriptSource,
            '\$DeviceMode = switch \(\$Mode\) \{(?<body>[\s\S]*?)\r?\n    \}\r?\n    \$StartArguments')
        if (-not $DeviceMap.Success -or
                $DeviceMap.Groups['body'].Value -match 'low_power_rf') {
            throw 'v0.61设备模式映射不得包含任何射频入口。'
        }
        $TempCredit = [IO.Path]::GetTempFileName()
        try {
            $ExactCredit = [byte[]](0x84,0xA9,0x61,0x00,0x02,0x03,0x01,0x00)
            [IO.File]::WriteAllBytes($TempCredit, $ExactCredit)
            Assert-ExternalDmrCreditFile -Path $TempCredit -Label '自测正确样本'
            $ExactCredit[5] = 0x20
            [IO.File]::WriteAllBytes($TempCredit, $ExactCredit)
            $Rejected = $false
            try {
                Assert-ExternalDmrCreditFile -Path $TempCredit -Label '自测错误样本'
            } catch { $Rejected = $true }
            if (-not $Rejected) {
                throw '宿主故障注入未拒绝packet type被翻转的8字节伪信用。'
            }
            $ExactAck = [byte[]](0x84,0xA9,0x61,0x00,0x01,0x20,0x43,0x00)
            [IO.File]::WriteAllBytes($TempCredit, $ExactAck)
            Assert-VlcAckFile -Path $TempCredit -Label '自测正确VLC确认'
            $ExactAck[6] = 0x42
            [IO.File]::WriteAllBytes($TempCredit, $ExactAck)
            $Rejected = $false
            try {
                Assert-VlcAckFile -Path $TempCredit -Label '自测错误VLC确认'
            } catch { $Rejected = $true }
            if (-not $Rejected) {
                throw '宿主故障注入未拒绝字段被翻转的VLC伪确认。'
            }
            $Termination = [byte[]](
                0x84,0xA9,0x61,0x00,0x0C,0x05,0x43,0x02,0x09,
                0x00,0x00,0x40,0x00,0x00,0x63,0x00,0x00,0x0D)
            [IO.File]::WriteAllBytes($TempCredit, $Termination)
            Assert-ExactBytesFile -Path $TempCredit -Expected $Termination `
                -Label '自测正确终止VLC'
            $Termination[17] = 0x0C
            [IO.File]::WriteAllBytes($TempCredit, $Termination)
            $Rejected = $false
            try {
                $ExpectedTermination = $Termination.Clone()
                $ExpectedTermination[17] = 0x0D
                Assert-ExactBytesFile -Path $TempCredit `
                    -Expected $ExpectedTermination -Label '自测错误终止VLC'
            } catch { $Rejected = $true }
            if (-not $Rejected) {
                throw '宿主故障注入未拒绝被翻转的终止VLC。'
            }
        } finally {
            Remove-Item -LiteralPath $TempCredit -Force -ErrorAction SilentlyContinue
        }
        Write-Host '宿主模式语义门故障注入自测通过。'
    } finally {
        $script:Mode = $OriginalMode
    }
}

function Test-ResultIsPass {
    param([Parameter(Mandatory=$true)][string]$ResultText)
    if ($ResultText -match '(?m)^result=PASS\r?$') { return $true }
    if ($ResultText -match '(?m)^result=FAIL\r?$') { return $false }
    throw '原子结果缺少唯一PASS/FAIL判定字段。'
}

function Assert-PulledDeviceManifest {
    param([Parameter(Mandatory=$true)][string]$ResultText)
    $Manifests = @(Get-ChildItem -LiteralPath (Join-Path $Capture 'device_capture') `
        -Filter artifacts_sha256.tsv -File -Recurse)
    if ($Manifests.Count -ne 1) {
        throw "设备证据清单数量错误：$($Manifests.Count)"
    }
    $Root = $Manifests[0].Directory.FullName
    $Failures = @()
    $Listed = @()
    foreach ($Line in (Get-Content -LiteralPath $Manifests[0].FullName | Select-Object -Skip 1)) {
        if (-not $Line) { continue }
        $Columns = $Line -split "`t"
        if ($Columns.Count -ne 4) {
            $Failures += "清单列数错误：$Line"
            continue
        }
        $Relative = $Columns[0].Replace('\','/')
        if ($Relative -match '(^/|^\.\.?/|/\.\.?/|^[A-Za-z]:)' -or
            $Listed -contains $Relative) {
            $Failures += "清单路径越界或重复：$Relative"
            continue
        }
        $Listed += $Relative
        $Target = Join-Path $Root ($Relative -replace '/', '\')
        if (-not (Test-Path -LiteralPath $Target -PathType Leaf)) {
            $Failures += "缺失：$($Columns[0])"
            continue
        }
        $Item = Get-Item -LiteralPath $Target
        $Hash = (Get-FileHash -LiteralPath $Target -Algorithm SHA256).Hash
        if ($Item.Length -ne [long]$Columns[1] -or
            $Hash -ne $Columns[2].ToUpperInvariant()) {
            $Failures += "长度或哈希错误：$($Columns[0])"
        }
    }
    $Actual = @(Get-ChildItem -LiteralPath $Root -File -Recurse |
        Where-Object { $_.FullName -ne $Manifests[0].FullName } |
        ForEach-Object {
            $_.FullName.Substring($Root.Length + 1).Replace('\','/')
        })
    foreach ($Relative in $Actual) {
        if ($Listed -notcontains $Relative) {
            $Failures += "实际文件未列入设备清单：$Relative"
        }
    }
    Save-Text 'device_manifest_verification.txt' ($(if ($Failures.Count -eq 0) {
        "通过：设备清单逐项核验完成，条目数=$((Get-Content $Manifests[0].FullName).Count - 1)"
    } else { $Failures -join "`n" }))
    if ($Failures.Count -ne 0) {
        throw "设备原始证据清单核验失败：$($Failures.Count)项"
    }
    if (Test-ResultIsPass $ResultText) {
        Assert-FrozenTwentyFiveEvidence -Root $Root
        Assert-TripleSosEvidence -Root $Root
        Save-Text 'device_success_evidence_verification.txt' `
            '通过：设备结果为PASS，成功会话专属内容证据门已经执行。'
    } else {
        Save-Text 'device_success_evidence_verification.txt' `
            '跳过：设备结果为FAIL；设备清单仍已逐项核验，不要求尚未执行的成功会话专属原件。'
    }
}

function Assert-ExternalDmrCreditFile {
    param(
        [Parameter(Mandatory=$true)][string]$Path,
        [Parameter(Mandatory=$true)][string]$Label
    )
    $Expected = [byte[]](0x84,0xA9,0x61,0x00,0x02,0x03,0x01,0x00)
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "$Label 信用原件不存在：$Path"
    }
    $Actual = [IO.File]::ReadAllBytes($Path)
    if ($Actual.Length -ne $Expected.Length) {
        throw "$Label 信用长度错误：expected=8 actual=$($Actual.Length)"
    }
    for ($Index = 0; $Index -lt $Expected.Length; $Index++) {
        if ($Actual[$Index] -ne $Expected[$Index]) {
            throw ("$Label 信用逐字门失败：offset={0} expected={1:X2} actual={2:X2}" -f `
                    $Index, $Expected[$Index], $Actual[$Index])
        }
    }
}

function Assert-VlcAckFile {
    param(
        [Parameter(Mandatory=$true)][string]$Path,
        [Parameter(Mandatory=$true)][string]$Label
    )
    $Actual = [IO.File]::ReadAllBytes($Path)
    if ($Actual.Length -ne 8 -or $Actual[0] -ne 0x84 -or
            $Actual[1] -ne 0xA9 -or $Actual[2] -ne 0x61 -or
            $Actual[3] -ne 0x00 -or $Actual[4] -ne 0x01 -or
            $Actual[5] -notin @(0x05,0x20) -or
            $Actual[6] -ne 0x43 -or $Actual[7] -ne 0x00) {
        $Hex = -join ($Actual | ForEach-Object { $_.ToString('X2') })
        throw "$Label 不是严格VLC确认帧：$Hex"
    }
}

function Assert-ExactBytesFile {
    param(
        [Parameter(Mandatory=$true)][string]$Path,
        [Parameter(Mandatory=$true)][byte[]]$Expected,
        [Parameter(Mandatory=$true)][string]$Label
    )
    $Actual = [IO.File]::ReadAllBytes($Path)
    if ($Actual.Length -ne $Expected.Length) {
        throw "$Label 长度错误：expected=$($Expected.Length) actual=$($Actual.Length)"
    }
    for ($Index = 0; $Index -lt $Expected.Length; $Index++) {
        if ($Actual[$Index] -ne $Expected[$Index]) {
            throw ("$Label 逐字门失败：offset={0} expected={1:X2} actual={2:X2}" -f `
                    $Index, $Expected[$Index], $Actual[$Index])
        }
    }
}

function Assert-RelayTimingFile {
    param(
        [Parameter(Mandatory=$true)][string]$Path,
        [Parameter(Mandatory=$true)][int]$Index,
        [Parameter(Mandatory=$true)][long]$PreviousFlush
    )
    $TimingText = Get-Content -LiteralPath $Path -Raw
    $Values = @{}
    foreach ($Field in @('call_ms','flush_ms','minimum_target_ms',
            'credit_ready_ms','backpressure_ms',
            'minimum_interval_late_ms')) {
        $Match = [regex]::Match($TimingText,
            "(?m)^$([regex]::Escape($Field))=(?<value>-?\d+)\r?$")
        if (-not $Match.Success) {
            throw "连续发送时序缺少字段：$Field"
        }
        $Values[$Field] = [long]$Match.Groups['value'].Value
    }
    if ($Values.flush_ms -lt $Values.call_ms -or
            $Values.call_ms -lt $Values.minimum_target_ms -or
            $Values.call_ms -lt $Values.credit_ready_ms) {
        throw '连续发送时序写出早于信用或最低间隔'
    }
    $ExpectedBackpressure = [Math]::Max(0L,
        $Values.credit_ready_ms - $Values.minimum_target_ms)
    if ($Values.backpressure_ms -ne $ExpectedBackpressure -or
            $Values.minimum_interval_late_ms -ne
            ($Values.call_ms - $Values.minimum_target_ms)) {
        throw '连续发送时序派生字段不一致'
    }
    if ($Index -eq 0) {
        if ($Values.minimum_target_ms -ne $Values.call_ms -or
                $Values.credit_ready_ms -ne $Values.call_ms) {
            throw '连续发送首包时序原点错误'
        }
    } elseif ($Values.minimum_target_ms -ne ($PreviousFlush + 80L)) {
        throw '连续发送相邻写出最低80毫秒合同错误'
    }
    return $Values.flush_ms
}

function Assert-TripleSosEvidence {
    param([string]$Root)
    if ($Mode -notin @('realtime_relay_software_privacy_triple_sos_no_rf',
            'realtime_relay_software_privacy_triple_sos_low_power_rf')) {
        return
    }
    for ($Index = 0; $Index -lt 5; $Index++) {
        $VlcAcks = @(Get-ChildItem -LiteralPath $Root -File |
            Where-Object { $_.Name -match "_hpi_vlc_${Index}_matched\.bin$" })
        if ($VlcAcks.Count -ne 1) {
            throw "VLC$Index 完整确认原件数量错误：$($VlcAcks.Count)"
        }
        Assert-VlcAckFile -Path $VlcAcks[0].FullName -Label "VLC$Index"
    }
    $TerminationRequests = @(Get-ChildItem -LiteralPath $Root -File |
        Where-Object { $_.Name -match '_hpi_termination_vlc_request\.bin$' })
    $TerminationAcks = @(Get-ChildItem -LiteralPath $Root -File |
        Where-Object { $_.Name -match '_hpi_termination_vlc_matched\.bin$' })
    if ($TerminationRequests.Count -ne 1 -or $TerminationAcks.Count -ne 1) {
        throw "终止VLC请求或完整确认原件数量错误：request=$($TerminationRequests.Count) ack=$($TerminationAcks.Count)"
    }
    $ExpectedTermination = [byte[]](
        0x84,0xA9,0x61,0x00,0x0C,0x05,0x43,0x02,0x09,
        0x00,0x00,0x40,0x00,0x00,0x63,0x00,0x00,0x0D)
    Assert-ExactBytesFile -Path $TerminationRequests[0].FullName `
        -Expected $ExpectedTermination -Label '终止VLC请求'
    Assert-VlcAckFile -Path $TerminationAcks[0].FullName -Label '终止VLC确认'
    $Pipeline = @(Get-ChildItem -LiteralPath $Root -File |
        Where-Object { $_.Name -match '_ambe_software_morse_pipeline\.bin$' })
    if ($Pipeline.Count -ne 1) {
        throw "三遍SOS流水线证据文件数错误：$($Pipeline.Count)"
    }
    $PipelineText = Get-Content -LiteralPath $Pipeline[0].FullName -Raw
    foreach ($ExpectedLine in @('frames=312','data36_units=78',
            'sos_repetitions=3','audio_duration_ms=6240',
            'software_encoder=project_mbelib_ambe',
            'software_decoder=project_mbelib',
            'h13_hardware_ambe_used=false','decode_error_sum=0',
            'channel_hamming_sum=0')) {
        if ($PipelineText -notmatch "(?m)^$([regex]::Escape($ExpectedLine))`r?$") {
            throw "三遍SOS流水线缺少严格字段：$ExpectedLine"
        }
    }
    $Lengths = @{
        '_ambe_software_morse_input_pcm_sent_s16le\.bin$' = 99840
        '_ambe_software_morse_actual_channel72\.bin$' = 2808
        '_ambe_software_morse_clear_channel72_sent\.bin$' = 2808
        '_ambe_software_morse_channel72\.bin$' = 2808
        '_ambe_software_morse_decoded_pcm_s16le\.bin$' = 99840
    }
    foreach ($Pattern in $Lengths.Keys) {
        $Files = @(Get-ChildItem -LiteralPath $Root -File |
            Where-Object { $_.Name -match $Pattern })
        if ($Files.Count -ne 1 -or $Files[0].Length -ne $Lengths[$Pattern]) {
            throw "三遍SOS原件长度错误：pattern=$Pattern count=$($Files.Count)"
        }
    }
    $Segments = @(Get-ChildItem -LiteralPath $Root -File |
        Where-Object { $_.Name -match '_ambe_software_morse_segment_[123]_metrics\.bin$' })
    if ($Segments.Count -ne 3) {
        throw "三遍SOS分段识别证据数量错误：$($Segments.Count)"
    }
    $PreviousFlush = 0L
    for ($Index = 0; $Index -lt 78; $Index++) {
        $Suffix = '{0:d2}' -f $Index
        $Requests = @(Get-ChildItem -LiteralPath $Root -File |
            Where-Object { $_.Name -match "_data36_relay_request_$Suffix\.bin$" })
        if ($Requests.Count -ne 1 -or $Requests[0].Length -ne 44) {
            throw "三遍SOS请求$Suffix 数量或长度错误"
        }
        $Bytes = [IO.File]::ReadAllBytes($Requests[0].FullName)
        $Header = -join ($Bytes[0..7] | ForEach-Object { $_.ToString('x2') })
        if ($Header -ne '84a9610026030124') {
            throw "三遍SOS请求$Suffix 头部错误：$Header"
        }
        $Credits = @(Get-ChildItem -LiteralPath $Root -File |
            Where-Object { $_.Name -match "_relay_credit_raw_$Suffix\.bin$" })
        if ($Credits.Count -ne 1 -or $Credits[0].Length -ne 8) {
            throw "三遍SOS信用$Suffix 数量或长度错误"
        }
        Assert-ExternalDmrCreditFile -Path $Credits[0].FullName `
            -Label "三遍SOS信用$Suffix"
        $Timings = @(Get-ChildItem -LiteralPath $Root -File |
            Where-Object { $_.Name -match "_relay_timing_$Suffix\.bin$" })
        if ($Timings.Count -ne 1) {
            throw "三遍SOS时序$Suffix 原件数量错误：$($Timings.Count)"
        }
        $PreviousFlush = Assert-RelayTimingFile `
            -Path $Timings[0].FullName -Index $Index `
            -PreviousFlush $PreviousFlush
    }
}

function Assert-FrozenTwentyFiveEvidence {
    param([string]$Root)
    if ($Mode -ne 'realtime_relay_fixed_asset_twentyfive_data36_no_rf') {
        return
    }
    $ExpectedRuntime14 = '0101010000123456789012345678'
    $RuntimeFiles = @(Get-ChildItem -LiteralPath $Root -File |
        Where-Object { $_.Name -match '_privacy_privacy_on_runtime14\.bin$' })
    if ($RuntimeFiles.Count -ne 1) {
        throw "25单元运行快照文件数错误：$($RuntimeFiles.Count)"
    }
    $RuntimeHex = -join (([IO.File]::ReadAllBytes($RuntimeFiles[0].FullName) |
        ForEach-Object { $_.ToString('x2') }))
    if ($RuntimeHex -ne $ExpectedRuntime14) {
        throw "25单元运行快照不是冻结14字节：$RuntimeHex"
    }
    $ExpectedRequests = @(
        '86DA1C498EEA1678B6ED2306DF3A8335D46C837337E97DFA69F0819827C8C63D',
        'BCF6FEBB720F517568FF283ABE0D5F583141775AEE135E3DC8F86C27ED52FEFF',
        '713F186FC9AB5B1A34BDEF95B1A80A53C2D476D1AB08C760A1C3FDF43457E1B3',
        '499CB6565BB8A3B57FC37F88774DEFC30D6277520B856B113BC40E28B2FB5403',
        '0E116E143A5DE57A1B48A383BB005CB610048A052A31DDE318F01317409D1D47',
        '8E6F5A4FDCB8A32794A92A92BB8337BEB812AC5F1B164133DABDEDA8A8D63294',
        '7D985C9C870A176EE3FD307D4AA53A9040FF28A8B851F8763C11D4E00DDB3C72',
        '579E487607E29C7E188440024B7ED92CEC142F8084BEEE017EA08D40F76349D9',
        '2AE3D13D9E72D7AF543193EBCCDDD0E5D681EADBABA7DD6DA05658B083C5B197',
        'B1EADF15C095EE56C3CF81997AA26C5D539BECBFE3186841D30F91408D752456',
        '53D0FD10667D47D61F735DBAEEBE8B53F77BC55DB144747F8C52B6342597ACEE',
        'DFAAB8110D5B15FBCD4FF6DB3EAF13BE4EECF5ADDDB9CB66D9DCAFC52FC5878B',
        'CB905241B29D3F2A6B7E71F52EF4A988902BF3C5FB964A85382294C3DADE8683',
        '3D0956FFC7C25DF832BDF9C900FC724C05BE82B4AEA3126EB92E10E20C2E1A82',
        '389E7B51B0AB7D442DD80EB10F23E81D52A777292F96450A3990412124C5CBA2',
        '0FB417A1537457F941EE054D1541F520BA8208FA425446C93D3C31DA5F7423A7',
        '7995EDB89F0C1CFE1D6C75072A39FB6D9DFD08B375F2B79A65D80C381B0F2B42',
        'D6FB4727DCFA567339A9EC284ADEF66DA9A96AD917E3D56F7CC210765A72E037',
        'AB0E26D07668A46967D32377CA99CCB4B49701A93A41B638446AF344B1D7F385',
        'CCA60559FAF31958013A5FD95E0E83657FE94507492AC81B28D694D38D6A17E1',
        'C77529FCCD00497C959A23E2D35E882BBC0C3ADA6A9C7A1288E9F83549CDFD9D',
        '7A46CD1CB1A086093527B171F7C5340147F3F5ADC22428D3D6CE491B7F3ED5FF',
        '1D032C3132D0C7856ED5BCFF2BE2EBC5C648327047D2DAD33E940D3ADC4C70D6',
        '90B7DF9C247D69F6E3115097988C4B6907648946DCA826E2A9528FAEAB86C0DC',
        '21353FF2C18DB9C8F54B02E369A0E682A72C4587B6DB1B6C72215EE151D1C12B'
    )
    $ExpectedPlains = @(
        '8DA5EFF98AA0CFF913981A1E87A6E59EE36DB0808C57BD9AC246A806DCCDD393',
        '6152B1B7453554154DF2F5272D48938F62858BCB90A972B9E29595E60F692DF2',
        '723323BC09CF5BE04665FBAE7804C359669796B51D9A31011FFAA4BB3A44228F',
        '79865E74D6AB17939CDBBE1877E30CC3CE1A595C8A13304F27A16991F4C0A578',
        'BCB760CD6EEF169F30748B30B085EF1090749E54CB21F062244BDC23C01EED56',
        'E55D676FD5CDC96441D0ECF62F3142DE2D8FF5326A7168D438BD8DEE19B398CA',
        '61A93C662630546DB153809BFA872A58EB901525BE9B9C2F8A4E9AA75EFC2158',
        '9ECB067F1E5B9CDC3BD2A4EE7D92A1BA2A7F62C0A82971E1673918C00F175321',
        'B2BBB0206E275C6EE51B2F7F795F4A13B50082D51DADD2C4F097D29C1AA1631B',
        '30BBBCA72DB3BDEC00C477A1CDD724915A04D4E37B325FDAB552FEA037BA4533',
        '7332BAFF5FA6A2B52A33223D12C6D6D0C2C5238EFB196A848BA097D308CBFF76',
        '04DDB514A01E49B51C1533DDAF803BACCA3322BA45600E5A0B45B16C47C499A2',
        '8F83F5889D11FEEE4D95C063B539FAEA36CD69DBE37AF64A6903914DCACABF29',
        'F44D6098D7AD6FA0401B6B584D7DC359F1A1C37CBCFAF130F5B17D855D2BDACB',
        '2DB2D491B4F860B5A2BE7C4649E11CDF3D95E835BCD5E2FC518AD2B275A03102',
        '9B3837ED4A1D70D930E8DDF7487E1B1F3557A3AEAA2A277E450E26D3083161F0',
        'F8D926DCE6B21CDC82F3029944DF7711DB62E5D82F3941A0FDD00EBEF624BA40',
        '8D6BB3DE0CD323FFD973780B0347BD406C1505A5AF46B267E4D605922358AE8E',
        '03ED828C27251F57C678BF87A6B9051EDCBA601D77449E340D452E3676F5C55D',
        'FA9B384CF289BAB73E53D1BCAC0C784C72DFA050AF2F95D531A33A7E02F45B22',
        'A25BFE604D6F41D6A56EEABC915CF212ABD2842D02DAC2CFB05F9E97D4363536',
        'CC788948D99D97B7F97B7021B0F44326A0D88B0893A088FB3F7E096AF50AEB06',
        '54BEFFAD3056A779111BACEB1E5FA40F761799909D7C6F22676311415E21ABC5',
        '06ACBE1615B7E5173B2665B11DBDD9525E152BFD6FF053C864659D65DCAB3152',
        'FDF29197F184975DB3858C480D01F4A7F72B7195C35602A18CB441A9C288E42F'
    )
    for ($Index = 0; $Index -lt 25; $Index++) {
        $Suffix = '{0:d2}' -f $Index
        $Request = @(Get-ChildItem -LiteralPath $Root -File |
            Where-Object { $_.Name -match "_data36_relay_request_$Suffix\.bin$" })
        if ($Request.Count -ne 1) {
            throw "25单元请求$Suffix 文件数错误：$($Request.Count)"
        }
        $RequestHash = (Get-FileHash -LiteralPath $Request[0].FullName -Algorithm SHA256).Hash
        if ($RequestHash -ne $ExpectedRequests[$Index]) {
            throw "25单元请求$Suffix 哈希不符：expected=$($ExpectedRequests[$Index]) actual=$RequestHash"
        }
        if ($Index -eq 0) {
            $Plain = @(Get-ChildItem -LiteralPath $Root -File |
                Where-Object { $_.Name -match '_relay_plain36\.bin$' })
        } else {
            $Plain = @(Get-ChildItem -LiteralPath $Root -File |
                Where-Object { $_.Name -match "_relay_plain36_$Suffix\.bin$" })
        }
        if ($Plain.Count -ne 1) {
            throw "25单元明文$Suffix 文件数错误：$($Plain.Count)"
        }
        $PlainHash = (Get-FileHash -LiteralPath $Plain[0].FullName -Algorithm SHA256).Hash
        if ($PlainHash -ne $ExpectedPlains[$Index]) {
            throw "25单元明文$Suffix 哈希不符：expected=$($ExpectedPlains[$Index]) actual=$PlainHash"
        }
    }
}

function Write-HostManifest {
    $Manifest = Join-Path $Capture 'host_artifacts_sha256.tsv'
    $Rows = @('相对路径' + "`t" + '字节数' + "`t" + 'SHA-256' + "`t" + '文件时间')
    foreach ($File in (Get-ChildItem -LiteralPath $Capture -File -Recurse |
            Where-Object { $_.FullName -ne $Manifest } |
            Sort-Object FullName)) {
        $Relative = $File.FullName.Substring($Capture.Length + 1).Replace('\','/')
        $Hash = (Get-FileHash -LiteralPath $File.FullName -Algorithm SHA256).Hash
        $Rows += "$Relative`t$($File.Length)`t$Hash`t$($File.LastWriteTimeUtc.ToString('o'))"
    }
    $Rows | Out-File -LiteralPath $Manifest -Encoding utf8
}

function Restore-ProductionStrict {
    Invoke-Adb shell am force-stop $Package | Out-Null
    Start-Sleep -Milliseconds 400
    if (Get-PackagePid $Package) {
        Invoke-Adb shell am force-stop $Package | Out-Null
        Start-Sleep -Milliseconds 400
    }
    Assert-TtyUnowned
    Save-Text 'disable_probe_restored.txt' (Invoke-Adb -Arguments @(
        'shell','pm','disable-user','--user','0',$Package))
    if (Get-PackagePid $Package) {
        throw '恢复生产前测试包仍有进程。'
    }
    Invoke-Adb -Arguments @('logcat','-c') | Out-Null
    Save-Text 'enable_production.txt' (Invoke-Adb shell pm enable $ProductionPackage)
    Save-Text 'production_start.txt' (Invoke-Adb -Arguments @(
        'shell','am','start','-W','-n',$ProductionActivity))
    $ProductionPid = ''
    for ($Attempt = 1; $Attempt -le 25; $Attempt++) {
        Start-Sleep -Seconds 1
        $ProductionPid = Get-PackagePid $ProductionPackage
        $Owners = @(Get-TtyOwnerPids)
        if ($ProductionPid -and $Owners.Count -eq 1 -and
            $Owners[0] -eq $ProductionPid) {
            break
        }
    }
    $Owners = @(Get-TtyOwnerPids)
    if (-not $ProductionPid -or $Owners.Count -ne 1 -or
        $Owners[0] -ne $ProductionPid) {
        throw "生产恢复持口失败：PID=$ProductionPid，所有者=$($Owners -join ',')"
    }
    $RestoreLog = ''
    for ($Attempt = 1; $Attempt -le 20; $Attempt++) {
        Start-Sleep -Seconds 1
        $RestoreLog = (Invoke-Adb -Arguments @(
            'logcat','-d','-v','threadtime')) -join "`n"
        if ($RestoreLog -match '\+DMOCONNECT:0' -and
            $RestoreLog -match '\+DMOGETSOFTVERSION:0\.3\.66;V2\.01\.07I3' -and
            $RestoreLog -match '\+DMOSETDIGITALCH:0') {
            break
        }
    }
    Save-Text 'restore_logcat.txt' $RestoreLog
    if ($RestoreLog -notmatch '\+DMOCONNECT:0' -or
        $RestoreLog -notmatch '\+DMOGETSOFTVERSION:0\.3\.66;V2\.01\.07I3' -or
        $RestoreLog -notmatch '\+DMOSETDIGITALCH:0') {
        throw '生产恢复缺少本次新鲜模块连接、版本或频道确认原文。'
    }
    $Gpio = Get-GpioState
    Save-Text 'gpio_restored.txt' $Gpio
    Assert-GpioBaseline $Gpio
    $Wifi = Get-WifiState
    Save-Text 'wifi_restored.txt' $Wifi
    Assert-WifiBaseline $Wifi
    Save-Text 'production_pid_restored.txt' $ProductionPid
    Save-Text 'port_owner_restored.txt' (Invoke-Adb shell su -c 'lsof /dev/ttyHS0 2>&1')
}

if ($SelfTest) {
    Invoke-HostContractSelfTest
    return
}

if (-not (Test-Path -LiteralPath $Adb -PathType Leaf)) {
    throw "找不到ADB：$Adb"
}
if ($Mode -in @('realtime_relay_one_data36_no_rf',
        'realtime_relay_fixed_asset_one_data36_no_rf',
        'realtime_relay_fixed_asset_three_data36_no_rf',
        'realtime_relay_fixed_asset_five_data36_no_rf',
        'realtime_relay_fixed_asset_twentyfive_data36_no_rf',
        'realtime_relay_software_49bit_one_data36_no_rf',
        'realtime_relay_software_49bit_tone800_one_data36_no_rf',
        'realtime_relay_software_49bit_morse_one_data36_no_rf',
        'realtime_relay_encode_dmr_silence_one_data36_no_rf',
        'realtime_relay_encode_dmr_tone800_one_data36_no_rf',
        'realtime_relay_encode_dmr_tone800_three_data36_no_rf',
        'realtime_relay_encode_dmr_tone800_five_data36_no_rf',
        'realtime_relay_encode_dmr_morse_unique_one_data36_no_rf',
        'realtime_relay_encode_dmr_morse_unique_three_data36_no_rf',
        'realtime_relay_encode_dmr_morse_unique_five_data36_no_rf',
        'realtime_relay_software_privacy_morse_twentyfive_no_rf',
        'realtime_relay_software_privacy_triple_sos_no_rf') -and -not $AllowPotentialRf) {
    throw '实时relay一包仍属潜在发射路径，需显式传入-AllowPotentialRf。'
}
if ($Mode -notin @('clear_only','setup0_only') -and -not $Setup0Stable) {
    throw '完整会话要求先有同制品setup0连续三次通过证据，需显式传入-Setup0Stable。'
}
if (-not $AllowDisableInterphone) {
    throw '本流程必须暂时停用生产Interphone，需显式传入-AllowDisableInterphone。'
}

New-Item -ItemType Directory -Path $Capture | Out-Null
Copy-Item -LiteralPath $PSCommandPath -Destination `
    (Join-Path $Capture 'run_h13_dmr_tx_harness_usb.ps1')
Save-Text 'host_script_sha256.txt' `
    (Get-FileHash -LiteralPath $PSCommandPath -Algorithm SHA256).Hash
try {
$env:ANDROID_ADB_SERVER_PORT = $AdbPort

$Devices = & $Adb -P $AdbPort devices -l
Save-Text 'adb_devices_before.txt' $Devices
$DeviceLines = @($Devices | Where-Object { $_ -match '^0\s+device\s+' })
if ($DeviceLines.Count -ne 1 -or
    $DeviceLines[0] -notmatch 'product:msm8909' -or
    $DeviceLines[0] -notmatch 'model:PoC_H13') {
    throw 'USB准入失败：必须且只能存在序列号0的PoC_H13。'
}
if (@($Devices | Where-Object { $_ -match '^\d+\.\d+\.\d+\.\d+:\d+\s+' }).Count -gt 0) {
    throw '检测到无线ADB条目，禁止继续。'
}

if (-not (Test-Path -LiteralPath $Apk -PathType Leaf)) {
    throw "找不到待核验APK：$Apk"
}
$LocalApkHash = (Get-FileHash -LiteralPath $Apk -Algorithm SHA256).Hash
Save-Text 'apk_sha256_local.txt' $LocalApkHash
if ($ExpectedApkSha256 -eq '__BUILD_PENDING__' -or
    $LocalApkHash -ne $ExpectedApkSha256) {
    throw "冻结APK哈希不匹配：expected=$ExpectedApkSha256 actual=$LocalApkHash"
}
$Fingerprint = ((Invoke-Adb shell getprop ro.build.fingerprint) -join '').Trim()
Save-Text 'build_fingerprint_before.txt' $Fingerprint
if ($Fingerprint -ne $ExpectedFingerprint) {
    throw "构建指纹不匹配：$Fingerprint"
}
if ($Mode -ne 'clear_only') {
    Assert-ClearPrecheck -Directory $ClearPrecheckCapture `
        -ApkHash $LocalApkHash -Fingerprint $Fingerprint
    $ClearPrecheckApkHash = (Get-Content -LiteralPath `
        (Join-Path $ClearPrecheckCapture 'apk_sha256_local.txt') -Raw).Trim()
    Save-Text 'clear_channel_precheck_reference.txt' `
        "capture=$((Resolve-Path -LiteralPath $ClearPrecheckCapture).Path)`nprecheck_apk_sha256=$ClearPrecheckApkHash`ncurrent_apk_sha256=$LocalApkHash`n"
}
$GpioBefore = Get-GpioState
Save-Text 'gpio_before.txt' $GpioBefore
Assert-GpioBaseline $GpioBefore
$WifiBefore = Get-WifiState
Save-Text 'wifi_before.txt' $WifiBefore
Assert-WifiBaseline $WifiBefore
Save-Text 'port_owner_before.txt' (Invoke-Adb shell su -c 'lsof /dev/ttyHS0 2>&1')
Save-Text 'packages_before.txt' (Invoke-Adb -Arguments @(
    'shell','pm','list','packages','-d'))
$ProductionPidBefore = Assert-ProductionOwner
Save-Text 'production_pid_before.txt' $ProductionPidBefore

if ($AllowInstall) {
    Save-Text 'install_output.txt' (& $Adb -P $AdbPort -s $Serial install -r $Apk 2>&1)
    if ($LASTEXITCODE -ne 0) {
        throw 'APK安装失败。'
    }
}

$Installed = Invoke-Adb shell pm path $Package
Save-Text 'package_path.txt' $Installed
if ($Installed -notmatch '^package:') {
    throw '新测试APK尚未安装；请在明确允许安装后传入-AllowInstall。'
}
$RemoteApk = (($Installed | Where-Object { $_ -match '^package:' } |
    Select-Object -First 1) -replace '^package:','').Trim()
$RemoteHashRaw = (Invoke-Adb shell su -c "sha256sum $RemoteApk") -join ' '
Save-Text 'apk_sha256_device.txt' $RemoteHashRaw
$RemoteHashMatch = [regex]::Match($RemoteHashRaw, '^([0-9a-fA-F]{64})\s+')
if (-not $RemoteHashMatch.Success -or
    $RemoteHashMatch.Groups[1].Value.ToUpperInvariant() -ne
    $LocalApkHash.ToUpperInvariant()) {
    throw '设备侧APK哈希与本机制品不一致。'
}
$PackageDump = (Invoke-Adb shell dumpsys package $Package) -join "`n"
Save-Text 'package_dump_before.txt' $PackageDump
if ($PackageDump -notmatch "versionCode=$ExpectedVersionCode(?:\s|$)" -or
    $PackageDump -notmatch "versionName=$([regex]::Escape($ExpectedVersionName))(?:\s|$)") {
    throw '设备侧APK版本号或版本名与冻结制品不一致。'
}
Save-Text 'enable_probe.txt' (Invoke-Adb -Arguments @(
    'shell','pm','enable','--user','0',$Package))
$DisabledAfterEnable = (Invoke-Adb -Arguments @(
    'shell','pm','list','packages','-d')) -join "`n"
Save-Text 'disabled_packages_after_probe_enable.txt' $DisabledAfterEnable
if ($DisabledAfterEnable -match "(?m)^package:$([regex]::Escape($Package))\r?$") {
    throw '测试包显式启用后仍在disabled-user列表。'
}

$Result = ''
$Session = ''
$RfStartObservedAt = $null
$RfPrepImminentAt = $null
$HardStop = $false
$ProductionDisabled = $false
$ProbeActivityStartIssued = $false
$ProbeOwnerChecked = $false
$RestoreFailure = ''
try {
    Invoke-Adb shell am force-stop $ProductionPackage | Out-Null
    Start-Sleep -Milliseconds 900
    if (Get-PackagePid $ProductionPackage) {
        Invoke-Adb shell am force-stop $ProductionPackage | Out-Null
        Start-Sleep -Milliseconds 500
    }
    Save-Text 'disable_production.txt' (Invoke-Adb -Arguments @(
        'shell','pm','disable-user','--user','0',$ProductionPackage))
    $ProductionDisabled = $true
    for ($Round = 0; $Round -lt 4; $Round++) {
        Start-Sleep -Milliseconds 350
        if (Get-PackagePid $ProductionPackage) {
            throw "停用生产应用后第$Round轮仍检测到进程。"
        }
    }
    Save-Text 'port_owner_after_disable.txt' (Invoke-Adb shell su -c 'lsof /dev/ttyHS0 2>&1')
    Assert-TtyUnowned

    $BeforeAttempt = Invoke-AdbOptional shell run-as $Package ls files/captures
    $BeforeSessions = if ($BeforeAttempt.ExitCode -eq 0) {
        @($BeforeAttempt.Output)
    } else {
        @()
    }
    Save-Text 'device_sessions_before.txt' $BeforeSessions
    $DeviceDeadlineStarted = $false
    if ($Mode -in @('deadline_handshake_no_rf','low_power_rf',
            'realtime_relay_software_privacy_triple_sos_low_power_rf')) {
        if (-not (Test-Path -LiteralPath $DeadlineHelper -PathType Leaf)) {
            throw "设备侧截止器脚本缺失：$DeadlineHelper"
        }
        $DeadlineHelperHash = (Get-FileHash -LiteralPath $DeadlineHelper `
            -Algorithm SHA256).Hash
        Save-Text 'deadline_helper_sha256_local.txt' $DeadlineHelperHash
        if ($ExpectedDeadlineHelperSha256 -eq '__BUILD_PENDING__' -or
            $DeadlineHelperHash -ne $ExpectedDeadlineHelperSha256) {
            throw "设备侧截止器脚本哈希不匹配：expected=$ExpectedDeadlineHelperSha256 actual=$DeadlineHelperHash"
        }
        Copy-Item -LiteralPath $DeadlineHelper -Destination `
            (Join-Path $Capture 'device_rf_deadline_helper.sh')
        & $Adb -P $AdbPort -s $Serial push $DeadlineHelper $RemoteDeadlineHelper |
            Out-File -LiteralPath (Join-Path $Capture 'deadline_helper_push.txt') -Encoding utf8
        if ($LASTEXITCODE -ne 0) { throw '设备侧截止器脚本推送失败。' }
        Invoke-Adb shell su -c "chmod 0755 $RemoteDeadlineHelper" | Out-Null
        $RemoteDeadlineHash = ((Invoke-Adb shell su -c `
            "sha256sum $RemoteDeadlineHelper") -join ' ').Split(' ')[0].Trim().ToUpperInvariant()
        Save-Text 'deadline_helper_sha256_device.txt' $RemoteDeadlineHash
        if ($RemoteDeadlineHash -ne $DeadlineHelperHash) {
            throw '设备侧截止器脚本上传后哈希不一致。'
        }
        Invoke-Adb -Arguments @(
            'shell','mkdir','-p',"/sdcard/Android/data/$Package/files/deadlines") |
            Out-Null
    }
    $DeviceMode = switch ($Mode) {
        'deadline_handshake_no_rf' { 'deadline_handshake_no_rf' }
        'realtime_relay_one_data36_no_rf' { 'realtime_relay_one_data36_no_rf' }
        'realtime_relay_fixed_asset_one_data36_no_rf' { 'realtime_relay_fixed_asset_one_data36_no_rf' }
        'realtime_relay_fixed_asset_three_data36_no_rf' { 'realtime_relay_fixed_asset_three_data36_no_rf' }
        'realtime_relay_fixed_asset_five_data36_no_rf' { 'realtime_relay_fixed_asset_five_data36_no_rf' }
        'realtime_relay_fixed_asset_twentyfive_data36_no_rf' { 'realtime_relay_fixed_asset_twentyfive_data36_no_rf' }
        'realtime_relay_software_49bit_one_data36_no_rf' { 'realtime_relay_software_49bit_one_data36_no_rf' }
        'realtime_relay_software_49bit_tone800_one_data36_no_rf' { 'realtime_relay_software_49bit_tone800_one_data36_no_rf' }
        'realtime_relay_software_49bit_morse_one_data36_no_rf' { 'realtime_relay_software_49bit_morse_one_data36_no_rf' }
        'realtime_relay_encode_dmr_silence_one_data36_no_rf' { 'realtime_relay_encode_dmr_silence_one_data36_no_rf' }
        'realtime_relay_encode_dmr_tone800_one_data36_no_rf' { 'realtime_relay_encode_dmr_tone800_one_data36_no_rf' }
        'realtime_relay_encode_dmr_tone800_three_data36_no_rf' { 'realtime_relay_encode_dmr_tone800_three_data36_no_rf' }
        'realtime_relay_encode_dmr_tone800_five_data36_no_rf' { 'realtime_relay_encode_dmr_tone800_five_data36_no_rf' }
        'realtime_relay_encode_dmr_morse_unique_one_data36_no_rf' { 'realtime_relay_encode_dmr_morse_unique_one_data36_no_rf' }
        'realtime_relay_encode_dmr_morse_unique_three_data36_no_rf' { 'realtime_relay_encode_dmr_morse_unique_three_data36_no_rf' }
        'realtime_relay_encode_dmr_morse_unique_five_data36_no_rf' { 'realtime_relay_encode_dmr_morse_unique_five_data36_no_rf' }
        'realtime_relay_software_privacy_morse_twentyfive_no_rf' { 'realtime_relay_software_privacy_morse_twentyfive_no_rf' }
        'realtime_relay_software_privacy_triple_sos_no_rf' { 'realtime_relay_software_privacy_triple_sos_no_rf' }
        'setup0_only' { 'setup0_only_no_rf' }
        'clear_only' { 'clear_channel_only_no_rf' }
        default { 'session_prepare_no_rf' }
    }
    $StartArguments = @('shell','am','start','-W','-n',$Activity,
        '--es','mode',$DeviceMode)
    $StartArguments += @('--ez','auto_start','true')
    $ActivityStart = (Invoke-Adb -Arguments $StartArguments) -join "`n"
    Save-Text 'activity_start.txt' $ActivityStart
    if (-not (Test-ActivityStartAccepted $ActivityStart)) {
        throw '测试Activity启动输出未通过严格门。'
    }
    $ProbeActivityStartIssued = $true

    $Deadline = (Get-Date).AddSeconds($WaitSeconds)
    while ((Get-Date) -lt $Deadline) {
        $SessionAttempt = Invoke-AdbOptional shell run-as $Package ls files/captures
        $Sessions = if ($SessionAttempt.ExitCode -eq 0) {
            @($SessionAttempt.Output)
        } else {
            @()
        }
        $New = @($Sessions | Where-Object {
            $_ -match '^[0-9]{8}_[0-9]{6}_[0-9]{3}_(rf|deadline_no_rf|relay_no_rf|no_rf|setup0|clear)$' -and
            $_ -notin $BeforeSessions
        } | Sort-Object)
        if ($New.Count -gt 0) {
            $Session = $New[-1].Trim()
            if (-not $ProbeOwnerChecked) {
                $ProbePid = ''
                $Owners = @()
                for ($OwnerAttempt = 1; $OwnerAttempt -le 40; $OwnerAttempt++) {
                    $ProbePid = Get-PackagePid $Package
                    $Owners = @(Get-TtyOwnerPids)
                    if ($ProbePid -and $Owners.Count -eq 1 -and
                        $Owners[0] -eq $ProbePid) {
                        break
                    }
                    Start-Sleep -Milliseconds 250
                }
                if (-not $ProbePid -or $Owners.Count -ne 1 -or
                    $Owners[0] -ne $ProbePid) {
                    throw "探针持口门失败：PID=$ProbePid，所有者=$($Owners -join ',')"
                }
                Save-Text 'probe_sole_tty_owner.txt' "pid=$ProbePid`nowners=$($Owners -join ',')"
                $ProbeOwnerChecked = $true
            }
            if ($Mode -in @('deadline_handshake_no_rf','low_power_rf',
                    'realtime_relay_software_privacy_triple_sos_low_power_rf') -and
                    -not $DeviceDeadlineStarted) {
                $FirstBridgeRfMode = $Mode -in @(
                    'realtime_relay_software_privacy_triple_sos_low_power_rf')
                $ArmFile = if ($FirstBridgeRfMode) {
                    'rf_first_bridge_arm_request.txt'
                } else {
                    'rf_deadline_arm_request.txt'
                }
                $ArmRead = Invoke-AdbOptional -Arguments @(
                    'shell','run-as',$Package,'cat',
                    "files/captures/$Session/$ArmFile")
                if ($ArmRead.ExitCode -eq 0) {
                    $ArmText = $ArmRead.Output -join "`n"
                    $ExpectedFirstBridgeExit = if ($FirstBridgeRfMode) {
                        'first_bridge_exit_confirmed=false'
                    } else {
                        'first_bridge_exit_confirmed=true'
                    }
                    foreach ($Required in @(
                            "launch_session_id=$Session",
                            $ExpectedFirstBridgeExit,
                            'rf_prepare_executed=false',
                            'rf_may_be_active=false',
                            'rf_timeout_sec=30')) {
                        if ($ArmText -notmatch "(?m)^$([regex]::Escape($Required))$") {
                            throw "截止器延后武装请求字段错误：$Required"
                        }
                    }
                    if ($FirstBridgeRfMode -and
                            $ArmText -notmatch '(?m)^rf_window_kind=first_bridge$') {
                        throw '第一桥截止器武装请求缺少窗口类型。'
                    }
                    Save-Text 'rf_deadline_arm_request_host.txt' $ArmText
                    $Marker = "/sdcard/Android/data/$Package/files/deadlines/$Session.marker"
                    $Private = "/data/data/$Package/files/captures/$Session"
                    $Launch = "sh $RemoteDeadlineHelper '$Session' 30 '$Marker' '$Private/rf_prep_imminent.txt' '$Private/rf_actual_start.txt' '$Private/atomic_result.txt' '$Private/rf_release_attestation.txt' 180 launch '$Package'"
                    Save-Text 'device_deadline_launch.txt' `
                        (Invoke-Adb shell su -c $Launch)
                    $MarkerText = ''
                    for ($DeadlineAttempt = 1; $DeadlineAttempt -le 50; $DeadlineAttempt++) {
                        Start-Sleep -Milliseconds 100
                        $MarkerRead = Invoke-AdbOptional shell su -c "cat '$Marker'"
                        if ($MarkerRead.ExitCode -eq 0) {
                            $MarkerText = $MarkerRead.Output -join "`n"
                            if ($MarkerText -match "(?m)^launch_session_id=$([regex]::Escape($Session))$" -and
                                $MarkerText -match '(?m)^device_armed=true$' -and
                                $MarkerText -match '(?m)^rf_timeout_sec=30$') { break }
                        }
                    }
                    if (-not $MarkerText -or
                        $MarkerText -notmatch '(?m)^device_armed=true$') {
                        throw '设备侧30秒截止器未在RF准备前完成武装。'
                    }
                    Save-Text 'device_deadline_marker_armed.txt' $MarkerText
                    $DeviceDeadlineStarted = $true
                }
            }
            if ($Mode -in @('low_power_rf',
                    'realtime_relay_software_privacy_triple_sos_low_power_rf') -and
                    $null -eq $RfPrepImminentAt -and
                (Invoke-AdbOptional -Arguments @('shell','run-as',$Package,
                    'test','-f',"files/captures/$Session/rf_prep_imminent.txt")).ExitCode -eq 0) {
                $RfPrepImminentAt = Get-Date
                Save-Text 'host_rf_prep_imminent.txt' "host_time=$($RfPrepImminentAt.ToString('o'))`nsession=$Session"
            }
            if ($Mode -in @('low_power_rf',
                    'realtime_relay_software_privacy_triple_sos_low_power_rf') -and
                    $null -eq $RfStartObservedAt -and
                (Invoke-AdbOptional -Arguments @('shell','run-as',$Package,
                    'test','-f',"files/captures/$Session/rf_actual_start.txt")).ExitCode -eq 0) {
                $RfStartObservedAt = Get-Date
                Save-Text 'host_rf_start_observed.txt' "host_time=$($RfStartObservedAt.ToString('o'))`nsession=$Session"
            }
            $AtomicAttempt = Invoke-AdbOptional shell run-as $Package cat "files/captures/$Session/atomic_result.txt"
            if ($AtomicAttempt.ExitCode -eq 0 -and
                ($AtomicAttempt.Output -join "`n") -match '^result=') {
                $Result = $AtomicAttempt.Output -join "`n"
                break
            }
        }
        if ($null -ne $RfStartObservedAt -and
            ((Get-Date) - $RfStartObservedAt).TotalSeconds -ge 30) {
            Save-Text 'hard_stop_output.txt' (Invoke-HardStop)
            $HardStop = $true
            break
        }
        if ($null -eq $RfStartObservedAt -and $null -ne $RfPrepImminentAt -and
            ((Get-Date) - $RfPrepImminentAt).TotalSeconds -ge 35) {
            Save-Text 'hard_stop_imminent_fallback.txt' (Invoke-HardStop)
            $HardStop = $true
            break
        }
        Start-Sleep -Seconds 1
    }

    if ($Session -and $Result) {
        $StableDeviceManifest = ''
        for ($ManifestAttempt = 1; $ManifestAttempt -le 40;
                $ManifestAttempt++) {
            $FirstManifest = Invoke-AdbOptional -Arguments @(
                'shell','run-as',$Package,'cat',
                "files/captures/$Session/artifacts_sha256.tsv")
            $FirstText = $FirstManifest.Output -join "`n"
            if ($FirstManifest.ExitCode -eq 0 -and
                $FirstText -match '(?m)^相对路径\s+字节数\s+SHA-256') {
                Start-Sleep -Milliseconds 250
                $SecondManifest = Invoke-AdbOptional -Arguments @(
                    'shell','run-as',$Package,'cat',
                    "files/captures/$Session/artifacts_sha256.tsv")
                $SecondText = $SecondManifest.Output -join "`n"
                if ($SecondManifest.ExitCode -eq 0 -and
                    $SecondText -eq $FirstText) {
                    $StableDeviceManifest = $SecondText
                    break
                }
            }
            Start-Sleep -Milliseconds 250
        }
        if (-not $StableDeviceManifest) {
            throw '设备原子结果已出现，但完整证据清单未稳定落盘。'
        }
        Save-Text 'device_manifest_ready.txt' $StableDeviceManifest
    }

    Save-Text 'atomic_result_host.txt' $Result
    if ($Result -match '(?m)^rf_may_be_active=true\r?$') {
        Save-Text 'result_forced_hard_stop.txt' (Invoke-HardStop)
        $HardStop = $true
    }
    if ($Session) {
        $External = "/sdcard/Android/data/$Package/files/$Session"
        Invoke-Adb -Arguments @('shell','run-as',$Package,'mkdir','-p',
            "/sdcard/Android/data/$Package/files") | Out-Null
        Invoke-Adb -Arguments @('shell','run-as',$Package,'cp','-r',
            "files/captures/$Session",
            "/sdcard/Android/data/$Package/files/") | Out-Null
        & $Adb -P $AdbPort -s $Serial pull $External (Join-Path $Capture 'device_capture') | Out-File -LiteralPath (Join-Path $Capture 'pull_output.txt') -Encoding utf8
        if ($LASTEXITCODE -ne 0) {
            throw '设备证据目录拉取失败。'
        }
        Assert-PulledDeviceManifest -ResultText $Result
        Invoke-AdbOptional -Arguments @('shell','run-as',$Package,'rm','-rf',
            $External) | Out-Null
    }
    Save-Text 'logcat_all.txt' (Invoke-Adb -Arguments @(
        'logcat','-d','-v','threadtime'))
    if ($Mode -in @('deadline_handshake_no_rf','low_power_rf',
            'realtime_relay_software_privacy_triple_sos_low_power_rf') -and
            $Session -and $DeviceDeadlineStarted) {
        $Marker = "/sdcard/Android/data/$Package/files/deadlines/$Session.marker"
        $DeadlineFinal = ''
        for ($DeadlineDoneAttempt = 1; $DeadlineDoneAttempt -le 80; $DeadlineDoneAttempt++) {
            $DeadlineRead = Invoke-AdbOptional shell su -c "cat '$Marker'"
            if ($DeadlineRead.ExitCode -eq 0) {
                $DeadlineFinal = $DeadlineRead.Output -join "`n"
                if ($DeadlineFinal -match '(?m)^device_deadline_fired=true$' -and
                    $DeadlineFinal -match '(?m)^hard_stop_confirmed=true$') { break }
            }
            Start-Sleep -Milliseconds 250
        }
        Save-Text 'device_rf_deadline_marker.txt' $DeadlineFinal
        if ($DeadlineFinal -notmatch '(?m)^device_deadline_fired=true$' -or
            $DeadlineFinal -notmatch '(?m)^hard_stop_confirmed=true$') {
            $HardStop = $true
            Save-Text 'device_deadline_forced_fallback.txt' (Invoke-HardStop)
            throw '设备侧截止器没有在生产恢复前给出完成和硬关闭证明。'
        }
        Save-Text 'device_rf_deadline_monitor.txt' `
            ((Invoke-AdbOptional shell su -c "cat '$Marker.monitor'").Output -join "`n")
    } elseif ($Mode -in @('low_power_rf',
            'realtime_relay_software_privacy_triple_sos_low_power_rf') -and
            $Session) {
        $SafeBeforeArm = $Result -match '(?m)^result=FAIL\r?$' -and
            $Result -match '(?m)^device_deadline_arm_requested=false\r?$' -and
            $Result -match '(?m)^device_deadline_armed=false\r?$' -and
            $Result -match '(?m)^rf_prepare_executed=false\r?$' -and
            $Result -match '(?m)^rf_may_be_active=false\r?$' -and
            $Result -match '(?m)^data36_write_attempts=0\r?$'
        if (-not $SafeBeforeArm) {
            $HardStop = $true
            Save-Text 'deadline_not_started_forced_fallback.txt' (Invoke-HardStop)
            throw '低功率模式未启动截止器，且原子结果不能证明仍在安全前门。'
        }
        Save-Text 'device_rf_deadline_not_started.txt' `
            '第一桥或更早门失败，未收到延后武装请求；原子结果证明RF准备和data36均未执行。'
    }
} finally {
    $StopAttempt = Invoke-AdbOptional shell am force-stop $Package
    Save-Text 'probe_force_stop_finally.txt' ($StopAttempt.Output -join "`n")
    $PreActivityFailure = -not $ProbeActivityStartIssued -and -not $HardStop
    if ($ProductionDisabled -and (
            ((Test-ResultAllowsProductionRestore $Result) -and -not $HardStop) -or
            $PreActivityFailure)) {
        try {
            Restore-ProductionStrict
        } catch {
            $RestoreFailure = $_.Exception.Message
            Save-Text 'production_restore_failure.txt' $RestoreFailure
        }
    } else {
        Save-Text 'production_restore_blocked.txt' '结果或恢复证据不完整，未自动恢复生产应用；需要审核后执行获批恢复。'
    }
    try {
        Save-Text 'gpio_after.txt' (Get-GpioState)
    } catch {
        Save-Text 'gpio_after_failure.txt' $_.Exception.Message
    }
    $OwnerAfter = Invoke-AdbOptional shell su -c 'lsof /dev/ttyHS0 2>&1'
    Save-Text 'port_owner_after.txt' ($OwnerAfter.Output -join "`n")
    Save-Text 'adb_devices_after.txt' (& $Adb -P $AdbPort devices -l)
}

if ($RestoreFailure) {
    throw "生产恢复严格核验失败：$RestoreFailure。捕获目录：$Capture"
}

if (-not $Result) {
    throw "在$WaitSeconds秒内未取得原子结果。捕获目录：$Capture"
}
if ($Result -notmatch '(?m)^result=PASS\r?$') {
    throw "设备流程失败。捕获目录：$Capture"
}
Assert-ModeResult $Result
if (-not (Test-ResultAllowsProductionRestore $Result) -or $HardStop) {
    throw "设备流程虽报告PASS，但安全恢复字段或截止状态不满足验收门。捕获目录：$Capture"
}
Write-Host "设备流程通过。捕获目录：$Capture"
} finally {
    Write-HostManifest
}
