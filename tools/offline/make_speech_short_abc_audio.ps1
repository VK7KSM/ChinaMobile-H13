# 生成首次真机低功率发射验证用的极短人声素材："A. B. C."
#
# 用途：唯一目的是验证"新语音内容能否通过已验证的三遍SOS射频通路发射并被
# 对端听清"，不追求覆盖全部字母数字——那是28.8秒素材以后再测的事。
# 内容特意很短（目标约1.5-1.7秒），配合下游固定为50个历史36字节单元
# （每单元80毫秒，共4.0秒，仍在3-5秒批准区间内，远低于RF_HOLD_MS=16秒
# 和用户30秒硬上限），不做重复。
#
# 输出为8千赫兹、16位有符号单声道，与模块语音采样率一致。
# 本脚本只生成素材，不接触设备。

param(
    [string]$Out = 'speech_short_abc.wav',
    [int]$Rate = -3,
    [int]$BreakMs = 300,
    [string]$Voice = 'Microsoft Zira Desktop'
)

Add-Type -AssemblyName System.Speech

$words = @('A', 'B', 'C')

$synth = New-Object System.Speech.Synthesis.SpeechSynthesizer
$synth.SelectVoice($Voice)
$synth.Rate = $Rate

$format = New-Object System.Speech.AudioFormat.SpeechAudioFormatInfo(
    8000,
    [System.Speech.AudioFormat.AudioBitsPerSample]::Sixteen,
    [System.Speech.AudioFormat.AudioChannel]::Mono)

$full = [System.IO.Path]::GetFullPath($Out)
$synth.SetOutputToWaveFile($full, $format)

$builder = New-Object System.Speech.Synthesis.PromptBuilder
foreach ($word in $words) {
    $builder.AppendText($word)
    $builder.AppendBreak([TimeSpan]::FromMilliseconds($BreakMs))
}
$synth.Speak($builder)
$synth.SetOutputToNull()
$synth.Dispose()

$reader = New-Object System.IO.BinaryReader([System.IO.File]::OpenRead($full))
$null = $reader.ReadBytes(12)
$dataLen = 0
while ($reader.BaseStream.Position -lt $reader.BaseStream.Length - 8) {
    $id = [System.Text.Encoding]::ASCII.GetString($reader.ReadBytes(4))
    $size = $reader.ReadUInt32()
    if ($id -eq 'data') { $dataLen = $size; break }
    $null = $reader.ReadBytes([int]$size + ($size % 2))
}
$reader.Close()
$seconds = $dataLen / 2 / 8000

Write-Output "语音      $Voice，语速 $Rate，字间停顿 $BreakMs 毫秒"
Write-Output "条目      A、B、C"
Write-Output ("时长      {0:N2} 秒（原始，未裁剪/填充前）" -f $seconds)
Write-Output "输出      $full"
