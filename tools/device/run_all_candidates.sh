#!/bin/bash
# 依次运行三个语音格式候选并汇总结果，用于横向对照。
#
# 三者的唯一差别是语音单元的线上格式，正文、节拍、会话顺序完全相同。
# 每个候选之间完整恢复生产基线，不复用上一次的现场。
#
# 用法: run_all_candidates.sh [单步超时秒数]

set -u
# 与run_candidate.sh的默认值保持一致，见那边的说明。
STEP_TIMEOUT=${1:-700}
HERE=$(cd "$(dirname "$0")" && pwd)
ADB=/c/Dev/android-sdk/platform-tools/adb.exe
CAPROOT=/c/Dev/H13_D22/research/h13_radio/captures

MODES=(
    speech_az09_chan_d27_type3_no_rf
    speech_az09_chan_d27_type0_no_rf
    speech_az09_digc_frame_no_rf
)
DESC=(
    "候选一 字段01 长度27 包类型3"
    "候选二 字段01 长度27 包类型0（与模块上报仅差读写位）"
    "候选三 字段43 帧属性10 包类型5"
)

declare -a RESULTS

for i in "${!MODES[@]}"; do
    mode=${MODES[$i]}
    echo
    echo "##############################################################"
    echo "# ${DESC[$i]}"
    echo "##############################################################"

    bash "$HERE/run_candidate.sh" "$mode" "$STEP_TIMEOUT"

    # 从设备侧原子结果取终态，那是会话自身产生的完成证据
    session=$(MSYS_NO_PATHCONV=1 "$ADB" -P 5038 -s 0 shell \
        "su -c 'ls -t /data/data/net.elfradio.h13dmrtx/files/captures 2>/dev/null | grep relay_no_rf | head -1'" \
        2>/dev/null | tr -d '\r')
    if [ -n "$session" ]; then
        summary=$(MSYS_NO_PATHCONV=1 "$ADB" -P 5038 -s 0 shell \
            "su -c 'grep -hE \"^result=|relay_units_written=|termination_acks=|relay_credits_consumed=|failure=\" /data/data/net.elfradio.h13dmrtx/files/captures/$session/atomic_result.txt 2>/dev/null'" \
            2>/dev/null | tr -d '\r' | tr '\n' ' ')
        RESULTS[$i]="$summary"
    else
        RESULTS[$i]="(未产生会话)"
    fi
    echo "  终态: ${RESULTS[$i]}"
done

echo
echo "##############################################################"
echo "# 三候选横向对照"
echo "##############################################################"
for i in "${!MODES[@]}"; do
    echo
    echo "${DESC[$i]}"
    echo "  ${RESULTS[$i]}"
done
echo
echo "说明：供数成功只证明接口层接受，不证明模块把写入内容当作语音处理。"
echo "      哪个格式正确需要空口验证，本批次不发射。"
