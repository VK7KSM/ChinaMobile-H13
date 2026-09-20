#!/usr/bin/env python3
"""汇总一次通话的供数统计：节拍、丢帧、延迟分布。

动机：网关排障需要每通话的可量化指标，但这些数字此前散落在
`relay_offer_paced_summary` 与几百个 `relay_timeline_read_*` 条目里，
每次都要手工翻。这里汇总成一页。

关键指标及其含义（均来自已验证的供数契约，2.8.73）：

  模块交帧数 / 宿主写出数   一对一才正常；宿主多写即越权，少写即欠供
  无许可写出                必须为 0，否则违反"收到一帧才回送一帧"
  交帧间隔                  应贴近空口帧率 60 毫秒，抖动反映节拍是否稳
  读取耗时                  宿主每次等待的实际耗时，反映是否在空等

用法：
    python call_stats.py <捕获目录>
"""
from __future__ import annotations

import argparse
import re
import statistics
from pathlib import Path


def read_text(path: Path) -> str:
    try:
        return path.read_bytes().decode("utf-8", "replace").lstrip("﻿")
    except OSError:
        return ""


def parse_kv(text: str) -> dict[str, int]:
    out = {}
    for m in re.finditer(r"^(\w+)=(-?\d+)$", text, re.M):
        out[m.group(1)] = int(m.group(2))
    return out


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("capture")
    a = ap.parse_args()
    dev = Path(a.capture) / "device_capture"
    if not dev.exists():
        print("无 device_capture")
        return 1

    summary = {}
    for f in sorted(dev.glob("*offer_paced_summary*.bin")):
        summary = parse_kv(read_text(f))
    if not summary:
        print("无供数摘要，可能不是语音会话")
        return 1

    units = summary.get("units_written", 0)
    offers = summary.get("module_offers", 0)
    nopermit = summary.get("writes_without_permit", -1)

    print("== %s" % Path(a.capture).name)
    print("模块交帧 %d，宿主写出 %d，差 %+d"
          % (offers, units, units - offers))
    print("无许可写出 %d %s" % (nopermit, "" if nopermit == 0 else "← 违反供数契约"))
    print("发射延时交帧 %d" % summary.get("tx_delay_offers", 0))

    # 时间线：取每个读点的开始时刻与耗时
    points = []
    for f in sorted(dev.glob("*relay_timeline_read_*.bin")):
        if "_raw_" in f.name:
            continue
        kv = parse_kv(read_text(f))
        name = re.search(r"point=(\S+)", read_text(f))
        if "begin_ms" in kv:
            points.append((kv["begin_ms"], kv.get("elapsed_ms", 0),
                           kv.get("bytes", 0),
                           name.group(1) if name else "?"))
    points.sort()
    waits = [p for p in points if p[3].startswith("offer_wait")]
    if len(waits) >= 3:
        gaps = [waits[i][0] - waits[i - 1][0] for i in range(1, len(waits))]
        gaps = [g for g in gaps if 0 < g < 1000]
        el = [p[1] for p in waits]
        got = sum(1 for p in waits if p[2] > 0)
        print("\n交帧读点 %d 个，其中读到字节 %d 个（%.0f%%）"
              % (len(waits), got, 100.0 * got / len(waits)))
        if gaps:
            print("交帧间隔：中位 %.0f 毫秒  最小 %d  最大 %d  标准差 %.1f"
                  % (statistics.median(gaps), min(gaps), max(gaps),
                     statistics.pstdev(gaps) if len(gaps) > 1 else 0.0))
            off = [abs(g - 60) for g in gaps]
            print("  偏离 60 毫秒空口帧率：中位 %.0f 毫秒" % statistics.median(off))
        print("读取耗时：中位 %.0f 毫秒  最大 %d" % (statistics.median(el), max(el)))
    else:
        print("\n交帧读点不足，无法统计节拍")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
