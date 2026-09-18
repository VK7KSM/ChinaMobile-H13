#!/usr/bin/env python3
"""生成发射测试用的摩尔斯音频素材。

内容为 26 个字母加 10 个数字各发一遍，接收端可以逐字核对收到了哪些、
从哪里开始丢失，比单纯的重复音调更容易判断链路质量。

约束：射频发射单次不超过 30 秒（发热限制）。点长 60 毫秒时全序列约 27.8 秒，
留约 2 秒余量。点长可调，但要自行确认总时长不超限。

输出为 8 千赫兹、16 位有符号小端单声道裸 PCM，与模块的语音采样率一致。
本脚本只生成素材，不接触设备。
"""

from __future__ import annotations

import argparse
import hashlib
import math
import struct
from pathlib import Path

MORSE = {
    'A': '.-', 'B': '-...', 'C': '-.-.', 'D': '-..', 'E': '.', 'F': '..-.',
    'G': '--.', 'H': '....', 'I': '..', 'J': '.---', 'K': '-.-', 'L': '.-..',
    'M': '--', 'N': '-.', 'O': '---', 'P': '.--.', 'Q': '--.-', 'R': '.-.',
    'S': '...', 'T': '-', 'U': '..-', 'V': '...-', 'W': '.--', 'X': '-..-',
    'Y': '-.--', 'Z': '--..',
    '0': '-----', '1': '.----', '2': '..---', '3': '...--', '4': '....-',
    '5': '.....', '6': '-....', '7': '--...', '8': '---..', '9': '----.',
}

DEFAULT_SEQUENCE = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ 0123456789'
SAMPLE_RATE = 8000
MAX_SECONDS = 30.0


def duration_units(text: str) -> int:
    """按国际摩尔斯时序计算总时长单位：点 1、划 3、符内 1、字符间 3、词间 7。"""
    total = 0
    for index, char in enumerate(text):
        if char == ' ':
            total += 7 - 3
            continue
        code = MORSE[char]
        total += sum(1 if symbol == '.' else 3 for symbol in code)
        total += len(code) - 1
        if index < len(text) - 1:
            total += 3
    return total


def render(text: str, dot_ms: int, tone_hz: int, amplitude: float) -> bytes:
    """渲染为 PCM。音调起止加 5 毫秒升降沿，避免方波跳变产生宽带杂散。"""
    samples: list[int] = []
    ramp = max(1, int(SAMPLE_RATE * 0.005))

    def silence(ms: float) -> None:
        samples.extend([0] * int(SAMPLE_RATE * ms / 1000))

    def tone(ms: float) -> None:
        count = int(SAMPLE_RATE * ms / 1000)
        peak = amplitude * 32767
        for n in range(count):
            value = peak * math.sin(2 * math.pi * tone_hz * n / SAMPLE_RATE)
            if n < ramp:
                value *= n / ramp
            elif n >= count - ramp:
                value *= (count - n) / ramp
            samples.append(int(value))

    for index, char in enumerate(text):
        if char == ' ':
            silence(dot_ms * (7 - 3))
            continue
        code = MORSE[char]
        for position, symbol in enumerate(code):
            tone(dot_ms * (1 if symbol == '.' else 3))
            if position < len(code) - 1:
                silence(dot_ms)
        if index < len(text) - 1:
            silence(dot_ms * 3)

    return b''.join(struct.pack('<h', max(-32768, min(32767, s)))
                    for s in samples)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument('--sequence', default=DEFAULT_SEQUENCE)
    parser.add_argument('--dot-ms', type=int, default=60,
                        help='点长毫秒。60 毫秒时全序列约 27.8 秒')
    parser.add_argument('--tone-hz', type=int, default=800)
    parser.add_argument('--amplitude', type=float, default=0.5)
    parser.add_argument('--out', required=True)
    parser.add_argument('--allow-over-limit', action='store_true',
                        help='允许超过 30 秒。射频发射有发热限制，默认拒绝')
    args = parser.parse_args()

    text = args.sequence.upper()
    unknown = [c for c in text if c != ' ' and c not in MORSE]
    if unknown:
        raise SystemExit(f'序列包含无法编码的字符: {unknown}')

    seconds = duration_units(text) * args.dot_ms / 1000
    if seconds > MAX_SECONDS and not args.allow_over_limit:
        raise SystemExit(
            f'时长 {seconds:.2f} 秒超过 {MAX_SECONDS} 秒发射上限。'
            f'请缩短序列或减小点长。')

    pcm = render(text, args.dot_ms, args.tone_hz, args.amplitude)

    # 帧对齐：一个语音帧为 160 采样（20 毫秒）。补静音使帧数同时能被 3 和 4
    # 整除，这样语音突发格式（每包 3 帧）与历史格式（每包 4 帧）都不需要补位，
    # 两种打包方式可以直接对照，且发出的语音内容完全相同。
    frame_bytes = 160 * 2
    frames = -(-len(pcm) // frame_bytes)
    while frames % 12 != 0:
        frames += 1
    padded = frames * frame_bytes
    if len(pcm) < padded:
        pcm = pcm + bytes(padded - len(pcm))

    out = Path(args.out)
    out.write_bytes(pcm)

    frames = len(pcm) // frame_bytes        # 每 160 采样为一个 20 毫秒语音帧
    bursts = frames // 3                    # 三帧组成一个语音突发
    legacy = frames // 4                    # 历史格式每包四帧
    print(f'序列      {text}')
    print(f'字符数    {len([c for c in text if c != " "])}')
    print(f'点长      {args.dot_ms} 毫秒，音调 {args.tone_hz} 赫兹')
    print(f'时长      {seconds:.2f} 秒（上限 {MAX_SECONDS} 秒）')
    print(f'PCM       {len(pcm)} 字节，{len(pcm)//2} 采样')
    print(f'语音帧    {frames} 个（每帧 20 毫秒）')
    print(f'语音突发  {bursts} 个（每突发 3 帧 60 毫秒，共 {bursts*0.06:.2f} 秒）')
    print(f'历史格式  {legacy} 包（每包 4 帧 80 毫秒，共 {legacy*0.08:.2f} 秒）')
    print(f'SHA-256   {hashlib.sha256(pcm).hexdigest().upper()}')
    print(f'输出      {out}')


if __name__ == '__main__':
    main()
