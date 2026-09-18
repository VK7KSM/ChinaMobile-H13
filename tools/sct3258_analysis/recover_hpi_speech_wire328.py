#!/usr/bin/env python3
"""Recover H13 SPEECH PCM when wire stride is 328 and LENGTH claims 323."""
from __future__ import annotations
import argparse, struct, wave, math, hashlib
from pathlib import Path

SYNC = b"\x84\xa9\x61"

def sha256(b: bytes) -> str:
    return hashlib.sha256(b).hexdigest()

def recover(data: bytes) -> tuple[list[bytes], list[int]]:
    offs, i = [], 0
    while True:
        j = data.find(SYNC, i)
        if j < 0:
            break
        offs.append(j)
        i = j + 1
    frames, strides = [], []
    for idx, o in enumerate(offs):
        if o + 12 > len(data):
            continue
        bl = int.from_bytes(data[o + 3 : o + 5], "big")
        if data[o + 5] != 0x30 or data[o + 6] != 0 or bl != 323:
            continue
        if int.from_bytes(data[o + 7 : o + 9], "big") != 160:
            continue
        nxt = offs[idx + 1] if idx + 1 < len(offs) else len(data)
        dist = nxt - o
        strides.append(dist)
        sample = data[o + 9 : nxt]
        if sample and sample[-1] == 0 and (len(sample) & 1):
            sample = sample[:-1]
        if len(sample) & 1:
            sample = sample[:-1]
        sample = sample[:320]
        if sample:
            frames.append(sample)
    return frames, strides

def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("capture", type=Path)
    ap.add_argument("--output", type=Path, required=True)
    args = ap.parse_args()
    data = args.capture.read_bytes()
    frames, strides = recover(data)
    args.output.mkdir(parents=True, exist_ok=False)
    available = b"".join(frames)
    padded = b"".join(f.ljust(320, b"\x00") for f in frames)
    (args.output / "speech_wire328_available_s16le.raw").write_bytes(available)
    (args.output / "speech_wire328_pad320_s16le.raw").write_bytes(padded)
    with wave.open(str(args.output / "speech_wire328_pad320_8k.wav"), "wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(8000); w.writeframes(padded)
    n = len(available) // 2
    samples = list(struct.unpack("<%dh" % n, available)) if n else []
    rms = math.sqrt(sum(x * x for x in samples) / n) if n else 0.0
    report = [
        f"capture={args.capture.name} bytes={len(data)} sha256={sha256(data)}",
        f"speech_frames={len(frames)} stride_mode={max(set(strides), key=strides.count) if strides else None}",
        f"available_bytes={len(available)} samples={n} duration_ms={n/8.0:.1f} rms={rms:.2f}",
        f"pad320_bytes={len(padded)} duration_ms={len(padded)/16.0:.1f}",
        f"available_sha256={sha256(available)}",
        f"pad320_sha256={sha256(padded)}",
    ]
    (args.output / "recovery_report.txt").write_text("\n".join(report) + "\n", encoding="ascii")
    print("\n".join(report))

if __name__ == "__main__":
    main()
