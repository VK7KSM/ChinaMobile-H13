#!/usr/bin/env python3
"""Assemble combined dual-counter SysTick stub for External DMR step3 (v002).

Load @ 0x20001700. On every SysTick:
  1) Absolute exit counter @ 0x20001788:
     if non-zero: decrement; when it reaches 0: clear bridge flag,
     restore SysTick vector to stock, write EXIT marker, tail to stock.
  2) Prep counter @ 0x20001784 (only if abs did not exit this tick):
     if non-zero: decrement; when it reaches 0: dataGate=1, write DGAT marker.
  3) Tail to stock SysTick.

Absolute exit never depends on prep completing. No bl/blx; EXC_RETURN stays in LR.
"""
from __future__ import annotations

import hashlib
import struct
import sys
from pathlib import Path

LOAD = 0x20001700
PREP_COUNTER = 0x20001784
ABS_COUNTER = 0x20001788
PREP_MARKER_ADDR = 0x20001780
EXIT_MARKER_ADDR = 0x2000178C
GATE_ADDR = 0x2000045C
BRIDGE_FLAG = 0x2000015C
VECTOR_SLOT = 0x2000003C
ORIGINAL_SYSTICK = 0x08021DDD
PREP_MARKER = 0x54414744  # DGAT
EXIT_MARKER = 0x54495845  # EXIT


def main() -> int:
    root = Path(__file__).resolve().parents[1]
    sys.path[:0] = [str(root / "asm_runtime_v038"), str(root / "capstone_runtime")]
    from capstone import CS_ARCH_ARM, CS_MODE_LITTLE_ENDIAN, CS_MODE_THUMB, Cs
    from keystone import KS_ARCH_ARM, KS_MODE_LITTLE_ENDIAN, KS_MODE_THUMB, Ks

    out_dir = Path(__file__).resolve().parent
    source = f"""
    // --- absolute exit counter ---
    ldr r0, abs_counter
    ldr r1, [r0]
    cmp r1, #0
    beq do_exit
    subs r1, #1
    str r1, [r0]
    cmp r1, #0
    beq do_exit
    // --- prep counter (abs still running) ---
    ldr r0, prep_counter
    ldr r1, [r0]
    cmp r1, #0
    beq chain_stock
    subs r1, #1
    str r1, [r0]
    cmp r1, #0
    bne chain_stock
    // prep fires once at zero
    ldr r0, gate_addr
    movs r1, #1
    strb r1, [r0]
    ldr r0, prep_marker_addr
    ldr r1, prep_marker_value
    str r1, [r0]
    b chain_stock
do_exit:
    ldr r0, bridge_flag
    movs r1, #0
    strb r1, [r0]
    ldr r0, vector_slot
    ldr r1, original_handler
    str r1, [r0]
    ldr r0, exit_marker_addr
    ldr r1, exit_marker_value
    str r1, [r0]
chain_stock:
    ldr r3, original_handler
    bx r3
    .align 2
abs_counter:
    .word {ABS_COUNTER}
prep_counter:
    .word {PREP_COUNTER}
gate_addr:
    .word {GATE_ADDR}
prep_marker_addr:
    .word {PREP_MARKER_ADDR}
prep_marker_value:
    .word {PREP_MARKER}
bridge_flag:
    .word {BRIDGE_FLAG}
vector_slot:
    .word {VECTOR_SLOT}
original_handler:
    .word {ORIGINAL_SYSTICK}
exit_marker_addr:
    .word {EXIT_MARKER_ADDR}
exit_marker_value:
    .word {EXIT_MARKER}
"""
    assembler = Ks(KS_ARCH_ARM, KS_MODE_THUMB | KS_MODE_LITTLE_ENDIAN)
    encoded, count = assembler.asm(source, addr=LOAD)
    blob = bytes(encoded)
    if len(blob) % 4 != 0:
        blob = blob + b"\x00" * (4 - (len(blob) % 4))

    disassembler = Cs(CS_ARCH_ARM, CS_MODE_THUMB | CS_MODE_LITTLE_ENDIAN)
    lit_count = 10
    lit_bytes = lit_count * 4
    code = blob[: len(blob) - lit_bytes]
    insns = list(disassembler.disasm(code, LOAD))
    mnemonics = [i.mnemonic for i in insns]
    if "bl" in mnemonics or "blx" in mnemonics:
        raise RuntimeError(f"must not call: {mnemonics}")
    if "strb" not in mnemonics:
        raise RuntimeError("missing strb")
    if mnemonics.count("bx") != 1:
        raise RuntimeError(f"expected one bx: {mnemonics}")

    constants = struct.unpack_from(f"<{lit_count}I", blob, len(blob) - lit_bytes)
    expected = (
        ABS_COUNTER,
        PREP_COUNTER,
        GATE_ADDR,
        PREP_MARKER_ADDR,
        PREP_MARKER,
        BRIDGE_FLAG,
        VECTOR_SLOT,
        ORIGINAL_SYSTICK,
        EXIT_MARKER_ADDR,
        EXIT_MARKER,
    )
    if constants != expected:
        raise RuntimeError(
            f"literals {[hex(c) for c in constants]} != {[hex(c) for c in expected]}"
        )

    bin_path = out_dir / "h13_mcu_combined_dual_counter_v002.bin"
    txt_path = out_dir / "h13_mcu_combined_dual_counter_v002_disasm.txt"
    bin_path.write_bytes(blob)
    digest = hashlib.sha256(blob).hexdigest()
    words = struct.unpack(f"<{len(blob)//4}I", blob)
    lines = [
        "variant=combined_dual_counter_v002",
        f"load_address=0x{LOAD:08x}",
        f"abs_counter=0x{ABS_COUNTER:08x}",
        f"prep_counter=0x{PREP_COUNTER:08x}",
        f"prep_marker=0x{PREP_MARKER_ADDR:08x}/0x{PREP_MARKER:08x}",
        f"exit_marker=0x{EXIT_MARKER_ADDR:08x}/0x{EXIT_MARKER:08x}",
        f"gate=0x{GATE_ADDR:08x}",
        f"bridge_flag=0x{BRIDGE_FLAG:08x}",
        f"length={len(blob)}",
        f"statement_count={count}",
        f"sha256={digest}",
        "no_bl_blx=true",
        "exc_return_in_lr=true",
        "absolute_exit_independent_of_prep=true",
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
