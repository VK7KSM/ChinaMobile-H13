#!/usr/bin/env python3
"""Analyze the uncoded SCT3258 FLASH_DATA136 sample without touching a device."""

from __future__ import annotations

import argparse
import hashlib
import json
from collections import Counter
from difflib import SequenceMatcher
from pathlib import Path

from analyze_sct3258_hex import parse_sct_hex
from index_sct3258_download_stream import merge_contiguous
from reconstruct_sct3258_dat import parse_run


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def words_le(data: bytes) -> list[int]:
    if len(data) % 2:
        raise ValueError("word-oriented payload has an odd byte count")
    return [int.from_bytes(data[offset : offset + 2], "little") for offset in range(0, len(data), 2)]


def command(report: dict, source: str, command_id: str) -> dict:
    matches = [
        item
        for file_item in report["files"]
        if file_item["path"] == source
        for item in file_item["commands"]
        if item["command_id"] == command_id
    ]
    if len(matches) != 1:
        raise ValueError(f"expected one {source}:{command_id}, found {len(matches)}")
    return matches[0]


def decode_hex(value: str) -> bytes:
    return bytes.fromhex(value) if value else b""


def block_comparison(sample: list[int], candidate: list[int]) -> dict:
    blocks = SequenceMatcher(a=sample, b=candidate, autojunk=False).get_matching_blocks()
    real = [block for block in blocks if block.size]
    longest = max(real, key=lambda block: block.size, default=None)
    sample_set = set(sample)
    candidate_set = set(candidate)
    result = {
        "candidate_words": len(candidate),
        "shared_unique_words": len(sample_set & candidate_set),
        "shared_unique_word_ratio_of_sample": (
            len(sample_set & candidate_set) / len(sample_set) if sample_set else 0.0
        ),
        "matching_blocks_ge_2_words": sum(block.size >= 2 for block in real),
        "matching_blocks_ge_3_words": sum(block.size >= 3 for block in real),
        "matching_blocks_ge_4_words": sum(block.size >= 4 for block in real),
    }
    if longest is None:
        result["longest_contiguous_match"] = None
    else:
        result["longest_contiguous_match"] = {
            "sample_word_offset": longest.a,
            "candidate_word_offset": longest.b,
            "length_words": longest.size,
            "words_hex": [f"0x{word:04x}" for word in sample[longest.a : longest.a + longest.size]],
        }
    return result


def analyze(command_index: Path, lineage_path: Path, loader_dir: Path) -> dict:
    command_report = json.loads(command_index.read_text(encoding="utf-8"))
    source = "SCT_DPMR/CommandArray.xml"
    head = decode_hex(command(command_report, source, "FLASH_HEAD")["write_hex"])
    data_frame = decode_hex(command(command_report, source, "FLASH_DATA136")["write_hex"])

    if head[:5] != bytes.fromhex("61 00 07 00 90") or len(head) != 11:
        raise ValueError(f"unexpected FLASH_HEAD: {head.hex(' ')}")
    if data_frame[:5] != bytes.fromhex("61 00 e5 00 91") or len(data_frame) != 233:
        raise ValueError(f"unexpected FLASH_DATA136 frame: {data_frame[:8].hex(' ')}")
    if head[2] != len(head) - 4 or data_frame[2] != len(data_frame) - 4:
        raise ValueError("SCT frame length byte does not match the captured frame")

    memory = {0: "instruction", 1: "data"}.get(head[5], f"unknown_{head[5]}")
    coded = head[6]
    address = int.from_bytes(head[7:9], "little")
    word_count = int.from_bytes(head[9:11], "little")
    payload = data_frame[5:]
    if len(payload) != word_count * 2:
        raise ValueError("FLASH_HEAD word count does not match FLASH_DATA136 payload")
    sample_words = words_le(payload)

    loader_comparisons = []
    for path in sorted(loader_dir.glob("*.bin")):
        candidate = path.read_bytes()
        item = {
            "path": str(path.resolve()),
            "size": len(candidate),
            "sha256": sha256(candidate),
            "exact_payload_byte_offsets": [
                offset
                for offset in range(max(0, len(candidate) - len(payload) + 1))
                if candidate[offset : offset + len(payload)] == payload
            ],
            "comparison": block_comparison(sample_words, words_le(candidate)),
        }
        loader_comparisons.append(item)

    lineage = json.loads(lineage_path.read_text(encoding="utf-8"))
    section_comparisons = []
    exact_section_matches = []
    section_counts = Counter()
    for version, version_item in lineage["versions"].items():
        hex_path = Path(version_item["path"])
        segments, _ = parse_sct_hex(hex_path)
        for run in merge_contiguous(segments):
            sections, _ = parse_run(run)
            for ordinal, section in enumerate(sections):
                section_counts[(str(section["memory"]), int(section["coded"]))] += 1
                candidate = bytes(section["payload"])
                exact_offsets = [
                    offset
                    for offset in range(max(0, len(candidate) - len(payload) + 1))
                    if candidate[offset : offset + len(payload)] == payload
                ]
                if candidate == payload or exact_offsets:
                    exact_section_matches.append(
                        {
                            "version": version,
                            "run_start": run.start,
                            "section_ordinal": ordinal,
                            "memory": section["memory"],
                            "coded": section["coded"],
                            "address_words": section["address_words"],
                            "word_count": section["word_count"],
                            "payload_sha256": sha256(candidate),
                            "exact_payload_byte_offsets": exact_offsets,
                        }
                    )
                if int(section["coded"]) != 0:
                    continue
                comparison = block_comparison(sample_words, words_le(candidate))
                section_comparisons.append(
                    {
                        "version": version,
                        "run_start": run.start,
                        "section_ordinal": ordinal,
                        "memory": section["memory"],
                        "coded": section["coded"],
                        "address_words": section["address_words"],
                        "word_count": section["word_count"],
                        "payload_sha256": sha256(candidate),
                        "comparison": comparison,
                    }
                )

    section_comparisons.sort(
        key=lambda item: (
            -int(item["comparison"]["longest_contiguous_match"]["length_words"]),
            -int(item["comparison"]["shared_unique_words"]),
            str(item["version"]),
            int(item["run_start"]),
            int(item["section_ordinal"]),
        )
    )
    histogram = Counter(sample_words)
    return {
        "format": "sct3258-flash-data136-analysis-v1",
        "source_command_index": str(command_index.resolve()),
        "source_command_index_sha256": sha256(command_index.read_bytes()),
        "source_lineage": str(lineage_path.resolve()),
        "source_lineage_sha256": sha256(lineage_path.read_bytes()),
        "flash_head": {
            "frame_hex": head.hex(" "),
            "memory": memory,
            "coded": coded,
            "address_words": address,
            "address_words_hex": f"0x{address:04x}",
            "word_count": word_count,
            "word_count_hex": f"0x{word_count:04x}",
        },
        "flash_data136": {
            "frame_bytes": len(data_frame),
            "frame_sha256": sha256(data_frame),
            "payload_bytes": len(payload),
            "payload_sha256": sha256(payload),
            "unique_words": len(histogram),
            "repeated_words": [
                {"word_hex": f"0x{word:04x}", "count": count}
                for word, count in histogram.most_common()
                if count > 1
            ],
            "addressed_words": [
                {"address": address + index, "address_hex": f"0x{address + index:04x}", "word_hex": f"0x{word:04x}"}
                for index, word in enumerate(sample_words)
            ],
        },
        "known_control_words_in_sample": {
            value: [address + index for index, word in enumerate(sample_words) if word == int(value, 16)]
            for value in ("44fb", "04fb", "cbf2", "ffff", "cf00")
        },
        "loader_and_bootled_comparisons": loader_comparisons,
        "five_version_section_counts": {
            f"{memory_name}:coded{coded_value}": count
            for (memory_name, coded_value), count in sorted(section_counts.items())
        },
        "exact_matches_in_all_five_version_sections": exact_section_matches,
        "uncoded_section_comparisons_best_first": section_comparisons,
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--command-index", type=Path, required=True)
    parser.add_argument("--lineage", type=Path, required=True)
    parser.add_argument("--loader-dir", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if args.output.exists():
        parser.error(f"refusing to overwrite existing output: {args.output}")
    try:
        report = analyze(args.command_index, args.lineage, args.loader_dir)
    except (OSError, KeyError, ValueError) as exc:
        parser.error(str(exc))
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2, ensure_ascii=True) + "\n", encoding="ascii")
    print(
        json.dumps(
            {
                "payload_bytes": report["flash_data136"]["payload_bytes"],
                "payload_sha256": report["flash_data136"]["payload_sha256"],
                "exact_section_matches": len(report["exact_matches_in_all_five_version_sections"]),
                "uncoded_sections_compared": len(report["uncoded_section_comparisons_best_first"]),
                "output": str(args.output.resolve()),
                "output_sha256": sha256(args.output.read_bytes()),
            },
            indent=2,
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
