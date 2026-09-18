#!/usr/bin/env python3
"""Audit nibble-level major-A producer/consumer constraints in the SCT3258 loader."""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path


BOOT_BASE = 0xF800
TARGET_RANGES = ((0xFB1A, 0xFB46), (0xFB5A, 0xFB79))
EXPECTED_WORDS = {
    0xFB21: 0xA906,
    0xFB22: 0xA593,
    0xFB2E: 0xA806,
    0xFB37: 0xA907,
    0xFB38: 0xA593,
    0xFB44: 0xA807,
    0xFB6C: 0xA592,
    0xFB6E: 0xA907,
    0xFB70: 0xA807,
    0xFB73: 0xA906,
    0xFB76: 0xA806,
}


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def words_le(data: bytes) -> list[int]:
    if len(data) != 2048:
        raise ValueError("expected an exact 2048-byte Boot RAM image")
    return [int.from_bytes(data[offset:offset + 2], "little")
            for offset in range(0, len(data), 2)]


def fields(address: int, word: int) -> dict[str, object]:
    return {
        "address": address,
        "address_hex": f"0x{address:04x}",
        "word_hex": f"0x{word:04x}",
        "major_hex": f"0x{word >> 12:x}",
        "result_like_field_hex": f"0x{(word >> 8) & 0xF:x}",
        "input_like_field_1_hex": f"0x{(word >> 4) & 0xF:x}",
        "input_like_field_2_hex": f"0x{word & 0xF:x}",
    }


def major_a_records(words: list[int], start: int, end: int) -> list[dict[str, object]]:
    return [
        fields(address, words[address - BOOT_BASE])
        for address in range(start, end)
        if words[address - BOOT_BASE] >> 12 == 0xA
    ]


def last_definition(words: list[int], field: int, before: int) -> dict[str, object] | None:
    for address in range(before - 1, BOOT_BASE - 1, -1):
        word = words[address - BOOT_BASE]
        if word >> 12 == 0xA and ((word >> 8) & 0xF) == field:
            return fields(address, word)
    return None


def analyze(loader_path: Path) -> dict[str, object]:
    data = loader_path.read_bytes()
    words = words_le(data)
    for address, expected in EXPECTED_WORDS.items():
        actual = words[address - BOOT_BASE]
        if actual != expected:
            raise ValueError(
                f"anchor mismatch at 0x{address:04x}: 0x{actual:04x} != 0x{expected:04x}"
            )

    ranges = [
        {
            "start_hex": f"0x{start:04x}",
            "end_exclusive_hex": f"0x{end:04x}",
            "major_a_words": major_a_records(words, start, end),
        }
        for start, end in TARGET_RANGES
    ]
    producer_consumer = [
        {
            "producer": fields(0xFB21, words[0xFB21 - BOOT_BASE]),
            "consumer": fields(0xFB22, words[0xFB22 - BOOT_BASE]),
            "shared_field_hex": "0x9",
            "distance_words": 1,
        },
        {
            "producer": fields(0xFB37, words[0xFB37 - BOOT_BASE]),
            "consumer": fields(0xFB38, words[0xFB38 - BOOT_BASE]),
            "shared_field_hex": "0x9",
            "distance_words": 1,
        },
    ]
    tight = fields(0xFB6C, words[0xFB6C - BOOT_BASE])
    body = major_a_records(words, 0xFB6E, 0xFB79)
    body_result_fields = {item["result_like_field_hex"] for item in body}
    return {
        "format": "sct3258-major-a-nibble-dataflow-v1",
        "loader": {
            "path": str(loader_path.resolve()),
            "size": len(data),
            "sha256": sha256(data),
        },
        "field_model": {
            "layout": "[major][result-like field][input-like field 1][input-like field 2]",
            "status": (
                "Supported by repeated local producer/consumer shapes; field direction and "
                "register semantics remain hypotheses, not recovered ISA mnemonics."
            ),
        },
        "target_ranges": ranges,
        "immediate_field9_producer_consumer_pairs": producer_consumer,
        "tight_loop_operation": tight,
        "tight_loop_input_like_fields": ["0x9", "0x2"],
        "major_a_result_like_fields_written_in_fb6e_fb78": sorted(body_result_fields),
        "field9_written_in_loop_body": "0x9" in body_result_fields,
        "field2_written_in_loop_body": "0x2" in body_result_fields,
        "last_major_a_definitions_before_fb6c": {
            "field_9": last_definition(words, 0x9, 0xFB6C),
            "field_2": last_definition(words, 0x2, 0xFB6C),
        },
        "mechanical_constraints": [
            "FB21 result-like 9 is consumed as FB22 input-like field 1 one word later.",
            "FB37 result-like 9 is consumed as FB38 input-like field 1 one word later.",
            "FB6C consumes input-like fields 9 and 2 to produce result-like field 5.",
            "FB6E..FB78 writes result-like field 9 but does not write result-like field 2.",
            "The FB78 back edge returns to the FB6D condition, which may re-enter FB6C.",
        ],
        "bounded_conclusion": (
            "The repeated producer/consumer chains materially support the result/input field "
            "ordering for major-A words. FB6C is consistent with a loop predicate computed "
            "from changing field 9 and locally invariant field 2, but arithmetic and hardware "
            "roles remain unknown."
        ),
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--loader", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if args.output.exists():
        parser.error(f"refusing to overwrite existing output: {args.output}")
    report = analyze(args.loader)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2, ensure_ascii=True) + "\n",
                           encoding="ascii", newline="\n")
    print(json.dumps({
        "format": report["format"],
        "field_model": report["field_model"],
        "mechanical_constraints": report["mechanical_constraints"],
        "last_major_a_definitions_before_fb6c":
            report["last_major_a_definitions_before_fb6c"],
    }, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
