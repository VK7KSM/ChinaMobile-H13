#!/usr/bin/env python3
"""Cross-check high-byte opcode and operand direction for SCT3258 major-8/B words."""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path


BOOT_BASE = 0xF800
STRUCTURED_END = 0xFB8C


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def words_le(data: bytes) -> list[int]:
    if len(data) % 2:
        raise ValueError("word-oriented input has an odd byte count")
    return [int.from_bytes(data[offset:offset + 2], "little")
            for offset in range(0, len(data), 2)]


def artifact(path: Path, data: bytes) -> dict[str, object]:
    return {"path": str(path.resolve()), "size": len(data), "sha256": sha256(data)}


def fields(word: int) -> tuple[int, int, int]:
    return (word >> 8 & 0xF, word >> 4 & 0xF, word & 0xF)


def known_read_fields(word: int) -> list[dict[str, object]]:
    """Return only operand reads supported by the already-audited field models."""
    major = word >> 12
    lower = fields(word)
    if major == 0xA:
        return [
            {"position": 2, "value": lower[1], "role": "major-A input 1"},
            {"position": 3, "value": lower[2], "role": "major-A input 2"},
        ]
    if major == 0x6:
        return [
            {"position": 1, "value": lower[0], "role": "major-6 data"},
            {"position": 3, "value": lower[2], "role": "major-6 address"},
        ]
    if major == 0x7:
        return [
            {"position": 3, "value": lower[2], "role": "major-7 address"},
        ]
    return []


def adjacent_feed_records(records: list[tuple[int, int]], producer_major: int) -> dict[str, object]:
    by_address = dict(records)
    producers = []
    scores = {position: 0 for position in (1, 2, 3)}
    unique_scores = {position: 0 for position in (1, 2, 3)}
    for address, word in records:
        if word >> 12 != producer_major:
            continue
        successor = by_address.get(address + 1)
        if successor is None:
            continue
        reads = known_read_fields(successor)
        if not reads:
            continue
        lower = fields(word)
        matches = {
            position: [item for item in reads if item["value"] == lower[position - 1]]
            for position in (1, 2, 3)
        }
        matching_positions = [position for position, items in matches.items() if items]
        for position in matching_positions:
            scores[position] += 1
        if len(matching_positions) == 1:
            unique_scores[matching_positions[0]] += 1
        producers.append({
            "producer_address": address,
            "producer_address_hex": f"0x{address:04x}",
            "producer_word_hex": f"0x{word:04x}",
            "producer_fields_hex": [f"0x{value:x}" for value in lower],
            "consumer_address_hex": f"0x{address + 1:04x}",
            "consumer_word_hex": f"0x{successor:04x}",
            "consumer_known_reads": [
                {**item, "value_hex": f"0x{item['value']:x}"} for item in reads
            ],
            "matching_candidate_result_positions": matching_positions,
        })
    return {
        "eligible_adjacent_pairs": len(producers),
        "candidate_result_position_match_counts": {
            str(position): scores[position] for position in (1, 2, 3)
        },
        "candidate_result_position_unique_match_counts": {
            str(position): unique_scores[position] for position in (1, 2, 3)
        },
        "pairs": producers,
    }


def require_anchor(records: list[tuple[int, int]], expected: dict[int, int]) -> None:
    by_address = dict(records)
    for address, word in expected.items():
        if by_address.get(address) != word:
            raise ValueError(f"anchor mismatch at 0x{address:04x}")


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
            if BOOT_BASE + index < STRUCTURED_END
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
    require_anchor(programs["flash_data136_uncoded_instruction"], {
        0x2039: 0xBAED,
        0x203A: 0xA6D0,
        0x203B: 0xBA0D,
        0x203C: 0xA6D0,
        0x203D: 0xBA2D,
    })
    require_anchor(programs["loader_structured_region"], {
        0xFA63: 0x2E72,
        0xFA64: 0x3EF2,
        0xFA83: 0x8E82,
        0xFB27: 0xBEA4,
        0xFB6C: 0xA592,
        0xFB71: 0x8EA2,
        0xFB74: 0x8EA5,
    })

    per_program = {}
    for name, records in programs.items():
        per_program[name] = {
            "major_8": adjacent_feed_records(records, 0x8),
            "major_b": adjacent_feed_records(records, 0xB),
        }

    combined = {}
    for major_name in ("major_8", "major_b"):
        combined[major_name] = {
            "eligible_adjacent_pairs": sum(
                item[major_name]["eligible_adjacent_pairs"] for item in per_program.values()
            ),
            "candidate_result_position_match_counts": {
                str(position): sum(
                    item[major_name]["candidate_result_position_match_counts"][str(position)]
                    for item in per_program.values()
                ) for position in (1, 2, 3)
            },
            "candidate_result_position_unique_match_counts": {
                str(position): sum(
                    item[major_name]["candidate_result_position_unique_match_counts"][str(position)]
                    for item in per_program.values()
                ) for position in (1, 2, 3)
            },
        }

    sample_b_pairs = per_program["flash_data136_uncoded_instruction"]["major_b"]["pairs"]
    decisive_b = [item for item in sample_b_pairs
                  if item["producer_address"] in (0x2039, 0x203B)]
    if len(decisive_b) != 2 \
            or decisive_b[0]["matching_candidate_result_positions"] != [3] \
            or 3 not in decisive_b[1]["matching_candidate_result_positions"]:
        raise ValueError("independent major-B output-last anchor failed")

    sample_8_pairs = per_program["flash_data136_uncoded_instruction"]["major_8"]["pairs"]
    decisive_8 = [item for item in sample_8_pairs
                  if item["producer_address"] in (0x2005, 0x206C)]
    if len(decisive_8) != 2 or any(
            item["matching_candidate_result_positions"] != [2] for item in decisive_8):
        raise ValueError("independent major-8 low-operand-1 result anchor failed")

    f272_first_field_words = []
    for address, word in programs["loader_structured_region"]:
        if address <= 0xFA64 or word >> 12 not in (0x8, 0xB):
            continue
        if fields(word)[0] == 0xE:
            f272_first_field_words.append({
                "address_hex": f"0x{address:04x}",
                "word_hex": f"0x{word:04x}",
                "major_hex": f"0x{word >> 12:x}",
                "high_byte_opcode_hex": f"0x{word >> 8:02x}",
                "status": (
                    "BE is a high-byte opcode-family candidate, not a field-E result, "
                    "under the supported major-B two-low-operand model"
                    if word >> 12 == 0xB else
                    "8E is a high-byte opcode-family candidate; low operand 1 is "
                    "result-like under the independently supported major-8 model"
                ),
            })

    return {
        "format": "sct3258-major-8-b-field-direction-audit-v2",
        "artifacts": {
            "loader": artifact(loader_path, loader_data),
            "bootled": artifact(bootled_path, bootled_data),
            "flash_data136_analysis": artifact(sample_report_path, sample_data),
        },
        "method": (
            "For each major-8/B word immediately followed by a consumer with an already "
            "supported read-field model (major-A, major-6, or major-7), score each lower "
            "nibble as a candidate result when its value is read by that consumer. Then "
            "separate the high-byte opcode-extension candidate (bits 11:8) from the two "
            "low-byte operand candidates (bits 7:4 and 3:0). Counts are structural "
            "coincidences; unique matches and repeated independent anchors carry more "
            "weight than aggregate frequency."
        ),
        "known_consumer_models": {
            "major_a": "reads lower fields 2 and 3; lower field 1 is result-like",
            "major_6": "reads data-like lower field 1 and address-like lower field 3",
            "major_7": "reads address-like lower field 3; data-like lower field 1 is result-like",
        },
        "per_program": per_program,
        "combined_scores": combined,
        "decisive_independent_major_8_anchors": decisive_8,
        "decisive_independent_major_b_anchors": decisive_b,
        "f272_false_alias_high_byte_8e_be_words": f272_first_field_words,
        "bounded_conclusion": (
            "Two independent 0x2000 anchors uniquely support bits 7:4 as result-like for "
            "major 8, and three loader 8C anchors repeat that feed direction. The same "
            "independent program contains one unique major-B low-operand-2-to-major-A "
            "feed followed by a compatible but ambiguous repeat. Together with repeated "
            "8x/Bx high-byte families and varying low bytes, this supports two-low-operand "
            "formats rather than the major-A three-register format: [8x opcode][result-like]"
            "[input-like], while BA specifically is supported as [BA opcode][varying "
            "operand][result-like]. Therefore 8E/BE words are not field-E result writes "
            "under the best current format model and no longer constitute F272 clobber "
            "alternatives. Exact 8x/Bx mnemonics and whether every Bx family has the same "
            "operand direction remain unresolved."
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
        "combined_scores": report["combined_scores"],
        "decisive_independent_major_8_anchors":
            report["decisive_independent_major_8_anchors"],
        "decisive_independent_major_b_anchors":
            report["decisive_independent_major_b_anchors"],
        "f272_false_alias_high_byte_8e_be_words":
            report["f272_false_alias_high_byte_8e_be_words"],
        "bounded_conclusion": report["bounded_conclusion"],
    }, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
