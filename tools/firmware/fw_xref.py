#!/usr/bin/env python3
"""固件静态分析小工具：按字符串/地址找引用、找函数边界、反汇编。

为什么要有它：2.9.19 用模拟器截序列，前提是先知道**从哪个入口开始跑**。
发射侧的入口是顺着 `DMR_CALL_START` 的调用者找到的，接收侧同理，但接收侧
没有 `SCT_Dsp_Send_*` 那样的自带符号，只能靠调试字符串反查。

约定：全片镜像 `Module_current_0.3.66_full_flash_256k.bin` 映射在
`0x08000000`，应用镜像位于片内偏移 `0x6000`（2.9.19 的地址修正）。

用法：
    python fw_xref.py --str "receive voice frame start"   # 找字符串并列出引用者
    python fw_xref.py --xref 0x08023f30                   # 找哪些函数引用此地址
    python fw_xref.py --dis 0x08023f00 --count 60         # 反汇编
    python fw_xref.py --func 0x08023f30                   # 找包含该地址的函数并反汇编全部
    python fw_xref.py --callers 0x0801c264                # 找 BL 到该入口的位置
"""
from __future__ import annotations

import argparse
import re
import struct
import sys
from pathlib import Path

BASE = 0x08000000
DEFAULT_IMAGE = (Path(__file__).resolve().parents[2]
                 / "firmware" / "mcu"
                 / "Module_current_0.3.66_full_flash_256k.bin")


def load(path: Path) -> bytes:
    return path.read_bytes()


def find_strings(flash: bytes, needle: str) -> list[tuple[int, str]]:
    out = []
    pat = re.escape(needle.encode())
    for m in re.finditer(pat, flash):
        # 向前回到字符串起点
        s = m.start()
        while s > 0 and 0x20 <= flash[s - 1] <= 0x7E:
            s -= 1
        e = m.end()
        while e < len(flash) and 0x20 <= flash[e] <= 0x7E:
            e += 1
        out.append((BASE + s, flash[s:e].decode(errors="replace")))
    return out


def xrefs(flash: bytes, addr: int) -> list[int]:
    """找字面量池里出现该地址的位置（四字节对齐）。"""
    needle = struct.pack("<I", addr)
    hits = []
    start = 0
    while True:
        i = flash.find(needle, start)
        if i < 0:
            break
        if i % 4 == 0:
            hits.append(BASE + i)
        start = i + 1
    return hits


def adr_refs(flash: bytes, addr: int) -> list[int]:
    """找 Thumb 的 ADR（ADD rN, PC, #imm8*4，编码 1010 0ddd iiiiiiii）指向该地址的指令。

    固件里的调试字符串几乎全是紧跟在函数体后面、由 ADR 取址的，
    字面量池扫描找不到它们——这个函数补上这一半。
    """
    out = []
    for i in range(0, len(flash) - 2, 2):
        hw = struct.unpack_from("<H", flash, i)[0]
        if (hw & 0xF800) != 0xA000:
            continue
        if (hw >> 8) & 0x07 == 0 and (hw & 0x0800):
            continue
        pc = BASE + i + 4
        target = (pc & ~3) + (hw & 0xFF) * 4
        if target == addr:
            out.append(BASE + i)
    return out


def func_start(flash: bytes, addr: int, limit: int = 0x600) -> int | None:
    """从 addr 向前找最外层的 push {..., lr}（0xB5xx）作为函数入口。

    先找到第一个 push，再继续向前找更外层的 push；遇到 pop{..pc}/bx lr
    说明已经跨到上一个函数，停。
    """
    off = addr - BASE
    i = off & ~1
    first = None
    while i > off - limit and i >= 2:
        i -= 2
        hw = struct.unpack_from("<H", flash, i)[0]
        if (hw & 0xFF00) == 0xB500:
            first = i
            break
    if first is None:
        return None
    entry = first
    k = first
    while k > first - limit and k >= 2:
        k -= 2
        hw = struct.unpack_from("<H", flash, k)[0]
        if (hw & 0xFF00) == 0xB500:
            entry = k
        if (hw & 0xFF00) == 0xBD00 or hw == 0x4770:
            break
    return BASE + entry


def callers(flash: bytes, target: int) -> list[int]:
    """扫描全片找 BL <target>（Thumb 32 位 BL 编码）。"""
    out = []
    t = target & ~1
    for i in range(0, len(flash) - 4, 2):
        hw1 = struct.unpack_from("<H", flash, i)[0]
        if (hw1 & 0xF800) != 0xF000:
            continue
        hw2 = struct.unpack_from("<H", flash, i + 2)[0]
        if (hw2 & 0xD000) != 0xD000:
            continue
        s = (hw1 >> 10) & 1
        imm10 = hw1 & 0x3FF
        j1 = (hw2 >> 13) & 1
        j2 = (hw2 >> 11) & 1
        imm11 = hw2 & 0x7FF
        i1 = 1 - (j1 ^ s)
        i2 = 1 - (j2 ^ s)
        imm = (s << 24) | (i1 << 23) | (i2 << 22) | (imm10 << 12) | (imm11 << 1)
        if s:
            imm -= 1 << 25
        dest = BASE + i + 4 + imm
        if dest == t:
            out.append(BASE + i)
    return out


def disasm(flash: bytes, addr: int, count: int) -> list[str]:
    import capstone
    md = capstone.Cs(capstone.CS_ARCH_ARM, capstone.CS_MODE_THUMB)
    md.detail = False
    off = (addr - BASE) & ~1
    data = flash[off:off + count * 4 + 16]
    lines = []
    for ins in md.disasm(data, BASE + off, count=count):
        lines.append("  %08x  %-22s %s %s"
                     % (ins.address, ins.bytes.hex(), ins.mnemonic, ins.op_str))
    return lines


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--image", type=Path, default=DEFAULT_IMAGE)
    ap.add_argument("--str", dest="s")
    ap.add_argument("--xref", type=lambda x: int(x, 0))
    ap.add_argument("--callers", type=lambda x: int(x, 0))
    ap.add_argument("--dis", type=lambda x: int(x, 0))
    ap.add_argument("--func", type=lambda x: int(x, 0))
    ap.add_argument("--count", type=int, default=48)
    a = ap.parse_args()

    flash = load(a.image)

    if a.s:
        hits = find_strings(flash, a.s)
        if not hits:
            print("未找到该字符串")
            return 1
        for addr, text in hits:
            print("字符串 %08x  %r" % (addr, text))
            refs = xrefs(flash, addr) + adr_refs(flash, addr)
            refs.sort()
            if not refs:
                print("   （既无字面量引用也无 ADR 引用）")
            for r in refs:
                fs = func_start(flash, r)
                print("   引用于 %08x   所属函数入口 %s"
                      % (r, "%08x" % fs if fs else "未定位"))
        return 0

    if a.xref is not None:
        for r in sorted(xrefs(flash, a.xref) + adr_refs(flash, a.xref)):
            fs = func_start(flash, r)
            print("%08x   所属函数入口 %s" % (r, "%08x" % fs if fs else "未定位"))
        return 0

    if a.callers is not None:
        for c in callers(flash, a.callers):
            fs = func_start(flash, c)
            print("BL 于 %08x   所属函数入口 %s"
                  % (c, "%08x" % fs if fs else "未定位"))
        return 0

    if a.func is not None:
        fs = func_start(flash, a.func)
        if fs is None:
            print("未定位函数入口")
            return 1
        print("函数入口 %08x" % fs)
        for line in disasm(flash, fs, a.count):
            print(line)
        return 0

    if a.dis is not None:
        for line in disasm(flash, a.dis, a.count):
            print(line)
        return 0

    ap.print_help()
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
