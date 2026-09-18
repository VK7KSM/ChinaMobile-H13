#!/usr/bin/env python3
"""Cross-check major-A field layouts across independent uncoded programs."""

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


def nibbles(word: int) -> tuple[int, int, int]:
    return ((word >> 8) & 0xF, (word >> 4) & 0xF, word & 0xF)


def artifact(path: Path, data: bytes) -> dict[str, object]:
    return {"path": str(path.resolve()), "size": len(data), "sha256": sha256(data)}


def major_a_pairs(records: list[tuple[int, int]]) -> list[dict[str, object]]:
    by_address = dict(records)
    result = []
    for address, first in records:
        second = by_address.get(address + 1)
        if first >> 12 != 0xA or second is None or second >> 12 != 0xA:
            continue
        result.append({
            "first_address": address,
            "first_address_hex": f"0x{address:04x}",
            "first_word_hex": f"0x{first:04x}",
            "first_fields_hex": [f"0x{value:x}" for value in nibbles(first)],
            "second_address_hex": f"0x{address + 1:04x}",
            "second_word_hex": f"0x{second:04x}",
            "second_fields_hex": [f"0x{value:x}" for value in nibbles(second)],
        })
    return result


def score_layout(pairs: list[dict[str, object]], result_position: int) -> dict[str, object]:
    input_positions = [position for position in range(3) if position != result_position]
    input_1_matches = 0
    input_2_matches = 0
    both_matches = 0
    neither_matches = 0
    matching_pairs = []
    for pair in pairs:
        first = [int(value, 16) for value in pair["first_fields_hex"]]
        second = [int(value, 16) for value in pair["second_fields_hex"]]
        match_1 = first[result_position] == second[input_positions[0]]
        match_2 = first[result_position] == second[input_positions[1]]
        input_1_matches += match_1
        input_2_matches += match_2
        both_matches += match_1 and match_2
        neither_matches += not match_1 and not match_2
        if match_1 or match_2:
            matching_pairs.append({
                "first_address_hex": pair["first_address_hex"],
                "first_word_hex": pair["first_word_hex"],
                "second_address_hex": pair["second_address_hex"],
                "second_word_hex": pair["second_word_hex"],
                "matched_next_input_positions": [
                    position + 1 for position, matched in zip(input_positions,
                                                               (match_1, match_2))
                    if matched
                ],
            })
    return {
        "candidate_result_lower_field_position": result_position + 1,
        "candidate_input_lower_field_positions": [position + 1 for position in input_positions],
        "pairs": len(pairs),
        "matches_either_input": len(pairs) - neither_matches,
        "matches_first_candidate_input": input_1_matches,
        "matches_second_candidate_input": input_2_matches,
        "matches_both_inputs": both_matches,
        "matches_neither_input": neither_matches,
        "matching_pairs": matching_pairs,
    }


def analyze(loader_path: Path, bootled_path: Path, sample_report_path: Path) -> dict[str, object]:
    loader_data = loader_path.read_bytes()
    bootled_data = bootled_path.read_bytes()
    sample_data = sample_report_path.read_bytes()
    if len(loader_data) != 2048 or len(bootled_data) != 2048:
        raise ValueError("loader and BootLED inputs must each be 2048 bytes")
    sample_report = json.loads(sample_data.decode("ascii"))
    if sample_report.get("format") != "sct3258-flash-data136-analysis-v1":
        raise ValueError("unexpected FLASH_DATA136 analysis format")

    loader_words = words_le(loader_data)
    bootled_words = words_le(bootled_data)
    programs = {
        "loader_structured_region": [
            (BOOT_BASE + index, word) for index, word in enumerate(loader_words)
            if BOOT_BASE + index < 0xFB8C
        ],
        "bootled_nonfill_region": [
            (BOOT_BASE + index, word) for index, word in enumerate(bootled_words)
            if word != 0xFFFF
        ],
        "flash_data136_uncoded_instruction": [
            (int(item["address"]), int(item["word_hex"], 16))
            for item in sample_report["flash_data136"]["addressed_words"]
        ],
    }
    per_program = {}
    combined_pairs = []
    for name, records in programs.items():
        pairs = major_a_pairs(records)
        combined_pairs.extend({"program": name, **pair} for pair in pairs)
        per_program[name] = {
            "address_start_hex": f"0x{min(address for address, _ in records):04x}",
            "address_end_inclusive_hex": f"0x{max(address for address, _ in records):04x}",
            "major_a_adjacent_pairs": pairs,
            "layout_scores": [score_layout(pairs, position) for position in range(3)],
        }

    combined_scores = [score_layout(combined_pairs, position) for position in range(3)]
    best_score = max(score["matches_either_input"] for score in combined_scores)
    best_positions = [score["candidate_result_lower_field_position"]
                      for score in combined_scores
                      if score["matches_either_input"] == best_score]
    return {
        "format": "sct3258-major-a-field-layout-crosscheck-v1",
        "artifacts": {
            "loader": artifact(loader_path, loader_data),
            "bootled": artifact(bootled_path, bootled_data),
            "flash_data136_analysis": artifact(sample_report_path, sample_data),
        },
        "method": (
            "For each adjacent major-A pair, test each lower nibble position as the "
            "candidate result field and count equality with either remaining field in "
            "the next word. This is a structural score, not an ISA mnemonic."
        ),
        "per_program": per_program,
        "combined_layout_scores": combined_scores,
        "best_candidate_result_lower_field_positions": best_positions,
        "bounded_conclusion": (
            "A uniquely best position supports result/input ordering across independent "
            "uncoded programs. Ties or weak margins must preserve competing layouts, and "
            "even a unique winner does not identify arithmetic, registers, or memory roles."
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
        "combined_layout_scores": [
            {key: value for key, value in score.items() if key != "matching_pairs"}
            for score in report["combined_layout_scores"]
        ],
        "best_candidate_result_lower_field_positions":
            report["best_candidate_result_lower_field_positions"],
        "per_program_pair_counts": {
            name: len(item["major_a_adjacent_pairs"])
            for name, item in report["per_program"].items()
        },
    }, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
