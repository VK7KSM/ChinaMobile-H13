#!/usr/bin/env python3
"""Count HPI (type, field, body_length) occurrences in capture bins."""
from __future__ import annotations

import argparse
from pathlib import Path

SYNC = b"\x84\xa9\x61"


def census(data: bytes) -> dict[tuple[int, int, int], int]:
    stats: dict[tuple[int, int, int], int] = {}
    n = len(data)
    i = 0
    while True:
        j = data.find(SYNC, i)
        if j < 0 or j + 9 > n:
            break
        body_len = int.from_bytes(data[j + 3 : j + 5], "big")
        packet_type = data[j + 5]
        field = data[j + 6]
        key = (packet_type, field, body_len)
        stats[key] = stats.get(key, 0) + 1
        i = j + 3
    return stats


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("paths", nargs="+", type=Path)
    args = ap.parse_args()
    for root in args.paths:
        files = []
        if root.is_file():
            files = [root]
        elif root.is_dir():
            files = sorted(list(root.rglob("*.bin")) + list(root.rglob("*.raw")))
        for path in files:
            if path.stat().st_size < 200:
                continue
            data = path.read_bytes()
            stats = census(data)
            s160 = sum(
                v
                for (pt, fld, bl), v in stats.items()
                if pt in (0x20, 0x30) and fld == 0 and bl == 323
            )
            s1283 = sum(
                v
                for (pt, fld, bl), v in stats.items()
                if pt in (0x20, 0x30) and fld == 0 and bl == 1283
            )
            if s160 == 0 and s1283 == 0 and sum(stats.values()) < 20:
                continue
            print(f"=== {path} size={len(data)}")
            print(f"  speech160(LENGTH=323)={s160} speech1283(LENGTH=1283)={s1283} keys={len(stats)}")
            for key, value in sorted(stats.items(), key=lambda item: -item[1])[:12]:
                print(f"  type=0x{key[0]:02x} field=0x{key[1]:02x} length={key[2]} count={value}")


if __name__ == "__main__":
    main()
