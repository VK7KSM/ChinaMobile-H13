#!/usr/bin/env python3
"""把 MCU 固件里的调试控制台命令表整表导出。

0.3.66 固件自带一个工厂/调试控制台，挂在 `/dev/ttyHS0` 上，与 AT 命令共用
同一条串口——宿主一直在用的 `memread`/`memwrite1`/`memwrite4` 就是其中三条
（H13_new.md 2.9.44）。本脚本把整张表导出来：命令名、参数个数、完成日志、
处理体调用的固件例程。

识别方式：分发器对每条命令都是同一个形状

    ADR  r1, <命令名字符串>
    LDR  r0, [sp, #0x1b0]        ; 或 MOV r0, r5
    BL   <比较>                  ; 0x080061c2 / 0x08006212
    CBZ/CMP ... 不匹配就跳下一条
    ...                          ; 取参、调用实现
    ADR  r1, <"<命令名> cmp">     ; 完成日志
    BL   <打印>

因此只要扫 ADR 指向命令名字符串的位置，再往后扫到下一条命令的 ADR，
中间就是该命令的处理体。

用法：
    python dump_mcu_console.py                 # 打印整表
    python dump_mcu_console.py --json out.json # 同时导出结构化结果
    python dump_mcu_console.py --md out.md     # 导出 Markdown 表
"""
from __future__ import annotations

import argparse
import json
import re
import struct
from pathlib import Path

BASE = 0x08000000
DEFAULT_IMAGE = (Path(__file__).resolve().parents[2]
                 / "firmware" / "mcu"
                 / "Module_current_0.3.66_full_flash_256k.bin")

# 分发器所在区间（含其字符串常量），由 2.9.44 的定位得出
DISPATCH_LO = 0x0800B900
DISPATCH_HI = 0x0800E100

CMP_FUNCS = {0x080061C2, 0x08006212}      # 命令名比较
PARSE_FUNCS = {0x08012EE0, 0x0801306C, 0x0801312A}   # 取参
LOG_FUNCS = {0x08012DC0}                  # 打印

# 危险等级：只读、改状态、开射频、写非易失存储
READ_ONLY = {
    "version", "gettick", "showsyscfg", "showtestpara", "getslotint",
    "sct3258read", "sct3258read1", "sct3258prostr", "sct3258hwver",
    "sct3258swver", "sct3258cidsn", "nandlistbadblock", "getsm",
    "getsleepstat", "readreg17val", "showcurrsq", "hobibstat",
    "getchandcnt", "sct3258getoobe", "memread", "ramlog", "print",
    "get2571lockflag", "read2571", "readeeprom", "nandreadmain",
    "nandreadspare", "nandgetfeature",
}
RF_DANGER = {
    "pttctrl", "rfswon", "rfswoff", "txvcovccon", "txvcovccoff",
    "sct3258entertx", "sct3258anaentertx", "sct3258callstart",
    "sct3258anacallstart", "sct3258dmrcallstart", "setdbslottx",
    "sct3258pwrval", "dacset", "sct3258dc13calstart", "sct3258dc17calstart",
}
NONVOLATILE = {
    "writeeeprom", "nanderaseblock", "nandmarkbadblock", "nandwritepage",
    "sct3258dspupdate", "ddreboot",
}
RECEIVE = {
    "sct3258enterrx", "sct3258rxstart", "sct3258rxstop", "sct3258initcfg",
    "sct3258anaenterrx", "sct3258anarxstart", "sct3258anarxstop",
    "sct3258anainitcfg",
}


def cstr(flash: bytes, addr: int, limit: int = 96) -> str | None:
    off = addr - BASE
    if off < 0 or off >= len(flash):
        return None
    end = flash.find(b"\x00", off, off + limit)
    if end < 0:
        return None
    raw = flash[off:end]
    # 固件里的日志字符串几乎都带回车换行结尾，按纯可打印判定会把它们
    # 全部丢掉——完成日志（"<命令> cmp" 后跟 CRLF）就是这样一条都
    # 认不出来的。
    if not raw or any(not (0x20 <= c <= 0x7E) and c not in (0x09, 0x0A, 0x0D)
                      for c in raw):
        return None
    return raw.decode().strip()


def adr_sites(flash: bytes, lo: int, hi: int):
    """区间内的 ADR 指令：返回 (指令地址, 目标地址)。"""
    for i in range(lo - BASE, hi - BASE, 2):
        hw = struct.unpack_from("<H", flash, i)[0]
        if (hw & 0xF800) != 0xA000:
            continue
        pc = BASE + i + 4
        yield BASE + i, (pc & ~3) + (hw & 0xFF) * 4


def bl_targets(flash: bytes, lo: int, hi: int):
    for i in range(lo - BASE, hi - BASE, 2):
        hw1 = struct.unpack_from("<H", flash, i)[0]
        if (hw1 & 0xF800) != 0xF000:
            continue
        hw2 = struct.unpack_from("<H", flash, i + 2)[0]
        if (hw2 & 0xD000) != 0xD000:
            continue
        s = (hw1 >> 10) & 1
        imm10 = hw1 & 0x3FF
        j1 = (hw2 >> 13) & 1
        j2 = (hw2 >> 11) & 1
        imm11 = hw2 & 0x7FF
        i1 = 1 - (j1 ^ s)
        i2 = 1 - (j2 ^ s)
        imm = (s << 24) | (i1 << 23) | (i2 << 22) | (imm10 << 12) | (imm11 << 1)
        if s:
            imm -= 1 << 25
        yield BASE + i, BASE + i + 4 + imm


def classify(name: str) -> str:
    if name in NONVOLATILE:
        return "写非易失"
    if name in RF_DANGER:
        return "会开射频"
    if name in RECEIVE:
        return "进接收态"
    if name in READ_ONLY:
        return "只读"
    return "改状态"


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--image", type=Path, default=DEFAULT_IMAGE)
    ap.add_argument("--json", type=Path)
    ap.add_argument("--md", type=Path)
    a = ap.parse_args()
    flash = a.image.read_bytes()

    # 第一遍：所有 ADR 目标里，哪些是"<名字> cmp"形式的完成日志
    completions = {}
    sites = list(adr_sites(flash, DISPATCH_LO, DISPATCH_HI))
    for site, target in sites:
        s = cstr(flash, target)
        if s and s.endswith(" cmp"):
            completions.setdefault(s[:-4].strip().split()[0], []).append(site)

    # 第二遍：候选命令名 = ADR 目标是纯命令名形状的字符串
    name_re = re.compile(r"^[a-z0-9_]{3,24}$")
    cands = []
    for site, target in sites:
        s = cstr(flash, target)
        if not s:
            continue
        # 只认"整串就是命令名"的 ADR。放宽到"首词是命令名"会把日志串
        # 也收进来：'sct3258 reset cmp' 会造出一条并不存在的 sct3258 命令。
        if name_re.match(s):
            cands.append((site, target, s))

    # 同名多处取最早出现的那处作为分发点
    seen = {}
    for site, target, s in cands:
        nm = s.split()[0]
        if nm not in seen or site < seen[nm][0]:
            seen[nm] = (site, target, s)

    ordered = sorted(seen.items(), key=lambda kv: kv[1][0])
    entries = []
    for idx, (nm, (site, target, s)) in enumerate(ordered):
        end = ordered[idx + 1][1][0] if idx + 1 < len(ordered) else DISPATCH_HI
        calls, parses, logs = [], 0, 0
        for _, dst in bl_targets(flash, site, min(end, site + 0x400)):
            if dst in CMP_FUNCS:
                continue
            if dst in PARSE_FUNCS:
                parses += 1
                continue
            if dst in LOG_FUNCS:
                logs += 1
                continue
            if dst not in calls:
                calls.append(dst)
        entries.append({
            "命令": nm,
            "字符串": s,
            "分发点": "0x%08x" % site,
            "取参次数": parses,
            "有完成日志": nm in completions,
            "实现例程": ["0x%08x" % c for c in calls[:6]],
            "等级": classify(nm),
        })

    order = {"只读": 0, "进接收态": 1, "改状态": 2, "会开射频": 3, "写非易失": 4}
    print("共 %d 条命令\n" % len(entries))
    print("%-22s %-8s %-4s %-5s %s"
          % ("命令", "等级", "取参", "完成", "实现例程"))
    print("-" * 78)
    for e in sorted(entries, key=lambda x: (order.get(x["等级"], 9), x["命令"])):
        print("%-22s %-8s %-4d %-5s %s"
              % (e["命令"], e["等级"], e["取参次数"],
                 "有" if e["有完成日志"] else "无",
                 " ".join(e["实现例程"][:3])))

    if a.json:
        a.json.write_text(json.dumps(entries, ensure_ascii=False, indent=1),
                          encoding="utf-8")
        print("\n已写 %s" % a.json)
    if a.md:
        lines = ["| 命令 | 等级 | 取参 | 实现例程 |", "|---|---|---|---|"]
        for e in sorted(entries,
                        key=lambda x: (order.get(x["等级"], 9), x["命令"])):
            lines.append("| `%s` | %s | %d | %s |"
                         % (e["命令"], e["等级"], e["取参次数"],
                            " ".join("`%s`" % c for c in e["实现例程"][:3])))
        a.md.write_text("\n".join(lines) + "\n", encoding="utf-8")
        print("已写 %s" % a.md)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
