#!/usr/bin/env python3
"""剥信道编码：把空口 CHAN_D 单元转成模块要的未编码 49 位参数单元。

为什么需要这个组件：2026-09-20 第二十次发射确立，模块收到宿主送来的语音帧
后会**自己做信道编码**，因此宿主必须送未经编码的 49 位 AMBE 参数，而不是
已完成 Golay/Hamming 编码并交织的空口帧（H13_new.md 2.8.83）。

网关场景下这一步是必须的：BrandMeister 网络上传输的是**已编码空口帧**，
而模块要的是**未编码参数**，二者不能原样直通（2.9.4）。

转换本身是确定性的：用已校准的解码器取出每帧 49 位参数，左对齐填入 9 字节
（余 23 位置零），三帧凑成一个 27 字节单元。

依赖 `research/h13_radio/tools/bin/chan_d_to_wav.exe` 输出的 ambe49 列。

用法：
    python chan_d_to_params.py --input <空口.bin> --output <参数.bin>
    python chan_d_to_params.py --input a.bin --output b.bin --verify
"""
from __future__ import annotations

import argparse
import subprocess
import tempfile
from pathlib import Path

UNIT_BYTES = 27
FRAME_BYTES = 9
PARAM_BITS = 49

DECODER = (Path(__file__).resolve().parents[3]
           / "h13_radio" / "tools" / "bin" / "chan_d_to_wav.exe")


def decode_params(path: Path) -> list[int]:
    """调用已校准的解码器，取出每帧的 49 位参数。"""
    with tempfile.TemporaryDirectory() as tmp:
        wav = Path(tmp) / "out.wav"
        proc = subprocess.run([str(DECODER), str(path), str(wav)],
                              capture_output=True, text=True)
        rows = []
        for line in proc.stdout.splitlines():
            parts = line.strip().split(",")
            if len(parts) >= 4 and parts[0].isdigit():
                rows.append(int(parts[3], 16))
        return rows


def pack_params(values: list[int]) -> bytes:
    """49 位参数按 MSB 在前左对齐填入 9 字节，余 23 位置零。"""
    out = bytearray()
    for v in values:
        bits = [(v >> (PARAM_BITS - 1 - i)) & 1 for i in range(PARAM_BITS)]
        bits += [0] * (FRAME_BYTES * 8 - PARAM_BITS)
        frame = bytearray(FRAME_BYTES)
        for i, bit in enumerate(bits):
            if bit:
                frame[i >> 3] |= 1 << (7 - (i & 7))
        out += frame
    return bytes(out)


def unpack_params(data: bytes) -> list[int]:
    """pack_params 的逆运算，用于自检。"""
    values = []
    for off in range(0, len(data) - FRAME_BYTES + 1, FRAME_BYTES):
        frame = data[off:off + FRAME_BYTES]
        v = 0
        for i in range(PARAM_BITS):
            bit = (frame[i >> 3] >> (7 - (i & 7))) & 1
            v = (v << 1) | bit
        values.append(v)
    return values


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--input", required=True)
    ap.add_argument("--output", required=True)
    ap.add_argument("--verify", action="store_true",
                    help="回解参数并与解码器输出逐帧核对")
    a = ap.parse_args()

    src = Path(a.input)
    raw = src.read_bytes()
    if len(raw) % UNIT_BYTES:
        print("输入 %d 字节不是 %d 的整数倍" % (len(raw), UNIT_BYTES))
        return 1

    params = decode_params(src)
    if not params:
        print("解码器没有输出参数，检查 %s" % DECODER)
        return 1

    packed = pack_params(params)
    Path(a.output).write_bytes(packed)
    print("输入 %d 单元 / %d 帧 → 输出 %d 字节（%d 单元）"
          % (len(raw) // UNIT_BYTES, len(params), len(packed),
             len(packed) // UNIT_BYTES))

    if a.verify:
        back = unpack_params(packed)
        bad = sum(1 for x, y in zip(params, back) if x != y)
        print("打包可逆性：%d 帧，%d 帧不一致" % (len(params), bad))
        if len(packed) % UNIT_BYTES:
            print("警告：输出不是 %d 字节单元的整数倍，末尾需补齐"
                  % UNIT_BYTES)
        return 0 if bad == 0 else 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
