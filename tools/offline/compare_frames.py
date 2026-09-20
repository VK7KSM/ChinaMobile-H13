#!/usr/bin/env python3
"""帧级比对：把发出去的语音单元与接收端还原的单元逐帧对齐并比对。

为什么要有这个工具：此前判断"发得对不对"只能靠人听，描述主观、也说不出
劣化从哪一帧开始。帧级比对给出确定的数字——对上了多少帧、每帧错几位、
第一处不一致在哪里。SDR 接收链路建好之后，这是闭环判据的判分端。

对齐：接收端的起点通常与参照不同（前导、丢帧、触发时刻差异），因此先在
一个窗口内搜索最佳偏移，取匹配帧数最多者。单元互不相同，误配概率极低。

输入是 27 字节单元流（每单元 3 帧 AMBE）或 9 字节帧流，由 --unit-bytes 指定。

用法：
    python compare_frames.py --reference <参照.bin> --received <接收.bin>
    python compare_frames.py --reference a.bin --received b.bin --unit-bytes 9
"""
from __future__ import annotations

import argparse
from pathlib import Path

POPCOUNT = bytes(bin(i).count("1") for i in range(256))


def split_units(data: bytes, unit_bytes: int) -> list[bytes]:
    count = len(data) // unit_bytes
    return [data[i * unit_bytes:(i + 1) * unit_bytes] for i in range(count)]


def bit_errors(a: bytes, b: bytes) -> int:
    return sum(POPCOUNT[x ^ y] for x, y in zip(a, b))


def best_offset(ref: list[bytes], got: list[bytes], window: int):
    """在 [-window, window] 内找匹配单元数最多的偏移。"""
    best = (0, -1)
    for off in range(-window, window + 1):
        exact = 0
        for i, unit in enumerate(got):
            j = i + off
            if 0 <= j < len(ref) and ref[j] == unit:
                exact += 1
        if exact > best[1]:
            best = (off, exact)
    return best


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--reference", required=True)
    ap.add_argument("--received", required=True)
    ap.add_argument("--unit-bytes", type=int, default=27)
    ap.add_argument("--window", type=int, default=200,
                    help="对齐搜索窗口（单元数）")
    a = ap.parse_args()

    unit = a.unit_bytes
    ref = split_units(Path(a.reference).read_bytes(), unit)
    got = split_units(Path(a.received).read_bytes(), unit)
    if not ref or not got:
        print("参照或接收为空")
        return 1

    print("参照 %d 个单元，接收 %d 个单元，每单元 %d 字节"
          % (len(ref), len(got), unit))

    off, exact = best_offset(ref, got, min(a.window, max(len(ref), len(got))))
    print("最佳偏移 %+d，完全一致 %d 个单元" % (off, exact))

    compared = 0
    total_bits = 0
    errored = 0
    first_bad = None
    for i, u in enumerate(got):
        j = i + off
        if not (0 <= j < len(ref)):
            continue
        compared += 1
        e = bit_errors(ref[j], u)
        total_bits += e
        if e:
            errored += 1
            if first_bad is None:
                first_bad = (i, j, e)

    if compared == 0:
        print("对齐后没有可比区间")
        return 1

    print("可比 %d 个单元：一致 %d，不一致 %d（%.1f%%）"
          % (compared, compared - errored, errored,
             100.0 * errored / compared))
    print("误码率 %.4f%%（%d 位 / %d 位）"
          % (100.0 * total_bits / (compared * unit * 8),
             total_bits, compared * unit * 8))
    print("覆盖率 %.1f%%（接收 %d / 参照 %d）"
          % (100.0 * compared / len(ref), compared, len(ref)))
    if first_bad:
        i, j, e = first_bad
        print("首处不一致：接收第 %d 个单元（参照第 %d 个），错 %d 位，"
              "约在第 %.2f 秒" % (i, j, e, i * 0.06))
    else:
        print("全部一致")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
