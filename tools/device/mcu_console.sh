#!/bin/bash
# MCU 调试控制台客户端（只读命令白名单）。
#
# 背景：0.3.66 固件自带一个约一百条命令的工厂/调试控制台，就挂在
# /dev/ttyHS0 上、与 AT 命令共用同一条串口——宿主一直在用的
# memread/memwrite1/memwrite4 就是其中三条。既然通道已经证实可用，
# 其余命令同样可达。本脚本把这件事做成可复用的组件。
#
# 回显默认是关的，必须先下 `print 1`（沿用 v223 探针的做法）。
#
# 安全约束：只允许只读命令。控制台里有 pttctrl / rfswon / txvcovccon /
# nanderaseblock / writeeeprom / sct3258dspupdate 这类会开射频或写非易失
# 存储的命令，一律不放进白名单——开射频必须有操作者在场。
#
# 用法:
#   mcu_console.sh version gettick hobibstat
#   mcu_console.sh --keep-port version      # 不恢复生产应用（连续测试用）
#   mcu_console.sh --list                   # 列出白名单
#   READ_SECONDS=8 mcu_console.sh ...       # 加长收集窗

set -u

ADB=${ADB:-/c/Dev/android-sdk/platform-tools/adb.exe}
ADB_PORT=${ADB_PORT:-5038}
ADB_SERIAL=${ADB_SERIAL:-0}
PKG=net.elfradio.h13interphone
ENTRY=$PKG/com.bozhou.interphone.ui.talk.MainActivity
READ_SECONDS=${READ_SECONDS:-5}
HERE=$(cd "$(dirname "$0")" && pwd)

# 白名单必须与固件导出表里的「只读」一类一致。
# tools/offline/check_console_whitelist.py 会在离线自检里核对这一点，
# 防止手写名单与实际固件行为悄悄走偏。
WHITELIST="print version gettick showsyscfg showtestpara getslotint sct3258read
sct3258read1 sct3258prostr sct3258hwver sct3258swver sct3258cidsn
nandlistbadblock getsm getsleepstat readreg17val showcurrsq hobibstat
getchandcnt sct3258getoobe memread ramlog read2571 get2571lockflag
nandreadmain nandreadspare nandgetfeature readeeprom"

adb_sh() { MSYS_NO_PATHCONV=1 "$ADB" -P "$ADB_PORT" -s "$ADB_SERIAL" shell "$@" 2>&1; }

if [ "${1:-}" = "--list" ]; then
    echo "只读白名单："; for c in $WHITELIST; do echo "  $c"; done; exit 0
fi

KEEP_PORT=0
ALLOW_RX=0
while true; do
    case "${1:-}" in
        --keep-port) KEEP_PORT=1; shift;;
        # 接收类命令让模块进入接收态。它们不开 PA、不发射，但确实改变
        # 模块状态，所以要显式要求，不混在只读白名单里。
        --rx) ALLOW_RX=1; shift;;
        *) break;;
    esac
done
RX_LIST="sct3258enterrx sct3258rxstart sct3258rxstop sct3258initcfg"
[ $ALLOW_RX -eq 1 ] && WHITELIST="$WHITELIST $RX_LIST"

[ $# -ge 1 ] || { echo "用法: mcu_console.sh [--keep-port] <命令> [命令...]" >&2; exit 2; }

for cmd in "$@"; do
    name=${cmd%% *}
    ok=0
    for w in $WHITELIST; do [ "$name" = "$w" ] && ok=1; done
    if [ $ok -eq 0 ]; then
        echo "拒绝：'$name' 不在只读白名单里。会开射频或写非易失存储的命令" >&2
        echo "      必须有操作者在场，本脚本不代劳。用 --list 看白名单。" >&2
        exit 3
    fi
done

restore_production() {
    [ $KEEP_PORT -eq 1 ] && { echo "（--keep-port：未恢复生产应用）"; return; }
    adb_sh "su -c 'pm enable $PKG >/dev/null 2>&1; am start -n $ENTRY >/dev/null 2>&1'" >/dev/null
    sleep 5
    local owner
    owner=$(adb_sh "su -c 'for p in /proc/[0-9]*; do ls -l \$p/fd 2>/dev/null | grep -q ttyHS0 && cat \$p/cmdline | tr -d \\0; done'" | tr -d '\0')
    if [ -n "$owner" ]; then echo "生产基线已恢复：$owner 持有串口"
    else echo "警告：串口仍无人持有，需要人工检查"; fi
}

pa=$(adb_sh "su -c 'cat /sys/boptt/pa_enable 2>/dev/null'" | tr -d '\r\n')
echo "开始前 pa_enable=${pa:-未知}"

# 命令清单：回显开关始终排在最前
TMP=$(mktemp)
echo "print 1" > "$TMP"
for cmd in "$@"; do echo "$cmd" >> "$TMP"; done
# adb 是 Windows 程序，本地路径必须给 Windows 形式；MSYS_NO_PATHCONV 只挡住
# 远端路径被改写，不会帮本地路径转换。
MSYS_NO_PATHCONV=1 "$ADB" -P "$ADB_PORT" -s "$ADB_SERIAL" push "$(cygpath -w "$TMP")" /data/local/tmp/mcu_console.cmds >/dev/null
MSYS_NO_PATHCONV=1 "$ADB" -P "$ADB_PORT" -s "$ADB_SERIAL" push "$(cygpath -w "$HERE/mcu_console_run.sh")" /data/local/tmp/mcu_console_run.sh >/dev/null
rm -f "$TMP"

echo "释放串口…"
adb_sh "su -c 'pm disable $PKG >/dev/null 2>&1'" >/dev/null
sleep 3
owner=$(adb_sh "su -c 'for p in /proc/[0-9]*; do ls -l \$p/fd 2>/dev/null | grep -q ttyHS0 && cat \$p/cmdline | tr -d \\0; done'" | tr -d '\0')
if [ -n "$owner" ]; then
    echo "串口仍被 $owner 持有，放弃" >&2
    restore_production; exit 4
fi
echo "串口已空闲；下发 $(( $# + 1 )) 条命令，收集窗 ${READ_SECONDS} 秒"

adb_sh "su -c 'sh /data/local/tmp/mcu_console_run.sh $READ_SECONDS'" >/dev/null
out=$(adb_sh "su -c 'cat /data/local/tmp/mcu_console.out'")
pahit=$(adb_sh "su -c 'cat /data/local/tmp/mcu_console.pa 2>/dev/null'" | tr -d '
')
if [ -n "$pahit" ]; then
    echo "!!! 下发期间 PA 曾被打开：$pahit" >&2
fi
echo "=== 回显 ==="
printf '%s\n' "$out" | tr '\r' '\n' | sed '/^[[:space:]]*$/d' | sed 's/^/  /'

restore_production
pa=$(adb_sh "su -c 'cat /sys/boptt/pa_enable 2>/dev/null'" | tr -d '\r\n')
echo "结束后 pa_enable=${pa:-未知}"
