#!/usr/bin/env python3
"""Verify the vendor host's section-download wire contract from source and IL."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
from pathlib import Path


METHOD_RE_TEMPLATE = r"\n\s*public\s+[^\n]+\s+{name}\([^\n]*\)\s*\n\s*\{{"
CONTROLS = {
    "instruction_uncoded": bytes.fromhex("80 a8"),
    "data_uncoded": bytes.fromhex("81 a7"),
    "instruction_coded1": bytes.fromhex("88 a8"),
    "data_coded1": bytes.fromhex("89 a7"),
    "instruction_coded2": bytes.fromhex("8a a8"),
    "data_coded2": bytes.fromhex("8b a7"),
    "instruction_coded3": bytes.fromhex("8c a8"),
    "data_coded3": bytes.fromhex("8d a7"),
}


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def method_body(text: str, name: str) -> tuple[str, int]:
    match = re.search(METHOD_RE_TEMPLATE.format(name=re.escape(name)), text)
    if match is None:
        raise ValueError(f"method not found: {name}")
    opening = text.find("{", match.start())
    depth = 0
    for index in range(opening, len(text)):
        if text[index] == "{":
            depth += 1
        elif text[index] == "}":
            depth -= 1
            if depth == 0:
                return text[opening : index + 1], text.count("\n", 0, match.start()) + 2
    raise ValueError(f"unterminated method: {name}")


def source_contract(path: Path) -> dict[str, object]:
    raw = path.read_bytes()
    text = raw.decode("utf-8-sig").replace("\r\n", "\n").replace("\r", "\n")
    load_code, load_code_line = method_body(text, "LoadCode")
    load_data, load_data_line = method_body(text, "LoadData")
    create, create_line = method_body(text, "CreatCmd")
    required_create_fragments = (
        "array[_usedPort.PacketHeaderLength - 6] = _usedPort.DataHeadID[0]",
        "array[_usedPort.PacketHeaderLength - 5] = _usedPort.DataHeadID[1]",
        "array[_usedPort.PacketHeaderLength - 4] = _masterID",
        "array[_usedPort.PacketHeaderLength - 3] = (byte)(num >> 8)",
        "array[_usedPort.PacketHeaderLength - 2] = (byte)num",
        "array[_usedPort.PacketHeaderLength - 1] = cmdType",
    )
    checks = {
        "load_code_wraps_header_in_packet_type_4": "array = CreatCmd(4, array);" in load_code,
        "load_code_then_sends_section_payload_directly": "array = section.GetCmd();" in load_code,
        "load_data_does_not_call_creatcmd": "CreatCmd(" not in load_data,
        "load_data_sends_six_byte_header_directly": "SendCmd(array, 0);" in load_data,
        "load_data_then_sends_section_payload_directly": "array = section.GetCmd();" in load_data,
        "create_cmd_has_exact_six_header_assignments": all(fragment in create for fragment in required_create_fragments),
    }
    if not all(checks.values()):
        raise ValueError(f"source contract check failed for {path}: {checks}")
    return {
        "path": str(path.resolve()),
        "size": len(raw),
        "sha256": sha256(raw),
        "method_start_lines": {
            "LoadCode": load_code_line,
            "LoadData": load_data_line,
            "CreatCmd": create_line,
        },
        "checks": checks,
    }


def reflection_contract(path: Path) -> dict[str, object]:
    raw = path.read_bytes()
    text = raw.decode("utf-8-sig").replace("\r\n", "\n").replace("\r", "\n")
    required = {
        "packet_header_length_6": "ldc.i4.6\n  0002: stfld Int32 _packetHeaderLength" in text,
        "data_head_first_0x84": "ldc.i4 132\n  0016: stelem.i1" in text,
        "data_head_second_0xa9": "ldc.i4 169\n  001E: stelem.i1" in text,
        "master_id_0x61": "ldc.i4.s 97\n  0028: stfld Byte _masterID" in text,
        "serial_default_baud_115200": "ldc.i4 115200\n  0006: stfld Int32 _baudRate" in text,
        "serial_write_chunk_64": "ldc.i4.s 64\n  000E: stfld Int32 writepacket_length" in text,
        "serial_write_calls_exact_buffer_offset_count": text.count(
            "callvirt Void Write(Byte[], Int32, Int32)"
        ) >= 2,
        "runtime_values_match": all(
            fragment in text for fragment in (
                "VALUE PacketHeaderLength = 6",
                "VALUE DataHeadID = 84 A9",
                "VALUE BaudRate = 115200",
            )
        ),
    }
    if not all(required.values()):
        raise ValueError(f"reflection contract check failed: {required}")
    return {
        "path": str(path.resolve()),
        "size": len(raw),
        "sha256": sha256(raw),
        "checks": required,
    }


def wire_examples() -> list[dict[str, object]]:
    address = bytes.fromhex("34 12")
    words = bytes.fromhex("78 56")
    examples = []
    for name, control in CONTROLS.items():
        body = control + address + words
        instruction = name.startswith("instruction")
        wire = bytes.fromhex("84 a9 61 00 06 04") + body if instruction else body
        examples.append(
            {
                "name": name,
                "example_address_words": "0x1234",
                "example_word_count": "0x5678",
                "header_body_hex": body.hex(" "),
                "wire_header_hex": wire.hex(" "),
                "wire_header_bytes": len(wire),
                "framing": "packet_type_4" if instruction else "raw",
                "payload_after_header": "exact section payload bytes; no CreatCmd wrapper",
            }
        )
    return examples


def analyze(old_source: Path, new_source: Path, reflection: Path, port_dll: Path) -> dict[str, object]:
    old = source_contract(old_source)
    new = source_contract(new_source)
    reflected = reflection_contract(reflection)
    dll_raw = port_dll.read_bytes()
    return {
        "format": "sct3258-loader-host-wire-contract-v1",
        "source_contracts": [old, new],
        "port_assembly": {
            "path": str(port_dll.resolve()),
            "size": len(dll_raw),
            "sha256": sha256(dll_raw),
        },
        "reflection_il_contract": reflected,
        "wire_examples": wire_examples(),
        "confirmed_contract": {
            "instruction_header": "84 a9 61 00 06 04 + six-byte section control body",
            "data_header": "raw six-byte section control body",
            "section_payload": "raw payload bytes, written in transport chunks of at most 64 bytes",
            "transport_chunking_changes_wire_bytes": False,
            "present_in_2019_sct3258_and_2025_sct3288_sources": True,
        },
        "bounded_inference": (
            "The loader must accommodate two host entry forms before consuming the same declared "
            "payload length. This supports assigning separate receive/dispatch responsibility to "
            "the cloned loader handlers, but it does not identify which CFG block owns either form."
        ),
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--old-source", type=Path, required=True)
    parser.add_argument("--new-source", type=Path, required=True)
    parser.add_argument("--reflection", type=Path, required=True)
    parser.add_argument("--port-dll", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if args.output.exists():
        parser.error(f"refusing to overwrite existing output: {args.output}")
    report = analyze(args.old_source, args.new_source, args.reflection, args.port_dll)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(
        json.dumps(report, indent=2, ensure_ascii=True) + "\n",
        encoding="ascii",
        newline="\n",
    )
    print(json.dumps(report["confirmed_contract"], indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
