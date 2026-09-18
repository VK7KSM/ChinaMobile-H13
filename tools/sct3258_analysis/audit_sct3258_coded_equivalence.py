#!/usr/bin/env python3
"""Audit whether SCT3258 coded2/coded3 sections are valid plaintext pairs."""

from __future__ import annotations

import argparse
import hashlib
import itertools
import json
import sys
from collections import Counter, defaultdict
from pathlib import Path


sys.path.insert(0, str(Path(__file__).resolve().parent))
from analyze_sct3258_coded_relations import extract_sections, public_record  # noqa: E402


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def differing_runs(left: bytes, right: bytes) -> list[dict[str, int]]:
    indexes = [index for index, values in enumerate(zip(left, right)) if values[0] != values[1]]
    runs = []
    for _, group in itertools.groupby(enumerate(indexes), lambda item: item[1] - item[0]):
        members = [item[1] for item in group]
        runs.append({"start": members[0], "end_exclusive": members[-1] + 1})
    return runs


def legacy_candidates(sections: list[dict[str, object]]) -> list[dict[str, object]]:
    by_target: dict[tuple[object, ...], list[dict[str, object]]] = defaultdict(list)
    for section in sections:
        by_target[
            (
                section["version"],
                section["memory"],
                section["address_words"],
                section["word_count"],
            )
        ].append(section)

    candidates = []
    for items in by_target.values():
        coded2 = [item for item in items if item["coded"] == 2]
        coded3 = [item for item in items if item["coded"] == 3]
        for source in coded3:
            for target in coded2:
                candidates.append({"coded3": source, "coded2": target})
    return candidates


def role_counts(sections: list[dict[str, object]]) -> list[dict[str, object]]:
    counts = Counter(
        (section["role"], section["memory"], section["coded"])
        for section in sections
    )
    return [
        {"role": role, "memory": memory, "coded": coded, "sections": count}
        for (role, memory, coded), count in sorted(counts.items())
    ]


def strict_pair_count(
    sections: list[dict[str, object]], include: tuple[str, ...]
) -> int:
    groups: dict[tuple[object, ...], set[int]] = defaultdict(set)
    for section in sections:
        key = tuple(section[field] for field in include)
        groups[key].add(int(section["coded"]))
    return sum(2 in values and 3 in values for values in groups.values())


def many_to_one(candidates: list[dict[str, object]]) -> list[dict[str, object]]:
    groups: dict[tuple[object, ...], list[dict[str, object]]] = defaultdict(list)
    for candidate in candidates:
        target = candidate["coded2"]
        identity = (
            target["version"],
            target["run_start"],
            target["ordinal"],
            target["payload_sha256"],
        )
        groups[identity].append(candidate)

    results = []
    for items in groups.values():
        source_hashes = sorted({str(item["coded3"]["payload_sha256"]) for item in items})
        if len(source_hashes) < 2:
            continue
        target = items[0]["coded2"]
        results.append(
            {
                "coded2": public_record(target),
                "distinct_coded3_payloads": len(source_hashes),
                "coded3_roles": sorted({item["coded3"]["role"] for item in items}),
                "coded3_payload_sha256": source_hashes,
                "coded3_locations": [public_record(item["coded3"]) for item in items],
            }
        )
    return sorted(results, key=lambda item: -int(item["distinct_coded3_payloads"]))


def witness(candidates: list[dict[str, object]]) -> dict[str, object] | None:
    by_target: dict[tuple[object, ...], list[dict[str, object]]] = defaultdict(list)
    for candidate in candidates:
        target = candidate["coded2"]
        key = (
            target["version"],
            target["run_start"],
            target["ordinal"],
            target["payload_sha256"],
        )
        by_target[key].append(candidate)

    choices = []
    for items in by_target.values():
        for left, right in itertools.combinations(items, 2):
            left_source = left["coded3"]
            right_source = right["coded3"]
            if left_source["payload_sha256"] == right_source["payload_sha256"]:
                continue
            if left_source["role"] == right_source["role"]:
                continue
            differences = differing_runs(left_source["payload"], right_source["payload"])
            choices.append((sum(item["end_exclusive"] - item["start"] for item in differences), differences, left, right))
    if not choices:
        return None

    difference_count, differences, left, right = min(choices, key=lambda item: item[0])
    left_source = left["coded3"]
    right_source = right["coded3"]
    target = left["coded2"]
    return {
        "meaning": (
            "The legacy matcher assigns two distinct coded3 payloads from different roles "
            "to the exact same coded2 section. This falsifies use of legacy address/length "
            "matching as evidence that either candidate is the same plaintext."
        ),
        "coded3_left": public_record(left_source),
        "coded3_right": public_record(right_source),
        "coded3_differing_bytes": difference_count,
        "coded3_differing_runs": differences,
        "shared_coded2": public_record(target),
        "shared_coded2_is_byte_identical": left["coded2"]["payload"] == right["coded2"]["payload"],
    }


def analyze(paths: list[Path]) -> dict[str, object]:
    sections = [section for path in paths for section in extract_sections(path)]
    candidates = legacy_candidates(sections)
    cross_role = [
        candidate
        for candidate in candidates
        if candidate["coded3"]["role"] != candidate["coded2"]["role"]
    ]
    same_role = [
        candidate
        for candidate in candidates
        if candidate["coded3"]["role"] == candidate["coded2"]["role"]
    ]
    collisions = many_to_one(candidates)
    strict_fields = (
        "version",
        "role",
        "memory",
        "address_words",
        "word_count",
    )
    return {
        "format": "sct3258-coded-equivalence-audit-v1",
        "inputs": [
            {"path": str(path.resolve()), "size": path.stat().st_size, "sha256": sha256(path.read_bytes())}
            for path in paths
        ],
        "section_count": len(sections),
        "coded_counts_by_role": role_counts(sections),
        "legacy_matcher": {
            "key_fields": ["version", "memory", "address_words", "word_count"],
            "candidate_pairs": len(candidates),
            "same_role_pairs": len(same_role),
            "cross_role_pairs": len(cross_role),
            "distinct_payload_pairs": len(
                {
                    (
                        candidate["coded3"]["payload_sha256"],
                        candidate["coded2"]["payload_sha256"],
                    )
                    for candidate in candidates
                }
            ),
        },
        "role_preserving_matcher": {
            "key_fields": list(strict_fields),
            "targets_containing_both_coded2_and_coded3": strict_pair_count(
                sections, strict_fields
            ),
        },
        "run_preserving_matcher": {
            "key_fields": [
                "version",
                "run_start",
                "memory",
                "address_words",
                "word_count",
            ],
            "targets_containing_both_coded2_and_coded3": strict_pair_count(
                sections,
                ("version", "run_start", "memory", "address_words", "word_count"),
            ),
        },
        "many_distinct_coded3_to_one_exact_coded2": {
            "groups": len(collisions),
            "examples": collisions[:16],
        },
        "decisive_witness": witness(candidates),
        "bounded_conclusion": (
            "The public files contain no role-preserving coded3/coded2 pair. All legacy "
            "pairs cross independent application/vocoder roles, and exact coded2 sections "
            "are often matched to multiple distinct coded3 payloads. The legacy pairs may "
            "be used to study coincidental/repeated ciphertext structure, but not as known-"
            "equivalent plaintext encodings or as training data for a coded3-to-coded2 decoder."
        ),
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("hex_files", type=Path, nargs="+")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if args.output.exists():
        parser.error(f"refusing to overwrite existing output: {args.output}")
    report = analyze(args.hex_files)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2, ensure_ascii=True) + "\n", encoding="ascii")
    print(json.dumps({
        "section_count": report["section_count"],
        "legacy_matcher": report["legacy_matcher"],
        "role_preserving_matcher": report["role_preserving_matcher"],
        "run_preserving_matcher": report["run_preserving_matcher"],
        "many_to_one_groups": report["many_distinct_coded3_to_one_exact_coded2"]["groups"],
    }, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
