#!/system/bin/sh
# 观察原厂发射时的引脚时序。
#
# 只读：本脚本只读取 /sys/boptt 下的节点，不写入任何文件、不占用串口、
# 不停止任何进程。生产专网应用继续独占串口并正常工作，本脚本仅旁观。
#
# 用法（设备侧）：
#   sh watch_stock_ptt_gpio.sh <持续秒数> > /data/local/tmp/<输出文件>
#
# 输出每行：单调毫秒 音频开关 专网开关 功放使能 握手方向 频段分箱

DURATION=${1:-30}

read_node() {
  cat "/sys/boptt/$1" 2>/dev/null || echo "?"
}

# 起始单调时间（纳秒）
start=$(cat /proc/timer_list 2>/dev/null | grep -m1 'now at' | sed 's/[^0-9]*\([0-9]*\).*/\1/')
[ -z "$start" ] && start=0

echo "# 开始 $(date +%Y-%m-%d\ %H:%M:%S) 持续 ${DURATION}s"
echo "# 列：毫秒 audio_switch dmr_switch pa_enable ptt_d freq_section"

end=$(( $(date +%s) + DURATION ))
prev=""
while [ "$(date +%s)" -lt "$end" ]; do
  now=$(cat /proc/timer_list 2>/dev/null | grep -m1 'now at' | sed 's/[^0-9]*\([0-9]*\).*/\1/')
  [ -z "$now" ] && now=0
  ms=$(( (now - start) / 1000000 ))

  a=$(read_node audio_switch)
  d=$(read_node dmr_switch)
  p=$(read_node pa_enable)
  t=$(read_node ptt_d)
  f=$(read_node freq_section)

  line="$a $d $p $t $f"
  # 只在状态变化时输出，避免刷屏；每 500 行强制输出一次心跳
  if [ "$line" != "$prev" ]; then
    echo "$ms $line"
    prev="$line"
  fi
done
echo "# 结束 $(date +%Y-%m-%d\ %H:%M:%S)"
