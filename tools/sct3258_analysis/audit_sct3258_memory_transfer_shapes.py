#!/usr/bin/env python3
"""Audit immediate-load and mirrored memory-transfer shapes in uncoded SCT code."""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path


BOOT_BASE = 0xF800


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def words_le(data: bytes) -> list[int]:
    if len(data) % 2:
        raise ValueError("word-oriented input has an odd byte count")
    return [int.from_bytes(data[offset:offset + 2], "little")
            for offset in range(0, len(data), 2)]


def artifact(path: Path, data: bytes) -> dict[str, object]:
    return {"path": str(path.resolve()), "size": len(data), "sha256": sha256(data)}


def records_from_bin(data: bytes, base: int, end_exclusive: int | None = None,
                     skip_fill: bool = False) -> list[tuple[int, int]]:
    result = []
    for index, word in enumerate(words_le(data)):
        address = base + index
        if end_exclusive is not None and address >= end_exclusive:
            break
        if skip_fill and word == 0xFFFF:
            continue
        result.append((address, word))
    return result


def immediate_pairs(records: list[tuple[int, int]]) -> list[dict[str, object]]:
    by_address = dict(records)
    result = []
    for address, first in records:
        second = by_address.get(address + 1)
        if second is None:
            continue
        first_major = first >> 12
        second_major = second >> 12
        if {first_major, second_major} != {0x2, 0x3}:
            continue
        first_register = first >> 8 & 0xF
        second_register = second >> 8 & 0xF
        if first_register != second_register:
            continue
        low = first & 0xFF if first_major == 0x2 else second & 0xFF
        high = first & 0xFF if first_major == 0x3 else second & 0xFF
        result.append({
            "start_address": address,
            "start_address_hex": f"0x{address:04x}",
            "words_hex": [f"0x{first:04x}", f"0x{second:04x}"],
            "order": "low-then-high" if first_major == 0x2 else "high-then-low",
            "register_field_hex": f"0x{first_register:x}",
            "combined_value": high << 8 | low,
            "combined_value_hex": f"0x{high:02x}{low:02x}",
        })
    return result


def transfer_record(address: int, word: int) -> dict[str, object]:
    return {
        "address": address,
        "address_hex": f"0x{address:04x}",
        "word_hex": f"0x{word:04x}",
        "major_hex": f"0x{word >> 12:x}",
        "data_field_hex": f"0x{word >> 8 & 0xF:x}",
        "mode_field_hex": f"0x{word >> 4 & 0xF:x}",
        "address_field_hex": f"0x{word & 0xF:x}",
    }


def transfers(records: list[tuple[int, int]]) -> list[dict[str, object]]:
    return [transfer_record(address, word) for address, word in records
            if word >> 12 in (0x6, 0x7)]


def mirrored_transfer_pairs(records: list[tuple[int, int]]) -> list[dict[str, object]]:
    by_address = dict(records)
    pairs = []
    for address, first in records:
        second = by_address.get(address + 1)
        if first >> 12 != 0x7 or second is None or second >> 12 != 0x6:
            continue
        first_data = first >> 8 & 0xF
        first_mode = first >> 4 & 0xF
        first_address = first & 0xF
        second_data = second >> 8 & 0xF
        second_mode = second >> 4 & 0xF
        second_address = second & 0xF
        if first_data != second_data or first_mode != second_mode:
            continue
        pairs.append({
            "start_address": address,
            "start_address_hex": f"0x{address:04x}",
            "read_like": transfer_record(address, first),
            "write_like": transfer_record(address + 1, second),
            "source_address_field_hex": f"0x{first_address:x}",
            "destination_address_field_hex": f"0x{second_address:x}",
        })
    for item in pairs:
        item["reverse_pair_start_addresses_hex"] = [
            other["start_address_hex"] for other in pairs
            if item["source_address_field_hex"] == other["destination_address_field_hex"]
            and item["destination_address_field_hex"] == other["source_address_field_hex"]
            and item["read_like"]["data_field_hex"] == other["read_like"]["data_field_hex"]
            and item["read_like"]["mode_field_hex"] == other["read_like"]["mode_field_hex"]
            and item["start_address"] != other["start_address"]
        ]
    return pairs


def analyze(loader_path: Path, bootled_path: Path, sample_report_path: Path) -> dict[str, object]:
    loader_data = loader_path.read_bytes()
    bootled_data = bootled_path.read_bytes()
    sample_data = sample_report_path.read_bytes()
    if len(loader_data) != 2048 or len(bootled_data) != 2048:
        raise ValueError("loader and BootLED inputs must each be 2048 bytes")
    sample_report = json.loads(sample_data.decode("ascii"))
    if sample_report.get("format") != "sct3258-flash-data136-analysis-v1":
        raise ValueError("unexpected FLASH_DATA136 analysis format")

    programs = {
        "loader_structured_region": records_from_bin(loader_data, BOOT_BASE, 0xFB8C),
        "bootled_nonfill_region": records_from_bin(bootled_data, BOOT_BASE, skip_fill=True),
        "flash_data136_uncoded_instruction": [
            (int(item["address"]), int(item["word_hex"], 16))
            for item in sample_report["flash_data136"]["addressed_words"]
        ],
    }
    bootled = dict(programs["bootled_nonfill_region"])
    expected_bootled = {
        0xF80D: 0x3AF8,
        0xF80E: 0x2A08,
        0xF80F: 0x2008,
        0xF810: 0x3003,
        0xF811: 0x600A,
        0xF81D: 0x600A,
    }
    for address, expected in expected_bootled.items():
        if bootled.get(address) != expected:
            raise ValueError(f"BootLED anchor mismatch at 0x{address:04x}")
    loader = dict(programs["loader_structured_region"])
    expected_loader = {
        0xFB29: 0x7CFD,
        0xFB2A: 0x6CF6,
        0xFB3F: 0x7CF6,
        0xFB40: 0x6CFD,
    }
    for address, expected in expected_loader.items():
        if loader.get(address) != expected:
            raise ValueError(f"loader transfer anchor mismatch at 0x{address:04x}")
    sample = dict(programs["flash_data136_uncoded_instruction"])
    expected_sample = {
        0x203E: 0x2508,
        0x203F: 0x35F8,
        0x2040: 0x2405,
        0x2041: 0x3430,
        0x2042: 0x7044,
        0x2043: 0x6045,
        0x2064: 0x2508,
        0x2065: 0x35F8,
        0x2066: 0x2405,
        0x2067: 0x3430,
        0x2068: 0x7044,
        0x2069: 0x6045,
    }
    for address, expected in expected_sample.items():
        if sample.get(address) != expected:
            raise ValueError(f"uncoded sample transfer anchor mismatch at 0x{address:04x}")

    per_program = {}
    for name, records in programs.items():
        pairs = immediate_pairs(records)
        transfer_items = transfers(records)
        mirror_items = mirrored_transfer_pairs(records)
        per_program[name] = {
            "immediate_low_high_pairs": pairs,
            "major_6_transfer_shapes": [item for item in transfer_items
                                         if item["major_hex"] == "0x6"],
            "major_7_transfer_shapes": [item for item in transfer_items
                                         if item["major_hex"] == "0x7"],
            "adjacent_7_then_6_same_data_mode_pairs": mirror_items,
        }

    loader_mirrors = per_program["loader_structured_region"][
        "adjacent_7_then_6_same_data_mode_pairs"
    ]
    anchor_mirrors = [item for item in loader_mirrors
                      if item["start_address"] in (0xFB29, 0xFB3F)]
    if len(anchor_mirrors) != 2 or any(
            not item["reverse_pair_start_addresses_hex"] for item in anchor_mirrors):
        raise ValueError("loader reverse-transfer mirror gate failed")

    return {
        "format": "sct3258-memory-transfer-shapes-v2",
        "artifacts": {
            "loader": artifact(loader_path, loader_data),
            "bootled": artifact(bootled_path, bootled_data),
            "flash_data136_analysis": artifact(sample_report_path, sample_data),
        },
        "field_models": {
            "major_2_3": (
                "[major 2/3][register-like field][8-bit immediate]; major 2 supplies "
                "the low byte and major 3 supplies the high byte"
            ),
            "major_6_7": (
                "[major 6/7][data-like field][mode-like field][address-like field]"
            ),
            "status": (
                "Supported structural models. Major 6 write-like and major 7 read-like "
                "roles are inferences from BootLED setup and exact reverse-copy shapes, "
                "not recovered vendor mnemonics."
            ),
        },
        "bootled_anchor": {
            "address_register_setup": "F80D 3AF8; F80E 2A08 -> field A = 0xF808",
            "data_register_setup": "F80F 2008; F810 3003 -> field 0 = 0x0308",
            "following_transfer": transfer_record(0xF811, bootled[0xF811]),
            "repeated_transfer": transfer_record(0xF81D, bootled[0xF81D]),
        },
        "flash_sample_anchor": {
            "first_sequence": {
                "destination_register_setup":
                    "203E 2508; 203F 35F8 -> field 5 = 0xF808",
                "source_register_setup":
                    "2040 2405; 2041 3430 -> field 4 = 0x3005",
                "read_like": transfer_record(0x2042, sample[0x2042]),
                "write_like": transfer_record(0x2043, sample[0x2043]),
            },
            "exact_repeat": {
                "destination_register_setup":
                    "2064 2508; 2065 35F8 -> field 5 = 0xF808",
                "source_register_setup":
                    "2066 2405; 2067 3430 -> field 4 = 0x3005",
                "read_like": transfer_record(0x2068, sample[0x2068]),
                "write_like": transfer_record(0x2069, sample[0x2069]),
            },
        },
        "loader_reverse_copy_anchor": anchor_mirrors,
        "per_program": per_program,
        "bounded_conclusion": (
            "Major 2/3 immediate halves are independently supported by BootLED and the "
            "uncoded 0x2000 sample. Both programs independently write through a major-6 "
            "shape to an address field loaded with 0xF808, and the sample first uses a "
            "major-7 shape to read from an address field loaded with 0x3005. The "
            "FB29/FB2A and FB3F/FB40 pairs reverse only their "
            "address-like fields while retaining data field C and mode F, supporting two "
            "opposite transfers between fields D and 6. This identifies a generic memory-"
            "transfer primitive shape, but neither endpoint is yet proven to be HPI, "
            "instruction RAM, data RAM, or a coded-state buffer."
        ),
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--loader", type=Path, required=True)
    parser.add_argument("--bootled", type=Path, required=True)
    parser.add_argument("--flash-sample-report", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if args.output.exists():
        parser.error(f"refusing to overwrite existing output: {args.output}")
    report = analyze(args.loader, args.bootled, args.flash_sample_report)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2, ensure_ascii=True) + "\n",
                           encoding="ascii", newline="\n")
    print(json.dumps({
        "format": report["format"],
        "bootled_anchor": report["bootled_anchor"],
        "flash_sample_anchor": report["flash_sample_anchor"],
        "loader_reverse_copy_anchor": report["loader_reverse_copy_anchor"],
        "per_program_counts": {
            name: {
                "immediate_pairs": len(item["immediate_low_high_pairs"]),
                "major_6": len(item["major_6_transfer_shapes"]),
                "major_7": len(item["major_7_transfer_shapes"]),
                "adjacent_7_6": len(item["adjacent_7_then_6_same_data_mode_pairs"]),
            } for name, item in report["per_program"].items()
        },
    }, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
