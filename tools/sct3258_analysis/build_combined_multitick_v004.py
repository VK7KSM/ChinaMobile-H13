#!/usr/bin/env python3
"""v1.73 multi-tick fullprep - Cortex-M0 safe (16-bit Thumb only) v004b."""
from __future__ import annotations
import hashlib, struct, sys
from pathlib import Path

LOAD = 0x20001700
PREP_MARKER = 0x20001900
PREP_COUNTER = 0x20001904
ABS_COUNTER = 0x20001908
EXIT_MARKER = 0x2000190C
PREP_PHASE = 0x20001910
PREP_CURSOR = 0x20001914
GATE, STATE, PTR_A, PTR_B = 0x2000045C, 0x20000410, 0x20000414, 0x20000418
FLAG_1C, COUNT = 0x2000041C, 0x20000420
QUEUE_A, QUEUE_B, LEN_A, LEN_B, CHUNK = 0x20004B56, 0x20004BC6, 112, 168, 16
BRIDGE, VECTOR, STOCK = 0x2000015C, 0x2000003C, 0x08021DDD
DGAT, EXITM = 0x54414744, 0x54495845

def main() -> int:
    root = Path(__file__).resolve().parents[1]
    sys.path[:0] = [str(root / "asm_runtime_v038"), str(root / "capstone_runtime")]
    from capstone import CS_ARCH_ARM, CS_MODE_LITTLE_ENDIAN, CS_MODE_THUMB, Cs
    from keystone import KS_ARCH_ARM, KS_MODE_LITTLE_ENDIAN, KS_MODE_THUMB, Ks

    # M0: only low regs, 16-bit ops. add r3, r1 means r3+=r1.
    source = f"""
    ldr r0, abs_counter
    ldr r1, [r0]
    cmp r1, #0
    beq do_exit
    subs r1, #1
    str r1, [r0]
    cmp r1, #0
    beq do_exit

    ldr r0, prep_phase
    ldr r1, [r0]
    cmp r1, #4
    beq chain_stock
    cmp r1, #0
    beq do_delay
    cmp r1, #1
    beq do_scalars
    cmp r1, #2
    beq do_queue_a
    cmp r1, #3
    beq do_queue_b
    b chain_stock

do_delay:
    ldr r0, prep_counter
    ldr r1, [r0]
    cmp r1, #0
    beq to_phase1
    subs r1, #1
    str r1, [r0]
    b chain_stock
to_phase1:
    ldr r0, prep_phase
    movs r1, #1
    str r1, [r0]

do_scalars:
    ldr r0, gate_addr
    movs r1, #1
    strb r1, [r0]
    ldr r0, state_addr
    movs r1, #2
    strb r1, [r0]
    movs r1, #0
    ldr r0, ptr_a_addr
    str r1, [r0]
    ldr r0, ptr_b_addr
    str r1, [r0]
    ldr r0, state_addr
    movs r1, #1
    strb r1, [r0]
    ldr r0, flag_1c_addr
    movs r1, #0
    strb r1, [r0]
    ldr r0, count_addr
    str r1, [r0]
    ldr r0, prep_cursor
    str r1, [r0]
    ldr r0, prep_phase
    movs r1, #2
    str r1, [r0]
    b chain_stock

do_queue_a:
    ldr r0, prep_cursor
    ldr r1, [r0]
    ldr r2, len_a
    cmp r1, r2
    bhs qa_done
    // r4 not available without push - use r3 only
    ldr r3, queue_a_addr
    add r3, r1
    movs r2, #0
    movs r0, #{CHUNK}
qa_loop:
    strb r2, [r3]
    adds r3, #1
    adds r1, #1
    // compare cursor to len
    ldr r2, len_a
    cmp r1, r2
    bhs qa_store
    subs r0, #1
    beq qa_store
    // continue; r3 already advanced
    movs r2, #0
    b qa_loop
qa_store:
    ldr r0, prep_cursor
    str r1, [r0]
    b chain_stock
qa_done:
    movs r1, #0
    ldr r0, prep_cursor
    str r1, [r0]
    ldr r0, prep_phase
    movs r1, #3
    str r1, [r0]
    b chain_stock

do_queue_b:
    ldr r0, prep_cursor
    ldr r1, [r0]
    ldr r2, len_b
    cmp r1, r2
    bhs qb_done
    ldr r3, queue_b_addr
    add r3, r1
    movs r2, #0
    movs r0, #{CHUNK}
qb_loop:
    strb r2, [r3]
    adds r3, #1
    adds r1, #1
    ldr r2, len_b
    cmp r1, r2
    bhs qb_store
    subs r0, #1
    beq qb_store
    movs r2, #0
    b qb_loop
qb_store:
    ldr r0, prep_cursor
    str r1, [r0]
    b chain_stock
qb_done:
    ldr r0, prep_marker_addr
    ldr r1, prep_marker_value
    str r1, [r0]
    ldr r0, prep_phase
    movs r1, #4
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
abs_counter: .word {ABS_COUNTER}
prep_phase: .word {PREP_PHASE}
prep_counter: .word {PREP_COUNTER}
gate_addr: .word {GATE}
state_addr: .word {STATE}
ptr_a_addr: .word {PTR_A}
ptr_b_addr: .word {PTR_B}
flag_1c_addr: .word {FLAG_1C}
count_addr: .word {COUNT}
prep_cursor: .word {PREP_CURSOR}
queue_a_addr: .word {QUEUE_A}
len_a: .word {LEN_A}
queue_b_addr: .word {QUEUE_B}
len_b: .word {LEN_B}
prep_marker_addr: .word {PREP_MARKER}
prep_marker_value: .word {DGAT}
bridge_flag: .word {BRIDGE}
vector_slot: .word {VECTOR}
original_handler: .word {STOCK}
exit_marker_addr: .word {EXIT_MARKER}
exit_marker_value: .word {EXITM}
"""
    ks = Ks(KS_ARCH_ARM, KS_MODE_THUMB | KS_MODE_LITTLE_ENDIAN)
    encoded, n = ks.asm(source, addr=LOAD)
    blob = bytes(encoded)
    if len(blob) % 4:
        blob += b"\x00" * (4 - len(blob) % 4)
    if LOAD + len(blob) > PREP_MARKER:
        raise RuntimeError(f"overlap 0x{LOAD+len(blob):x}")

    # forbid 32-bit thumb
    i = 0
    while i < len(blob) - 2:
        h = struct.unpack_from("<H", blob, i)[0]
        if (h & 0xF800) in (0xE800, 0xF000, 0xF800):
            raise RuntimeError(f"Thumb-2 at 0x{LOAD+i:x}: {h:04x}")
        i += 2

    dis = Cs(CS_ARCH_ARM, CS_MODE_THUMB | CS_MODE_LITTLE_ENDIAN)
    lit_n = 21
    code = blob[: len(blob) - lit_n * 4]
    insns = list(dis.disasm(code, LOAD))
    mnems = [x.mnemonic for x in insns]
    if "bl" in mnems or "blx" in mnems:
        raise RuntimeError(mnems)
    constants = struct.unpack_from(f"<{lit_n}I", blob, len(blob) - lit_n * 4)
    expected = (
        ABS_COUNTER, PREP_PHASE, PREP_COUNTER, GATE, STATE, PTR_A, PTR_B,
        FLAG_1C, COUNT, PREP_CURSOR, QUEUE_A, LEN_A, QUEUE_B, LEN_B,
        PREP_MARKER, DGAT, BRIDGE, VECTOR, STOCK, EXIT_MARKER, EXITM,
    )
    if constants != expected:
        raise RuntimeError([hex(c) for c in constants])

    out = Path(__file__).resolve().parent
    bp = out / "h13_mcu_combined_multitick_v004.bin"
    tp = out / "h13_mcu_combined_multitick_v004_disasm.txt"
    bp.write_bytes(blob)
    digest = hashlib.sha256(blob).hexdigest()
    words = struct.unpack(f"<{len(blob)//4}I", blob)
    lines = [f"variant=combined_multitick_v004b_m0", f"len={len(blob)} sha256={digest}",
             "cortex_m0_thumb16_only=true", ""]
    for insn in insns:
        lines.append(f"0x{insn.address:08x}: {insn.bytes.hex(' '):<18} {insn.mnemonic:<8} {insn.op_str}".rstrip())
    lines.append("words=" + ",".join(f"0x{w:08x}" for w in words))
    tp.write_text("\n".join(lines)+"\n", encoding="ascii")
    print(f"wrote {bp.name} len={len(blob)} sha256={digest}")
    print("words=" + ",".join(f"0x{w:08x}" for w in words))
    return 0

if __name__ == "__main__":
    raise SystemExit(main())
