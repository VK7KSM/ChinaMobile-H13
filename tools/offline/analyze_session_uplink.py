#!/usr/bin/env python3
"""会话上行证据一览：给定宿主捕获目录，回答四个问题。

1. PROCESS_MODE(2) 的确认是否为 1a 00
2. VLC 之后是否仍出现 17 0a（呼叫结束事件），出现在哪几条之后
3. 模块是否仍自行上报 27 字节语音单元（DSP 内部输入编码）
4. 是否出现符合厂商信用签名的短帧（字段 0x00/0x01、正文 1~2 字节）

用法: python analyze_session.py <宿主捕获目录>
"""
import sys, os, glob, collections

D = os.path.join(sys.argv[1], "device_capture")
files = sorted(glob.glob(os.path.join(D, "*")))

def frames_in(path):
    b = open(path, "rb").read(); i = 0
    while i + 6 <= len(b):
        if b[i:i+3] != b"\x84\xa9\x61":
            i += 1; continue
        L = (b[i+3] << 8) | b[i+4]; t = b[i+5]; body = b[i+6:i+6+L]
        yield t, body
        i += 6 + L + ((6 + L) & 1)

def is_uplink(n):
    return "request" not in n and any(k in n for k in
        ("predrain", "_late", "_carry", "active_tail_raw", "_primary", "relay_read"))

print("== 1. PROCESS_MODE(2) 请求与确认 ==")
for f in files:
    n = os.path.basename(f)
    if "codec_0_request" in n:
        print("  请求:", open(f, "rb").read().hex())
    if "codec_0_primary" in n:
        print("  确认:", open(f, "rb").read().hex())

print("\n== 2. 17 0a（呼叫结束）出现位置 ==")
seen = []
for f in files:
    n = os.path.basename(f)
    if not is_uplink(n): continue
    for t, body in frames_in(f):
        if body[:1] == b"\x17" and len(body) >= 2:
            seen.append((n, t, body.hex()))
if seen:
    for n, t, h in seen: print(f"  {n[:50]:50s} type=0x{t:02x} {h}")
else:
    print("  无")

print("\n== 3. 模块自行上报的 27 字节单元 ==")
cnt = 0
for f in files:
    n = os.path.basename(f)
    if not is_uplink(n): continue
    for t, body in frames_in(f):
        if body[:1] == b"\x01" and len(body) == 29:
            cnt += 1
            if cnt <= 3: print(f"  {n[:50]:50s} type=0x{t:02x} {body.hex()[:40]}…")
print(f"  共 {cnt} 帧")

print("\n== 4. 厂商信用签名短帧（字段 00/01，正文 1~2 字节）==")
cred = collections.Counter(); ex = []
for f in files:
    n = os.path.basename(f)
    if not is_uplink(n): continue
    for t, body in frames_in(f):
        if body and body[0] in (0, 1) and 1 <= len(body) <= 2:
            cred[(t, body.hex())] += 1
            if len(ex) < 5: ex.append((n, t, body.hex()))
if cred:
    for (t, h), c in cred.most_common(): print(f"  type=0x{t:02x} body={h} × {c}")
    for n, t, h in ex: print(f"    例: {n[:50]}")
else:
    print("  无")

print("\n== 5. 全部上行帧按 (包类型, 字段, 长度) 汇总 ==")
c = collections.Counter()
for f in files:
    n = os.path.basename(f)
    if not is_uplink(n): continue
    for t, body in frames_in(f):
        c[(t, body[0] if body else None, len(body))] += 1
for (t, fld, L), v in sorted(c.items(), key=lambda x: -x[1])[:16]:
    print(f"  type=0x{t:02x} field={('0x%02x' % fld) if fld is not None else '-':5s} len={L:3d} : {v}")
