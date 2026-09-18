#!/usr/bin/env python3
"""Assemble mid-bridge delayed dataGate oneshot for External DMR step3.

Fires from SysTick after a host-loaded countdown. On zero:
  - stores 1 to dataGate @ 0x2000045C
  - points SysTick vector to UART-HPI timeout entry @ 0x20001601
  - writes marker @ 0x20001780
  - tails to stock SysTick

No bl/blx: EXC_RETURN stays in LR. Load @ 0x20001700.
"""
from __future__ import annotations

import hashlib
import struct
import sys
from pathlib import Path

LOAD = 0x20001700
COUNTER_ADDR = 0x20001784
MARKER_ADDR = 0x20001780
GATE_ADDR = 0x2000045C
VECTOR_SLOT = 0x2000003C
TIMEOUT_ENTRY = 0x20001601  # thumb
ORIGINAL_SYSTICK = 0x08021DDD
MARKER_VALUE = 0x54414744  # 'DGAT' LE


def main() -> int:
    root = Path(__file__).resolve().parents[1]
    sys.path[:0] = [str(root / "asm_runtime_v038"), str(root / "capstone_runtime")]
    from capstone import CS_ARCH_ARM, CS_MODE_LITTLE_ENDIAN, CS_MODE_THUMB, Cs
    from keystone import KS_ARCH_ARM, KS_MODE_LITTLE_ENDIAN, KS_MODE_THUMB, Ks

    out_dir = Path(__file__).resolve().parent
    source = f"""
    ldr r0, counter_addr
    ldr r1, [r0]
    cmp r1, #0
    beq do_work
    subs r1, #1
    str r1, [r0]
    ldr r3, original_handler
    bx r3
do_work:
    ldr r0, gate_addr
    movs r1, #1
    strb r1, [r0]
    ldr r0, vector_slot
    ldr r1, timeout_entry
    str r1, [r0]
    ldr r0, marker_addr
    ldr r1, marker_value
    str r1, [r0]
    ldr r3, original_handler
    bx r3
    .align 2
counter_addr:
    .word {COUNTER_ADDR}
gate_addr:
    .word {GATE_ADDR}
vector_slot:
    .word {VECTOR_SLOT}
timeout_entry:
    .word {TIMEOUT_ENTRY}
marker_addr:
    .word {MARKER_ADDR}
marker_value:
    .word {MARKER_VALUE}
original_handler:
    .word {ORIGINAL_SYSTICK}
"""
    assembler = Ks(KS_ARCH_ARM, KS_MODE_THUMB | KS_MODE_LITTLE_ENDIAN)
    encoded, count = assembler.asm(source, addr=LOAD)
    blob = bytes(encoded)
    if len(blob) % 4 != 0:
        blob = blob + b"\x00" * (4 - (len(blob) % 4))

    disassembler = Cs(CS_ARCH_ARM, CS_MODE_THUMB | CS_MODE_LITTLE_ENDIAN)
    # code ends before 7 literals
    lit_bytes = 7 * 4
    code = blob[: len(blob) - lit_bytes]
    insns = list(disassembler.disasm(code, LOAD))
    mnemonics = [i.mnemonic for i in insns]
    if "bl" in mnemonics or "blx" in mnemonics:
        raise RuntimeError(f"must not call: {mnemonics}")
    if mnemonics.count("bx") != 2:
        raise RuntimeError(f"expected two bx (countdown + work): {mnemonics}")
    if "strb" not in mnemonics:
        raise RuntimeError("missing strb for dataGate")

    constants = struct.unpack_from("<7I", blob, len(blob) - lit_bytes)
    expected = (
        COUNTER_ADDR,
        GATE_ADDR,
        VECTOR_SLOT,
        TIMEOUT_ENTRY,
        MARKER_ADDR,
        MARKER_VALUE,
        ORIGINAL_SYSTICK,
    )
    if constants != expected:
        raise RuntimeError(
            f"literals {tuple(hex(c) for c in constants)} != {tuple(hex(c) for c in expected)}"
        )

    bin_path = out_dir / "h13_mcu_datagate_delayed_oneshot_v001.bin"
    txt_path = out_dir / "h13_mcu_datagate_delayed_oneshot_v001_disasm.txt"
    # allow overwrite for iterative offline build
    bin_path.write_bytes(blob)
    digest = hashlib.sha256(blob).hexdigest()
    words = struct.unpack(f"<{len(blob)//4}I", blob)
    lines = [
        "variant=datagate_delayed_oneshot_v001",
        f"load_address=0x{LOAD:08x}",
        f"counter_address=0x{COUNTER_ADDR:08x}",
        f"marker_address=0x{MARKER_ADDR:08x}",
        f"marker_value=0x{MARKER_VALUE:08x}",
        f"gate_address=0x{GATE_ADDR:08x}",
        f"timeout_entry=0x{TIMEOUT_ENTRY:08x}",
        f"length={len(blob)}",
        f"statement_count={count}",
        f"sha256={digest}",
        "no_bl_blx=true",
        "exc_return_in_lr=true",
        "",
    ]
    for insn in insns:
        lines.append(
            f"0x{insn.address:08x}: {insn.bytes.hex(' '):<18} "
            f"{insn.mnemonic:<8} {insn.op_str}".rstrip()
        )
    lines.append("")
    for i, v in enumerate(constants):
        lines.append(f"literal[{i}]=0x{v:08x}")
    lines.append("")
    lines.append("words=" + ",".join(f"0x{w:08x}" for w in words))
    txt_path.write_text("\n".join(lines) + "\n", encoding="ascii")
    print(f"wrote {bin_path.name} len={len(blob)} sha256={digest}")
    print("words=" + ",".join(f"0x{w:08x}" for w in words))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
