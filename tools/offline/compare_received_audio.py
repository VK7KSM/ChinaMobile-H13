#!/usr/bin/env python3
"""比对接收端录音与参照音频，给出可量化的判读结果。

用途：空口测试时，对端录音与本地参照（素材经软件编解码后的音频）逐段比对，
回答三个问题：从第几秒开始出现明显劣化、整体劣化程度、以及 36 个条目中
有多少段是可用的。

这样判据不再是"听到没有"，而是"从第几个条目开始丢"。

参照音频应当用素材经编解码后的版本，而不是原始素材：声码器本身会带来
可听的劣化，那部分不属于链路问题。

输入可以是 WAV 或裸 PCM（8 千赫兹、16 位有符号小端单声道）。
录音采样率不同会自动重采样到 8 千赫兹再比对。

用法：
    python compare_received_audio.py --reference <参照> --received <录音>
"""

from __future__ import annotations

import argparse
import array
import math
import wave
from pathlib import Path

SAMPLE_RATE = 8000
# 36 个条目：26 个字母加 10 个数字
ITEMS = [chr(ord('A') + i) for i in range(26)] + [str(d) for d in range(10)]


def load(path: Path) -> array.array:
    if path.suffix.lower() == '.wav':
        with wave.open(str(path), 'rb') as w:
            if w.getsampwidth() != 2 or w.getnchannels() != 1:
                raise SystemExit(f'{path.name} 必须是 16 位单声道')
            data = array.array('h')
            data.frombytes(w.readframes(w.getnframes()))
            rate = w.getframerate()
    else:
        data = array.array('h')
        data.frombytes(path.read_bytes())
        rate = SAMPLE_RATE

    if rate != SAMPLE_RATE:
        # 最近邻重采样，用于能量包络比对已经足够
        ratio = rate / SAMPLE_RATE
        out = array.array('h',
                          (data[min(int(i * ratio), len(data) - 1)]
                           for i in range(int(len(data) / ratio))))
        data = out
    return data


def rms(samples, start: int, count: int) -> float:
    end = min(start + count, len(samples))
    if end <= start:
        return 0.0
    total = sum(float(v) * v for v in samples[start:end])
    return math.sqrt(total / (end - start))


def best_offset(reference, received, search_seconds: float = 3.0) -> int:
    """用前若干秒的能量包络估计录音相对参照的起始偏移。"""
    step = 160                       # 20 毫秒
    span = int(SAMPLE_RATE * 2)      # 用 2 秒做匹配
    if len(reference) < span or len(received) < span:
        return 0

    ref_env = [rms(reference, i, step) for i in range(0, span, step)]
    best, best_score = 0, -1.0
    limit = int(SAMPLE_RATE * search_seconds)
    for offset in range(0, min(limit, max(1, len(received) - span)), step):
        env = [rms(received, offset + i, step) for i in range(0, span, step)]
        num = sum(a * b for a, b in zip(ref_env, env))
        den = math.sqrt(sum(a * a for a in ref_env) * sum(b * b for b in env))
        score = num / den if den > 0 else 0.0
        if score > best_score:
            best_score, best = score, offset
    return best


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument('--reference', required=True,
                        help='参照音频，应为素材经软件编解码后的版本')
    parser.add_argument('--received', required=True, help='接收端录音')
    parser.add_argument('--threshold', type=float, default=0.35,
                        help='段落可用判定阈值，录音与参照的能量比下限')
    args = parser.parse_args()

    reference = load(Path(args.reference))
    received = load(Path(args.received))

    offset = best_offset(reference, received)
    print(f'参照长度  {len(reference)/SAMPLE_RATE:.2f} 秒')
    print(f'录音长度  {len(received)/SAMPLE_RATE:.2f} 秒')
    print(f'对齐偏移  {offset/SAMPLE_RATE:.2f} 秒')
    print()

    # 按条目切分。素材是 36 个条目均匀朗读，按参照总长等分即可近似定位。
    item_samples = len(reference) // len(ITEMS)
    print('条目  参照RMS  录音RMS   比值   判定')
    usable = 0
    first_bad = None
    for index, name in enumerate(ITEMS):
        start = index * item_samples
        ref_rms = rms(reference, start, item_samples)
        got_rms = rms(received, start + offset, item_samples)
        ratio = got_rms / ref_rms if ref_rms > 50 else 0.0
        ok = ratio >= args.threshold
        if ok:
            usable += 1
        elif first_bad is None:
            first_bad = (index, name)
        print(f' {name:>3}  {ref_rms:7.0f}  {got_rms:7.0f}  {ratio:5.2f}   '
              f'{"可用" if ok else "劣化"}')

    print()
    print(f'可用条目  {usable} / {len(ITEMS)}')
    if first_bad is None:
        print('判定      全部条目可用')
    else:
        index, name = first_bad
        print(f'判定      从第 {index + 1} 个条目（{name}）开始出现劣化，'
              f'约在第 {index * item_samples / SAMPLE_RATE:.1f} 秒')
    print()
    print('说明      参照本身已含声码器劣化，此处只反映链路引入的额外劣化。')
    print('          能量比对只能判断有无与强弱，内容是否正确仍需人耳确认。')


if __name__ == '__main__':
    main()
