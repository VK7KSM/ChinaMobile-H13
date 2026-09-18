#!/usr/bin/env python3
import argparse
import hashlib
import struct
import sys
from pathlib import Path


LOAD_ADDRESS = 0x20001D00
CODE_LENGTH = 52
BASE_SHA256 = "9632bd52be2970f3fe4c01a5d2dc55cd9a2b97f4a432cd2e7264b3b30a079e14"
BASE_CONSTANTS = (
    0x20000038,
    0x08013387,
    0x20001D80,
    0x52495048,
    0x44495048,
    0x08010291,
    0x08010561,
    0x0802CB8A,
    1116,
    0x0802CFE8,
    930,
)
EXPECTED_CONSTANTS = (
    0x2000003C,
    0x08021DDD,
    0x20001D80,
    0x52495048,
    0x44495048,
    0x08010291,
    0x08010561,
    0x0802CB8A,
    1116,
    0x0802CFE8,
    930,
)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("base_binary", type=Path)
    parser.add_argument("source", type=Path)
    parser.add_argument("binary", type=Path)
    parser.add_argument("disassembly", type=Path)
    args = parser.parse_args()

    source = args.source.read_text(encoding="ascii")
    if ".word 0x2000003c" not in source or ".word 0x08021ddd" not in source:
        raise RuntimeError("SysTick source literals are missing")

    base = args.base_binary.read_bytes()
    if hashlib.sha256(base).hexdigest() != BASE_SHA256:
        raise RuntimeError("base PendSV adapter hash mismatch")
    if len(base) != CODE_LENGTH + 4 * len(BASE_CONSTANTS):
        raise RuntimeError(f"unexpected base binary length {len(base)}")
    if struct.unpack_from("<11I", base, CODE_LENGTH) != BASE_CONSTANTS:
        raise RuntimeError("base PendSV adapter literal pool mismatch")

    derived = bytearray(base)
    struct.pack_into("<I", derived, CODE_LENGTH, EXPECTED_CONSTANTS[0])
    struct.pack_into("<I", derived, CODE_LENGTH + 4, EXPECTED_CONSTANTS[1])
    blob = bytes(derived)
    if len(blob) != CODE_LENGTH + 4 * len(EXPECTED_CONSTANTS):
        raise RuntimeError(f"unexpected binary length {len(blob)}")
    constants = struct.unpack_from("<11I", blob, CODE_LENGTH)
    if constants != EXPECTED_CONSTANTS:
        raise RuntimeError(f"literal pool mismatch: {constants!r}")

    if blob[:CODE_LENGTH] != base[:CODE_LENGTH]:
        raise RuntimeError("adapter executable bytes changed unexpectedly")

    args.binary.write_bytes(blob)
    digest = hashlib.sha256(blob).hexdigest()
    lines = [
        f"load_address=0x{LOAD_ADDRESS:08x}",
        f"code_length={CODE_LENGTH}",
        f"binary_length={len(blob)}",
        "derivation=verified PendSV adapter with vector-slot literals patched for SysTick",
        f"sha256={digest}",
        "",
    ]
    lines.append(f"executable_bytes={blob[:CODE_LENGTH].hex(' ')}")
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
