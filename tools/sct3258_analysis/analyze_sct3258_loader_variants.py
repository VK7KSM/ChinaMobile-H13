#!/usr/bin/env python3
"""Index SCT3258 1024-word Boot RAM loader variants without assuming an ISA."""

from __future__ import annotations

import argparse
import hashlib
import json
from collections import Counter
from difflib import SequenceMatcher
from pathlib import Path


BOOT_RAM_WORD_BASE = 0xF800
BASE_NAME = "vendor_bootloader_base.bin"
VARIANT_NAMES = (
    "vendor_bootloader_hpi_mode.bin",
    "vendor_bootloader_user2_flash.bin",
    "vendor_bootloader_user3_flash.bin",
    "vendor_bootloader_system_dmr.bin",
    "vendor_bootloader_system_dpmr.bin",
    "vendor_boothpi_data.bin",
)
OTHER_PROGRAM_NAMES = ("vendor_bootled_data.bin",)
DOWNLOAD_CONTROLS = (
    "80a8", "81a7", "88a8", "89a7", "8aa8", "8ba7", "8ca8", "8da7", "82a3", "85ab"
)


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def words_le(data: bytes) -> list[int]:
    if len(data) % 2:
        raise ValueError("loader byte count is not even")
    return [int.from_bytes(data[offset : offset + 2], "little") for offset in range(0, len(data), 2)]


def occurrences(data: bytes, pattern: bytes) -> list[int]:
    return [index for index in range(len(data) - len(pattern) + 1) if data[index : index + len(pattern)] == pattern]


def repeated_word_runs(words: list[int], minimum: int = 2) -> list[dict[str, object]]:
    runs: list[dict[str, object]] = []
    start = 0
    while start < len(words):
        end = start + 1
        while end < len(words) and words[end] == words[start]:
            end += 1
        if end - start >= minimum:
            runs.append(
                {
                    "word": words[start],
                    "word_hex": f"0x{words[start]:04x}",
                    "start_word_address": BOOT_RAM_WORD_BASE + start,
                    "end_word_address_inclusive": BOOT_RAM_WORD_BASE + end - 1,
                    "length_words": end - start,
                }
            )
        start = end
    return sorted(runs, key=lambda item: (-int(item["length_words"]), int(item["start_word_address"])))


def context(words: list[int], word_index: int, radius: int = 10) -> list[dict[str, object]]:
    first = max(0, word_index - radius)
    last = min(len(words), word_index + radius + 1)
    return [
        {
            "word_address": BOOT_RAM_WORD_BASE + index,
            "word": words[index],
            "word_hex": f"0x{words[index]:04x}",
            "is_patch": index == word_index,
        }
        for index in range(first, last)
    ]


def variant_diff(base: bytes, variant: bytes, name: str) -> dict[str, object]:
    if len(base) != 2048 or len(variant) != 2048:
        raise ValueError(f"{name}: expected 2048 bytes")
    base_words = words_le(base)
    variant_words = words_le(variant)
    byte_diffs = [index for index, (left, right) in enumerate(zip(base, variant)) if left != right]
    changed_words = sorted({index // 2 for index in byte_diffs})
    return {
        "name": name,
        "sha256": sha256(variant),
        "changed_bytes": len(byte_diffs),
        "changed_words": len(changed_words),
        "patches": [
            {
                "byte_offset": word_index * 2,
                "word_address": BOOT_RAM_WORD_BASE + word_index,
                "base_word": base_words[word_index],
                "base_word_hex": f"0x{base_words[word_index]:04x}",
                "variant_word": variant_words[word_index],
                "variant_word_hex": f"0x{variant_words[word_index]:04x}",
                "base_bytes_hex": base[word_index * 2 : word_index * 2 + 2].hex(" "),
                "variant_bytes_hex": variant[word_index * 2 : word_index * 2 + 2].hex(" "),
                "base_context": context(base_words, word_index),
            }
            for word_index in changed_words
        ],
    }


def common_word_blocks(left: list[int], right: list[int], minimum: int = 4) -> list[dict[str, object]]:
    blocks = SequenceMatcher(a=left, b=right, autojunk=False).get_matching_blocks()
    return sorted(
        [
            {
                "left_word_address": BOOT_RAM_WORD_BASE + block.a,
                "right_word_address": BOOT_RAM_WORD_BASE + block.b,
                "length_words": block.size,
                "sha256_le_bytes": sha256(b"".join(word.to_bytes(2, "little") for word in left[block.a : block.a + block.size])),
            }
            for block in blocks
            if block.size >= minimum
        ],
        key=lambda item: (-int(item["length_words"]), int(item["left_word_address"])),
    )


def analyze(directory: Path) -> dict[str, object]:
    base_path = directory / BASE_NAME
    base = base_path.read_bytes()
    if len(base) != 2048:
        raise ValueError(f"{base_path}: expected 2048 bytes")
    words = words_le(base)
    histogram = Counter(words)
    variants = []
    for name in VARIANT_NAMES:
        path = directory / name
        if path.exists():
            variants.append(variant_diff(base, path.read_bytes(), name))
    other_programs = []
    for name in OTHER_PROGRAM_NAMES:
        path = directory / name
        if not path.exists():
            continue
        data = path.read_bytes()
        if len(data) != 2048:
            raise ValueError(f"{path}: expected 2048 bytes")
        program_words = words_le(data)
        program_histogram = Counter(program_words)
        other_programs.append(
            {
                "name": name,
                "sha256": sha256(data),
                "top_words": [
                    {"word": word, "word_hex": f"0x{word:04x}", "count": count}
                    for word, count in program_histogram.most_common(16)
                ],
                "base_common_word_blocks_min4": common_word_blocks(words, program_words),
            }
        )

    return {
        "format": "sct3258-loader-variant-index-v1",
        "boot_ram_word_base": BOOT_RAM_WORD_BASE,
        "base": {"path": str(base_path.resolve()), "size": len(base), "sha256": sha256(base)},
        "base_word_histogram_top32": [
            {"word": word, "word_hex": f"0x{word:04x}", "count": count}
            for word, count in histogram.most_common(32)
        ],
        "base_repeated_word_runs": repeated_word_runs(words),
        "base_control_byte_sequence_occurrences": {
            control: occurrences(base, bytes.fromhex(control)) for control in DOWNLOAD_CONTROLS
        },
        "variants": variants,
        "other_uncoded_programs": other_programs,
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("loader_directory", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if args.output.exists():
        parser.error(f"refusing to overwrite existing output: {args.output}")
    try:
        report = analyze(args.loader_directory)
    except (OSError, ValueError) as exc:
        parser.error(str(exc))
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2, ensure_ascii=True) + "\n", encoding="ascii")
    print(
        json.dumps(
            {
                "variants": len(report["variants"]),
                "repeated_word_runs": len(report["base_repeated_word_runs"]),
                "top_word": report["base_word_histogram_top32"][0],
            },
            indent=2,
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
