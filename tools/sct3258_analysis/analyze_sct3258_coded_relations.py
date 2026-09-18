#!/usr/bin/env python3
"""Find evidence-backed relationships between SCT3258 coded section classes.

The tool compares sections only when their memory type, target word address, and
word count are identical.  This is a candidate-equivalence test, not a claim
that differently coded payloads necessarily represent the same plaintext.
"""

from __future__ import annotations

import argparse
import hashlib
import itertools
import json
import re
from collections import defaultdict
from pathlib import Path

from analyze_sct3258_hex import parse_sct_hex
from index_sct3258_download_stream import (
    FOUR_BYTE_COMMANDS,
    LOAD_COMMANDS,
    LOAD_COMMAND_ADDRESS_BASES,
    merge_contiguous,
)


VERSION_RE = re.compile(r"v?2_01_([^_.]+)", re.IGNORECASE)
ROLE_BY_START = {
    0x000100: "user_application",
    0x018000: "vocoder_index_04",
    0x02C000: "vocoder_index_12",
    0x040000: "vocoder_index_16",
    0x051000: "vocoder_index_23",
    0x068000: "modem2_application",
}


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def version_name(path: Path) -> str:
    match = VERSION_RE.search(path.stem)
    return match.group(1).upper() if match else path.stem


def minimal_period(data: bytes) -> int | None:
    for period in range(1, len(data) // 2 + 1):
        if all(value == data[index % period] for index, value in enumerate(data)):
            return period
    return None


def constant_or_none(values: list[int]) -> int | None:
    return values[0] if values and len(set(values)) == 1 else None


def relation(left: bytes, right: bytes) -> dict[str, object]:
    if len(left) != len(right):
        raise ValueError("relation operands have different lengths")
    xor = bytes(a ^ b for a, b in zip(left, right))
    left_words = [int.from_bytes(left[i : i + 2], "little") for i in range(0, len(left), 2)]
    right_words = [int.from_bytes(right[i : i + 2], "little") for i in range(0, len(right), 2)]
    word_xor = [a ^ b for a, b in zip(left_words, right_words)]
    word_add = [(b - a) & 0xFFFF for a, b in zip(left_words, right_words)]

    byte_map: dict[int, int] = {}
    conflicts = 0
    for a, b in zip(left, right):
        if a in byte_map and byte_map[a] != b:
            conflicts += 1
        else:
            byte_map[a] = b

    affine: list[dict[str, int]] = []
    for multiplier in range(1, 256, 2):
        offset = (right[0] - multiplier * left[0]) & 0xFF
        if all(((multiplier * a + offset) & 0xFF) == b for a, b in zip(left, right)):
            affine.append({"multiplier": multiplier, "offset": offset})

    result: dict[str, object] = {
        "bytes": len(left),
        "xor_sha256": sha256(xor),
        "xor_unique_byte_count": len(set(xor)),
        "xor_minimal_period_bytes": minimal_period(xor),
        "constant_word_xor_le": constant_or_none(word_xor),
        "constant_word_add_delta_le": constant_or_none(word_add),
        "byte_substitution_distinct_inputs": len(byte_map),
        "byte_substitution_conflicts": conflicts,
        "byte_affine_candidates_mod_256": affine,
    }
    if len(left) <= 64:
        result.update(
            {
                "left_hex": left.hex(" "),
                "right_hex": right.hex(" "),
                "xor_hex": xor.hex(" "),
                "left_words_le": left_words,
                "right_words_le": right_words,
            }
        )
    return result


def substitution_stats(pairs: list[tuple[int, int]]) -> dict[str, int]:
    mapping: dict[int, int] = {}
    conflicts = 0
    for left, right in pairs:
        if left in mapping and mapping[left] != right:
            conflicts += 1
        else:
            mapping[left] = right
    return {
        "observations": len(pairs),
        "distinct_inputs": len(mapping),
        "conflicts": conflicts,
    }


def gf2_affine_test(word_pairs: list[tuple[int, int]]) -> dict[str, object]:
    """Test whether each output bit is affine in the 16 input bits."""

    results: list[dict[str, object]] = []
    for output_bit in range(16):
        rows = []
        for source, target in word_pairs:
            coefficients = source | (1 << 16)
            rhs = (target >> output_bit) & 1
            rows.append(coefficients | (rhs << 17))

        rank = 0
        for column in range(17):
            pivot = next((index for index in range(rank, len(rows)) if (rows[index] >> column) & 1), None)
            if pivot is None:
                continue
            rows[rank], rows[pivot] = rows[pivot], rows[rank]
            for index in range(len(rows)):
                if index != rank and ((rows[index] >> column) & 1):
                    rows[index] ^= rows[rank]
            rank += 1
        consistent = all((row & ((1 << 17) - 1)) != 0 or ((row >> 17) & 1) == 0 for row in rows)
        results.append({"output_bit": output_bit, "rank": rank, "consistent": consistent})
    return {
        "observations": len(word_pairs),
        "all_output_bits_consistent": all(bool(item["consistent"]) for item in results),
        "output_bits": results,
    }


def global_coded3_to_coded2_models(raw_pairs: list[tuple[bytes, bytes]]) -> dict[str, object]:
    unique_payload_pairs: dict[tuple[str, str], tuple[bytes, bytes]] = {}
    for coded3, coded2 in raw_pairs:
        unique_payload_pairs[(sha256(coded3), sha256(coded2))] = (coded3, coded2)

    byte_pairs: list[tuple[int, int]] = []
    word_pairs: list[tuple[int, int]] = []
    xor_words: list[int] = []
    add_words: list[int] = []
    for coded3, coded2 in unique_payload_pairs.values():
        byte_pairs.extend(zip(coded3, coded2))
        for offset in range(0, len(coded3), 2):
            left = int.from_bytes(coded3[offset : offset + 2], "little")
            right = int.from_bytes(coded2[offset : offset + 2], "little")
            word_pairs.append((left, right))
            xor_words.append(left ^ right)
            add_words.append((right - left) & 0xFFFF)

    return {
        "candidate_equivalence_warning": (
            "Models assume same-target differently-coded sections have the same plaintext; "
            "the metadata alone does not prove that assumption."
        ),
        "unique_payload_pairs": len(unique_payload_pairs),
        "byte_substitution": substitution_stats(byte_pairs),
        "word_substitution_le": substitution_stats(word_pairs),
        "constant_word_xor_le": constant_or_none(xor_words),
        "distinct_word_xor_values": len(set(xor_words)),
        "constant_word_add_delta_le": constant_or_none(add_words),
        "distinct_word_add_delta_values": len(set(add_words)),
        "gf2_affine_word_map_le": gf2_affine_test(word_pairs),
    }


def extract_sections(path: Path) -> list[dict[str, object]]:
    segments, _ = parse_sct_hex(path)
    records: list[dict[str, object]] = []
    version = version_name(path)
    for run in merge_contiguous(segments):
        offset = 0
        ordinal = 0
        while offset < len(run.data):
            control = run.data[offset : offset + 2]
            if control in LOAD_COMMANDS:
                memory, coded = LOAD_COMMANDS[control]
                address = (
                    LOAD_COMMAND_ADDRESS_BASES.get(control, 0)
                    + int.from_bytes(run.data[offset + 2 : offset + 4], "little")
                )
                words = int.from_bytes(run.data[offset + 4 : offset + 6], "little")
                end = offset + 6 + words * 2
                if end > len(run.data):
                    raise ValueError(f"truncated section in {path} at 0x{run.start + offset:x}")
                payload = run.data[offset + 6 : end]
                records.append(
                    {
                        "version": version,
                        "role": ROLE_BY_START.get(run.start, f"run_0x{run.start:06x}"),
                        "run_start": run.start,
                        "ordinal": ordinal,
                        "memory": memory,
                        "coded": coded,
                        "address_words": address,
                        "word_count": words,
                        "file_address": run.start + offset,
                        "payload": payload,
                        "payload_sha256": sha256(payload),
                    }
                )
                ordinal += 1
                offset = end
                continue
            if control in FOUR_BYTE_COMMANDS:
                offset += 4
                continue
            raise ValueError(f"unknown control {control.hex()} in {path} at 0x{run.start + offset:x}")
    return records


def public_record(item: dict[str, object]) -> dict[str, object]:
    return {key: value for key, value in item.items() if key != "payload"}


def analyze(paths: list[Path]) -> dict[str, object]:
    all_sections = [item for path in paths for item in extract_sections(path)]
    by_version_and_target: dict[tuple[object, ...], list[dict[str, object]]] = defaultdict(list)
    for item in all_sections:
        key = (item["version"], item["memory"], item["address_words"], item["word_count"])
        by_version_and_target[key].append(item)

    candidates: list[dict[str, object]] = []
    raw_coded3_to_coded2: list[tuple[bytes, bytes]] = []
    seen: set[tuple[object, ...]] = set()
    for items in by_version_and_target.values():
        for left, right in itertools.combinations(items, 2):
            if left["coded"] == right["coded"]:
                continue
            identity = (
                left["version"],
                left["memory"],
                left["address_words"],
                left["word_count"],
                left["coded"],
                left["payload_sha256"],
                right["coded"],
                right["payload_sha256"],
            )
            if identity in seen:
                continue
            seen.add(identity)
            if left["coded"] == 3 and right["coded"] == 2:
                raw_coded3_to_coded2.append((left["payload"], right["payload"]))
            elif left["coded"] == 2 and right["coded"] == 3:
                raw_coded3_to_coded2.append((right["payload"], left["payload"]))
            candidates.append(
                {
                    "left": public_record(left),
                    "right": public_record(right),
                    "relation": relation(left["payload"], right["payload"]),
                }
            )

    exact_payload_groups: list[dict[str, object]] = []
    by_hash: dict[str, list[dict[str, object]]] = defaultdict(list)
    for item in all_sections:
        by_hash[str(item["payload_sha256"])].append(item)
    for payload_hash, items in by_hash.items():
        if len(items) < 2:
            continue
        exact_payload_groups.append(
            {
                "payload_sha256": payload_hash,
                "payload_bytes": int(items[0]["word_count"]) * 2,
                "occurrences": len(items),
                "coded_values": sorted({int(item["coded"]) for item in items}),
                "locations": [public_record(item) for item in items],
            }
        )

    return {
        "format": "sct3258-coded-relation-analysis-v1",
        "inputs": [
            {"path": str(path.resolve()), "size": path.stat().st_size, "sha256": sha256(path.read_bytes())}
            for path in paths
        ],
        "summary": {
            "sections": len(all_sections),
            "different_coded_same_target_candidate_pairs": len(candidates),
            "exact_payload_duplicate_groups": len(exact_payload_groups),
        },
        "coded3_to_coded2_global_model_tests": global_coded3_to_coded2_models(raw_coded3_to_coded2),
        "different_coded_same_target_candidates": candidates,
        "exact_payload_duplicate_groups": exact_payload_groups,
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("hex_files", type=Path, nargs="+")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if args.output.exists():
        parser.error(f"refusing to overwrite existing output: {args.output}")
    try:
        report = analyze(args.hex_files)
    except ValueError as exc:
        parser.error(str(exc))
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2, ensure_ascii=True) + "\n", encoding="ascii")
    print(json.dumps(report["summary"], indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
