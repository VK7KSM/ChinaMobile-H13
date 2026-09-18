#!/usr/bin/env python3
"""Diagnose state patterns in legacy cross-role SCT3258 coded3/coded2 matches.

These matches are not known-equivalent plaintext pairs. Use
audit_sct3258_coded_equivalence.py before interpreting this report.
"""

from __future__ import annotations

import argparse
import hashlib
import itertools
import json
import sys
from collections import Counter, defaultdict
from pathlib import Path


sys.path.insert(0, str(Path(__file__).resolve().parent))
from analyze_sct3258_coded_relations import extract_sections  # noqa: E402


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def public(item: dict[str, object]) -> dict[str, object]:
    return {key: value for key, value in item.items() if key != "payload"}


def collect_pairs(paths: list[Path]) -> list[dict[str, object]]:
    sections = [section for path in paths for section in extract_sections(path)]
    by_target: dict[tuple[object, ...], list[dict[str, object]]] = defaultdict(list)
    for section in sections:
        key = (
            section["version"], section["memory"], section["address_words"],
            section["word_count"],
        )
        by_target[key].append(section)

    unique: dict[tuple[str, str], dict[str, object]] = {}
    for candidates in by_target.values():
        for left, right in itertools.combinations(candidates, 2):
            if {left["coded"], right["coded"]} != {2, 3}:
                continue
            coded3, coded2 = (left, right) if left["coded"] == 3 else (right, left)
            identity = (str(coded3["payload_sha256"]), str(coded2["payload_sha256"]))
            record = unique.setdefault(
                identity,
                {
                    "coded3": coded3["payload"],
                    "coded2": coded2["payload"],
                    "coded3_sha256": identity[0],
                    "coded2_sha256": identity[1],
                    "bytes": len(coded3["payload"]),
                    "locations": [],
                },
            )
            record["locations"].append({"coded3": public(coded3), "coded2": public(coded2)})
    return list(unique.values())


def pair_metadata(pair: dict[str, object]) -> dict[str, object]:
    locations = pair["locations"]
    return {
        "versions": sorted({location["coded3"]["version"] for location in locations}),
        "source_roles": sorted({location["coded3"]["role"] for location in locations}),
        "target_roles": sorted({location["coded2"]["role"] for location in locations}),
        "memories": sorted({location["coded3"]["memory"] for location in locations}),
        "addresses_words": sorted({location["coded3"]["address_words"] for location in locations}),
        "word_counts": sorted({location["coded3"]["word_count"] for location in locations}),
    }


def observation(pair_index: int, pair: dict[str, object], offset: int) -> dict[str, object]:
    start = max(0, offset - 8)
    end = min(int(pair["bytes"]), offset + 9)
    return {
        "pair_index": pair_index,
        "offset": offset,
        "context_start": start,
        "coded3_context_hex": pair["coded3"][start:end].hex(" "),
        "coded2_context_hex": pair["coded2"][start:end].hex(" "),
        "metadata": pair_metadata(pair),
    }


def common_prefix(left: bytes, right: bytes) -> int:
    for index, (a, b) in enumerate(zip(left, right)):
        if a != b:
            return index
    return min(len(left), len(right))


def common_suffix(left: bytes, right: bytes) -> int:
    return common_prefix(left[::-1], right[::-1])


def positional_xor(pairs: list[dict[str, object]]) -> dict[str, object]:
    maximum = max(int(pair["bytes"]) for pair in pairs)
    positions = []
    for offset in range(maximum):
        values = {
            pair["coded3"][offset] ^ pair["coded2"][offset]
            for pair in pairs if int(pair["bytes"]) > offset
        }
        observations = sum(int(pair["bytes"]) > offset for pair in pairs)
        if observations >= 2:
            positions.append(
                {"offset": offset, "observations": observations, "distinct_xor_values": len(values)}
            )
    return {
        "positions_with_multiple_observations": len(positions),
        "fixed_xor_positions": sum(item["distinct_xor_values"] == 1 for item in positions),
        "conflicting_xor_positions": sum(item["distinct_xor_values"] > 1 for item in positions),
        "first_64": positions[:64],
    }


def mapping_test(
    pairs: list[dict[str, object]], block_size: int, position_period: int
) -> dict[str, int]:
    mapping: dict[tuple[int, bytes], bytes] = {}
    repeated = conflicts = observations = 0
    for pair in pairs:
        coded3 = pair["coded3"]
        coded2 = pair["coded2"]
        for offset in range(0, len(coded3) - block_size + 1, block_size):
            source = coded3[offset : offset + block_size]
            target = coded2[offset : offset + block_size]
            key = (offset % position_period, source)
            observations += 1
            if key in mapping:
                repeated += 1
                conflicts += mapping[key] != target
            else:
                mapping[key] = target
    return {
        "block_size": block_size,
        "position_period": position_period,
        "observations": observations,
        "distinct_keys": len(mapping),
        "repeated_key_observations": repeated,
        "conflicts": conflicts,
    }


def feedback_test(pairs: list[dict[str, object]], history: int) -> dict[str, object]:
    mapping: dict[bytes, tuple[int, int, int]] = {}
    observations = repeated = conflicts = 0
    conflict_details = []
    for pair_index, pair in enumerate(pairs):
        coded3 = pair["coded3"]
        coded2 = pair["coded2"]
        for offset in range(history, len(coded3)):
            key = (
                coded3[offset - history : offset + 1]
                + coded2[offset - history : offset]
            )
            target = coded2[offset]
            observations += 1
            if key in mapping:
                repeated += 1
                prior_target, prior_pair_index, prior_offset = mapping[key]
                if prior_target != target:
                    conflicts += 1
                    if len(conflict_details) < 64:
                        conflict_details.append(
                            {
                                "key_hex": key.hex(" "),
                                "prior_target": prior_target,
                                "current_target": target,
                                "prior": observation(
                                    prior_pair_index, pairs[prior_pair_index], prior_offset
                                ),
                                "current": observation(pair_index, pair, offset),
                            }
                        )
            else:
                mapping[key] = (target, pair_index, offset)
    return {
        "history_bytes_each_side": history,
        "observations": observations,
        "distinct_keys": len(mapping),
        "repeated_key_observations": repeated,
        "conflicts": conflicts,
        "conflict_details": conflict_details,
    }


def word_feedback_test(
    pairs: list[dict[str, object]], history_words: int, byteorder: str
) -> dict[str, object]:
    mapping: dict[tuple[int, ...], tuple[int, int, int]] = {}
    observations = repeated = conflicts = 0
    conflict_details = []
    for pair_index, pair in enumerate(pairs):
        coded3 = [
            int.from_bytes(pair["coded3"][offset : offset + 2], byteorder)
            for offset in range(0, int(pair["bytes"]), 2)
        ]
        coded2 = [
            int.from_bytes(pair["coded2"][offset : offset + 2], byteorder)
            for offset in range(0, int(pair["bytes"]), 2)
        ]
        for word_offset in range(history_words, len(coded3)):
            key = tuple(
                coded3[word_offset - history_words : word_offset + 1]
                + coded2[word_offset - history_words : word_offset]
            )
            target = coded2[word_offset]
            observations += 1
            if key in mapping:
                repeated += 1
                prior_target, prior_pair_index, prior_word_offset = mapping[key]
                if prior_target != target:
                    conflicts += 1
                    if len(conflict_details) < 64:
                        conflict_details.append(
                            {
                                "key_hex_words": [f"{word:04x}" for word in key],
                                "prior_target_hex": f"{prior_target:04x}",
                                "current_target_hex": f"{target:04x}",
                                "prior": observation(
                                    prior_pair_index,
                                    pairs[prior_pair_index],
                                    prior_word_offset * 2,
                                ),
                                "current": observation(pair_index, pair, word_offset * 2),
                            }
                        )
            else:
                mapping[key] = (target, pair_index, word_offset)
    return {
        "history_words_each_side": history_words,
        "byteorder": byteorder,
        "observations": observations,
        "distinct_keys": len(mapping),
        "repeated_key_observations": repeated,
        "conflicts": conflicts,
        "conflict_details": conflict_details,
    }


def transition_counts(
    pairs: list[dict[str, object]], pair_indexes: set[int], history: int
) -> dict[bytes, Counter[int]]:
    counts: dict[bytes, Counter[int]] = defaultdict(Counter)
    for pair_index in pair_indexes:
        pair = pairs[pair_index]
        coded3 = pair["coded3"]
        coded2 = pair["coded2"]
        for offset in range(history, len(coded3)):
            key = (
                coded3[offset - history : offset + 1]
                + coded2[offset - history : offset]
            )
            counts[key][coded2[offset]] += 1
    return counts


def evaluate_holdout(
    pairs: list[dict[str, object]], train_indexes: set[int], test_indexes: set[int], history: int
) -> dict[str, object]:
    counts = transition_counts(pairs, train_indexes, history)
    deterministic = {key: next(iter(values)) for key, values in counts.items() if len(values) == 1}
    total = seen = correct = mismatches = ambiguous = 0
    per_pair = []
    for pair_index in sorted(test_indexes):
        pair = pairs[pair_index]
        pair_total = pair_seen = pair_correct = pair_mismatches = pair_ambiguous = 0
        for offset in range(history, int(pair["bytes"])):
            key = (
                pair["coded3"][offset - history : offset + 1]
                + pair["coded2"][offset - history : offset]
            )
            pair_total += 1
            if key not in counts:
                continue
            pair_seen += 1
            if key not in deterministic:
                pair_ambiguous += 1
            elif deterministic[key] == pair["coded2"][offset]:
                pair_correct += 1
            else:
                pair_mismatches += 1
        total += pair_total
        seen += pair_seen
        correct += pair_correct
        mismatches += pair_mismatches
        ambiguous += pair_ambiguous
        per_pair.append(
            {
                "pair_index": pair_index,
                "observations": pair_total,
                "seen_keys": pair_seen,
                "deterministic_correct": pair_correct,
                "deterministic_mismatches": pair_mismatches,
                "ambiguous_train_keys": pair_ambiguous,
                "metadata": pair_metadata(pair),
            }
        )
    return {
        "train_pairs": len(train_indexes),
        "test_pairs": len(test_indexes),
        "train_distinct_keys": len(counts),
        "train_deterministic_keys": len(deterministic),
        "test_observations": total,
        "seen_keys": seen,
        "coverage_fraction": seen / total if total else None,
        "deterministic_correct": correct,
        "deterministic_mismatches": mismatches,
        "ambiguous_train_keys": ambiguous,
        "accuracy_on_deterministic_seen": (
            correct / (correct + mismatches) if correct + mismatches else None
        ),
        "per_pair": per_pair,
    }


def holdout_tests(pairs: list[dict[str, object]], history: int) -> dict[str, object]:
    all_indexes = set(range(len(pairs)))
    leave_one_pair = []
    for pair_index in range(len(pairs)):
        leave_one_pair.append(
            evaluate_holdout(pairs, all_indexes - {pair_index}, {pair_index}, history)
        )

    versions = sorted(
        {version for pair in pairs for version in pair_metadata(pair)["versions"]}
    )
    leave_one_version = []
    for version in versions:
        test = {
            pair_index
            for pair_index, pair in enumerate(pairs)
            if version in pair_metadata(pair)["versions"]
        }
        # Exclude shared cross-version payload pairs from training to prevent leakage.
        train = all_indexes - test
        result = evaluate_holdout(pairs, train, test, history)
        result["held_out_version"] = version
        leave_one_version.append(result)

    aggregate_total = sum(item["test_observations"] for item in leave_one_pair)
    aggregate_seen = sum(item["seen_keys"] for item in leave_one_pair)
    aggregate_correct = sum(item["deterministic_correct"] for item in leave_one_pair)
    aggregate_mismatches = sum(item["deterministic_mismatches"] for item in leave_one_pair)
    aggregate_ambiguous = sum(item["ambiguous_train_keys"] for item in leave_one_pair)
    return {
        "history_bytes_each_side": history,
        "leave_one_pair_out_aggregate": {
            "test_observations": aggregate_total,
            "seen_keys": aggregate_seen,
            "coverage_fraction": aggregate_seen / aggregate_total,
            "deterministic_correct": aggregate_correct,
            "deterministic_mismatches": aggregate_mismatches,
            "ambiguous_train_keys": aggregate_ambiguous,
            "accuracy_on_deterministic_seen": (
                aggregate_correct / (aggregate_correct + aggregate_mismatches)
                if aggregate_correct + aggregate_mismatches
                else None
            ),
        },
        "leave_one_pair_out": leave_one_pair,
        "leave_one_version_out": leave_one_version,
    }


def grouped_feedback_tests(pairs: list[dict[str, object]], history: int) -> list[dict[str, object]]:
    groups: dict[str, list[dict[str, object]]] = defaultdict(list)
    for pair in pairs:
        metadata = pair_metadata(pair)
        for memory in metadata["memories"]:
            groups[f"memory:{memory}"].append(pair)
        for source_role in metadata["source_roles"]:
            groups[f"source_role:{source_role}"].append(pair)
        for target_role in metadata["target_roles"]:
            groups[f"target_role:{target_role}"].append(pair)
    results = []
    for group, members in sorted(groups.items()):
        result = feedback_test(members, history)
        result["group"] = group
        result["pairs"] = len(members)
        results.append(result)
    return results


def pairwise_prefixes(pairs: list[dict[str, object]]) -> dict[str, object]:
    comparisons = []
    for left_index, right_index in itertools.combinations(range(len(pairs)), 2):
        left = pairs[left_index]
        right = pairs[right_index]
        input_prefix = common_prefix(left["coded3"], right["coded3"])
        output_prefix = common_prefix(left["coded2"], right["coded2"])
        input_suffix = common_suffix(left["coded3"], right["coded3"])
        output_suffix = common_suffix(left["coded2"], right["coded2"])
        if max(input_prefix, output_prefix, input_suffix, output_suffix) == 0:
            continue
        comparisons.append(
            {
                "left_pair": left_index,
                "right_pair": right_index,
                "coded3_common_prefix": input_prefix,
                "coded2_common_prefix": output_prefix,
                "coded3_common_suffix": input_suffix,
                "coded2_common_suffix": output_suffix,
            }
        )
    by_input_prefix = sorted(
        comparisons,
        key=lambda item: (-int(item["coded3_common_prefix"]), int(item["coded2_common_prefix"])),
    )
    return {
        "comparisons_with_any_common_edge": len(comparisons),
        "shared_coded3_prefix_but_earlier_coded2_divergence": sum(
            int(item["coded3_common_prefix"]) > int(item["coded2_common_prefix"])
            for item in comparisons
        ),
        "top_32_by_coded3_prefix": by_input_prefix[:32],
    }


def analyze(paths: list[Path]) -> dict[str, object]:
    pairs = collect_pairs(paths)
    return {
        "format": "sct3258-coded-state-tests-v2",
        "warning": (
            "All current matches cross independent application/vocoder roles. The public "
            "files contain zero role-preserving coded3/coded2 pairs, so these statistics "
            "describe repeated/aligned ciphertext only and are not decoder evidence."
        ),
        "inputs": [
            {"path": str(path.resolve()), "size": path.stat().st_size, "sha256": sha256(path.read_bytes())}
            for path in paths
        ],
        "unique_coded3_to_coded2_pairs": len(pairs),
        "pair_lengths_bytes": sorted({int(pair["bytes"]) for pair in pairs}),
        "positional_xor": positional_xor(pairs),
        "aligned_block_mappings": [
            mapping_test(pairs, block_size, period)
            for block_size in (1, 2, 4, 8)
            for period in (1, 2, 4, 8, 16, 32)
        ],
        "short_feedback_mappings": [feedback_test(pairs, history) for history in range(5)],
        "word_feedback_mappings": [
            word_feedback_test(pairs, history, byteorder)
            for byteorder in ("little", "big")
            for history in range(4)
        ],
        "grouped_history_2_feedback": grouped_feedback_tests(pairs, 2),
        "history_2_holdout_tests": holdout_tests(pairs, 2),
        "pairwise_edge_comparisons": pairwise_prefixes(pairs),
        "pairs": [
            {
                "pair_index": pair_index,
                **{key: value for key, value in pair.items() if key not in {"coded3", "coded2"}},
                "metadata": pair_metadata(pair),
                "coded3_prefix_hex": pair["coded3"][:16].hex(" "),
                "coded2_prefix_hex": pair["coded2"][:16].hex(" "),
                "coded3_suffix_hex": pair["coded3"][-16:].hex(" "),
                "coded2_suffix_hex": pair["coded2"][-16:].hex(" "),
            }
            for pair_index, pair in enumerate(pairs)
        ],
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("hex_files", type=Path, nargs="+")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument(
        "--allow-cross-role-diagnostic",
        action="store_true",
        help="acknowledge that inputs are not known-equivalent plaintext pairs",
    )
    args = parser.parse_args()
    if not args.allow_cross_role_diagnostic:
        parser.error(
            "refusing decoder-style analysis of cross-role candidates; run "
            "audit_sct3258_coded_equivalence.py first, then pass "
            "--allow-cross-role-diagnostic only for ciphertext-structure diagnostics"
        )
    if args.output.exists():
        parser.error(f"refusing to overwrite existing output: {args.output}")
    report = analyze(args.hex_files)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(
        json.dumps(report, indent=2, ensure_ascii=True) + "\n", encoding="ascii"
    )
    print(json.dumps({
        "warning": report["warning"],
        "unique_pairs": report["unique_coded3_to_coded2_pairs"],
        "positional_xor_summary": {
            key: value
            for key, value in report["positional_xor"].items()
            if key != "first_64"
        },
        "short_feedback_summary": [
            {key: value for key, value in item.items() if key != "conflict_details"}
            for item in report["short_feedback_mappings"]
        ],
    }, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
