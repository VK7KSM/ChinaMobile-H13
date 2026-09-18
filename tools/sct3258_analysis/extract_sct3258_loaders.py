#!/usr/bin/env python3
"""Extract SCT3258 loader blobs from the decompiled vendor .NET source.

The script is deliberately deterministic: it never modifies the vendor source and
refuses to emit a blob unless the declared and parsed lengths both equal 2048.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
from pathlib import Path


ARRAY_NAMES = ("_bootLoader", "_bootHpiData", "_bootLedData")


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def extract_array(source: str, name: str) -> bytes:
    pattern = re.compile(
        rf"protected\s+byte\[\]\s+{re.escape(name)}\s*=\s*new\s+byte\[(\d+)\]\s*\{{(.*?)\}}\s*;",
        re.DOTALL,
    )
    match = pattern.search(source)
    if not match:
        raise ValueError(f"array {name} not found")
    declared = int(match.group(1))
    values = [int(token, 0) for token in re.findall(r"(?:0x[0-9a-fA-F]+|\d+)", match.group(2))]
    if declared != 2048 or len(values) != declared:
        raise ValueError(f"{name}: declared={declared}, parsed={len(values)}, expected=2048")
    if any(value < 0 or value > 255 for value in values):
        raise ValueError(f"{name}: contains a value outside byte range")
    return bytes(values)


def patched(data: bytes, changes: dict[int, int]) -> bytes:
    output = bytearray(data)
    for offset, value in changes.items():
        output[offset] = value
    return bytes(output)


def differences(left: bytes, right: bytes) -> list[dict[str, int]]:
    if len(left) != len(right):
        raise ValueError("cannot compare blobs of different length")
    return [
        {"offset": index, "left": a, "right": b}
        for index, (a, b) in enumerate(zip(left, right))
        if a != b
    ]


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("source", type=Path, help="decompiled SCT3252.cs")
    parser.add_argument("output", type=Path, help="output directory")
    parser.add_argument("--h13-dir", type=Path, help="directory containing H13 mode0/1/2 blobs")
    args = parser.parse_args()

    source = args.source.read_text(encoding="utf-8-sig")
    arrays = {name: extract_array(source, name) for name in ARRAY_NAMES}
    base = arrays["_bootLoader"]
    blobs = {
        "vendor_bootloader_base.bin": base,
        "vendor_boothpi_data.bin": arrays["_bootHpiData"],
        "vendor_bootled_data.bin": arrays["_bootLedData"],
        "vendor_bootloader_hpi_mode.bin": patched(base, {0x45C: 0xF2, 0x45D: 0xCB}),
        "vendor_bootloader_user2_flash.bin": patched(base, {0x34C: 0xA4, 0x352: 0x72}),
        "vendor_bootloader_user3_flash.bin": patched(base, {0x34C: 0xA7, 0x352: 0xE2}),
        "vendor_bootloader_system_dmr.bin": patched(base, {0x34C: 0xA1, 0x352: 0x62, 0x45D: 0x04}),
        "vendor_bootloader_system_dpmr.bin": patched(base, {0x34C: 0xA1, 0x352: 0x72, 0x45D: 0x04}),
    }

    args.output.mkdir(parents=True, exist_ok=True)
    manifest: dict[str, object] = {
        "source": str(args.source),
        "source_sha256": sha256(args.source.read_bytes()),
        "blobs": {},
        "comparisons": {},
    }
    for filename, data in blobs.items():
        (args.output / filename).write_bytes(data)
        manifest["blobs"][filename] = {"length": len(data), "sha256": sha256(data)}

    comparisons: dict[str, object] = manifest["comparisons"]
    comparison_targets = (
        "vendor_bootloader_hpi_mode.bin",
        "vendor_bootloader_user2_flash.bin",
        "vendor_bootloader_user3_flash.bin",
        "vendor_bootloader_system_dmr.bin",
        "vendor_bootloader_system_dpmr.bin",
    )
    for left_name, right_name in (
        ("vendor_bootloader_base.bin", "vendor_boothpi_data.bin"),
        *(("vendor_bootloader_base.bin", target) for target in comparison_targets),
    ):
        diff = differences(blobs[left_name], blobs[right_name])
        comparisons[f"{left_name} vs {right_name}"] = {
            "difference_count": len(diff),
            "differences": diff,
        }

    if args.h13_dir:
        for mode in range(3):
            h13_path = args.h13_dir / f"sct3258_bootloader_mode{mode}.bin"
            if not h13_path.exists():
                continue
            h13_data = h13_path.read_bytes()
            for vendor_name in ("vendor_bootloader_base.bin", *comparison_targets):
                diff = differences(h13_data, blobs[vendor_name])
                comparisons[f"H13 mode{mode} vs {vendor_name}"] = {
                    "difference_count": len(diff),
                    "differences": diff,
                }

    manifest_path = args.output / "manifest.json"
    manifest_path.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")

    lines = ["# SCT3258 loader extraction", "", f"Source SHA-256: `{manifest['source_sha256']}`", "", "## Blobs", ""]
    for filename, info in manifest["blobs"].items():
        lines.append(f"- `{filename}`: {info['length']} bytes, SHA-256 `{info['sha256']}`")
    lines.extend(["", "## Comparisons", ""])
    for name, info in comparisons.items():
        offsets = ", ".join(f"0x{item['offset']:04X}" for item in info["differences"])
        lines.append(f"- `{name}`: {info['difference_count']} differences" + (f" at {offsets}" if offsets else ""))
    (args.output / "README.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(json.dumps(manifest["blobs"], indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
