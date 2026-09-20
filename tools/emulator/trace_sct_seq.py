#!/usr/bin/env python3
"""在模拟器里直接调用固件的呼叫状态机入口，截取它向 SCT3258 发出的每一条 HPI 包。

与 trace_tx_chain.py 的区别：那个脚本把高层发送例程整体打桩，看不到包内容；
本脚本只在最底层的 HPI 事务（0x080102D0 短事务 / 0x080103EC 批量事务）
打桩为成功，并在同一入口先记录 r0=缓冲区、r1=长度、r2=包类型，
因此 33 个 SCT_Dsp_Send_* 例程各自组包的类型与正文都能完整截下。

打桩点仍是替身：SCT3258 的真实应答须由设备参考样本确认。

用法：
    python trace_sct_seq.py --flash <全片> --entry 8013a44 [--args 0] [--preset 20001234=1,...]
"""
from __future__ import annotations
import argparse, json, re, struct, sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).parent))
from h13_mcu_emu import Emu, TRAMP_BASE
from unicorn.arm_const import UC_ARM_REG_R0, UC_ARM_REG_R1, UC_ARM_REG_R2, UC_ARM_REG_R3, UC_ARM_REG_LR, UC_ARM_REG_PC

HPI_SHORT = 0x080102D0
HPI_BULK  = 0x080103EC
GENERIC_SEND = 0x08027244   # (buf,len,type,timeout,[sp]=indicate)
GENERIC_SEND2 = 0x080272B4

def sct_entries(flash: bytes) -> dict[int, str]:
    out = {}
    for m in re.finditer(rb"SCT_Dsp_Send_([A-Z_0-9]+) indicate send time out", flash):
        nm = m.group(1).decode(); soff = m.start(); i = soff; pop = None
        while i > soff - 64:
            i -= 2
            if (struct.unpack_from("<H", flash, i)[0] & 0xFF00) == 0xBD00:
                pop = i; break
        if pop is None: continue
        j = pop; entry = None
        while j > pop - 0x400:
            j -= 2
            hw = struct.unpack_from("<H", flash, j)[0]
            if (hw & 0xFF00) == 0xB500:
                entry = j; k = j
                while k > j - 0x400:
                    k -= 2; hw2 = struct.unpack_from("<H", flash, k)[0]
                    if (hw2 & 0xFF00) == 0xB500: entry = k
                    if (hw2 & 0xFF00) == 0xBD00 or hw2 == 0x4770: break
                break
        if entry is not None:
            out[0x08000000 + entry] = "SCT_" + nm
    return out

class SeqTracer:
    def __init__(self, emu: Emu, names: dict[int, str]):
        self.emu = emu; self.names = names
        self.packets = []; self.calls = []; self.gpio = []; self.states = []; self.stubbed = []
        self.current_sender = None
    def install(self):
        emu = self.emu
        for addr, nm in self.names.items():
            emu.func_hooks[addr] = nm
        emu.func_hooks[HPI_SHORT] = "HPI短事务"
        emu.func_hooks[HPI_BULK] = "HPI批量事务"
        emu.func_hooks[GENERIC_SEND] = "通用发送"
        emu.func_hooks[GENERIC_SEND2] = "通用发送2"
        emu.func_stubs[HPI_SHORT] = (0, "HPI短事务")
        emu.func_stubs[HPI_BULK] = (0, "HPI批量事务")
        emu.func_hook_limit = 100000
        emu.watch_limit = 100000
        emu.trace_gpio = True
        emu.log = self.log
    def log(self, msg: str):
        emu = self.emu
        if msg.startswith("调用 SCT_"):
            self.current_sender = msg.split("@")[0][3:]
            self.calls.append({"步": emu.steps, "原文": msg})
        elif msg.startswith("调用 HPI"):
            m = re.search(r"r0=(0x[0-9a-f]+) r1=(0x[0-9a-f]+) r2=(0x[0-9a-f]+) r3=(0x[0-9a-f]+).*?buf=([0-9a-f]+)", msg)
            if m:
                ln = int(m.group(2), 16); ty = int(m.group(3), 16)
                buf = m.group(5)[: ln * 2]
                self.packets.append({"步": emu.steps, "发送者": self.current_sender,
                                     "类型": ty, "长度": ln, "正文": buf, "超时": int(m.group(4), 16)})
            else:
                self.packets.append({"步": emu.steps, "发送者": self.current_sender, "原文": msg})
        elif msg.startswith("调用 "):
            self.calls.append({"步": emu.steps, "原文": msg})
        elif msg.startswith("W GPIO"):
            self.gpio.append({"步": emu.steps, "原文": msg})
        elif msg.startswith("写 "):
            self.states.append({"步": emu.steps, "原文": msg})
        elif msg.startswith("打桩跳过"):
            self.stubbed.append({"步": emu.steps, "原文": msg})
    def call(self, target: int, args: list[int], steps: int):
        emu = self.emu; uc = emu.uc
        for i, v in enumerate(args[:4]):
            uc.reg_write([UC_ARM_REG_R0, UC_ARM_REG_R1, UC_ARM_REG_R2, UC_ARM_REG_R3][i], v)
        uc.reg_write(UC_ARM_REG_LR, TRAMP_BASE | 1)
        emu.call_return_pending = True
        emu.run(emu.steps + steps, start=target)
        return uc.reg_read(UC_ARM_REG_R0)

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--flash", required=True)
    ap.add_argument("--entry", required=True)
    ap.add_argument("--args", default="")
    ap.add_argument("--preset", default="", help="调用前写 SRAM：addr=val(字节),... 十六进制")
    ap.add_argument("--boot-steps", type=int, default=20_000_000)
    ap.add_argument("--call-steps", type=int, default=6_000_000)
    ap.add_argument("--out", default="")
    ap.add_argument("--watch", default="", help="监视 SRAM 写：lo-hi:名,... 十六进制")
    a = ap.parse_args()
    flash = open(a.flash, "rb").read()
    names = sct_entries(flash)
    emu = Emu(flash, trace_gpio=False, tick_steps=3000)
    emu.hobib_toggle = 4
    print(f"启动固件到稳定状态（{a.boot_steps} 步）…", flush=True)
    emu.run(a.boot_steps)
    print(f"  完成：pc={emu.uc.reg_read(UC_ARM_REG_PC):#010x}", flush=True)
    tr = SeqTracer(emu, names); tr.install()
    for spec in [x for x in a.watch.split(",") if x.strip()]:
        rng, nm = spec.split(":"); lo, hi = rng.split("-")
        emu.watches.append((int(lo,16), int(hi,16), nm))
    for kv in [x for x in a.preset.split(",") if x.strip()]:
        ad, val = kv.split("="); ad = int(ad, 16); val = int(val, 16)
        emu.uc.mem_write(ad, bytes([val & 0xff]))
        print(f"  预置 [{ad:#010x}] = {val:#x}")
    entry = int(a.entry, 16)
    argv = [int(x, 16) for x in a.args.split(",") if x.strip()]
    print(f"调用 {entry:#010x} 参数={[hex(x) for x in argv]}", flush=True)
    ret = tr.call(entry, argv, a.call_steps)
    print(f"  返回 {ret:#x}\n")
    print("=== HPI 包序列（类型/正文）===")
    for p in tr.packets:
        if "正文" in p:
            print(f"  {p['步']:>10} {p['发送者'] or '?':<24} type={p['类型']:<3} len={p['长度']:<3} {p['正文']}")
        else:
            print(f"  {p['步']:>10} {p['原文']}")
    print("\n=== 调用 ===")
    for c in tr.calls: print(f"  {c['步']:>10} {c['原文']}")
    print("\n=== GPIO ===")
    for g in tr.gpio: print(f"  {g['步']:>10} {g['原文']}")
    print("\n=== 状态写入 ===")
    for s in tr.states: print(f"  {s['步']:>10} {s['原文']}")
    if a.out:
        Path(a.out).write_text(json.dumps({"入口": f"{entry:#010x}", "参数": [hex(x) for x in argv], "返回": f"{ret:#x}",
            "包": tr.packets, "调用": tr.calls, "GPIO": tr.gpio, "状态写入": tr.states, "打桩": tr.stubbed},
            ensure_ascii=False, indent=1), encoding="utf-8")
        print(f"\n已写 {a.out}")

if __name__ == "__main__":
    main()
