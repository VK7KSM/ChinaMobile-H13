#!/usr/bin/env python3
"""核对「剥信道编码」组件：能否还原那次成功发射真正送出的载荷。

网关方向这一步是必须的：网络上传的是**已做信道编码的空口帧**，而模块要的
是**未编码的 49 位参数**（H13_new.md 2.8.83）。转换做错，空口上就是人声
轮廓但不可懂——这正是十九次失败发射的样子，光看「有没有声音」分辨不出来。

因此锚点必须是有空口结果背书的那一对文件，而不是随便造一组数据：

    captures_ref/2026-09-20-raw49/asset_air_input.bin   输入（空口表示）
    captures_ref/2026-09-20-raw49/asset_raw49.bin       输出（49 位参数）

后者就是 2.8.83 那次发射送给模块的载荷，对端第一次听清了内容。

核对两件事：
  1. 冻结输出的每个 9 字节帧低 23 位为零——「49 位左对齐」的可验证形式
  2. 用组件从冻结输入重算，与冻结输出逐字节相同

第 2 条依赖 `research/h13_radio/tools/bin/chan_d_to_wav.exe`，不在本仓库。
拿不到解码器时跳过该条并说明，不当作通过。

用法：
    python check_param_packing.py
"""
from __future__ import annotations

import hashlib
import subprocess
import sys
import tempfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
REF = ROOT / "captures_ref" / "2026-09-20-raw49"
AIR = REF / "asset_air_input.bin"
RAW49 = REF / "asset_raw49.bin"
TOOL = HERE / "chan_d_to_params.py"
DECODER = (ROOT.parents[0] / "h13_radio" / "tools" / "bin"
           / "chan_d_to_wav.exe")

FRAME_BYTES = 9
PARAM_BITS = 49
TAIL_MASK = (1 << (FRAME_BYTES * 8 - PARAM_BITS)) - 1
EXPECT_RAW49 = "eac18a5a64e2b52e"
EXPECT_AIR = "c3c688aaf757e6de"


def sha16(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()[:16]


def main() -> int:
    bad = 0

    def report(ok: bool, name: str, detail: str) -> None:
        nonlocal bad
        if not ok:
            bad += 1
        print("   %-6s %-22s %s" % ("通过  " if ok else "不通过 ", name, detail))

    if not AIR.exists() or not RAW49.exists():
        print("   不通过  锚点文件缺失          %s" % REF)
        return 1

    air = AIR.read_bytes()
    raw49 = RAW49.read_bytes()
    report(sha16(air) == EXPECT_AIR and sha16(raw49) == EXPECT_RAW49,
           "锚点文件未被改动",
           "输入 %s 输出 %s" % (sha16(air), sha16(raw49)))

    frames = len(raw49) // FRAME_BYTES
    tail_bad = sum(1 for i in range(0, len(raw49), FRAME_BYTES)
                   if int.from_bytes(raw49[i:i + FRAME_BYTES], "big")
                   & TAIL_MASK)
    report(tail_bad == 0, "输出确为49位左对齐",
           "低 %d 位非零的帧 %d / %d"
           % (FRAME_BYTES * 8 - PARAM_BITS, tail_bad, frames))

    # 空口输入不该满足同一条不变量，否则说明两个文件其实是同一种表示
    air_tail_bad = sum(1 for i in range(0, len(air), FRAME_BYTES)
                       if int.from_bytes(air[i:i + FRAME_BYTES], "big")
                       & TAIL_MASK)
    report(air_tail_bad == len(air) // FRAME_BYTES, "输入确实不是参数表示",
           "低位非零的帧 %d / %d" % (air_tail_bad, len(air) // FRAME_BYTES))

    if not DECODER.exists():
        print("   跳过   组件重算               找不到解码器 %s" % DECODER)
        print("          （该条未通过也未失败，不可当作组件已验证）")
        return 1 if bad else 0

    with tempfile.TemporaryDirectory() as tmp:
        out = Path(tmp) / "derived.bin"
        proc = subprocess.run(
            [sys.executable, str(TOOL), "--input", str(AIR),
             "--output", str(out)],
            capture_output=True, text=True, timeout=300)
        if proc.returncode != 0 or not out.exists():
            report(False, "组件重算",
                   "转换失败：%s" % (proc.stderr.strip() or proc.stdout.strip()))
        else:
            got = out.read_bytes()
            same = got == raw49
            report(same, "组件重算与冻结一致",
                   "重算 %s%s" % (sha16(got),
                                  "" if same else "，与冻结不同"))
    return 1 if bad else 0


if __name__ == "__main__":
    raise SystemExit(main())
