#!/usr/bin/env python3
"""按厂商 SCT3252T 的上行分类规则统计一次会话的上行帧。

厂商规则（PacketHeaderLength=6，即 84 a9 61 + 长度2 + 类型1）：
  正文首字节 0/1 且正文长 1~2        -> 信用/响应帧（该发下一帧了）
  类型 0x20 且正文长 2+36=38         -> 发射侧待改写语音帧（入 tx_savedata）
  类型 0x30 且正文长 38              -> 接收侧语音帧

用法: python scan_relay_frames.py <宿主捕获目录>
"""
import sys
import os
import glob
import collections

D = os.path.join(sys.argv[1], "device_capture")
files = sorted(glob.glob(os.path.join(D, "*")))


def frames_in(path):
    b = open(path, "rb").read()
    i = 0
    while i + 6 <= len(b):
        if b[i:i + 3] != b"\x84\xa9\x61":
            i += 1
            continue
        L = (b[i + 3] << 8) | b[i + 4]
        t = b[i + 5]
        body = b[i + 6:i + 6 + L]
        yield t, body
        i += 6 + L + ((6 + L) & 1)


def is_uplink(n):
    if "request" in n:
        return False
    return any(k in n for k in
               ("predrain", "_late", "_carry", "active_tail_raw",
                "_primary", "relay_read", "_matched"))


credit = []
tx_relay = []
rx_relay = []
other = collections.Counter()

for f in files:
    n = os.path.basename(f)
    if not is_uplink(n):
        continue
    for t, body in frames_in(f):
        if body and body[0] in (0, 1) and 1 <= len(body) <= 2:
            credit.append((n, t, body.hex()))
        elif t == 0x20 and len(body) >= 3 and body[0] <= 1 and body[1] == len(body) - 2:
            tx_relay.append((n, body))
        elif t == 0x30 and len(body) >= 3 and body[0] <= 1 and body[1] == len(body) - 2:
            rx_relay.append((n, body))
        else:
            other[(t, body[0] if body else None, len(body))] += 1

print("信用/响应帧（正文首字节0/1、长1~2）:", len(credit))
for n, t, h in credit[:8]:
    print("   ", n[:56], "type=0x%02x" % t, h)

print("\n发射侧待改写语音帧（类型0x20、正文=2+长度字节）:", len(tx_relay))
for n, body in tx_relay[:5]:
    print("   ", n[:56], body.hex()[:56], "…")

print("\n接收侧语音帧（类型0x30、正文=2+长度字节）:", len(rx_relay))
for n, body in rx_relay[:3]:
    print("   ", n[:56], body.hex()[:56], "…")

print("\n其余上行帧 (类型, 首字节, 正文长) 前 14 项:")
for (t, fld, L), v in sorted(other.items(), key=lambda x: -x[1])[:14]:
    f = ("0x%02x" % fld) if fld is not None else "-"
    print("    type=0x%02x field=%-5s len=%-4d : %d" % (t, f, L, v))
