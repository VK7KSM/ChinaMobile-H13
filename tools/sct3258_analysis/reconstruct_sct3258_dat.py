#!/usr/bin/env python3
"""Reconstruct vendor-readable .dat-equivalent files from SCT3258 updater HEX."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
from pathlib import Path

from analyze_sct3258_hex import Segment, parse_sct_hex
from index_sct3258_download_stream import FOUR_BYTE_COMMANDS, LOAD_COMMANDS, merge_contiguous


CONTROL_BY_SECTION = {
    value: control for control, value in LOAD_COMMANDS.items()
}
ROLE_BY_START = {
    0x000100: "user_application",
    0x018000: "vocoder_index_04",
    0x02C000: "vocoder_index_12",
    0x040000: "vocoder_index_16",
    0x051000: "vocoder_index_23",
    0x068000: "modem2_application",
}
HEADER_RE = re.compile(r"^([id])\s+0x([0-9a-f]+)\s+0x([0-9a-f]+)\s+0x([0-9a-f]+)$")
WORD_RE = re.compile(r"^0x([0-9a-f]{1,4})$")
ENTRY_RE = re.compile(r"^E\s+0x([0-9a-f]+)$")


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def parse_run(run: Segment) -> tuple[list[dict[str, object]], dict[str, object]]:
    sections: list[dict[str, object]] = []
    offset = 0
    terminal: dict[str, object] | None = None
    while offset < len(run.data):
        control = run.data[offset : offset + 2]
        if control in LOAD_COMMANDS:
            memory, coded = LOAD_COMMANDS[control]
            address = int.from_bytes(run.data[offset + 2 : offset + 4], "little")
            words = int.from_bytes(run.data[offset + 4 : offset + 6], "little")
            end = offset + 6 + words * 2
            if end > len(run.data):
                raise ValueError(f"section at 0x{run.start + offset:x} crosses run end")
            payload = run.data[offset + 6 : end]
            sections.append(
                {
                    "memory": memory,
                    "coded": coded,
                    "address_words": address,
                    "word_count": words,
                    "payload": payload,
                }
            )
            offset = end
            continue
        if control in FOUR_BYTE_COMMANDS:
            if terminal is not None or offset + 4 != len(run.data):
                raise ValueError(f"non-final or duplicate terminal at 0x{run.start + offset:x}")
            terminal = {
                "control": control,
                "kind": FOUR_BYTE_COMMANDS[control],
                "argument": int.from_bytes(run.data[offset + 2 : offset + 4], "little"),
            }
            offset += 4
            continue
        raise ValueError(f"unknown control {control.hex()} at 0x{run.start + offset:x}")
    if terminal is None:
        raise ValueError(f"run at 0x{run.start:x} has no terminal command")
    return sections, terminal


def render_dat(sections: list[dict[str, object]], entry: int | None) -> bytes:
    lines: list[str] = []
    if entry is not None:
        lines.append(f"E 0x{entry:04x}")
    for section in sections:
        command = "i" if section["memory"] == "instruction" else "d"
        lines.append(
            f'{command} 0x{int(section["address_words"]):04x} '
            f'0x{int(section["word_count"]):04x} 0x{int(section["coded"]):04x}'
        )
        payload = bytes(section["payload"])
        for offset in range(0, len(payload), 2):
            lines.append(f"0x{int.from_bytes(payload[offset:offset + 2], 'little'):04x}")
    return ("\r\n".join(lines) + "\r\n").encode("ascii")


def parse_rendered_dat(data: bytes) -> tuple[list[dict[str, object]], int | None]:
    lines = [line.strip() for line in data.decode("ascii").splitlines() if line.strip()]
    sections: list[dict[str, object]] = []
    entry: int | None = None
    index = 0
    while index < len(lines):
        entry_match = ENTRY_RE.fullmatch(lines[index])
        if entry_match:
            if entry is not None or sections:
                raise ValueError("entry must occur once before all sections")
            entry = int(entry_match.group(1), 16)
            index += 1
            continue
        header = HEADER_RE.fullmatch(lines[index])
        if not header:
            raise ValueError(f"invalid reconstructed line: {lines[index]}")
        command, address_hex, words_hex, coded_hex = header.groups()
        words = int(words_hex, 16)
        index += 1
        payload = bytearray()
        for _ in range(words):
            if index >= len(lines):
                raise ValueError("reconstructed section is truncated")
            word = WORD_RE.fullmatch(lines[index])
            if not word:
                raise ValueError(f"invalid reconstructed word: {lines[index]}")
            payload.extend(int(word.group(1), 16).to_bytes(2, "little"))
            index += 1
        sections.append(
            {
                "memory": "instruction" if command == "i" else "data",
                "coded": int(coded_hex, 16),
                "address_words": int(address_hex, 16),
                "word_count": words,
                "payload": bytes(payload),
            }
        )
    return sections, entry


def build_stream(sections: list[dict[str, object]], terminal: dict[str, object]) -> bytes:
    result = bytearray()
    for section in sections:
        key = (str(section["memory"]), int(section["coded"]))
        control = CONTROL_BY_SECTION.get(key)
        if control is None:
            raise ValueError(f"unsupported section key: {key}")
        result.extend(control)
        result.extend(int(section["address_words"]).to_bytes(2, "little"))
        result.extend(int(section["word_count"]).to_bytes(2, "little"))
        result.extend(bytes(section["payload"]))
    result.extend(bytes(terminal["control"]))
    result.extend(int(terminal["argument"]).to_bytes(2, "little"))
    return bytes(result)


def reconstruct(hex_path: Path, output_dir: Path) -> dict[str, object]:
    if output_dir.exists():
        raise FileExistsError(f"refusing to overwrite existing output directory: {output_dir}")
    segments, _ = parse_sct_hex(hex_path)
    runs = merge_contiguous(segments)
    prepared: list[tuple[Path, bytes, dict[str, object]]] = []
    reports: list[dict[str, object]] = []
    for run in runs:
        sections, terminal = parse_run(run)
        terminal_control = bytes(terminal["control"])
        entry = int(terminal["argument"]) if terminal_control == bytes.fromhex("82a3") else None
        role = ROLE_BY_START.get(run.start, f"run_0x{run.start:06x}")
        filename = Path(f"{run.start:06x}_{role}.dat")
        rendered = render_dat(sections, entry)
        parsed_sections, parsed_entry = parse_rendered_dat(rendered)
        if parsed_entry != entry:
            raise AssertionError("entry changed during .dat round-trip")
        rebuilt = build_stream(parsed_sections, terminal)
        exact = rebuilt == run.data
        if not exact:
            raise AssertionError(f"round-trip mismatch for run at 0x{run.start:x}")
        prepared.append((filename, rendered, terminal))
        reports.append(
            {
                "run_start": run.start,
                "run_length": len(run.data),
                "role": role,
                "dat_file": filename.as_posix(),
                "dat_kind": "application" if entry is not None else "section_only_dat_equivalent",
                "entry": entry,
                "section_count": len(sections),
                "section_payload_bytes": sum(len(bytes(item["payload"])) for item in sections),
                "terminal_control": terminal_control.hex(),
                "terminal_kind": terminal["kind"],
                "terminal_argument": terminal["argument"],
                "source_stream_sha256": sha256(run.data),
                "rebuilt_stream_sha256": sha256(rebuilt),
                "round_trip_exact": exact,
                "dat_sha256": sha256(rendered),
                "dat_bytes": len(rendered),
            }
        )
    output_dir.mkdir(parents=True)
    for filename, rendered, _ in prepared:
        (output_dir / filename).write_bytes(rendered)
    manifest = {
        "format": "sct3258-dat-reconstruction-v1",
        "source_hex": str(hex_path.resolve()),
        "source_hex_size": hex_path.stat().st_size,
        "source_hex_sha256": sha256(hex_path.read_bytes()),
        "note": (
            "Application entries are source-proven by 82a3. Vocoder files omit an unknown "
            "original entry and require the recorded 85ab terminal when regenerating HEX."
        ),
        "runs": reports,
        "all_round_trips_exact": all(bool(item["round_trip_exact"]) for item in reports),
    }
    manifest_path = output_dir / "manifest.json"
    manifest_path.write_text(json.dumps(manifest, indent=2, ensure_ascii=True) + "\n", encoding="ascii")
    return manifest


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("hex_file", type=Path)
    parser.add_argument("--output-dir", type=Path, required=True)
    args = parser.parse_args()
    try:
        report = reconstruct(args.hex_file, args.output_dir)
    except (FileExistsError, ValueError, AssertionError) as exc:
        parser.error(str(exc))
    print(json.dumps(report, indent=2, ensure_ascii=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
