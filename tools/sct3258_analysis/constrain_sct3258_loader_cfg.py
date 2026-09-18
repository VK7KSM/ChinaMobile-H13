#!/usr/bin/env python3
"""Combine host, HPI, and anonymous-CFG constraints without naming the ISA."""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path


BOOT_BASE = 0xF800
MOTIF_WORDS = (0xA906, 0xA907, 0xA806, 0xA807, 0x7CFD, 0x6CF6, 0x7CF6, 0x6CFD)


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def words_le(data: bytes) -> list[int]:
    if len(data) % 2:
        raise ValueError("loader is not word aligned")
    return [int.from_bytes(data[offset : offset + 2], "little") for offset in range(0, len(data), 2)]


def artifact(path: Path) -> dict[str, object]:
    raw = path.read_bytes()
    return {"path": str(path.resolve()), "size": len(raw), "sha256": sha256(raw)}


def occurrences(words: list[int], target: int) -> list[dict[str, object]]:
    return [
        {"address": BOOT_BASE + index, "address_hex": f"0x{BOOT_BASE + index:04x}"}
        for index, word in enumerate(words)
        if word == target
    ]


def require_block(cfg: dict[str, object], start: int, expected: list[int]) -> None:
    block = next((item for item in cfg["blocks"] if int(item["start"]) == start), None)
    if block is None:
        raise ValueError(f"missing CFG block 0x{start:04x}")
    actual = [int(value, 16) for value in block["words_hex"]]
    if actual != expected:
        raise ValueError(f"unexpected words in CFG block 0x{start:04x}")


def analyze(loader_path: Path, cfg_path: Path, host_path: Path,
            datasheet_path: Path, packet_path: Path) -> dict[str, object]:
    loader = loader_path.read_bytes()
    if len(loader) != 2048:
        raise ValueError("expected a 2048-byte loader")
    words = words_le(loader)
    cfg = json.loads(cfg_path.read_text(encoding="ascii"))
    host = json.loads(host_path.read_text(encoding="ascii"))
    if cfg["format"] != "sct3258-loader-candidate-cfg-v1":
        raise ValueError("expected candidate CFG v1")
    if host["format"] != "sct3258-loader-host-wire-contract-v1":
        raise ValueError("expected host wire contract v1")
    if cfg["loader"]["sha256"].lower() != sha256(loader):
        raise ValueError("CFG loader hash mismatch")

    loop_a = [0x92B2, 0x0A4F, 0x6B0E, 0xB807, 0x76AE, 0xA5A2, 0x45F0,
              0xA906, 0xA593, 0x4DF9, 0xB822, 0xB8A3, 0xA793, 0xBEA4, 0x04F1]
    loop_b = [0xA433, 0xBBF3, 0x6B0E, 0xB807, 0x76AE, 0xA5A2, 0x45F0,
              0xA907, 0xA593, 0x4DF9, 0xB822, 0xB8A3, 0xA793, 0xBEA4, 0x04F1]
    loop_c = [0x45FE, 0xA907, 0x74A6, 0xA807, 0x8EA2, 0x748B,
              0xA906, 0x8EA5, 0x64A6, 0xA806, 0xA4B3, 0x04F4]
    require_block(cfg, 0xFB1A, loop_a)
    require_block(cfg, 0xFB30, loop_b)
    require_block(cfg, 0xFB6D, loop_c)

    motif = {
        f"0x{target:04x}": occurrences(words, target)
        for target in MOTIF_WORDS
    }
    return {
        "format": "sct3258-loader-cfg-constraints-v2",
        "artifacts": {
            "loader": artifact(loader_path),
            "candidate_cfg": artifact(cfg_path),
            "host_wire_contract": artifact(host_path),
            "sct3258_datasheet": artifact(datasheet_path),
            "packet_interface": artifact(packet_path),
        },
        "confirmed_host_transaction_units": {
            "instruction_header_bytes": 12,
            "instruction_header_words": 6,
            "data_header_bytes": 6,
            "data_header_words": 3,
            "instruction_extra_transport_prefix_bytes": 6,
            "payload_bytes_per_declared_word": 2,
            "payload_is_wrapped_by_creatcmd": False,
        },
        "confirmed_hpi_physical_constraints": {
            "evidence_pages": {
                "sct3258_datasheet": [30, 33],
                "packet_interface": [87],
            },
            "runtime_rx_fifo_words": 64,
            "runtime_tx_fifo_words": 64,
            "fifo_word_bits": 16,
            "runtime_transfer_multiple_bytes": 2,
            "runtime_transfer_order": "most-significant byte first",
            "reset_boot_words": 1024,
            "reset_boot_transfer_order": "low-byte first",
        },
        "mechanical_cfg_constraints": {
            "early_back_edge_loops": [
                {"range": "0xfa3d..0xfa45", "span_words": 9},
                {"range": "0xfa4b..0xfa5a", "span_words": 16},
            ],
            "paired_15_word_loops": [
                {"range": "0xfb1a..0xfb28", "selector_word": "0xa906"},
                {"range": "0xfb30..0xfb3e", "selector_word": "0xa907"},
            ],
            "paired_loop_common_suffix_words_after_selector": 7,
            "third_loop_range": "0xfb6d..0xfb78",
            "third_loop_contains_all_a8_a9_6_7_forms": True,
            "motif_word_occurrences": motif,
            "out_of_loader_candidate_branch_remains_quarantined": "0xfbc5 -> 0xfc30",
        },
        "competing_role_hypotheses": [
            {
                "name": "hpi_rx_tx_endpoint_handlers",
                "support": (
                    "The paired loops select 0xa906/0xa907 and later 0xa806/0xa807; "
                    "the third loop contains all four forms. The documented HPI has paired RX/TX FIFOs."
                ),
                "gap": "No SCT3258 ISA semantics map these words to HPI registers or directions.",
            },
            {
                "name": "instruction_data_destination_handlers",
                "support": (
                    "The host exposes separate instruction and data section headers followed by raw payloads."
                ),
                "gap": (
                    "The instruction header has an extra three-word transport prefix, and no control-flow "
                    "edge or opcode semantics currently assigns either loop to a memory class."
                ),
            },
        ],
        "bounded_conclusion": (
            "The 6/7 paired motifs are real and recur in a third loop, but current evidence cannot choose "
            "between HPI direction and instruction/data destination roles. The early 9/16-word loops remain "
            "receive/framing candidates only. No loader patch or device experiment follows from this report."
        ),
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--loader", type=Path, required=True)
    parser.add_argument("--cfg", type=Path, required=True)
    parser.add_argument("--host-contract", type=Path, required=True)
    parser.add_argument("--datasheet", type=Path, required=True)
    parser.add_argument("--packet-interface", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if args.output.exists():
        parser.error(f"refusing to overwrite existing output: {args.output}")
    report = analyze(args.loader, args.cfg, args.host_contract, args.datasheet,
                     args.packet_interface)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2, ensure_ascii=True) + "\n",
                           encoding="ascii", newline="\n")
    print(json.dumps({
        "format": report["format"],
        "motif_word_occurrences": report["mechanical_cfg_constraints"]["motif_word_occurrences"],
        "competing_role_hypotheses": [item["name"] for item in report["competing_role_hypotheses"]],
    }, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
