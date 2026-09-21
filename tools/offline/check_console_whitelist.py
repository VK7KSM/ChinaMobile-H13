#!/usr/bin/env python3
"""核对 MCU 控制台客户端的白名单与固件导出表一致。

为什么要有这道闸：`tools/device/mcu_console.sh` 的白名单是手写的，而
危险等级来自固件导出（`tools/firmware/dump_mcu_console.py`）。两者一旦
走偏——比如把一条会开射频的命令误列进只读——脚本就会在没有操作者在场时
把设备发射出去。这正是必须由机器核对、不能靠眼睛的那类事。

核对三条：
  1. 只读白名单里的每一条，在导出表里都必须是「只读」
  2. `--rx` 列表里的每一条，都必须是「进接收态」
  3. 两份名单里都不得出现「会开射频」或「写非易失」

用法：
    python check_console_whitelist.py
"""
from __future__ import annotations

import json
import re
import subprocess
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
SHELL = ROOT / "tools" / "device" / "mcu_console.sh"
DUMPER = ROOT / "tools" / "firmware" / "dump_mcu_console.py"
EXPORT = ROOT / "docs" / "traces" / "mcu_console_commands.json"

FORBIDDEN = {"会开射频", "写非易失"}


def load_table() -> dict[str, str]:
    """优先直接从固件重跑导出；跑不起来再退回已入库的导出结果。"""
    try:
        proc = subprocess.run(
            [sys.executable, str(DUMPER), "--json", str(EXPORT)],
            capture_output=True, text=True, timeout=300)
        if proc.returncode != 0:
            print("   导出器返回 %d，改用已入库结果" % proc.returncode)
    except Exception as exc:
        print("   导出器跑不起来（%s），改用已入库结果" % exc)
    data = json.loads(EXPORT.read_text(encoding="utf-8"))
    return {e["命令"]: e["等级"] for e in data}


def load_lists() -> tuple[list[str], list[str]]:
    text = SHELL.read_text(encoding="utf-8")
    wl = re.search(r'WHITELIST="(.*?)"', text, re.S)
    rx = re.search(r'RX_LIST="(.*?)"', text, re.S)
    if not wl or not rx:
        raise SystemExit("没能在 mcu_console.sh 里找到名单")
    return wl.group(1).split(), rx.group(1).split()


def main() -> int:
    table = load_table()
    whitelist, rxlist = load_lists()
    bad = 0

    def report(ok: bool, name: str, detail: str) -> None:
        nonlocal bad
        if not ok:
            bad += 1
        print("   %-6s %-22s %s" % ("通过  " if ok else "不通过 ", name, detail))

    unknown = [c for c in whitelist + rxlist if c not in table]
    report(not unknown, "名单里的命令都在表里",
           "缺 %s" % unknown if unknown else "%d 条全部在表里"
           % len(whitelist + rxlist))

    wrong = [(c, table.get(c)) for c in whitelist
             if c in table and table[c] != "只读"]
    report(not wrong, "只读白名单确实只读",
           "越界 %s" % wrong if wrong else "%d 条全部为只读" % len(whitelist))

    wrongrx = [(c, table.get(c)) for c in rxlist
               if c in table and table[c] != "进接收态"]
    report(not wrongrx, "接收名单确实是接收类",
           "越界 %s" % wrongrx if wrongrx else "%d 条全部为进接收态" % len(rxlist))

    danger = [(c, table[c]) for c in whitelist + rxlist
              if table.get(c) in FORBIDDEN]
    report(not danger, "名单里没有危险命令",
           "危险 %s" % danger if danger else "无会开射频、无写非易失")

    missing = sorted(c for c, lv in table.items()
                     if lv == "只读" and c not in whitelist)
    # 少放行不危险，只提示，不判失败
    print("   %-6s %-22s %s" % ("提示  ", "表里只读但未放行",
                                ", ".join(missing) if missing else "无"))
    return 1 if bad else 0


if __name__ == "__main__":
    raise SystemExit(main())
