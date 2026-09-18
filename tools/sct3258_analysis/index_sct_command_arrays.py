#!/usr/bin/env python3
"""Index command templates from SCT_PORT SoapFormatter XML files."""

from __future__ import annotations

import argparse
import base64
import hashlib
import html
import json
import re
from collections import Counter, defaultdict
from pathlib import Path


COMMAND_RE = re.compile(
    r'<a1:CommandInfo\b[^>]*>(.*?)</a1:CommandInfo>', re.DOTALL
)
ID_RE = re.compile(r'<_commandID\b[^>]*>(.*?)</_commandID>', re.DOTALL)
HREF_RE = re.compile(r'<_(writeCommand|readCommand)\s+href="#(ref-\d+)"\s*/>')
COUNT_RE = re.compile(r'<_readCount>(-?\d+)</_readCount>')
ARRAY_RE = re.compile(
    r'<SOAP-ENC:Array\s+id="(ref-\d+)"\s+xsi:type="SOAP-ENC:base64">'
    r'(.*?)</SOAP-ENC:Array>',
    re.DOTALL,
)
SUMMARY_RE = re.compile(
    r'<SOAP-ENC:string\s+id="ref-1">(.*?)</SOAP-ENC:string>', re.DOTALL
)


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def decode_base64(value: str) -> bytes:
    compact = re.sub(r"\s+", "", value)
    return base64.b64decode(compact) if compact else b""


def hex_bytes(value: bytes) -> str:
    return " ".join(f"{byte:02X}" for byte in value)


def parse_file(path: Path, root: Path) -> dict:
    text = path.read_text(encoding="utf-8-sig", errors="strict")
    arrays = {ref: decode_base64(data) for ref, data in ARRAY_RE.findall(text)}
    summaries = [html.unescape(item).strip() for item in SUMMARY_RE.findall(text)]
    commands = []

    for ordinal, body in enumerate(COMMAND_RE.findall(text)):
        id_match = ID_RE.search(body)
        if not id_match:
            raise ValueError(f"missing command id in {path}, ordinal {ordinal}")
        refs = dict(HREF_RE.findall(body))
        count_match = COUNT_RE.search(body)
        write = arrays.get(refs.get("writeCommand", ""), b"")
        read = arrays.get(refs.get("readCommand", ""), b"")
        command_id = html.unescape(id_match.group(1)).strip()
        summary = summaries[ordinal] if ordinal < len(summaries) else ""
        commands.append(
            {
                "ordinal": ordinal,
                "command_id": command_id,
                "write_length": len(write),
                "write_hex": hex_bytes(write),
                "write_sha256": hashlib.sha256(write).hexdigest(),
                "expected_read_length": len(read),
                "expected_read_hex": hex_bytes(read),
                "expected_read_sha256": hashlib.sha256(read).hexdigest(),
                "configured_read_count": int(count_match.group(1)) if count_match else None,
                "summary": summary,
            }
        )

    declared_match = re.search(r'CommandInfo\[(\d+)\]', text)
    declared = int(declared_match.group(1)) if declared_match else None
    if declared is not None and declared != len(commands):
        raise ValueError(
            f"declared {declared} commands but parsed {len(commands)} in {path}"
        )

    return {
        "path": path.relative_to(root).as_posix(),
        "size": path.stat().st_size,
        "sha256": sha256(path),
        "declared_command_count": declared,
        "parsed_command_count": len(commands),
        "summary_count": len(summaries),
        "commands": commands,
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("input_root", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()

    root = args.input_root.resolve()
    output = args.output.resolve()
    if output.exists():
        raise FileExistsError(f"refusing to overwrite {output}")

    paths = sorted(root.rglob("CommandArray*.xml"))
    if not paths:
        raise FileNotFoundError(f"no CommandArray*.xml under {root}")

    files = [parse_file(path, root) for path in paths]
    semantic_re = re.compile(
        r"flash|memory|\bmem\b|read|iq|i/q|speech|chan|voice|voco|modem|adc|dac|"
        r"sample|debug|record",
        re.IGNORECASE,
    )
    semantic_hits = []
    long_responses = []
    by_write = defaultdict(list)
    id_counts = Counter()

    for file_item in files:
        for command in file_item["commands"]:
            ref = {
                "source": file_item["path"],
                "ordinal": command["ordinal"],
                "command_id": command["command_id"],
                "write_hex": command["write_hex"],
                "write_length": command["write_length"],
                "expected_read_length": command["expected_read_length"],
                "expected_read_hex": command["expected_read_hex"],
                "summary": command["summary"],
            }
            id_counts[command["command_id"]] += 1
            by_write[command["write_hex"]].append(ref)
            if semantic_re.search(command["command_id"] + " " + command["summary"]):
                semantic_hits.append(ref)
            if command["expected_read_length"] >= 16:
                long_responses.append(ref)

    duplicate_writes = [
        {"write_hex": write_hex, "occurrences": refs}
        for write_hex, refs in sorted(by_write.items())
        if write_hex and len(refs) > 1
    ]
    report = {
        "format": "sct_command_array_index_v1",
        "input_root": root.as_posix(),
        "file_count": len(files),
        "command_count": sum(item["parsed_command_count"] for item in files),
        "unique_command_id_count": len(id_counts),
        "command_id_counts": dict(sorted(id_counts.items())),
        "semantic_hits": semantic_hits,
        "long_expected_responses": long_responses,
        "duplicate_write_templates": duplicate_writes,
        "files": files,
    }
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"files={report['file_count']}")
    print(f"commands={report['command_count']}")
    print(f"unique_ids={report['unique_command_id_count']}")
    print(f"semantic_hits={len(semantic_hits)}")
    print(f"long_expected_responses={len(long_responses)}")
    print(f"output={output}")
    print(f"sha256={sha256(output)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
