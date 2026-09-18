#!/usr/bin/env python3
"""Audit the HPI-reachable SCT3258 loader region without assuming an ISA."""

from __future__ import annotations

import argparse
import hashlib
import json
import math
from collections import Counter
from pathlib import Path


from analyze_sct3258_loader_branches import branch_candidates


BOOT_BASE = 0xF800
BOOT_END = 0xFC00
HPI_PATCH = 0xFA2E
HPI_FALLTHROUGH = 0xFA2F
CONTROL_BYTES = (
    "80a8", "81a7", "88a8", "89a7", "8aa8",
    "8ba7", "8ca8", "8da7", "82a3", "85ab",
)


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def words_le(data: bytes) -> list[int]:
    if len(data) % 2:
        raise ValueError("loader size is not word aligned")
    return [int.from_bytes(data[index : index + 2], "little") for index in range(0, len(data), 2)]


def byte_occurrences(data: bytes, pattern: bytes) -> list[int]:
    return [index for index in range(len(data) - len(pattern) + 1) if data[index : index + len(pattern)] == pattern]


def offset_record(offset: int) -> dict[str, object]:
    return {
        "byte_offset": offset,
        "byte_offset_hex": f"0x{offset:04x}",
        "word_aligned": offset % 2 == 0,
        "word_address_if_aligned": None if offset % 2 else BOOT_BASE + offset // 2,
        "word_address_hex_if_aligned": None if offset % 2 else f"0x{BOOT_BASE + offset // 2:04x}",
    }


def shannon_entropy(data: bytes) -> float:
    if not data:
        return 0.0
    counts = Counter(data)
    return -sum((count / len(data)) * math.log2(count / len(data)) for count in counts.values())


def entropy_windows(data: bytes, window_bytes: int = 64) -> list[dict[str, object]]:
    return [
        {
            "start_word_address": BOOT_BASE + start // 2,
            "start_word_address_hex": f"0x{BOOT_BASE + start // 2:04x}",
            "byte_count": len(data[start : start + window_bytes]),
            "shannon_bits_per_byte": round(shannon_entropy(data[start : start + window_bytes]), 6),
            "distinct_bytes": len(set(data[start : start + window_bytes])),
        }
        for start in range(0, len(data), window_bytes)
    ]


def repeated_word_runs(words: list[int], start_index: int = 0) -> list[dict[str, object]]:
    runs = []
    index = start_index
    while index < len(words):
        end = index + 1
        while end < len(words) and words[end] == words[index]:
            end += 1
        if end - index >= 2:
            runs.append(
                {
                    "word_hex": f"0x{words[index]:04x}",
                    "start": BOOT_BASE + index,
                    "start_hex": f"0x{BOOT_BASE + index:04x}",
                    "end_inclusive": BOOT_BASE + end - 1,
                    "end_inclusive_hex": f"0x{BOOT_BASE + end - 1:04x}",
                    "length_words": end - index,
                }
            )
        index = end
    return sorted(runs, key=lambda item: (-int(item["length_words"]), int(item["start"])))


def repeated_word_blocks(
    words: list[int], start_index: int, minimum_words: int = 4
) -> list[dict[str, object]]:
    blocks: list[dict[str, object]] = []
    for left in range(start_index, len(words)):
        for right in range(left + 1, len(words)):
            if left > start_index and words[left - 1] == words[right - 1]:
                continue
            length = 0
            while right + length < len(words) and words[left + length] == words[right + length]:
                length += 1
            if length < minimum_words:
                continue
            blocks.append(
                {
                    "left_start": BOOT_BASE + left,
                    "left_start_hex": f"0x{BOOT_BASE + left:04x}",
                    "right_start": BOOT_BASE + right,
                    "right_start_hex": f"0x{BOOT_BASE + right:04x}",
                    "length_words": length,
                    "words_hex": [f"0x{word:04x}" for word in words[left : left + length]],
                }
            )
    return sorted(
        blocks,
        key=lambda item: (-int(item["length_words"]), int(item["left_start"]), int(item["right_start"])),
    )


def byte_value_positions(
    data: bytes, values: tuple[int, ...], base_word_address: int = BOOT_BASE
) -> dict[str, dict[str, list[int]]]:
    result: dict[str, dict[str, list[int]]] = {}
    for value in values:
        result[f"0x{value:02x}"] = {
            "low_byte_word_addresses": [
                base_word_address + index // 2 for index in range(0, len(data), 2) if data[index] == value
            ],
            "high_byte_word_addresses": [
                base_word_address + index // 2 for index in range(1, len(data), 2) if data[index] == value
            ],
        }
    return result


def analyze(loader_path: Path) -> dict[str, object]:
    data = loader_path.read_bytes()
    if len(data) != 2048:
        raise ValueError("expected a 2048-byte Boot RAM loader")
    words = words_le(data)
    patch_index = HPI_PATCH - BOOT_BASE
    if words[patch_index] != 0x44FB:
        raise ValueError(f"expected 0x44fb at 0x{HPI_PATCH:04x}")

    service_offset = (HPI_FALLTHROUGH - BOOT_BASE) * 2
    service_data = data[service_offset:]
    all_branches = branch_candidates(words, BOOT_BASE)
    service_branches = [item for item in all_branches if int(item["address"]) >= HPI_FALLTHROUGH]
    exact_controls = {}
    for text in CONTROL_BYTES:
        pattern = bytes.fromhex(text)
        all_offsets = byte_occurrences(data, pattern)
        service_offsets = [offset for offset in all_offsets if offset >= service_offset]
        exact_controls[text] = {
            "whole_loader": [offset_record(offset) for offset in all_offsets],
            "hpi_fallthrough_region": [offset_record(offset) for offset in service_offsets],
        }

    return {
        "format": "sct3258-loader-dispatcher-audit-v2",
        "loader": {
            "path": str(loader_path.resolve()),
            "size": len(data),
            "sha256": sha256(data),
            "boot_ram_word_range": [f"0x{BOOT_BASE:04x}", f"0x{BOOT_END:04x}"],
        },
        "hpi_reachability_boundary": {
            "base_word_address": f"0x{HPI_PATCH:04x}",
            "base_word": "0x44fb",
            "base_branch_target_under_supported_model": "0xfa2a",
            "hpi_patch_word": "0xcbf2",
            "newly_exposed_fallthrough_word_address": f"0x{HPI_FALLTHROUGH:04x}",
            "fallthrough_region_end_exclusive": f"0x{BOOT_END:04x}",
            "fallthrough_region_words": BOOT_END - HPI_FALLTHROUGH,
            "scope_warning": (
                "This identifies the region made directly reachable by deleting the FA2E back-edge. "
                "It does not prove that every word is code or that helpers before FA2F are unreachable."
            ),
        },
        "download_control_byte_sequences": exact_controls,
        "control_component_byte_positions": byte_value_positions(
            service_data,
            (0x80, 0x81, 0x82, 0x88, 0x89, 0x8A, 0x8B, 0x8C, 0x8D, 0xA3, 0xA7, 0xA8),
            HPI_FALLTHROUGH,
        ),
        "candidate_short_branches_in_fallthrough_region": service_branches,
        "candidate_short_branch_summary": {
            "count": len(service_branches),
            "backward": sum(int(item["target"]) < int(item["address"]) for item in service_branches),
            "forward": sum(int(item["target"]) > int(item["address"]) for item in service_branches),
            "inside_loader": sum(bool(item["target_inside_program"]) for item in service_branches),
        },
        "repeated_word_runs_in_fallthrough_region": repeated_word_runs(words, HPI_FALLTHROUGH - BOOT_BASE),
        "repeated_word_blocks_in_fallthrough_region_min4": repeated_word_blocks(
            words, HPI_FALLTHROUGH - BOOT_BASE
        ),
        "entropy_windows_64_bytes": entropy_windows(data),
        "bounded_inferences": [
            (
                "The HPI variant changes only FA2E, so decoding, state update, and transport logic "
                "used after HPI entry is byte-identical across all known loader variants."
            ),
            (
                "The loader does not contain a literal table of all documented 2-byte download "
                "controls. Exact sequence absence is consistent with field/bit decoding, but does "
                "not reveal the ISA operations or prove which aligned words are constants."
            ),
            (
                "No loader modification is justified until an HPI receive primitive, destination "
                "write primitive, coded-state update, and bounded readback path are independently identified."
            ),
        ],
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
    args.output.write_text(
        json.dumps(report, indent=2, ensure_ascii=True) + "\n",
        encoding="ascii",
        newline="\n",
    )
    print(
        json.dumps(
            {
                "fallthrough_words": report["hpi_reachability_boundary"]["fallthrough_region_words"],
                "exact_controls_in_fallthrough": {
                    key: len(value["hpi_fallthrough_region"])
                    for key, value in report["download_control_byte_sequences"].items()
                },
                "branch_summary": report["candidate_short_branch_summary"],
                "repeated_runs": len(report["repeated_word_runs_in_fallthrough_region"]),
            },
            indent=2,
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
