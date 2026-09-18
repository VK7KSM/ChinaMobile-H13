#!/usr/bin/env python3
"""Container recovery: HPI SPEECH LENGTH=323 → 8 kHz s16le WAV.

IMPORTANT (ChatGPT 2026-08-08 review / corrective P1-P2):
  This pipeline proves framing, duration, and WAV container integrity only.
  It does NOT prove the payload is decoded RX voice PCM.
  Do not call outputs "golden speech" without score_speech_likeness + operator.

Framing contract (P1 L0 only):
  SPEECH type=0x30 LENGTH=323 count=160
  Normal wire=328 → 318 sample bytes (159 s16) + trailing pad 0x00
  Product frame = 160 s16 (pad 1 zero sample when short); record gap in gaps.csv
"""

from __future__ import annotations

import argparse
import csv
import hashlib
import json
import math
import struct
import wave
from pathlib import Path

SYNC = b"\x84\xa9\x61"


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def is_plausible_header(data: bytes, offset: int) -> bool:
    if offset + 9 > len(data):
        return False
    if data[offset : offset + 3] != SYNC:
        return False
    body_length = int.from_bytes(data[offset + 3 : offset + 5], "big")
    packet_type = data[offset + 5]
    field = data[offset + 6]
    if packet_type not in (0x00, 0x20, 0x30):
        return False
    if body_length < 1 or body_length > 2048:
        return False
    if body_length == 323 and field == 0x00:
        return int.from_bytes(data[offset + 7 : offset + 9], "big") == 160
    if body_length == 29 and field == 0x01:
        return True
    if body_length == 2 and field in (0x01, 0x3E, 0x7F, 0x18, 0x1A, 0x1B):
        return True
    if body_length <= 4:
        return True
    return body_length <= 1283


def paper_wire(body_length: int) -> int:
    u = 6 + body_length
    return u + (u & 1)


def extract_speech_frames(data: bytes) -> list[dict]:
    frames: list[dict] = []
    offset = 0
    n = len(data)
    while offset + 9 <= n:
        found = -1
        cursor = offset
        while cursor + 9 <= n:
            hit = data.find(SYNC, cursor)
            if hit < 0:
                break
            if is_plausible_header(data, hit):
                found = hit
                break
            cursor = hit + 1
        if found < 0:
            break
        offset = found
        body_length = int.from_bytes(data[offset + 3 : offset + 5], "big")
        packet_type = data[offset + 5]
        field = data[offset + 6]
        expected = paper_wire(body_length)
        search_from = offset + 6
        search_to = min(n - 2, offset + max(expected, body_length + 6) + 8)
        next_hdr = -1
        for i in range(search_from, search_to):
            if data[i : i + 3] == SYNC and is_plausible_header(data, i):
                next_hdr = i
                break
        if next_hdr < 0:
            next_hdr = n
        distance = next_hdr - offset
        if 8 <= distance < expected and next_hdr < n:
            wire = distance
        elif next_hdr < n and next_hdr < offset + expected:
            wire = distance
        elif offset + expected <= n:
            wire = expected
        else:
            wire = max(1, n - offset)

        if body_length == 323 and field == 0x00 and packet_type == 0x30:
            end = offset + wire
            sample = data[offset + 9 : end]
            if sample and sample[-1] == 0 and (len(sample) & 1):
                sample = sample[:-1]
            if len(sample) & 1:
                sample = sample[:-1]
            sample = sample[:320]
            n_samp = len(sample) // 2
            missing = max(0, 160 - n_samp)
            method = "none"
            if missing:
                method = "zero_pad"
                sample = sample + (b"\x00\x00" * missing)
            elif n_samp > 160:
                sample = sample[:320]
                missing = 0
            frames.append(
                {
                    "index": len(frames),
                    "offset": offset,
                    "wire": wire,
                    "sample_bytes_raw": n_samp * 2,
                    "samples_out": 160,
                    "missing_samples": missing,
                    "pad_method": method,
                    "pcm160": sample[:320],
                }
            )
        if wire <= 0:
            break
        offset += wire
    return frames


def rms_s16(pcm: bytes) -> float:
    if len(pcm) < 2:
        return 0.0
    if len(pcm) & 1:
        pcm = pcm[:-1]
    samples = [v[0] for v in struct.iter_unpack("<h", pcm)]
    if not samples:
        return 0.0
    return math.sqrt(sum(s * s for s in samples) / len(samples))


def run_pipeline(capture: Path, output: Path) -> dict:
    data = capture.read_bytes()
    frames = extract_speech_frames(data)
    output.mkdir(parents=True, exist_ok=False)

    unpadded_parts = []
    padded = bytearray()
    gaps = []
    for f in frames:
        raw_n = f["sample_bytes_raw"]
        unpadded_parts.append(f["pcm160"][:raw_n] if raw_n <= 320 else f["pcm160"][:320])
        # For unpadded stream use available only (no pad)
        padded.extend(f["pcm160"])
        if f["missing_samples"]:
            gaps.append(
                {
                    "frame_index": f["index"],
                    "offset": f["offset"],
                    "wire": f["wire"],
                    "missing_samples": f["missing_samples"],
                    "method": f["pad_method"],
                }
            )

    # unpadded = concatenate available only
    available = b""
    for f in frames:
        end = f["sample_bytes_raw"]
        available += f["pcm160"][:end]

    product = bytes(padded)
    (output / "pcm_unpadded_s16le.raw").write_bytes(available)
    (output / "pcm_padded_160_s16le.raw").write_bytes(product)
    with wave.open(str(output / "pcm_padded_160_8k.wav"), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(8000)
        w.writeframes(product)

    with (output / "gaps.csv").open("w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(
            f,
            fieldnames=["frame_index", "offset", "wire", "missing_samples", "method"],
        )
        writer.writeheader()
        writer.writerows(gaps)

    wire_counts: dict[int, int] = {}
    for f in frames:
        wire_counts[f["wire"]] = wire_counts.get(f["wire"], 0) + 1

    meta = {
        "capture": capture.name,
        "capture_sha256": sha256(data),
        "capture_bytes": len(data),
        "speech_frames": len(frames),
        "wire_counts": {str(k): v for k, v in sorted(wire_counts.items())},
        "gap_frames": len(gaps),
        "missing_samples_total": sum(g["missing_samples"] for g in gaps),
        "available_bytes": len(available),
        "available_duration_ms": len(available) / 16.0,
        "product_bytes": len(product),
        "product_duration_ms": len(product) / 16.0,
        "product_frames_x_20ms": len(frames) * 20,
        "rms_available": round(rms_s16(available), 3),
        "rms_product": round(rms_s16(product), 3),
        "available_sha256": sha256(available),
        "product_sha256": sha256(product),
        "pad_policy": "zero_pad_to_160_when_wire328",
    }
    (output / "raw_meta.json").write_text(
        json.dumps(meta, indent=2) + "\n", encoding="utf-8"
    )
    report = [
        f"capture={capture.name} bytes={len(data)} sha256={sha256(data)}",
        f"speech_frames={len(frames)} wire_counts={wire_counts}",
        f"gap_frames={len(gaps)} missing_samples_total={meta['missing_samples_total']}",
        f"available_duration_ms={meta['available_duration_ms']:.1f} rms={meta['rms_available']}",
        f"product_duration_ms={meta['product_duration_ms']:.1f} "
        f"(frames*20ms={meta['product_frames_x_20ms']}) rms={meta['rms_product']}",
        f"product_sha256={meta['product_sha256']}",
    ]
    (output / "pipeline_report.txt").write_text("\n".join(report) + "\n", encoding="utf-8")
    return meta


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("captures", nargs="+", type=Path)
    ap.add_argument("--output-root", type=Path, required=True)
    args = ap.parse_args()
    args.output_root.mkdir(parents=True, exist_ok=True)
    for cap in args.captures:
        out = args.output_root / (cap.stem + "_product")
        if out.exists():
            # timestamped sibling
            import time

            out = args.output_root / f"{cap.stem}_product_{int(time.time())}"
        meta = run_pipeline(cap, out)
        print(json.dumps(meta, indent=2))


if __name__ == "__main__":
    main()
