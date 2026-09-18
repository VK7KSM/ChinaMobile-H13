#!/usr/bin/env python3
"""Score whether a WAV is speech-like (objective gate for HPI PCM claims).

Does NOT replace operator hearing. Blocks false 'PCM success' from frame
counters / WAV containers alone.

Calibrated on:
  PASS: CHAN_D mbelib WAV (operator-confirmed speech)
  FAIL: HPI Voice OUT wire328 product under VOCODER_IO=0x01 (operator noise)
  FAIL-ish: historical v0.84 HPI (high RMS but lag-2 ~ -0.9 oscillation)
"""
from __future__ import annotations

import argparse
import json
import math
import struct
import wave
from pathlib import Path


def load_wav(path: Path) -> tuple[int, list[int]]:
    with wave.open(str(path), "rb") as handle:
        rate = handle.getframerate()
        data = handle.readframes(handle.getnframes())
    samples = list(struct.unpack("<%dh" % (len(data) // 2), data))
    return rate, samples


def lag_correlation(samples: list[int], lag: int) -> float:
    if lag <= 0 or len(samples) <= lag + 8:
        return 0.0
    a = samples[:-lag]
    b = samples[lag:]
    n = len(a)
    mean_a = sum(a) / n
    mean_b = sum(b) / n
    num = 0.0
    den_a = 0.0
    den_b = 0.0
    for x, y in zip(a, b):
        dx = x - mean_a
        dy = y - mean_b
        num += dx * dy
        den_a += dx * dx
        den_b += dy * dy
    den = math.sqrt(den_a * den_b)
    if den < 1e-9:
        return 0.0
    return num / den


def score(samples: list[int], rate: int = 8000) -> dict:
    n = len(samples)
    if n < max(160, rate // 10):
        return {"ok": False, "reason": "too_short", "duration_s": n / rate}
    rms = math.sqrt(sum(x * x for x in samples) / n)
    peak = max(abs(x) for x in samples)
    fl = max(1, rate // 50)  # 20 ms frames at 8 kHz
    energies = []
    for i in range(0, n - fl, fl):
        fr = samples[i : i + fl]
        energies.append(math.sqrt(sum(x * x for x in fr) / fl))
    energies_sorted = sorted(energies)
    p10 = energies_sorted[int(len(energies_sorted) * 0.1)]
    p50 = energies_sorted[len(energies_sorted) // 2]
    p90 = energies_sorted[int(len(energies_sorted) * 0.9)]
    dyn = (p90 + 1.0) / (p10 + 1.0)
    zc = sum(1 for a, b in zip(samples, samples[1:]) if (a >= 0) != (b >= 0))
    zcr = zc / max(1, n - 1)
    lag1 = lag_correlation(samples, 1)
    lag2 = lag_correlation(samples, 2)
    # Stationary low-level: fraction of frames with RMS < 30
    low_frac = sum(1 for e in energies if e < 30.0) / max(1, len(energies))

    gates = {
        "peak>=8000": peak >= 8000,
        "rms>=400": rms >= 400,
        "dyn>=5": dyn >= 5.0,
        "p90>=500": p90 >= 500,
        "zcr<0.40": zcr < 0.40,
        "lag2>-0.5": lag2 > -0.5,  # reject strong sample-pair oscillation
        "low_frac<0.5": low_frac < 0.5,
    }
    speech_like = all(gates.values())
    return {
        "ok": True,
        "duration_s": round(n / rate, 3),
        "rms": round(rms, 2),
        "peak": peak,
        "frame_rms_p10": round(p10, 2),
        "frame_rms_p50": round(p50, 2),
        "frame_rms_p90": round(p90, 2),
        "dynamic_p90_p10": round(dyn, 2),
        "zcr": round(zcr, 4),
        "lag1_corr": round(lag1, 4),
        "lag2_corr": round(lag2, 4),
        "low_energy_frame_frac": round(low_frac, 4),
        "speech_like": speech_like,
        "gates": gates,
        "note": (
            "speech_like is an objective gate only; operator hearing remains "
            "required before any 'decoded RX PCM' claim"
        ),
    }


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("wavs", nargs="+", type=Path)
    ap.add_argument("--json-out", type=Path, default=None)
    args = ap.parse_args()
    report: dict = {}
    for wav in args.wavs:
        rate, samples = load_wav(wav)
        result = score(samples, rate)
        report[str(wav)] = result
        print(wav.name, json.dumps(result, ensure_ascii=False))
    if args.json_out:
        args.json_out.write_text(json.dumps(report, indent=2), encoding="utf-8")


if __name__ == "__main__":
    main()
