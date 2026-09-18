#!/usr/bin/env python3
"""Recover and inspect the module firmware's compressed RAM initializer.

The first scatter-load region is decompressed at boot by the routine at
0x08006fec.  This script reproduces that routine offline so pointer tables in
the initialized 0x200000c0..0x2000046b region can be inspected safely.
"""

from __future__ import annotations

import argparse
import struct
from pathlib import Path


FLASH_BASE = 0x08006000
PACKED_SOURCE = 0x08029D84
RAM_BASE = 0x200000C0
RAM_SIZE = 0x3AC
CTCSS_TABLE = 0x200001BC
DCS_TABLE = 0x20000288


def decompress_initializer(image: bytes) -> bytes:
    source = PACKED_SOURCE - FLASH_BASE
    output = bytearray()

    while len(output) < RAM_SIZE:
        control = image[source]
        source += 1

        literal_length = control & 0x07
        if literal_length == 0:
            literal_length = image[source]
            source += 1

        match_length = control >> 4
        if match_length == 0:
            match_length = image[source]
            source += 1

        # The Thumb loop decrements this field before testing/copying, so the
        # stored literal count is one greater than the number of literal bytes.
        literal_length -= 1
        output.extend(image[source : source + literal_length])
        source += literal_length

        if control & 0x08:
            distance = image[source]
            source += 1
            for _ in range(match_length + 2):
                output.append(output[-distance])
        else:
            output.extend(b"\x00" * match_length)

    if len(output) != RAM_SIZE:
        raise ValueError(f"initializer expanded to {len(output)} bytes, expected {RAM_SIZE}")
    return bytes(output)


def read_u32(ram: bytes, address: int) -> int:
    offset = address - RAM_BASE
    return struct.unpack_from("<I", ram, offset)[0]


def read_flash_string(image: bytes, address: int) -> str:
    offset = address - FLASH_BASE
    if not 0 <= offset < len(image):
        raise ValueError(f"string pointer 0x{address:08x} is outside the firmware image")
    end = image.index(0, offset)
    return image[offset:end].decode("ascii")


def read_string_table(image: bytes, ram: bytes, address: int, count: int) -> list[str]:
    return [read_flash_string(image, read_u32(ram, address + index * 4)) for index in range(count)]


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "firmware",
        nargs="?",
        type=Path,
        default=Path(__file__).with_name("Module_required_0.3.54.bin"),
    )
    args = parser.parse_args()

    image = args.firmware.read_bytes()
    ram = decompress_initializer(image)

    ctcss = read_string_table(image, ram, CTCSS_TABLE, 51)
    dcs = read_string_table(image, ram, DCS_TABLE, 83)
    print("CTCSS (parser index -> text):")
    print(", ".join(f"{index}:{value}" for index, value in enumerate(ctcss)))
    print("DCS (parser index -> text):")
    print(", ".join(f"{index}:{value}" for index, value in enumerate(dcs)))


if __name__ == "__main__":
    main()
