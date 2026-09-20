#!/usr/bin/env python3
"""生成单音与扫频测试素材，用于测量声码器的行为边界。

2.9.4 第五块的素材库列了「已知语句、单音、扫频」，前者已有（语音与摩尔斯），
后两者缺。

**为什么对声码器也要测单音**：AMBE 是参数化声码器，不是波形编码器，
正弦音并不"原样通过"——它会被当成浊音建模。测单音的目的不是看保真度，
而是取得**确定性的判据**：同一频率的单音，编码后参数应当稳定且可重复；
若发射链有错位或丢帧，接收端会表现为音高跳变或断续，比听语音更容易定位。

扫频用来找声码器的基音跟踪范围：过低或过高时参数会失锁，接收端听感突变，
由此可标出可用区间。

约束：单次发射不超过 30 秒（2.9.4 安全与合规）。

输出为 8 千赫兹、16 位有符号小端单声道裸 PCM，与模块语音采样率一致。
本脚本只生成素材，不接触设备。

用法：
    python make_probe_tones.py --mode tones --output tones.pcm
    python make_probe_tones.py --mode sweep --output sweep.pcm
"""
from __future__ import annotations

import argparse
import hashlib
import math
import struct
from pathlib import Path

RATE = 8000
MAX_SECONDS = 28.0          # 留 2 秒余量
AMPLITUDE = 8000            # 约 -12 dBFS，避免削顶

# 人声基音范围内取点，两端各留一个越界点用于找失锁边界
TONE_HZ = [80, 110, 150, 200, 260, 330, 420, 520, 650, 800]
TONE_SECONDS = 2.0
GAP_SECONDS = 0.3

SWEEP_LOW, SWEEP_HIGH = 70, 900
SWEEP_SECONDS = 20.0


def silence(seconds: float) -> list[int]:
    return [0] * int(RATE * seconds)


def tone(hz: float, seconds: float) -> list[int]:
    n = int(RATE * seconds)
    return [int(AMPLITUDE * math.sin(2 * math.pi * hz * i / RATE))
            for i in range(n)]


def sweep(low: float, high: float, seconds: float) -> list[int]:
    """对数扫频：低频端分辨率更高，基音失锁通常发生在两端。"""
    n = int(RATE * seconds)
    out = []
    phase = 0.0
    for i in range(n):
        t = i / n
        hz = low * (high / low) ** t
        phase += 2 * math.pi * hz / RATE
        out.append(int(AMPLITUDE * math.sin(phase)))
    return out


def build(mode: str) -> tuple[list[int], str]:
    if mode == "tones":
        samples: list[int] = []
        marks = []
        for hz in TONE_HZ:
            marks.append("%.1f 秒起 %d 赫兹"
                         % (len(samples) / RATE, hz))
            samples += tone(hz, TONE_SECONDS)
            samples += silence(GAP_SECONDS)
        return samples, "；".join(marks)
    if mode == "sweep":
        return (sweep(SWEEP_LOW, SWEEP_HIGH, SWEEP_SECONDS),
                "%d 赫兹起对数扫到 %d 赫兹，历时 %.0f 秒"
                % (SWEEP_LOW, SWEEP_HIGH, SWEEP_SECONDS))
    raise ValueError("未知模式 %s" % mode)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--mode", choices=["tones", "sweep"], required=True)
    ap.add_argument("--output", required=True)
    a = ap.parse_args()

    samples, note = build(a.mode)
    seconds = len(samples) / RATE
    if seconds > MAX_SECONDS:
        print("素材 %.1f 秒超过单次发射上限 %.1f 秒，拒绝生成"
              % (seconds, MAX_SECONDS))
        return 1

    data = struct.pack("<%dh" % len(samples), *samples)
    Path(a.output).write_bytes(data)
    print("已写 %s：%.1f 秒，%d 样本，%d 字节"
          % (a.output, seconds, len(samples), len(data)))
    print("内容：%s" % note)
    print("SHA-256 %s" % hashlib.sha256(data).hexdigest()[:32])
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
