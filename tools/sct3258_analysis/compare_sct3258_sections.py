#!/usr/bin/env python3
"""Compare parsed SCT3258 download sections across updater versions and roles."""

from __future__ import annotations

import argparse
import json
import re
from collections import defaultdict
from pathlib import Path

from index_sct3258_download_stream import index_file


VERSION_RE = re.compile(r"v?2_01_([^_.]+)", re.IGNORECASE)
ROLE_BY_START = {
    0x000100: "user_application",
    0x018000: "vocoder_index_04",
    0x02C000: "vocoder_index_12",
    0x040000: "vocoder_index_16",
    0x051000: "vocoder_index_23",
    0x068000: "modem2_application",
}


def version_name(path: Path) -> str:
    match = VERSION_RE.search(path.stem)
    return match.group(1).upper() if match else path.stem


def sections(run: dict[str, object]) -> list[dict[str, object]]:
    return [item for item in run["commands"] if item["kind"] == "load_section"]


def terminal(run: dict[str, object]) -> dict[str, object]:
    items = [item for item in run["commands"] if item["kind"] != "load_section"]
    if len(items) != 1:
        raise ValueError(f'run 0x{int(run["start"]):x} has {len(items)} terminals')
    return items[0]


def metadata(section: dict[str, object]) -> tuple[object, ...]:
    return (
        section["memory"],
        int(section["coded"]),
        int(section["sct_address_words"]),
        int(section["word_count"]),
    )


def metadata_json(section: dict[str, object]) -> dict[str, object]:
    return {
        "memory": section["memory"],
        "coded": section["coded"],
        "address_words": section["sct_address_words"],
        "word_count": section["word_count"],
        "payload_bytes": section["payload_bytes"],
    }


def compare(hex_files: list[Path]) -> dict[str, object]:
    versions: dict[str, dict[str, object]] = {}
    for path in hex_files:
        name = version_name(path)
        if name in versions:
            raise ValueError(f"duplicate version label {name}")
        indexed = index_file(path)
        if indexed["summary"]["fully_parsed_runs"] != indexed["summary"]["runs"]:
            raise ValueError(f"not all runs parsed for {path}")
        versions[name] = indexed

    run_starts = sorted(
        {int(run["start"]) for version in versions.values() for run in version["runs"]}
    )
    run_lineage: list[dict[str, object]] = []
    for start in run_starts:
        by_version = {
            name: next((run for run in data["runs"] if int(run["start"]) == start), None)
            for name, data in versions.items()
        }
        present = {name: run for name, run in by_version.items() if run is not None}
        stable_positions: list[dict[str, object]] = []
        changed_positions: list[dict[str, object]] = []
        max_sections = max((len(sections(run)) for run in present.values()), default=0)
        for ordinal in range(max_sections):
            items = {
                name: sections(run)[ordinal]
                for name, run in present.items()
                if ordinal < len(sections(run))
            }
            metadata_same = len(items) == len(versions) and len(
                {metadata(item) for item in items.values()}
            ) == 1
            hashes = {name: str(item["payload_sha256"]) for name, item in items.items()}
            payload_same = metadata_same and len(set(hashes.values())) == 1
            record = {
                "ordinal": ordinal,
                "present_versions": sorted(items),
                "metadata_same_in_all_versions": metadata_same,
                "payload_same_in_all_versions": payload_same,
                "metadata_by_version": {
                    name: metadata_json(item) for name, item in items.items()
                },
                "payload_sha256_by_version": hashes,
            }
            (stable_positions if payload_same else changed_positions).append(record)
        stable_payload = sum(
            int(next(iter(item["metadata_by_version"].values()))["payload_bytes"])
            for item in stable_positions
        )
        terminals = {
            name: {
                "control": terminal(run)["control"],
                "kind": terminal(run)["kind"],
                "argument": terminal(run)["argument"],
            }
            for name, run in present.items()
        }
        run_lineage.append(
            {
                "run_start": start,
                "role": ROLE_BY_START.get(start, f"run_0x{start:06x}"),
                "present_versions": sorted(present),
                "section_count_by_version": {
                    name: len(sections(run)) for name, run in present.items()
                },
                "run_length_by_version": {
                    name: run["length"] for name, run in present.items()
                },
                "terminal_by_version": terminals,
                "stable_section_positions_in_all_versions": len(stable_positions),
                "stable_section_payload_bytes_in_all_versions": stable_payload,
                "stable_positions": stable_positions,
                "changed_positions": changed_positions,
            }
        )

    cross_role: dict[str, object] = {}
    for name, data in versions.items():
        by_start = {int(run["start"]): run for run in data["runs"]}
        if 0x000100 not in by_start or 0x068000 not in by_start:
            continue
        user = sections(by_start[0x000100])
        modem2 = sections(by_start[0x068000])
        modem_lookup: dict[tuple[object, ...], list[tuple[int, dict[str, object]]]] = defaultdict(list)
        for ordinal, item in enumerate(modem2):
            modem_lookup[(*metadata(item), item["payload_sha256"])].append((ordinal, item))
        matches: list[dict[str, object]] = []
        for user_ordinal, item in enumerate(user):
            key = (*metadata(item), item["payload_sha256"])
            candidates = modem_lookup.get(key)
            if not candidates:
                continue
            modem_ordinal, _ = candidates.pop(0)
            matches.append(
                {
                    "user_ordinal": user_ordinal,
                    "modem2_ordinal": modem_ordinal,
                    **metadata_json(item),
                    "payload_sha256": item["payload_sha256"],
                }
            )
        cross_role[name] = {
            "exact_section_matches": len(matches),
            "exact_payload_bytes": sum(int(item["payload_bytes"]) for item in matches),
            "matches": matches,
        }

    return {
        "format": "sct3258-section-lineage-v1",
        "versions": {
            name: {
                "path": data["path"],
                "sha256": data["sha256"],
                "summary": data["summary"],
            }
            for name, data in versions.items()
        },
        "run_lineage": run_lineage,
        "user_vs_modem2_exact_sections_by_version": cross_role,
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("hex_files", type=Path, nargs="+")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if args.output.exists():
        parser.error(f"refusing to overwrite existing output: {args.output}")
    try:
        report = compare(args.hex_files)
    except ValueError as exc:
        parser.error(str(exc))
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2, ensure_ascii=True) + "\n", encoding="ascii")
    summary = [
        {
            "role": item["role"],
            "present_versions": item["present_versions"],
            "section_count_by_version": item["section_count_by_version"],
            "stable_sections": item["stable_section_positions_in_all_versions"],
            "stable_payload_bytes": item["stable_section_payload_bytes_in_all_versions"],
        }
        for item in report["run_lineage"]
    ]
    print(json.dumps({"run_summary": summary, "cross_role": report["user_vs_modem2_exact_sections_by_version"]}, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
