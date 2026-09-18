#!/usr/bin/env python3
"""Compare decoded address ranges across SCT3258 updater HEX files."""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path

from analyze_sct3258_hex import Segment, parse_sct_hex


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def slice_at(segments: list[Segment], start: int, end: int) -> bytes | None:
    for segment in segments:
        if segment.start <= start and end <= segment.end:
            offset = start - segment.start
            return segment.data[offset : offset + end - start]
    return None


def matching_bytes(left: bytes, right: bytes) -> int:
    return sum(a == b for a, b in zip(left, right, strict=True))


def common_prefix_length(left: bytes, right: bytes) -> int:
    for index, (a, b) in enumerate(zip(left, right)):
        if a != b:
            return index
    return min(len(left), len(right))


def compare(paths: list[Path]) -> dict[str, object]:
    parsed = {path.stem: parse_sct_hex(path)[0] for path in paths}
    boundaries = sorted(
        {
            boundary
            for segments in parsed.values()
            for segment in segments
            for boundary in (segment.start, segment.end)
        }
    )

    intervals: list[dict[str, object]] = []
    names = list(parsed)
    for start, end in zip(boundaries, boundaries[1:]):
        payloads = {
            name: slice_at(segments, start, end) for name, segments in parsed.items()
        }
        if any(payload is None for payload in payloads.values()):
            continue
        present = {name: payload for name, payload in payloads.items() if payload is not None}
        pairs: dict[str, object] = {}
        for left_index, left_name in enumerate(names):
            for right_name in names[left_index + 1 :]:
                count = matching_bytes(present[left_name], present[right_name])
                pairs[f"{left_name}__{right_name}"] = {
                    "matching_bytes": count,
                    "matching_fraction": round(count / (end - start), 9),
                }
        intervals.append(
            {
                "start": start,
                "end_exclusive": end,
                "length": end - start,
                "all_identical": len(set(present.values())) == 1,
                "sha256_by_version": {
                    name: hashlib.sha256(payload).hexdigest()
                    for name, payload in present.items()
                },
                "pairwise": pairs,
            }
        )

    repeated_prefixes: dict[str, object] = {}
    for name, segments in parsed.items():
        first = slice_at(segments, 0x000100, 0x010000)
        mirror = slice_at(segments, 0x068000, 0x070000)
        if first is not None and mirror is not None:
            length = common_prefix_length(first, mirror)
            repeated_prefixes[name] = {
                "left_start": 0x000100,
                "right_start": 0x068000,
                "common_prefix_bytes": length,
                "common_prefix_sha256": hashlib.sha256(first[:length]).hexdigest(),
            }

    return {
        "format": "sct3258-cross-version-comparison-v1",
        "inputs": [
            {
                "name": path.stem,
                "path": str(path.resolve()),
                "size": path.stat().st_size,
                "sha256": sha256(path),
            }
            for path in paths
        ],
        "common_address_intervals": intervals,
        "within_version_repeated_prefixes": repeated_prefixes,
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("hex_files", type=Path, nargs="+")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()

    if len(args.hex_files) < 2:
        parser.error("at least two HEX files are required")
    if args.output.exists():
        parser.error(f"refusing to overwrite existing output: {args.output}")

    report = compare(args.hex_files)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(
        json.dumps(report, indent=2, ensure_ascii=True) + "\n", encoding="ascii"
    )
    print(json.dumps(report, indent=2, ensure_ascii=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
