#!/bin/bash
# 跑一个格式候选：新鲜预检 + 主试验，带超时与失败自动恢复。
#
# 历史教训：宿主自带 15 分钟等待，外层再套后台任务时，一次失败要很久才发现；
# 且宿主在会话期间会停用生产专网应用，失败退出时不会恢复，导致下一次预检
# 在生产基线门被拒，形成人工介入的循环。本脚本对两者都做了处理。
#
# 用法: run_candidate.sh <模式名> [单步超时秒数]

set -u
MODE=${1:?用法: run_candidate.sh <模式名> [超时秒]}
STEP_TIMEOUT=${2:-420}

TOOLS=/c/Dev/H13_D22/research/h13_dmr_tx_harness/tools
CAPDIR=/c/Dev/H13_D22/research/h13_radio/captures/$(date +%Y-%m-%d)
SCRIPT=run_h13_dmr_tx_harness_v083_usb.ps1
ADB=/c/Dev/android-sdk/platform-tools/adb.exe
PKG=net.elfradio.h13interphone
ENTRY=$PKG/com.bozhou.interphone.ui.talk.MainActivity

adb_sh() { MSYS_NO_PATHCONV=1 "$ADB" -P 5038 -s 0 shell "$@" 2>&1; }

restore_production() {
    # 生产专网应用重新启用并取回串口。宿主失败退出时不做这件事。
    adb_sh "su -c 'pm enable $PKG >/dev/null 2>&1; am start -n $ENTRY >/dev/null 2>&1'" >/dev/null
    sleep 6
    local owner
    owner=$(adb_sh "su -c 'for p in /proc/[0-9]*; do ls -l \$p/fd 2>/dev/null | grep -q ttyHS0 && cat \$p/cmdline | tr -d \\\\0; done'")
    if [ -n "$owner" ]; then
        echo "  生产基线已恢复：$owner 持有串口"
    else
        echo "  警告：串口仍无人持有，需要人工检查"
    fi
}

run_step() {
    local desc=$1; shift
    echo "=== $desc ==="
    if timeout "$STEP_TIMEOUT" powershell -NoProfile -ExecutionPolicy Bypass \
            -File "$TOOLS/$SCRIPT" "$@" 2>&1 | tail -6; then
        return 0
    fi
    local code=$?
    if [ $code -eq 124 ]; then
        echo "  超时 ${STEP_TIMEOUT} 秒，终止该步"
    else
        echo "  该步失败，退出码 $code"
    fi
    return 1
}

cd "$TOOLS" || exit 1

if ! run_step "新鲜预检" -Mode clear_only -AllowDisableInterphone; then
    restore_production
    exit 1
fi

LATEST=$(ls -dt "$CAPDIR"/new-dmr-tx-harness-clear_only-* 2>/dev/null | head -1)
if [ -z "$LATEST" ]; then
    echo "未找到预检捕获目录"
    restore_production
    exit 1
fi
echo "  预检目录: $(basename "$LATEST")"

if ! run_step "主试验 $MODE" -Mode "$MODE" -AllowDisableInterphone \
        -Setup0Stable -ClearPrecheckCapture "$(cygpath -w "$LATEST")"; then
    restore_production
    echo
    echo "=== 失败时的设备侧会话（供追溯）==="
    adb_sh "su -c 'ls -t /data/data/net.elfradio.h13dmrtx/files/captures | head -3'"
    exit 1
fi

echo
echo "=== 原子结果 ==="
D=$(ls -dt "$CAPDIR"/new-dmr-tx-harness-"$MODE"-* 2>/dev/null | head -1)
if [ -n "$D" ] && [ -f "$D/atomic_result_host.txt" ]; then
    tr -d '\r\357\273\277' < "$D/atomic_result_host.txt" | grep -E \
     'result=|terminal_phase=|^mode=|setup_acks|vlc_acks|data36_written|relay_units_written|relay_credits_consumed|termination_acks|recovery_error_count|reboot_required|failure='
    echo "捕获目录: $(basename "$D")"
else
    echo "未生成原子结果"
fi

# 无论成败都确认生产基线回到位
echo
echo "=== 收尾核验 ==="
restore_production
