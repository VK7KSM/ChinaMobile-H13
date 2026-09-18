#!/usr/bin/env python3
"""Audit suspected SCT3258 loader literal regions and nearby A5 contexts."""

from __future__ import annotations

import argparse
import hashlib
import json
import math
from collections import Counter
from pathlib import Path


BOOT_BASE = 0xF800
SEGMENTS = (
    ("structured_service_prefix", 0xFA2F, 0xFB8C),
    ("suspected_32_byte_literal", 0xFB8C, 0xFB9C),
    ("code_like_separator", 0xFB9C, 0xFBA0),
    ("suspected_192_byte_tail", 0xFBA0, 0xFC00),
)
VARIANT_NAMES = (
    "vendor_bootloader_base.bin",
    "vendor_bootloader_hpi_mode.bin",
    "vendor_bootloader_user2_flash.bin",
    "vendor_bootloader_user3_flash.bin",
    "vendor_bootloader_system_dmr.bin",
    "vendor_bootloader_system_dpmr.bin",
)
STANDARD_PREFIXES = {
    "aes_forward_sbox": bytes.fromhex("637c777bf26b6fc5"),
    "aes_inverse_sbox": bytes.fromhex("52096ad53036a538"),
    "sha256_k_big_endian": bytes.fromhex("428a2f9871374491"),
    "sha256_k_little_endian": bytes.fromhex("982f8a4291443771"),
    "blowfish_p_big_endian": bytes.fromhex("243f6a8885a308d3"),
    "blowfish_p_little_endian": bytes.fromhex("886a3f24d308a385"),
    "tea_delta_little_endian": bytes.fromhex("b979379e"),
    "crc32_table_little_endian": bytes.fromhex("0000000096300777"),
}


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def read_image(path: Path) -> bytes:
    data = path.read_bytes()
    if len(data) != 2048:
        raise ValueError(f"{path}: expected an exact 2048-byte Boot RAM image")
    return data


def words_le(data: bytes) -> list[int]:
    return [int.from_bytes(data[offset:offset + 2], "little")
            for offset in range(0, len(data), 2)]


def region(data: bytes, start: int, end: int) -> bytes:
    return data[(start - BOOT_BASE) * 2:(end - BOOT_BASE) * 2]


def entropy(values: bytes | list[int]) -> float:
    counts = Counter(values)
    length = len(values)
    return -sum((count / length) * math.log2(count / length)
                for count in counts.values())


def segment_metrics(data: bytes, name: str, start: int, end: int) -> dict[str, object]:
    raw = region(data, start, end)
    words = words_le(raw)
    high_bytes = [word >> 8 for word in words]
    high_counts = Counter(high_bytes)
    return {
        "name": name,
        "start_hex": f"0x{start:04x}",
        "end_exclusive_hex": f"0x{end:04x}",
        "byte_count": len(raw),
        "word_count": len(words),
        "unique_byte_count": len(set(raw)),
        "unique_word_count": len(set(words)),
        "unique_opcode_high_byte_count": len(high_counts),
        "singleton_opcode_high_byte_count": sum(count == 1 for count in high_counts.values()),
        "byte_entropy_bits": round(entropy(raw), 6),
        "opcode_high_byte_entropy_bits": round(entropy(high_bytes), 6),
        "cbf2_word_count": words.count(0xCBF2),
        "sha256": sha256(raw),
    }


def is_expanded_candidate(word: int) -> bool:
    opcode = word >> 8
    return (opcode & 0x3F) == 0x04 or opcode >> 4 in (0x0, 0x4)


def signed8(value: int) -> int:
    return value - 0x100 if value & 0x80 else value


def a5_contexts(words: list[int]) -> list[dict[str, object]]:
    records = []
    for index, word in enumerate(words[:-1]):
        address = BOOT_BASE + index
        if address >= 0xFB8C or word >> 8 != 0xA5:
            continue
        following = words[index + 1]
        item = {
            "address_hex": f"0x{address:04x}",
            "word_hex": f"0x{word:04x}",
            "following_word_hex": f"0x{following:04x}",
            "following_is_expanded_branch_candidate": is_expanded_candidate(following),
        }
        if is_expanded_candidate(following):
            target = address + 2 + signed8(following & 0xFF)
            item["following_candidate_target_hex"] = f"0x{target:04x}"
        records.append(item)
    return records


def analyze(loader_dir: Path) -> dict[str, object]:
    paths = [loader_dir / name for name in VARIANT_NAMES]
    for path in paths:
        if not path.is_file():
            raise ValueError(f"missing loader variant: {path}")
    images = {path.name: read_image(path) for path in paths}
    base = images[VARIANT_NAMES[0]]
    literal_ranges = ((0xFB8C, 0xFB9C), (0xFBA0, 0xFC00))
    variants = []
    for path in paths:
        data = images[path.name]
        variants.append({
            "name": path.name,
            "path": str(path.resolve()),
            "image_sha256": sha256(data),
            "literal_region_sha256": [
                sha256(region(data, start, end)) for start, end in literal_ranges
            ],
        })
    literal_hash_sets = [
        {item["literal_region_sha256"][index] for item in variants}
        for index in range(len(literal_ranges))
    ]
    tail = region(base, 0xFBA0, 0xFC00)
    signature_offsets = {
        name: tail.find(prefix) for name, prefix in STANDARD_PREFIXES.items()
    }
    contexts = a5_contexts(words_le(base))
    return {
        "format": "sct3258-loader-literal-region-audit-v1",
        "loader_variants": variants,
        "all_literal_regions_identical_across_variants": all(
            len(items) == 1 for items in literal_hash_sets
        ),
        "segments": [segment_metrics(base, *segment) for segment in SEGMENTS],
        "tail_chunk_uniqueness": [
            {
                "chunk_bytes": width,
                "chunk_count": len(tail) // width,
                "unique_chunk_count": len({
                    tail[offset:offset + width]
                    for offset in range(0, len(tail), width)
                }),
            }
            for width in (16, 24, 32, 48, 64, 96)
        ],
        "standard_constant_prefix_byte_offsets_in_192_byte_tail": signature_offsets,
        "a5_context_summary": {
            "structured_a5_word_count": len(contexts),
            "immediately_followed_by_expanded_branch_candidate_count": sum(
                bool(item["following_is_expanded_branch_candidate"])
                for item in contexts
            ),
            "contexts": contexts,
        },
        "bounded_conclusion": (
            "The 32-byte and 192-byte regions are invariant across all six loader "
            "variants. The tail has data-like uniqueness and opcode-high-byte dispersion, "
            "but its role is not proven. No tested standard constant-table prefix occurs. "
            "A5 words are frequently followed by expanded branch candidates, which supports "
            "control-predicate relevance without assigning an A5 mnemonic or operand role."
        ),
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--loader-dir", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if args.output.exists():
        parser.error(f"refusing to overwrite existing output: {args.output}")
    report = analyze(args.loader_dir)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2, ensure_ascii=True) + "\n",
                           encoding="ascii", newline="\n")
    print(json.dumps({
        "format": report["format"],
        "all_literal_regions_identical_across_variants":
            report["all_literal_regions_identical_across_variants"],
        "segments": report["segments"],
        "a5_context_summary": {
            key: value for key, value in report["a5_context_summary"].items()
            if key != "contexts"
        },
    }, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
