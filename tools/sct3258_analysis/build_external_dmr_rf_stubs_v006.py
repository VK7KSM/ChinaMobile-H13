#!/usr/bin/env python3
"""生产同构External DMR射频准备与关闭桩：Cortex-M0，离线生成。"""
from __future__ import annotations

import hashlib
import struct
import sys
from pathlib import Path


MAIN = 0x20002F00
MAIN_LENGTH = 0x150
OLD_HELPER = 0x20003081
RF_PREP = 0x20003100
RF_PREP_LIMIT = 0x20003140
RF_OFF = 0x20003140
RF_OFF_LIMIT = 0x200031C0
RF_OFF_MARKER_ADDRESS = 0x200031C0

GPIO_PE_BRR = 0x48001028
GPIO_PF_BRR = 0x48001428
RF_ACTIVE_GATE = 0x20000138
MIRROR_HELPER = 0x20003081
VECTOR = 0x2000003C
STOCK_SYSTICK = 0x08021DDD
STOCK_RF_OFF = 0x0801FF35
RF_OFF_MARKER = 0x21464F52  # 'ROF!'


def assemble(source: str, address: int) -> bytes:
    from keystone import KS_ARCH_ARM, KS_MODE_LITTLE_ENDIAN, KS_MODE_THUMB, Ks

    assembler = Ks(KS_ARCH_ARM, KS_MODE_THUMB | KS_MODE_LITTLE_ENDIAN)
    encoded, _ = assembler.asm(source, addr=address)
    blob = bytes(encoded)
    if len(blob) % 4:
        blob += b"\x00" * (4 - len(blob) % 4)
    return blob


def validate(code: bytes, address: int, disassembler, allow_call: bool) -> list:
    instructions = list(disassembler.disasm(code, address))
    for item in instructions:
        if item.mnemonic in ("bl", "blx") and not allow_call:
            raise RuntimeError(f"准备桩禁止调用：{item.mnemonic} {item.op_str}")
        text = f"{item.mnemonic} {item.op_str}"
        if any(reg in text for reg in ("r5", "r6", "r7")):
            raise RuntimeError(f"禁止修改未保存寄存器：{text}")
    return instructions


def main() -> int:
    root = Path(__file__).resolve().parents[1]
    sys.path[:0] = [str(root / "asm_runtime_v038"), str(root / "capstone_runtime")]
    from capstone import CS_ARCH_ARM, CS_MODE_LITTLE_ENDIAN, CS_MODE_THUMB, Cs

    tools = Path(__file__).resolve().parent
    original = (tools / "h13_mcu_combined_multitick_runtime_mirror_v005.bin").read_bytes()
    if len(original) != MAIN_LENGTH:
        raise RuntimeError("v005 combined桩长度不是336字节")
    old_literal = struct.pack("<I", OLD_HELPER)
    if original.count(old_literal) != 1:
        raise RuntimeError("v005 helper入口literal不是唯一值")
    rf_main = original.replace(old_literal, struct.pack("<I", RF_PREP | 1))

    prep_source = f"""
    ldr r0, pe_brr
    movs r1, #0x40
    str r1, [r0]
    ldr r0, pf_brr
    movs r1, #0x08
    str r1, [r0]
    ldr r0, active_gate
    movs r1, #1
    str r1, [r0]
    ldr r3, mirror_helper
    bx r3
    .align 2
pe_brr: .word {GPIO_PE_BRR}
pf_brr: .word {GPIO_PF_BRR}
active_gate: .word {RF_ACTIVE_GATE}
mirror_helper: .word {MIRROR_HELPER}
"""
    prep = assemble(prep_source, RF_PREP)

    off_source = f"""
    push {{r4, lr}}
    movs r0, #0
    ldr r1, stock_rf_off
    blx r1
    ldr r0, active_gate
    movs r1, #0
    str r1, [r0]
    pop {{r2, r4}}
    mov lr, r2
    ldr r0, vector_slot
    ldr r1, stock_systick
    str r1, [r0]
    ldr r0, marker_addr
    ldr r1, marker_value
    str r1, [r0]
    ldr r3, stock_systick
    bx r3
    .align 2
stock_rf_off: .word {STOCK_RF_OFF}
active_gate: .word {RF_ACTIVE_GATE}
vector_slot: .word {VECTOR}
stock_systick: .word {STOCK_SYSTICK}
marker_addr: .word {RF_OFF_MARKER_ADDRESS}
marker_value: .word {RF_OFF_MARKER}
"""
    off = assemble(off_source, RF_OFF)

    if RF_PREP + len(prep) > RF_PREP_LIMIT:
        raise RuntimeError(f"RF准备桩越界：{len(prep)}")
    if RF_OFF + len(off) > RF_OFF_LIMIT:
        raise RuntimeError(f"RF关闭桩越界：{len(off)}")

    dis = Cs(CS_ARCH_ARM, CS_MODE_THUMB | CS_MODE_LITTLE_ENDIAN)
    prep_literal_count = 4
    off_literal_count = 6
    prep_code = prep[:-prep_literal_count * 4]
    off_code = off[:-off_literal_count * 4]
    prep_ins = validate(prep_code, RF_PREP, dis, False)
    off_ins = validate(off_code, RF_OFF, dis, True)
    if struct.unpack_from("<4I", prep, len(prep_code)) != (
        GPIO_PE_BRR, GPIO_PF_BRR, RF_ACTIVE_GATE, MIRROR_HELPER
    ):
        raise RuntimeError("RF准备桩literal表不符")
    if struct.unpack_from("<6I", off, len(off_code)) != (
        STOCK_RF_OFF, RF_ACTIVE_GATE, VECTOR, STOCK_SYSTICK,
        RF_OFF_MARKER_ADDRESS, RF_OFF_MARKER
    ):
        raise RuntimeError("RF关闭桩literal表不符")
    if not any(item.mnemonic == "blx" for item in off_ins):
        raise RuntimeError("RF关闭桩没有调用stock完整关闭函数")
    if struct.pack("<I", RF_ACTIVE_GATE) not in prep or struct.pack("<I", RF_ACTIVE_GATE) not in off:
        raise RuntimeError("RF active gate未同时出现在准备和关闭桩")

    artifacts = {
        "h13_mcu_combined_multitick_external_dmr_rf_v006.bin": rf_main,
        "h13_mcu_external_dmr_rf_prep_v006.bin": prep,
        "h13_mcu_external_dmr_rf_off_v006.bin": off,
    }
    for name, blob in artifacts.items():
        (tools / name).write_bytes(blob)

    lines = [
        "variant=external_dmr_production_rf_v006_m0",
        f"combined_address=0x{MAIN:08x} len={len(rf_main)} sha256={hashlib.sha256(rf_main).hexdigest()}",
        f"rf_prep_address=0x{RF_PREP:08x} len={len(prep)} sha256={hashlib.sha256(prep).hexdigest()}",
        f"rf_off_address=0x{RF_OFF:08x} len={len(off)} sha256={hashlib.sha256(off).hexdigest()}",
        f"rf_off_marker_address=0x{RF_OFF_MARKER_ADDRESS:08x} value=0x{RF_OFF_MARKER:08x}",
        "rf_prepare_sequence=PE6_low,PF3_low,0x20000138=1,mirror_helper",
        "rf_off_sequence=0x0801ff34(),0x20000138=0,restore_systick,marker",
        "",
        "[rf_prep]",
    ]
    for item in prep_ins:
        lines.append(f"0x{item.address:08x}: {item.bytes.hex(' '):<16} {item.mnemonic:<8} {item.op_str}".rstrip())
    lines.append("")
    lines.append("[rf_off]")
    for item in off_ins:
        lines.append(f"0x{item.address:08x}: {item.bytes.hex(' '):<16} {item.mnemonic:<8} {item.op_str}".rstrip())
    for label, blob in (("combined_words", rf_main), ("rf_prep_words", prep), ("rf_off_words", off)):
        lines.append("")
        lines.append(label + "=" + ",".join(f"0x{x:08x}" for x in struct.unpack(f"<{len(blob)//4}I", blob)))
    (tools / "h13_mcu_external_dmr_rf_v006_disasm.txt").write_text("\n".join(lines) + "\n", encoding="ascii")
    for name, blob in artifacts.items():
        print(f"{name} len={len(blob)} sha256={hashlib.sha256(blob).hexdigest()}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
