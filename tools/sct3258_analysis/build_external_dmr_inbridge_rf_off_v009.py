#!/usr/bin/env python3
"""v009：桥内可调用的 0x0801FF34，不恢复 SysTick。

合成桩 chain_stock 改为 BX 调度桩。调度桩仅在 prep_phase==4 时递减
0x20003074；减到 0 则栈安全调用 0x0801FF34、strb 清 gate、写关闭标记，
再 BX 原 SysTick。禁止写 0x2000003C。
"""
from __future__ import annotations

import hashlib
import os
import re
import subprocess
import struct
import tempfile
from pathlib import Path


MAIN = 0x20002F00
MAIN_LENGTH = 0x150
OLD_HELPER = 0x20003081
RF_PREP_ENTRY = 0x20003101
SCHEDULER = 0x20003200
SCHEDULER_LIMIT = 0x20003280
HOLD_COUNTER = 0x20003074
PREP_PHASE = 0x20003060
STOCK_RF_OFF = 0x0801FF35
RF_ACTIVE_GATE = 0x20000138
VECTOR = 0x2000003C
STOCK_SYSTICK = 0x08021DDD
RF_OFF_MARKER_ADDRESS = 0x200031C0
RF_OFF_MARKER = 0x21464F52

ABS_COUNTER = 0x20003058
PREP_COUNTER = 0x20003054
GATE = 0x2000045C
STATE = 0x20000410
PTR_A = 0x20000414
PTR_B = 0x20000418
FLAG_1C = 0x2000041C
COUNT = 0x20000420
PREP_CURSOR = 0x20003064
QUEUE_A = 0x20004B56
QUEUE_B = 0x20004BC6
LEN_A = 112
LEN_B = 168
CHUNK = 16
BRIDGE = 0x2000015C
EXIT_MARKER = 0x2000305C
MIRROR_HELPER = 0x20003080
EXITM = 0x54495845


def find_llvm_bin(root: Path) -> Path:
    candidates = []
    configured = os.environ.get("ANDROID_NDK_HOME")
    if configured:
        candidates.append(Path(configured) / "toolchains" / "llvm"
                          / "prebuilt" / "windows-x86_64" / "bin")
    candidates.extend([
        root / ".tools" / "android-sdk" / "ndk" / "26.3.11579264"
        / "toolchains" / "llvm" / "prebuilt" / "windows-x86_64" / "bin",
        Path("C:/Dev/android-sdk/ndk/26.3.11579264/toolchains/llvm/prebuilt/"
             "windows-x86_64/bin"),
    ])
    for candidate in candidates:
        if (candidate / "clang.exe").is_file() and \
                (candidate / "llvm-objcopy.exe").is_file() and \
                (candidate / "llvm-objdump.exe").is_file():
            return candidate
    raise RuntimeError("找不到Android NDK LLVM工具链")


def assemble(source: str, address: int, llvm_bin: Path) -> tuple[bytes, str]:
    assembly = ".syntax unified\n.thumb\n.section .text\n" + source
    with tempfile.TemporaryDirectory(prefix="h13_rf_v009_") as temp_name:
        temp = Path(temp_name)
        source_path = temp / "stub.s"
        object_path = temp / "stub.o"
        binary_path = temp / "stub.bin"
        source_path.write_text(assembly, encoding="ascii")
        subprocess.run([
            str(llvm_bin / "clang.exe"), "-c", "-target",
            "armv6m-none-eabi", "-mcpu=cortex-m0", "-mthumb",
            str(source_path), "-o", str(object_path),
        ], check=True)
        subprocess.run([
            str(llvm_bin / "llvm-objcopy.exe"), "-O", "binary",
            "--only-section=.text", str(object_path), str(binary_path),
        ], check=True)
        disassembly = subprocess.run([
            str(llvm_bin / "llvm-objdump.exe"), "-d",
            "--triple=thumbv6m-none-eabi", f"--adjust-vma=0x{address:x}",
            str(object_path),
        ], check=True, text=True, capture_output=True).stdout
        disassembly = disassembly.replace(str(object_path), "stub.o")
        blob = binary_path.read_bytes()
    if len(blob) % 4:
        blob += b"\x00" * (4 - len(blob) % 4)
    return blob, disassembly


def reject_thumb2_and_bl(disassembly: str, allow_blx: bool) -> None:
    for line in disassembly.splitlines():
        lowered = line.lower()
        if not re.match(r"^\s*[0-9a-f]+:\s", lowered):
            continue
        if re.search(r"\bbl\b", lowered):
            raise RuntimeError(f"禁止bl：{line.strip()}")
        if re.search(r"\bblx\b", lowered) and not allow_blx:
            raise RuntimeError(f"禁止blx：{line.strip()}")


def main() -> int:
    tools = Path(__file__).resolve().parent
    project_root = tools.parents[1]
    llvm_bin = find_llvm_bin(project_root)

    original = (tools / "h13_mcu_combined_multitick_runtime_mirror_v005.bin").read_bytes()
    if len(original) != MAIN_LENGTH:
        raise RuntimeError("v005 combined桩长度不是336字节")

    combined_source = f"""
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
    ldr r3, scheduler_entry
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
mirror_helper_entry: .word {RF_PREP_ENTRY}
bridge_flag: .word {BRIDGE}
vector_slot: .word {VECTOR}
original_handler: .word {STOCK_SYSTICK}
exit_marker_addr: .word {EXIT_MARKER}
exit_marker_value: .word {EXITM}
scheduler_entry: .word {SCHEDULER | 1}
"""
    combined, combined_disasm = assemble(combined_source, MAIN, llvm_bin)
    reject_thumb2_and_bl(combined_disasm, False)
    if len(combined) > MAIN_LENGTH:
        raise RuntimeError(f"合成桩越界：{len(combined)}")
    combined = combined + b"\x00" * (MAIN_LENGTH - len(combined))
    if struct.pack("<I", OLD_HELPER) in combined:
        raise RuntimeError("合成桩仍指向镜像助手")
    if struct.pack("<I", RF_PREP_ENTRY) not in combined:
        raise RuntimeError("合成桩未指向链上RF准备")
    if struct.pack("<I", SCHEDULER | 1) not in combined:
        raise RuntimeError("合成桩未指向桥内关断调度")
    if combined.count(struct.pack("<I", STOCK_SYSTICK)) != 1:
        raise RuntimeError("原SysTick literal必须只用于退桥写回向量")

    scheduler_source = f"""
    ldr r0, prep_phase
    ldr r1, [r0]
    cmp r1, #4
    bne go_stock
    ldr r0, hold_counter
    ldr r1, [r0]
    cmp r1, #0
    beq go_stock
    subs r1, #1
    str r1, [r0]
    cmp r1, #0
    bne go_stock
    push {{r4, lr}}
    movs r0, #0
    ldr r1, stock_rf_off
    blx r1
    ldr r0, active_gate
    movs r1, #0
    strb r1, [r0]
    pop {{r2, r3}}
    mov r4, r2
    mov lr, r3
    ldr r0, marker_addr
    ldr r1, marker_value
    str r1, [r0]
go_stock:
    ldr r3, stock_systick
    bx r3
    nop
    .align 2
prep_phase: .word {PREP_PHASE}
hold_counter: .word {HOLD_COUNTER}
stock_rf_off: .word {STOCK_RF_OFF}
active_gate: .word {RF_ACTIVE_GATE}
marker_addr: .word {RF_OFF_MARKER_ADDRESS}
marker_value: .word {RF_OFF_MARKER}
stock_systick: .word {STOCK_SYSTICK}
"""
    scheduler, scheduler_disasm = assemble(scheduler_source, SCHEDULER, llvm_bin)
    if SCHEDULER + len(scheduler) > SCHEDULER_LIMIT:
        raise RuntimeError(f"桥内关断调度桩越界：{len(scheduler)}")
    if struct.pack("<I", VECTOR) in scheduler:
        raise RuntimeError("桥内关断不得写SysTick向量")
    if struct.pack("<I", STOCK_RF_OFF) not in scheduler:
        raise RuntimeError("桥内关断未调用0x0801FF34")
    if b"\x10\xb5" not in scheduler[:4] and scheduler[0:2] != b"\x00\x48":
        pass
    if not re.search(r"\bblx\b", scheduler_disasm.lower()):
        raise RuntimeError("桥内关断没有blx 0x0801FF34")
    if re.search(r"\bbl\b", scheduler_disasm.lower()):
        raise RuntimeError("桥内关断禁止bl")

    tools_out = {
        "h13_mcu_combined_multitick_external_dmr_rf_v009.bin": combined,
        "h13_mcu_external_dmr_rf_off_keep_systick_v001.bin": scheduler,
    }
    for name, blob in tools_out.items():
        (tools / name).write_bytes(blob)

    lines = [
        "variant=external_dmr_inbridge_rf_off_v009_m0",
        f"combined_address=0x{MAIN:08x} len={len(combined)} sha256={hashlib.sha256(combined).hexdigest()}",
        f"scheduler_address=0x{SCHEDULER:08x} len={len(scheduler)} sha256={hashlib.sha256(scheduler).hexdigest()}",
        f"hold_counter=0x{HOLD_COUNTER:08x}",
        "rf_off_inbridge_sequence=phase4_hold,0x0801ff34(),strb_gate=0,marker,bx_stock",
        "systick_vector_written=false",
        "",
        "[combined]",
    ]
    lines.extend(combined_disasm.strip().splitlines())
    lines.append("")
    lines.append("[scheduler]")
    lines.extend(scheduler_disasm.strip().splitlines())
    (tools / "h13_mcu_external_dmr_rf_v009_disasm.txt").write_text(
        "\n".join(lines) + "\n", encoding="ascii")
    for name, blob in tools_out.items():
        print(f"{name} len={len(blob)} sha256={hashlib.sha256(blob).hexdigest()}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
