#!/usr/bin/env python3
"""网络到射频方向的抖动缓冲模型。

为什么需要：网络来话经互联网到达，间隔抖动远大于空口的 60 毫秒定拍；
而模块按自己的节奏交帧、宿主必须一帧一回（2.8.73）。中间没有缓冲就会
出现两种故障——欠载时无帧可回导致节拍断掉，过载时积压导致延迟累积。

设计要点：

  1. **欠载补静音帧，不断流**。供数契约是收到一帧才回送一帧，回不上就
     等于违约。宁可补一帧静音，也不能让节拍断掉。
  2. **过载丢最旧的**。语音过时即无意义，保新不保旧。
  3. **缓冲深度是延迟与稳健的折中**。热点大致能容忍一两百毫秒
     （2.9.5），按 60 毫秒一帧算即 2 到 3 帧。

本模块只做缓冲决策，不碰硬件。
"""
from __future__ import annotations

from collections import deque
from dataclasses import dataclass, field

# 缓冲元素是一个 60 毫秒的**供数单元**：三帧 AMBE × 9 字节 = 27 字节。
# 2026-09-21 更正：这里原来写的是 9 字节。模型里长度不承重，写错也跑得过；
# 但 Java 侧照着它写成 9 字节，一拼成全链路就当场崩了——网络来的一个突发
# 拆出来是 27 字节。单帧 AMBE 是 20 毫秒、单元是 60 毫秒，差三倍，
# 混用会让延迟预算直接错三倍。
UNIT_MS = 60
UNIT_BYTES = 27
FRAME_MS = UNIT_MS         # 兼容旧调用点
DEFAULT_DEPTH = 3          # 三个单元 ＝ 约 180 毫秒
SILENCE = b"\x00" * UNIT_BYTES


@dataclass
class JitterBuffer:
    depth: int = DEFAULT_DEPTH
    queue: deque = field(default_factory=deque)
    underruns: int = 0
    drops: int = 0
    delivered: int = 0

    def push(self, frame: bytes) -> None:
        """网络侧到帧。满了就丢最旧的——保新不保旧。"""
        if len(self.queue) >= self.depth:
            self.queue.popleft()
            self.drops += 1
        self.queue.append(frame)

    def pop(self) -> bytes:
        """模块交帧时取一帧回送；无帧则补静音，绝不断流。"""
        self.delivered += 1
        if self.queue:
            return self.queue.popleft()
        self.underruns += 1
        return SILENCE

    def stats(self) -> str:
        return ("回送 %d 帧，欠载补静音 %d（%.1f%%），过载丢弃 %d，当前深度 %d"
                % (self.delivered, self.underruns,
                   100.0 * self.underruns / max(self.delivered, 1),
                   self.drops, len(self.queue)))


def _simulate(arrivals_ms: list[int], total_ms: int, depth: int) -> JitterBuffer:
    """按空口 60 毫秒定拍取帧，按给定时刻注入网络帧。"""
    jb = JitterBuffer(depth=depth)
    idx = 0
    for now in range(0, total_ms, FRAME_MS):
        while idx < len(arrivals_ms) and arrivals_ms[idx] <= now:
            jb.push(bytes([idx & 0xFF]) * UNIT_BYTES)
            idx += 1
        jb.pop()
    return jb


def _selftest() -> int:
    bad = 0

    def check(cond: bool, what: str) -> None:
        nonlocal bad
        if not cond:
            bad += 1
            print("   不通过 %s" % what)
        else:
            print("   通过   %s" % what)

    print("理想情况：网络严格 60 毫秒一帧")
    jb = _simulate(list(range(0, 3000, 60)), 3000, DEFAULT_DEPTH)
    print("   " + jb.stats())
    check(jb.underruns <= 1, "稳态不应欠载")

    print("抖动：间隔在 30 到 90 毫秒之间摆动")
    import random
    random.seed(11)
    t, arrivals = 0, []
    while t < 3000:
        arrivals.append(t)
        t += random.choice([30, 60, 90])
    jb = _simulate(arrivals, 3000, DEFAULT_DEPTH)
    print("   " + jb.stats())
    check(jb.underruns / jb.delivered < 0.15, "抖动下欠载应低于 15%")

    print("网络中断 600 毫秒")
    arrivals = list(range(0, 600, 60)) + list(range(1200, 3000, 60))
    jb = _simulate(arrivals, 3000, DEFAULT_DEPTH)
    print("   " + jb.stats())
    check(jb.underruns > 0, "中断期间应补静音而不是断流")
    check(jb.delivered == len(range(0, 3000, FRAME_MS)), "回送帧数必须等于交帧数")

    print("突发：一次到 20 帧")
    jb = JitterBuffer()
    for i in range(20):
        jb.push(bytes([i]) * 9)
    check(len(jb.queue) == DEFAULT_DEPTH, "缓冲不得超过设定深度")
    check(jb.drops == 20 - DEFAULT_DEPTH, "多余帧应被丢弃")
    check(jb.queue[-1] == bytes([19]) * 9, "保留的应是最新帧")

    print("\n不通过项 %d" % bad)
    return 1 if bad else 0


if __name__ == "__main__":
    raise SystemExit(_selftest())
