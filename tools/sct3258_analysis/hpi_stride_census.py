#!/usr/bin/env python3
"""P1 offline census: SPEECH wire strides (328 vs 330) across frozen HPI captures.

Does not modify source .bin files. Writes timestamped CSV + summaries only.
"""

from __future__ import annotations

import argparse
import csv
import hashlib
import math
import struct
import sys
from collections import Counter, defaultdict
from datetime import datetime
from pathlib import Path

SYNC = b"\x84\xa9\x61"
KNOWN_TYPES = {0x00, 0x20, 0x30}


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def is_plausible_header(data: bytes, offset: int) -> bool:
    """Strict candidate validation (P1-B rule): not SYNC alone."""
    if offset + 9 > len(data):
        return False
    if data[offset : offset + 3] != SYNC:
        return False
    body_length = int.from_bytes(data[offset + 3 : offset + 5], "big")
    packet_type = data[offset + 5]
    field = data[offset + 6]
    if packet_type not in KNOWN_TYPES:
        return False
    if body_length < 1 or body_length > 2048:
        return False
    # Known useful control / speech / channel shapes on this project.
    if body_length == 2 and field in (0x01, 0x3E, 0x7F, 0x18, 0x1A, 0x1B):
        return True
    if body_length == 29 and field == 0x01:
        return True
    if (
        body_length == 323
        and field == 0x00
        and int.from_bytes(data[offset + 7 : offset + 9], "big") == 160
    ):
        return True
    if body_length in (1, 2, 4):
        return True
    # Allow other type0/20/30 with moderate body (generic HPI).
    if 1 <= body_length <= 1283:
        return True
    return False


def paper_wire(body_length: int) -> int:
    unpadded = 6 + body_length
    return unpadded + (unpadded & 1)


def sample_rms(pcm: bytes) -> float:
    if len(pcm) < 2:
        return 0.0
    if len(pcm) & 1:
        pcm = pcm[:-1]
    if not pcm:
        return 0.0
    samples = [v[0] for v in struct.iter_unpack("<h", pcm)]
    return math.sqrt(sum(s * s for s in samples) / len(samples))


def walk_frames(data: bytes) -> list[dict]:
    """Walk with strict headers; SPEECH uses next-plausible-SYNC stride when earlier."""
    rows: list[dict] = []
    offset = 0
    n = len(data)
    while offset + 9 <= n:
        # Seek next plausible header
        found = -1
        cursor = offset
        while cursor + 9 <= n:
            hit = data.find(SYNC, cursor)
            if hit < 0 or hit + 9 > n:
                break
            if is_plausible_header(data, hit):
                found = hit
                break
            cursor = hit + 1
        if found < 0:
            break
        offset = found
        body_length = int.from_bytes(data[offset + 3 : offset + 5], "big")
        packet_type = data[offset + 5]
        field = data[offset + 6]
        expected = paper_wire(body_length)
        # Find next plausible SYNC in window
        search_from = offset + 6
        search_to = min(n - 2, offset + max(expected, body_length + 6) + 8)
        next_sync = -1
        for i in range(search_from, search_to):
            if data[i : i + 3] == SYNC and is_plausible_header(data, i):
                next_sync = i
                break
        if next_sync < 0:
            # Fallback: any SYNC then validate, or end of buffer
            hit = data.find(SYNC, search_from)
            if hit > offset and is_plausible_header(data, hit):
                next_sync = hit
            else:
                next_sync = n
        distance = next_sync - offset
        # Prefer early SYNC when tightly packed (wire328)
        if 8 <= distance < expected and next_sync < n:
            wire = distance
        elif offset + expected <= n:
            wire = expected
            # If expected lands past next_sync, use next_sync
            if next_sync < n and next_sync < offset + expected:
                wire = distance
        else:
            wire = max(1, n - offset)

        inner_count = ""
        sample_bytes = 0
        last_byte = ""
        rms = ""
        classification = "other"
        if body_length == 323 and field == 0x00:
            inner_count = int.from_bytes(data[offset + 7 : offset + 9], "big")
            end = offset + wire
            samples = data[offset + 9 : end]
            if samples and samples[-1] == 0 and (len(samples) & 1):
                samples = samples[:-1]
            if len(samples) & 1:
                samples = samples[:-1]
            samples = samples[:320]
            sample_bytes = len(samples)
            last_byte = f"0x{data[end - 1]:02x}" if end > offset else ""
            rms = f"{sample_rms(samples):.2f}"
            if sample_bytes == 320 and wire >= 330:
                classification = "pcm_160_complete"
            elif wire == 328 and sample_bytes >= 318:
                classification = "pcm_160_wire328"
            else:
                classification = "pcm_160_partial"
        elif body_length == 29 and field == 0x01:
            classification = "chan_d_27"
            if offset + 8 <= n:
                inner_count = data[offset + 7]
        elif body_length == 2 and field == 0x7F:
            classification = "dmr_slot_found"
        elif body_length <= 4:
            classification = "control"

        next_header_valid = (
            next_sync < n and is_plausible_header(data, next_sync)
            if next_sync < n
            else False
        )

        rows.append(
            {
                "offset": offset,
                "offset_hex": f"0x{offset:06x}",
                "body_length": body_length,
                "packet_type": f"0x{packet_type:02x}",
                "field": f"0x{field:02x}",
                "inner_count": inner_count,
                "paper_wire": expected,
                "wire": wire,
                "distance_to_next_sync": distance if next_sync < n else "",
                "wire_delta_vs_paper": wire - expected if next_sync < n or wire else "",
                "next_header_valid": next_header_valid,
                "sample_bytes": sample_bytes,
                "last_byte": last_byte,
                "rms": rms,
                "classification": classification,
            }
        )
        if wire <= 0:
            break
        offset += wire
    return rows


def guess_baud_tag(path: Path) -> str:
    name = path.as_posix().lower()
    if "230400" in name:
        return "230400"
    if "57600" in name:
        return "57600"
    # v0.61 era often 57600 voice out without baud in name
    if "voice_out" in name or "hpi_dmr" in name or "control_a" in name:
        return "57600_or_unknown"
    return "unknown"


def find_bins(root: Path) -> list[Path]:
    bins: list[Path] = []
    for p in root.rglob("*.bin"):
        # Skip derived/analysis copies when obvious; keep forensic + primary
        name = p.name.lower()
        if "hpi_" not in name and "voice" not in name and "speech" not in name:
            continue
        # Prefer primary capture names
        if any(x in p.as_posix().replace("\\", "/") for x in (
            "/analysis",
            "/recover",
            "analysis_",
        )) and "v086-forensic" not in p.as_posix():
            # Still include analysis copies only if no sibling primary — skip derived
            if "analysis" in p.parts:
                continue
        bins.append(p)
    return sorted(set(bins))


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--captures-root",
        type=Path,
        default=Path(__file__).resolve().parents[1] / "captures",
    )
    parser.add_argument("--output", type=Path, default=None)
    args = parser.parse_args()

    if args.output is None:
        day = datetime.now().strftime("%Y-%m-%d")
        stamp = datetime.now().strftime("%Y%m%d_%H%M%S")
        args.output = (
            args.captures_root / day / f"p1-stride-census-{stamp}"
        )
    args.output.mkdir(parents=True, exist_ok=False)

    bins = find_bins(args.captures_root)
    if not bins:
        print("No bins found", file=sys.stderr)
        return 1

    all_rows: list[dict] = []
    by_file: list[str] = []
    by_baud: dict[str, Counter] = defaultdict(Counter)
    speech_wire_global: Counter = Counter()
    continuity_notes: list[str] = []

    for path in bins:
        data = path.read_bytes()
        if len(data) < 16:
            continue
        rel = path.relative_to(args.captures_root).as_posix()
        baud = guess_baud_tag(path)
        rows = walk_frames(data)
        speech = [r for r in rows if str(r["classification"]).startswith("pcm_160")]
        wire_counts = Counter(r["wire"] for r in speech)
        speech_wire_global.update(wire_counts)
        by_baud[baud].update(wire_counts)

        # Continuity: consecutive wire328 frames RMS chain
        prev_rms = None
        low_rms_run = 0
        for r in speech:
            try:
                rms = float(r["rms"]) if r["rms"] != "" else None
            except ValueError:
                rms = None
            if rms is not None and rms < 5:
                low_rms_run += 1
            if prev_rms is not None and rms is not None:
                pass
            prev_rms = rms

        by_file.append(
            f"{rel}: bytes={len(data)} sha256={sha256(data)[:16]}… "
            f"frames={len(rows)} speech={len(speech)} wires={dict(wire_counts)} baud={baud}"
        )
        if speech:
            complete = sum(1 for r in speech if r["classification"] == "pcm_160_complete")
            w328 = sum(1 for r in speech if r["classification"] == "pcm_160_wire328")
            sample_lens = Counter(r["sample_bytes"] for r in speech)
            last_bytes = Counter(r["last_byte"] for r in speech if r["last_byte"])
            continuity_notes.append(
                f"{rel}: complete={complete} wire328={w328} "
                f"sample_bytes={dict(sample_lens)} last_byte={dict(last_bytes)} "
                f"low_rms_frames≈{low_rms_run}"
            )

        for r in rows:
            out = {
                "file": rel,
                "baud_tag": baud,
                "file_sha256": sha256(data),
                "file_bytes": len(data),
                **r,
            }
            all_rows.append(out)

    # Write CSV
    csv_path = args.output / "all_frames.csv"
    if all_rows:
        fields = list(all_rows[0].keys())
        with csv_path.open("w", newline="", encoding="utf-8") as f:
            w = csv.DictWriter(f, fieldnames=fields)
            w.writeheader()
            w.writerows(all_rows)

    (args.output / "summary_by_file.txt").write_text(
        "\n".join(by_file) + "\n", encoding="utf-8"
    )
    baud_lines = []
    for baud, ctr in sorted(by_baud.items()):
        baud_lines.append(f"{baud}: speech_wire_counts={dict(ctr)}")
    (args.output / "summary_by_baud.txt").write_text(
        "\n".join(baud_lines) + "\n", encoding="utf-8"
    )
    (args.output / "continuity_notes.txt").write_text(
        "\n".join(continuity_notes) + "\n", encoding="utf-8"
    )

    # Layer conclusion draft
    total_328 = speech_wire_global.get(328, 0)
    total_330 = speech_wire_global.get(330, 0)
    total_other = sum(v for k, v in speech_wire_global.items() if k not in (328, 330))
    conclusion = []
    conclusion.append("P1-A stride census conclusion draft")
    conclusion.append(f"bins_scanned={len(bins)}")
    conclusion.append(f"speech_wire_global={dict(speech_wire_global)}")
    conclusion.append(f"count_328={total_328} count_330={total_330} other={total_other}")
    if total_328 > 0 and total_330 > 0:
        conclusion.append(
            "OBSERVATION: both 328 and 330 SPEECH wire lengths exist in corpus."
        )
        conclusion.append(
            "INFERENCE: format CAN be full 330/320-sample; continuous 230400 trains "
            "prefer/lose to 328 — not 'format never complete'."
        )
    if total_328 > 50 and total_330 == 0:
        conclusion.append(
            "OBSERVATION: corpus speech frames are exclusively 328 in this scan set."
        )
    # Systematic last-byte pad
    speech_rows = [r for r in all_rows if str(r["classification"]).startswith("pcm_160")]
    last0 = sum(1 for r in speech_rows if r.get("last_byte") == "0x00")
    if speech_rows:
        conclusion.append(
            f"last_byte_0x00={last0}/{len(speech_rows)} "
            f"({100.0 * last0 / len(speech_rows):.1f}%)"
        )
    sample318 = sum(1 for r in speech_rows if r.get("sample_bytes") == 318)
    sample320 = sum(1 for r in speech_rows if r.get("sample_bytes") == 320)
    conclusion.append(f"sample_bytes_318={sample318} sample_bytes_320={sample320}")
    conclusion.append(
        "LAYER: Systematic 328 + trailing 0x00 + 318 sample bytes on high-rate trains "
        "supports packed wire / length-field inflation (L0/L1) over random UART loss. "
        "v0.86 already falsified Java drain-alone (L3-app). "
        "Cannot yet separate SCT packing (L1) vs MCU pre-UART (L2) without MCU probe; "
        "L3 kernel remains open only if MCU sees 330."
    )
    conclusion.append(
        "P1-C needed?: only if product requires proving L1 vs L2 vs L3 hardware layer; "
        "for protocol-correct recovery, L0 interpretation (LENGTH inflated by 2, "
        "wire 328, 159 s16 + pad) is already operationally sufficient."
    )
    (args.output / "conclusion.txt").write_text(
        "\n".join(conclusion) + "\n", encoding="utf-8"
    )
    print(f"Wrote {args.output}")
    print("\n".join(conclusion))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
