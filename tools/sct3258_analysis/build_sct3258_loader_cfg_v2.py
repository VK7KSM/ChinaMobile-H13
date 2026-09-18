#!/usr/bin/env python3
"""Build a quarantined anonymous CFG from the expanded SCT3258 branch audit."""

from __future__ import annotations

import argparse
import hashlib
import json
import math
from collections import Counter
from pathlib import Path


BOOT_BASE = 0xF800
BOOT_END = 0xFC00
SERVICE_START = 0xFA2F
STRUCTURED_END = 0xFBA0
SUSPECTED_LITERAL_RANGES = ((0xFB8C, 0xFB9C), (0xFBA0, 0xFC00))


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def words_le(data: bytes) -> list[int]:
    if len(data) != 2048:
        raise ValueError("expected an exact 2048-byte Boot RAM image")
    return [int.from_bytes(data[offset:offset + 2], "little")
            for offset in range(0, len(data), 2)]


def artifact(path: Path, data: bytes) -> dict[str, object]:
    return {
        "path": str(path.resolve()),
        "size": len(data),
        "sha256": sha256(data),
    }


def in_range(address: int, ranges: tuple[tuple[int, int], ...]) -> bool:
    return any(start <= address < end for start, end in ranges)


def is_structured_address(address: int) -> bool:
    return (SERVICE_START <= address < STRUCTURED_END
            and not in_range(address, SUSPECTED_LITERAL_RANGES))


def shannon_entropy(data: bytes) -> float:
    counts = Counter(data)
    length = len(data)
    return -sum((count / length) * math.log2(count / length)
                for count in counts.values())


def range_record(data: bytes, start: int, end: int) -> dict[str, object]:
    begin = (start - BOOT_BASE) * 2
    finish = (end - BOOT_BASE) * 2
    raw = data[begin:finish]
    return {
        "start_hex": f"0x{start:04x}",
        "end_exclusive_hex": f"0x{end:04x}",
        "word_count": end - start,
        "byte_count": len(raw),
        "unique_byte_count": len(set(raw)),
        "shannon_entropy_bits_per_byte": round(shannon_entropy(raw), 6),
        "sha256": sha256(raw),
        "status": "suspected literal/table region; excluded from CFG edges",
    }


def branch_class(item: dict[str, object]) -> str:
    opcode = int(item["opcode_high_byte"])
    if bool(item["old_narrow_model"]):
        return "legacy_narrow_family_candidate"
    if opcode in (0x0F, 0x4E, 0x4F):
        return "exact_opcode_seen_in_bootled_loop_anchor"
    return "bootled_constrained_family_extrapolation"


def classify_candidates(records: list[dict[str, object]]) -> tuple[
        list[dict[str, object]], list[dict[str, object]], list[dict[str, object]]]:
    usable = []
    degenerate = []
    quarantined = []
    for original in records:
        item = dict(original)
        source = int(item["address"])
        target = int(item["target"])
        item["branch_model_class"] = branch_class(item)
        if not is_structured_address(source):
            item["quarantine_reason"] = "source is in suspected literal/table region"
            quarantined.append(item)
        elif target == source + 1:
            item["quarantine_reason"] = "candidate target equals sequential fallthrough"
            degenerate.append(item)
        elif not is_structured_address(target):
            item["quarantine_reason"] = "target leaves structured analysis region"
            quarantined.append(item)
        else:
            usable.append(item)
    return usable, degenerate, quarantined


def build_blocks(words: list[int], branches: list[dict[str, object]]) -> list[dict[str, object]]:
    boundaries = {SERVICE_START, 0xFB8C, 0xFB9C, STRUCTURED_END}
    by_source = {int(item["address"]): item for item in branches}
    for item in branches:
        source = int(item["address"])
        target = int(item["target"])
        boundaries.add(source + 1)
        boundaries.add(target)
    ordered = sorted(boundaries)
    blocks = []
    for start, end in zip(ordered, ordered[1:]):
        if not is_structured_address(start):
            continue
        block_words = words[start - BOOT_BASE:end - BOOT_BASE]
        branch = by_source.get(end - 1)
        outgoing = []
        if branch is not None:
            target = int(branch["target"])
            outgoing.append({
                "kind": "anonymous_branch_target",
                "target": target,
                "target_hex": f"0x{target:04x}",
            })
            if end < STRUCTURED_END and is_structured_address(end):
                outgoing.append({
                    "kind": "fallthrough",
                    "target": end,
                    "target_hex": f"0x{end:04x}",
                })
        elif end < STRUCTURED_END and is_structured_address(end):
            outgoing.append({
                "kind": "sequential",
                "target": end,
                "target_hex": f"0x{end:04x}",
            })
        blocks.append({
            "start": start,
            "start_hex": f"0x{start:04x}",
            "end_exclusive": end,
            "end_exclusive_hex": f"0x{end:04x}",
            "word_count": end - start,
            "words_hex": [f"0x{word:04x}" for word in block_words],
            "terminating_candidate": branch,
            "outgoing": outgoing,
        })
    return blocks


def cyclic_components(blocks: list[dict[str, object]]) -> list[dict[str, object]]:
    starts = {int(block["start"]) for block in blocks}
    graph = {
        int(block["start"]): [int(edge["target"]) for edge in block["outgoing"]
                              if int(edge["target"]) in starts]
        for block in blocks
    }
    index = 0
    indices: dict[int, int] = {}
    lowlink: dict[int, int] = {}
    stack: list[int] = []
    on_stack: set[int] = set()
    components: list[list[int]] = []

    def visit(node: int) -> None:
        nonlocal index
        indices[node] = index
        lowlink[node] = index
        index += 1
        stack.append(node)
        on_stack.add(node)
        for successor in graph[node]:
            if successor not in indices:
                visit(successor)
                lowlink[node] = min(lowlink[node], lowlink[successor])
            elif successor in on_stack:
                lowlink[node] = min(lowlink[node], indices[successor])
        if lowlink[node] == indices[node]:
            component = []
            while True:
                member = stack.pop()
                on_stack.remove(member)
                component.append(member)
                if member == node:
                    break
            components.append(sorted(component))

    for node in sorted(graph):
        if node not in indices:
            visit(node)

    cyclic = []
    for component in components:
        self_loop = len(component) == 1 and component[0] in graph[component[0]]
        if len(component) > 1 or self_loop:
            cyclic.append({
                "block_count": len(component),
                "block_starts_hex": [f"0x{member:04x}" for member in component],
                "minimum_hex": f"0x{min(component):04x}",
                "maximum_hex": f"0x{max(component):04x}",
            })
    return cyclic


def exact_occurrences(words: list[int], value: int) -> list[str]:
    return [f"0x{BOOT_BASE + index:04x}" for index, word in enumerate(words)
            if word == value]


def analyze(loader_path: Path, branch_report_path: Path) -> dict[str, object]:
    loader_data = loader_path.read_bytes()
    words = words_le(loader_data)
    report_data = branch_report_path.read_bytes()
    report = json.loads(report_data.decode("ascii"))
    if report["format"] != "sct3258-expanded-short-branch-family-v2":
        raise ValueError("expected expanded short-branch family v2")
    if report["artifacts"]["loader"]["sha256"].lower() != sha256(loader_data):
        raise ValueError("loader hash does not match branch report")

    records = list(report["hpi_fallthrough_candidates"])
    usable, degenerate, quarantined = classify_candidates(records)
    blocks = build_blocks(words, usable)
    by_address = {int(item["address"]): item for item in usable}
    tight = by_address.get(0xFB6D)
    if tight is None or int(tight["target"]) != 0xFB6C or words[0xFB6C - BOOT_BASE] != 0xA592:
        raise ValueError("FB6C/FB6D tight-loop anchor mismatch")

    back_edges = [
        {
            "source_hex": item["address_hex"],
            "word_hex": item["word_hex"],
            "target_hex": item["target_hex"],
            "span_words_inclusive": int(item["address"]) - int(item["target"]) + 1,
            "branch_model_class": item["branch_model_class"],
        }
        for item in usable if int(item["target"]) < int(item["address"])
    ]

    return {
        "format": "sct3258-loader-quarantined-candidate-cfg-v2",
        "artifacts": {
            "loader": artifact(loader_path, loader_data),
            "expanded_branch_report": artifact(branch_report_path, report_data),
        },
        "scope": {
            "structured_start_hex": f"0x{SERVICE_START:04x}",
            "structured_end_exclusive_hex": f"0x{STRUCTURED_END:04x}",
            "warning": (
                "This is an over-approximating anonymous CFG, not an ISA disassembly. "
                "All branch conditions and non-branch instruction meanings remain unknown."
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
        },
        "degenerate_candidates": degenerate,
        "quarantined_candidates": quarantined,
        "back_edges": back_edges,
        "cyclic_components": cyclic_components(blocks),
        "blocks": blocks,
        "tight_loop_anchor": {
            "range": "0xfb6c..0xfb6d",
            "body_word_hex": "0xa592",
            "branch_word_hex": tight["word_hex"],
            "branch_target_hex": tight["target_hex"],
            "bounded_interpretation": (
                "If the expanded branch encoding is correct at 0xfb6d, 0xa592 is the "
                "only non-branch word in this two-word tight loop. Its operation is unknown."
            ),
        },
        "a8_a9_exact_operand_pairs": {
            "0x06": {"a9": exact_occurrences(words, 0xA906),
                     "a8": exact_occurrences(words, 0xA806)},
            "0x07": {"a9": exact_occurrences(words, 0xA907),
                     "a8": exact_occurrences(words, 0xA807)},
            "0x0e": {"a9": exact_occurrences(words, 0xA90E),
                     "a8": exact_occurrences(words, 0xA80E)},
        },
        "bounded_conclusion": (
            "The expanded family exposes nested back edges omitted by the old 12-branch "
            "model. The FB6C/FB6D pair is a mechanically isolated tight-loop candidate, "
            "but neither A592 nor the A8/A9 low operands may yet be named as an HPI "
            "status register, endpoint, memory selector, or coded-state operation."
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
        "tight_loop_anchor": report["tight_loop_anchor"],
        "back_edges": report["back_edges"],
        "cyclic_components": report["cyclic_components"],
    }, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
