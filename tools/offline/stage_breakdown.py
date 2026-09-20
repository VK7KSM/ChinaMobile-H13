"""拆解一次会话各阶段耗时，用于定位九十秒准备时间的真实构成。"""
import sys, re, os
from datetime import datetime

log = sys.argv[1]
# 阶段的判定锚点：证据文件名里的关键词 -> 阶段名
MARKS = [
    ("pre_device_build",        "会话建立"),
    ("text_baseline",           "文本基线校验"),
    ("firmware_slice",          "固件切片校验"),
    ("privacy",                 "信道与隐私设置"),
    ("channel_",                "信道设置"),
    ("power_save",              "省电处理"),
    ("tickhz",                  "节拍测量"),
    ("fullprep_code",           "推桩(代码)"),
    ("fullprep_helper",         "推桩(helper)"),
    ("fullprep_meta",           "推桩(元数据)"),
    ("bridge_arm",              "武装桥"),
    ("hpi_setup",               "控制链"),
    ("hpi_vlc",                 "呼叫头"),
    ("relay_",                  "供数"),
    ("offer_paced",             "供数"),
    ("hpi_term",                "终止"),
    ("restore",                 "恢复"),
]

events = []
for line in open(log, encoding='utf-8', errors='replace'):
    m = re.match(r"(\S+)\s+.*path=(\S+)", line)
    if not m:
        continue
    ts = datetime.fromisoformat(m.group(1))
    events.append((ts, m.group(2)))

if not events:
    print("无事件"); sys.exit(1)

t0 = events[0][0]
total = (events[-1][0] - t0).total_seconds()

def stage_of(name):
    for key, label in MARKS:
        if key in name:
            return label
    return None

# 按阶段归并连续区间
spans = {}
cur = None
cur_start = None
last = t0
for ts, name in events:
    st = stage_of(name)
    if st is None:
        st = cur
    if st != cur:
        if cur is not None and cur_start is not None:
            spans[cur] = spans.get(cur, 0.0) + (last - cur_start).total_seconds()
        cur, cur_start = st, last
    last = ts
if cur is not None and cur_start is not None:
    spans[cur] = spans.get(cur, 0.0) + (last - cur_start).total_seconds()

print("会话总时长 %.1f 秒，事件 %d 条" % (total, len(events)))
print()
print("%-16s %8s %7s" % ("阶段", "秒", "占比"))
for st, sec in sorted(spans.items(), key=lambda kv: -kv[1]):
    if sec < 0.05:
        continue
    print("%-16s %8.1f %6.1f%%" % (st, sec, 100.0*sec/total))
