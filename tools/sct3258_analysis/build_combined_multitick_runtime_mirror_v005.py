#!/usr/bin/env python3
"""v1.98 fullprep实时字段镜像桩：Cortex-M0，仅Thumb-16，离线生成。"""
from __future__ import annotations

import hashlib
import struct
import sys
from pathlib import Path


LOAD = 0x20002F00
MAIN_WINDOW_LENGTH = 0x150
PREP_MARKER = 0x20003050
PREP_COUNTER = 0x20003054
ABS_COUNTER = 0x20003058
EXIT_MARKER = 0x2000305C
PREP_PHASE = 0x20003060
PREP_CURSOR = 0x20003064
RUNTIME_MIRROR = 0x20003068
MIRROR_HELPER = 0x20003080
MIRROR_HELPER_LIMIT = 0x20003100

LIVE_SIGNAL_0D = 0x2000040D
LIVE_SIGNAL_0E = 0x2000040E
LIVE_SESSION_D3 = 0x20004CD3
GATE = 0x2000045C
STATE = 0x20000410
PTR_A = 0x20000414
PTR_B = 0x20000418
FLAG_1C = 0x2000041C
COUNT = 0x20000420
QUEUE_A = 0x20004B56
QUEUE_B = 0x20004BC6
LEN_A = 112
LEN_B = 168
CHUNK = 16
BRIDGE = 0x2000015C
VECTOR = 0x2000003C
STOCK = 0x08021DDD
DGAT = 0x54414744
EXITM = 0x54495845
MIRR = 0x5252494D


def assemble(source: str, address: int) -> bytes:
    from keystone import KS_ARCH_ARM, KS_MODE_LITTLE_ENDIAN, KS_MODE_THUMB, Ks

    assembler = Ks(KS_ARCH_ARM, KS_MODE_THUMB | KS_MODE_LITTLE_ENDIAN)
    encoded, _ = assembler.asm(source, addr=address)
    blob = bytes(encoded)
    if len(blob) % 4:
        blob += b"\x00" * (4 - len(blob) % 4)
    return blob


def validate_thumb16(code: bytes, address: int, disassembler) -> list:
    for offset in range(0, len(code) - 1, 2):
        halfword = struct.unpack_from("<H", code, offset)[0]
        if (halfword & 0xF800) in (0xE800, 0xF000, 0xF800):
            raise RuntimeError(
                f"发现Thumb-2指令：0x{address + offset:08x} halfword={halfword:04x}"
            )
    instructions = list(disassembler.disasm(code, address))
    mnemonics = [item.mnemonic for item in instructions]
    if "bl" in mnemonics or "blx" in mnemonics:
        raise RuntimeError("禁止bl/blx")
    for item in instructions:
        text = f"{item.mnemonic} {item.op_str}"
        if any(register in text for register in ("r4", "r5", "r6", "r7")):
            raise RuntimeError(f"禁止修改被调用者保存寄存器：{text}")
    return instructions


def main() -> int:
    root = Path(__file__).resolve().parents[1]
    sys.path[:0] = [
        str(root / "asm_runtime_v038"),
        str(root / "capstone_runtime"),
    ]
    from capstone import CS_ARCH_ARM, CS_MODE_LITTLE_ENDIAN, CS_MODE_THUMB, Cs

    main_source = f"""
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
    ldr r3, queue_a_addr
    add r3, r1
    movs r2, #0
    movs r0, #{CHUNK}
qa_loop:
    strb r2, [r3]
    adds r3, #1
    adds r1, #1
    ldr r2, len_a
    cmp r1, r2
    bhs qa_store
    subs r0, #1
    beq qa_store
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
    ldr r3, mirror_helper_entry
    bx r3

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
mirror_helper_entry: .word {MIRROR_HELPER | 1}
bridge_flag: .word {BRIDGE}
vector_slot: .word {VECTOR}
original_handler: .word {STOCK}
exit_marker_addr: .word {EXIT_MARKER}
exit_marker_value: .word {EXITM}
"""

    helper_source = f"""
    ldr r0, live_signal_0d
    ldrb r1, [r0]
    ldr r2, runtime_mirror
    strb r1, [r2, #0]
    adds r0, #1
    ldrb r1, [r0]
    strb r1, [r2, #1]

    ldr r0, live_session_d3
    ldrb r1, [r0]
    strb r1, [r2, #2]
    adds r0, #1
    ldrb r1, [r0]
    strb r1, [r2, #3]
    adds r0, #1
    ldrb r1, [r0]
    strb r1, [r2, #4]
    adds r0, #1
    ldrb r1, [r0]
    strb r1, [r2, #5]

    ldr r0, gate_addr
    ldrb r1, [r0]
    strb r1, [r2, #6]
    ldr r0, state_addr
    ldrb r1, [r0]
    strb r1, [r2, #7]
    ldr r1, mirror_marker_value
    str r1, [r2, #8]

    ldr r0, prep_marker_addr
    ldr r1, prep_marker_value
    str r1, [r0]
    ldr r0, prep_phase
    movs r1, #4
    str r1, [r0]
    ldr r3, original_handler
    bx r3
    .align 2
live_signal_0d: .word {LIVE_SIGNAL_0D}
runtime_mirror: .word {RUNTIME_MIRROR}
live_session_d3: .word {LIVE_SESSION_D3}
gate_addr: .word {GATE}
state_addr: .word {STATE}
mirror_marker_value: .word {MIRR}
prep_marker_addr: .word {PREP_MARKER}
prep_marker_value: .word {DGAT}
prep_phase: .word {PREP_PHASE}
original_handler: .word {STOCK}
"""

    disassembler = Cs(CS_ARCH_ARM, CS_MODE_THUMB | CS_MODE_LITTLE_ENDIAN)
    main_raw = assemble(main_source, LOAD)
    main_literal_count = 20
    main_code_length = len(main_raw) - main_literal_count * 4
    main_instructions = validate_thumb16(
        main_raw[:main_code_length], LOAD, disassembler
    )
    main_constants = struct.unpack_from(
        f"<{main_literal_count}I", main_raw, main_code_length
    )
    expected_main = (
        ABS_COUNTER,
        PREP_PHASE,
        PREP_COUNTER,
        GATE,
        STATE,
        PTR_A,
        PTR_B,
        FLAG_1C,
        COUNT,
        PREP_CURSOR,
        QUEUE_A,
        LEN_A,
        QUEUE_B,
        LEN_B,
        MIRROR_HELPER | 1,
        BRIDGE,
        VECTOR,
        STOCK,
        EXIT_MARKER,
        EXITM,
    )
    if main_constants != expected_main:
        raise RuntimeError([hex(item) for item in main_constants])
    if len(main_raw) > MAIN_WINDOW_LENGTH:
        raise RuntimeError(f"主桩超出336字节窗口：{len(main_raw)}")
    main_blob = main_raw + b"\x00" * (MAIN_WINDOW_LENGTH - len(main_raw))
    if LOAD + len(main_blob) != PREP_MARKER:
        raise RuntimeError("主桩窗口未精确结束于元数据起点")

    helper_blob = assemble(helper_source, MIRROR_HELPER)
    helper_literal_count = 10
    helper_code_length = len(helper_blob) - helper_literal_count * 4
    helper_instructions = validate_thumb16(
        helper_blob[:helper_code_length], MIRROR_HELPER, disassembler
    )
    helper_constants = struct.unpack_from(
        f"<{helper_literal_count}I", helper_blob, helper_code_length
    )
    expected_helper = (
        LIVE_SIGNAL_0D,
        RUNTIME_MIRROR,
        LIVE_SESSION_D3,
        GATE,
        STATE,
        MIRR,
        PREP_MARKER,
        DGAT,
        PREP_PHASE,
        STOCK,
    )
    if helper_constants != expected_helper:
        raise RuntimeError([hex(item) for item in helper_constants])
    if MIRROR_HELPER + len(helper_blob) > MIRROR_HELPER_LIMIT:
        raise RuntimeError(f"镜像辅助桩越界：{len(helper_blob)}")
    if RUNTIME_MIRROR + 12 > MIRROR_HELPER:
        raise RuntimeError("镜像数据窗与辅助桩重叠")

    output = Path(__file__).resolve().parent
    main_path = output / "h13_mcu_combined_multitick_runtime_mirror_v005.bin"
    helper_path = output / "h13_mcu_runtime_mirror_helper_v005.bin"
    disassembly_path = output / "h13_mcu_runtime_mirror_v005_disasm.txt"
    main_path.write_bytes(main_blob)
    helper_path.write_bytes(helper_blob)
    main_hash = hashlib.sha256(main_blob).hexdigest()
    helper_hash = hashlib.sha256(helper_blob).hexdigest()

    lines = [
        "variant=combined_multitick_runtime_mirror_v005_m0",
        f"main_address=0x{LOAD:08x} len={len(main_blob)} sha256={main_hash}",
        f"main_assembled_len={len(main_raw)} padded_len={len(main_blob)}",
        f"helper_address=0x{MIRROR_HELPER:08x} len={len(helper_blob)} sha256={helper_hash}",
        f"runtime_mirror_address=0x{RUNTIME_MIRROR:08x} len=12",
        "cortex_m0_thumb16_only=true",
        "bl_blx=false",
        "r4_r7_modified=false",
        "",
        "[main]",
    ]
    for instruction in main_instructions:
        lines.append(
            f"0x{instruction.address:08x}: {instruction.bytes.hex(' '):<18} "
            f"{instruction.mnemonic:<8} {instruction.op_str}".rstrip()
        )
    lines.append("")
    lines.append("[helper]")
    for instruction in helper_instructions:
        lines.append(
            f"0x{instruction.address:08x}: {instruction.bytes.hex(' '):<18} "
            f"{instruction.mnemonic:<8} {instruction.op_str}".rstrip()
        )
    lines.append("")
    lines.append(
        "main_words="
        + ",".join(
            f"0x{word:08x}"
            for word in struct.unpack(f"<{len(main_blob) // 4}I", main_blob)
        )
    )
    lines.append(
        "helper_words="
        + ",".join(
            f"0x{word:08x}"
            for word in struct.unpack(f"<{len(helper_blob) // 4}I", helper_blob)
        )
    )
    disassembly_path.write_text("\n".join(lines) + "\n", encoding="ascii")
    print(f"main len={len(main_blob)} sha256={main_hash}")
    print(f"helper len={len(helper_blob)} sha256={helper_hash}")
    print(f"disassembly={disassembly_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
