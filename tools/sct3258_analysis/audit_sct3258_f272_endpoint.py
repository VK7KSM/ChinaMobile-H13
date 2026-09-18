#!/usr/bin/env python3
"""Audit the CFG-dominating 0xF272 endpoint setup in the SCT3258 loader."""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path


BOOT_BASE = 0xF800
STRUCTURED_END = 0xFB8C
SETUP_LOW_ADDRESS = 0xFA63
SETUP_HIGH_ADDRESS = 0xFA64
ENDPOINT_REGISTER = 0xE


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def words_le(data: bytes) -> list[int]:
    if len(data) != 2048:
        raise ValueError("expected an exact 2048-byte Boot RAM image")
    return [int.from_bytes(data[offset:offset + 2], "little")
            for offset in range(0, len(data), 2)]


def transfer_record(address: int, word: int) -> dict[str, object]:
    return {
        "address": address,
        "address_hex": f"0x{address:04x}",
        "word_hex": f"0x{word:04x}",
        "direction_shape": "write-like" if word >> 12 == 0x6 else "read-like",
        "data_field_hex": f"0x{word >> 8 & 0xF:x}",
        "mode_field_hex": f"0x{word >> 4 & 0xF:x}",
        "address_field_hex": f"0x{word & 0xF:x}",
    }


def build_cfg(cfg: dict[str, object]) -> tuple[
        dict[int, dict[str, object]], dict[int, list[int]], dict[int, list[int]], dict[int, int]]:
    blocks = {int(block["start"]): block for block in cfg["blocks"]}
    successors = {start: [] for start in blocks}
    predecessors = {start: [] for start in blocks}
    owners: dict[int, int] = {}
    for start, block in blocks.items():
        for address in range(start, int(block["end_exclusive"])):
            if address in owners:
                raise ValueError(f"overlapping CFG blocks at 0x{address:04x}")
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


def reachable_from(entry: int, successors: dict[int, list[int]]) -> set[int]:
    seen: set[int] = set()
    pending = [entry]
    while pending:
        start = pending.pop()
        if start in seen:
            continue
        seen.add(start)
        pending.extend(successors[start])
    return seen


def dominators(entry: int, reachable: set[int],
               predecessors: dict[int, list[int]]) -> dict[int, set[int]]:
    result = {start: set(reachable) for start in reachable}
    result[entry] = {entry}
    changed = True
    while changed:
        changed = False
        for start in sorted(reachable - {entry}):
            preds = [pred for pred in predecessors[start] if pred in reachable]
            if not preds:
                continue
            current = set(result[preds[0]])
            for pred in preds[1:]:
                current.intersection_update(result[pred])
            current.add(start)
            if current != result[start]:
                result[start] = current
                changed = True
    return result


def analyze(loader_path: Path, cfg_path: Path) -> dict[str, object]:
    loader_data = loader_path.read_bytes()
    words = words_le(loader_data)
    cfg_data = cfg_path.read_bytes()
    cfg = json.loads(cfg_data.decode("ascii"))
    if cfg.get("format") != "sct3258-loader-quarantined-nibble-cfg-v3":
        raise ValueError("expected quarantined nibble CFG v3")
    if cfg["artifacts"]["loader"]["sha256"].lower() != sha256(loader_data):
        raise ValueError("loader hash does not match CFG")
    if words[SETUP_LOW_ADDRESS - BOOT_BASE] != 0x2E72 \
            or words[SETUP_HIGH_ADDRESS - BOOT_BASE] != 0x3EF2:
        raise ValueError("FA63/FA64 0xF272 setup anchor mismatch")

    blocks, successors, predecessors, owners = build_cfg(cfg)
    entry = min(blocks)
    reachable = reachable_from(entry, successors)
    dom = dominators(entry, reachable, predecessors)
    setup_block = owners[SETUP_LOW_ADDRESS]

    endpoint_uses = []
    known_writes_after_setup = []
    unclassified_first_field_e = []
    for address in range(SETUP_HIGH_ADDRESS + 1, STRUCTURED_END):
        word = words[address - BOOT_BASE]
        major = word >> 12
        first_field = word >> 8 & 0xF
        block = owners.get(address)
        setup_dominates = block in dom and setup_block in dom[block]
        if major in (0x6, 0x7) and (word & 0xF) == ENDPOINT_REGISTER:
            item = transfer_record(address, word)
            item["block_start_hex"] = f"0x{block:04x}" if block is not None else None
            item["setup_block_dominates"] = setup_dominates
            endpoint_uses.append(item)
        if major in (0x2, 0x3, 0xA, 0x7) and first_field == ENDPOINT_REGISTER:
            known_writes_after_setup.append({
                "address_hex": f"0x{address:04x}",
                "word_hex": f"0x{word:04x}",
                "major_hex": f"0x{major:x}",
            })
        if major in (0x8, 0xB) and first_field == ENDPOINT_REGISTER:
            unclassified_first_field_e.append({
                "address_hex": f"0x{address:04x}",
                "word_hex": f"0x{word:04x}",
                "major_hex": f"0x{major:x}",
                "warning": "major-8/B field direction is not recovered",
            })

    if not endpoint_uses or not all(item["setup_block_dominates"] for item in endpoint_uses):
        raise ValueError("0xF272 setup does not dominate every structured endpoint use")
    anchor_words = {
        0xFA87: 0x66FE,
        0xFA8A: 0x65FE,
        0xFB1E: 0x76AE,
        0xFB34: 0x76AE,
    }
    for address, expected in anchor_words.items():
        if words[address - BOOT_BASE] != expected:
            raise ValueError(f"endpoint use anchor mismatch at 0x{address:04x}")

    return {
        "format": "sct3258-f272-endpoint-cfg-audit-v1",
        "artifacts": {
            "loader": {"path": str(loader_path.resolve()), "size": len(loader_data),
                       "sha256": sha256(loader_data)},
            "cfg": {"path": str(cfg_path.resolve()), "size": len(cfg_data),
                    "sha256": sha256(cfg_data)},
        },
        "setup": {
            "words": ["0x2e72", "0x3ef2"],
            "addresses_hex": ["0xfa63", "0xfa64"],
            "register_field_hex": "0xe",
            "combined_value_hex": "0xf272",
            "block_start_hex": f"0x{setup_block:04x}",
            "entry_block_hex": f"0x{entry:04x}",
        },
        "endpoint_uses": endpoint_uses,
        "endpoint_use_counts": {
            "total": len(endpoint_uses),
            "read_like": sum(item["direction_shape"] == "read-like"
                             for item in endpoint_uses),
            "write_like": sum(item["direction_shape"] == "write-like"
                              for item in endpoint_uses),
        },
        "known_result_writes_to_field_e_after_setup": known_writes_after_setup,
        "unclassified_major_8_b_first_field_e_after_setup": unclassified_first_field_e,
        "bounded_conclusion": (
            "The FA63/FA64 0xF272 setup dominates every later major-6/7 transfer "
            "that uses field E as its address field, including write-like FA87/FA8A "
            "and read-like FB1E/FB34. No later major-2/3, major-A, or major-7 result "
            "write targets field E, but major-8/B field direction remains unknown and "
            "is listed as a clobber alternative. In the HPI-maintenance loader context, "
            "0xF272 is the strongest current I/O endpoint candidate; absent an SCT3258 "
            "internal memory map or an independently named transaction, it is not yet "
            "proven to be hpird, hpixd, hpictl, instruction RAM, or data RAM."
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
        "setup": report["setup"],
        "endpoint_use_counts": report["endpoint_use_counts"],
        "known_result_writes_to_field_e_after_setup":
            report["known_result_writes_to_field_e_after_setup"],
        "unclassified_major_8_b_first_field_e_after_setup":
            report["unclassified_major_8_b_first_field_e_after_setup"],
    }, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
