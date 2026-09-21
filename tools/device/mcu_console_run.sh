#!/system/bin/sh
# 设备侧执行体，由 mcu_console.sh 推送。读命令清单、逐条下发、收集回显。
# 独立成文件是因为经 adb shell + su 多层引号传带空格的命令极易被吃掉。
PORT=/dev/ttyHS0
BB=/data/adb/magisk/busybox
CMDS=/data/local/tmp/mcu_console.cmds
OUT=/data/local/tmp/mcu_console.out
SECS=${1:-4}

$BB stty -F $PORT 57600 raw -echo cs8 -parenb -cstopb 2>/dev/null
: > "$OUT"
$BB timeout "$SECS" cat "$PORT" >> "$OUT" &
CATPID=$!
$BB sleep 0.3
# 射频看护：整个下发窗里持续采样 pa_enable，任何一次非 0 都记下来。
# 只读命令不该让 PA 开起来；接收类命令更不该。
( i=0; while [ $i -lt 200 ]; do
    v=$(cat /sys/boptt/pa_enable 2>/dev/null)
    [ "$v" != "0" ] && echo "PA_WATCH $v" >> /data/local/tmp/mcu_console.pa
    $BB sleep 0.1; i=$((i+1))
  done ) &
PAPID=$!
: > /data/local/tmp/mcu_console.pa
while IFS= read -r c; do
    [ -z "$c" ] && continue
    printf '%s\r\n' "$c" > "$PORT"
    $BB sleep 0.4
done < "$CMDS"
wait $CATPID 2>/dev/null
kill $PAPID 2>/dev/null
