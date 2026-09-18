#!/usr/bin/env python3
"""Assemble v0.68 marker-only SysTick one-shot (no baud HAL call)."""

from __future__ import annotations

import hashlib
import struct
import sys
from pathlib import Path

LOAD = 0x20001700
MARKER_ADDR = 0x20001780
SYSTICK_SLOT = 0x2000003C
ORIGINAL_SYSTICK = 0x08021DDD
MARKER_VALUE = 0x4B52414D  # 'MARK'


def main() -> int:
    sys.path[:0] = [
        str(Path(__file__).resolve().parents[1] / "asm_runtime_v038"),
        str(Path(__file__).resolve().parents[1] / "capstone_runtime"),
    ]
    from capstone import CS_ARCH_ARM, CS_MODE_LITTLE_ENDIAN, CS_MODE_THUMB, Cs
    from keystone import KS_ARCH_ARM, KS_MODE_LITTLE_ENDIAN, KS_MODE_THUMB, Ks

    out_dir = Path(__file__).resolve().parent
    assembler = Ks(KS_ARCH_ARM, KS_MODE_THUMB | KS_MODE_LITTLE_ENDIAN)
    disassembler = Cs(CS_ARCH_ARM, CS_MODE_THUMB | CS_MODE_LITTLE_ENDIAN)

    source = f"""
    ldr r0, vector_slot
    ldr r1, original_handler
    str r1, [r0]
    ldr r0, marker_address
    ldr r1, marker_value
    str r1, [r0]
    ldr r3, original_handler
    bx r3
    .align 2
vector_slot:
    .word {SYSTICK_SLOT}
original_handler:
    .word {ORIGINAL_SYSTICK}
marker_address:
    .word {MARKER_ADDR}
marker_value:
    .word {MARKER_VALUE}
"""
    encoded, count = assembler.asm(source, addr=LOAD)
    blob = bytes(encoded)
    if len(blob) < 24 or (len(blob) % 2) != 0:
        raise RuntimeError(f"bad length {len(blob)}")

    constants = struct.unpack_from("<4I", blob, len(blob) - 16)
    expected = (SYSTICK_SLOT, ORIGINAL_SYSTICK, MARKER_ADDR, MARKER_VALUE)
    if constants != expected:
        raise RuntimeError(f"constants {tuple(hex(c) for c in constants)}")

    code = blob[: len(blob) - 16]
    insns = list(disassembler.disasm(code, LOAD))
    mnemonics = [i.mnemonic for i in insns]
    if "blx" in mnemonics or "bl" in mnemonics:
        raise RuntimeError(f"marker-only stub must not call: {mnemonics!r}")
    if mnemonics.count("bx") != 1:
        raise RuntimeError(f"expected single bx: {mnemonics!r}")

    bin_path = out_dir / "h13_mcu_marker_only_oneshot_v068.bin"
    txt_path = out_dir / "h13_mcu_marker_only_oneshot_v068_disasm.txt"
    if bin_path.exists() or txt_path.exists():
        raise RuntimeError(f"refusing to overwrite {bin_path}")

    bin_path.write_bytes(blob)
    digest = hashlib.sha256(blob).hexdigest()
    lines = [
        "variant=marker_only",
        f"load_address=0x{LOAD:08x}",
        f"marker_address=0x{MARKER_ADDR:08x}",
        f"marker_value=0x{MARKER_VALUE:08x}",
        f"length={len(blob)}",
        f"statement_count={count}",
        f"sha256={digest}",
        "no_baud_hal=true",
        "",
    ]
    for insn in insns:
        lines.append(
            f"0x{insn.address:08x}: {insn.bytes.hex(' '):<18} "
            f"{insn.mnemonic:<8} {insn.op_str}".rstrip()
        )
    lines.append("")
    for index, value in enumerate(constants):
        lines.append(f"literal[{index}]=0x{value:08x}")
    txt_path.write_text("\n".join(lines) + "\n", encoding="ascii")
    words = struct.unpack(f"<{len(blob) // 4}I", blob)
    print(f"wrote {bin_path.name} len={len(blob)} sha256={digest}")
    print("words=" + ",".join(f"0x{w:08x}" for w in words))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
