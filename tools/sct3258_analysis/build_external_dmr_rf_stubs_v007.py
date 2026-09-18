#!/usr/bin/env python3
"""生产同构External DMR射频准备与关闭桩：Cortex-M0，离线生成。"""
from __future__ import annotations

import hashlib
import os
import re
import subprocess
import struct
import sys
import tempfile
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


def find_llvm_bin(root: Path) -> Path:
    candidates = []
    configured = os.environ.get("ANDROID_NDK_HOME")
    if configured:
        candidates.append(Path(configured) / "toolchains" / "llvm" /
                          "prebuilt" / "windows-x86_64" / "bin")
    candidates.extend([
        root / ".tools" / "android-sdk" / "ndk" / "26.3.11579264" /
        "toolchains" / "llvm" / "prebuilt" / "windows-x86_64" / "bin",
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
    with tempfile.TemporaryDirectory(prefix="h13_rf_v007_") as temp_name:
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
        raise RuntimeError("LLVM生成的桩不是四字节对齐")
    return blob, disassembly


def validate(disassembly: str, allow_call: bool) -> None:
    instruction_lines = [line.lower() for line in disassembly.splitlines()
                         if re.match(r"^\s*[0-9a-f]+:\s", line)]
    for line in instruction_lines:
        if re.search(r"\bblx?\b", line) and not allow_call:
            raise RuntimeError(f"准备桩禁止调用：{line.strip()}")
        if re.search(r"\br[567]\b", line):
            raise RuntimeError(f"禁止修改未保存寄存器：{line.strip()}")


def main() -> int:
    root = Path(__file__).resolve().parents[1]
    project_root = root.parents[1]
    llvm_bin = find_llvm_bin(project_root)

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
    nop
pe_brr: .word {GPIO_PE_BRR}
pf_brr: .word {GPIO_PF_BRR}
active_gate: .word {RF_ACTIVE_GATE}
mirror_helper: .word {MIRROR_HELPER}
"""
    prep, prep_disassembly = assemble(prep_source, RF_PREP, llvm_bin)

    off_source = f"""
    push {{r4, lr}}
    movs r0, #0
    ldr r1, stock_rf_off
    blx r1
    ldr r0, active_gate
    movs r1, #0
    str r1, [r0]
    pop {{r2, r3}}
    mov r4, r2
    mov lr, r3
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
    off, off_disassembly = assemble(off_source, RF_OFF, llvm_bin)

    if RF_PREP + len(prep) > RF_PREP_LIMIT:
        raise RuntimeError(f"RF准备桩越界：{len(prep)}")
    if RF_OFF + len(off) > RF_OFF_LIMIT:
        raise RuntimeError(f"RF关闭桩越界：{len(off)}")
    if hashlib.sha256(prep).hexdigest() != \
            "0599e611c3bfc5baea542ad6a73d3ca80cfdef218d47af4b9533d76a079318d1":
        raise RuntimeError("RF准备桩发生非预期变化")
    if hashlib.sha256(off).hexdigest() != \
            "b295c416579490a1a1bcd4d96632e28cc9bf683d49e21e75458b192f62dd8b59":
        raise RuntimeError("RF关闭桩不匹配v007冻结制品")

    prep_literal_count = 4
    off_literal_count = 6
    prep_code = prep[:-prep_literal_count * 4]
    off_code = off[:-off_literal_count * 4]
    validate(prep_disassembly, False)
    validate(off_disassembly, True)
    if struct.unpack_from("<4I", prep, len(prep_code)) != (
        GPIO_PE_BRR, GPIO_PF_BRR, RF_ACTIVE_GATE, MIRROR_HELPER
    ):
        raise RuntimeError("RF准备桩literal表不符")
    if struct.unpack_from("<6I", off, len(off_code)) != (
        STOCK_RF_OFF, RF_ACTIVE_GATE, VECTOR, STOCK_SYSTICK,
        RF_OFF_MARKER_ADDRESS, RF_OFF_MARKER
    ):
        raise RuntimeError("RF关闭桩literal表不符")
    if not re.search(r"\bblx\b", off_disassembly.lower()):
        raise RuntimeError("RF关闭桩没有调用stock完整关闭函数")
    if b"\x14\xbc\x96\x46" in off or b"\x96\x46" in off_code:
        raise RuntimeError("RF关闭桩包含已禁止的异常返回序列")
    if b"\x0c\xbc\x14\x46\x9e\x46" not in off_code:
        raise RuntimeError("RF关闭桩缺少正确的异常恢复序列")
    if struct.pack("<I", RF_ACTIVE_GATE) not in prep or struct.pack("<I", RF_ACTIVE_GATE) not in off:
        raise RuntimeError("RF active gate未同时出现在准备和关闭桩")

    artifacts = {
        "h13_mcu_combined_multitick_external_dmr_rf_v007.bin": rf_main,
        "h13_mcu_external_dmr_rf_prep_v007.bin": prep,
        "h13_mcu_external_dmr_rf_off_v007.bin": off,
    }
    for name, blob in artifacts.items():
        (tools / name).write_bytes(blob)

    lines = [
        "variant=external_dmr_production_rf_v007_m0",
        f"combined_address=0x{MAIN:08x} len={len(rf_main)} sha256={hashlib.sha256(rf_main).hexdigest()}",
        f"rf_prep_address=0x{RF_PREP:08x} len={len(prep)} sha256={hashlib.sha256(prep).hexdigest()}",
        f"rf_off_address=0x{RF_OFF:08x} len={len(off)} sha256={hashlib.sha256(off).hexdigest()}",
        f"rf_off_marker_address=0x{RF_OFF_MARKER_ADDRESS:08x} value=0x{RF_OFF_MARKER:08x}",
        "rf_prepare_sequence=PE6_low,PF3_low,0x20000138=1,mirror_helper",
        "rf_off_sequence=0x0801ff34(),0x20000138=0,restore_systick,marker",
        "",
        "[rf_prep]",
    ]
    lines.extend(prep_disassembly.strip().splitlines())
    lines.append("")
    lines.append("[rf_off]")
    lines.extend(off_disassembly.strip().splitlines())
    for label, blob in (("combined_words", rf_main), ("rf_prep_words", prep), ("rf_off_words", off)):
        lines.append("")
        lines.append(label + "=" + ",".join(f"0x{x:08x}" for x in struct.unpack(f"<{len(blob)//4}I", blob)))
    (tools / "h13_mcu_external_dmr_rf_v007_disasm.txt").write_text("\n".join(lines) + "\n", encoding="ascii")
    for name, blob in artifacts.items():
        print(f"{name} len={len(blob)} sha256={hashlib.sha256(blob).hexdigest()}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
