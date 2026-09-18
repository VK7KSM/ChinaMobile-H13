#!/usr/bin/env python3
"""Inventory SCT HEX/DAT artifacts and audit cross-coded equivalence evidence."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import sys
from collections import Counter, defaultdict
from pathlib import Path


sys.path.insert(0, str(Path(__file__).resolve().parent))
from index_sct3258_download_stream import index_file  # noqa: E402
from index_sct_batch_dat import decode_container, split_batches  # noqa: E402
from index_sct_dat import parse_dat  # noqa: E402


EXCLUDED_DIRECTORIES = {"asm_runtime_v038", "capstone_runtime", "pydeps"}
SCT3258_ROLES = {
    0x000100: "user_application",
    0x018000: "vocoder_index_04",
    0x02C000: "vocoder_index_12",
    0x040000: "vocoder_index_16",
    0x051000: "vocoder_index_23",
    0x068000: "modem2_application",
}
SCT3288_CUSTOM_ROLES = {
    0x000100: "modem_flash_1",
    0x022000: "vocoder1_flash_4",
    0x040000: "vocoder2_flash_16",
    0x053000: "vocoder6_flash_23",
}
RECONSTRUCTED_ROLE_RE = re.compile(r"^[0-9a-fA-F]{6}_(.+)\.dat$")


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def discover(root: Path) -> list[Path]:
    found: list[Path] = []
    for directory, names, files in os.walk(root, topdown=True):
        names[:] = [name for name in names if name not in EXCLUDED_DIRECTORIES]
        for name in files:
            if Path(name).suffix.lower() in {".hex", ".dat"}:
                found.append(Path(directory) / name)
    return sorted(found, key=lambda path: str(path).lower())


def generation(path: Path) -> str:
    lowered = str(path).lower()
    if "sct3258" in lowered:
        return "sct3258"
    if "sct3288" in lowered:
        return "sct3288"
    return "unknown"


def normalize_role(text: str) -> str:
    return re.sub(r"[^a-z0-9]+", "_", text.lower()).strip("_")


def hex_role(chip: str, path: Path, run_start: int) -> str:
    if chip == "sct3258":
        return SCT3258_ROLES.get(run_start, f"run_0x{run_start:06x}")
    if chip == "sct3288" and path.stem.lower().startswith("customflash"):
        return SCT3288_CUSTOM_ROLES.get(run_start, f"run_0x{run_start:06x}")
    return f"run_0x{run_start:06x}"


def section_record(
    *,
    chip: str,
    role: str,
    source: Path,
    carrier: str,
    ordinal: int,
    memory: str,
    address: int,
    words: int,
    coded: int,
    payload_hash: str,
) -> dict[str, object]:
    return {
        "chip_generation": chip,
        "application_role": role,
        "source": str(source.resolve()),
        "carrier": carrier,
        "ordinal": ordinal,
        "memory": memory,
        "address_words": address,
        "address_hex": f"0x{address:05x}",
        "word_count": words,
        "coded_type": coded,
        "payload_sha256": payload_hash,
    }


def parse_hex(path: Path, chip: str) -> tuple[list[dict[str, object]], dict[str, object]]:
    indexed = index_file(path)
    records: list[dict[str, object]] = []
    incomplete = []
    for run in indexed["runs"]:
        start = int(run["start"])
        role = hex_role(chip, path, start)
        if not run["fully_parsed"]:
            incomplete.append({"run_start": start, "stop": run["stop"]})
        ordinal = 0
        for command in run["commands"]:
            if command["kind"] != "load_section":
                continue
            records.append(
                section_record(
                    chip=chip,
                    role=role,
                    source=path,
                    carrier="intel_hex_download_stream",
                    ordinal=ordinal,
                    memory=str(command["memory"]),
                    address=int(command["sct_address_words"]),
                    words=int(command["word_count"]),
                    coded=int(command["coded"]),
                    payload_hash=str(command["payload_sha256"]),
                )
            )
            ordinal += 1
    return records, {"classification": "intel_hex_download_stream", "incomplete_runs": incomplete}


def dat_role(path: Path) -> str:
    match = RECONSTRUCTED_ROLE_RE.fullmatch(path.name)
    if match:
        return normalize_role(match.group(1))
    if path.name.lower().startswith("plat4m_flash3"):
        return "exec_application"
    return normalize_role(path.stem)


def parse_text_dat(path: Path, chip: str) -> tuple[list[dict[str, object]], dict[str, object]]:
    indexed, _ = parse_dat(path, require_entry=False)
    role = dat_role(path)
    records = [
        section_record(
            chip=chip,
            role=role,
            source=path,
            carrier="text_section_dat",
            ordinal=ordinal,
            memory="instruction" if section["kind"] == "i" else "data",
            address=int(section["address"]),
            words=int(section["word_count"]),
            coded=int(section["coded_type"]),
            payload_hash=str(section["payload_sha256"]),
        )
        for ordinal, section in enumerate(indexed["sections"])
    ]
    return records, {
        "classification": "text_section_dat",
        "entry": indexed["entry_hex"],
        "role": role,
    }


def parse_batch(path: Path, chip: str, raw: bytes) -> tuple[list[dict[str, object]], dict[str, object]]:
    batches = split_batches(decode_container(raw))
    records: list[dict[str, object]] = []
    for batch in batches:
        role = normalize_role(str(batch["heading"]))
        for ordinal, section in enumerate(batch["sections"]):
            records.append(
                section_record(
                    chip=chip,
                    role=role,
                    source=path,
                    carrier="abc_xor_batch_dat",
                    ordinal=ordinal,
                    memory="instruction" if section["kind"] == "i" else "data",
                    address=int(section["address"]),
                    words=int(section["word_count"]),
                    coded=int(section["coded_type"]),
                    payload_hash=str(section["payload_sha256"]),
                )
            )
    return records, {
        "classification": "abc_xor_batch_dat",
        "batch_roles": [normalize_role(str(batch["heading"])) for batch in batches],
    }


def parse_artifact(path: Path) -> tuple[list[dict[str, object]], dict[str, object]]:
    raw = path.read_bytes()
    chip = generation(path)
    if path.suffix.lower() == ".hex" and raw.lstrip().startswith(b":"):
        return parse_hex(path, chip)
    if path.suffix.lower() == ".dat" and raw.startswith(b"abc"):
        return parse_batch(path, chip, raw)
    if path.suffix.lower() == ".dat" and raw.lstrip().startswith((b"E ", b"i ", b"d ")):
        return parse_text_dat(path, chip)
    return [], {"classification": "non_section_binary_or_data_dat"}


def summarize_group(key: tuple[object, ...], items: list[dict[str, object]]) -> dict[str, object]:
    chip, role, memory, address, words = key
    payloads_by_coded: dict[int, set[str]] = defaultdict(set)
    sources_by_coded: dict[int, set[str]] = defaultdict(set)
    for item in items:
        coded = int(item["coded_type"])
        payloads_by_coded[coded].add(str(item["payload_sha256"]))
        sources_by_coded[coded].add(str(item["source"]))
    return {
        "chip_generation": chip,
        "application_role": role,
        "memory": memory,
        "address_words": address,
        "address_hex": f"0x{int(address):05x}",
        "word_count": words,
        "coded_types": sorted(payloads_by_coded),
        "distinct_payloads_by_coded_type": {
            str(coded): len(payloads) for coded, payloads in sorted(payloads_by_coded.items())
        },
        "sources_by_coded_type": {
            str(coded): sorted(sources) for coded, sources in sorted(sources_by_coded.items())
        },
    }


def analyze(root: Path) -> dict[str, object]:
    paths = discover(root)
    by_raw_hash: dict[str, list[Path]] = defaultdict(list)
    raw_by_path: dict[Path, bytes] = {}
    for path in paths:
        raw = path.read_bytes()
        raw_by_path[path] = raw
        by_raw_hash[sha256(raw)].append(path)

    canonical_paths = [sorted(group, key=lambda path: str(path).lower())[0] for group in by_raw_hash.values()]
    artifacts: list[dict[str, object]] = []
    sections: list[dict[str, object]] = []
    for path in sorted(canonical_paths, key=lambda item: str(item).lower()):
        artifact = {
            "path": str(path.resolve()),
            "size": len(raw_by_path[path]),
            "sha256": sha256(raw_by_path[path]),
            "chip_generation": generation(path),
            "duplicate_paths": [
                str(item.resolve()) for item in sorted(by_raw_hash[sha256(raw_by_path[path])], key=lambda value: str(value).lower())
                if item != path
            ],
        }
        try:
            records, details = parse_artifact(path)
            artifact.update(details)
            artifact["section_count"] = len(records)
            sections.extend(records)
        except (UnicodeDecodeError, ValueError) as error:
            artifact.update({"classification": "parse_error", "error": str(error), "section_count": 0})
        artifacts.append(artifact)

    occurrence_identity = {
        (
            item["chip_generation"],
            item["application_role"],
            item["memory"],
            item["address_words"],
            item["word_count"],
            item["coded_type"],
            item["payload_sha256"],
        )
        for item in sections
    }
    groups: dict[tuple[object, ...], list[dict[str, object]]] = defaultdict(list)
    for item in sections:
        key = (
            item["chip_generation"],
            item["application_role"],
            item["memory"],
            item["address_words"],
            item["word_count"],
        )
        groups[key].append(item)
    mixed = [summarize_group(key, items) for key, items in groups.items() if len({item["coded_type"] for item in items}) > 1]
    mixed.sort(key=lambda item: (str(item["chip_generation"]), str(item["application_role"]), str(item["memory"]), int(item["address_words"]), int(item["word_count"])))

    classification_counts = Counter(str(item["classification"]) for item in artifacts)
    return {
        "format": "sct-global-coded-equivalence-audit-v2",
        "root": str(root.resolve()),
        "discovered_hex_dat_files": len(paths),
        "unique_raw_artifacts": len(canonical_paths),
        "duplicate_raw_files": len(paths) - len(canonical_paths),
        "artifact_classification_counts": dict(sorted(classification_counts.items())),
        "section_occurrences_in_canonical_artifacts": len(sections),
        "distinct_role_scoped_section_payloads": len(occurrence_identity),
        "metadata_groups": len(groups),
        "mixed_coded_metadata_groups": len(mixed),
        "mixed_coded_metadata_group_details": mixed,
        "verified_same_plaintext_cross_coded_pairs": [],
        "verified_same_plaintext_cross_coded_pair_count": 0,
        "artifacts": artifacts,
        "bounded_conclusion": (
            "This inventory found no provenance-backed instance of one logical plaintext section "
            "encoded under two coded types. Mixed coded-type metadata groups, if any, are only "
            "same-role/address/length candidates and are not equivalence evidence without a build "
            "manifest, uncoded source, or another explicit same-plaintext link. Raw-file duplicates "
            "and exact section payload duplicates across HEX, reconstructed DAT, and batch carriers "
            "are deduplicated diagnostically and do not create cross-coded pairs."
        ),
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("root", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if args.output.exists():
        parser.error(f"refusing to overwrite existing output: {args.output}")
    report = analyze(args.root)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(
        json.dumps(report, indent=2, ensure_ascii=True) + "\n",
        encoding="ascii",
        newline="\n",
    )
    print(
        json.dumps(
            {key: report[key] for key in (
                "discovered_hex_dat_files",
                "unique_raw_artifacts",
                "duplicate_raw_files",
                "artifact_classification_counts",
                "section_occurrences_in_canonical_artifacts",
                "distinct_role_scoped_section_payloads",
                "metadata_groups",
                "mixed_coded_metadata_groups",
                "verified_same_plaintext_cross_coded_pair_count",
            )},
            indent=2,
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
