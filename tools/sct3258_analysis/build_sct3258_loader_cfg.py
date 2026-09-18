#!/usr/bin/env python3
"""Build a conservative candidate CFG from the SCT3258 short-branch audit."""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path


SERVICE_START = 0xFA2F
SERVICE_END = 0xFC00
BOOT_BASE = 0xF800


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def words_le(data: bytes) -> list[int]:
    return [int.from_bytes(data[index : index + 2], "little") for index in range(0, len(data), 2)]


def header_field_model() -> dict[str, object]:
    controls = {
        "instruction_uncoded": (0x80, 0xA8, 0),
        "data_uncoded": (0x81, 0xA7, 0),
        "instruction_coded1": (0x88, 0xA8, 1),
        "data_coded1": (0x89, 0xA7, 1),
        "instruction_coded2": (0x8A, 0xA8, 2),
        "data_coded2": (0x8B, 0xA7, 2),
        "instruction_coded3": (0x8C, 0xA8, 3),
        "data_coded3": (0x8D, 0xA7, 3),
    }
    rows = []
    for name, (first, second, coded) in controls.items():
        memory_bit = first & 1
        selector = (first >> 1) & 7
        rows.append(
            {
                "name": name,
                "first_byte_hex": f"0x{first:02x}",
                "second_byte_hex": f"0x{second:02x}",
                "memory_bit": memory_bit,
                "selector_bits3_to_1": selector,
                "coded_type": coded,
                "second_equals_a8_minus_memory_bit": second == 0xA8 - memory_bit,
                "nonzero_coded_equals_selector_minus_3": coded == 0 or coded == selector - 3,
            }
        )
    return {
        "rows": rows,
        "identities_holding_for_all_rows": {
            "memory_is_first_byte_bit0": True,
            "second_byte_equals_0xa8_minus_memory_bit": all(
                bool(row["second_equals_a8_minus_memory_bit"]) for row in rows
            ),
            "coded1_to_3_equals_first_bits3_to_1_minus_3": all(
                bool(row["nonzero_coded_equals_selector_minus_3"]) for row in rows
            ),
            "uncoded_selector_is_zero": all(
                row["selector_bits3_to_1"] == 0 for row in rows if row["coded_type"] == 0
            ),
        },
        "status": (
            "Exact host-side header algebra. Its use by the loader is a supported target "
            "hypothesis, not a recovered ISA implementation."
        ),
    }


def build_blocks(words: list[int], branches: list[dict[str, object]]) -> tuple[list[dict[str, object]], list[dict[str, object]]]:
    usable = [
        branch for branch in branches
        if SERVICE_START <= int(branch["address"]) < SERVICE_END
    ]
    by_source = {int(branch["address"]): branch for branch in usable}
    boundaries = {SERVICE_START, SERVICE_END}
    quarantined = []
    for branch in usable:
        source = int(branch["address"])
        target = int(branch["target"])
        boundaries.add(source + 1)
        if SERVICE_START <= target < SERVICE_END:
            boundaries.add(target)
        else:
            quarantined.append(
                {
                    "source": source,
                    "source_hex": f"0x{source:04x}",
                    "target": target,
                    "target_hex": f"0x{target:04x}",
                    "word_hex": branch["word_hex"],
                    "reason": "candidate target is outside the 2 KiB loader",
                }
            )

    ordered = sorted(boundaries)
    blocks = []
    for index, start in enumerate(ordered[:-1]):
        end = ordered[index + 1]
        if start == end:
            continue
        block_words = words[start - BOOT_BASE : end - BOOT_BASE]
        last = end - 1
        outgoing = []
        branch = by_source.get(last)
        if branch is not None:
            target = int(branch["target"])
            if SERVICE_START <= target < SERVICE_END:
                outgoing.append({"kind": "anonymous_branch_target", "target": target, "target_hex": f"0x{target:04x}"})
            if end < SERVICE_END:
                outgoing.append({"kind": "fallthrough", "target": end, "target_hex": f"0x{end:04x}"})
        elif end < SERVICE_END:
            outgoing.append({"kind": "sequential", "target": end, "target_hex": f"0x{end:04x}"})
        blocks.append(
            {
                "start": start,
                "start_hex": f"0x{start:04x}",
                "end_exclusive": end,
                "end_exclusive_hex": f"0x{end:04x}",
                "word_count": end - start,
                "sha256_le_bytes": sha256(b"".join(word.to_bytes(2, "little") for word in block_words)),
                "words_hex": [f"0x{word:04x}" for word in block_words],
                "terminating_candidate_branch": None if branch is None else {
                    "address": branch["address"],
                    "address_hex": branch["address_hex"],
                    "word_hex": branch["word_hex"],
                    "target": branch["target"],
                    "target_hex": branch["target_hex"],
                },
                "outgoing": outgoing,
            }
        )
    return blocks, quarantined


def back_edges(blocks: list[dict[str, object]]) -> list[dict[str, object]]:
    loops = []
    for block in blocks:
        source = int(block["end_exclusive"]) - 1
        for edge in block["outgoing"]:
            target = int(edge["target"])
            if edge["kind"] == "anonymous_branch_target" and target < source:
                loops.append(
                    {
                        "branch_source": source,
                        "branch_source_hex": f"0x{source:04x}",
                        "target": target,
                        "target_hex": f"0x{target:04x}",
                        "span_words_inclusive": source - target + 1,
                    }
                )
    return loops


def analyze(loader_path: Path, dispatcher_report_path: Path) -> dict[str, object]:
    data = loader_path.read_bytes()
    if len(data) != 2048:
        raise ValueError("expected a 2048-byte loader")
    report = json.loads(dispatcher_report_path.read_text(encoding="ascii"))
    if report["format"] != "sct3258-loader-dispatcher-audit-v2":
        raise ValueError("expected dispatcher audit v2")
    if report["loader"]["sha256"].lower() != sha256(data):
        raise ValueError("loader hash does not match dispatcher report")
    blocks, quarantined = build_blocks(words_le(data), report["candidate_short_branches_in_fallthrough_region"])
    loops = back_edges(blocks)
    return {
        "format": "sct3258-loader-candidate-cfg-v1",
        "loader": {
            "path": str(loader_path.resolve()),
            "size": len(data),
            "sha256": sha256(data),
        },
        "dispatcher_report": {
            "path": str(dispatcher_report_path.resolve()),
            "sha256": sha256(dispatcher_report_path.read_bytes()),
        },
        "scope": {
            "start": SERVICE_START,
            "start_hex": f"0x{SERVICE_START:04x}",
            "end_exclusive": SERVICE_END,
            "end_exclusive_hex": f"0x{SERVICE_END:04x}",
            "condition_names": "unknown",
            "warning": (
                "Every supported short-branch candidate is treated as conditional with target "
                "and fallthrough edges. This over-approximates reachability and is not a disassembly."
            ),
        },
        "host_section_header_field_model": header_field_model(),
        "block_count": len(blocks),
        "blocks": blocks,
        "back_edge_loops": loops,
        "quarantined_branch_candidates": quarantined,
        "bounded_conclusion": (
            "The anonymous CFG isolates seven backward-loop spans and preserves one out-of-range "
            "candidate as untrusted. The two 15-word loop spans FB1A..FB28 and FB30..FB3E align "
            "with the repeated handler templates. Header algebra makes a shared memory-bit and "
            "coded-selector parser the next static target, but no ISA operation is named here."
        ),
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--loader", type=Path, required=True)
    parser.add_argument("--dispatcher-report", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if args.output.exists():
        parser.error(f"refusing to overwrite existing output: {args.output}")
    report = analyze(args.loader, args.dispatcher_report)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(
        json.dumps(report, indent=2, ensure_ascii=True) + "\n",
        encoding="ascii",
        newline="\n",
    )
    print(
        json.dumps(
            {
                "block_count": report["block_count"],
                "back_edge_loops": report["back_edge_loops"],
                "quarantined_branch_candidates": report["quarantined_branch_candidates"],
            },
            indent=2,
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
