#!/usr/bin/env python3
import argparse
import hashlib
import struct
import sys
from pathlib import Path


LOAD_ADDRESS = 0x20001D00
MAX_BINARY_LENGTH = 0x180
EXPECTED_LITERALS = (
    0x20001E80,
    0x20001E84,
    0x20001E88,
    0x20001E8C,
    0x20001EA0,
    0x2000003C,
    0x08021DDD,
    0x200000E4,
    0x2000015C,
    0x48000800,
    0x0802CB8A,
    0x00000440,
    0x08010561,
    60000,
    0x52495048,
    0x47445242,
    0x44495048,
)
BASE_LOADER_SHA256 = "743515b9f7cd90737c1fff29b652b7558bc5a56487ec38df4fdea8877e7a619a"
HPI_LOADER_SHA256 = "761012f781bd7e7ea8e3137566f47e80dbb55e7ff91f65bde6ea81069432d9d9"


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


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("source", type=Path)
    parser.add_argument("mcu_flash", type=Path)
    parser.add_argument("binary", type=Path)
    parser.add_argument("disassembly", type=Path)
    args = parser.parse_args()

    runtime = Path(__file__).resolve().parents[1] / "asm_runtime_v038"
    sys.path.insert(0, str(runtime))
    from capstone import CS_ARCH_ARM, CS_MODE_LITTLE_ENDIAN, CS_MODE_THUMB, Cs
    from keystone import KS_ARCH_ARM, KS_MODE_LITTLE_ENDIAN, KS_MODE_THUMB, Ks

    assembler = Ks(KS_ARCH_ARM, KS_MODE_THUMB | KS_MODE_LITTLE_ENDIAN)
    encoded, statement_count = assembler.asm(
        assembler_source(args.source), addr=LOAD_ADDRESS)
    blob = bytes(encoded)
    if len(blob) > MAX_BINARY_LENGTH or len(blob) % 4:
        raise RuntimeError(f"unexpected binary length {len(blob)}")

    for literal in EXPECTED_LITERALS:
        packed = struct.pack("<I", literal)
        count = blob.count(packed)
        if count != 1:
            raise RuntimeError(f"literal 0x{literal:08x} occurs {count} times")

    flash = args.mcu_flash.read_bytes()
    loader_offset = 0x0802CB8A - 0x08000000
    base_loader = flash[loader_offset:loader_offset + 2048]
    if len(base_loader) != 2048:
        raise RuntimeError("MCU image does not contain complete loader")
    if hashlib.sha256(base_loader).hexdigest() != BASE_LOADER_SHA256:
        raise RuntimeError("MCU base loader hash mismatch")
    hpi_loader = bytearray(base_loader)
    hpi_loader[0x45C:0x45E] = b"\xF2\xCB"
    if hashlib.sha256(hpi_loader).hexdigest() != HPI_LOADER_SHA256:
        raise RuntimeError("derived HPI loader hash mismatch")
    chunks = [bytes(hpi_loader[offset:offset + 32]) for offset in range(0, 2048, 32)]
    if len(chunks) != 64 or any(len(chunk) != 32 for chunk in chunks):
        raise RuntimeError("loader chunk coverage mismatch")
    if b"".join(chunks) != bytes(hpi_loader):
        raise RuntimeError("loader chunk reconstruction mismatch")
    differences = [
        index for index, (left, right) in enumerate(zip(base_loader, hpi_loader))
        if left != right
    ]
    if differences != [0x45C, 0x45D]:
        raise RuntimeError(f"unexpected derived loader differences {differences!r}")

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
        "loader_chunks=64x32",
        "patch_offsets=0x045c,0x045d",
        "boot_ready_complete=0x200000e4:1",
        "raw_bridge=0x2000015c:1",
        "raw_bridge_timeout_ticks=60000",
        f"derived_loader_sha256={HPI_LOADER_SHA256}",
        "",
    ]
    lines.extend(
        f"0x{item.address:08x}: {item.bytes.hex(' '):<14} "
        f"{item.mnemonic:<7} {item.op_str}".rstrip()
        for item in instructions
    )
    lines.append("")
    lines.extend(f"literal=0x{value:08x}" for value in EXPECTED_LITERALS)
    args.disassembly.write_text("\n".join(lines) + "\n", encoding="ascii")
    print(f"wrote {args.binary} ({len(blob)} bytes, sha256 {digest})")
    print(f"wrote {args.disassembly}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
