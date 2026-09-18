#!/usr/bin/env python3
import argparse
import hashlib
import struct
import sys
from pathlib import Path


LOAD_ADDRESS = 0x20001D00
CODE_LENGTH = 40
EXPECTED_CONSTANTS = (
    0x20001D80,
    0x2000015C,
    0x2000003C,
    0x08021DDD,
    0x20001D84,
    0x47445242,
)
EXPECTED_MNEMONICS = (
    "ldr", "ldr", "cmp", "beq", "subs", "str", "bne",
    "ldr", "movs", "strb",
    "ldr", "ldr", "str",
    "ldr", "ldr", "str",
    "ldr", "bx", "nop", "nop",
)


def keystone_source(path: Path) -> str:
    kept = []
    for raw_line in path.read_text(encoding="ascii").splitlines():
        line = raw_line.strip()
        if not line or line.startswith((".syntax", ".cpu", ".thumb",
                                        ".section", ".global", ".type",
                                        ".thumb_func", ".size")):
            continue
        kept.append(raw_line)
    return "\n".join(kept)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("source", type=Path)
    parser.add_argument("binary", type=Path)
    parser.add_argument("disassembly", type=Path)
    args = parser.parse_args()

    from capstone import CS_ARCH_ARM, CS_MODE_LITTLE_ENDIAN, CS_MODE_THUMB, Cs
    from keystone import KS_ARCH_ARM, KS_MODE_LITTLE_ENDIAN, KS_MODE_THUMB, Ks

    source = keystone_source(args.source)
    assembler = Ks(KS_ARCH_ARM, KS_MODE_THUMB | KS_MODE_LITTLE_ENDIAN)
    encoded, statement_count = assembler.asm(source, addr=LOAD_ADDRESS)
    blob = bytes(encoded)
    if len(blob) != CODE_LENGTH + 4 * len(EXPECTED_CONSTANTS):
        raise RuntimeError(f"unexpected binary length {len(blob)}")

    constants = struct.unpack_from("<6I", blob, CODE_LENGTH)
    if constants != EXPECTED_CONSTANTS:
        raise RuntimeError(f"literal pool mismatch: {constants!r}")

    disassembler = Cs(CS_ARCH_ARM, CS_MODE_THUMB | CS_MODE_LITTLE_ENDIAN)
    instructions = list(disassembler.disasm(blob[:CODE_LENGTH], LOAD_ADDRESS))
    mnemonics = tuple(item.mnemonic for item in instructions)
    if mnemonics != EXPECTED_MNEMONICS:
        raise RuntimeError(f"instruction sequence mismatch: {mnemonics!r}")

    branch_targets = {
        item.address: int(item.op_str.lstrip("#"), 16)
        for item in instructions
        if item.mnemonic in ("beq", "bne")
    }
    expected_branches = {
        LOAD_ADDRESS + 0x06: LOAD_ADDRESS + 0x0E,
        LOAD_ADDRESS + 0x0C: LOAD_ADDRESS + 0x20,
    }
    if branch_targets != expected_branches:
        raise RuntimeError(f"branch target mismatch: {branch_targets!r}")

    args.binary.write_bytes(blob)
    digest = hashlib.sha256(blob).hexdigest()
    lines = [
        f"load_address=0x{LOAD_ADDRESS:08x}",
        f"code_length={CODE_LENGTH}",
        f"binary_length={len(blob)}",
        f"statement_count={statement_count}",
        f"sha256={digest}",
        "",
    ]
    lines.extend(
        f"0x{item.address:08x}: {item.bytes.hex(' '):<14} "
        f"{item.mnemonic:<7} {item.op_str}".rstrip()
        for item in instructions
    )
    lines.append("")
    lines.extend(
        f"literal[{index}]=0x{value:08x}"
        for index, value in enumerate(constants)
    )
    args.disassembly.write_text("\n".join(lines) + "\n", encoding="ascii")
    print(f"wrote {args.binary} ({len(blob)} bytes, sha256 {digest})")
    print(f"wrote {args.disassembly}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
