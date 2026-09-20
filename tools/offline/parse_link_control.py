#!/usr/bin/env python3
"""解析语音链路控制，取出呼叫类型、被叫与主叫。

网关必须能从空口帧里读出这些元数据，转发到网络时才带得上主叫/目标；
2.9.4 标注「构造已有、解析待建」，这里补上解析侧。

九字节链路控制的布局（与 DmrProtocol.Session.lc9() 构造侧一致）：

    [0]     全链路控制操作码：0 = 组呼语音，3 = 私呼语音
    [1]     厂商标识
    [2]     服务选项，第 6 位（0x40）为加密标志
    [3..5]  被叫地址，大端 24 位
    [6..8]  主叫地址，大端 24 位

**加密标志必须为 0**：信道配置是加密关闭时若置 1，等于告诉接收机这是加密
语音，对端会用它没有的密钥去解，放出来就是噪音（2.8.71）。解析侧把它
单独标出来，便于一眼发现这类配置错误。

输入可以是九字节链路控制，或包含它的呼叫头线帧（`… 43 <呼叫模式> 09 <9字节>`）。

用法：
    python parse_link_control.py 84a961000c0543010900000000006300000d
    python parse_link_control.py --file <帧.bin>
"""
from __future__ import annotations

import argparse
from pathlib import Path

OPCODE = {0: "组呼语音", 3: "私呼语音", 4: "组呼语音(带 Talker Alias)"}


def u24(data: bytes, off: int) -> int:
    return (data[off] << 16) | (data[off + 1] << 8) | data[off + 2]


def find_lc9(data: bytes) -> tuple[bytes, int | None]:
    """从线帧里抠出九字节链路控制；找不到就把输入当作链路控制本身。"""
    for i in range(len(data) - 11):
        if data[i] == 0x43 and data[i + 2] == 0x09:
            return data[i + 3:i + 12], data[i + 1]
    return data[:9], None


def describe(lc: bytes, call_mode: int | None) -> list[str]:
    if len(lc) < 9:
        return ["链路控制不足九字节"]
    opcode = lc[0] & 0x3F
    service = lc[2]
    lines = [
        "操作码 0x%02x（%s）" % (opcode, OPCODE.get(opcode, "未知")),
        "厂商标识 0x%02x" % lc[1],
        "服务选项 0x%02x" % service,
        "  加密标志 %s%s" % (
            "置位" if service & 0x40 else "清零",
            "  ← 明文信道上置位会让对端按加密语音处理" if service & 0x40 else ""),
        "被叫 %d" % u24(lc, 3),
        "主叫 %d" % u24(lc, 6),
    ]
    if call_mode is not None:
        kind = {0x01: "呼叫头第一条", 0x11: "呼叫头第二条",
                0x02: "终止呼叫头"}.get(call_mode & 0x7F, "未知")
        lines.insert(0, "呼叫模式 0x%02x（%s，时隙位=%d）"
                     % (call_mode, kind, (call_mode >> 7) & 1))
    return lines


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("hex", nargs="?", help="十六进制字节串")
    ap.add_argument("--file", help="从文件读取")
    a = ap.parse_args()

    if a.file:
        data = Path(a.file).read_bytes()
    elif a.hex:
        data = bytes.fromhex(a.hex.replace(" ", ""))
    else:
        ap.error("给出十六进制串或 --file")

    lc, mode = find_lc9(data)
    for line in describe(lc, mode):
        print(line)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
