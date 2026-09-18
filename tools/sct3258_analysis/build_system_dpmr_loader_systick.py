#!/usr/bin/env python3
import argparse
import hashlib
import os
import struct
import sys
from pathlib import Path


LOAD_ADDRESS = 0x20001D00
MAX_BINARY_LENGTH = 0x280
BASE_LOADER_SHA256 = "743515b9f7cd90737c1fff29b652b7558bc5a56487ec38df4fdea8877e7a619a"
SYSTEM_DPMR_LOADER_SHA256 = "95a99631ee8c31492483d616139c1fbc0abfeacd4c27fa73d9899e5bf1cb2be1"
SEGMENTS = (
    (0x000, 0x34C),
    (None, b"\xA1"),
    (0x34D, 0x005),
    (None, b"\x72"),
    (0x353, 0x10A),
    (None, b"\x04"),
    (0x45E, 0x3A2),
)


def assembler_source(path: Path) -> str:
    kept = []
    for raw_line in path.read_text(encoding="ascii").splitlines():
        line = raw_line.strip()
        if not line or line.startswith((
                ".syntax", ".cpu", ".thumb", ".section", ".global",
                ".type", ".thumb_func", ".size")):
            continue
        kept.append(raw_line)
    return "\n".join(kept)


def reconstruct(base: bytes) -> bytes:
    output = bytearray()
    for offset, value in SEGMENTS:
        if offset is None:
            output.extend(value)
        else:
            output.extend(base[offset:offset + value])
    return bytes(output)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("source", type=Path)
    parser.add_argument("mcu_flash", type=Path)
    parser.add_argument("binary", type=Path)
    parser.add_argument("disassembly", type=Path)
    args = parser.parse_args()

    runtime = Path(os.environ.get(
        "H13_ASM_RUNTIME",
        str(Path(__file__).resolve().parents[1] / "asm_runtime_v038")))
    sys.path.insert(0, str(runtime))
    from capstone import CS_ARCH_ARM, CS_MODE_LITTLE_ENDIAN, CS_MODE_THUMB, Cs
    from keystone import KS_ARCH_ARM, KS_MODE_LITTLE_ENDIAN, KS_MODE_THUMB, Ks

    assembler = Ks(KS_ARCH_ARM, KS_MODE_THUMB | KS_MODE_LITTLE_ENDIAN)
    encoded, statement_count = assembler.asm(
        assembler_source(args.source), addr=LOAD_ADDRESS)
    blob = bytes(encoded)
    if len(blob) > MAX_BINARY_LENGTH or len(blob) % 4:
        raise RuntimeError(f"unexpected binary length {len(blob)}")

    flash = args.mcu_flash.read_bytes()
    loader_offset = 0x0802CB8A - 0x08000000
    base_loader = flash[loader_offset:loader_offset + 2048]
    if hashlib.sha256(base_loader).hexdigest() != BASE_LOADER_SHA256:
        raise RuntimeError("MCU base loader hash mismatch")
    derived_loader = reconstruct(base_loader)
    if len(derived_loader) != 2048:
        raise RuntimeError(f"derived loader length is {len(derived_loader)}")
    if hashlib.sha256(derived_loader).hexdigest() != SYSTEM_DPMR_LOADER_SHA256:
        raise RuntimeError("derived system-DPMR loader hash mismatch")
    expected = bytearray(base_loader)
    expected[0x34C] = 0xA1
    expected[0x352] = 0x72
    expected[0x45D] = 0x04
    if derived_loader != bytes(expected):
        raise RuntimeError("segment reconstruction differs from audited patch set")

    for patch in (b"\xA1\x00\x00\x00", b"\x72\x00\x00\x00",
            b"\x04\x00\x00\x00"):
        if blob.count(patch) != 1:
            raise RuntimeError(f"patch literal {patch.hex()} count mismatch")
    for address in (0x20001F80, 0x20001F84, 0x20001F88,
            0x2000003C, 0x08021DDD, 0x200000E4, 0x2000015C,
            0x48000800, 0x08010561):
        if blob.count(struct.pack("<I", address)) != 1:
            raise RuntimeError(f"literal 0x{address:08x} count mismatch")

    disassembler = Cs(CS_ARCH_ARM, CS_MODE_THUMB | CS_MODE_LITTLE_ENDIAN)
    instructions = list(disassembler.disasm(blob, LOAD_ADDRESS))
    args.binary.write_bytes(blob)
    digest = hashlib.sha256(blob).hexdigest()
    lines = [
        f"load_address=0x{LOAD_ADDRESS:08x}",
        f"binary_length={len(blob)}",
        f"statement_count={statement_count}",
        f"sha256={digest}",
        "reset_ticks=1,50,10",
        "loader_transactions=844,1,5,1,266,1,930",
        "loader_patches=0x034c:a1,0x0352:72,0x045d:04",
        "uart_bridge_baud=57600_unchanged",
        "loader_settle_ticks=100",
        "raw_bridge_timeout_ticks=60000",
        f"derived_loader_sha256={SYSTEM_DPMR_LOADER_SHA256}",
        "",
    ]
    lines.extend(
        f"0x{item.address:08x}: {item.bytes.hex(' '):<14} "
        f"{item.mnemonic:<7} {item.op_str}".rstrip()
        for item in instructions
    )
    args.disassembly.write_text("\n".join(lines) + "\n", encoding="ascii")
    print(f"wrote {args.binary} ({len(blob)} bytes, sha256 {digest})")
    print(f"wrote {args.disassembly}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
