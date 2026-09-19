#!/usr/bin/env python3
"""比对模块交出的帧与我们回送的帧。

判定"替换有没有被模块采纳"：
  若模块在我们开始替换之后交出的帧逐渐变成我们送过的内容，说明采纳；
  若始终是它自己的编码（与我们送的毫不相干），说明没有采纳。

用法: python compare_offers_vs_sent.py <宿主捕获目录> <素材文件>
"""
import sys
import os
import glob

cap = sys.argv[1]
asset = open(sys.argv[2], "rb").read()
units = [asset[i:i + 27] for i in range(0, len(asset), 27)]
unit_set = {u: i for i, u in enumerate(units)}

d = os.path.join(cap, "device_capture")


def scan(stream):
    """容错扫描，返回 (包类型, 正文) 列表。"""
    out = []
    i = 0
    while i + 6 <= len(stream):
        if stream[i:i + 3] != b"\x84\xa9\x61":
            i += 1
            continue
        length = (stream[i + 3] << 8) | stream[i + 4]
        ptype = stream[i + 5]
        if i + 6 + length > len(stream):
            break
        out.append((ptype, stream[i + 6:i + 6 + length]))
        wire = 6 + length
        i += wire + (wire & 1)
    return out


raw_files = sorted(glob.glob(os.path.join(d, "*offer_paced_uplink_raw*")))
if not raw_files:
    print("未找到 offer_paced_uplink_raw")
    sys.exit(1)

stream = b"".join(open(f, "rb").read() for f in raw_files)
print("上行原始流 %d 字节" % len(stream))

offers = []
for ptype, body in scan(stream):
    if ptype == 0x20 and len(body) >= 3 and body[0] <= 1 and body[1] == len(body) - 2:
        offers.append(body[2:2 + body[1]])

print("模块交帧 %d 条，其中 27 字节 %d 条" %
      (len(offers), sum(1 for o in offers if len(o) == 27)))

matched = 0
first_match = None
for idx, o in enumerate(offers):
    if o in unit_set:
        matched += 1
        if first_match is None:
            first_match = (idx, unit_set[o])
print("与素材单元逐字节相同的交帧: %d" % matched)
if first_match:
    print("  首次命中：第 %d 条交帧 == 素材单元 %d" % first_match)

print()
print("前 8 条交帧（十六进制，前 18 字节）：")
for o in offers[:8]:
    print("   ", o[:18].hex())
print()
print("素材前 4 个单元（前 18 字节）：")
for u in units[:4]:
    print("   ", u[:18].hex())

# 交帧内容是否重复（静音编码通常高度重复）
uniq = len(set(offers))
print()
print("交帧去重后 %d 条 / 共 %d 条" % (uniq, len(offers)))
