#!/usr/bin/env python3
"""Assemble v0.68 baud one-shot stubs that preserve EXC_RETURN on the stack.

v0.66 saved EXC_RETURN in r2 across blx 0x08010A00. That is wrong: the callee
immediately calls 0x08006194 which starts with movs r2,#0 (AAPCS volatile), so
mov lr,r2 restores a destroyed value. v0.63-v0.67 stubs using r2 are FORBIDDEN.

Correct pattern (8-byte aligned stack frame):
  push {r4, lr}      ; r4 dummy/callee-save + EXC_RETURN
  blx  baud_setter
  pop  {r2, r3}      ; r2=saved r4, r3=EXC_RETURN
  mov  r4, r2
  mov  lr, r3
"""

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

# keep_57600 first: call setter with 57600 (no line-rate change) to prove ISR
# call/return before any 230400 experiment.
VARIANTS = {
    "keep_57600": (57600, 0x5045454B),  # 'KEEP'
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
    push {{r4, lr}}
    ldr r0, baud_value
    ldr r1, baud_fn
    blx r1
    pop {{r2, r3}}
    mov r4, r2
    mov lr, r3
    ldr r0, vector_slot
    ldr r1, original_handler
    str r1, [r0]
    ldr r0, marker_address
    ldr r1, marker_value
    str r1, [r0]
    ldr r3, original_handler
    bx r3
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
        if len(blob) < 40 or (len(blob) % 2) != 0:
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
            raise RuntimeError(
                f"{name}: constants {tuple(hex(c) for c in constants)}"
            )

        code = blob[: len(blob) - 24]
        insns = list(disassembler.disasm(code, LOAD))
        mnemonics = [i.mnemonic for i in insns]
        # Must be push {r4,lr} ... blx ... pop {r2,r3} ... mov r4,r2 ... mov lr,r3
        if mnemonics[0] != "push":
            raise RuntimeError(f"{name}: first insn must be push, got {mnemonics!r}")
        if "r4" not in insns[0].op_str or "lr" not in insns[0].op_str:
            raise RuntimeError(f"{name}: push must include r4,lr: {insns[0].op_str}")
        if "blx" not in mnemonics:
            raise RuntimeError(f"{name}: missing blx: {mnemonics!r}")
        blx_i = mnemonics.index("blx")
        if mnemonics[blx_i + 1] != "pop":
            raise RuntimeError(f"{name}: blx must be followed by pop: {mnemonics!r}")
        if "r2" not in insns[blx_i + 1].op_str or "r3" not in insns[blx_i + 1].op_str:
            raise RuntimeError(
                f"{name}: pop must restore into r2,r3: {insns[blx_i + 1].op_str}"
            )
        # Find mov lr, r3
        lr_restore = [
            i for i in insns
            if i.mnemonic == "mov" and "lr" in i.op_str and "r3" in i.op_str
        ]
        if not lr_restore:
            raise RuntimeError(f"{name}: missing mov lr,r3")
        if mnemonics.count("bx") != 1:
            raise RuntimeError(f"{name}: unexpected bx count {mnemonics!r}")

        # Reject v0.66 pattern: mov r2,lr as first instruction (0x4672)
        if blob[0] == 0x72 and blob[1] == 0x46:
            raise RuntimeError(f"{name}: looks like forbidden v0.66 r2-save stub")

        bin_path = out_dir / f"h13_mcu_baud_{name}_oneshot_v068.bin"
        txt_path = out_dir / f"h13_mcu_baud_{name}_oneshot_v068_disasm.txt"
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
            "exc_return_save=push {r4,lr} / pop {r2,r3} / mov r4,r2 / mov lr,r3",
            "forbidden_v066_r2_save=true",
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
