# -*- coding: utf-8 -*-
"""v3.50 栈安全 SysTick 包装：延时后一次 0x08010560(PCM,1290)，再链到退桥桩。"""
from __future__ import annotations

import hashlib
import struct
from pathlib import Path

OUT = Path(__file__).resolve().parent
STUB_ADDR = 0x20001800
TIMEOUT_ENTRY = 0x20001601
PCM_ADDR = 0x20003200
PCM_LEN = 1290
HPI_WRITE = 0x08010561
DELAY_ADDR = 0x20001870
DONE_ADDR = 0x20001874
MARKER_ADDR = 0x20001878
MARKER = 0x48504957


def hw(v: int) -> bytes:
    return struct.pack("<H", v)


def wd(v: int) -> bytes:
    return struct.pack("<I", v)


def main() -> None:
    code = bytearray()
    code += hw(0xB510)  # 00 push {r4, lr}
    code += hw(0x480D)  # 02 ldr r0, done_addr
    code += hw(0x6801)  # 04 ldr r1, [r0]
    code += hw(0x2900)  # 06 cmp r1, #0
    code += hw(0xD110)  # 08 bne skip @2C
    code += hw(0x490C)  # 0A ldr r1, delay_addr
    code += hw(0x680A)  # 0C ldr r2, [r1]
    code += hw(0x2A00)  # 0E cmp r2, #0
    code += hw(0xD003)  # 10 beq do_write @1A
    code += hw(0x3A01)  # 12 subs r2, #1
    code += hw(0x600A)  # 14 str r2, [r1]
    code += hw(0xE009)  # 16 b skip @2C
    code += hw(0xBF00)  # 18 nop
    code += hw(0x2201)  # 1A movs r2, #1
    code += hw(0x6002)  # 1C str r2, [r0]
    code += hw(0x4808)  # 1E ldr r0, pcm_addr
    code += hw(0x4908)  # 20 ldr r1, pcm_len
    code += hw(0x4A09)  # 22 ldr r2, hpi_fn
    code += hw(0x4790)  # 24 blx r2
    code += hw(0x4809)  # 26 ldr r0, marker_addr
    code += hw(0x4909)  # 28 ldr r1, marker_val
    code += hw(0x6001)  # 2A str r1, [r0]
    code += hw(0xBC0C)  # 2C pop {r2, r3}
    code += hw(0x4614)  # 2E mov r4, r2
    code += hw(0x469E)  # 30 mov lr, r3
    code += hw(0x4B08)  # 32 ldr r3, timeout_entry
    code += hw(0x4718)  # 34 bx r3
    code += hw(0xBF00)  # 36 nop
    assert len(code) == 0x38
    code += wd(DONE_ADDR)
    code += wd(DELAY_ADDR)
    code += wd(PCM_ADDR)
    code += wd(PCM_LEN)
    code += wd(HPI_WRITE)
    code += wd(MARKER_ADDR)
    code += wd(MARKER)
    code += wd(TIMEOUT_ENTRY)
    while len(code) < 96:
        code += b"\x00"
    assert len(code) == 96

    sha = hashlib.sha256(code).hexdigest()
    words = [struct.unpack_from("<I", code, i)[0] for i in range(0, 96, 4)]
    (OUT / "h13_mcu_stock_hpi_write_oneshot_v350.bin").write_bytes(code)
    (OUT / "h13_mcu_stock_hpi_write_oneshot_v350.sha256").write_text(
        sha + "\n", encoding="utf-8")
    java = ",".join("0x%08x" % w for w in words)
    print("SHA-256", sha)
    print("WORDS", java)
    print("LEN", len(code))


if __name__ == "__main__":
    main()
