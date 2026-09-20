#!/usr/bin/env python3
"""把接收守望期间的多次磁带快照拼接成完整时间轴。

录音存储是小环形缓冲，只保留最近一段。守望在信号存在期间反复读取，
得到若干部分重叠的快照。相邻快照按 27 字节单元对齐、取最大重叠即可
还原原始顺序；语音单元几乎不重复，重叠匹配很可靠。

用法: python stitch_tape.py <宿主捕获目录> <输出.bin>
"""
import sys
import os
import re
import glob

UNIT = 27

cap = os.path.join(sys.argv[1], "device_capture")
out_path = sys.argv[2]

files = glob.glob(os.path.join(cap, "*rx_live_tape_units_*parsed.bin"))


def sample_index(path):
    m = re.search(r"rx_live_tape_units_(\d+)", os.path.basename(path))
    return int(m.group(1)) if m else 0


files.sort(key=sample_index)
print("快照数:", len(files))

snaps = []
for f in files:
    b = open(f, "rb").read()
    if len(b) >= UNIT and len(b) % UNIT == 0:
        snaps.append([b[i:i + UNIT] for i in range(0, len(b), UNIT)])

print("有效快照:", len(snaps), "单元数序列:", [len(s) for s in snaps][:12], "...")

if not snaps:
    print("没有可用快照")
    sys.exit(1)


def merge(acc, new):
    """把 new 接到 acc 后面，按最大重叠对齐；无重叠则整体追加。"""
    best = 0
    limit = min(len(acc), len(new))
    for k in range(limit, 0, -1):
        if acc[-k:] == new[:k]:
            best = k
            break
    return acc + new[best:], best


timeline = snaps[0]
no_overlap = 0
for snap in snaps[1:]:
    timeline, ov = merge(timeline, snap)
    if ov == 0:
        no_overlap += 1

print("拼接后单元数:", len(timeline))
print("无重叠拼接次数:", no_overlap, "（次数多说明快照间有丢失）")
print("时长: %.2f 秒" % (len(timeline) * 0.06))

data = b"".join(timeline)
open(out_path, "wb").write(data)
print("已写出", out_path, len(data), "字节")
