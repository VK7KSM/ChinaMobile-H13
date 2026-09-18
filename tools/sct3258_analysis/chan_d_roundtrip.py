#!/usr/bin/env python3
"""Offline CHAN_D shape and air-mapping roundtrip (job.md 36019).

Uses only the mapping already proven in analyze_chan_d.py:
air/forward/msb/normal. No device access.
"""

from __future__ import annotations

import argparse
import hashlib
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from analyze_chan_d import (  # noqa: E402
    RW,
    RX,
    RY,
    RZ,
    bytes_to_bits,
    golay_score,
    map_air,
    nearest_golay,
)


def bits_to_bytes(bits: list[int]) -> bytes:
    out = bytearray((len(bits) + 7) // 8)
    for index, bit in enumerate(bits):
        if bit:
            out[index // 8] |= 1 << (7 - (index % 8))
    return bytes(out)


def inverse_air(frame: list[list[int]]) -> bytes:
    bits = [0] * 72
    for index in range(36):
        bits[2 * index] = frame[RW[index]][RX[index]]
        bits[2 * index + 1] = frame[RY[index]][RZ[index]]
    return bits_to_bytes(bits)


def ecc_and_rebuild(frame9: bytes) -> tuple[bytes, int]:
    bits = bytes_to_bits(frame9, "forward", "msb")
    ambe_fr = map_air(bits, "normal")
    c0_word = sum(ambe_fr[0][j + 1] << j for j in range(23))
    c0_distance, corrected_c0 = nearest_golay(c0_word)
    expected_parity = corrected_c0.bit_count() & 1
    c0_distance += int(ambe_fr[0][0] != expected_parity)
    for j in range(23):
        ambe_fr[0][j + 1] = (corrected_c0 >> j) & 1
    ambe_fr[0][0] = expected_parity

    seed = 0
    for i in range(23, 11, -1):
        seed = (seed << 1) | ambe_fr[0][i]
    state = (16 * seed) & 0xFFFF
    for column in range(22, -1, -1):
        state = (173 * state + 13849) & 0xFFFF
        ambe_fr[1][column] ^= state // 32768
    c1_word = sum(ambe_fr[1][j] << j for j in range(23))
    c1_distance, corrected_c1 = nearest_golay(c1_word)
    for j in range(23):
        ambe_fr[1][j] = (corrected_c1 >> j) & 1
    state = (16 * seed) & 0xFFFF
    for column in range(22, -1, -1):
        state = (173 * state + 13849) & 0xFFFF
        ambe_fr[1][column] ^= state // 32768
    rebuilt = inverse_air(ambe_fr)
    return rebuilt, c0_distance + c1_distance


def summarize(path: Path) -> None:
    payload = path.read_bytes()
    if not payload or len(payload) % 9:
        raise SystemExit(f"length must be a non-zero multiple of 9: {path}")
    frames = [payload[i : i + 9] for i in range(0, len(payload), 9)]
    high_zero = sum(1 for frame in frames if (frame[8] & 0xF0) == 0)
    scores = [
        golay_score(map_air(bytes_to_bits(frame, "forward", "msb"), "normal"))
        for frame in frames
    ]
    total = sum(item[2] for item in scores)
    exact = 0
    first_diff = None
    distances = []
    for index, frame in enumerate(frames):
        rebuilt, _ = ecc_and_rebuild(frame)
        dist = sum(bin(a ^ b).count("1") for a, b in zip(frame, rebuilt))
        distances.append(dist)
        if rebuilt == frame:
            exact += 1
        elif first_diff is None:
            first_diff = (index, dist, frame.hex(), rebuilt.hex())
    print(f"FILE {path}")
    print(f"  bytes={len(payload)} frames={len(frames)} sha256={hashlib.sha256(payload).hexdigest().upper()}")
    print(f"  last_nibble_zero={high_zero}/{len(frames)}")
    print(f"  air/forward/msb/normal total={total} mean={total / len(frames):.3f}")
    print(f"  ecc_roundtrip exact={exact}/{len(frames)} mean_hamming={sum(distances)/len(distances):.3f} max={max(distances)}")
    if first_diff is not None:
        index, dist, original, rebuilt = first_diff
        print(f"  first_diff index={index} ham={dist} orig={original} rebuilt={rebuilt}")
    print()


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("files", nargs="+", type=Path)
    args = parser.parse_args()
    for path in args.files:
        summarize(path)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
