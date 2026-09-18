#!/usr/bin/env python3
"""Parse the v0.61 SCT3258 HPI captures without modifying source evidence."""

from __future__ import annotations

import argparse
import csv
import hashlib
import math
import struct
import wave
from pathlib import Path


SYNC = b"\x84\xa9\x61"


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def is_plausible_header(data: bytes, offset: int) -> bool:
    """P1: require LENGTH/type/field sanity — not bare 84 A9 61."""
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
    known_control = body_length == 2 and field in (0x01, 0x3E, 0x7F, 0x18, 0x1A, 0x1B)
    chan_d = body_length == 29 and field == 0x01
    pcm = (
        body_length == 323
        and field == 0x00
        and int.from_bytes(data[offset + 7 : offset + 9], "big") == 160
    )
    if known_control or chan_d or pcm or body_length in (1, 2, 4):
        return True
    return body_length <= 1283


def plausible_offsets(data: bytes) -> list[int]:
    offsets: list[int] = []
    cursor = 0
    while True:
        offset = data.find(SYNC, cursor)
        if offset < 0:
            return offsets
        cursor = offset + 1
        if is_plausible_header(data, offset):
            offsets.append(offset)


def write_wav(path: Path, pcm: bytes) -> None:
    with wave.open(str(path), "wb") as output:
        output.setnchannels(1)
        output.setsampwidth(2)
        output.setframerate(8000)
        output.writeframes(pcm)


def sample_stats(pcm: bytes) -> dict[str, float | int]:
    samples = [value[0] for value in struct.iter_unpack("<h", pcm)]
    if not samples:
        return {"samples": 0, "minimum": 0, "maximum": 0, "mean": 0.0, "rms": 0.0}
    mean = sum(samples) / len(samples)
    rms = math.sqrt(sum(value * value for value in samples) / len(samples))
    return {
        "samples": len(samples),
        "minimum": min(samples),
        "maximum": max(samples),
        "mean": mean,
        "rms": rms,
    }


def parse_capture(path: Path) -> tuple[list[dict[str, object]], bytes, list[bytes]]:
    data = path.read_bytes()
    offsets = plausible_offsets(data)
    rows: list[dict[str, object]] = []
    chan_d_units: list[bytes] = []
    pcm_frames: list[bytes] = []

    for index, offset in enumerate(offsets):
        next_offset = offsets[index + 1] if index + 1 < len(offsets) else len(data)
        body_length = int.from_bytes(data[offset + 3 : offset + 5], "big")
        packet_type = data[offset + 5]
        field = data[offset + 6]
        distance = next_offset - offset
        unpadded_wire = 6 + body_length
        expected_wire = unpadded_wire + (unpadded_wire & 1)
        body_available = max(0, min(body_length, next_offset - (offset + 6)))
        classification = "control"
        inner_count = ""
        payload_hash = ""

        if body_length == 2 and field == 0x7F:
            classification = "dmr_slot_found"
            inner_count = data[offset + 7] if offset + 8 <= len(data) else ""
        elif body_length == 29 and field == 0x01:
            inner_count = data[offset + 7]
            classification = "chan_d_27"
            if body_available >= 29 and inner_count == 27:
                payload = data[offset + 8 : offset + 35]
                chan_d_units.append(payload)
                payload_hash = sha256(payload)
            else:
                classification = "chan_d_truncated"
        elif body_length == 323 and field == 0x00:
            inner_count = int.from_bytes(data[offset + 7 : offset + 9], "big")
            # H13@230400: next SYNC is always at +328 while LENGTH claims 323
            # (paper wire 330). Last byte before next SYNC is almost always 0x00
            # (pad). Effective body ≈ 321 → field+count+318 sample bytes
            # (159 s16le). Do not read into the next SYNC.
            if distance == 328 and next_offset > offset + 9:
                # Prefer [+9, next) but drop trailing pad 0x00 if present.
                sample_bytes = data[offset + 9 : next_offset]
                if sample_bytes and sample_bytes[-1] == 0 and (len(sample_bytes) & 1):
                    sample_bytes = sample_bytes[:-1]
                if len(sample_bytes) & 1:
                    sample_bytes = sample_bytes[:-1]
                # Cap at 320; typical is 318.
                sample_bytes = sample_bytes[:320]
            else:
                available_samples = max(0, min(320, next_offset - (offset + 9)))
                sample_bytes = data[offset + 9 : offset + 9 + available_samples]
                if len(sample_bytes) & 1:
                    sample_bytes = sample_bytes[:-1]
            pcm_frames.append(sample_bytes)
            if len(sample_bytes) == 320:
                classification = "pcm_160_complete"
            elif distance == 328 and len(sample_bytes) >= 318:
                classification = "pcm_160_wire328"
            else:
                classification = "pcm_160_partial"
            payload_hash = sha256(sample_bytes)
        elif body_length in (1, 2):
            classification = "control"

        rows.append(
            {
                "capture": path.name,
                "offset_hex": f"0x{offset:04x}",
                "body_length": body_length,
                "packet_type_hex": f"0x{packet_type:02x}",
                "field_hex": f"0x{field:02x}",
                "inner_count": inner_count,
                "next_offset_hex": f"0x{next_offset:04x}",
                "distance": distance,
                "expected_wire": expected_wire,
                "wire_delta": distance - expected_wire,
                "body_available": body_available,
                "classification": classification,
                "payload_sha256": payload_hash,
            }
        )

    return rows, b"".join(chan_d_units), pcm_frames


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("captures", nargs="+", type=Path)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=False)

    all_rows: list[dict[str, object]] = []
    report: list[str] = []
    all_pcm_frames: list[bytes] = []

    for capture in args.captures:
        rows, chan_d, pcm_frames = parse_capture(capture)
        all_rows.extend(rows)
        report.append(
            f"{capture.name}: bytes={capture.stat().st_size} sha256={sha256(capture.read_bytes())} "
            f"frames={len(rows)} chan_d_units={len(chan_d) // 27} pcm_frames={len(pcm_frames)}"
        )
        if chan_d:
            destination = args.output / f"{capture.stem}_chan_d27.bin"
            destination.write_bytes(chan_d)
            report.append(
                f"  {destination.name}: bytes={len(chan_d)} units={len(chan_d) // 27} "
                f"sha256={sha256(chan_d)}"
            )
        all_pcm_frames.extend(pcm_frames)

    inventory = args.output / "hpi_frame_inventory.csv"
    with inventory.open("w", newline="", encoding="ascii") as output:
        writer = csv.DictWriter(output, fieldnames=list(all_rows[0].keys()))
        writer.writeheader()
        writer.writerows(all_rows)

    if all_pcm_frames:
        complete_frames = [frame for frame in all_pcm_frames if len(frame) == 320]
        # Prefer full 320; else use longest even slice (wire328 → 318 B typical).
        usable_frames = [
            frame if len(frame) == 320 else frame[: len(frame) - (len(frame) & 1)]
            for frame in all_pcm_frames
            if len(frame) >= 2
        ]
        available_pcm = b"".join(all_pcm_frames)
        gap_padded_pcm = b"".join(frame.ljust(320, b"\x00") for frame in all_pcm_frames)
        complete_pcm = b"".join(complete_frames)
        # Pad each usable frame to 320 for continuous 8 kHz play (last 1 sample
        # zero when wire328 / 318 B).
        wire328_padded = b"".join(f.ljust(320, b"\x00") for f in usable_frames)

        for name, pcm in (
            ("voice_out_pcm_available_s16le.raw", available_pcm),
            ("voice_out_pcm_gap_padded_8k_s16le.raw", gap_padded_pcm),
            ("voice_out_pcm_complete_frames_s16le.raw", complete_pcm),
            ("voice_out_pcm_wire328_padded_8k_s16le.raw", wire328_padded),
        ):
            destination = args.output / name
            destination.write_bytes(pcm)
            report.append(f"{name}: bytes={len(pcm)} sha256={sha256(pcm)} stats={sample_stats(pcm)}")

        write_wav(args.output / "voice_out_pcm_gap_padded_8k_s16le.wav", gap_padded_pcm)
        write_wav(args.output / "voice_out_pcm_complete_frames_8k_s16le.wav", complete_pcm)
        write_wav(args.output / "voice_out_pcm_wire328_padded_8k_s16le.wav", wire328_padded)
        wire328_n = sum(1 for f in all_pcm_frames if 300 <= len(f) < 320)
        report.append(
            f"PCM frames: total={len(all_pcm_frames)} complete320={len(complete_frames)} "
            f"wire328ish={wire328_n} other={len(all_pcm_frames) - len(complete_frames) - wire328_n} "
            f"gap_padded_duration_ms={len(gap_padded_pcm) / 16.0:.1f} "
            f"wire328_padded_duration_ms={len(wire328_padded) / 16.0:.1f}"
        )

    report.append(f"hpi_frame_inventory.csv: rows={len(all_rows)} sha256={sha256(inventory.read_bytes())}")
    report_path = args.output / "analysis_report.txt"
    report_path.write_text("\n".join(report) + "\n", encoding="ascii")
    print(report_path.read_text(encoding="ascii"), end="")


if __name__ == "__main__":
    main()
