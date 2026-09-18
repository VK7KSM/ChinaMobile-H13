#!/usr/bin/env python3
"""Audit the middle-nibble offset model for SCT3258 major-6/7 transfers."""

from __future__ import annotations

import argparse
import hashlib
import json
from collections import Counter
from pathlib import Path


BOOT_BASE = 0xF800
STRUCTURED_END = 0xFB8C
F272_SETUP = (0xFA63, 0x2E72, 0xFA64, 0x3EF2)


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def words_le(data: bytes) -> list[int]:
    if len(data) % 2:
        raise ValueError("word-oriented input has an odd byte count")
    return [int.from_bytes(data[offset:offset + 2], "little")
            for offset in range(0, len(data), 2)]


def artifact(path: Path, data: bytes) -> dict[str, object]:
    return {"path": str(path.resolve()), "size": len(data), "sha256": sha256(data)}


def transfer(address: int, word: int, known_base: int | None = None) -> dict[str, object]:
    offset = word >> 4 & 0xF
    result = {
        "address": address,
        "address_hex": f"0x{address:04x}",
        "word_hex": f"0x{word:04x}",
        "direction_shape": "write-like" if word >> 12 == 0x6 else "read-like",
        "data_field_hex": f"0x{word >> 8 & 0xF:x}",
        "offset_nibble_hex": f"0x{offset:x}",
        "base_field_hex": f"0x{word & 0xF:x}",
    }
    if known_base is not None:
        result["candidate_effective_word_address_hex"] = f"0x{known_base + offset:04x}"
        result["effective_address_warning"] = (
            "computed under the supported unsigned word-offset model; exact vendor "
            "mnemonic and byte/word nomenclature remain unrecovered"
        )
    return result


def analyze(loader_path: Path, bootled_path: Path, sample_report_path: Path) -> dict[str, object]:
    loader_data = loader_path.read_bytes()
    bootled_data = bootled_path.read_bytes()
    sample_data = sample_report_path.read_bytes()
    if len(loader_data) != 2048 or len(bootled_data) != 2048:
        raise ValueError("loader and BootLED inputs must each be 2048 bytes")
    sample_report = json.loads(sample_data.decode("ascii"))
    if sample_report.get("format") != "sct3258-flash-data136-analysis-v1":
        raise ValueError("unexpected FLASH_DATA136 analysis format")

    loader_words = words_le(loader_data)
    bootled_words = words_le(bootled_data)
    loader = {BOOT_BASE + index: word for index, word in enumerate(loader_words)
              if BOOT_BASE + index < STRUCTURED_END}
    bootled = {BOOT_BASE + index: word for index, word in enumerate(bootled_words)
               if word != 0xFFFF}
    sample = {int(item["address"]): int(item["word_hex"], 16)
              for item in sample_report["flash_data136"]["addressed_words"]}

    expected_loader = {
        F272_SETUP[0]: F272_SETUP[1], F272_SETUP[2]: F272_SETUP[3],
        0xFB1C: 0x6B0E, 0xFB1E: 0x76AE, 0xFB29: 0x7CFD,
        0xFB2A: 0x6CF6, 0xFB2F: 0x750E, 0xFB32: 0x6B0E,
        0xFB34: 0x76AE, 0xFB3F: 0x7CF6, 0xFB40: 0x6CFD,
    }
    for address, word in expected_loader.items():
        if loader.get(address) != word:
            raise ValueError(f"loader anchor mismatch at 0x{address:04x}")
    expected_bootled = {0xF80D: 0x3AF8, 0xF80E: 0x2A08, 0xF811: 0x600A}
    for address, word in expected_bootled.items():
        if bootled.get(address) != word:
            raise ValueError(f"BootLED anchor mismatch at 0x{address:04x}")
    expected_sample = {
        0x203E: 0x2508, 0x203F: 0x35F8,
        0x2040: 0x2405, 0x2041: 0x3430,
        0x2042: 0x7044, 0x2043: 0x6045,
        0x2064: 0x2508, 0x2065: 0x35F8,
        0x2066: 0x2405, 0x2067: 0x3430,
        0x2068: 0x7044, 0x2069: 0x6045,
    }
    for address, word in expected_sample.items():
        if sample.get(address) != word:
            raise ValueError(f"sample anchor mismatch at 0x{address:04x}")

    base_e_accesses = []
    for address in range(0xFA65, STRUCTURED_END):
        word = loader[address]
        if word >> 12 in (0x6, 0x7) and (word & 0xF) == 0xE:
            base_e_accesses.append(transfer(address, word, 0xF272))
    offsets = Counter(int(item["offset_nibble_hex"], 16) for item in base_e_accesses)

    return {
        "format": "sct3258-memory-offset-model-v1",
        "artifacts": {
            "loader": artifact(loader_path, loader_data),
            "bootled": artifact(bootled_path, bootled_data),
            "flash_data136_analysis": artifact(sample_report_path, sample_data),
        },
        "field_model": {
            "major_6_7": "[major 6/7][data field][unsigned offset nibble][base field]",
            "status": (
                "Supported by two independent base+same-offset copy sequences and the "
                "BootLED zero-offset store. Exact vendor mnemonics remain unknown."
            ),
        },
        "bootled_zero_offset_anchor": {
            "base_setup": "F80D 3AF8; F80E 2A08 -> field A = F808",
            "transfer": transfer(0xF811, bootled[0xF811], 0xF808),
        },
        "independent_sample_copy_anchors": [
            {
                "base_5_setup": "203E 2508; 203F 35F8 -> field 5 = F808",
                "base_4_setup": "2040 2405; 2041 3430 -> field 4 = 3005",
                "read": transfer(0x2042, sample[0x2042], 0x3005),
                "write": transfer(0x2043, sample[0x2043], 0xF808),
            },
            {
                "base_5_setup": "2064 2508; 2065 35F8 -> field 5 = F808",
                "base_4_setup": "2066 2405; 2067 3430 -> field 4 = 3005",
                "read": transfer(0x2068, sample[0x2068], 0x3005),
                "write": transfer(0x2069, sample[0x2069], 0xF808),
            },
        ],
        "loader_reverse_copy_anchors": [
            {
                "read": transfer(0xFB29, loader[0xFB29]),
                "write": transfer(0xFB2A, loader[0xFB2A]),
                "shared_offset_hex": "0xf",
            },
            {
                "read": transfer(0xFB3F, loader[0xFB3F]),
                "write": transfer(0xFB40, loader[0xFB40]),
                "shared_offset_hex": "0xf",
            },
        ],
        "f272_base_setup": {
            "words": ["0x2e72", "0x3ef2"],
            "addresses_hex": ["0xfa63", "0xfa64"],
            "base_field_hex": "0xe",
            "base_value_hex": "0xf272",
        },
        "f272_base_accesses": base_e_accesses,
        "f272_offset_histogram": [
            {"offset_hex": f"0x{offset:x}", "count": count,
             "candidate_effective_word_address_hex": f"0x{0xF272 + offset:04x}"}
            for offset, count in sorted(offsets.items())
        ],
        "corrected_d6_source_transaction": {
            "producer_words": ["FB1E 76AE", "FB34 76AE"],
            "shape": "read-like [field E base + offset A] -> field 6",
            "base_value_hex": "0xf272",
            "candidate_effective_word_address_hex": "0xf27c",
            "warning": "F27C is an offset-model result, not a recovered vendor register name",
        },
        "bounded_conclusion": (
            "The middle nibble of major-6/7 transfers is supported as a small unsigned "
            "offset shared by source and destination copies, not merely an opaque mode. "
            "F272 is therefore a base candidate for an accessed word-register block spanning "
            "the observed offsets 0, 5, 6, 7, 8, A, and F. In particular, FB1E/FB34 read "
            "field 6 from candidate address F27C (F272+A), correcting the earlier shorthand "
            "that described them as reads from bare F272. The block is strongly compatible "
            "with memory-mapped HPI registers but remains unnamed without an internal map."
        ),
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--loader", type=Path, required=True)
    parser.add_argument("--bootled", type=Path, required=True)
    parser.add_argument("--flash-sample-report", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if args.output.exists():
        parser.error(f"refusing to overwrite existing output: {args.output}")
    report = analyze(args.loader, args.bootled, args.flash_sample_report)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2, ensure_ascii=True) + "\n",
                           encoding="ascii", newline="\n")
    print(json.dumps({
        "format": report["format"],
        "field_model": report["field_model"],
        "f272_offset_histogram": report["f272_offset_histogram"],
        "corrected_d6_source_transaction": report["corrected_d6_source_transaction"],
        "bounded_conclusion": report["bounded_conclusion"],
    }, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
