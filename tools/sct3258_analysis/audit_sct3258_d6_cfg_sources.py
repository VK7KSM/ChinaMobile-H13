#!/usr/bin/env python3
"""Trace CFG-aware sources of loader address fields D and 6."""

from __future__ import annotations

import argparse
import hashlib
import json
from collections import deque
from pathlib import Path


ENTRY_UNKNOWN = -1
TARGET_FIELDS = (0xD, 0x6)
TARGET_USES = (0xFB29, 0xFB2A, 0xFB3F, 0xFB40)


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


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


def block_words(block: dict[str, object]) -> list[tuple[int, int]]:
    start = int(block["start"])
    return [(start + offset, int(word_hex, 16))
            for offset, word_hex in enumerate(block["words_hex"])]


def supported_write_fields(word: int, conservative_b: bool) -> list[tuple[int, str]]:
    major = word >> 12
    lower_1 = word >> 8 & 0xF
    low_operand_1 = word >> 4 & 0xF
    low_operand_2 = word & 0xF
    if major in (0x2, 0x3):
        return [(lower_1, "major-2/3 immediate destination")]
    if major == 0x7:
        return [(lower_1, "major-7 read-like data result")]
    if major == 0x8:
        return [(low_operand_1, "major-8 low-operand-1 result-like")]
    if major == 0xA:
        return [(lower_1, "major-A result-like")]
    if major == 0xB and conservative_b:
        return [
            (low_operand_1, "major-B conservative low-operand-1 alternative"),
            (low_operand_2, "major-B conservative low-operand-2 alternative"),
        ]
    return []


def transfer(block: dict[str, object], incoming: dict[int, set[int]],
             conservative_b: bool) -> dict[int, set[int]]:
    state = clone_state(incoming)
    for address, word in block_words(block):
        for field, _ in supported_write_fields(word, conservative_b):
            state[field] = {address}
    return state


def build_graph(cfg: dict[str, object]) -> tuple[
        dict[int, dict[str, object]], dict[int, list[int]], dict[int, list[int]], dict[int, int]]:
    blocks = {int(block["start"]): block for block in cfg["blocks"]}
    successors = {start: [] for start in blocks}
    predecessors = {start: [] for start in blocks}
    owners: dict[int, int] = {}
    for start, block in blocks.items():
        for address, _ in block_words(block):
            if address in owners:
                raise ValueError(f"overlapping block at 0x{address:04x}")
            owners[address] = start
        for edge in block["outgoing"]:
            target = int(edge["target"])
            if target in blocks:
                successors[start].append(target)
                predecessors[target].append(start)
    for mapping in (successors, predecessors):
        for start in mapping:
            mapping[start] = sorted(set(mapping[start]))
    return blocks, successors, predecessors, owners


def fixed_point(blocks: dict[int, dict[str, object]], successors: dict[int, list[int]],
                predecessors: dict[int, list[int]], conservative_b: bool) -> tuple[
                    set[int], dict[int, dict[int, set[int]]],
                    dict[int, dict[int, set[int]]], int]:
    entry = min(blocks)
    reachable: set[int] = set()
    pending = deque([entry])
    while pending:
        start = pending.popleft()
        if start in reachable:
            continue
        reachable.add(start)
        pending.extend(successors[start])

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
        new_outgoing = transfer(blocks[start], incoming[start], conservative_b)
        if not same_state(new_outgoing, outgoing[start]):
            outgoing[start] = new_outgoing
            work.extend(target for target in successors[start] if target in reachable)
    return reachable, incoming, outgoing, iterations


def serialize(definitions: set[int]) -> list[str]:
    return [address_hex(address) for address in sorted(definitions)]


def state_before(address: int, blocks: dict[int, dict[str, object]], owners: dict[int, int],
                 incoming: dict[int, dict[int, set[int]]], conservative_b: bool) -> dict[int, set[int]]:
    owner = owners[address]
    state = clone_state(incoming[owner])
    for current, word in block_words(blocks[owner]):
        if current == address:
            return state
        for field, _ in supported_write_fields(word, conservative_b):
            state[field] = {current}
    raise ValueError(f"address 0x{address:04x} not found in owner block")


def definition_catalog(blocks: dict[int, dict[str, object]], conservative_b: bool) -> dict[int, dict[str, object]]:
    result = {
        ENTRY_UNKNOWN: {"address_hex": "entry-unknown", "word_hex": None,
                        "write_roles": ["unknown entry state"]}
    }
    for block in blocks.values():
        for address, word in block_words(block):
            roles = [role for field, role in supported_write_fields(word, conservative_b)
                     if field in TARGET_FIELDS]
            if roles:
                result[address] = {
                    "address_hex": address_hex(address),
                    "word_hex": f"0x{word:04x}",
                    "write_roles": roles,
                }
    return result


def run_model(blocks: dict[int, dict[str, object]], successors: dict[int, list[int]],
              predecessors: dict[int, list[int]], owners: dict[int, int],
              conservative_b: bool) -> dict[str, object]:
    reachable, incoming, outgoing, iterations = fixed_point(
        blocks, successors, predecessors, conservative_b
    )
    uses = {}
    all_reaching: set[int] = set()
    for address in TARGET_USES:
        state = state_before(address, blocks, owners, incoming, conservative_b)
        uses[address_hex(address)] = {
            f"field_{field:x}_reaching_definitions": serialize(state[field])
            for field in TARGET_FIELDS
        }
        for field in TARGET_FIELDS:
            all_reaching.update(state[field])

    pred_split = {}
    for block_start in (0xFB29, 0xFB3F):
        pred_split[address_hex(block_start)] = {
            address_hex(pred): {
                f"field_{field:x}_outgoing_definitions": serialize(outgoing[pred][field])
                for field in TARGET_FIELDS
            } for pred in predecessors[block_start] if pred in reachable
        }
    catalog = definition_catalog(blocks, conservative_b)
    return {
        "model": (
            "supported writes plus both major-B low operands as possible results"
            if conservative_b else
            "supported writes; major-B result direction omitted"
        ),
        "reachable_blocks": len(reachable),
        "fixed_point_work_items": iterations,
        "target_states_before_instruction": uses,
        "target_block_predecessor_contributions": pred_split,
        "reaching_definition_catalog": [catalog[address] for address in sorted(all_reaching)],
    }


def analyze(loader_path: Path, cfg_path: Path) -> dict[str, object]:
    loader_data = loader_path.read_bytes()
    cfg_data = cfg_path.read_bytes()
    if len(loader_data) != 2048:
        raise ValueError("expected an exact 2048-byte Boot RAM image")
    cfg = json.loads(cfg_data.decode("ascii"))
    if cfg.get("format") != "sct3258-loader-quarantined-nibble-cfg-v3":
        raise ValueError("expected quarantined nibble CFG v3")
    if cfg["artifacts"]["loader"]["sha256"].lower() != sha256(loader_data):
        raise ValueError("loader hash does not match CFG")
    blocks, successors, predecessors, owners = build_graph(cfg)
    expected = {0xFB10: 0xADA0, 0xFB1E: 0x76AE, 0xFB29: 0x7CFD,
                0xFB2A: 0x6CF6, 0xFB34: 0x76AE, 0xFB3F: 0x7CF6,
                0xFB40: 0x6CFD}
    for address, word in expected.items():
        owner = owners.get(address)
        actual = dict(block_words(blocks[owner])).get(address) if owner is not None else None
        if actual != word:
            raise ValueError(f"anchor mismatch at 0x{address:04x}")

    models = {
        "major_b_omitted": run_model(
            blocks, successors, predecessors, owners, False
        ),
        "major_b_both_low_operands_conservative": run_model(
            blocks, successors, predecessors, owners, True
        ),
    }
    expected_states = {
        "0xfb29": {"field_d_reaching_definitions": ["0xfb10", "0xfb52"],
                    "field_6_reaching_definitions": ["0xfb1e", "0xfb34"]},
        "0xfb2a": {"field_d_reaching_definitions": ["0xfb10", "0xfb52"],
                    "field_6_reaching_definitions": ["0xfb1e", "0xfb34"]},
        "0xfb3f": {"field_d_reaching_definitions": ["0xfb10", "0xfb52"],
                    "field_6_reaching_definitions": ["0xfb1e", "0xfb34"]},
        "0xfb40": {"field_d_reaching_definitions": ["0xfb10", "0xfb52"],
                    "field_6_reaching_definitions": ["0xfb1e", "0xfb34"]},
    }
    for name, model in models.items():
        if model["target_states_before_instruction"] != expected_states:
            raise ValueError(
                "D/6 reaching-definition gate failed for " + name + ": " +
                json.dumps(model["target_states_before_instruction"], sort_keys=True)
            )

    return {
        "format": "sct3258-d6-cfg-sources-v1",
        "artifacts": {
            "loader": {"path": str(loader_path.resolve()), "size": len(loader_data),
                       "sha256": sha256(loader_data)},
            "cfg": {"path": str(cfg_path.resolve()), "size": len(cfg_data),
                    "sha256": sha256(cfg_data)},
        },
        "write_models": {
            "major_2_3": "bits 11:8 destination-like",
            "major_7": "bits 11:8 read-like data result",
            "major_8": "bits 7:4 result-like",
            "major_a": "bits 11:8 result-like",
            "major_b": "omitted in one model; both low operands write in conservative model",
        },
        "models": models,
        "bounded_conclusion": (
            "Both the supported-write model and an intentionally over-conservative model "
            "where every major-B word writes both low operands yield the same D/6 sources. "
            "Field D may reach all four transfers from FB10 ADA0 or the CFG-loop-carried "
            "FB52 3D89 immediate-high write. Field 6 at every transfer may come only from "
            "FB1E or FB34. Both are 76AE read-like transfers "
            "whose address field E is backed by the dominating F272 setup. This identifies "
            "a stable F272-read -> field-6 -> address-use transaction shape, but it does not "
            "yet name F272 or the memory spaces selected by D and 6."
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
        "model_states": {
            name: model["target_states_before_instruction"]
            for name, model in report["models"].items()
        },
        "bounded_conclusion": report["bounded_conclusion"],
    }, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
