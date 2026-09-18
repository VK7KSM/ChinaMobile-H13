#!/usr/bin/env python3
"""Index source-proven SCT3258 download commands embedded in updater HEX files."""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path

from analyze_sct3258_hex import Segment, parse_sct_hex


LOAD_COMMANDS = {
    bytes.fromhex("80a8"): ("instruction", 0),
    bytes.fromhex("88a8"): ("instruction", 1),
    bytes.fromhex("8aa8"): ("instruction", 2),
    bytes.fromhex("8ca8"): ("instruction", 3),
    bytes.fromhex("81a7"): ("data", 0),
    bytes.fromhex("89a7"): ("data", 1),
    bytes.fromhex("8ba7"): ("data", 2),
    bytes.fromhex("8da7"): ("data", 3),
    # SCT3288 Section.isNewCode selects its 0x90000..0x9ffff instruction
    # bank by changing A8 to B8 while retaining the low 16 address bits.
    bytes.fromhex("88b8"): ("instruction", 1),
    bytes.fromhex("8ab8"): ("instruction", 2),
    bytes.fromhex("8cb8"): ("instruction", 3),
}

LOAD_COMMAND_ADDRESS_BASES = {
    bytes.fromhex("88b8"): 0x90000,
    bytes.fromhex("8ab8"): 0x90000,
    bytes.fromhex("8cb8"): 0x90000,
}

FOUR_BYTE_COMMANDS = {
    bytes.fromhex("82a3"): "execute_or_flash_start",
    bytes.fromhex("85ab"): "flash_tail",
}


def merge_contiguous(segments: list[Segment]) -> list[Segment]:
    runs: list[Segment] = []
    for segment in sorted(segments, key=lambda item: item.start):
        if runs and runs[-1].end == segment.start:
            previous = runs.pop()
            runs.append(Segment(previous.selector, previous.start, previous.data + segment.data))
        else:
            runs.append(segment)
    return runs


def index_run(run: Segment) -> dict[str, object]:
    commands: list[dict[str, object]] = []
    offset = 0
    error: dict[str, object] | None = None
    while offset < len(run.data):
        control = run.data[offset : offset + 2]
        absolute = run.start + offset
        if control in LOAD_COMMANDS:
            if offset + 6 > len(run.data):
                error = {"kind": "truncated_header", "offset": offset, "address": absolute}
                break
            memory, coded = LOAD_COMMANDS[control]
            address = (
                LOAD_COMMAND_ADDRESS_BASES.get(control, 0)
                + int.from_bytes(run.data[offset + 2 : offset + 4], "little")
            )
            words = int.from_bytes(run.data[offset + 4 : offset + 6], "little")
            payload_end = offset + 6 + words * 2
            if payload_end > len(run.data):
                error = {
                    "kind": "payload_crosses_run_end",
                    "offset": offset,
                    "address": absolute,
                    "control": control.hex(),
                    "declared_words": words,
                    "available_payload_bytes": len(run.data) - offset - 6,
                }
                break
            payload = run.data[offset + 6 : payload_end]
            commands.append(
                {
                    "file_offset": offset,
                    "file_address": absolute,
                    "control": control.hex(),
                    "kind": "load_section",
                    "memory": memory,
                    "coded": coded,
                    "sct_address_words": address,
                    "word_count": words,
                    "payload_bytes": len(payload),
                    "payload_sha256": hashlib.sha256(payload).hexdigest(),
                }
            )
            offset = payload_end
            continue
        if control in FOUR_BYTE_COMMANDS:
            if offset + 4 > len(run.data):
                error = {"kind": "truncated_control", "offset": offset, "address": absolute}
                break
            argument = int.from_bytes(run.data[offset + 2 : offset + 4], "little")
            commands.append(
                {
                    "file_offset": offset,
                    "file_address": absolute,
                    "control": control.hex(),
                    "kind": FOUR_BYTE_COMMANDS[control],
                    "argument": argument,
                }
            )
            offset += 4
            continue
        error = {
            "kind": "unknown_control",
            "offset": offset,
            "address": absolute,
            "next_16": run.data[offset : offset + 16].hex(),
        }
        break

    return {
        "start": run.start,
        "end_exclusive": run.end,
        "length": len(run.data),
        "sha256": hashlib.sha256(run.data).hexdigest(),
        "parsed_bytes": offset,
        "fully_parsed": offset == len(run.data),
        "commands": commands,
        "stop": error,
        "compact_summary": summarize_commands(commands),
    }


def summarize_commands(commands: list[dict[str, object]]) -> dict[str, object]:
    sections = [command for command in commands if command["kind"] == "load_section"]
    terminals = [command for command in commands if command["kind"] != "load_section"]
    by_memory_and_coding: dict[str, int] = {}
    for section in sections:
        key = f'{section["memory"]}_coded{section["coded"]}'
        by_memory_and_coding[key] = by_memory_and_coding.get(key, 0) + 1
    return {
        "section_count": len(sections),
        "section_payload_bytes": sum(int(section["payload_bytes"]) for section in sections),
        "section_counts_by_memory_and_coding": by_memory_and_coding,
        "instruction_address_words": address_span(sections, "instruction"),
        "data_address_words": address_span(sections, "data"),
        "terminal_commands": [
            {
                "control": command["control"],
                "kind": command["kind"],
                "argument": command["argument"],
            }
            for command in terminals
        ],
    }


def address_span(
    sections: list[dict[str, object]], memory: str
) -> dict[str, int] | None:
    matching = [section for section in sections if section["memory"] == memory]
    if not matching:
        return None
    starts = [int(section["sct_address_words"]) for section in matching]
    ends = [
        int(section["sct_address_words"]) + int(section["word_count"])
        for section in matching
    ]
    return {"start": min(starts), "end_exclusive": max(ends)}


def index_file(path: Path) -> dict[str, object]:
    segments, record_counts = parse_sct_hex(path)
    runs = merge_contiguous(segments)
    indexed = [index_run(run) for run in runs]
    return {
        "path": str(path.resolve()),
        "size": path.stat().st_size,
        "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
        "record_counts": record_counts,
        "runs": indexed,
        "summary": {
            "runs": len(indexed),
            "fully_parsed_runs": sum(bool(run["fully_parsed"]) for run in indexed),
            "commands": sum(len(run["commands"]) for run in indexed),
            "parsed_bytes": sum(int(run["parsed_bytes"]) for run in indexed),
            "payload_bytes": sum(
                int(command.get("payload_bytes", 0))
                for run in indexed
                for command in run["commands"]
            ),
        },
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("hex_files", type=Path, nargs="+")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()

    if args.output.exists():
        parser.error(f"refusing to overwrite existing output: {args.output}")
    report = {
        "format": "sct3258-download-stream-index-v2",
        "inputs": [index_file(path) for path in args.hex_files],
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(
        json.dumps(report, indent=2, ensure_ascii=True) + "\n", encoding="ascii"
    )
    print(json.dumps(report, indent=2, ensure_ascii=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
