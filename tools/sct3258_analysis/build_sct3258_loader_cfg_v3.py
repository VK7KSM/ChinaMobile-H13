#!/usr/bin/env python3
"""Build the quarantined SCT3258 CFG from the v3 branch-nibble model."""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path

from build_sct3258_loader_cfg_v2 import (
    BOOT_BASE,
    artifact,
    build_blocks,
    classify_candidates,
    cyclic_components,
    range_record,
    words_le,
    SUSPECTED_LITERAL_RANGES,
)


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def normalize(item: dict[str, object]) -> dict[str, object]:
    result = dict(item)
    word = int(result["word"])
    result["opcode_high_byte"] = word >> 8
    result["old_narrow_model"] = bool(result["also_in_old_narrow_model"])
    return result


def analyze(loader_path: Path, branch_report_path: Path) -> dict[str, object]:
    loader_data = loader_path.read_bytes()
    words = words_le(loader_data)
    branch_data = branch_report_path.read_bytes()
    branch_report = json.loads(branch_data.decode("ascii"))
    if branch_report["format"] != "sct3258-short-branch-nibble-model-v3":
        raise ValueError("expected SCT3258 branch nibble model v3")
    if branch_report["artifacts"]["loader"]["sha256"].lower() != sha256(loader_data):
        raise ValueError("loader hash does not match branch report")

    records = [normalize(item) for item in branch_report["hpi_candidates"]]
    usable, degenerate, quarantined = classify_candidates(records)
    blocks = build_blocks(words, usable)
    by_address = {int(item["address"]): item for item in usable}
    tight = by_address.get(0xFB6D)
    if tight is None or int(tight["target"]) != 0xFB6C \
            or words[0xFB6C - BOOT_BASE] != 0xA592:
        raise ValueError("FB6C/FB6D tight-loop anchor mismatch")

    back_edges = [
        {
            "source_hex": item["address_hex"],
            "word_hex": item["word_hex"],
            "target_hex": item["target_hex"],
            "span_words_inclusive": int(item["address"]) - int(item["target"]) + 1,
        }
        for item in usable if int(item["target"]) < int(item["address"])
    ]
    rejected = branch_report["legacy_model_candidates_rejected_by_nibble_model"]
    return {
        "format": "sct3258-loader-quarantined-nibble-cfg-v3",
        "artifacts": {
            "loader": artifact(loader_path, loader_data),
            "branch_nibble_report": artifact(branch_report_path, branch_data),
        },
        "scope": {
            "warning": (
                "This is an over-approximating anonymous CFG, not an ISA disassembly. "
                "Condition names, call/return rules, registers, and memory roles remain unknown."
            ),
        },
        "suspected_literal_regions": [
            range_record(loader_data, start, end)
            for start, end in SUSPECTED_LITERAL_RANGES
        ],
        "candidate_summary": {
            "input_hpi_candidates": len(records),
            "structured_edge_candidates": len(usable),
            "degenerate_target_equals_fallthrough": len(degenerate),
            "quarantined_candidates": len(quarantined),
            "basic_blocks": len(blocks),
            "legacy_84_c4_candidates_rejected_before_cfg": len(rejected),
        },
        "legacy_84_c4_candidates_rejected_before_cfg": rejected,
        "degenerate_candidates": degenerate,
        "quarantined_candidates": quarantined,
        "back_edges": back_edges,
        "cyclic_components": cyclic_components(blocks),
        "blocks": blocks,
        "tight_loop_anchor": {
            "range": "0xfb6c..0xfb6d",
            "operation_word_hex": "0xa592",
            "operation_nibbles": ["0xa", "0x5", "0x9", "0x2"],
            "branch_word_hex": tight["word_hex"],
            "branch_target_hex": tight["target_hex"],
            "bounded_interpretation": (
                "The loop repeatedly executes one major-A operation with operand nibbles "
                "5/9/2 and then a condition-5 branch. It is not a proven direct HPI read."
            ),
        },
        "bounded_conclusion": (
            "Removing the unsupported legacy 84/C4 edges yields the v3 anonymous CFG. "
            "The paired nested loops and FB6C/FB6D tight loop remain, while no branch "
            "edge is inferred from 8478, C472, or 84BE."
        ),
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--loader", type=Path, required=True)
    parser.add_argument("--branch-report", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if args.output.exists():
        parser.error(f"refusing to overwrite existing output: {args.output}")
    report = analyze(args.loader, args.branch_report)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2, ensure_ascii=True) + "\n",
                           encoding="ascii", newline="\n")
    print(json.dumps({
        "format": report["format"],
        "candidate_summary": report["candidate_summary"],
        "back_edges": report["back_edges"],
        "cyclic_components": report["cyclic_components"],
        "tight_loop_anchor": report["tight_loop_anchor"],
    }, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
