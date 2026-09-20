"""拆解一次会话各阶段耗时。

**重要**：证据文件的落盘时间不等于协议事件发生时间。热路径为了不打断
供数节拍，会把证据写入推迟到之后统一落盘（见 saveHotPathAwareEvent）。
2026-09-20 首版工具按证据时间归并，把两条呼叫头算成 13.7 秒，而它们的
实际写出只相隔 0.2 秒——差别来自 141 秒后才落盘的证据。

因此本工具只用 `*_request.bin` 这类**由主机主动写出**的事件做时间轴，
它们是同步落盘的；其余证据仅计数，不参与耗时归并。

用法: python stage_breakdown.py <session.log>
"""
import sys
import re
from datetime import datetime

MARKS = [
    ("hpi_setup", "控制链"), ("hpi_codec", "控制链"),
    ("hpi_vlc", "呼叫头"), ("hpi_term", "终止"),
    ("fullprep_code", "推桩(代码)"), ("fullprep_helper", "推桩(helper)"),
    ("fullprep_meta", "推桩(元数据)"), ("tickhz", "节拍测量"),
    ("privacy", "信道与隐私"), ("channel_", "信道设置"),
    ("power_save", "省电处理"), ("firmware_slice", "固件切片校验"),
    ("relay_", "供数"), ("restore", "恢复"),
]

events = []
for line in open(sys.argv[1], encoding="utf-8", errors="replace"):
    m = re.match(r"(\S+)\s+.*path=(\S+)", line)
    if m:
        events.append((datetime.fromisoformat(m.group(1)), m.group(2)))

if not events:
    print("无事件")
    sys.exit(1)

total = (events[-1][0] - events[0][0]).total_seconds()
print("会话总时长 %.1f 秒，事件 %d 条" % (total, len(events)))
print()


def label_of(name):
    for key, lab in MARKS:
        if key in name:
            return lab
    return None


# 只用主机主动写出的事件做时间轴
timeline = [(t, label_of(n)) for t, n in events
            if n.endswith("_request.bin") or "upload" in n or "memwrite" in n]
timeline = [(t, l) for t, l in timeline if l]

print("%-14s %8s %8s  %s" % ("阶段", "首", "末", "跨度秒"))
spans = {}
for t, lab in timeline:
    if lab not in spans:
        spans[lab] = [t, t]
    else:
        spans[lab][1] = t
for lab, (a, b) in sorted(spans.items(), key=lambda kv: kv[1][0]):
    print("%-14s %8s %8s  %6.1f" % (
        lab, a.strftime("%H:%M:%S"), b.strftime("%H:%M:%S"),
        (b - a).total_seconds()))

print()
print("注：跨度按主机写出事件计；证据落盘时间不可用于耗时判断。")
