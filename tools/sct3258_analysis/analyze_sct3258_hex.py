#!/usr/bin/env python3
"""Parse the non-standard Intel HEX container used by SCT3258 updater files."""

from __future__ import annotations

import argparse
import hashlib
import json
import math
from collections import Counter
from dataclasses import dataclass
from pathlib import Path


@dataclass(frozen=True)
class Segment:
    selector: int
    start: int
    data: bytes

    @property
    def end(self) -> int:
        return self.start + len(self.data)


def entropy(data: bytes) -> float:
    if not data:
        return 0.0
    counts = Counter(data)
    size = len(data)
    return -sum((count / size) * math.log2(count / size) for count in counts.values())


def parse_record(line: str, line_number: int) -> tuple[int, int, int, bytes]:
    line = line.strip()
    if not line.startswith(":"):
        raise ValueError(f"line {line_number}: missing ':'")
    try:
        raw = bytes.fromhex(line[1:])
    except ValueError as error:
        raise ValueError(f"line {line_number}: invalid hexadecimal data") from error
    if len(raw) < 5:
        raise ValueError(f"line {line_number}: record is too short")
    length = raw[0]
    if len(raw) != length + 5:
        raise ValueError(
            f"line {line_number}: declared {length} data bytes, got {len(raw) - 5}"
        )
    if sum(raw) & 0xFF:
        raise ValueError(f"line {line_number}: checksum mismatch")
    address = int.from_bytes(raw[1:3], "big")
    record_type = raw[3]
    return length, address, record_type, raw[4:-1]


def parse_sct_hex(path: Path) -> tuple[list[Segment], dict[str, int]]:
    selector: int | None = None
    chunks: dict[int, list[tuple[int, bytes]]] = {}
    record_counts: Counter[int] = Counter()
    eof_seen = False

    for line_number, line in enumerate(path.read_text(encoding="ascii").splitlines(), 1):
        if not line.strip():
            continue
        length, address, record_type, data = parse_record(line, line_number)
        record_counts[record_type] += 1

        if record_type == 0x00:
            if selector is None:
                raise ValueError(f"line {line_number}: data before selector record")
            chunks.setdefault(selector, []).append((address, data))
        elif record_type == 0x04:
            # Sicomm stores the 16-bit upper-address selector in the record address
            # field and emits a zero-length payload. This differs from Intel HEX.
            if length != 0:
                raise ValueError(
                    f"line {line_number}: unsupported standard type-04 record"
                )
            selector = address
        elif record_type == 0x01:
            if length != 0 or address != 0:
                raise ValueError(f"line {line_number}: malformed EOF record")
            eof_seen = True
        else:
            raise ValueError(
                f"line {line_number}: unsupported record type 0x{record_type:02x}"
            )

    if not eof_seen:
        raise ValueError("missing EOF record")

    segments: list[Segment] = []
    for current_selector, records in sorted(chunks.items()):
        records.sort()
        run_start = records[0][0]
        expected = run_start
        payload = bytearray()
        for address, data in records:
            if address < expected:
                raise ValueError(
                    f"selector 0x{current_selector:04x}: overlap at "
                    f"0x{address:04x}, previous end 0x{expected:04x}"
                )
            if address > expected:
                start = (current_selector << 16) | run_start
                segments.append(Segment(current_selector, start, bytes(payload)))
                run_start = address
                payload = bytearray()
                expected = address
            payload.extend(data)
            expected += len(data)
        start = (current_selector << 16) | run_start
        segments.append(Segment(current_selector, start, bytes(payload)))

    return segments, {f"0x{key:02x}": value for key, value in sorted(record_counts.items())}


def longest_printable(data: bytes) -> int:
    longest = current = 0
    for value in data:
        if 0x20 <= value <= 0x7E:
            current += 1
            longest = max(longest, current)
        else:
            current = 0
    return longest


def analyze(path: Path, output_dir: Path | None) -> dict[str, object]:
    segments, record_counts = parse_sct_hex(path)
    report: dict[str, object] = {
        "path": str(path),
        "source_size": path.stat().st_size,
        "source_sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
        "record_counts": record_counts,
        "segments": [],
    }

    segment_reports = []
    for segment in segments:
        segment_reports.append(
            {
                "selector": segment.selector,
                "start": segment.start,
                "end_exclusive": segment.end,
                "length": len(segment.data),
                "sha256": hashlib.sha256(segment.data).hexdigest(),
                "entropy_bits_per_byte": round(entropy(segment.data), 6),
                "longest_printable_run": longest_printable(segment.data),
                "first_16": segment.data[:16].hex(),
                "last_16": segment.data[-16:].hex(),
            }
        )
    report["segments"] = segment_reports
    report["payload_bytes"] = sum(len(segment.data) for segment in segments)

    if output_dir is not None:
        output_dir.mkdir(parents=True, exist_ok=True)
        stem = path.stem
        for segment in segments:
            target = output_dir / (
                f"{stem}.segment-{segment.selector:04x}-0x{segment.start:08x}.bin"
            )
            target.write_bytes(segment.data)
        report_path = output_dir / f"{stem}.analysis.json"
        report_path.write_text(
            json.dumps(report, indent=2, ensure_ascii=True) + "\n", encoding="ascii"
        )

    return report


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("hex_files", type=Path, nargs="+")
    parser.add_argument("--output-dir", type=Path)
    args = parser.parse_args()

    reports = [analyze(path, args.output_dir) for path in args.hex_files]
    print(json.dumps(reports, indent=2, ensure_ascii=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
