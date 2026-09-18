#!/usr/bin/env python3
"""Reproduce the TLV320AIC3204 right-DAC biquad found in H13 firmware."""

from __future__ import annotations

import argparse
import cmath
import math


RAW_COEFFICIENTS = {
    "N0": 0x7C6B00,
    "N1": 0xB3B300,
    "N2": 0x382800,
    "D1": 0x4B3C00,
    "D2": 0xC94A00,
}

DEFAULT_FREQUENCIES = (0, 100, 300, 500, 1000, 1500, 2000, 2500, 3000,
                       4000, 5000, 6000, 8000, 10000)


def signed24(value: int) -> int:
    return value - (1 << 24) if value & (1 << 23) else value


def transfer_coefficients() -> tuple[list[float], list[float]]:
    values = {name: signed24(value) for name, value in RAW_COEFFICIENTS.items()}
    scale = float(1 << 23)

    # SLAA557 equation 11:
    # H(z) = (N0 + 2*N1*z^-1 + N2*z^-2) /
    #        (2^23 - 2*D1*z^-1 - D2*z^-2)
    numerator = [values["N0"] / scale, 2 * values["N1"] / scale,
                 values["N2"] / scale]
    denominator = [1.0, -2 * values["D1"] / scale,
                   -values["D2"] / scale]
    return numerator, denominator


def quadratic_roots(coefficients: list[float]) -> tuple[complex, complex]:
    a, b, c = coefficients
    discriminant = cmath.sqrt(b * b - 4 * a * c)
    return ((-b + discriminant) / (2 * a),
            (-b - discriminant) / (2 * a))


def response(
    frequency: float,
    sample_rate: float,
    numerator: list[float],
    denominator: list[float],
) -> complex:
    z1 = cmath.exp(-2j * math.pi * frequency / sample_rate)
    z2 = z1 * z1
    return ((numerator[0] + numerator[1] * z1 + numerator[2] * z2) /
            (denominator[0] + denominator[1] * z1 + denominator[2] * z2))


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--sample-rate", type=float, default=24000.0)
    parser.add_argument("--frequency", type=float, action="append", dest="frequencies")
    args = parser.parse_args()

    numerator, denominator = transfer_coefficients()
    print("Raw and signed 24-bit coefficients:")
    for name, raw in RAW_COEFFICIENTS.items():
        print(f"  {name}: 0x{raw:06x}  {signed24(raw):d}")

    print(f"\nNormalized numerator:   {numerator}")
    print(f"Normalized denominator: {denominator}")
    print("Zeros:")
    for root in quadratic_roots(numerator):
        print(f"  {root.real:+.9f}{root.imag:+.9f}j  abs={abs(root):.9f}")
    print("Poles:")
    for root in quadratic_roots(denominator):
        print(f"  {root.real:+.9f}{root.imag:+.9f}j  abs={abs(root):.9f}")

    frequencies = args.frequencies or DEFAULT_FREQUENCIES
    print(f"\nFrequency response at Fs={args.sample_rate:g} Hz:")
    print("  frequency_hz   magnitude_db   phase_deg")
    for frequency in frequencies:
        if not 0 <= frequency <= args.sample_rate / 2:
            raise SystemExit(f"frequency {frequency:g} is outside 0..Nyquist")
        value = response(frequency, args.sample_rate, numerator, denominator)
        magnitude_db = 20 * math.log10(abs(value))
        phase_deg = math.degrees(cmath.phase(value))
        print(f"  {frequency:12g}   {magnitude_db:12.6f}   {phase_deg:9.4f}")


if __name__ == "__main__":
    main()
