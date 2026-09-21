#!/usr/bin/env python3
"""导出模块 MCU 的 AT 命令表与主动上报消息名。

与 `dump_mcu_console.py` 是一对：控制台是厂商的调试口，AT 是产品接口。
把两张表都摆出来，「重写服务替换原厂程序」这条路线才谈得上有没有盲区
（H13_new.md 2.9.7 曾断言没有盲区，但当时并没有把表列全）。

命令名在固件里是一段连续的字符串区；主动上报消息名另有一组，形如
`AUTOUPDATA*` / `SENDMOUDULE*` / `STARTUP`，出现在模块**自己发出**的
那一侧。两者在协议里角色不同，不能混为一谈。

用法：
    python dump_at_commands.py
    python dump_at_commands.py --json out.json --md out.md
"""
from __future__ import annotations

import argparse
import json
import re
from pathlib import Path

BASE = 0x08000000
DEFAULT_IMAGE = (Path(__file__).resolve().parents[2]
                 / "firmware" / "mcu"
                 / "Module_current_0.3.66_full_flash_256k.bin")

# 命令名字符串区（连续），与模块自发消息区分开
TABLE_LO = 0x08007540
TABLE_HI = 0x08007790

ASYNC_MARKERS = ("AUTOUPDATA", "SENDMOUDULE", "STARTUP")

# 出厂测试专用的一组：正常业务不该碰
TEST_PREFIXES = ("DMOTEST",)

# 会让设备发射的
RF_DANGER = {"DMOPTT"}


def strings(flash: bytes, lo: int, hi: int) -> list[tuple[int, str]]:
    out = []
    off = lo - BASE
    end = hi - BASE
    while off < end:
        stop = flash.find(b"\x00", off, end)
        if stop < 0:
            break
        raw = flash[off:stop]
        if raw and all(0x20 <= c <= 0x7E for c in raw):
            out.append((BASE + off, raw.decode()))
        off = stop + 1
    return out


def find_async(flash: bytes) -> list[str]:
    seen = []
    for m in re.finditer(rb"DMO[A-Z0-9_]{4,40}", flash):
        s = m.group().decode()
        if any(k in s for k in ASYNC_MARKERS) and s not in seen:
            seen.append(s)
    return sorted(seen)


def classify(name: str) -> str:
    # 带参数的命令在表里存成 "DMOPTT=" 这种形式，比对前先去掉尾部等号，
    # 否则 DMOPTT 会落不进「会开射频」——这类判错会让危险命令被当成普通命令。
    name = name.rstrip("=")
    if name in RF_DANGER:
        return "会开射频"
    if name.startswith(TEST_PREFIXES):
        return "出厂测试"
    if name.startswith("DMOGET") or name in ("DMOCONNECT", "DMOEAREXIST"):
        return "只读"
    return "改状态"


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--image", type=Path, default=DEFAULT_IMAGE)
    ap.add_argument("--json", type=Path)
    ap.add_argument("--md", type=Path)
    a = ap.parse_args()
    flash = a.image.read_bytes()

    cmds = [(addr, s) for addr, s in strings(flash, TABLE_LO, TABLE_HI)
            if s.startswith("DMO")]
    asyncs = [s for s in find_async(flash)
              if s not in {c for _, c in cmds}]

    order = {"只读": 0, "改状态": 1, "出厂测试": 2, "会开射频": 3}
    entries = [{"命令": s, "地址": "0x%08x" % addr, "等级": classify(s)}
               for addr, s in cmds]
    entries.sort(key=lambda e: (order.get(e["等级"], 9), e["命令"]))

    print("AT 命令 %d 条\n" % len(entries))
    print("%-24s %s" % ("命令", "等级"))
    print("-" * 40)
    for e in entries:
        print("%-24s %s" % (e["命令"], e["等级"]))
    print("\n模块主动上报 %d 条（不是命令，是模块自己发出来的）\n" % len(asyncs))
    for s in asyncs:
        print("   " + s)

    if a.json:
        a.json.write_text(json.dumps(
            {"命令": entries, "主动上报": asyncs},
            ensure_ascii=False, indent=1), encoding="utf-8")
        print("\n已写 %s" % a.json)
    if a.md:
        lines = ["| AT 命令 | 等级 |", "|---|---|"]
        lines += ["| `%s` | %s |" % (e["命令"], e["等级"]) for e in entries]
        lines += ["", "模块主动上报（非命令）：", ""]
        lines += ["- `%s`" % s for s in asyncs]
        a.md.write_text("\n".join(lines) + "\n", encoding="utf-8")
        print("已写 %s" % a.md)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
