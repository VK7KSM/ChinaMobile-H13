#!/usr/bin/env python3
"""构建并审计 v2.18 HOBIB SysTick 观察器。"""

from __future__ import annotations

import argparse
import hashlib
import struct
import sys
from pathlib import Path


LOAD_ADDRESS = 0x20001600
CODE_END = 0x200016A0
METADATA_BASE = 0x200016A0
METADATA_END = 0x200016C0
FORBIDDEN_LITERALS = {
    0x08010561,  # HPI 写函数
    0x0800FFC9,  # HPI 读函数
    0x2000015C,  # raw bridge 标志
}
EXPECTED_LITERALS = (
    0x200016A0,
    0x48000810,
    0x200016A4,
    0x200016A8,
    0x200016AC,
    0x200016B0,
    0x200016B4,
    0x200016B8,
    0xFFFFFFFF,
    0x2000003C,
    0x08021DDD,
    0x200016BC,
    0x44424F48,
)


def keystone_source(path: Path) -> str:
    kept = []
    for raw_line in path.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if not line or line.startswith((
                ".syntax", ".cpu", ".thumb", ".section", ".global",
                ".type", ".thumb_func", ".size", "@")):
            continue
        kept.append(raw_line)
    return "\n".join(kept)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("source", type=Path)
    parser.add_argument("binary", type=Path)
    parser.add_argument("disassembly", type=Path)
    args = parser.parse_args()

    runtime = Path(__file__).resolve().parents[1] / "runtime_v218"
    sys.path.insert(0, str(runtime))
    from capstone import CS_ARCH_ARM, CS_MODE_LITTLE_ENDIAN, CS_MODE_THUMB, Cs
    from keystone import KS_ARCH_ARM, KS_MODE_LITTLE_ENDIAN, KS_MODE_THUMB, Ks

    assembler = Ks(KS_ARCH_ARM, KS_MODE_THUMB | KS_MODE_LITTLE_ENDIAN)
    encoded, statement_count = assembler.asm(
        keystone_source(args.source), addr=LOAD_ADDRESS)
    blob = bytes(encoded)
    if len(blob) > CODE_END - LOAD_ADDRESS:
        raise RuntimeError(f"代码越过统计区：{len(blob)} 字节")
    if len(blob) % 4:
        raise RuntimeError(f"制品长度未按字对齐：{len(blob)}")

    literal_bytes = b"".join(struct.pack("<I", item) for item in EXPECTED_LITERALS)
    if not blob.endswith(literal_bytes):
        raise RuntimeError("文字池顺序或内容不符")
    words = set(struct.unpack(f"<{len(blob) // 4}I", blob))
    if words & FORBIDDEN_LITERALS:
        raise RuntimeError(f"出现禁止常量：{words & FORBIDDEN_LITERALS!r}")

    disassembler = Cs(CS_ARCH_ARM, CS_MODE_THUMB | CS_MODE_LITTLE_ENDIAN)
    code_length = len(blob) - len(literal_bytes)
    instructions = list(disassembler.disasm(blob[:code_length], LOAD_ADDRESS))
    mnemonics = [item.mnemonic for item in instructions]
    if any(item in mnemonics for item in ("bl", "blx", "strb", "strh")):
        raise RuntimeError("观察器包含函数调用或非32位写入")
    if mnemonics.count("bx") != 1 or instructions[-1].mnemonic not in ("bx", "nop"):
        raise RuntimeError("原 SysTick 链接出口不唯一")
    if "lsrs" not in mnemonics or "ands" not in mnemonics:
        raise RuntimeError("缺少 PF10 位提取")

    for output in (args.binary, args.disassembly):
        if output.exists():
            raise RuntimeError(f"拒绝覆盖既有制品：{output}")
    args.binary.write_bytes(blob)
    digest = hashlib.sha256(blob).hexdigest()
    lines = [
        f"装载地址=0x{LOAD_ADDRESS:08x}",
        f"代码字节数={code_length}",
        f"制品字节数={len(blob)}",
        f"统计区=0x{METADATA_BASE:08x}..0x{METADATA_END - 1:08x}",
        f"汇编语句数={statement_count}",
        f"SHA-256={digest}",
        "HPI读写调用=无",
        "raw bridge标志访问=无",
        "",
    ]
    lines.extend(
        f"0x{item.address:08x}: {item.bytes.hex(' '):<14} "
        f"{item.mnemonic:<7} {item.op_str}".rstrip()
        for item in instructions
    )
    lines.append("")
    lines.extend(
        f"文字池[{index}]=0x{value:08x}"
        for index, value in enumerate(EXPECTED_LITERALS)
    )
    args.disassembly.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"已生成 {args.binary}，{len(blob)} 字节，SHA-256={digest}")
    print("words=" + ",".join(f"0x{word:08x}" for word in
          struct.unpack(f"<{len(blob) // 4}I", blob)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
