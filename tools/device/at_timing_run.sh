#!/system/bin/sh
# 设备侧执行体：逐条下发 AT 命令并计时。由 at_timing.sh 推送。
PORT=/dev/ttyHS0
BB=/data/adb/magisk/busybox
OUT=/data/local/tmp/at_timing.out
ROUNDS=${1:-3}

$BB stty -F $PORT 57600 raw -echo cs8 -parenb -cstopb 2>/dev/null
: > "$OUT"
: > /data/local/tmp/at_timing.pa
( i=0; while [ $i -lt 600 ]; do
    v=$(cat /sys/boptt/pa_enable 2>/dev/null)
    [ "$v" != "0" ] && echo "PA $v" >> /data/local/tmp/at_timing.pa
    $BB sleep 0.1; i=$((i+1))
  done ) &
PAPID=$!

now_ms() { $BB date +%s%3N; }

# 同一条命令连发两次：第二次反映"已在该信道"的代价
CH1='AT+DMOSETDIGITALCH=433550000,433550000,13,99,12345678,directmode,group,slot1,slot1,off,low,8,2,2,99'
CH2='AT+DMOSETDIGITALCH=433550000,433550000,13,88,12345678,directmode,group,slot1,slot1,off,low,8,2,2,88'

send_timed() {
    label=$1; cmd=$2
    $BB timeout 3 cat "$PORT" > /data/local/tmp/at_rx.tmp &
    CATPID=$!
    $BB sleep 0.15
    t0=$(now_ms)
    printf '%s\r\n' "$cmd" > "$PORT"
    # 轮询到出现回应为止，最多 2.5 秒
    j=0
    while [ $j -lt 250 ]; do
        if [ -s /data/local/tmp/at_rx.tmp ]; then break; fi
        $BB sleep 0.01; j=$((j+1))
    done
    t1=$(now_ms)
    wait $CATPID 2>/dev/null
    rsp=$($BB tr -d '\r\n' < /data/local/tmp/at_rx.tmp)
    echo "$label $((t1-t0)) $rsp" >> "$OUT"
}

r=0
while [ $r -lt $ROUNDS ]; do
    send_timed "connect" "AT+DMOCONNECT"
    send_timed "version" "AT+DMOGETSOFTVERSION"
    send_timed "rxinfo" "AT+DMOGETDIGITALRXINFO"
    send_timed "setch_tg99" "$CH1"
    send_timed "setch_tg99_again" "$CH1"
    send_timed "setch_tg88" "$CH2"
    send_timed "setch_tg99_back" "$CH1"
    r=$((r+1))
done
kill $PAPID 2>/dev/null
rm -f /data/local/tmp/at_rx.tmp
