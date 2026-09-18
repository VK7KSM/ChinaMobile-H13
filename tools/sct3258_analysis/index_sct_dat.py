#!/usr/bin/env python3
"""Index SCT instruction/data-memory section files without modifying them."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
from pathlib import Path


ENTRY_RE = re.compile(r"^E\s+0x([0-9a-fA-F]+)$")
SECTION_RE = re.compile(
    r"^([id])\s+0x([0-9a-fA-F]+)\s+0x([0-9a-fA-F]+)\s+0x([0-9a-fA-F]+)$"
)
WORD_RE = re.compile(r"^0x([0-9a-fA-F]{1,4})$")


def parse_dat(
    path: Path, *, require_entry: bool = True
) -> tuple[dict[str, object], list[bytes]]:
    lines = path.read_text(encoding="ascii").splitlines()
    sections: list[dict[str, object]] = []
    payloads: list[bytes] = []
    entry: int | None = None
    line_index = 0

    while line_index < len(lines):
        text = lines[line_index].strip()
        if not text:
            line_index += 1
            continue
        if text == "\x1a" and all(not tail.strip() for tail in lines[line_index + 1 :]):
            line_index = len(lines)
            continue

        entry_match = ENTRY_RE.fullmatch(text)
        if entry_match:
            if entry is not None:
                raise ValueError(f"{path}:{line_index + 1}: duplicate entry point")
            entry = int(entry_match.group(1), 16)
            line_index += 1
            continue

        section_match = SECTION_RE.fullmatch(text)
        if not section_match:
            raise ValueError(f"{path}:{line_index + 1}: unexpected line: {text!r}")

        kind = section_match.group(1)
        address = int(section_match.group(2), 16)
        word_count = int(section_match.group(3), 16)
        coded_type = int(section_match.group(4), 16)
        header_line = line_index + 1
        payload = bytearray()
        line_index += 1

        for word_offset in range(word_count):
            if line_index >= len(lines):
                raise ValueError(
                    f"{path}:{header_line}: section ended after {word_offset:#x} "
                    f"of {word_count:#x} words"
                )
            word_text = lines[line_index].strip()
            word_match = WORD_RE.fullmatch(word_text)
            if not word_match:
                raise ValueError(
                    f"{path}:{line_index + 1}: expected word {word_offset:#x}, "
                    f"got {word_text!r}"
                )
            word = int(word_match.group(1), 16)
            payload.extend((word & 0xFF, word >> 8))
            line_index += 1

        payload_bytes = bytes(payload)
        payloads.append(payload_bytes)
        sections.append(
            {
                "index": len(sections),
                "kind": kind,
                "address": address,
                "address_hex": f"0x{address:05x}",
                "word_count": word_count,
                "word_count_hex": f"0x{word_count:04x}",
                "byte_count": len(payload),
                "coded_type": coded_type,
                "new_instruction_space": kind == "i" and 0x90000 <= address <= 0x9FFFF,
                "header_line": header_line,
                "payload_first_line": header_line + 1,
                "payload_last_line": header_line + word_count,
                "payload_sha256": hashlib.sha256(payload_bytes).hexdigest(),
            }
        )

    if entry is None and require_entry:
        raise ValueError(f"{path}: no entry point")

    raw = path.read_bytes()
    document = {
        "format": "sct-dat-section-index-v1",
        "source": str(path.resolve()),
        "file_size": len(raw),
        "file_sha256": hashlib.sha256(raw).hexdigest(),
        "entry": entry,
        "entry_hex": None if entry is None else f"0x{entry:04x}",
        "section_count": len(sections),
        "total_words": sum(int(section["word_count"]) for section in sections),
        "total_payload_bytes": sum(int(section["byte_count"]) for section in sections),
        "sections": sections,
    }
    return document, payloads


def index_dat(path: Path) -> dict[str, object]:
    document, _ = parse_dat(path)
    return document


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("files", nargs="+", type=Path)
    parser.add_argument("--output", type=Path, help="write JSON to this new file")
    args = parser.parse_args()

    result = [index_dat(path) for path in args.files]
    document: object = result[0] if len(result) == 1 else result
    rendered = json.dumps(document, indent=2, ensure_ascii=True) + "\n"

    if args.output:
        if args.output.exists():
            raise FileExistsError(f"refusing to overwrite {args.output}")
        args.output.write_text(rendered, encoding="ascii", newline="\n")
    else:
        print(rendered, end="")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
