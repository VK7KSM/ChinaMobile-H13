#!/usr/bin/env python3
"""还原 H13 专网模块的生产发射启动链。

做法：在模拟器里把固件跑到稳定状态，然后直接调用发射链入口，记录这条链
向 SCT3258 发出的每一条 HPI 命令、每一次 GPIO 动作和关键状态字节的写入。

SCT3258 本身是模拟器之外的芯片，凡是需要它应答才能返回的函数都被打桩为
成功。每一个被打桩的调用都会在报告里单独列出——它们是替身，不是真实行为，
真实应答必须由设备参考样本回答。

用法：
    python trace_tx_chain.py --flash <完整 256 KiB 固件> [--entry 8015c7c]
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))

from h13_mcu_emu import (  # noqa: E402
    Emu, USART1, TRAMP_BASE, GPIO_PORTS, GPIO_REGS,
)
from unicorn.arm_const import (  # noqa: E402
    UC_ARM_REG_PC, UC_ARM_REG_LR, UC_ARM_REG_R0, UC_ARM_REG_R1,
    UC_ARM_REG_R2, UC_ARM_REG_R3,
)

# 发射链上已定位的函数。名称来自控制流分析，不是固件里的符号。
FUNCS = {
    0x08015C7C: "配置包装器",
    0x08019480: "外部DMR配置",
    0x08024DC0: "大型配置事务",
    0x0801B0E4: "SCT控制包发送",
    0x0801C16C: "SCT配置提交",
    0x0801D2BC: "SCT工作模式",
    0x0800FBCE: "信道与功率寄存器组写",
    0x0800E6C0: "PE6拉低",
    0x0800B9DC: "PF3拉低",
    0x080102D0: "HPI短事务",
    0x080103EC: "HPI批量事务",
    0x08010560: "HPI写包装",
    0x080105C8: "HPI读路径",
    0x0801BBB4: "配置子链",
}

# 需要 SCT 真实应答才能成功返回的函数。打桩为成功，以便观察 MCU 侧后续动作。
SCT_DEPENDENT = {
    0x0801B0E4: "SCT控制包发送",
    0x0801C16C: "SCT配置提交",
    0x0801D2BC: "SCT工作模式",
}

# 关心的状态字节
WATCH = [
    (0x20000134, 0x20000138, "PC6边沿累计计数"),
    (0x20000138, 0x20000139, "射频时隙门"),
    (0x2000013A, 0x2000013C, "时隙短计数"),
    (0x2000013C, 0x2000013D, "时隙完成位"),
    (0x2000015C, 0x20000160, "串口/HPI桥标志"),
    (0x200017DC, 0x200017E0, "外部发射生命周期状态"),
    (0x20000410, 0x20000424, "会话记录"),
]


class ChainTracer:
    def __init__(self, emu: Emu):
        self.emu = emu
        self.packets: list[dict] = []
        self.gpio: list[dict] = []
        self.states: list[dict] = []
        self.stubbed: list[dict] = []

    def install(self):
        emu = self.emu
        # HPI 事务：抓命令字节
        for addr in (0x080102D0, 0x080103EC):
            emu.func_hooks[addr] = FUNCS[addr]
        for addr, name in FUNCS.items():
            emu.func_hooks.setdefault(addr, name)
        for addr, name in SCT_DEPENDENT.items():
            emu.func_stubs[addr] = (0, name)
        for lo, hi, name in WATCH:
            emu.watches.append((lo, hi, name))
        emu.watch_limit = 200
        emu.func_hook_limit = 200
        emu.trace_gpio = True
        # 接管日志，转成结构化记录
        emu.log = self.log  # type: ignore[method-assign]

    def log(self, msg: str):
        emu = self.emu
        emu.events.append((emu.steps, msg))
        if msg.startswith("调用 ") and "HPI" in msg:
            self.packets.append({"步": emu.steps, "原文": msg})
        elif msg.startswith("调用 "):
            self.packets.append({"步": emu.steps, "原文": msg})
        elif msg.startswith("W GPIO"):
            self.gpio.append({"步": emu.steps, "原文": msg})
        elif msg.startswith("写 "):
            self.states.append({"步": emu.steps, "原文": msg})
        elif msg.startswith("打桩跳过"):
            self.stubbed.append({"步": emu.steps, "原文": msg})

    def call(self, target: int, args: list[int], steps: int):
        emu = self.emu
        uc = emu.uc
        for i, v in enumerate(args[:4]):
            uc.reg_write([UC_ARM_REG_R0, UC_ARM_REG_R1,
                          UC_ARM_REG_R2, UC_ARM_REG_R3][i], v)
        uc.reg_write(UC_ARM_REG_LR, TRAMP_BASE | 1)
        emu.call_return_pending = True
        emu.run(emu.steps + steps, start=target)
        return uc.reg_read(UC_ARM_REG_R0)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--flash", required=True)
    ap.add_argument("--entry", default="8019480", help="发射链入口地址（十六进制）")
    ap.add_argument("--args", default="1,0", help="入口参数，逗号分隔十六进制")
    ap.add_argument("--boot-steps", type=int, default=20_000_000)
    ap.add_argument("--call-steps", type=int, default=5_000_000)
    ap.add_argument("--out", default="发射链还原报告.json")
    args = ap.parse_args()

    flash = open(args.flash, "rb").read()
    emu = Emu(flash, trace_gpio=False, tick_steps=3000)
    emu.hobib_toggle = 4
    print(f"启动固件到稳定状态（{args.boot_steps} 步）…")
    emu.run(args.boot_steps)
    print(f"  完成：pc={emu.uc.reg_read(UC_ARM_REG_PC):#010x} 重映射={emu.remap_sram}")

    tracer = ChainTracer(emu)
    tracer.install()

    entry = int(args.entry, 16)
    argv = [int(x, 16) for x in args.args.split(",") if x.strip()]
    print(f"调用 {FUNCS.get(entry, '未知')} {entry:#010x} 参数={[hex(a) for a in argv]}")
    ret = tracer.call(entry, argv, args.call_steps)
    print(f"  返回 {ret:#x}（0 为成功）\n")

    print("=== 发出的调用与 HPI 命令 ===")
    for p in tracer.packets:
        print(f"  {p['步']:>10} {p['原文']}")
    print("\n=== GPIO 动作 ===")
    for g in tracer.gpio:
        print(f"  {g['步']:>10} {g['原文']}")
    print("\n=== 状态字节写入 ===")
    for s in tracer.states:
        print(f"  {s['步']:>10} {s['原文']}")
    print("\n=== 被打桩跳过的外部芯片依赖调用（替身，非真实行为）===")
    for s in tracer.stubbed:
        print(f"  {s['步']:>10} {s['原文']}")

    report = {
        "入口": f"{entry:#010x}",
        "入口名称": FUNCS.get(entry, "未知"),
        "参数": [hex(a) for a in argv],
        "返回值": f"{ret:#x}",
        "调用与HPI命令": tracer.packets,
        "GPIO动作": tracer.gpio,
        "状态字节写入": tracer.states,
        "打桩跳过": tracer.stubbed,
        "说明": "SCT3258 为模拟器之外的芯片，打桩调用的返回值是替身，"
                "真实应答需由设备参考样本确认。",
    }
    Path(args.out).write_text(json.dumps(report, ensure_ascii=False, indent=2),
                              encoding="utf-8")
    print(f"\n报告已写入 {args.out}")


if __name__ == "__main__":
    main()
