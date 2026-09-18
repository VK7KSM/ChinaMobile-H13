#!/usr/bin/env python3
"""Assemble the v0.63 SysTick handler that restores 57600 on bridge timeout."""

from __future__ import annotations

import argparse
import hashlib
import struct
import sys
from pathlib import Path

LOAD_ADDRESS = 0x20001600
EXPECTED_CONSTANTS = (
    0x20001680,  # counter
    0x2000015C,  # bridge flag
    0x2000003C,  # SysTick vector slot
    0x08021DDD,  # original SysTick handler
    0x20001684,  # marker address
    0x47445242,  # 'BRDG'
    57600,       # restore baud
    0x08010A01,  # baud setter Thumb entry
)


def keystone_source(path: Path) -> str:
    kept = []
    for raw_line in path.read_text(encoding="ascii").splitlines():
        line = raw_line.strip()
        if not line or line.startswith(
            (".syntax", ".cpu", ".thumb", ".section", ".global",
             ".type", ".thumb_func", ".size", "@")
        ):
            continue
        kept.append(raw_line)
    return "\n".join(kept)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("source", type=Path)
    parser.add_argument("binary", type=Path)
    parser.add_argument("disassembly", type=Path)
    args = parser.parse_args()
    for output in (args.binary, args.disassembly):
        if output.exists():
            raise RuntimeError(f"refusing to overwrite {output}")

    # Prefer project-local keystone/capstone wheels when present.
    sys.path[:0] = [
        str(Path(__file__).resolve().parents[1] / "asm_runtime"),
        str(Path(__file__).resolve().parents[1] / "capstone_runtime"),
        str(Path(__file__).resolve().parents[1] / "pydeps"),
    ]
    from capstone import CS_ARCH_ARM, CS_MODE_LITTLE_ENDIAN, CS_MODE_THUMB, Cs
    from keystone import KS_ARCH_ARM, KS_MODE_LITTLE_ENDIAN, KS_MODE_THUMB, Ks

    assembler = Ks(KS_ARCH_ARM, KS_MODE_THUMB | KS_MODE_LITTLE_ENDIAN)
    encoded, _count = assembler.asm(
        keystone_source(args.source), addr=LOAD_ADDRESS)
    blob = bytes(encoded)

    # Literal pool is 8 words at end; locate by last 32 bytes.
    if len(blob) < 32 or len(blob) % 2:
        raise RuntimeError(f"unexpected binary length {len(blob)}")
    constants = struct.unpack_from("<8I", blob, len(blob) - 32)
    if constants != EXPECTED_CONSTANTS:
        raise RuntimeError(f"literal pool mismatch: {tuple(hex(c) for c in constants)}")

    code_length = len(blob) - 32
    disassembler = Cs(CS_ARCH_ARM, CS_MODE_THUMB | CS_MODE_LITTLE_ENDIAN)
    instructions = list(disassembler.disasm(blob[:code_length], LOAD_ADDRESS))
    mnemonics = [item.mnemonic for item in instructions]
    if "blx" not in mnemonics:
        raise RuntimeError(f"expected blx to baud setter, got {mnemonics!r}")
    if mnemonics.count("bx") != 1:
        raise RuntimeError(f"expected single bx tail-chain, got {mnemonics!r}")

    args.binary.write_bytes(blob)
    digest = hashlib.sha256(blob).hexdigest()
    lines = [
        f"load_address=0x{LOAD_ADDRESS:08x}",
        f"length={len(blob)}",
        f"sha256={digest}",
        f"constants={[hex(c) for c in constants]}",
        "",
    ]
    for item in instructions:
        lines.append(
            f"0x{item.address:08x}: {item.mnemonic:8} {item.op_str}"
        )
    args.disassembly.write_text("\n".join(lines) + "\n", encoding="ascii")
    print(f"wrote {args.binary} ({len(blob)} bytes) sha256={digest}")
    print(f"wrote {args.disassembly}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
