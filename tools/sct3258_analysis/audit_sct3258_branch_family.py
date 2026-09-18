#!/usr/bin/env python3
"""Audit an expanded SCT3258 short-branch family using BootLED anchors."""

from __future__ import annotations

import argparse
import hashlib
import json
from collections import Counter
from pathlib import Path


BOOT_BASE = 0xF800
BOOT_END = 0xFC00
HPI_FALLTHROUGH = 0xFA2F

EXPECTED_BOOTLED_BRANCHES = (
    (0xF81A, 0x4EFC, 0xF817),
    (0xF81B, 0x4FF9, 0xF815),
    (0xF826, 0x4EFC, 0xF823),
    (0xF827, 0x4FF9, 0xF821),
    (0xF82B, 0x0FE7, 0xF813),
)

EXPECTED_LOADER_ANCHORS = (
    (0xFA2E, 0x44FB, 0xFA2A),
    (0xFA45, 0x44F7, 0xFA3D),
    (0xFA5A, 0x44F0, 0xFA4B),
    (0xFB28, 0x04F1, 0xFB1A),
    (0xFB3E, 0x04F1, 0xFB30),
    (0xFB78, 0x04F4, 0xFB6D),
)


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def words_le(data: bytes) -> list[int]:
    if len(data) != 2048:
        raise ValueError("expected an exact 2048-byte Boot RAM image")
    return [int.from_bytes(data[offset : offset + 2], "little")
            for offset in range(0, len(data), 2)]


def artifact(path: Path, data: bytes) -> dict[str, object]:
    return {
        "path": str(path.resolve()),
        "size": len(data),
        "sha256": sha256(data),
    }


def signed8(value: int) -> int:
    return value - 0x100 if value & 0x80 else value


def is_expanded_candidate(word: int) -> bool:
    opcode = word >> 8
    return is_old_narrow_candidate(word) or opcode >> 4 in (0x0, 0x4)


def is_old_narrow_candidate(word: int) -> bool:
    return ((word >> 8) & 0x3F) == 0x04


def branch_record(address: int, word: int) -> dict[str, object]:
    displacement = signed8(word & 0xFF)
    target = address + 1 + displacement
    return {
        "address": address,
        "address_hex": f"0x{address:04x}",
        "word": word,
        "word_hex": f"0x{word:04x}",
        "opcode_high_byte": word >> 8,
        "opcode_high_byte_hex": f"0x{word >> 8:02x}",
        "signed_low8_displacement_words": displacement,
        "target": target,
        "target_hex": f"0x{target:04x}",
        "target_inside_boot_ram": BOOT_BASE <= target < BOOT_END,
        "old_narrow_model": is_old_narrow_candidate(word),
    }


def candidates(words: list[int], start: int = BOOT_BASE,
               end: int = BOOT_END) -> list[dict[str, object]]:
    result = []
    for address in range(start, end):
        word = words[address - BOOT_BASE]
        if is_expanded_candidate(word):
            result.append(branch_record(address, word))
    return result


def require_anchors(words: list[int], expected: tuple[tuple[int, int, int], ...],
                    label: str) -> None:
    actual = []
    for address, expected_word, expected_target in expected:
        word = words[address - BOOT_BASE]
        record = branch_record(address, word)
        actual.append((address, word, int(record["target"])))
        if word != expected_word or int(record["target"]) != expected_target:
            raise ValueError(
                f"{label} anchor mismatch at 0x{address:04x}: "
                f"word=0x{word:04x} target={record['target_hex']}"
            )


def basic_blocks(words: list[int], records: list[dict[str, object]],
                 start: int, end: int) -> list[dict[str, object]]:
    in_range = [item for item in records
                if start <= int(item["address"]) < end]
    boundaries = {start, end}
    by_address = {int(item["address"]): item for item in in_range}
    for item in in_range:
        address = int(item["address"])
        target = int(item["target"])
        if start <= address + 1 <= end:
            boundaries.add(address + 1)
        if start <= target < end:
            boundaries.add(target)
    ordered = sorted(boundaries)
    blocks = []
    for first, last in zip(ordered, ordered[1:]):
        if first == last:
            continue
        terminator = by_address.get(last - 1)
        outgoing = []
        if terminator is None:
            outgoing.append({"kind": "sequential", "target_hex": f"0x{last:04x}"})
        else:
            target = int(terminator["target"])
            outgoing.append({
                "kind": "expanded_family_candidate_target",
                "target_hex": f"0x{target:04x}",
                "inside_hpi_region": start <= target < end,
            })
            outgoing.append({"kind": "fallthrough", "target_hex": f"0x{last:04x}"})
        blocks.append({
            "start_hex": f"0x{first:04x}",
            "end_exclusive_hex": f"0x{last:04x}",
            "word_count": last - first,
            "words_hex": [f"0x{word:04x}"
                          for word in words[first - BOOT_BASE:last - BOOT_BASE]],
            "terminating_candidate": terminator,
            "outgoing": outgoing,
        })
    return blocks


def analyze(loader_path: Path, bootled_path: Path) -> dict[str, object]:
    loader_data = loader_path.read_bytes()
    bootled_data = bootled_path.read_bytes()
    loader_words = words_le(loader_data)
    bootled_words = words_le(bootled_data)
    require_anchors(bootled_words, EXPECTED_BOOTLED_BRANCHES, "BootLED")
    require_anchors(loader_words, EXPECTED_LOADER_ANCHORS, "loader")

    bootled_records = candidates(bootled_words)
    bootled_non_fill_end = max(
        index for index, word in enumerate(bootled_words) if word != 0xFFFF
    ) + 1
    bootled_code_records = [
        item for item in bootled_records
        if int(item["address"]) < BOOT_BASE + bootled_non_fill_end
    ]
    expected_bootled = [
        (address, word, target)
        for address, word, target in EXPECTED_BOOTLED_BRANCHES
    ]
    actual_bootled = [
        (int(item["address"]), int(item["word"]), int(item["target"]))
        for item in bootled_code_records
    ]
    if actual_bootled != expected_bootled:
        raise ValueError("BootLED expanded family produced unexpected active-region candidates")

    loader_records = candidates(loader_words)
    hpi_records = [item for item in loader_records
                   if int(item["address"]) >= HPI_FALLTHROUGH]
    new_hpi_records = [item for item in hpi_records
                       if not bool(item["old_narrow_model"])]
    opcode_counts = Counter(int(item["opcode_high_byte"]) for item in hpi_records)

    return {
        "format": "sct3258-expanded-short-branch-family-v2",
        "artifacts": {
            "loader": artifact(loader_path, loader_data),
            "bootled": artifact(bootled_path, bootled_data),
        },
        "candidate_encoding": {
            "word_bits": 16,
            "predicate": (
                "union of old (opcode_high_byte & 0x3f) == 0x04 family "
                "and BootLED-constrained opcode high nibble 0x0/0x4 candidates"
            ),
            "displacement": "signed low 8 bits in instruction words",
            "target_formula": "address + 1 + signed8(low_byte)",
            "status": "expanded candidate family, not a complete decoded ISA",
        },
        "bootled_mechanical_gate": {
            "active_words": bootled_non_fill_end,
            "candidate_count_in_active_region": len(bootled_code_records),
            "all_targets_inside_active_region": all(
                BOOT_BASE <= int(item["target"]) < BOOT_BASE + bootled_non_fill_end
                for item in bootled_code_records
            ),
            "candidates": bootled_code_records,
            "interpretation": (
                "The two repeated 0x4efc/0x4ff9 back-edge pairs form nested delay-loop "
                "shapes, and final 0x0fe7 returns to the first loop body."
            ),
        },
        "loader_summary": {
            "whole_loader_candidate_count": len(loader_records),
            "whole_loader_inside_target_count": sum(
                bool(item["target_inside_boot_ram"]) for item in loader_records
            ),
            "hpi_fallthrough_candidate_count": len(hpi_records),
            "hpi_fallthrough_old_narrow_count": sum(
                bool(item["old_narrow_model"]) for item in hpi_records
            ),
            "hpi_fallthrough_new_candidate_count": len(new_hpi_records),
            "hpi_opcode_high_byte_histogram": [
                {"opcode_hex": f"0x{opcode:02x}", "count": count}
                for opcode, count in sorted(opcode_counts.items())
            ],
        },
        "loader_validated_back_edge_anchors": [
            branch_record(address, word)
            for address, word, _target in EXPECTED_LOADER_ANCHORS
        ],
        "hpi_fallthrough_candidates": hpi_records,
        "hpi_candidates_missing_from_old_narrow_model": new_hpi_records,
        "hpi_candidate_basic_blocks": basic_blocks(
            loader_words, hpi_records, HPI_FALLTHROUGH, BOOT_END
        ),
        "bounded_conclusion": (
            "The prior narrow 0x04/0x44 model is incomplete because it excludes all five "
            "mechanically gated BootLED loop branches. The expanded family materially changes "
            "the candidate HPI CFG, but candidates with out-of-range targets may be embedded "
            "data and no mnemonic, condition, register, or memory role is assigned here."
        ),
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--loader", type=Path, required=True)
    parser.add_argument("--bootled", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if args.output.exists():
        parser.error(f"refusing to overwrite existing output: {args.output}")
    report = analyze(args.loader, args.bootled)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(
        json.dumps(report, indent=2, ensure_ascii=True) + "\n",
        encoding="ascii",
        newline="\n",
    )
    print(json.dumps({
        "format": report["format"],
        "bootled_gate": report["bootled_mechanical_gate"],
        "loader_summary": report["loader_summary"],
    }, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
