#!/usr/bin/env python3
"""Assemble v0.66 baud one-shot stubs that preserve EXC_RETURN across blx."""

from __future__ import annotations

import hashlib
import struct
import sys
from pathlib import Path

LOAD = 0x20001700
MARKER_ADDR = 0x20001780
BAUD_SETTER = 0x08010A01
SYSTICK_SLOT = 0x2000003C
ORIGINAL_SYSTICK = 0x08021DDD

VARIANTS = {
    "set_230400": (230400, 0x44554142),  # 'BAUD'
    "restore_57600": (57600, 0x36373542),  # 'B576'
}


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

    for name, (baud, marker) in VARIANTS.items():
        source = f"""
    mov r2, lr
    ldr r0, baud_value
    ldr r1, baud_fn
    blx r1
    mov lr, r2
    ldr r0, vector_slot
    ldr r1, original_handler
    str r1, [r0]
    ldr r0, marker_address
    ldr r1, marker_value
    str r1, [r0]
    ldr r3, original_handler
    bx r3
    nop
    .align 2
baud_value:
    .word {baud}
baud_fn:
    .word {BAUD_SETTER}
vector_slot:
    .word {SYSTICK_SLOT}
original_handler:
    .word {ORIGINAL_SYSTICK}
marker_address:
    .word {MARKER_ADDR}
marker_value:
    .word {marker}
"""
        encoded, count = assembler.asm(source, addr=LOAD)
        blob = bytes(encoded)
        if len(blob) < 32 or (len(blob) % 2) != 0:
            raise RuntimeError(f"{name}: bad length {len(blob)}")

        constants = struct.unpack_from("<6I", blob, len(blob) - 24)
        expected = (
            baud,
            BAUD_SETTER,
            SYSTICK_SLOT,
            ORIGINAL_SYSTICK,
            MARKER_ADDR,
            marker,
        )
        if constants != expected:
            raise RuntimeError(f"{name}: constants {tuple(hex(c) for c in constants)}")

        code = blob[: len(blob) - 24]
        insns = list(disassembler.disasm(code, LOAD))
        mnemonics = [i.mnemonic for i in insns]
        # Must preserve LR across blx: mov r2,lr ... blx ... mov lr,r2 ... bx
        if mnemonics[:5] != ["mov", "ldr", "ldr", "blx", "mov"]:
            raise RuntimeError(f"{name}: expected LR save sequence, got {mnemonics!r}")
        if "lr" not in insns[0].op_str or "r2" not in insns[0].op_str:
            raise RuntimeError(f"{name}: first insn must save LR: {insns[0].op_str}")
        if "lr" not in insns[4].op_str or "r2" not in insns[4].op_str:
            raise RuntimeError(f"{name}: fifth insn must restore LR: {insns[4].op_str}")
        if mnemonics.count("bx") != 1 or "blx" not in mnemonics:
            raise RuntimeError(f"{name}: unexpected branch pattern {mnemonics!r}")

        bin_path = out_dir / f"h13_mcu_baud_{name}_oneshot_v066.bin"
        txt_path = out_dir / f"h13_mcu_baud_{name}_oneshot_v066_disasm.txt"
        if bin_path.exists() or txt_path.exists():
            raise RuntimeError(f"refusing to overwrite {bin_path}")

        bin_path.write_bytes(blob)
        digest = hashlib.sha256(blob).hexdigest()
        lines = [
            f"variant={name}",
            f"load_address=0x{LOAD:08x}",
            f"marker_address=0x{MARKER_ADDR:08x}",
            f"baud={baud}",
            f"length={len(blob)}",
            f"statement_count={count}",
            f"sha256={digest}",
            "lr_preservation=mov r2,lr ; blx ; mov lr,r2",
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
