#!/usr/bin/env python3
"""Compute CFG-aware reaching definitions for SCT3258 major-A nibble fields."""

from __future__ import annotations

import argparse
import hashlib
import json
from collections import deque
from pathlib import Path


BOOT_BASE = 0xF800
ENTRY_UNKNOWN = -1
TARGET_FIELDS = (0x9, 0x2, 0x5)


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def field_hex(field: int) -> str:
    return f"0x{field:x}"


def address_hex(address: int) -> str:
    return "entry-unknown" if address == ENTRY_UNKNOWN else f"0x{address:04x}"


def empty_state() -> dict[int, set[int]]:
    return {field: set() for field in range(16)}


def entry_state() -> dict[int, set[int]]:
    return {field: {ENTRY_UNKNOWN} for field in range(16)}


def clone_state(state: dict[int, set[int]]) -> dict[int, set[int]]:
    return {field: set(definitions) for field, definitions in state.items()}


def merge_states(states: list[dict[int, set[int]]]) -> dict[int, set[int]]:
    merged = empty_state()
    for state in states:
        for field, definitions in state.items():
            merged[field].update(definitions)
    return merged


def same_state(left: dict[int, set[int]], right: dict[int, set[int]]) -> bool:
    return all(left[field] == right[field] for field in range(16))


def major_a_fields(word: int) -> tuple[int, int, int] | None:
    if word >> 12 != 0xA:
        return None
    return (word >> 8 & 0xF, word >> 4 & 0xF, word & 0xF)


def block_words(block: dict[str, object]) -> list[tuple[int, int]]:
    start = int(block["start"])
    return [(start + offset, int(word_hex, 16))
            for offset, word_hex in enumerate(block["words_hex"])]


def transfer(block: dict[str, object], incoming: dict[int, set[int]]) -> dict[int, set[int]]:
    state = clone_state(incoming)
    for address, word in block_words(block):
        decoded = major_a_fields(word)
        if decoded is not None:
            result, _, _ = decoded
            state[result] = {address}
    return state


def serialize_defs(definitions: set[int]) -> list[str]:
    return [address_hex(address) for address in sorted(definitions)]


def analyze(loader_path: Path, cfg_path: Path) -> dict[str, object]:
    loader_data = loader_path.read_bytes()
    if len(loader_data) != 2048:
        raise ValueError("expected an exact 2048-byte Boot RAM image")
    cfg_data = cfg_path.read_bytes()
    cfg = json.loads(cfg_data.decode("ascii"))
    if cfg.get("format") != "sct3258-loader-quarantined-nibble-cfg-v3":
        raise ValueError("expected the quarantined nibble CFG v3")
    if cfg["artifacts"]["loader"]["sha256"].lower() != sha256(loader_data):
        raise ValueError("loader hash does not match CFG")

    blocks = {int(block["start"]): block for block in cfg["blocks"]}
    if not blocks:
        raise ValueError("CFG has no blocks")
    entry = min(blocks)
    successors: dict[int, list[int]] = {}
    predecessors: dict[int, list[int]] = {start: [] for start in blocks}
    for start, block in blocks.items():
        targets = [int(edge["target"]) for edge in block["outgoing"]
                   if int(edge["target"]) in blocks]
        successors[start] = sorted(set(targets))
        for target in successors[start]:
            predecessors[target].append(start)
    for pred_list in predecessors.values():
        pred_list.sort()

    reachable: set[int] = set()
    queue = deque([entry])
    while queue:
        start = queue.popleft()
        if start in reachable:
            continue
        reachable.add(start)
        queue.extend(successors[start])

    incoming = {start: empty_state() for start in blocks}
    outgoing = {start: empty_state() for start in blocks}
    incoming[entry] = entry_state()
    work = deque(sorted(reachable))
    iterations = 0
    while work:
        start = work.popleft()
        iterations += 1
        if start != entry:
            pred_states = [outgoing[pred] for pred in predecessors[start]
                           if pred in reachable]
            new_incoming = merge_states(pred_states)
            if not same_state(new_incoming, incoming[start]):
                incoming[start] = new_incoming
        new_outgoing = transfer(blocks[start], incoming[start])
        if not same_state(new_outgoing, outgoing[start]):
            outgoing[start] = new_outgoing
            work.extend(target for target in successors[start]
                        if target in reachable)

    uses: list[dict[str, object]] = []
    definitions: list[dict[str, object]] = []
    for start in sorted(reachable):
        state = clone_state(incoming[start])
        for address, word in block_words(blocks[start]):
            decoded = major_a_fields(word)
            if decoded is None:
                continue
            result, input_1, input_2 = decoded
            definitions.append({
                "address_hex": address_hex(address),
                "word_hex": f"0x{word:04x}",
                "result_field_hex": field_hex(result),
            })
            for position, field in ((1, input_1), (2, input_2)):
                if field in TARGET_FIELDS:
                    uses.append({
                        "address": address,
                        "address_hex": address_hex(address),
                        "word_hex": f"0x{word:04x}",
                        "input_position": position,
                        "field_hex": field_hex(field),
                        "reaching_definitions": serialize_defs(state[field]),
                    })
            state[result] = {address}

    anchor = 0xFB6C
    if anchor not in blocks or block_words(blocks[anchor])[0][1] != 0xA592:
        raise ValueError("FB6C A592 anchor mismatch")
    anchor_predecessors = {}
    for pred in predecessors[anchor]:
        anchor_predecessors[address_hex(pred)] = {
            field_hex(field): serialize_defs(outgoing[pred][field])
            for field in TARGET_FIELDS
        }
    first_entry_defs = outgoing.get(0xFB6B, empty_state())
    loop_entry_defs = outgoing.get(0xFB6D, empty_state())
    loop_carried_only = {
        field_hex(field): serialize_defs(
            loop_entry_defs[field] - first_entry_defs[field]
        ) for field in TARGET_FIELDS
    }

    return {
        "format": "sct3258-major-a-cfg-reaching-definitions-v2",
        "artifacts": {
            "loader": {"path": str(loader_path.resolve()), "size": len(loader_data),
                       "sha256": sha256(loader_data)},
            "cfg": {"path": str(cfg_path.resolve()), "size": len(cfg_data),
                    "sha256": sha256(cfg_data)},
        },
        "method": {
            "entry_block_hex": address_hex(entry),
            "reachable_blocks": len(reachable),
            "total_blocks": len(blocks),
            "fixed_point_work_items": iterations,
            "field_layout_hypothesis":
                "[major A][result-like][input-like 1][input-like 2]",
            "warning": (
                "All anonymous branch targets and fallthroughs in CFG v3 are retained. "
                "Results are may-reach sets over an over-approximating CFG, not recovered "
                "instruction semantics or proof that every candidate edge is executable."
            ),
        },
        "target_fields_hex": [field_hex(field) for field in TARGET_FIELDS],
        "definitions": definitions,
        "target_field_uses": uses,
        "fb6c_predecessor_contributions": anchor_predecessors,
        "fb6c_loop_carried_only_definitions": loop_carried_only,
        "bounded_conclusion": (
            "The initial FB6B entry and FB6D loop-back contributions are kept separate. "
            "Only definitions added by the latter are loop-carried candidates. Empty "
            "differences or multiple may-reach definitions must not be collapsed into a "
            "single named hardware source."
        ),
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--loader", type=Path, required=True)
    parser.add_argument("--cfg", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if args.output.exists():
        parser.error(f"refusing to overwrite existing output: {args.output}")
    report = analyze(args.loader, args.cfg)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2, ensure_ascii=True) + "\n",
                           encoding="ascii", newline="\n")
    print(json.dumps({
        "format": report["format"],
        "method": report["method"],
        "fb6c_predecessor_contributions": report["fb6c_predecessor_contributions"],
        "fb6c_loop_carried_only_definitions":
            report["fb6c_loop_carried_only_definitions"],
    }, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
