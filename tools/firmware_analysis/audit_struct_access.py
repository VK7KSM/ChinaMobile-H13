#!/usr/bin/env python3
"""Audit bounded Thumb data flow from references to a firmware RAM structure."""

from __future__ import annotations

import argparse
import re
import struct
from collections import deque
from dataclasses import dataclass
from pathlib import Path


IMAGE_BASE = 0x08006000
REGISTER_ALIASES = {"ip": 12, "lr": 14, "pc": 15}
LINE_PATTERN = re.compile(
    r"^\s*([0-9a-f]+):\s+((?:[0-9a-f]{2}\s+)+)\s+(\S+)(?:\s+(.*?))?\s*$",
    re.IGNORECASE,
)
MEMORY_PATTERN = re.compile(
    r"\[(r(?:1[0-5]|[0-9])|ip|lr|pc)(?:,\s*#(-?(?:0x[0-9a-f]+|\d+)))?\]",
    re.IGNORECASE,
)


def u32(data: bytes, address: int, base: int) -> int:
    return struct.unpack_from("<I", data, address - base)[0]


def literal_references(data: bytes, base: int, value: int) -> list[int]:
    references = []
    for offset in range(0, len(data) - 1, 2):
        instruction = struct.unpack_from("<H", data, offset)[0]
        if instruction & 0xF800 != 0x4800:
            continue
        address = base + offset
        pool = ((address + 4) & ~3) + ((instruction & 0xFF) << 2)
        if base <= pool <= base + len(data) - 4 and u32(data, pool, base) == value:
            references.append(address)
    return references


def register_number(name: str) -> int | None:
    name = name.strip().lower()
    if name in REGISTER_ALIASES:
        return REGISTER_ALIASES[name]
    match = re.fullmatch(r"r(1[0-5]|[0-9])", name)
    return int(match.group(1)) if match else None


def integer(value: str) -> int:
    return int(value.strip().replace("#", ""), 0)


@dataclass(frozen=True)
class Instruction:
    address: int
    size: int
    mnemonic: str
    operands: str


@dataclass(frozen=True)
class Finding:
    address: int
    kind: str
    offset: int
    width: int
    detail: str


def parse_disassembly(path: Path) -> dict[int, Instruction]:
    instructions = {}
    for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
        match = LINE_PATTERN.match(line)
        if not match:
            continue
        address = int(match.group(1), 16)
        size = len(match.group(2).split())
        mnemonic = match.group(3).lower()
        operands = (match.group(4) or "").split("@", 1)[0].strip()
        instructions[address] = Instruction(address, size, mnemonic, operands)
    return instructions


class Auditor:
    def __init__(
        self,
        data: bytes,
        instructions: dict[int, Instruction],
        base: int,
        struct_address: int,
        max_steps: int,
        field: int | None,
    ):
        self.data = data
        self.instructions = instructions
        self.base = base
        self.end = base + len(data)
        self.struct_address = struct_address
        self.max_steps = max_steps
        self.field = field
        self.references = set(literal_references(data, base, struct_address))
        self.findings: set[Finding] = set()
        self.call_edges: set[tuple[int, int, tuple[tuple[int, int], ...]]] = set()
        self.visited: set[tuple[int, tuple[tuple[int, int], ...]]] = set()

    @staticmethod
    def state_key(registers: dict[int, int]) -> tuple[tuple[int, int], ...]:
        return tuple(sorted(registers.items()))

    @staticmethod
    def branch_target(instruction: Instruction) -> int | None:
        match = re.search(r"(?:^|,\s*)(0x[0-9a-f]+)(?:\s|$)", instruction.operands)
        return int(match.group(1), 16) & ~1 if match else None

    @staticmethod
    def memory_access(instruction: Instruction) -> tuple[int, int] | None:
        match = MEMORY_PATTERN.search(instruction.operands)
        if not match:
            return None
        base_register = register_number(match.group(1))
        if base_register is None:
            return None
        displacement = integer(match.group(2)) if match.group(2) else 0
        return base_register, displacement

    @staticmethod
    def first_register(instruction: Instruction) -> int | None:
        first = instruction.operands.split(",", 1)[0]
        return register_number(first)

    def update_registers(self, instruction: Instruction, registers: dict[int, int]) -> None:
        mnemonic = instruction.mnemonic
        parts = [part.strip() for part in instruction.operands.split(",")]
        destination = register_number(parts[0]) if parts else None
        if destination is None:
            return

        if mnemonic in {"mov", "movs"} and len(parts) >= 2:
            source = register_number(parts[1])
            if source in registers:
                registers[destination] = registers[source]
            else:
                registers.pop(destination, None)
            return

        if mnemonic in {"add", "adds", "sub", "subs"} and len(parts) >= 2:
            if len(parts) == 2:
                source = destination
                value_text = parts[1]
            else:
                source = register_number(parts[1])
                value_text = parts[2]
            if source not in registers or not value_text.startswith("#"):
                registers.pop(destination, None)
                return
            delta = integer(value_text)
            if mnemonic.startswith("sub"):
                delta = -delta
            registers[destination] = registers[source] + delta
            return

        if mnemonic.startswith("ldr"):
            registers.pop(destination, None)
            return

        if mnemonic not in {"cmp", "cmn", "tst", "str", "strb", "strh"}:
            registers.pop(destination, None)

    def walk(self, start: int, initial: dict[int, int]) -> None:
        queue = deque([(start, initial, 0)])
        while queue:
            address, inherited, steps = queue.popleft()
            registers = dict(inherited)
            while steps < self.max_steps and self.base <= address < self.end:
                key = (address, self.state_key(registers))
                if key in self.visited:
                    break
                self.visited.add(key)
                instruction = self.instructions.get(address)
                if instruction is None:
                    break
                mnemonic = instruction.mnemonic

                is_anchor = address in self.references and mnemonic == "ldr"
                if is_anchor:
                    destination = self.first_register(instruction)
                    if destination is not None:
                        registers[destination] = 0

                memory = self.memory_access(instruction)
                if memory is not None:
                    base_register, displacement = memory
                    if base_register in registers and mnemonic.startswith(("str", "ldr")):
                        offset = registers[base_register] + displacement
                        kind = "write" if mnemonic.startswith("str") else "read"
                        width = 1 if mnemonic.endswith("b") else 2 if mnemonic.endswith("h") else 4
                        self.findings.add(
                            Finding(
                                instruction.address,
                                kind,
                                offset,
                                width,
                                f"{mnemonic} {instruction.operands}",
                            )
                        )

                is_call = mnemonic in {"bl", "blx"}
                if is_call:
                    target = self.branch_target(instruction)
                    arguments = tuple(
                        (register, registers[register])
                        for register in range(4)
                        if register in registers
                    )
                    if target is not None and arguments:
                        edge = (instruction.address, target, arguments)
                        if edge not in self.call_edges:
                            self.call_edges.add(edge)
                            self.walk(target, dict(arguments))
                    for register in range(4):
                        registers.pop(register, None)
                elif not is_anchor:
                    self.update_registers(instruction, registers)

                next_address = address + instruction.size
                is_conditional = (
                    mnemonic.startswith("b")
                    and mnemonic not in {"b", "bl", "blx", "bx"}
                ) or mnemonic in {"cbz", "cbnz"}
                if is_conditional:
                    target = self.branch_target(instruction)
                    if target is not None:
                        queue.append((target, dict(registers), steps + 1))
                if mnemonic in {"b", "bx"} or (mnemonic == "pop" and "pc" in instruction.operands):
                    if mnemonic == "b":
                        target = self.branch_target(instruction)
                        if target is not None:
                            queue.append((target, dict(registers), steps + 1))
                    break
                address = next_address
                steps += 1

    def run(self) -> None:
        for reference in sorted(self.references):
            self.walk(reference, {})
        print(f"Structure 0x{self.struct_address:08x}")
        print(f"Literal references: {len(self.references)}")
        findings = self.findings
        if self.field is not None:
            findings = {
                finding
                for finding in findings
                if finding.offset <= self.field < finding.offset + finding.width
            }
            print(f"Memory accesses overlapping +0x{self.field:x}: {len(findings)}")
        else:
            print(f"Memory accesses reached: {len(findings)}")
        for finding in sorted(findings, key=lambda item: (item.offset, item.address, item.kind)):
            print(
                f"  +0x{finding.offset:04x}/w{finding.width} {finding.kind:<5} "
                f"0x{finding.address:08x}: {finding.detail}"
            )
        print(f"Calls carrying a structure-derived pointer: {len(self.call_edges)}")
        for source, target, arguments in sorted(self.call_edges):
            rendered = ", ".join(
                f"r{register}=+0x{offset:x}" for register, offset in arguments
            )
            print(f"  0x{source:08x} -> 0x{target:08x}: {rendered}")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("firmware", type=Path)
    parser.add_argument("--base", type=lambda value: int(value, 0), default=IMAGE_BASE)
    parser.add_argument("--struct", type=lambda value: int(value, 0), required=True)
    parser.add_argument("--disassembly", type=Path)
    parser.add_argument("--max-steps", type=int, default=256)
    parser.add_argument("--field", type=lambda value: int(value, 0))
    args = parser.parse_args()
    disassembly = args.disassembly or args.firmware.with_name(
        f"{args.firmware.stem}_thumb_disasm.txt"
    )
    Auditor(
        args.firmware.read_bytes(),
        parse_disassembly(disassembly),
        args.base,
        args.struct,
        args.max_steps,
        args.field,
    ).run()


if __name__ == "__main__":
    main()
