#!/usr/bin/env python3
"""v008 链上RF准备桩：按 0x0800845C + 0x0801966A/6E/74 写时隙门，禁止 32 位 str。"""
from __future__ import annotations

import hashlib
import os
import re
import subprocess
import struct
import tempfile
from pathlib import Path


RF_PREP = 0x20003100
RF_PREP_LIMIT = 0x20003140
GPIO_PE_BRR = 0x48001028
GPIO_PF_BRR = 0x48001428
RF_ACTIVE_GATE = 0x20000138
RF_SLOT_COUNT = 0x2000013A
RF_COMPLETION = 0x2000013C
MIRROR_HELPER = 0x20003081
SLOT_COUNT_VALUE = 16
V007_PREP_SHA256 = "0599e611c3bfc5baea542ad6a73d3ca80cfdef218d47af4b9533d76a079318d1"


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
    with tempfile.TemporaryDirectory(prefix="h13_rf_v008_") as temp_name:
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


def main() -> int:
    project_root = Path(__file__).resolve().parents[2]
    llvm_bin = find_llvm_bin(project_root)
    tools = Path(__file__).resolve().parent

    prep_source = f"""
    ldr r0, pe_brr
    movs r1, #0x40
    str r1, [r0]
    ldr r0, pf_brr
    movs r1, #0x08
    str r1, [r0]
    ldr r0, completion
    movs r1, #0
    strb r1, [r0]
    ldr r0, slot_count
    movs r1, #{SLOT_COUNT_VALUE}
    strh r1, [r0]
    ldr r0, active_gate
    movs r1, #1
    strb r1, [r0]
    ldr r3, mirror_helper
    bx r3
    nop
pe_brr: .word {GPIO_PE_BRR}
pf_brr: .word {GPIO_PF_BRR}
completion: .word {RF_COMPLETION}
slot_count: .word {RF_SLOT_COUNT}
active_gate: .word {RF_ACTIVE_GATE}
mirror_helper: .word {MIRROR_HELPER}
"""
    prep, prep_disassembly = assemble(prep_source, RF_PREP, llvm_bin)
    if RF_PREP + len(prep) > RF_PREP_LIMIT:
        raise RuntimeError(f"RF准备桩越界：{len(prep)}")
    digest = hashlib.sha256(prep).hexdigest()
    if digest == V007_PREP_SHA256:
        raise RuntimeError("v008不得与v007空转桩同哈希")
    lowered = prep_disassembly.lower()
    if re.search(r"\bblx?\b", lowered):
        raise RuntimeError("链上准备桩禁止调用")
    if "strb" not in lowered or "strh" not in lowered:
        raise RuntimeError("缺少strb/strh时隙门写")
    if struct.unpack_from("<6I", prep, len(prep) - 24) != (
        GPIO_PE_BRR, GPIO_PF_BRR, RF_COMPLETION, RF_SLOT_COUNT,
        RF_ACTIVE_GATE, MIRROR_HELPER
    ):
        raise RuntimeError("RF准备桩literal表不符")
    if struct.pack("<I", 1) == prep[len(prep)-24:]:
        raise RuntimeError("literal表解析失败")

    name = "h13_mcu_external_dmr_rf_prep_v008.bin"
    (tools / name).write_bytes(prep)
    lines = [
        "variant=external_dmr_slot_gate_rf_prep_v008_m0",
        f"rf_prep_address=0x{RF_PREP:08x} len={len(prep)} sha256={digest}",
        "rf_prepare_sequence=PE6_low,PF3_low,completion=0,count=16,strb_gate,mirror_helper",
        "stock_count_helper=0x0800845C",
        "stock_gate_store=strb_0x08019676",
        "",
        "[rf_prep]",
    ]
    lines.extend(prep_disassembly.strip().splitlines())
    lines.append("")
    lines.append("rf_prep_words=" + ",".join(
        f"0x{x:08x}" for x in struct.unpack(f"<{len(prep)//4}I", prep)))
    (tools / "h13_mcu_external_dmr_rf_prep_v008_disasm.txt").write_text(
        "\n".join(lines) + "\n", encoding="ascii")
    print(f"{name} len={len(prep)} sha256={digest}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
