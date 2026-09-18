param(
    [Parameter(Mandatory = $true)]
    [string]$Capture
)

$ErrorActionPreference = 'Stop'
$Adb = 'C:\Dev\android-sdk\platform-tools\adb.exe'
$AdbPort = '5038'
$Serial = '0'
$ProductionPackage = 'net.elfradio.h13interphone'
$ExpectedFingerprint =
        'CMCC/msm8909/msm8909:8.1.0/OPM1.171019.026/build11020953:user/test-keys'
$Capture = (Resolve-Path -LiteralPath $Capture).Path

function Invoke-AdbRaw {
    param([string[]]$Arguments)
    $Previous = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $Output = & $Adb -P $AdbPort -s $Serial @Arguments 2>&1
    } finally {
        $ErrorActionPreference = $Previous
    }
    return @($Output)
}

function Save-NewText {
    param([string]$Name, [object]$Value)
    $Target = Join-Path $Capture $Name
    if (Test-Path -LiteralPath $Target) {
        throw "禁止覆盖恢复原件：$Name"
    }
    $Value | Out-File -LiteralPath $Target -Encoding utf8
}

Save-NewText 'process_after_corrected.txt' (Invoke-AdbRaw @('shell','ps','-A'))
Save-NewText 'tty_owner_after_corrected.txt' (Invoke-AdbRaw @(
        'shell','su','-c','lsof /dev/ttyHS0 2>&1'))
$ProductionPid = ((Invoke-AdbRaw @(
        'shell','pidof',$ProductionPackage)) -join ' ').Trim()
Save-NewText 'production_pid_after_corrected.txt' $ProductionPid
$Log = (Invoke-AdbRaw @('logcat','-d','-v','threadtime')) -join "`n"
Save-NewText 'logcat_full_after_corrected.txt' $Log
Save-NewText 'logcat_decisive_after_corrected.txt' ($Log -split "`r?`n" |
        Where-Object {
            $_ -match 'DMOSTARTUP|DMOCONNECT|DMOGETSOFTVERSION|DMOSETPWRSAVELV|DMOSETDIGITALCH'
        })

$GpioRows = @()
foreach ($Name in @('dmr_switch','audio_switch','pa_enable','ptt_d',
        'freq_section')) {
    $Value = (Invoke-AdbRaw @(
            'shell','su','-c',"cat /sys/boptt/$Name")) -join ''
    $GpioRows += "$Name=$($Value.Trim())"
}
$Gpio = $GpioRows -join "`n"
$WifiOn = ((Invoke-AdbRaw @(
        'shell','settings','get','global','wifi_on')) -join '').Trim()
$WifiState = ((Invoke-AdbRaw @(
        'shell','cat','/sys/class/net/wlan0/operstate')) -join '').Trim()
$Wifi = "wifi_on=$WifiOn`noperstate=$WifiState"
Save-NewText 'gpio_after_corrected.txt' $Gpio
Save-NewText 'wifi_after_corrected.txt' $Wifi

$Fingerprint = ((Invoke-AdbRaw @(
        'shell','getprop','ro.build.fingerprint')) -join '').Trim()
$Boot = ((Invoke-AdbRaw @(
        'shell','getprop','sys.boot_completed')) -join '').Trim()
$Owner = Get-Content -LiteralPath (Join-Path $Capture 'tty_owner_after_corrected.txt') -Raw
if ($ProductionPid -notmatch '^\d+$' -or
        $Owner -notmatch "\s$ProductionPid\s" -or
        $Fingerprint -ne $ExpectedFingerprint -or $Boot -ne '1' -or
        $Log -notmatch '\+DMOCONNECT:0' -or
        $Log -notmatch '\+DMOGETSOFTVERSION:0\.3\.66;V2\.01\.07I3' -or
        $Log -notmatch '\+DMOSETDIGITALCH:0' -or
        $Gpio -ne "dmr_switch=1`naudio_switch=0`npa_enable=0`nptt_d=in`nfreq_section=4" -or
        $Wifi -ne "wifi_on=1`noperstate=up") {
    throw '只读收尾核验未达到生产基线。'
}

$Manifest = Join-Path $Capture 'artifacts_sha256.tsv'
if (Test-Path -LiteralPath $Manifest) {
    throw '禁止覆盖恢复证据清单。'
}
$Rows = @("相对路径`t字节数`tSHA-256`t文件时间")
foreach ($File in Get-ChildItem -LiteralPath $Capture -File | Sort-Object Name) {
    $Hash = (Get-FileHash -LiteralPath $File.FullName -Algorithm SHA256).Hash
    $Rows += "$($File.Name)`t$($File.Length)`t$Hash`t$($File.LastWriteTimeUtc.ToString('o'))"
}
$Rows | Out-File -LiteralPath $Manifest -Encoding utf8
Write-Output "H13恢复证据只读收尾通过。目录：$Capture"
