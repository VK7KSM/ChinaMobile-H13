# 生成发射测试用的人声音频素材。
#
# 内容为 26 个字母加 10 个数字逐个朗读，接收端可以逐项核对收到了哪些、
# 从哪里开始丢失。选用人声而非音调，是因为语音声码器按人声建模，
# 纯音调经它编解码后会严重失真（实测摩尔斯经编解码后已无法辨认）。
#
# 约束：射频发射单次不超过 30 秒（发热限制）。当前参数约 28.8 秒。
#
# 输出为 8 千赫兹、16 位有符号单声道，与模块语音采样率一致。
# 本脚本只生成素材，不接触设备。

param(
    [string]$Out = 'speech_az09.wav',
    [int]$Rate = -3,
    [int]$BreakMs = 250,
    [string]$Voice = 'Microsoft Zira Desktop',
    [double]$MaxSeconds = 30.0
)

Add-Type -AssemblyName System.Speech

$words = @(
    'A','B','C','D','E','F','G','H','I','J','K','L','M',
    'N','O','P','Q','R','S','T','U','V','W','X','Y','Z',
    'zero','one','two','three','four','five','six','seven','eight','nine'
)

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

# 按块遍历定位 data 块。合成器写出的头部可能含额外块，
# 固定偏移读长度会得到错误值。
$reader = New-Object System.IO.BinaryReader([System.IO.File]::OpenRead($full))
$null = $reader.ReadBytes(12)          # RIFF 头
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
Write-Output "条目      $($words.Count) 个（26 字母加 10 数字）"
Write-Output ("时长      {0:N2} 秒（上限 {1} 秒）" -f $seconds, $MaxSeconds)
Write-Output "输出      $full"

if ($seconds -gt $MaxSeconds) {
    throw "时长 $seconds 秒超过 $MaxSeconds 秒发射上限，请减小停顿或提高语速。"
}
