#!/usr/bin/env python3
"""发射功率标定表：结构、插值与安全上限。

现状（2.9.0）：功率寄存器 `0x20002DE6`（两字节）已定位，厂商低功率码
`2030` 实测 1.15 瓦（2.8.83），但档位与实际功率的对应关系没有测过，
也没有闭环控制。

本模块先把标定表的结构、插值与校验做好并用已知点验证；等功率表读数到位，
把测点填进 `POINTS` 即可直接用。**在标定点足够之前，任何"设定瓦数"的调用
都必须拒绝而不是外推**——射频功率外推不安全。

用法：
    python power_calibration.py                 自检
    python power_calibration.py --code 2030     查某码对应功率
    python power_calibration.py --watts 1.0     求某功率对应码
"""
from __future__ import annotations

import argparse

# 标定点：(寄存器码, 实测瓦数, 出处)
# 只有真机功率表读数才能进这张表，听感与推算一律不算。
POINTS: list[tuple[int, float, str]] = [
    (2030, 1.15, "2026-09-20 第二十次发射，操作者功率表读数"),
]

MAX_WATTS = 5.0          # 操作安全上限，超出一律拒绝
CODE_MIN, CODE_MAX = 0, 4095


def calibrated() -> list[tuple[int, float]]:
    return sorted((c, w) for c, w, _ in POINTS)


def watts_for_code(code: int) -> tuple[float | None, str]:
    pts = calibrated()
    if not pts:
        return None, "标定表为空"
    for c, w in pts:
        if c == code:
            return w, "标定点"
    if len(pts) < 2:
        return None, "标定点不足两个，无法插值；外推不安全，拒绝给值"
    if code < pts[0][0] or code > pts[-1][0]:
        return None, "超出标定范围 [%d, %d]，外推不安全，拒绝给值" % (
            pts[0][0], pts[-1][0])
    for i in range(1, len(pts)):
        (c0, w0), (c1, w1) = pts[i - 1], pts[i]
        if c0 <= code <= c1:
            t = (code - c0) / (c1 - c0)
            return w0 + t * (w1 - w0), "在 %d 与 %d 之间线性插值" % (c0, c1)
    return None, "未命中区间"


def code_for_watts(watts: float) -> tuple[int | None, str]:
    if watts <= 0:
        return None, "功率须为正"
    if watts > MAX_WATTS:
        return None, "超过安全上限 %.1f 瓦，拒绝" % MAX_WATTS
    pts = calibrated()
    if len(pts) < 2:
        return None, "标定点不足两个，无法求解；外推不安全，拒绝给值"
    lo, hi = pts[0][1], pts[-1][1]
    if not (lo <= watts <= hi):
        return None, "超出标定范围 [%.2f, %.2f] 瓦，拒绝" % (lo, hi)
    for i in range(1, len(pts)):
        (c0, w0), (c1, w1) = pts[i - 1], pts[i]
        if w0 <= watts <= w1 and w1 != w0:
            t = (watts - w0) / (w1 - w0)
            return round(c0 + t * (c1 - c0)), "在 %.2f 与 %.2f 瓦之间插值" % (w0, w1)
    return None, "未命中区间"


def _selftest() -> int:
    bad = 0

    def check(cond: bool, what: str) -> None:
        nonlocal bad
        if not cond:
            bad += 1
            print("   不通过 %s" % what)
        else:
            print("   通过   %s" % what)

    print("已有标定点 %d 个" % len(POINTS))
    for c, w, src in POINTS:
        print("   码 %d = %.2f 瓦（%s）" % (c, w, src))

    print("\n已知点应原样返回")
    w, why = watts_for_code(2030)
    check(w == 1.15 and why == "标定点", "码 2030 返回 1.15 瓦")

    print("标定点不足时必须拒绝而不是外推")
    w, why = watts_for_code(1500)
    check(w is None and "外推不安全" in why, "未标定的码拒绝给值")
    c, why = code_for_watts(1.0)
    check(c is None and "不足两个" in why, "求码同样拒绝")

    print("安全上限")
    c, why = code_for_watts(9.0)
    check(c is None and "安全上限" in why, "超上限拒绝")
    c, why = code_for_watts(-1)
    check(c is None, "非正功率拒绝")

    print("\n补两个假想点后插值应生效（仅验证算法，不入表）")
    POINTS.append((1000, 0.30, "假想点，自检用"))
    POINTS.append((3000, 2.50, "假想点，自检用"))
    w, why = watts_for_code(2000)
    check(w is not None and 0.3 < w < 2.5, "范围内插值给出合理值：%s" % (
        "%.2f 瓦" % w if w else "无"))
    c, why = code_for_watts(1.0)
    check(c is not None and 1000 <= c <= 3000, "反向求码在范围内：%s" % c)
    del POINTS[-2:]

    print("\n不通过项 %d" % bad)
    return 1 if bad else 0


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--code", type=int)
    ap.add_argument("--watts", type=float)
    a = ap.parse_args()
    if a.code is not None:
        w, why = watts_for_code(a.code)
        print("码 %d → %s（%s）" % (a.code, "%.2f 瓦" % w if w else "无解", why))
        return 0
    if a.watts is not None:
        c, why = code_for_watts(a.watts)
        print("%.2f 瓦 → %s（%s）" % (a.watts, c if c else "无解", why))
        return 0
    return _selftest()


if __name__ == "__main__":
    raise SystemExit(main())
