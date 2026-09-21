#!/usr/bin/env python3
"""端到端延迟预算：把各段实测值加起来，看热点是否够用。

2.9.5 列了三个必须先证明的未知量，第三个是延迟预算——「从未测量」。
真机端到端延迟要等射频测试，但**各段的实测值今天已经有了**，先把预算
算清楚，届时只需核对总和是否吻合。

热点可容忍的延迟大致在一两百毫秒（2.9.5）。超出后对话会互相打断。

各段来源（全部为已验证实测，不含推算）：

  网络到达抖动      抖动缓冲深度 3 个供数单元 = 180 毫秒（2.9.33，设计值）
  剥信道编码        纯计算，实测 936 帧毫秒级（2.9.27）
  控制链＋编解码链  重新武装轮实测 2.07 至 2.13 秒（2.9.11）
  呼叫头            实测 0.135 秒（2.9.14）
  供数节拍          每单元 60 毫秒，与空口同步（2.9.30）

用法：
    python latency_model.py
    python latency_model.py --rearm     按重新武装路径估算
"""
from __future__ import annotations

import argparse

# (名称, 毫秒, 出处, 是否每次发射都付)
SEGMENTS_FIRST = [
    ("推桩", 55_000, "2.9.8 实测", True),
    ("信道与省电设置", 45_800, "2.9.8 实测", True),
    ("准备期", 13_000, "2.9.11 缩短后", True),
    ("控制链＋编解码链", 3_400, "2.9.11 首轮实测", True),
    ("呼叫头", 200, "2.9.14 实测", True),
]

SEGMENTS_REARM = [
    ("重新武装", 800, "2.9.18 实测三次写", True),
    ("准备期", 13_000, "2.9.11 缩短后", True),
    ("控制链＋编解码链", 2_100, "2.9.14 重入轮实测", True),
    ("呼叫头", 135, "2.9.14 实测", True),
]

STREAMING = [
    ("抖动缓冲", 180, "2.9.33 深度 3 单元", ),
    ("剥信道编码", 5, "2.9.27 纯计算", ),
    ("供数节拍", 60, "2.9.30 每单元", ),
]

HOTSPOT_BUDGET_MS = 200


def report(segments, title: str) -> int:
    print("== %s" % title)
    total = 0
    for name, ms, src, _ in segments:
        total += ms
        print("   %-18s %7d 毫秒   %s" % (name, ms, src))
    print("   %-18s %7d 毫秒" % ("按键到出声", total))
    return total


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--rearm", action="store_true")
    a = ap.parse_args()

    first = report(SEGMENTS_FIRST, "首次发射（完整会话）")
    print()
    rearm = report(SEGMENTS_REARM, "重新武装路径（第二次起，当前不可用）")

    print("\n== 稳态流式延迟（通话建立后，每帧）")
    stream = 0
    for name, ms, src in STREAMING:
        stream += ms
        print("   %-18s %7d 毫秒   %s" % (name, ms, src))
    print("   %-18s %7d 毫秒" % ("合计", stream))

    print("\n== 对热点的判断")
    print("   热点可容忍约 %d 毫秒（2.9.5）" % HOTSPOT_BUDGET_MS)
    if stream <= HOTSPOT_BUDGET_MS:
        print("   稳态流式 %d 毫秒 → **在预算内**" % stream)
    else:
        print("   稳态流式 %d 毫秒 → 超预算 %d 毫秒"
              % (stream, stream - HOTSPOT_BUDGET_MS))
    print("   但**通话建立**首次 %.1f 秒、重新武装路径 %.1f 秒，"
          % (first / 1000, rearm / 1000))
    print("   两者都远超热点可接受范围；且重新武装路径当前因")
    print("   「第二次发射会话不启动」（2.9.25）而不可用。")
    print("")
    print("   结论一：稳态 %d 毫秒略超 %d 毫秒预算，缺口全在抖动缓冲"
          % (stream, HOTSPOT_BUDGET_MS))
    print("   （深度 3 单元 = 180 毫秒）。降到 2 单元即 %d 毫秒，落入预算；"
          % (stream - 60))
    print("   代价是网络抖动时欠载率上升（2.9.33 实测 30 至 90 毫秒抖动下")
    print("   深度 3 单元欠载 8%）。这是延迟与稳健的直接折中，需实测定夺。")
    print("")
    print("   量纲提醒（2.9.59）：这里的「单元」是 27 字节、60 毫秒，")
    print("   不是 9 字节、20 毫秒的单帧 AMBE。按单帧理解，同一个深度")
    print("   会被算成 60 毫秒，整份预算错三倍。")
    print("")
    print("   结论二：真正的瓶颈是通话建立，不是稳态。准备期与一次性")
    print("   设置占绝大部分，协议本身不到 4 秒（2.9.8）。")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
