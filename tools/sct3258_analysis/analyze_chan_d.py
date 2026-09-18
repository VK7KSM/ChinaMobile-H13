#!/usr/bin/env python3
"""Score possible SCT3258/H13 9-byte DMR CHAN_D bit layouts.

This tool is deliberately read-only. It compares candidate layouts using the
Golay protection expected by mbelib's AMBE3600x2450 decoder. It does not claim
that the lowest score is correct unless the result is clearly separated from
the other candidates and is confirmed with more captured speech.
"""

from __future__ import annotations

import argparse
import hashlib
from dataclasses import dataclass
from pathlib import Path


RW = (
    0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1,
    0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 2,
    0, 2, 0, 2, 0, 2, 0, 2, 0, 2, 0, 2,
)
RX = (
    23, 10, 22, 9, 21, 8, 20, 7, 19, 6, 18, 5,
    17, 4, 16, 3, 15, 2, 14, 1, 13, 0, 12, 10,
    11, 9, 10, 8, 9, 7, 8, 6, 7, 5, 6, 4,
)
RY = (
    0, 2, 0, 2, 0, 2, 0, 2, 0, 3, 0, 3,
    1, 3, 1, 3, 1, 3, 1, 3, 1, 3, 1, 3,
    1, 3, 1, 3, 1, 3, 1, 3, 1, 3, 1, 3,
)
RZ = (
    5, 3, 4, 2, 3, 1, 2, 0, 1, 13, 0, 12,
    22, 11, 21, 10, 20, 9, 19, 8, 18, 7, 17, 6,
    16, 5, 15, 4, 14, 3, 13, 2, 12, 1, 11, 0,
)

GOLAY_GENERATOR = (
    0x63A, 0x31D, 0x7B4, 0x3DA, 0x1ED, 0x6CC,
    0x366, 0x1B3, 0x6E3, 0x54B, 0x49F, 0x475,
)


def golay_codewords() -> tuple[int, ...]:
    words = []
    for data in range(1 << 12):
        parity = 0
        for i, generator in enumerate(GOLAY_GENERATOR):
            if data & (1 << (11 - i)):
                parity ^= generator
        words.append((data << 11) | parity)
    return tuple(words)


GOLAY_WORDS = golay_codewords()


def nearest_golay(word: int) -> tuple[int, int]:
    distance, corrected = min(
        ((word ^ candidate).bit_count(), candidate) for candidate in GOLAY_WORDS
    )
    return distance, corrected


def bytes_to_bits(frame: bytes, byte_order: str, bit_order: str) -> list[int]:
    octets = frame if byte_order == "forward" else frame[::-1]
    shifts = range(7, -1, -1) if bit_order == "msb" else range(8)
    return [(octet >> shift) & 1 for octet in octets for shift in shifts]


def map_air(bits: list[int], pair_order: str) -> list[list[int]]:
    frame = [[0] * 24 for _ in range(4)]
    for i in range(36):
        first, second = bits[2 * i : 2 * i + 2]
        if pair_order == "swapped":
            first, second = second, first
        frame[RW[i]][RX[i]] = first
        frame[RY[i]][RZ[i]] = second
    return frame


def map_rows(bits: list[int], direction: str) -> list[list[int]]:
    frame = [[0] * 24 for _ in range(4)]
    limits = (24, 23, 11, 14)
    positions = []
    for row, length in enumerate(limits):
        columns = range(length - 1, -1, -1) if direction == "descending" else range(length)
        positions.extend((row, column) for column in columns)
    for bit, (row, column) in zip(bits, positions):
        frame[row][column] = bit
    return frame


def golay_score(frame: list[list[int]]) -> tuple[int, int, int]:
    c0_word = sum(frame[0][j + 1] << j for j in range(23))
    c0_distance, corrected_c0 = nearest_golay(c0_word)
    expected_extended_parity = corrected_c0.bit_count() & 1
    c0_distance += frame[0][0] != expected_extended_parity
    for j in range(23):
        frame[0][j + 1] = (corrected_c0 >> j) & 1

    seed = 0
    for i in range(23, 11, -1):
        seed = (seed << 1) | frame[0][i]
    state = (16 * seed) & 0xFFFF
    for column in range(22, -1, -1):
        state = (173 * state + 13849) & 0xFFFF
        frame[1][column] ^= state // 32768

    c1_word = sum(frame[1][j] << j for j in range(23))
    c1_distance, _ = nearest_golay(c1_word)
    return c0_distance, c1_distance, c0_distance + c1_distance


@dataclass(frozen=True)
class CandidateResult:
    name: str
    scores: tuple[tuple[int, int, int], ...]

    @property
    def total(self) -> int:
        return sum(score[2] for score in self.scores)


def analyze(frames: list[bytes]) -> list[CandidateResult]:
    results = []
    for byte_order in ("forward", "reverse"):
        for bit_order in ("msb", "lsb"):
            bit_frames = [bytes_to_bits(frame, byte_order, bit_order) for frame in frames]
            for pair_order in ("normal", "swapped"):
                scores = tuple(
                    golay_score(map_air(bits, pair_order)) for bits in bit_frames
                )
                results.append(CandidateResult(
                    f"air/{byte_order}/{bit_order}/{pair_order}", scores
                ))
            for direction in ("descending", "ascending"):
                scores = tuple(
                    golay_score(map_rows(bits, direction)) for bits in bit_frames
                )
                results.append(CandidateResult(
                    f"rows/{byte_order}/{bit_order}/{direction}", scores
                ))
    return sorted(results, key=lambda result: (result.total, result.name))


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("capture", type=Path)
    args = parser.parse_args()

    payload = args.capture.read_bytes()
    if not payload or len(payload) % 9:
        parser.error(f"capture length must be a non-zero multiple of 9, got {len(payload)}")
    frames = [payload[offset : offset + 9] for offset in range(0, len(payload), 9)]

    print(f"file={args.capture}")
    print(f"bytes={len(payload)} frames={len(frames)} sha256={hashlib.sha256(payload).hexdigest()}")
    high_nibbles = [frame[8] >> 4 for frame in frames]
    low_nibbles = [frame[8] & 0x0F for frame in frames]
    print(f"byte8_high_nibbles={' '.join(f'{value:x}' for value in high_nibbles)}")
    print(f"byte8_low_nibbles={' '.join(f'{value:x}' for value in low_nibbles)}")
    print("candidate,total,mean,c0+c1_per_frame")
    for result in analyze(frames):
        details = " ".join(f"{c0}+{c1}" for c0, c1, _ in result.scores)
        print(f"{result.name},{result.total},{result.total / len(frames):.3f},{details}")

    print("raw49_msb_prefixes=")
    for index, frame in enumerate(frames):
        bits = bytes_to_bits(frame, "forward", "msb")[:49]
        value = 0
        for bit in bits:
            value = (value << 1) | bit
        print(f"  {index:02d}: {value:013x} (pad7={frame[6] & 0x7f:02x}, tail={frame[7]:02x}{frame[8]:02x})")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
