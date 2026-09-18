#!/usr/bin/env python3
"""生成H13外部DMR射频准备一次性桩，仅供已授权的低功率测试。"""

from __future__ import annotations

import hashlib
import re
import subprocess
import tempfile
from pathlib import Path


LOAD = 0x20003200
GPIO_PE_BRR = 0x48001028
GPIO_PF_BRR = 0x48001428
ACTIVE_GATE = 0x20000138
MARKER_ADDRESS = 0x200031C0
MARKER_VALUE = 0x50465221
VECTOR = 0x2000003C
STOCK_SYSTICK = 0x08021DDD


def main() -> int:
    project = Path(__file__).resolve().parents[4]
    llvm = (Path("C:/Dev/android-sdk/ndk/26.3.11579264/toolchains/llvm/"
                 "prebuilt/windows-x86_64/bin"))
    clang = llvm / "clang.exe"
    objcopy = llvm / "llvm-objcopy.exe"
    objdump = llvm / "llvm-objdump.exe"
    if not clang.is_file() or not objcopy.is_file() or not objdump.is_file():
        raise RuntimeError("找不到Android NDK LLVM工具链")

    source = f"""
.syntax unified
.thumb
.section .text
.global _start
_start:
    push {{r4, lr}}
    ldr r0, pe_brr
    movs r1, #0x40
    str r1, [r0]
    ldr r0, pf_brr
    movs r1, #0x08
    str r1, [r0]
    ldr r0, active_gate
    movs r1, #1
    str r1, [r0]
    ldr r0, marker_address
    ldr r1, marker_value
    str r1, [r0]
    pop {{r2, r3}}
    mov r4, r2
    mov lr, r3
    ldr r0, vector
    ldr r1, stock_systick
    str r1, [r0]
    ldr r3, stock_systick
    bx r3
    nop
    .align 2
pe_brr: .word {GPIO_PE_BRR}
pf_brr: .word {GPIO_PF_BRR}
active_gate: .word {ACTIVE_GATE}
marker_address: .word {MARKER_ADDRESS}
marker_value: .word {MARKER_VALUE}
vector: .word {VECTOR}
stock_systick: .word {STOCK_SYSTICK}
"""
    with tempfile.TemporaryDirectory(prefix="h13_rf_prep_oneshot_") as temp:
        work = Path(temp)
        asm = work / "stub.s"
        obj = work / "stub.o"
        binary = work / "stub.bin"
        asm.write_text(source, encoding="ascii")
        subprocess.run([str(clang), "-c", "-target", "armv6m-none-eabi",
                        "-mcpu=cortex-m0", "-mthumb", str(asm), "-o", str(obj)],
                       check=True)
        subprocess.run([str(objcopy), "-O", "binary", "--only-section=.text",
                        str(obj), str(binary)], check=True)
        disassembly = subprocess.run(
            [str(objdump), "-d", "--triple=thumbv6m-none-eabi",
             f"--adjust-vma=0x{LOAD:x}", str(obj)],
            check=True, text=True, capture_output=True).stdout
        blob = binary.read_bytes()

    if len(blob) % 4 != 0 or len(blob) > 0x80:
        raise RuntimeError(f"一次性桩长度异常：{len(blob)}")
    lowered = disassembly.lower()
    if re.search(r"\bblx?\b", lowered):
        raise RuntimeError("RF准备一次性桩禁止调用其他函数")
    if "push\t{r4, lr}" not in lowered or "pop\t{r2, r3}" not in lowered:
        raise RuntimeError("RF准备一次性桩缺少栈式EXC_RETURN保护")
    if "mov\tlr, r3" not in lowered:
        raise RuntimeError("RF准备一次性桩没有恢复EXC_RETURN")

    assets = Path(__file__).resolve().parents[1] / "app/src/main/assets/mcu"
    assets.mkdir(parents=True, exist_ok=True)
    output = assets / "h13_mcu_external_dmr_rf_prep_oneshot_v001.bin"
    output.write_bytes(blob)
    disassembly_path = Path(__file__).resolve().parent / "rf_prep_oneshot_v001_disasm.txt"
    digest = hashlib.sha256(blob).hexdigest().upper()
    disassembly_path.write_text(
        f"地址=0x{LOAD:08X}\n长度={len(blob)}\nSHA-256={digest}\n\n"
        + disassembly,
        encoding="utf-8")
    print(f"{output} len={len(blob)} sha256={digest}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
