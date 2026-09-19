#!/usr/bin/env python3
"""导出原厂发射链提交给 SCT3258 的配置块内容。

trace_tx_chain.py 把 `SCT配置提交@0x0801c16c` 打桩跳过，因此报告里只有
调用参数，没有缓冲区内容。这条链把 `r1` 指向的 `r2` 字节提交给基带，
很可能就是 DMR 呼叫配置（本机/目标 ID、色码、时隙、呼叫类型）。
本脚本在调用发生的瞬间把缓冲区原样打印出来，供与探针当前发出的
五条 setup 和五条 VLC 逐字段对照。

不接触设备，只在模拟器里运行固件。

用法：
    python dump_tx_config_blob.py --flash <完整 256 KiB 固件>
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))

from h13_mcu_emu import Emu, TRAMP_BASE  # noqa: E402
from unicorn import UC_HOOK_BLOCK  # noqa: E402
from unicorn.arm_const import (  # noqa: E402
    UC_ARM_REG_PC, UC_ARM_REG_LR, UC_ARM_REG_R0, UC_ARM_REG_R1,
    UC_ARM_REG_R2, UC_ARM_REG_R3,
)

EXTERNAL_DMR_CONFIG = 0x08019480
WATCHED = {
    0x0801C16C: ("SCT配置提交", True),
    0x0801B0E4: ("SCT控制包发送", True),
    0x0801D2BC: ("SCT工作模式", False),
    0x08024DC0: ("大型配置事务", True),
}


def hexdump(data: bytes, base: int) -> None:
    for offset in range(0, len(data), 16):
        chunk = data[offset:offset + 16]
        hexpart = " ".join(f"{b:02x}" for b in chunk)
        text = "".join(chr(b) if 32 <= b < 127 else "." for b in chunk)
        print(f"    {base + offset:08x}  {hexpart:<47}  |{text}|")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--flash", required=True)
    parser.add_argument("--boot-steps", type=int, default=20_000_000)
    parser.add_argument("--call-steps", type=int, default=2_000_000)
    args = parser.parse_args()

    flash = Path(args.flash).read_bytes()
    emu = Emu(flash, trace_gpio=False, tick_steps=3000)
    print(f"启动固件到稳定状态（{args.boot_steps} 步）…")
    emu.run(args.boot_steps)
    print(f"  完成：pc=0x{emu.uc.reg_read(UC_ARM_REG_PC):08x}")

    captured = []

    def on_block(uc, address, size, user_data):
        entry = WATCHED.get(address)
        if entry is None:
            return
        name, want_blob = entry
        r0 = uc.reg_read(UC_ARM_REG_R0)
        r1 = uc.reg_read(UC_ARM_REG_R1)
        r2 = uc.reg_read(UC_ARM_REG_R2)
        blob = b""
        if want_blob and r1:
            length = r2 if 0 < r2 <= 256 else 48
            try:
                blob = bytes(uc.mem_read(r1, length))
            except Exception:  # noqa: BLE001
                blob = b""
        captured.append((name, r0, r1, r2, blob))

    emu.uc.hook_add(UC_HOOK_BLOCK, on_block)

    # 与 trace_tx_chain.py 一致：凡是需要真实 SCT3258 应答才能返回的调用
    # 都打桩为成功，否则链路会卡在第一个调用上。打桩是替身，不是真实行为。
    for address, (name, _) in WATCHED.items():
        emu.func_stubs[address] = (0, name)
    emu.func_hook_limit = 200

    uc = emu.uc
    for index, value in enumerate([1, 0]):
        uc.reg_write([UC_ARM_REG_R0, UC_ARM_REG_R1,
                      UC_ARM_REG_R2, UC_ARM_REG_R3][index], value)
    uc.reg_write(UC_ARM_REG_LR, TRAMP_BASE | 1)
    emu.call_return_pending = True
    print(f"调用外部DMR配置 0x{EXTERNAL_DMR_CONFIG:08x}")
    emu.run(emu.steps + args.call_steps, start=EXTERNAL_DMR_CONFIG)

    print()
    print("=== 提交给基带的内容 ===")
    for name, r0, r1, r2, blob in captured:
        print(f"  {name}: r0=0x{r0:x} r1=0x{r1:08x} r2=0x{r2:x}")
        if blob:
            hexdump(blob, r1)
        print()
    if not captured:
        print("  未捕获到任何提交调用")
    return 0


if __name__ == "__main__":
    sys.exit(main())
