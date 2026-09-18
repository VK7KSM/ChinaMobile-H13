#!/usr/bin/env python3
"""Create a read-only structural index for SCT ProgramFlash.prog files."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
from collections import Counter
from pathlib import Path


LINE_RE = re.compile(r"^([WR]):([0-9A-Fa-f]{2}) ([0-9A-Fa-f]{2}) (.+)$")
HEX_RE = re.compile(r"^[0-9A-Fa-f]{2}$")


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def parse_line(line: str, line_number: int) -> dict:
    match = LINE_RE.fullmatch(line.strip())
    if not match:
        raise ValueError(f"line {line_number}: invalid W/R record")

    direction, length_hi, length_lo, hex_text = match.groups()
    tokens = hex_text.split()
    if not tokens or any(not HEX_RE.fullmatch(token) for token in tokens):
        raise ValueError(f"line {line_number}: invalid hexadecimal byte")

    frame = bytes(int(token, 16) for token in tokens)
    listed_length = (int(length_hi, 16) << 8) | int(length_lo, 16)
    if listed_length != len(frame):
        raise ValueError(
            f"line {line_number}: listed length {listed_length} != {len(frame)}"
        )
    if len(frame) < 7 or frame[:3] != b"\x84\xA9\x61":
        raise ValueError(f"line {line_number}: invalid SCT transport header")

    body_length = int.from_bytes(frame[3:5], "big")
    if len(frame) != body_length + 6:
        raise ValueError(
            f"line {line_number}: body length {body_length} does not match frame"
        )

    packet_type = frame[5]
    body = frame[6:]
    field = body[0]
    item = {
        "line": line_number,
        "direction": direction,
        "frame_length": len(frame),
        "body_length": body_length,
        "packet_type": f"0x{packet_type:02X}",
        "field": f"0x{field:02X}",
        "body_sha256": sha256(body),
        "frame_sha256": sha256(frame),
    }

    if field in (0x16, 0x93) and len(body) >= 2:
        item["value"] = f"0x{body[1]:02X}"

    # PWritFlash(): 94 + 24-bit address + count + data + 2-byte parity.
    if direction == "W" and packet_type == 0x03 and field == 0x94:
        if len(body) < 7:
            raise ValueError(f"line {line_number}: truncated Flash write body")
        address = int.from_bytes(body[1:4], "big")
        payload = body[5:-2]
        item.update(
            {
                "operation": "flash_write",
                "address": address,
                "address_hex": f"0x{address:06X}",
                "count_byte": body[4],
                "data_length": len(payload),
                "data_end_exclusive": address + len(payload),
                "data_end_exclusive_hex": f"0x{address + len(payload):06X}",
                "data_sha256": sha256(payload),
                "parity_hex": body[-2:].hex().upper(),
            }
        )
    elif direction == "W" and field == 0x93:
        item["operation"] = "flash_session_control"
        item["risk"] = "session_start_or_erase" if body[1] != 0 else "session_end"
    elif direction == "W" and field == 0x16:
        item["operation"] = "parity_control"

    return item


def merge_ranges(writes: list[dict]) -> list[dict]:
    ranges: list[dict] = []
    for item in sorted(writes, key=lambda value: (value["address"], value["line"])):
        start = item["address"]
        end = item["data_end_exclusive"]
        if ranges and start <= ranges[-1]["end_exclusive"]:
            current = ranges[-1]
            if start < current["end_exclusive"]:
                current["overlap_bytes"] += current["end_exclusive"] - start
            current["end_exclusive"] = max(current["end_exclusive"], end)
            current["write_count"] += 1
        else:
            ranges.append(
                {
                    "start": start,
                    "end_exclusive": end,
                    "write_count": 1,
                    "overlap_bytes": 0,
                }
            )

    for item in ranges:
        item["start_hex"] = f"0x{item['start']:06X}"
        item["end_exclusive_hex"] = f"0x{item['end_exclusive']:06X}"
        item["span_bytes"] = item["end_exclusive"] - item["start"]
    return ranges


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("input", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()

    if args.output.exists():
        raise SystemExit(f"refusing to overwrite existing output: {args.output}")

    raw = args.input.read_bytes()
    text = raw.decode("ascii")
    source_lines = text.splitlines()
    records = [parse_line(line, number) for number, line in enumerate(source_lines, 1)]

    if len(records) % 2:
        raise ValueError("record count is not an even W/R sequence")
    transactions = []
    for index in range(0, len(records), 2):
        write = records[index]
        response = records[index + 1]
        if write["direction"] != "W" or response["direction"] != "R":
            raise ValueError(f"lines {write['line']}-{response['line']}: not a W/R pair")
        transactions.append(
            {
                "index": index // 2,
                "write_line": write["line"],
                "response_line": response["line"],
                "write_field": write["field"],
                "response_field": response["field"],
                "fields_match": write["field"] == response["field"],
            }
        )

    writes = [item for item in records if item.get("operation") == "flash_write"]
    ranges = merge_ranges(writes)
    field_counts = Counter(
        f"{item['direction']}/type={item['packet_type']}/field={item['field']}"
        for item in records
    )
    session_controls = [
        item for item in records if item.get("operation") == "flash_session_control"
    ]

    result = {
        "format": "sct-programflash-index-v1",
        "source": {
            "path": str(args.input),
            "size": len(raw),
            "sha256": sha256(raw),
        },
        "summary": {
            "line_count": len(source_lines),
            "record_count": len(records),
            "transaction_count": len(transactions),
            "all_transactions_are_wr_pairs": True,
            "all_transaction_fields_match": all(
                item["fields_match"] for item in transactions
            ),
            "flash_write_count": len(writes),
            "flash_write_data_bytes": sum(item["data_length"] for item in writes),
            "flash_write_ranges": ranges,
            "session_controls": session_controls,
            "field_counts": dict(sorted(field_counts.items())),
        },
        "transactions": transactions,
        "records": records,
    }

    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, indent=2) + "\n", encoding="ascii")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
