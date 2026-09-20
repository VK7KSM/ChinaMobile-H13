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
# v0.86首次跑通480个单元的完整会话，主机侧要拉6800+个证据文件，
# 全程约475秒——贴着旧的420秒默认值，收尾打印阶段被外层timeout掐掉，
# 会话其实已经PASS（result=PASS/relay_units_written=480/failure=空）。
# 默认值改大留出余量，避免以后的成功会话被误判成超时失败。
STEP_TIMEOUT=${2:-700}

TOOLS=/c/Dev/H13_D22/research/h13_dmr_tx_harness/tools
CAPDIR=/c/Dev/H13_D22/research/h13_radio/captures/$(date +%Y-%m-%d)
SCRIPT=run_h13_dmr_tx_harness_v087_usb.ps1
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
    # 管道到 tail 时 $? 取到的是 tail 的退出码，会把宿主的失败吞掉。
    # 必须用 PIPESTATUS 取管道第一段的真实退出码。
    timeout "$STEP_TIMEOUT" powershell -NoProfile -ExecutionPolicy Bypass \
            -File "$TOOLS/$SCRIPT" "$@" 2>&1 | tail -8
    local code=${PIPESTATUS[0]}
    if [ "$code" -eq 0 ]; then
        return 0
    fi
    if [ "$code" -eq 124 ]; then
        echo "  超时 ${STEP_TIMEOUT} 秒，终止该步"
    else
        echo "  该步失败，退出码 $code"
    fi
    return 1
}

# 开跑前确认生产基线到位。应用重新启动后取回串口需要时间，
# 过早启动预检会在生产基线门被拒。这里等待而不是假定。
wait_production() {
    # 宿主要求串口唯一所有者。仅确认生产应用在列是不够的：
    # 上次会话遗留的探针进程可能仍持有串口，形成两个所有者而被基线门拒绝。
    local i count
    for i in $(seq 1 20); do
        count=$(adb_sh "su -c 'for p in /proc/[0-9]*; do ls -l \$p/fd 2>/dev/null | grep -q ttyHS0 && echo X; done'" | grep -c X)
        if [ "$count" -eq 1 ]; then
            echo "  生产基线就绪：串口唯一所有者"
            return 0
        elif [ "$count" -gt 1 ]; then
            echo "  串口有 $count 个所有者，停止遗留探针"
            adb_sh "su -c 'am force-stop net.elfradio.h13dmrtx'" >/dev/null
        else
            restore_production >/dev/null
        fi
        sleep 3
    done
    echo "  生产基线未在预期时间内就绪，终止"
    return 1
}

cd "$TOOLS" || exit 1

echo "=== 开跑前基线确认 ==="
wait_production || exit 1

if ! run_step "新鲜预检" -Mode clear_only -AllowDisableInterphone -AllowInstall; then
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

# -AllowPotentialRf 只是宿主的"潜在发射路径"门禁，本身不会让设备发射：
# 真正发射还要求模式名在 requestsLowPowerRf() 里，并且宿主额外下发
# rf_permission。无射频模式带上这个开关只是为了通过门禁。
# MIC_PATH_ON=1：主试验全程接通 H13 麦克风通路（audio_switch=1），并每 2 秒记录
# 实际值到捕获目录，供核对射频窗内麦克风是否真的接通。结束后无论成败都复位。
EXTRA=()
MICLOG=""
if [ "${MIC_PATH_ON:-0}" = 1 ]; then
    EXTRA=(-MicPathOn)
    MICLOG="$CAPDIR/mic_path_log_$(date +%Y%m%d_%H%M%S).txt"
    ( while :; do echo "$(date +%T) audio_switch=$(adb_sh "su -c 'cat /sys/boptt/audio_switch'" | tr -d '\r')"; sleep 2; done ) > "$MICLOG" 2>&1 &
    MICLOG_PID=$!
fi
STEP_OK=0
if run_step "主试验 $MODE" -Mode "$MODE" -AllowDisableInterphone \
        -AllowPotentialRf "${EXTRA[@]}" \
        -Setup0Stable -ClearPrecheckCapture "$(cygpath -w "$LATEST")"; then
    STEP_OK=1
fi
if [ "${MIC_PATH_ON:-0}" = 1 ]; then
    kill "$MICLOG_PID" 2>/dev/null
    adb_sh "su -c 'echo 0 > /sys/boptt/audio_switch'" >/dev/null
    echo "  麦克风通路已复位: audio_switch=$(adb_sh "su -c 'cat /sys/boptt/audio_switch'" | tr -d '\r')  记录: $(basename "$MICLOG")"
fi
if [ "$STEP_OK" != 1 ]; then
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

# 判据自动核对：审计器逐条检查已确立的不变量。手工跑等于没跑——
# 十九次发射全部发在加密信道上，正是因为检查存在却没人执行（2.8.71/2.9.29）。
if [ -n "$D" ] && [ -d "$D/device_capture" ]; then
    echo
    echo "=== 判据自动核对 ==="
    python "$(dirname "$0")/../offline/audit_capture.py" "$D" ||         echo "审计不通过，详见上方条目"
fi

# 无论成败都确认生产基线回到位
echo
echo "=== 收尾核验 ==="
restore_production
