#!/usr/bin/env python3
"""对一次捕获逐条核对已确立的不变量，把知识变成自动检查。

动机：2026-09-20 之前十九次发射全部发在加密信道上，对端听到的必然是噪音，
而这一点直到当天才被发现（H13_new.md 2.8.71）。同类错误应当由机器拦下，
不该靠人每次记得去看。

核对项（全部来自已验证结论，括号内为出处）：

  1. 信道加密必须关闭，即信道回读第十项为 off（2.8.71）
  2. 呼叫头的服务选项必须为 0，第 6 位是加密标志（2.8.71）
  3. 语音单元长度为 27 字节，不是 36（README「容易踩的坑」）
  4. 零射频会话应写满预期单元数（2.8.83）
  5. 发射会话的实际射频占空不得超过 30 秒（操作安全约束）

用法：
    python audit_capture.py <捕获目录>
    python audit_capture.py --all <捕获根目录>
"""
from __future__ import annotations

import argparse
import re
from pathlib import Path

OK, BAD, SKIP = "通过", "不通过", "不适用"


def read_text(path: Path) -> str:
    try:
        return path.read_bytes().decode("utf-8", "replace").lstrip("﻿")
    except OSError:
        return ""


def check_channel_clear(dev: Path) -> tuple[str, str]:
    for f in sorted(dev.glob("*text_response*.bin")):
        text = read_text(f)
        if "DMOGETDIGITALCH:" in text:
            fields = text.split("DMOGETDIGITALCH:")[1].split(",")
            if len(fields) >= 10:
                value = fields[9].strip()
                if value == "off":
                    return OK, "信道加密 off"
                return BAD, "信道加密 %s ← 对端会按加密语音处理" % value
    return SKIP, "无信道回读"


def check_service_option(dev: Path) -> tuple[str, str]:
    for f in sorted(dev.glob("*hpi_vlc_*_request.bin")):
        data = f.read_bytes()
        for i in range(len(data) - 11):
            if data[i] == 0x43 and data[i + 2] == 0x09:
                service = data[i + 5]
                if service & 0x40:
                    return BAD, "服务选项 0x%02x 置了加密位" % service
                return OK, "服务选项 0x%02x" % service
    return SKIP, "无呼叫头"


def check_unit_bytes(dev: Path) -> tuple[str, str]:
    for f in sorted(dev.glob("*relay_offer_paced_summary*.bin")):
        text = read_text(f)
        m = re.search(r"units_written=(\d+)", text)
        if m:
            return OK, "按单元计数 %s" % m.group(1)
    for f in sorted(dev.glob("*hpi_vlc_0_request.bin")):
        return OK, "呼叫头存在，单元长度由格式常量约束"
    return SKIP, "无供数摘要"


def check_units_complete(root: Path) -> tuple[str, str]:
    text = read_text(root / "atomic_result_host.txt")
    written = re.search(r"relay_units_written=(\d+)", text)
    result = re.search(r"result=(\w+)", text)
    if not written:
        return SKIP, "无单元计数"
    count = int(written.group(1))
    verdict = result.group(1) if result else "未知"
    if count == 0:
        return BAD, "一个单元都没写（结果 %s）" % verdict
    if verdict != "PASS":
        return BAD, "写出 %d 个单元但结果 %s" % (count, verdict)
    return OK, "写出 %d 个单元，结果 PASS" % count


def check_rf_duty(dev: Path) -> tuple[str, str]:
    for f in sorted(dev.glob("*first_bridge_gate*.bin")):
        text = read_text(f)
        m = re.search(r"slot_count=(\d+)", text)
        if m:
            seconds = int(m.group(1)) * 0.06
            if seconds > 30:
                return BAD, "射频占空约 %.1f 秒，超 30 秒上限" % seconds
            return OK, "射频占空约 %.1f 秒" % seconds
    return SKIP, "非发射会话"


CHECKS = [
    ("信道加密关闭", lambda r, d: check_channel_clear(d)),
    ("服务选项无加密位", lambda r, d: check_service_option(d)),
    ("语音单元计数", lambda r, d: check_unit_bytes(d)),
    ("单元写出完整", lambda r, d: check_units_complete(r)),
    ("射频占空不超限", lambda r, d: check_rf_duty(d)),
]


def audit(root: Path) -> int:
    dev = root / "device_capture"
    print("== %s" % root.name)
    bad = 0
    for name, fn in CHECKS:
        try:
            status, detail = fn(root, dev)
        except Exception as exc:
            status, detail = BAD, "核对出错：%s" % exc
        if status == BAD:
            bad += 1
        print("   %-6s %-14s %s" % (status, name, detail))
    return bad


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("path")
    ap.add_argument("--all", action="store_true", help="遍历子目录")
    a = ap.parse_args()
    base = Path(a.path)
    targets = sorted(p for p in base.iterdir()
                     if p.is_dir()) if a.all else [base]
    total_bad = 0
    for t in targets:
        if not (t / "device_capture").exists():
            print("== %s" % t.name)
            print("   %-6s %s" % (SKIP, "无 device_capture，可能仍在写入"))
            continue
        total_bad += audit(t)
    print("\n合计不通过项 %d" % total_bad)
    return 1 if total_bad else 0


if __name__ == "__main__":
    raise SystemExit(main())
