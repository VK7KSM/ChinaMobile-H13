#!/bin/bash
# 测量原厂 AT 命令的往返耗时，尤其是信道/TG 切换。
#
# 为什么要测：热点要跟随网络的 TG 变化，就得知道"只改信道不重走完整
# 初始化"要多少毫秒、模块认不认。2.9.4 把这一项标为 [建]，此前没有数。
#
# 零射频：这里只下查询与信道设置，不下 PTT，不开 PA。整个窗内持续
# 采样 /sys/boptt/pa_enable。
#
# 用法: at_timing.sh [重复次数]

set -u
ADB=${ADB:-/c/Dev/android-sdk/platform-tools/adb.exe}
ADB_PORT=${ADB_PORT:-5038}
ADB_SERIAL=${ADB_SERIAL:-0}
PKG=net.elfradio.h13interphone
ENTRY=$PKG/com.bozhou.interphone.ui.talk.MainActivity
HERE=$(cd "$(dirname "$0")" && pwd)
ROUNDS=${1:-3}

adb_sh() { MSYS_NO_PATHCONV=1 "$ADB" -P "$ADB_PORT" -s "$ADB_SERIAL" shell "$@" 2>&1; }

restore_production() {
    adb_sh "su -c 'pm enable $PKG >/dev/null 2>&1; am start -n $ENTRY >/dev/null 2>&1'" >/dev/null
    sleep 5
    local owner
    owner=$(adb_sh "su -c 'for p in /proc/[0-9]*; do ls -l \$p/fd 2>/dev/null | grep -q ttyHS0 && cat \$p/cmdline | tr -d \\0; done'" | tr -d '\0')
    [ -n "$owner" ] && echo "生产基线已恢复：$owner 持有串口" \
                    || echo "警告：串口仍无人持有，需要人工检查"
}

pa=$(adb_sh "su -c 'cat /sys/boptt/pa_enable 2>/dev/null'" | tr -d '\r\n')
echo "开始前 pa_enable=${pa:-未知}"

MSYS_NO_PATHCONV=1 "$ADB" -P "$ADB_PORT" -s "$ADB_SERIAL" \
    push "$(cygpath -w "$HERE/at_timing_run.sh")" /data/local/tmp/at_timing_run.sh >/dev/null

echo "释放串口…"
adb_sh "su -c 'pm disable $PKG >/dev/null 2>&1'" >/dev/null
sleep 3
owner=$(adb_sh "su -c 'for p in /proc/[0-9]*; do ls -l \$p/fd 2>/dev/null | grep -q ttyHS0 && cat \$p/cmdline | tr -d \\0; done'" | tr -d '\0')
if [ -n "$owner" ]; then
    echo "串口仍被 $owner 持有，放弃" >&2
    restore_production; exit 4
fi
echo "串口已空闲，跑 $ROUNDS 轮"

adb_sh "su -c 'sh /data/local/tmp/at_timing_run.sh $ROUNDS'" >/dev/null
out=$(adb_sh "su -c 'cat /data/local/tmp/at_timing.out'")
echo "=== 逐条耗时（毫秒）==="
printf '%s\n' "$out" | tr -d '\r' | sed '/^[[:space:]]*$/d'

pahit=$(adb_sh "su -c 'cat /data/local/tmp/at_timing.pa 2>/dev/null'" | tr -d '\r\n')
[ -n "$pahit" ] && echo "!!! 期间 PA 曾被打开：$pahit" >&2

restore_production
pa=$(adb_sh "su -c 'cat /sys/boptt/pa_enable 2>/dev/null'" | tr -d '\r\n')
echo "结束后 pa_enable=${pa:-未知}"
