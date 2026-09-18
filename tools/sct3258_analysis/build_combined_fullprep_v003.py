#!/usr/bin/env python3
"""v1.71 combined dual-counter + full 0x08024F42-equivalent mid-prep.

Layout:
  code @ 0x20001700 (up to ~0x200017FC)
  prep_marker DGAT @ 0x20001800
  prep_counter     @ 0x20001804
  abs_counter      @ 0x20001808
  exit_marker EXIT @ 0x2000180C

On each SysTick:
  1) abs counter: independent exit (flag=0, vector=stock, EXIT marker)
  2) prep counter: when hits 0, one-shot full local prep (no HPI wait):
       dataGate=1
       session state=2, ptrA=0, ptrB=0
       memset queueA 112 @ 0x20004B56
       session state=1, flag1c=0, count=0
       memset queueB 168 @ 0x20004BC6
       write DGAT
  3) chain stock SysTick

Uses no bl/blx (inline memset). EXC_RETURN stays in LR.
"""
from __future__ import annotations

import hashlib
import struct
import sys
from pathlib import Path

LOAD = 0x20001700
PREP_MARKER_ADDR = 0x20001800
PREP_COUNTER = 0x20001804
ABS_COUNTER = 0x20001808
EXIT_MARKER_ADDR = 0x2000180C
GATE = 0x2000045C
STATE = 0x20000410
PTR_A = 0x20000414
PTR_B = 0x20000418
FLAG_1C = 0x2000041C
COUNT = 0x20000420
QUEUE_A = 0x20004B56
QUEUE_B = 0x20004BC6
BRIDGE = 0x2000015C
VECTOR = 0x2000003C
STOCK = 0x08021DDD
DGAT = 0x54414744
EXITM = 0x54495845


def main() -> int:
    root = Path(__file__).resolve().parents[1]
    sys.path[:0] = [str(root / "asm_runtime_v038"), str(root / "capstone_runtime")]
    from capstone import CS_ARCH_ARM, CS_MODE_LITTLE_ENDIAN, CS_MODE_THUMB, Cs
    from keystone import KS_ARCH_ARM, KS_MODE_LITTLE_ENDIAN, KS_MODE_THUMB, Ks

    source = f"""
    ldr r0, abs_counter
    ldr r1, [r0]
    cmp r1, #0
    beq do_exit
    subs r1, #1
    str r1, [r0]
    cmp r1, #0
    beq do_exit

    ldr r0, prep_counter
    ldr r1, [r0]
    cmp r1, #0
    beq chain_stock
    subs r1, #1
    str r1, [r0]
    cmp r1, #0
    bne chain_stock

    // --- full local prep (fires once) ---
    ldr r0, gate_addr
    movs r1, #1
    strb r1, [r0]

    ldr r0, state_addr
    movs r1, #2
    strb r1, [r0]
    ldr r0, ptr_a_addr
    movs r1, #0
    str r1, [r0]
    ldr r0, ptr_b_addr
    str r1, [r0]

    ldr r0, queue_a_addr
    movs r1, #0
    ldr r2, len_a
clear_a:
    strb r1, [r0]
    adds r0, #1
    subs r2, #1
    bne clear_a

    ldr r0, state_addr
    movs r1, #1
    strb r1, [r0]
    ldr r0, flag_1c_addr
    movs r1, #0
    strb r1, [r0]
    ldr r0, count_addr
    str r1, [r0]

    ldr r0, queue_b_addr
    movs r1, #0
    ldr r2, len_b
clear_b:
    strb r1, [r0]
    adds r0, #1
    subs r2, #1
    bne clear_b

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
    .word {GATE}
state_addr:
    .word {STATE}
ptr_a_addr:
    .word {PTR_A}
ptr_b_addr:
    .word {PTR_B}
queue_a_addr:
    .word {QUEUE_A}
len_a:
    .word 112
flag_1c_addr:
    .word {FLAG_1C}
count_addr:
    .word {COUNT}
queue_b_addr:
    .word {QUEUE_B}
len_b:
    .word 168
prep_marker_addr:
    .word {PREP_MARKER_ADDR}
prep_marker_value:
    .word {DGAT}
bridge_flag:
    .word {BRIDGE}
vector_slot:
    .word {VECTOR}
original_handler:
    .word {STOCK}
exit_marker_addr:
    .word {EXIT_MARKER_ADDR}
exit_marker_value:
    .word {EXITM}
"""
    assembler = Ks(KS_ARCH_ARM, KS_MODE_THUMB | KS_MODE_LITTLE_ENDIAN)
    encoded, count = assembler.asm(source, addr=LOAD)
    blob = bytes(encoded)
    if len(blob) % 4:
        blob += b"\x00" * (4 - len(blob) % 4)
    if len(blob) > 0x100:
        raise RuntimeError(f"code too large: {len(blob)}")
    if LOAD + len(blob) > PREP_MARKER_ADDR:
        raise RuntimeError(f"code overlaps markers: end=0x{LOAD+len(blob):x}")

    dis = Cs(CS_ARCH_ARM, CS_MODE_THUMB | CS_MODE_LITTLE_ENDIAN)
    # find literal pool: last 19 words
    lit_n = 19
    lit_bytes = lit_n * 4
    code = blob[: len(blob) - lit_bytes]
    insns = list(dis.disasm(code, LOAD))
    mnems = [i.mnemonic for i in insns]
    if "bl" in mnems or "blx" in mnems:
        raise RuntimeError(f"forbidden call: {mnems}")
    if mnems.count("bx") != 1:
        raise RuntimeError(f"bx count: {mnems}")

    constants = struct.unpack_from(f"<{lit_n}I", blob, len(blob) - lit_bytes)
    expected = (
        ABS_COUNTER, PREP_COUNTER, GATE, STATE, PTR_A, PTR_B, QUEUE_A, 112,
        FLAG_1C, COUNT, QUEUE_B, 168, PREP_MARKER_ADDR, DGAT, BRIDGE, VECTOR,
        STOCK, EXIT_MARKER_ADDR, EXITM,
    )
    if constants != expected:
        raise RuntimeError(
            f"literals {[hex(c) for c in constants]} != {[hex(c) for c in expected]}"
        )

    out = Path(__file__).resolve().parent
    bin_path = out / "h13_mcu_combined_fullprep_v003.bin"
    txt_path = out / "h13_mcu_combined_fullprep_v003_disasm.txt"
    bin_path.write_bytes(blob)
    digest = hashlib.sha256(blob).hexdigest()
    words = struct.unpack(f"<{len(blob)//4}I", blob)
    lines = [
        "variant=combined_fullprep_v003",
        f"load_address=0x{LOAD:08x}",
        f"length={len(blob)}",
        f"sha256={digest}",
        f"prep_marker=0x{PREP_MARKER_ADDR:08x}",
        f"abs_counter=0x{ABS_COUNTER:08x}",
        "no_bl_blx=true",
        "full_08024F42_equivalent=true",
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
    lines.append("words=" + ",".join(f"0x{w:08x}" for w in words))
    txt_path.write_text("\n".join(lines) + "\n", encoding="ascii")
    print(f"wrote {bin_path.name} len={len(blob)} sha256={digest}")
    print("words=" + ",".join(f"0x{w:08x}" for w in words))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
