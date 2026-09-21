#!/system/bin/sh
# 被动监听：只打开串口听，不下任何命令，看模块会不会主动推消息。
PORT=/dev/ttyHS0
BB=/data/adb/magisk/busybox
OUT=/data/local/tmp/at_listen.out
SECS=${1:-25}
$BB stty -F $PORT 57600 raw -echo cs8 -parenb -cstopb 2>/dev/null
: > "$OUT"
$BB timeout "$SECS" cat "$PORT" >> "$OUT"
