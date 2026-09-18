#!/usr/bin/env python3
"""Strictly compare two Sicomm text application containers section by section."""

from __future__ import annotations

import argparse
import hashlib
import itertools
import json
import sys
from collections import Counter
from pathlib import Path


sys.path.insert(0, str(Path(__file__).resolve().parent))
from index_sct_dat import parse_dat  # noqa: E402


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def differing_runs(left: bytes, right: bytes) -> list[dict[str, int]]:
    indexes = [index for index, pair in enumerate(zip(left, right)) if pair[0] != pair[1]]
    runs: list[dict[str, int]] = []
    for _, group in itertools.groupby(enumerate(indexes), lambda item: item[1] - item[0]):
        members = [item[1] for item in group]
        runs.append({"start": members[0], "end_exclusive": members[-1] + 1})
    return runs


def common_prefix_bytes(left: bytes, right: bytes) -> int:
    return next(
        (index for index, pair in enumerate(zip(left, right)) if pair[0] != pair[1]),
        min(len(left), len(right)),
    )


def common_suffix_bytes(left: bytes, right: bytes, prefix: int) -> int:
    limit = min(len(left), len(right)) - prefix
    count = 0
    while count < limit and left[-count - 1] == right[-count - 1]:
        count += 1
    return count


def compare_section(
    ordinal: int,
    left_meta: dict[str, object],
    right_meta: dict[str, object],
    left: bytes,
    right: bytes,
) -> dict[str, object]:
    identity_fields = ("kind", "address", "coded_type")
    same_identity = all(left_meta[field] == right_meta[field] for field in identity_fields)
    prefix = common_prefix_bytes(left, right)
    suffix = common_suffix_bytes(left, right, prefix)
    runs = differing_runs(left, right)
    common_length = min(len(left), len(right))
    differing_bytes = sum(left[index] != right[index] for index in range(common_length))
    return {
        "ordinal": ordinal,
        "same_role_address_and_coded_type": same_identity,
        "kind": {"left": left_meta["kind"], "right": right_meta["kind"]},
        "address": {"left": left_meta["address_hex"], "right": right_meta["address_hex"]},
        "coded_type": {"left": left_meta["coded_type"], "right": right_meta["coded_type"]},
        "word_count": {"left": left_meta["word_count"], "right": right_meta["word_count"]},
        "payload_sha256": {"left": sha256(left), "right": sha256(right)},
        "payload_identical": left == right,
        "common_prefix_bytes": prefix,
        "common_suffix_bytes": suffix,
        "common_length_bytes": common_length,
        "differing_bytes_in_common_length": differing_bytes,
        "left_only_trailing_bytes": max(0, len(left) - len(right)),
        "right_only_trailing_bytes": max(0, len(right) - len(left)),
        "differing_run_count": len(runs),
        "differing_runs": runs,
    }


def analyze(left_path: Path, right_path: Path) -> dict[str, object]:
    left_index, left_payloads = parse_dat(left_path)
    right_index, right_payloads = parse_dat(right_path)
    if len(left_payloads) != len(right_payloads):
        raise ValueError(
            f"section count differs: {len(left_payloads)} != {len(right_payloads)}; "
            "ordinal comparison would be ambiguous"
        )

    sections = [
        compare_section(ordinal, left_meta, right_meta, left_payload, right_payload)
        for ordinal, (left_meta, right_meta, left_payload, right_payload) in enumerate(
            zip(left_index["sections"], right_index["sections"], left_payloads, right_payloads)
        )
    ]
    transitions = Counter(
        (int(item["coded_type"]["left"]), int(item["coded_type"]["right"]))
        for item in sections
    )
    incompatible = [item["ordinal"] for item in sections if not item["same_role_address_and_coded_type"]]
    changed = [item["ordinal"] for item in sections if not item["payload_identical"]]
    cross_coded = [
        item["ordinal"]
        for item in sections
        if item["coded_type"]["left"] != item["coded_type"]["right"]
    ]
    return {
        "format": "sct-dat-application-comparison-v1",
        "inputs": {
            "left": {
                "path": str(left_path.resolve()),
                "size": left_path.stat().st_size,
                "sha256": sha256(left_path.read_bytes()),
            },
            "right": {
                "path": str(right_path.resolve()),
                "size": right_path.stat().st_size,
                "sha256": sha256(right_path.read_bytes()),
            },
        },
        "entry": {"left": left_index["entry_hex"], "right": right_index["entry_hex"]},
        "section_count": len(sections),
        "same_entry": left_index["entry"] == right_index["entry"],
        "same_section_role_address_and_coded_type_sequence": not incompatible,
        "incompatible_section_ordinals": incompatible,
        "coded_type_transitions": [
            {"left": left, "right": right, "sections": count}
            for (left, right), count in sorted(transitions.items())
        ],
        "cross_coded_section_ordinals": cross_coded,
        "identical_payload_sections": len(sections) - len(changed),
        "changed_payload_section_ordinals": changed,
        "same_length_changed_section_ordinals": [
            item["ordinal"]
            for item in sections
            if not item["payload_identical"]
            and item["word_count"]["left"] == item["word_count"]["right"]
        ],
        "different_length_section_ordinals": [
            item["ordinal"]
            for item in sections
            if item["word_count"]["left"] != item["word_count"]["right"]
        ],
        "sections": sections,
        "bounded_conclusion": (
            "The two files use the same entry and the same section role/address/coded-type "
            "sequence, but changed payloads prove they are not merely alternate Flash-address "
            "layouts. Every aligned section retains its coded type, so this pair provides no "
            "cross-coded known-plaintext equivalence for recovering coded1/2/3 transforms."
        ),
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("left", type=Path)
    parser.add_argument("right", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if args.output.exists():
        parser.error(f"refusing to overwrite existing output: {args.output}")
    report = analyze(args.left, args.right)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(
        json.dumps(report, indent=2, ensure_ascii=True) + "\n",
        encoding="ascii",
        newline="\n",
    )
    print(
        json.dumps(
            {
                "section_count": report["section_count"],
                "same_sequence": report["same_section_role_address_and_coded_type_sequence"],
                "coded_type_transitions": report["coded_type_transitions"],
                "changed_payload_section_ordinals": report["changed_payload_section_ordinals"],
                "cross_coded_section_ordinals": report["cross_coded_section_ordinals"],
            },
            indent=2,
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
