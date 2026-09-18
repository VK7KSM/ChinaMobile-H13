#!/usr/bin/env python3
"""Recover likely short PC-relative branches in uncoded SCT3258 programs."""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path


BOOT_BASE = 0xF800
BRANCH_HIGH_BYTES = (0x04, 0x44, 0x84, 0xC4)


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def words_le(data: bytes) -> list[int]:
    if len(data) % 2:
        raise ValueError("program has an odd byte count")
    return [int.from_bytes(data[offset : offset + 2], "little") for offset in range(0, len(data), 2)]


def signed8(value: int) -> int:
    return value - 0x100 if value & 0x80 else value


def context(words: list[int], base: int, index: int, radius: int = 5) -> list[dict[str, object]]:
    return [
        {
            "address": base + cursor,
            "address_hex": f"0x{base + cursor:04x}",
            "word_hex": f"0x{words[cursor]:04x}",
            "is_branch": cursor == index,
        }
        for cursor in range(max(0, index - radius), min(len(words), index + radius + 1))
    ]


def branch_candidates(words: list[int], base: int) -> list[dict[str, object]]:
    result = []
    for index, word in enumerate(words):
        high = word >> 8
        if high not in BRANCH_HIGH_BYTES:
            continue
        displacement = signed8(word & 0xFF)
        source = base + index
        target = source + 1 + displacement
        result.append(
            {
                "address": source,
                "address_hex": f"0x{source:04x}",
                "word_hex": f"0x{word:04x}",
                "high_byte_hex": f"0x{high:02x}",
                "condition_bits": high >> 6,
                "shared_opcode_low6": high & 0x3F,
                "signed_low8_displacement_words": displacement,
                "target": target,
                "target_hex": f"0x{target:04x}",
                "target_inside_program": base <= target < base + len(words),
                "self_loop": target == source,
                "context": context(words, base, index),
            }
        )
    return result


def repeated_runs(words: list[int], value: int) -> list[dict[str, object]]:
    runs = []
    index = 0
    while index < len(words):
        if words[index] != value:
            index += 1
            continue
        end = index + 1
        while end < len(words) and words[end] == value:
            end += 1
        runs.append(
            {
                "start": BOOT_BASE + index,
                "start_hex": f"0x{BOOT_BASE + index:04x}",
                "end_inclusive": BOOT_BASE + end - 1,
                "end_inclusive_hex": f"0x{BOOT_BASE + end - 1:04x}",
                "length_words": end - index,
            }
        )
        index = end
    return sorted(runs, key=lambda item: (-int(item["length_words"]), int(item["start"])))


def load_flash_sample(path: Path) -> tuple[int, list[int], dict]:
    report = json.loads(path.read_text(encoding="utf-8"))
    base = int(report["flash_head"]["address_words"])
    words = [int(item["word_hex"], 16) for item in report["flash_data136"]["addressed_words"]]
    return base, words, report


def analyze(loader_path: Path, flash_sample_path: Path) -> dict[str, object]:
    loader_data = loader_path.read_bytes()
    if len(loader_data) != 2048:
        raise ValueError("expected a 2048-byte SCT3258 Boot RAM loader")
    loader_words = words_le(loader_data)
    flash_base, flash_words, flash_report = load_flash_sample(flash_sample_path)
    loader_branches = branch_candidates(loader_words, BOOT_BASE)
    flash_branches = branch_candidates(flash_words, flash_base)
    by_target: dict[int, list[dict[str, object]]] = {}
    for item in loader_branches:
        by_target.setdefault(int(item["target"]), []).append(item)

    fa2e = next((item for item in loader_branches if item["address"] == 0xFA2E), None)
    if fa2e is None or fa2e["word_hex"] != "0x44fb" or fa2e["target"] != 0xFA2A:
        raise ValueError("the expected FA2E short branch was not recovered")
    same_target = [item for item in loader_branches if item["target"] == 0xF893]
    if {item["address"] for item in same_target} != {0xF897, 0xF899}:
        raise ValueError("the F893 double-target branch invariant was not recovered")

    c = loader_words.count(0xCBF2)
    return {
        "format": "sct3258-loader-branch-analysis-v1",
        "model": {
            "candidate_high_bytes": [f"0x{value:02x}" for value in BRANCH_HIGH_BYTES],
            "shared_high_byte_low6": 0x04,
            "target_formula": "target = current_word_address + 1 + sign_extend(low8)",
            "status": "structurally supported candidate; ISA mnemonic and condition names remain unknown",
        },
        "loader": {
            "path": str(loader_path.resolve()),
            "size": len(loader_data),
            "sha256": sha256(loader_data),
            "branch_candidate_count": len(loader_branches),
            "branch_candidates": loader_branches,
            "self_loops": [item for item in loader_branches if item["self_loop"]],
            "shared_targets": [
                {
                    "target": target,
                    "target_hex": f"0x{target:04x}",
                    "sources": [item["address"] for item in items],
                    "source_hex": [item["address_hex"] for item in items],
                }
                for target, items in sorted(by_target.items())
                if len(items) > 1
            ],
            "fa2e": {
                "base_branch": fa2e,
                "system_patch_word_hex": "0x04fb",
                "system_patch_target_with_same_model_hex": "0xfa2a",
                "system_patch_xor_hex": "0x4000",
                "hpi_patch_word_hex": "0xcbf2",
                "hpi_patch_is_branch_family": False,
            },
            "cbf2": {
                "count": c,
                "runs": repeated_runs(loader_words, 0xCBF2),
                "interpretation": "strong no-op/padding candidate, not a proven mnemonic",
            },
        },
        "flash_data136_uncoded_sample": {
            "source": str(flash_sample_path.resolve()),
            "source_sha256": sha256(flash_sample_path.read_bytes()),
            "payload_sha256": flash_report["flash_data136"]["payload_sha256"],
            "base_address": flash_base,
            "base_address_hex": f"0x{flash_base:04x}",
            "word_count": len(flash_words),
            "branch_candidate_count": len(flash_branches),
            "branch_candidates": flash_branches,
        },
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--loader", type=Path, required=True)
    parser.add_argument("--flash-sample", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if args.output.exists():
        parser.error(f"refusing to overwrite existing output: {args.output}")
    try:
        report = analyze(args.loader, args.flash_sample)
    except (OSError, KeyError, ValueError) as exc:
        parser.error(str(exc))
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2, ensure_ascii=True) + "\n", encoding="ascii")
    print(
        json.dumps(
            {
                "loader_branch_candidates": report["loader"]["branch_candidate_count"],
                "loader_self_loops": len(report["loader"]["self_loops"]),
                "flash_sample_branch_candidates": report["flash_data136_uncoded_sample"]["branch_candidate_count"],
                "output": str(args.output.resolve()),
                "output_sha256": sha256(args.output.read_bytes()),
            },
            indent=2,
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
