#!/usr/bin/env python3
"""Decode and index Sicomm ``abc``/XOR batch application containers."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
from pathlib import Path


sys.path.insert(0, str(Path(__file__).resolve().parent))
from index_sct3258_download_stream import index_file  # noqa: E402


MAGIC = b"abc"
XOR_KEY = 0x76
HEADING_RE = re.compile(
    r"^(?:BootLoader|Exec App|System Flash|Modem Flash(?: \d+)?|"
    r"Vocoder\d+ Flash(?: \d+)?|User\d+ Flash(?: \d+)?)$"
)
ENTRY_RE = re.compile(r"^E\s+0x([0-9a-fA-F]+)$")
SECTION_RE = re.compile(
    r"^([id])\s+0x([0-9a-fA-F]+)\s+0x([0-9a-fA-F]+)\s+0x([0-9a-fA-F]+)$"
)
WORD_RE = re.compile(r"^0x([0-9a-fA-F]{1,4})$")


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def decode_container(raw: bytes) -> bytes:
    if len(raw) < len(MAGIC) or raw[: len(MAGIC)] != MAGIC:
        raise ValueError("missing Sicomm abc batch-container magic")
    return bytes(value ^ XOR_KEY for value in raw[len(MAGIC) :])


def parse_section_body(lines: list[str], heading: str) -> dict[str, object]:
    entry: int | None = None
    sections: list[dict[str, object]] = []
    index = 0
    while index < len(lines):
        text = lines[index].strip()
        if not text or text == "\x1a":
            index += 1
            continue
        entry_match = ENTRY_RE.fullmatch(text)
        if entry_match:
            if entry is not None:
                raise ValueError(f"{heading}: duplicate entry at body line {index + 1}")
            entry = int(entry_match.group(1), 16)
            index += 1
            continue
        section_match = SECTION_RE.fullmatch(text)
        if not section_match:
            raise ValueError(f"{heading}: unexpected body line {index + 1}: {text!r}")

        kind = section_match.group(1)
        address = int(section_match.group(2), 16)
        word_count = int(section_match.group(3), 16)
        coded_type = int(section_match.group(4), 16)
        header_line = index + 1
        payload = bytearray()
        index += 1
        for word_offset in range(word_count):
            if index >= len(lines):
                raise ValueError(
                    f"{heading}: section at body line {header_line} ended after "
                    f"{word_offset:#x} of {word_count:#x} words"
                )
            word_match = WORD_RE.fullmatch(lines[index].strip())
            if not word_match:
                raise ValueError(
                    f"{heading}: expected word at body line {index + 1}, "
                    f"got {lines[index].strip()!r}"
                )
            word = int(word_match.group(1), 16)
            payload.extend((word & 0xFF, word >> 8))
            index += 1

        sections.append(
            {
                "index": len(sections),
                "kind": kind,
                "address": address,
                "address_hex": f"0x{address:05x}",
                "word_count": word_count,
                "byte_count": len(payload),
                "coded_type": coded_type,
                "header_body_line": header_line,
                "payload_sha256": sha256(payload),
            }
        )

    return {
        "entry": entry,
        "entry_hex": None if entry is None else f"0x{entry:04x}",
        "section_count": len(sections),
        "total_words": sum(int(section["word_count"]) for section in sections),
        "coded_type_counts": count_values(sections, "coded_type"),
        "memory_counts": count_values(sections, "kind"),
        "sections": sections,
    }


def count_values(items: list[dict[str, object]], key: str) -> dict[str, int]:
    result: dict[str, int] = {}
    for item in items:
        value = str(item[key])
        result[value] = result.get(value, 0) + 1
    return result


def split_batches(decoded: bytes) -> list[dict[str, object]]:
    try:
        text = decoded.decode("ascii")
    except UnicodeDecodeError as error:
        raise ValueError(f"decoded body is not ASCII at byte {error.start}") from error
    lines = text.splitlines(keepends=True)
    headings = [
        index for index, line in enumerate(lines) if HEADING_RE.fullmatch(line.rstrip("\r\n"))
    ]
    if not headings or headings[0] != 0:
        raise ValueError("decoded body does not start with a recognized batch heading")

    batches: list[dict[str, object]] = []
    for ordinal, start in enumerate(headings):
        end = headings[ordinal + 1] if ordinal + 1 < len(headings) else len(lines)
        heading = lines[start].rstrip("\r\n")
        body_lines_with_endings = lines[start + 1 : end]
        body = "".join(body_lines_with_endings).encode("ascii")
        parsed = parse_section_body(
            [line.rstrip("\r\n") for line in body_lines_with_endings], heading
        )
        batches.append(
            {
                "index": ordinal,
                "heading": heading,
                "decoded_start_line": start + 1,
                "decoded_end_line": end,
                "body_size": len(body),
                "body_sha256": sha256(body),
                **parsed,
            }
        )
    return batches


def embedded_sections(hex_files: list[Path]) -> dict[tuple[object, ...], list[dict[str, object]]]:
    matches: dict[tuple[object, ...], list[dict[str, object]]] = {}
    for path in hex_files:
        indexed = index_file(path)
        for run in indexed["runs"]:
            for command in run["commands"]:
                if command["kind"] != "load_section":
                    continue
                memory = "i" if command["memory"] == "instruction" else "d"
                key = (
                    memory,
                    int(command["sct_address_words"]) & 0xFFFF,
                    command["word_count"],
                    command["coded"],
                    command["payload_sha256"],
                )
                matches.setdefault(key, []).append(
                    {
                        "hex_file": str(path.resolve()),
                        "control": command["control"],
                        "stream_address": command["sct_address_words"],
                        "stream_address_hex": f"0x{int(command['sct_address_words']):05x}",
                        "flash_address": command["file_address"],
                        "flash_address_hex": f"0x{int(command['file_address']):06x}",
                    }
                )
    return matches


def index_container(path: Path, hex_files: list[Path]) -> dict[str, object]:
    raw = path.read_bytes()
    decoded = decode_container(raw)
    batches = split_batches(decoded)
    matches = embedded_sections(hex_files)
    matched_sections = 0
    for batch in batches:
        for section in batch["sections"]:
            key = (
                section["kind"],
                int(section["address"]) & 0xFFFF,
                section["word_count"],
                section["coded_type"],
                section["payload_sha256"],
            )
            section["embedded_hex_matches"] = matches.get(key, [])
            for match in section["embedded_hex_matches"]:
                match["address_match"] = (
                    "full"
                    if int(match["stream_address"]) == int(section["address"])
                    else "low16_context"
                )
            matched_sections += bool(section["embedded_hex_matches"])

    total_sections = sum(int(batch["section_count"]) for batch in batches)
    return {
        "format": "sicomm-xor-batch-index-v1",
        "source": str(path.resolve()),
        "source_size": len(raw),
        "source_sha256": sha256(raw),
        "magic_ascii": MAGIC.decode("ascii"),
        "xor_key_hex": f"0x{XOR_KEY:02x}",
        "decoded_size": len(decoded),
        "decoded_sha256": sha256(decoded),
        "batch_count": len(batches),
        "total_sections": total_sections,
        "sections_with_embedded_hex_match": matched_sections,
        "all_sections_match_embedded_hex": matched_sections == total_sections,
        "hex_comparison_files": [str(path.resolve()) for path in hex_files],
        "batches": batches,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("container", type=Path)
    parser.add_argument("--hex-file", action="append", default=[], type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    if args.output.exists():
        parser.error(f"refusing to overwrite existing output: {args.output}")
    report = index_container(args.container, args.hex_file)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(
        json.dumps(report, indent=2, ensure_ascii=True) + "\n", encoding="ascii"
    )
    print(json.dumps({key: report[key] for key in (
        "format", "source_sha256", "batch_count", "total_sections",
        "sections_with_embedded_hex_match", "all_sections_match_embedded_hex",
    )}, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
