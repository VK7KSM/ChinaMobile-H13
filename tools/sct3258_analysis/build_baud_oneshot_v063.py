#!/usr/bin/env python3
"""Assemble one-shot SysTick stubs that call MCU 0x08010A00(baud)."""

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
    ldr r0, baud_value
    ldr r1, baud_fn
    blx r1
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
            raise RuntimeError(f"{name}: constant mismatch {constants!r}")

        code = blob[: len(blob) - 24]
        insns = list(disassembler.disasm(code, LOAD))
        mnemonics = [i.mnemonic for i in insns]
        if "blx" not in mnemonics or mnemonics.count("bx") != 1:
            raise RuntimeError(f"{name}: unexpected mnemonics {mnemonics!r}")

        bin_path = out_dir / f"h13_mcu_baud_{name}_oneshot_v063.bin"
        txt_path = out_dir / f"h13_mcu_baud_{name}_oneshot_v063_disasm.txt"
        if bin_path.exists() or txt_path.exists():
            raise RuntimeError(f"refusing to overwrite {bin_path} or {txt_path}")

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
        print(f"wrote {bin_path.name} len={len(blob)} sha256={digest}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
