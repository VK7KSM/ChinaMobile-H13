#!/usr/bin/env python3
"""Read-only ELF32/ARM inspection helper for the H13 dmr_upgrade binary."""

from __future__ import annotations

import argparse
import lzma
import re
import struct
import sys
from pathlib import Path


ELF_HEADER = struct.Struct("<16sHHIIIIIHHHHHH")
SECTION_HEADER = struct.Struct("<IIIIIIIIII")
SYMBOL = struct.Struct("<IIIBBH")
REL = struct.Struct("<II")


def c_string(data: bytes, offset: int) -> str:
    end = data.find(b"\0", offset)
    if end < 0:
        end = len(data)
    return data[offset:end].decode("utf-8", errors="replace")


class Elf32:
    def __init__(self, path: Path | str, data: bytes | None = None):
        self.path = path
        self.data = Path(path).read_bytes() if data is None else data
        header = ELF_HEADER.unpack_from(self.data)
        ident = header[0]
        if ident[:4] != b"\x7fELF" or ident[4:6] != b"\x01\x01":
            raise SystemExit("expected a little-endian ELF32 image")
        (
            _, self.etype, self.machine, self.version, self.entry,
            self.phoff, self.shoff, self.flags, self.ehsize, self.phentsize,
            self.phnum, self.shentsize, self.shnum, self.shstrndx,
        ) = header
        raw_sections = [
            SECTION_HEADER.unpack_from(self.data, self.shoff + index * self.shentsize)
            for index in range(self.shnum)
        ]
        shstr = raw_sections[self.shstrndx]
        names = self.data[shstr[4] : shstr[4] + shstr[5]]
        self.sections = []
        for index, values in enumerate(raw_sections):
            section = {
                "index": index,
                "name": c_string(names, values[0]),
                "type": values[1],
                "flags": values[2],
                "addr": values[3],
                "offset": values[4],
                "size": values[5],
                "link": values[6],
                "info": values[7],
                "align": values[8],
                "entsize": values[9],
            }
            self.sections.append(section)
        self.by_name = {item["name"]: item for item in self.sections}

    def section_data(self, section: dict) -> bytes:
        start = section["offset"]
        return self.data[start : start + section["size"]]

    def address_to_offset(self, address: int) -> int | None:
        for section in self.sections:
            if section["type"] == 8:  # SHT_NOBITS
                continue
            start = section["addr"]
            if start <= address < start + section["size"]:
                return section["offset"] + address - start
        return None

    def symbols(self) -> list[dict]:
        result = []
        for table in self.sections:
            if table["type"] not in (2, 11) or not table["entsize"]:
                continue
            strings = self.sections[table["link"]]
            str_data = self.section_data(strings)
            for offset in range(0, table["size"], table["entsize"]):
                values = SYMBOL.unpack_from(self.data, table["offset"] + offset)
                result.append({
                    "table": table["name"],
                    "name": c_string(str_data, values[0]),
                    "value": values[1],
                    "size": values[2],
                    "info": values[3],
                    "other": values[4],
                    "shndx": values[5],
                })
        return result

    def relocations(self) -> list[dict]:
        symbols_by_section = {}
        for table in self.sections:
            if table["type"] not in (2, 11) or not table["entsize"]:
                continue
            strings = self.sections[table["link"]]
            str_data = self.section_data(strings)
            items = []
            for offset in range(0, table["size"], table["entsize"]):
                values = SYMBOL.unpack_from(self.data, table["offset"] + offset)
                items.append(c_string(str_data, values[0]))
            symbols_by_section[table["index"]] = items

        result = []
        for table in self.sections:
            if table["type"] != 9 or table["entsize"] != REL.size:
                continue
            names = symbols_by_section.get(table["link"], [])
            for offset in range(0, table["size"], table["entsize"]):
                address, info = REL.unpack_from(self.data, table["offset"] + offset)
                symbol_index = info >> 8
                result.append({
                    "table": table["name"],
                    "address": address,
                    "type": info & 0xFF,
                    "symbol": names[symbol_index] if symbol_index < len(names) else "",
                })
        return result

    def plt_symbols(self) -> dict[int, str]:
        plt = self.by_name.get(".plt")
        rel_plt = self.by_name.get(".rel.plt")
        if plt is None or rel_plt is None:
            return {}
        items = [item for item in self.relocations() if item["table"] == ".rel.plt"]
        # Android ARM32 uses a 20-byte PLT0 followed by 12-byte entries.
        return {
            plt["addr"] + 20 + index * 12: item["symbol"]
            for index, item in enumerate(items)
        }


def printable_strings(elf: Elf32, minimum: int = 4) -> list[tuple[int, int, str]]:
    result = []
    pattern = re.compile(rb"[ -~]{%d,}" % minimum)
    for section in elf.sections:
        if not section["size"] or section["type"] == 8:
            continue
        for match in pattern.finditer(elf.section_data(section)):
            result.append((
                section["addr"] + match.start(),
                section["offset"] + match.start(),
                match.group().decode("ascii"),
            ))
    return result


def disassemble(elf: Elf32, start: int, size: int) -> None:
    for directory in ("pydeps", "capstone_runtime"):
        bundled_capstone = Path(__file__).with_name(directory)
        if bundled_capstone.is_dir():
            sys.path.append(str(bundled_capstone))
    try:
        from capstone import CS_ARCH_ARM, CS_MODE_LITTLE_ENDIAN, CS_MODE_THUMB, Cs
    except ImportError as error:
        raise SystemExit(
            "Capstone is required for --disassemble; add its package directory to PYTHONPATH"
        ) from error

    file_offset = elf.address_to_offset(start)
    if file_offset is None:
        raise SystemExit(f"address 0x{start:x} is not file-backed")
    code = elf.data[file_offset : file_offset + size]
    engine = Cs(CS_ARCH_ARM, CS_MODE_THUMB | CS_MODE_LITTLE_ENDIAN)
    plt = elf.plt_symbols()
    relocations = {item["address"]: item["symbol"] for item in elf.relocations()}
    strings = {address: value for address, _, value in printable_strings(elf)}
    pending_literals: dict[str, int] = {}
    for instruction in engine.disasm(code, start):
        raw = instruction.bytes.hex(" ")
        comments = []
        branch = re.fullmatch(r"#(0x[0-9a-f]+)", instruction.op_str)
        if instruction.mnemonic in ("bl", "blx") and branch:
            target = int(branch.group(1), 16)
            if target in plt:
                comments.append(f"PLT {plt[target]}")

        literal = re.fullmatch(
            r"(r(?:1[0-2]|[0-9])|ip|lr), \[pc, #(0x[0-9a-f]+)\]",
            instruction.op_str,
        )
        if instruction.mnemonic.startswith("ldr") and literal:
            register, immediate = literal.groups()
            literal_address = ((instruction.address + 4) & ~3) + int(immediate, 16)
            literal_offset = elf.address_to_offset(literal_address)
            if literal_offset is not None:
                value = struct.unpack_from("<i", elf.data, literal_offset)[0]
                pending_literals[register] = value
                comments.append(f"literal@0x{literal_address:x}=0x{value & 0xffffffff:08x}")

        pc_add_immediate = re.fullmatch(
            r"(r(?:1[0-2]|[0-9])|ip|lr), pc, #(0x[0-9a-f]+)",
            instruction.op_str,
        )
        if instruction.mnemonic in ("addw", "adr") and pc_add_immediate:
            _, immediate = pc_add_immediate.groups()
            target = ((instruction.address + 4) & ~3) + int(immediate, 16)
            comments.append(f"=>0x{target:x}")
            if target in strings:
                comments.append(repr(strings[target]))

        pc_add_register = re.fullmatch(
            r"(r(?:1[0-2]|[0-9])|ip|lr), pc", instruction.op_str
        )
        if instruction.mnemonic == "add" and pc_add_register:
            register = pc_add_register.group(1)
            if register in pending_literals:
                target = ((instruction.address + 4) & ~3) + pending_literals[register]
                comments.append(f"=>0x{target:x}")
                if target in relocations:
                    comments.append(f"GOT {relocations[target]}")
                if target in strings:
                    comments.append(repr(strings[target]))

        suffix = f" ; {'; '.join(comments)}" if comments else ""
        print(
            f"0x{instruction.address:08x}: {raw:<12} "
            f"{instruction.mnemonic:<8} {instruction.op_str}{suffix}"
        )


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("elf", type=Path)
    parser.add_argument("--symbols", action="store_true")
    parser.add_argument("--relocations", action="store_true")
    parser.add_argument("--strings", metavar="REGEX")
    parser.add_argument(
        "--mini-debug",
        action="store_true",
        help="decompress .gnu_debugdata and list its symbols",
    )
    parser.add_argument("--disassemble", type=lambda value: int(value, 0), metavar="ADDRESS")
    parser.add_argument("--size", type=lambda value: int(value, 0), default=0x100)
    args = parser.parse_args()

    elf = Elf32(args.elf)
    print(f"File: {elf.path}")
    print(
        f"ELF32 type={elf.etype} machine=0x{elf.machine:x} entry=0x{elf.entry:x} "
        f"flags=0x{elf.flags:x} sections={elf.shnum}"
    )
    print("\nSections:")
    for section in elf.sections:
        print(
            f"  [{section['index']:2d}] {section['name']:<18} "
            f"addr=0x{section['addr']:08x} off=0x{section['offset']:06x} "
            f"size=0x{section['size']:x} type={section['type']}"
        )

    if args.symbols:
        print("\nSymbols:")
        for symbol in elf.symbols():
            if symbol["name"]:
                print(
                    f"  0x{symbol['value']:08x} size=0x{symbol['size']:x} "
                    f"shndx={symbol['shndx']:>3} {symbol['name']}"
                )

    if args.relocations:
        print("\nRelocations:")
        for relocation in elf.relocations():
            print(
                f"  0x{relocation['address']:08x} type={relocation['type']:>3} "
                f"{relocation['symbol']}"
            )

    if args.strings:
        query = re.compile(args.strings, re.IGNORECASE)
        print("\nMatching strings:")
        for address, offset, value in printable_strings(elf):
            if query.search(value):
                print(f"  addr=0x{address:08x} off=0x{offset:06x} {value}")

    if args.mini_debug:
        section = elf.by_name.get(".gnu_debugdata")
        if section is None:
            print("\nNo .gnu_debugdata section")
        else:
            mini_data = lzma.decompress(elf.section_data(section))
            mini = Elf32(f"{elf.path}:.gnu_debugdata", mini_data)
            print(f"\nMini debug ELF: {len(mini_data)} bytes")
            for symbol in mini.symbols():
                if symbol["name"]:
                    print(
                        f"  0x{symbol['value']:08x} size=0x{symbol['size']:x} "
                        f"shndx={symbol['shndx']:>3} {symbol['name']}"
                    )

    if args.disassemble is not None:
        print(f"\nThumb disassembly from 0x{args.disassemble:08x}:")
        disassemble(elf, args.disassemble, args.size)


if __name__ == "__main__":
    main()
