#!/usr/bin/env python3
"""Repeatable, read-only checks for the H13 radio module firmware."""

from __future__ import annotations

import argparse
import hashlib
import re
import struct
import sys
from collections import Counter
from dataclasses import dataclass
from pathlib import Path


KNOWN_REGISTERS = {
    0x40010000: "SYSCFG/COMP (STM32F0) or AFIO (STM32F1)",
    0x40010400: "EXTI",
    0x40012400: "ADC",
    0x40012C00: "TIM1",
    0x40013000: "SPI1",
    0x40013800: "USART1",
    0x40020000: "DMA1",
    0x40021000: "RCC",
    0x40022000: "FLASH interface",
    0x40004400: "USART2",
    0x40004800: "USART3 (device-dependent)",
    0x48000000: "GPIOA (STM32F0)",
    0x48000400: "GPIOB (STM32F0)",
    0x48000800: "GPIOC (STM32F0)",
    0x48000C00: "GPIOD (STM32F0)",
    0x48001000: "GPIOE (STM32F0)",
    0x48001400: "GPIOF (STM32F0)",
    0x40010800: "GPIOA (STM32F1)",
    0x40010C00: "GPIOB (STM32F1)",
    0x40011000: "GPIOC (STM32F1)",
    0xE000E010: "SysTick",
    0xE000ED08: "SCB VTOR",
}

DEFAULT_STRINGS = (
    "memread",
    "memwrite1",
    "ramlog",
    "sct3258reset",
    "sct hpi send",
    "3811Reg17",
    "read2571",
)

BOOTLOADER_ADDRESS = 0x08027D16
BOOTLOADER_SIZE = 2048
BOOTLOADER_PATCHES = {
    0: {},
    1: {
        0x34C: 0xA4,
        0x352: 0x72,
    },
    2: {
        0x34C: 0xA1,
        0x352: 0x62,
        0x45D: 0x04,
    },
}

DECOMPRESS_SCATTERLOAD = 0x08006FEC


@dataclass(frozen=True)
class ScatterloadRegion:
    table_address: int
    source: int
    destination: int
    size: int
    handler: int


def word(data: bytes, offset: int) -> int:
    return struct.unpack_from("<I", data, offset)[0]


def decompress_scatterload(
    data: bytes, output_size: int, prefix_size: int = 0
) -> tuple[list[int | None], int, int]:
    """Expand the compact scatterload stream used by __decompress at 0x08006fec."""
    source = 0
    memory: list[int | None] = [None] * prefix_size
    output_start = len(memory)

    def take_byte() -> int:
        nonlocal source
        if source >= len(data):
            raise ValueError("compressed stream ended before output was complete")
        value = data[source]
        source += 1
        return value

    while len(memory) - output_start < output_size:
        control = take_byte()
        literal_count = control & 0x07
        if literal_count == 0:
            literal_count = take_byte()
        if literal_count == 0:
            raise ValueError("zero literal-count encoding would underflow the MCU loop")
        # The firmware decrements this field before its first conditional copy.
        literal_count -= 1
        run_length = control >> 4
        if run_length == 0:
            run_length = take_byte()

        literal_end = source + literal_count
        if literal_end > len(data):
            raise ValueError("literal run extends past the compressed stream")
        memory.extend(data[source:literal_end])
        source = literal_end

        if control & 0x08:
            distance = take_byte()
            if distance == 0 or distance > len(memory):
                raise ValueError(f"invalid back-reference distance {distance}")
            for _ in range(run_length + 2):
                memory.append(memory[-distance])
        else:
            memory.extend([0] * run_length)

    produced = len(memory) - output_start
    return memory[output_start : output_start + output_size], source, produced - output_size


def discover_initialized_data(data: bytes, base: int) -> ScatterloadRegion:
    """Locate the compressed initialized-data entry in the Keil scatter table."""
    # The table stores an even code address; the startup dispatcher ORs bit 0
    # immediately before BLX to enter Thumb state.
    handler = DECOMPRESS_SCATTERLOAD
    matches = []
    for handler_offset in exact_occurrences(data, handler):
        entry_offset = handler_offset - 12
        if entry_offset < 0:
            continue
        source, destination, size, found_handler = struct.unpack_from(
            "<IIII", data, entry_offset
        )
        if found_handler != handler:
            continue
        if not base <= source < base + len(data):
            continue
        if not 0x20000000 <= destination < 0x20010000:
            continue
        if size <= 0 or destination + size > 0x20010000:
            continue
        matches.append(
            ScatterloadRegion(
                table_address=base + entry_offset,
                source=source,
                destination=destination,
                size=size,
                handler=found_handler,
            )
        )
    if len(matches) != 1:
        rendered = ", ".join(f"0x{item.table_address:08x}" for item in matches)
        raise ValueError(
            "expected one compressed initialized-data scatter entry, "
            f"found {len(matches)} ({rendered or 'none'})"
        )
    return matches[0]


def initialized_data(
    data: bytes, base: int
) -> tuple[list[int | None], int, int, ScatterloadRegion]:
    region = discover_initialized_data(data, base)
    offset = region.source - base
    if offset < 0 or offset >= len(data):
        raise ValueError("initialized-data stream is outside the firmware image")
    prefix_size = region.destination - 0x20000000
    image, consumed, overrun = decompress_scatterload(
        data[offset:], region.size, prefix_size
    )
    return image, consumed, overrun, region


def parse_ram_range(value: str) -> tuple[int, int]:
    address_text, separator, size_text = value.partition(":")
    address = int(address_text, 0)
    size = int(size_text, 0) if separator else 4
    if size <= 0:
        raise argparse.ArgumentTypeError("RAM range size must be positive")
    return address, size


def print_ram_ranges(
    image: list[int | None], ranges: list[tuple[int, int]], destination: int
) -> None:
    print("\nReconstructed initialized RAM:")
    for address, size in ranges:
        offset = address - destination
        if offset < 0 or offset + size > len(image):
            raise SystemExit(
                f"RAM range 0x{address:08x}+0x{size:x} is outside initialized data"
            )
        chunk = image[offset : offset + size]
        rendered = " ".join("??" if value is None else f"{value:02x}" for value in chunk)
        print(f"  0x{address:08x}+0x{size:x}: {rendered}")


def infer_base(data: bytes) -> int:
    reset = word(data, 4) & ~1
    return reset & ~0xFFF


def print_vectors(data: bytes, base: int) -> None:
    print("\nVector table:")
    print(f"  [00] initial_sp   0x{word(data, 0):08x}")
    for index in range(1, min(96, len(data) // 4)):
        value = word(data, index * 4)
        target = value & ~1
        is_vector = value == 0 or (value & 1 and base <= target < base + len(data))
        if not is_vector:
            print(f"  table_end        +0x{index * 4:04x} ({index} words)")
            break
        print(f"  [{index:02d}]              0x{value:08x}")


def exact_occurrences(data: bytes, value: int) -> list[int]:
    needle = struct.pack("<I", value)
    offsets = []
    start = 0
    while True:
        offset = data.find(needle, start)
        if offset < 0:
            return offsets
        offsets.append(offset)
        start = offset + 1


def thumb_adr_xrefs(data: bytes, base: int, target: int) -> list[int]:
    """Find Thumb-1 ADR instructions that form an exact absolute target."""
    xrefs = []
    for offset in range(0, len(data) - 1, 2):
        instruction = struct.unpack_from("<H", data, offset)[0]
        if instruction & 0xF800 != 0xA000:
            continue
        address = base + offset
        immediate = (instruction & 0xFF) << 2
        resolved = ((address + 4) & ~3) + immediate
        if resolved == target:
            xrefs.append(address)
    return xrefs


def thumb_ldr_literal_xrefs(
    data: bytes, base: int, value: int
) -> list[tuple[int, int]]:
    """Find Thumb-1 PC-relative LDR instructions whose pool word matches value."""
    xrefs = []
    for offset in range(0, len(data) - 1, 2):
        instruction = struct.unpack_from("<H", data, offset)[0]
        if instruction & 0xF800 != 0x4800:
            continue
        address = base + offset
        pool_address = ((address + 4) & ~3) + ((instruction & 0xFF) << 2)
        pool_offset = pool_address - base
        if pool_offset < 0 or pool_offset + 4 > len(data):
            continue
        if word(data, pool_offset) == value:
            xrefs.append((address, pool_address))
    return xrefs


def thumb_immediate_memory_access(
    instruction: int,
) -> tuple[str, int, int, int] | None:
    """Decode a Thumb-1 immediate load/store as mnemonic, Rt, Rn, byte offset."""
    opcode = instruction & 0xF800
    operations = {
        0x6000: ("str", 4),
        0x6800: ("ldr", 4),
        0x7000: ("strb", 1),
        0x7800: ("ldrb", 1),
        0x8000: ("strh", 2),
        0x8800: ("ldrh", 2),
    }
    operation = operations.get(opcode)
    if operation is None:
        return None
    mnemonic, scale = operation
    target_register = instruction & 0x07
    base_register = (instruction >> 3) & 0x07
    offset = ((instruction >> 6) & 0x1F) * scale
    return mnemonic, target_register, base_register, offset


def thumb_struct_field_xrefs(
    data: bytes, base: int, struct_base: int, field_offset: int
) -> list[tuple[int, int, str, int]]:
    """Find direct field accesses immediately after loading a structure literal."""
    references = []
    for load_address, _ in thumb_ldr_literal_xrefs(data, base, struct_base):
        load_offset = load_address - base
        literal_load = struct.unpack_from("<H", data, load_offset)[0]
        base_register = (literal_load >> 8) & 0x07
        access_offset = load_offset + 2
        if access_offset + 2 > len(data):
            continue
        decoded = thumb_immediate_memory_access(
            struct.unpack_from("<H", data, access_offset)[0]
        )
        if decoded is None:
            continue
        mnemonic, target_register, access_base_register, offset = decoded
        if access_base_register == base_register and offset == field_offset:
            references.append(
                (load_address, base + access_offset, mnemonic, target_register)
            )
    return references


def thumb_bl_target(first: int, second: int, address: int) -> int | None:
    """Decode a Thumb-2 BL immediate target, or return None for other opcodes."""
    if first & 0xF800 != 0xF000 or second & 0xD000 != 0xD000:
        return None
    sign = (first >> 10) & 1
    j1 = (second >> 13) & 1
    j2 = (second >> 11) & 1
    i1 = 1 ^ (j1 ^ sign)
    i2 = 1 ^ (j2 ^ sign)
    displacement = (
        (sign << 24)
        | (i1 << 23)
        | (i2 << 22)
        | ((first & 0x03FF) << 12)
        | ((second & 0x07FF) << 1)
    )
    if sign:
        displacement -= 1 << 25
    return (address + 4 + displacement) & 0xFFFFFFFF


def thumb_bl_xrefs(data: bytes, base: int, target: int) -> list[int]:
    """Find direct Thumb BL references without requiring a disassembler."""
    wanted = target & ~1
    references = []
    for offset in range(0, len(data) - 3, 2):
        first, second = struct.unpack_from("<HH", data, offset)
        resolved = thumb_bl_target(first, second, base + offset)
        if resolved == wanted:
            references.append(base + offset)
    return references


def print_registers(data: bytes, base: int) -> None:
    print("\nKnown register literals:")
    for address, name in KNOWN_REGISTERS.items():
        offsets = exact_occurrences(data, address)
        if offsets:
            locations = ", ".join(f"0x{base + item:08x}" for item in offsets[:8])
            suffix = " ..." if len(offsets) > 8 else ""
            print(f"  0x{address:08x}  {name}: {len(offsets)} at {locations}{suffix}")

    peripheral_words = Counter()
    for offset in range(0, len(data) - 3, 4):
        value = word(data, offset)
        if 0x40000000 <= value < 0x60000000 or 0xE0000000 <= value < 0xE0100000:
            peripheral_words[value] += 1
    print("\nMost frequent aligned peripheral/system literals:")
    for value, count in peripheral_words.most_common(30):
        label = KNOWN_REGISTERS.get(value, "")
        print(f"  0x{value:08x}  count={count:3d}  {label}")


def print_string_xrefs(data: bytes, base: int, queries: list[str]) -> None:
    print("\nString locations and literal cross-references:")
    for query in queries:
        needle = query.encode("ascii")
        starts = []
        offset = 0
        while True:
            offset = data.find(needle, offset)
            if offset < 0:
                break
            starts.append(offset)
            offset += 1
        if not starts:
            print(f"  {query!r}: not found")
            continue
        for string_offset in starts:
            address = base + string_offset
            pointer_xrefs = exact_occurrences(data, address)
            pointer_refs = (
                ", ".join(f"0x{base + item:08x}" for item in pointer_xrefs) or "none"
            )
            adr_xrefs = thumb_adr_xrefs(data, base, address)
            adr_refs = ", ".join(f"0x{item:08x}" for item in adr_xrefs) or "none"
            print(
                f"  {query!r}: 0x{address:08x}; "
                f"pointer literals at {pointer_refs}; Thumb ADR at {adr_refs}"
            )


def print_hex_xrefs(data: bytes, base: int, queries: list[str]) -> None:
    print("\nBinary locations and literal cross-references:")
    for query in queries:
        try:
            needle = bytes.fromhex(query)
        except ValueError as error:
            raise SystemExit(f"Invalid --hex value {query!r}: {error}") from error
        if not needle:
            raise SystemExit("--hex requires at least one byte")

        starts = []
        offset = 0
        while True:
            offset = data.find(needle, offset)
            if offset < 0:
                break
            starts.append(offset)
            offset += 1
        if not starts:
            print(f"  {query!r}: not found")
            continue

        for binary_offset in starts:
            address = base + binary_offset
            pointer_xrefs = exact_occurrences(data, address)
            pointer_refs = (
                ", ".join(f"0x{base + item:08x}" for item in pointer_xrefs) or "none"
            )
            adr_xrefs = thumb_adr_xrefs(data, base, address)
            adr_refs = ", ".join(f"0x{item:08x}" for item in adr_xrefs) or "none"
            print(
                f"  {query!r}: 0x{address:08x}; "
                f"pointer literals at {pointer_refs}; Thumb ADR at {adr_refs}"
            )


def print_literal_xrefs(data: bytes, base: int, values: list[int]) -> None:
    print("\nThumb literal-load cross-references:")
    for value in values:
        xrefs = thumb_ldr_literal_xrefs(data, base, value)
        if not xrefs:
            print(f"  0x{value:08x}: none")
            continue
        locations = ", ".join(
            f"0x{address:08x} (pool 0x{pool:08x})" for address, pool in xrefs
        )
        print(f"  0x{value:08x}: {locations}")


def parse_field_access(value: str) -> tuple[int, int]:
    base_text, separator, offset_text = value.partition(":")
    if not separator:
        raise argparse.ArgumentTypeError("field access must be BASE:OFFSET")
    struct_base = int(base_text, 0)
    field_offset = int(offset_text, 0)
    if field_offset < 0:
        raise argparse.ArgumentTypeError("field offset cannot be negative")
    return struct_base, field_offset


def print_struct_field_xrefs(
    data: bytes, base: int, fields: list[tuple[int, int]]
) -> None:
    print("\nDirect Thumb structure-field accesses:")
    for struct_base, field_offset in fields:
        references = thumb_struct_field_xrefs(data, base, struct_base, field_offset)
        rendered = ", ".join(
            f"0x{access:08x} {mnemonic} r{target}, "
            f"[literal loaded at 0x{load:08x}]"
            for load, access, mnemonic, target in references
        )
        print(f"  0x{struct_base:08x}+0x{field_offset:x}: {rendered or 'none'}")


def print_ascii_summary(data: bytes, base: int) -> None:
    strings = []
    for match in re.finditer(rb"[ -~]{8,}", data):
        text = match.group().decode("ascii", errors="replace")
        strings.append((base + match.start(), text))
    print(f"\nPrintable ASCII strings (length >= 8): {len(strings)}")


def print_ascii_range(data: bytes, base: int, start: int, size: int) -> None:
    offset = start - base
    if offset < 0 or offset >= len(data):
        raise SystemExit(f"address 0x{start:08x} is outside the firmware image")
    chunk = data[offset : min(offset + size, len(data))]
    print(f"\nPrintable ASCII strings 0x{start:08x}..0x{start + len(chunk):08x}:")
    for match in re.finditer(rb"[ -~]{4,}", chunk):
        rendered = match.group().decode("ascii", errors="replace")
        print(f"  0x{start + match.start():08x}  {rendered}")


def extract_bootloaders(data: bytes, base: int, output_dir: Path) -> None:
    offset = BOOTLOADER_ADDRESS - base
    if offset < 0 or offset + BOOTLOADER_SIZE > len(data):
        raise SystemExit(
            f"Bootloader range 0x{BOOTLOADER_ADDRESS:08x}+{BOOTLOADER_SIZE} "
            "is outside the firmware image"
        )

    original = data[offset : offset + BOOTLOADER_SIZE]
    output_dir.mkdir(parents=True, exist_ok=True)
    print("\nSCT3258 bootloader variants:")
    print(f"  source address  0x{BOOTLOADER_ADDRESS:08x}")
    print(f"  source offset   0x{offset:x}")
    print(f"  size            {BOOTLOADER_SIZE} bytes")

    for mode, patches in BOOTLOADER_PATCHES.items():
        variant = bytearray(original)
        differences = []
        for patch_offset, replacement in patches.items():
            previous = variant[patch_offset]
            variant[patch_offset] = replacement
            differences.append((patch_offset, previous, replacement))

        output_path = output_dir / f"sct3258_bootloader_mode{mode}.bin"
        output_path.write_bytes(variant)
        digest = hashlib.sha256(variant).hexdigest()
        print(f"  mode {mode}: {output_path}  SHA-256 {digest}")
        if differences:
            for patch_offset, previous, replacement in differences:
                print(
                    f"    +0x{patch_offset:03x}: "
                    f"0x{previous:02x} -> 0x{replacement:02x}"
                )
        else:
            print("    no patches")


def parse_disassembly_range(value: str) -> tuple[int, int]:
    address_text, separator, size_text = value.partition(":")
    address = int(address_text, 0)
    size = int(size_text, 0) if separator else 0x100
    if size <= 0:
        raise argparse.ArgumentTypeError("disassembly size must be positive")
    return address, size


def disassemble_thumb(data: bytes, base: int, start: int, size: int) -> None:
    """Disassemble one bounded Thumb range without assuming adjacent bytes are code."""
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

    offset = start - base
    if offset < 0 or offset >= len(data):
        raise SystemExit(f"address 0x{start:08x} is outside the firmware image")
    code = data[offset : min(offset + size, len(data))]
    engine = Cs(CS_ARCH_ARM, CS_MODE_THUMB | CS_MODE_LITTLE_ENDIAN)
    print(f"\nThumb disassembly 0x{start:08x}..0x{start + len(code):08x}:")
    for instruction in engine.disasm(code, start):
        raw = instruction.bytes.hex(" ")
        comment = ""
        literal = re.fullmatch(
            r"(r(?:1[0-2]|[0-9])|ip|lr), \[pc, #(0x[0-9a-f]+|[0-9]+)\]",
            instruction.op_str,
        )
        if instruction.mnemonic.startswith("ldr") and literal:
            immediate = int(literal.group(2), 16)
            literal_address = ((instruction.address + 4) & ~3) + immediate
            literal_offset = literal_address - base
            if 0 <= literal_offset <= len(data) - 4:
                value = word(data, literal_offset)
                comment = f"  ; [0x{literal_address:08x}]=0x{value:08x}"
        print(
            f"  0x{instruction.address:08x}: {raw:<12} "
            f"{instruction.mnemonic:<8} {instruction.op_str}{comment}"
        )


def print_thumb_call_xrefs(data: bytes, base: int, targets: list[int]) -> None:
    """Find direct Thumb BL references without assuming a linear code map."""
    wanted = {target & ~1 for target in targets}
    references = {target: thumb_bl_xrefs(data, base, target) for target in wanted}

    print("\nDirect Thumb call cross-references:")
    for target in sorted(wanted):
        rendered = ", ".join(f"0x{address:08x}" for address in references[target])
        print(f"  0x{target:08x}: {rendered or 'none'}")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("firmware", type=Path)
    parser.add_argument("--base", type=lambda value: int(value, 0))
    parser.add_argument("--string", action="append", dest="strings")
    parser.add_argument("--hex", action="append", dest="hex_strings")
    parser.add_argument(
        "--literal",
        action="append",
        type=lambda value: int(value, 0),
        dest="literal_values",
        help="find Thumb-1 PC-relative literal loads of this 32-bit value",
    )
    parser.add_argument(
        "--field-access",
        action="append",
        type=parse_field_access,
        dest="field_accesses",
        metavar="BASE:OFFSET",
        help="find immediate Thumb-1 accesses after loading a structure base literal",
    )
    parser.add_argument(
        "--call-target",
        action="append",
        type=lambda value: int(value, 0),
        dest="call_targets",
        help="find direct Thumb BL references to this address",
    )
    parser.add_argument(
        "--extract-bootloaders",
        type=Path,
        metavar="DIR",
        help="extract the three 2048-byte SCT3258 bootloader variants",
    )
    parser.add_argument(
        "--extract-initialized-data",
        type=Path,
        metavar="FILE",
        help="decompress the 0x200000c0 initialized RAM image",
    )
    parser.add_argument(
        "--ram",
        action="append",
        type=parse_ram_range,
        dest="ram_ranges",
        metavar="ADDRESS[:SIZE]",
        help="show bytes from the reconstructed initialized RAM image",
    )
    parser.add_argument(
        "--disassemble",
        action="append",
        type=parse_disassembly_range,
        metavar="ADDRESS[:SIZE]",
        help="disassemble a bounded Thumb range (default size: 0x100)",
    )
    parser.add_argument(
        "--ascii-range",
        action="append",
        type=parse_disassembly_range,
        metavar="ADDRESS[:SIZE]",
        help="list printable ASCII strings in a bounded firmware range",
    )
    parser.add_argument(
        "--only-disassemble",
        action="store_true",
        help="suppress the standard image report when using --disassemble",
    )
    args = parser.parse_args()

    data = args.firmware.read_bytes()
    base = args.base if args.base is not None else infer_base(data)
    reset = word(data, 4)

    ram_image = None
    ram_source_size = None
    ram_overrun = None
    ram_region = None
    if args.ram_ranges or args.extract_initialized_data or not args.only_disassemble:
        try:
            ram_image, ram_source_size, ram_overrun, ram_region = initialized_data(
                data, base
            )
        except ValueError as error:
            raise SystemExit(f"Cannot reconstruct initialized RAM: {error}") from error

    if not args.only_disassemble:
        print(f"File:       {args.firmware}")
        print(f"Size:       {len(data)} bytes (0x{len(data):x})")
        print(f"SHA-256:    {hashlib.sha256(data).hexdigest()}")
        print(f"Image base: 0x{base:08x}")
        print(f"Initial SP: 0x{word(data, 0):08x}")
        print(f"Reset:      0x{reset:08x} (file offset 0x{(reset & ~1) - base:x})")
        print(
            "Init data:  "
            f"table 0x{ram_region.table_address:08x}; "
            f"0x{ram_region.source:08x}+0x{ram_source_size:x} -> "
            f"0x{ram_region.destination:08x}+0x{len(ram_image):x} "
            f"({sum(value is None for value in ram_image)} prefix-dependent bytes, "
            f"final run overrun 0x{ram_overrun:x})"
        )

        print_vectors(data, base)
        print_registers(data, base)
        print_string_xrefs(data, base, args.strings or list(DEFAULT_STRINGS))
        if args.hex_strings:
            print_hex_xrefs(data, base, args.hex_strings)
        if args.literal_values:
            print_literal_xrefs(data, base, args.literal_values)
        if args.field_accesses:
            print_struct_field_xrefs(data, base, args.field_accesses)
        if args.call_targets:
            print_thumb_call_xrefs(data, base, args.call_targets)
        if args.extract_bootloaders:
            extract_bootloaders(data, base, args.extract_bootloaders)
    if args.ram_ranges:
        print_ram_ranges(ram_image, args.ram_ranges, ram_region.destination)
    if args.extract_initialized_data:
        if any(value is None for value in ram_image):
            raise SystemExit(
                "Cannot extract initialized RAM: output depends on bytes before "
                "0x200000c0 that are populated before the application starts"
            )
        args.extract_initialized_data.write_bytes(bytes(ram_image))
        print(
            f"\nInitialized RAM written to {args.extract_initialized_data} "
            f"({len(ram_image)} bytes)"
        )
    for start, size in args.disassemble or []:
        disassemble_thumb(data, base, start, size)
    for start, size in args.ascii_range or []:
        print_ascii_range(data, base, start, size)
    if not args.only_disassemble:
        print_ascii_summary(data, base)


if __name__ == "__main__":
    main()
