#!/usr/bin/env python3
"""Audit the SCT3258 [0/4][condition][signed8] branch-nibble model."""

from __future__ import annotations

import argparse
import hashlib
import json
from collections import Counter
from pathlib import Path


BOOT_BASE = 0xF800
BOOT_END = 0xFC00
HPI_START = 0xFA2F
BOOTLED_ANCHORS = (
    (0xF81A, 0x4EFC, 0xF817),
    (0xF81B, 0x4FF9, 0xF815),
    (0xF826, 0x4EFC, 0xF823),
    (0xF827, 0x4FF9, 0xF821),
    (0xF82B, 0x0FE7, 0xF813),
)
LOADER_ANCHORS = (
    (0xFA2E, 0x44FB, 0xFA2A),
    (0xFA45, 0x44F7, 0xFA3D),
    (0xFA5A, 0x44F0, 0xFA4B),
    (0xFB28, 0x04F1, 0xFB1A),
    (0xFB3E, 0x04F1, 0xFB30),
    (0xFB78, 0x04F4, 0xFB6D),
)
EXPECTED_LEGACY_ONLY = (
    (0xFA6C, 0x8478),
    (0xFB69, 0xC472),
    (0xFB80, 0x84BE),
)


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def words_le(data: bytes) -> list[int]:
    if len(data) != 2048:
        raise ValueError("expected an exact 2048-byte Boot RAM image")
    return [int.from_bytes(data[offset:offset + 2], "little")
            for offset in range(0, len(data), 2)]


def artifact(path: Path, data: bytes) -> dict[str, object]:
    return {"path": str(path.resolve()), "size": len(data), "sha256": sha256(data)}


def signed8(value: int) -> int:
    return value - 0x100 if value & 0x80 else value


def is_branch_nibble_candidate(word: int) -> bool:
    return word >> 12 in (0x0, 0x4)


def is_old_narrow_candidate(word: int) -> bool:
    return ((word >> 8) & 0x3F) == 0x04


def record(address: int, word: int) -> dict[str, object]:
    target = address + 1 + signed8(word & 0xFF)
    return {
        "address": address,
        "address_hex": f"0x{address:04x}",
        "word": word,
        "word_hex": f"0x{word:04x}",
        "major_nibble_hex": f"0x{word >> 12:x}",
        "condition_nibble_hex": f"0x{(word >> 8) & 0xF:x}",
        "signed_low8_displacement_words": signed8(word & 0xFF),
        "target": target,
        "target_hex": f"0x{target:04x}",
        "target_inside_boot_ram": BOOT_BASE <= target < BOOT_END,
        "also_in_old_narrow_model": is_old_narrow_candidate(word),
    }


def candidates(words: list[int]) -> list[dict[str, object]]:
    return [record(BOOT_BASE + index, word) for index, word in enumerate(words)
            if is_branch_nibble_candidate(word)]


def require_anchors(words: list[int], anchors: tuple[tuple[int, int, int], ...],
                    label: str) -> None:
    for address, expected_word, expected_target in anchors:
        word = words[address - BOOT_BASE]
        actual = record(address, word)
        if word != expected_word or int(actual["target"]) != expected_target:
            raise ValueError(f"{label} anchor mismatch at 0x{address:04x}")
        if not is_branch_nibble_candidate(word):
            raise ValueError(f"{label} anchor rejected by nibble model at 0x{address:04x}")


def occurrences(words: list[int], value: int) -> list[str]:
    return [f"0x{BOOT_BASE + index:04x}" for index, word in enumerate(words)
            if word == value]


def nibble_fields(word: int) -> list[str]:
    return [f"0x{(word >> shift) & 0xF:x}" for shift in (12, 8, 4, 0)]


def analyze(loader_path: Path, bootled_path: Path) -> dict[str, object]:
    loader_data = loader_path.read_bytes()
    bootled_data = bootled_path.read_bytes()
    loader_words = words_le(loader_data)
    bootled_words = words_le(bootled_data)
    require_anchors(bootled_words, BOOTLED_ANCHORS, "BootLED")
    require_anchors(loader_words, LOADER_ANCHORS, "loader")

    bootled_end = max(index for index, word in enumerate(bootled_words)
                      if word != 0xFFFF) + 1
    bootled_active = [item for item in candidates(bootled_words)
                      if int(item["address"]) < BOOT_BASE + bootled_end]
    if [(int(item["address"]), int(item["word"]), int(item["target"]))
            for item in bootled_active] != list(BOOTLED_ANCHORS):
        raise ValueError("unexpected BootLED active-region branch candidates")

    loader_candidates = candidates(loader_words)
    hpi = [item for item in loader_candidates if int(item["address"]) >= HPI_START]
    legacy_only = []
    for index, word in enumerate(loader_words):
        address = BOOT_BASE + index
        if address >= HPI_START and is_old_narrow_candidate(word) \
                and not is_branch_nibble_candidate(word):
            legacy_only.append((address, word))
    if tuple(legacy_only) != EXPECTED_LEGACY_ONLY:
        raise ValueError(f"unexpected legacy-only candidates: {legacy_only!r}")

    condition_counts = Counter((int(item["word"]) >> 8) & 0xF for item in hpi)
    triples = (0xA906, 0xA806, 0xA907, 0xA807, 0xA90E, 0xA80E, 0xA592)
    cross_major = (0x5A2, 0x582, 0x502)
    return {
        "format": "sct3258-short-branch-nibble-model-v3",
        "artifacts": {
            "loader": artifact(loader_path, loader_data),
            "bootled": artifact(bootled_path, bootled_data),
        },
        "branch_encoding_model": {
            "word_nibbles": "[major class 0x0/0x4][condition][signed displacement high][signed displacement low]",
            "predicate": "word top nibble is 0x0 or 0x4",
            "target_formula": "address + 1 + signed8(low_byte)",
            "status": "strongly constrained candidate encoding, not a recovered mnemonic table",
        },
        "bootled_gate": {
            "active_words": bootled_end,
            "candidate_count": len(bootled_active),
            "candidates": bootled_active,
        },
        "hpi_summary": {
            "candidate_count": len(hpi),
            "also_old_04_44_count": sum(bool(item["also_in_old_narrow_model"])
                                        for item in hpi),
            "new_condition_candidates": sum(not bool(item["also_in_old_narrow_model"])
                                              for item in hpi),
            "condition_nibble_histogram": [
                {"condition_nibble_hex": f"0x{condition:x}", "count": count}
                for condition, count in sorted(condition_counts.items())
            ],
        },
        "legacy_model_candidates_rejected_by_nibble_model": [
            {
                "address_hex": f"0x{address:04x}",
                "word_hex": f"0x{word:04x}",
                "reason": "major nibble is 0x8/0xc, not branch class 0x0/0x4",
            }
            for address, word in legacy_only
        ],
        "hpi_candidates": hpi,
        "a_major_field_evidence": {
            "exact_word_occurrences": {
                f"0x{word:04x}": occurrences(loader_words, word) for word in triples
            },
            "nibble_decomposition": {
                f"0x{word:04x}": nibble_fields(word) for word in triples
            },
            "same_low12_across_major_8_a": {
                f"0x{low12:03x}": {
                    "major_8_occurrences": occurrences(loader_words, 0x8000 | low12),
                    "major_a_occurrences": occurrences(loader_words, 0xA000 | low12),
                }
                for low12 in cross_major
            },
            "bounded_interpretation": (
                "The A906/A806 and A907/A807 mirrors are more naturally parsed as "
                "one major-A instruction class with a changing first operand nibble "
                "(9/8), not two A9/A8 opcode families. The final 06/07/0e nibbles "
                "remain operand fields; register roles and operation semantics are unproven."
            ),
        },
        "bounded_conclusion": (
            "The nibble model retains 47 HPI branch candidates: nine old 04/44 anchors "
            "and 38 additional conditions. It rejects the old model's three 84/C4 "
            "candidates. A592 is best treated as major-A plus three operand nibbles "
            "inside a compare/branch-shaped tight loop, not as a proven HPI read opcode."
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
    args.output.write_text(json.dumps(report, indent=2, ensure_ascii=True) + "\n",
                           encoding="ascii", newline="\n")
    print(json.dumps({
        "format": report["format"],
        "hpi_summary": report["hpi_summary"],
        "legacy_rejected": report["legacy_model_candidates_rejected_by_nibble_model"],
        "a_major_field_evidence": report["a_major_field_evidence"],
    }, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
