#!/usr/bin/env python3
"""H13 专网模块 MCU（Cortex-M0 / STM32F0 外设图）固件模拟器。

目的：在电脑上运行 Module_current_0.3.66 固件，记录它对 GPIO、HPI、时隙区、
会话记录的每一次读写，得到原厂 DMOPTT 发射序列，不接触真机。

用法：
    python h13_mcu_emu.py --flash Module_current_0.3.66_full_flash_256k.bin \
        --cmd "AT+DMOCONNECT" --steps 20000000

只做模拟与记录，不生成任何可写回设备的制品。
"""

from __future__ import annotations

import argparse
import struct
import sys
from collections import Counter, deque

from unicorn import (
    UC_ARCH_ARM, UC_MODE_THUMB, UC_MODE_MCLASS, UC_HOOK_CODE, UC_HOOK_MEM_READ,
    UC_HOOK_MEM_WRITE, UC_HOOK_MEM_UNMAPPED, UC_HOOK_INTR, UC_HOOK_BLOCK,
    Uc, UcError,
)
from unicorn.arm_const import (
    UC_ARM_REG_PC, UC_ARM_REG_SP, UC_ARM_REG_LR, UC_ARM_REG_R0, UC_ARM_REG_R1,
    UC_ARM_REG_R2, UC_ARM_REG_R3, UC_ARM_REG_R12, UC_ARM_REG_XPSR,
    UC_ARM_REG_CPSR,
)

FLASH_BASE = 0x08000000
FLASH_SIZE = 0x40000
APP_BASE = 0x08006000
SRAM_BASE = 0x20000000
SRAM_SIZE = 0x10000
PERIPH_BASE = 0x40000000
PERIPH_SIZE = 0x40000
GPIO_BASE = 0x48000000
GPIO_SIZE = 0x2000
SCS_BASE = 0xE000E000
SCS_SIZE = 0x1000
TRAMP_BASE = 0x0FFF0000  # 异常返回跳板（模拟 EXC_RETURN）
TRAMP_SIZE = 0x1000
SYSMEM_BASE = 0x1FFF0000
SYSMEM_SIZE = 0x10000

GPIO_PORTS = {0x48000000: "A", 0x48000400: "B", 0x48000800: "C",
              0x48000C00: "D", 0x48001000: "E", 0x48001400: "F"}
GPIO_REGS = {0x00: "MODER", 0x04: "OTYPER", 0x08: "OSPEEDR", 0x0C: "PUPDR",
             0x10: "IDR", 0x14: "ODR", 0x18: "BSRR", 0x1C: "LCKR",
             0x20: "AFRL", 0x24: "AFRH", 0x28: "BRR"}

RCC = 0x40021000
USART1 = 0x40013800
USART_BASES = {0x40013800: "USART1", 0x40004400: "USART2",
               0x40004800: "USART3", 0x40004C00: "USART4"}
SPI1 = 0x40013000
SYSTICK_CSR = 0xE000E010
SYSTICK_RVR = 0xE000E014
SYSTICK_CVR = 0xE000E018
NVIC_ISER = 0xE000E100
NVIC_ICER = 0xE000E180
NVIC_ISPR = 0xE000E200


def periph_name(addr: int) -> str:
    if GPIO_BASE <= addr < GPIO_BASE + GPIO_SIZE:
        port = GPIO_PORTS.get(addr & ~0x3FF, "?")
        reg = GPIO_REGS.get(addr & 0x3FF, f"+{addr & 0x3FF:#x}")
        return f"GPIO{port}.{reg}"
    table = {
        0x40021000: "RCC", 0x40013800: "USART1", 0x40004400: "USART2",
        0x40004800: "USART3", 0x40013000: "SPI1", 0x40003800: "SPI2",
        0x40010400: "EXTI", 0x40010000: "SYSCFG", 0x40022000: "FLASH",
        0x40000000: "TIM2", 0x40000400: "TIM3", 0x40002000: "TIM6",
        0x40007000: "PWR", 0x40012400: "ADC", 0x40012C00: "TIM1",
        0x40014000: "TIM15", 0x40014400: "TIM16", 0x40014800: "TIM17",
        0x40003000: "IWDG", 0x40002C00: "WWDG", 0x40020000: "DMA1",
        0x40007400: "DAC", 0x40005400: "I2C1", 0x40005800: "I2C2",
        0xE000E010: "SYST_CSR", 0xE000E014: "SYST_RVR", 0xE000E018: "SYST_CVR",
    }
    base = addr & ~0x3FF
    if addr in table:
        return table[addr]
    if base in table:
        return f"{table[base]}+{addr - base:#x}"
    return f"periph{addr:#010x}"


class Emu:
    def __init__(self, flash: bytes, trace_gpio=True, trace_all=False, verbose=False,
                 tick_steps: int = 0, trace_usart: bool = False):
        self.uc = Uc(UC_ARCH_ARM, UC_MODE_THUMB | UC_MODE_MCLASS)
        uc = self.uc
        uc.mem_map(FLASH_BASE, FLASH_SIZE)
        uc.mem_write(FLASH_BASE, flash.ljust(FLASH_SIZE, b"\xff"))
        uc.mem_map(SRAM_BASE, SRAM_SIZE)
        uc.mem_map(PERIPH_BASE, PERIPH_SIZE)
        uc.mem_map(GPIO_BASE, GPIO_SIZE)
        uc.mem_map(SCS_BASE, SCS_SIZE)
        uc.mem_map(TRAMP_BASE, TRAMP_SIZE)
        # 系统存储区：出厂引导程序、选项字节、芯片唯一 ID、Flash 容量寄存器。
        # 固件启动时会读 UID 和容量，缺这块映射会直接触发读故障。
        uc.mem_map(SYSMEM_BASE, SYSMEM_SIZE)
        uc.mem_write(0x1FFFF7AC, bytes(12))               # UID：模拟器内固定为全零
        uc.mem_write(0x1FFFF7CC, struct.pack("<H", 256))  # Flash 容量 256 KiB
        # 0 地址映射：Cortex-M0 通过 SYSCFG 重映射把 Flash 或 SRAM 映射到 0
        uc.mem_map(0, 0x10000)
        uc.mem_write(0, flash[:0x10000])

        self.flash = flash
        self.trace_gpio = trace_gpio
        self.trace_all = trace_all
        self.verbose = verbose
        self.reg_state: dict[int, int] = {}
        self.uart_tx: dict[int, bytearray] = {b: bytearray() for b in USART_BASES}
        self.uart_rx: dict[int, deque] = {b: deque() for b in USART_BASES}
        self.events: list[tuple[int, str]] = []
        self.steps = 0
        self.in_exception = 0
        self.systick_period = 0
        self.systick_next = 0
        # tick_steps>0 时用固定的指令间隔驱动 SysTick，压缩固件的毫秒级忙等，
        # 只改变模拟耗时，不改变固件的执行顺序。
        self.tick_steps = tick_steps
        self.trace_usart = trace_usart
        self.usart_poll: Counter = Counter()
        self.hobib_toggle = 0
        self.hobib_reads = 0
        self.func_hooks: dict[int, str] = {}
        self.func_hit: Counter = Counter()
        self.func_hook_limit = 30
        self.watch_hit: Counter = Counter()
        self.watch_limit = 20
        self.watch_reads = False
        self.call_return_pending = False
        self.func_stubs: dict[int, tuple[int, str]] = {}
        self.stub_hit: Counter = Counter()
        self.trace_path = False
        self.path: list[tuple[int, int]] = []
        self.usart_irq_next = 0
        self.usart_irq_interval = 40
        self.spin_counter = Counter()
        self.last_pc = 0
        self.gpio_odr = {p: 0 for p in GPIO_PORTS}
        self.gpio_idr = {p: 0 for p in GPIO_PORTS}
        self.gpio_idr[0x48000800] |= 1 << 10  # PC10 HOBIB 默认高（HPI 空闲可写）
        self.remap_sram = False
        self.pending_irq: deque[int] = deque()
        # 每次异常注入前的精确 SP。Cortex-M 压栈时若 SP 未 8 字节对齐会额外
        # 调整并在 xPSR bit9 记录；返回时必须还原该差值，否则 SP 会逐次漂移。
        self.exc_sp_stack: list[int] = []

        uc.hook_add(UC_HOOK_MEM_READ, self.on_read, begin=PERIPH_BASE, end=PERIPH_BASE + PERIPH_SIZE)
        uc.hook_add(UC_HOOK_MEM_WRITE, self.on_write, begin=PERIPH_BASE, end=PERIPH_BASE + PERIPH_SIZE)
        uc.hook_add(UC_HOOK_MEM_READ, self.on_read, begin=GPIO_BASE, end=GPIO_BASE + GPIO_SIZE)
        uc.hook_add(UC_HOOK_MEM_WRITE, self.on_write, begin=GPIO_BASE, end=GPIO_BASE + GPIO_SIZE)
        uc.hook_add(UC_HOOK_MEM_READ, self.on_read, begin=SCS_BASE, end=SCS_BASE + SCS_SIZE)
        uc.hook_add(UC_HOOK_MEM_WRITE, self.on_write, begin=SCS_BASE, end=SCS_BASE + SCS_SIZE)
        self.watches: list[tuple[int, int, str]] = []
        uc.hook_add(UC_HOOK_MEM_WRITE, self.on_sram_write,
                    begin=SRAM_BASE, end=SRAM_BASE + SRAM_SIZE)
        uc.hook_add(UC_HOOK_MEM_READ, self.on_sram_read,
                    begin=SRAM_BASE, end=SRAM_BASE + SRAM_SIZE)
        uc.hook_add(UC_HOOK_BLOCK, self.on_block)
        uc.hook_add(UC_HOOK_CODE, self.on_code)
        uc.hook_add(UC_HOOK_MEM_UNMAPPED, self.on_unmapped)
        uc.hook_add(UC_HOOK_INTR, self.on_intr)

    # ---------- 日志 ----------
    def log(self, msg: str):
        self.events.append((self.steps, msg))
        if self.verbose:
            print(f"[{self.steps:>10}] {msg}")

    # ---------- 外设读写模型 ----------
    def on_read(self, uc, access, addr, size, value, user):
        val = self.reg_state.get(addr, 0)
        name = periph_name(addr)
        if addr == RCC + 0x00:
            # RCC_CR：就绪位必须跟随使能位。固件在切换 PLL 源之前会先关 PLL
            # 并等待 PLLRDY 清零；把就绪位恒置为 1 会让那个等待永远超时。
            for on_bit, rdy_bit in ((0, 1), (16, 17), (24, 25)):
                if val & (1 << on_bit):
                    val |= 1 << rdy_bit
                else:
                    val &= ~(1 << rdy_bit)
        elif addr == RCC + 0x34:          # RCC_CR2：HSI14 / HSI48 就绪跟随使能
            for on_bit, rdy_bit in ((0, 1), (16, 17)):
                if val & (1 << on_bit):
                    val |= 1 << rdy_bit
                else:
                    val &= ~(1 << rdy_bit)
        elif addr == RCC + 0x04:          # RCC_CFGR：SWS 跟随 SW
            sw = val & 0x3
            val = (val & ~0xC) | (sw << 2)
        elif addr == RCC + 0x24:          # RCC_CSR：LSI 就绪跟随使能
            val = (val | 0x2) if (val & 0x1) else (val & ~0x2)
        elif addr == RCC + 0x20:          # RCC_BDCR：LSE 就绪跟随使能
            val = (val | 0x2) if (val & 0x1) else (val & ~0x2)
        elif (addr & ~0x3FF) in USART_BASES and (addr & 0x3FF) == 0x1C:
            # USART_ISR：TXE|TC 恒置（发送永不阻塞）；本实例有待收字节时置 RXNE
            base = addr & ~0x3FF
            cr1 = self.reg_state.get(base + 0x00, 0)
            # bit7 TXE、bit6 TC 恒置；bit5 RXNE 跟随接收队列；
            # bit20 TEACK、bit21 REACK 是使能确认位，固件初始化串口时会等它们，
            # 缺这两位会让 UART 初始化一直超时。
            val = 0xC0 | (0x20 if self.uart_rx.get(base) else 0)
            if cr1 & (1 << 3):
                val |= 1 << 21   # TE -> TEACK
            if cr1 & (1 << 2):
                val |= 1 << 22   # RE -> REACK
            if self.trace_usart:
                pc = uc.reg_read(UC_ARM_REG_PC)
                key = (base, pc)
                self.usart_poll[key] += 1
                if self.usart_poll[key] <= 3:
                    from unicorn.arm_const import UC_ARM_REG_R5, UC_ARM_REG_R7
                    self.log(f"{USART_BASES[base]}.ISR 轮询 pc={pc:#010x} "
                             f"掩码={uc.reg_read(UC_ARM_REG_R5):#x} "
                             f"期望={uc.reg_read(UC_ARM_REG_R7):#x} 返回={val:#x}")
        elif (addr & ~0x3FF) in USART_BASES and (addr & 0x3FF) == 0x24:
            base = addr & ~0x3FF
            q = self.uart_rx.get(base)
            val = q.popleft() if q else 0
            self.log(f"{USART_BASES[base]} RX <- {val:#04x} {chr(val) if 32 <= val < 127 else ''}")
        elif addr == SPI1 + 0x08:         # SPI1_SR：TXE|RXNE
            val = 0x3
        elif addr == SPI1 + 0x0C:         # SPI1_DR
            val = 0
        elif addr == 0x40022000 + 0x0C:   # FLASH_SR：BSY=0
            val = 0
        elif addr == 0x40010400 + 0x14:   # EXTI_PR
            val = self.reg_state.get(addr, 0)
        elif GPIO_BASE <= addr < GPIO_BASE + GPIO_SIZE and (addr & 0x3FF) == 0x10:
            port = addr & ~0x3FF
            if port == 0x48000800 and self.hobib_toggle:
                # PC10 是 SCT3258 的 HOBIB 状态线。SCT 本身是模拟器外的黑箱，
                # 这里让该位在连续读取时翻转，使固件的忙等能够推进，从而暴露
                # 后续控制序列。翻转是替身行为，不代表真实 SCT 时序。
                self.hobib_reads += 1
                if self.hobib_reads % self.hobib_toggle == 0:
                    self.gpio_idr[port] ^= 1 << 10
            val = self.gpio_idr[port] | (self.gpio_odr[port] & 0xFFFF)
            if self.trace_gpio:
                self.log(f"R {name} -> {val:#06x}")
        elif addr == SYSTICK_CSR:
            val = self.reg_state.get(addr, 0)
            # COUNTFLAG 置位一次
            val |= 0x10000
        elif addr == SYSTICK_CVR:
            val = (self.reg_state.get(SYSTICK_RVR, 0) - (self.steps % max(1, self.systick_period))) & 0xFFFFFF
        if self.trace_all and not name.startswith("GPIO"):
            self.log(f"R {name} -> {val:#010x}")
        uc.mem_write(addr, struct.pack("<I", val & 0xFFFFFFFF)[:size])

    def on_write(self, uc, access, addr, size, value, user):
        name = periph_name(addr)
        self.reg_state[addr] = value
        if GPIO_BASE <= addr < GPIO_BASE + GPIO_SIZE:
            port = addr & ~0x3FF
            reg = addr & 0x3FF
            if reg == 0x14:
                self.gpio_odr[port] = value & 0xFFFF
            elif reg == 0x18:
                self.gpio_odr[port] = (self.gpio_odr[port] | (value & 0xFFFF)) & ~((value >> 16) & 0xFFFF)
            elif reg == 0x28:
                self.gpio_odr[port] &= ~(value & 0xFFFF)
            if self.trace_gpio and reg in (0x14, 0x18, 0x28):
                self.log(f"W {name} <- {value:#010x}  ODR{GPIO_PORTS[port]}={self.gpio_odr[port]:#06x}")
            elif self.trace_all:
                self.log(f"W {name} <- {value:#010x}")
            return
        if (addr & ~0x3FF) in USART_BASES and (addr & 0x3FF) == 0x28:  # USART_TDR
            base = addr & ~0x3FF
            self.uart_tx[base].append(value & 0xFF)
            if self.trace_usart:
                pc = self.uc.reg_read(UC_ARM_REG_PC)
                k = (base, pc, "tx")
                self.usart_poll[k] += 1
                if self.usart_poll[k] <= 4:
                    ch = chr(value & 0xFF)
                    self.log(f"{USART_BASES[base]} TX pc={pc:#010x} "
                             f"字节={value & 0xFF:#04x} {ch if 32 <= value & 0xFF < 127 else ''}")
            return
        if addr == 0x40010000:              # SYSCFG_CFGR1 MEM_MODE
            mode = value & 0x3
            if mode == 3 and not self.remap_sram:
                self.remap_sram = True
                self.sync_remap()
                self.log("SYSCFG 重映射：SRAM -> 0x00000000")
            elif mode != 3 and self.remap_sram:
                self.remap_sram = False
        if addr == SYSTICK_RVR:
            self.systick_period = (self.tick_steps if self.tick_steps
                                   else max(1, (value & 0xFFFFFF) // 4))
        if addr == SYSTICK_CSR and (value & 0x3) == 0x3:
            self.systick_next = self.steps + self.systick_period
            self.log(f"SysTick 启用，重装载={self.reg_state.get(SYSTICK_RVR, 0):#x}")
        if self.trace_all:
            self.log(f"W {name} <- {value:#010x}")

    def on_unmapped(self, uc, access, addr, size, value, user):
        self.log(f"未映射访问 addr={addr:#010x} pc={uc.reg_read(UC_ARM_REG_PC):#010x}")
        return False

    def on_intr(self, uc, intno, user):
        pc = uc.reg_read(UC_ARM_REG_PC)
        self.log(f"CPU 异常 intno={intno} pc={pc:#010x}")
        uc.emu_stop()

    def on_block(self, uc, addr, size, user):
        """记录执行路径。只在开启时工作，用于还原控制链实际走到了哪里。"""
        if not self.trace_path:
            return
        if self.in_exception:
            return
        self.path.append((self.steps, addr))

    # ---------- SRAM 监视 ----------
    def _watch_name(self, addr):
        for lo, hi, name in self.watches:
            if lo <= addr < hi:
                return name
        return None

    def on_sram_write(self, uc, access, addr, size, value, user):
        name = self._watch_name(addr)
        if name is None:
            return
        pc = uc.reg_read(UC_ARM_REG_PC)
        key = ("w", addr, pc)
        self.watch_hit[key] += 1
        if self.watch_hit[key] <= self.watch_limit:
            self.log(f"写 {name} {addr:#010x} <- {value:#x} ({size}字节) pc={pc:#010x}")

    def on_sram_read(self, uc, access, addr, size, value, user):
        if not self.watch_reads:
            return
        name = self._watch_name(addr)
        if name is None:
            return
        pc = uc.reg_read(UC_ARM_REG_PC)
        key = ("r", addr, pc)
        self.watch_hit[key] += 1
        if self.watch_hit[key] <= self.watch_limit:
            cur = struct.unpack("<I", uc.mem_read(addr & ~3, 4))[0]
            self.log(f"读 {name} {addr:#010x} -> {cur:#x} pc={pc:#010x}")

    # ---------- 异常注入 ----------
    def sync_remap(self):
        """重映射生效后，地址 0 必须镜像 SRAM 前 1 KiB（向量表所在）。"""
        if self.remap_sram:
            self.uc.mem_write(0, bytes(self.uc.mem_read(SRAM_BASE, 0x400)))

    def vector(self, index: int) -> int:
        base = SRAM_BASE if self.remap_sram else APP_BASE
        return struct.unpack("<I", self.uc.mem_read(base + index * 4, 4))[0]

    def inject_exception(self, index: int, tag: str):
        uc = self.uc
        self.sync_remap()
        handler = self.vector(index)
        if handler == 0 or handler == 0x080060e3 or handler < 0x1000:
            return False
        sp = uc.reg_read(UC_ARM_REG_SP)
        pc = uc.reg_read(UC_ARM_REG_PC)
        xpsr = uc.reg_read(UC_ARM_REG_XPSR)
        frame = struct.pack("<8I", uc.reg_read(UC_ARM_REG_R0), uc.reg_read(UC_ARM_REG_R1),
                            uc.reg_read(UC_ARM_REG_R2), uc.reg_read(UC_ARM_REG_R3),
                            uc.reg_read(UC_ARM_REG_R12), uc.reg_read(UC_ARM_REG_LR),
                            pc | 1, xpsr | 0x01000000)
        self.exc_sp_stack.append(sp)
        sp = (sp - 32) & ~0x7
        uc.mem_write(sp, frame)
        uc.reg_write(UC_ARM_REG_SP, sp)
        uc.reg_write(UC_ARM_REG_LR, TRAMP_BASE | 1)
        uc.reg_write(UC_ARM_REG_PC, handler | 1)
        self.in_exception += 1
        if "中断" not in tag and "SysTick" not in tag:
            self.log(f"异常注入 {tag} -> {handler:#010x}")
        return True

    def exception_return(self):
        uc = self.uc
        sp = uc.reg_read(UC_ARM_REG_SP)
        r0, r1, r2, r3, r12, lr, pc, xpsr = struct.unpack("<8I", uc.mem_read(sp, 32))
        uc.reg_write(UC_ARM_REG_SP,
                     self.exc_sp_stack.pop() if self.exc_sp_stack else sp + 32)
        for reg, v in ((UC_ARM_REG_R0, r0), (UC_ARM_REG_R1, r1), (UC_ARM_REG_R2, r2),
                       (UC_ARM_REG_R3, r3), (UC_ARM_REG_R12, r12), (UC_ARM_REG_LR, lr)):
            uc.reg_write(reg, v)
        uc.reg_write(UC_ARM_REG_PC, pc | 1)
        self.in_exception -= 1

    # ---------- 指令钩子 ----------
    def on_code(self, uc, addr, size, user):
        self.steps += 1
        if addr in self.func_hooks:
            self.dump_call(addr)
        if addr in self.func_stubs:
            # 打桩：直接返回给定值并跳回调用者。用于跳过依赖 SCT3258 真实应答
            # 的函数，从而观察 MCU 侧后续控制写序。被跳过的调用都会记录，
            # 结论中必须标注这些点是替身而非真实行为。
            ret, name = self.func_stubs[addr]
            lr = uc.reg_read(UC_ARM_REG_LR)
            self.stub_hit[addr] += 1
            if self.stub_hit[addr] <= 12:
                r0 = uc.reg_read(UC_ARM_REG_R0)
                r1 = uc.reg_read(UC_ARM_REG_R1)
                self.log(f"打桩跳过 {name}@{addr:#010x} r0={r0:#x} r1={r1:#x} "
                         f"-> 返回{ret:#x} 回到 {lr:#010x}")
            uc.reg_write(UC_ARM_REG_R0, ret)
            uc.reg_write(UC_ARM_REG_PC, lr | 1)
            return
        if addr >= TRAMP_BASE and addr < TRAMP_BASE + TRAMP_SIZE:
            if self.call_return_pending and self.in_exception == 0:
                self.log("=== 直接调用返回 ===")
                uc.emu_stop()
                return
            self.exception_return()
            return
        if self.in_exception == 0:
            # 串口中断：固件用中断驱动收发，只模型化标志位不够——发送完一个
            # 字节后必须由 TXE 中断推进缓冲区索引，否则同一个字节会被反复写出。
            if self.steps >= self.usart_irq_next:
                self.usart_irq_next = self.steps + self.usart_irq_interval
                for base, irq in self.USART_IRQ.items():
                    cr1 = self.reg_state.get(base + 0x00, 0)
                    if not (cr1 & 0x1):        # UE 未使能
                        continue
                    tx_ready = bool(cr1 & (1 << 7))                    # TXEIE
                    rx_ready = bool(cr1 & (1 << 5)) and bool(self.uart_rx[base])
                    if tx_ready or rx_ready:
                        if self.inject_exception(16 + irq, f"{USART_BASES[base]}中断"):
                            return
            if self.systick_period and self.steps >= self.systick_next:
                self.systick_next = self.steps + self.systick_period
                self.inject_exception(15, "SysTick")
                return
            if self.pending_irq:
                irq = self.pending_irq.popleft()
                self.inject_exception(16 + irq, f"IRQ{irq}")
                return
        if self.steps >= self.max_steps:
            uc.emu_stop()

    def dump_call(self, addr):
        """在登记的函数入口记录传入参数，用于还原调用链的实际内容。"""
        uc = self.uc
        from unicorn.arm_const import UC_ARM_REG_R4, UC_ARM_REG_R5
        r0 = uc.reg_read(UC_ARM_REG_R0)
        r1 = uc.reg_read(UC_ARM_REG_R1)
        r2 = uc.reg_read(UC_ARM_REG_R2)
        r3 = uc.reg_read(UC_ARM_REG_R3)
        lr = uc.reg_read(UC_ARM_REG_LR)
        name = self.func_hooks[addr]
        self.func_hit[addr] += 1
        if self.func_hit[addr] > self.func_hook_limit:
            return
        extra = ""
        # 参数看起来像缓冲区时，把内容一并取出
        if 0x20000000 <= r0 < 0x20010000 or FLASH_BASE <= r0 < FLASH_BASE + FLASH_SIZE:
            n = r1 if 0 < r1 <= 64 else 32
            try:
                data = bytes(uc.mem_read(r0, n))
                printable = "".join(chr(c) if 32 <= c < 127 else "." for c in data)
                extra = f" buf={data.hex()} |{printable}|"
            except UcError:
                pass
        self.log(f"调用 {name}@{addr:#010x} r0={r0:#x} r1={r1:#x} r2={r2:#x} "
                 f"r3={r3:#x} lr={lr:#010x}{extra}")

    # ---------- 运行 ----------
    def run(self, max_steps: int, start: int | None = None):
        self.max_steps = max_steps
        uc = self.uc
        if start is None:
            sp, reset = struct.unpack("<2I", self.flash[APP_BASE - FLASH_BASE:APP_BASE - FLASH_BASE + 8])
            uc.reg_write(UC_ARM_REG_SP, sp)
            start = reset
        try:
            uc.emu_start(start | 1, 0, count=0)
        except UcError as exc:
            self.log(f"UcError {exc} pc={uc.reg_read(UC_ARM_REG_PC):#010x}")

    # USART 实例 -> STM32F0 中断号
    USART_IRQ = {0x40013800: 27, 0x40004400: 28, 0x40004800: 29, 0x40004C00: 29}

    def send_uart(self, text: str, base: int = USART1):
        for b in text.encode():
            self.uart_rx[base].append(b)

    def tx_summary(self) -> str:
        parts = []
        for base, name in USART_BASES.items():
            if self.uart_tx[base]:
                parts.append(f"{name}: {bytes(self.uart_tx[base])!r}")
        return "  |  ".join(parts) if parts else "(无串口输出)"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--flash", required=True)
    ap.add_argument("--steps", type=int, default=5_000_000)
    ap.add_argument("--cmd", action="append", default=[], help="启动后注入的文本命令（自动补 \\r\\n）")
    ap.add_argument("--cmd-after", type=int, default=2_000_000, help="注入命令前先运行的指令数")
    ap.add_argument("--trace-all", action="store_true")
    ap.add_argument("--no-gpio", action="store_true")
    ap.add_argument("-v", "--verbose", action="store_true")
    ap.add_argument("--tick-steps", type=int, default=0,
                    help="固定 SysTick 注入间隔（指令数），压缩毫秒忙等；0 为按重装载值")
    ap.add_argument("--trace-usart", action="store_true", help="记录串口标志轮询")
    ap.add_argument("--trace-path", action="store_true",
                    help="记录直接调用期间的执行路径（基本块）")
    ap.add_argument("--stub", action="append", default=[],
                    help="函数打桩，格式 地址:返回值[:名称]，跳过依赖外部芯片应答的调用")
    ap.add_argument("--call", default=None,
                    help="启动阶段结束后直接调用该地址的函数（十六进制），用于观察"
                         "指定控制链的实际寄存器写序")
    ap.add_argument("--call-args", default="",
                    help="调用参数，逗号分隔的十六进制，依次放入 r0..r3")
    ap.add_argument("--call-steps", type=int, default=2_000_000)
    ap.add_argument("--dump", action="append", default=[],
                    help="结束时转储内存，格式 起始[+长度][:名称]，如 20000504+40:接收环")
    ap.add_argument("--watch", action="append", default=[],
                    help="SRAM 监视，格式 起始[-结束][:名称]，如 20000504-20000cd4:接收环")
    ap.add_argument("--watch-reads", action="store_true", help="监视读取，不只是写入")
    ap.add_argument("--watch-limit", type=int, default=20)
    ap.add_argument("--hook", action="append", default=[],
                    help="函数入口追踪，格式 地址[:名称]，如 8010b7c:uart_send")
    ap.add_argument("--hook-limit", type=int, default=30)
    ap.add_argument("--hobib-toggle", type=int, default=0,
                    help="PC10 HOBIB 每读取 N 次翻转一次（SCT 黑箱替身），0 为保持不变")
    ap.add_argument("--port", default=None, help="命令注入的 USART 基址，如 40013800")
    ap.add_argument("--out", default="emu_trace.txt")
    args = ap.parse_args()

    flash = open(args.flash, "rb").read()
    emu = Emu(flash, trace_gpio=not args.no_gpio, trace_all=args.trace_all,
              verbose=args.verbose, tick_steps=args.tick_steps,
              trace_usart=args.trace_usart)
    emu.hobib_toggle = args.hobib_toggle
    emu.func_hook_limit = args.hook_limit
    emu.watch_reads = args.watch_reads
    emu.watch_limit = args.watch_limit
    for spec in args.watch:
        rng, _, nm = spec.partition(":")
        lo, _, hi = rng.partition("-")
        lo_i = int(lo, 16)
        hi_i = int(hi, 16) + 1 if hi else lo_i + 4
        emu.watches.append((lo_i, hi_i, nm or f"{lo_i:#x}"))
    for spec in args.stub:
        parts = spec.split(":")
        emu.func_stubs[int(parts[0], 16)] = (
            int(parts[1], 16) if len(parts) > 1 else 0,
            parts[2] if len(parts) > 2 else f"fn_{parts[0]}")
    for spec in args.hook:
        part = spec.split(":")
        emu.func_hooks[int(part[0], 16)] = part[1] if len(part) > 1 else f"fn_{part[0]}"
    emu.run(args.cmd_after)
    print(f"初始化阶段结束：steps={emu.steps} pc={emu.uc.reg_read(UC_ARM_REG_PC):#010x} "
          f"remap_sram={emu.remap_sram} systick_period={emu.systick_period}")
    print("串口输出：", emu.tx_summary()[:600])
    port = int(args.port, 16) if args.port else USART1
    for cmd in args.cmd:
        before = {b: len(emu.uart_tx[b]) for b in USART_BASES}
        emu.send_uart(cmd + "\r\n", port)
        emu.run(emu.steps + args.steps, start=emu.uc.reg_read(UC_ARM_REG_PC))
        print(f"命令 {cmd!r} 之后 steps={emu.steps} pc={emu.uc.reg_read(UC_ARM_REG_PC):#010x}")
        got = False
        for b, name in USART_BASES.items():
            new = bytes(emu.uart_tx[b][before[b]:])
            if new:
                got = True
                print(f"  {name} 新增输出: {new[:400]!r}")
        if not got:
            print("  （无串口输出）")
    if args.call:
        target = int(args.call, 16)
        from unicorn.arm_const import UC_ARM_REG_R0 as _R0
        argv = [int(x, 16) for x in args.call_args.split(",") if x.strip()]
        for i, v in enumerate(argv[:4]):
            emu.uc.reg_write([UC_ARM_REG_R0, UC_ARM_REG_R1,
                              UC_ARM_REG_R2, UC_ARM_REG_R3][i], v)
        # 返回地址指向跳板，函数返回时模拟自然停止
        emu.uc.reg_write(UC_ARM_REG_LR, TRAMP_BASE | 1)
        emu.call_return_pending = True
        emu.trace_path = args.trace_path
        emu.path.clear()
        emu.trace_gpio = True
        emu.log(f"=== 直接调用 {target:#010x} 参数={[hex(a) for a in argv]} ===")
        mark = len(emu.events)
        emu.run(emu.steps + args.call_steps, start=target)
        print(f"直接调用 {target:#010x} 结束：steps={emu.steps} "
              f"pc={emu.uc.reg_read(UC_ARM_REG_PC):#010x} "
              f"r0={emu.uc.reg_read(UC_ARM_REG_R0):#x}")
        print(f"该调用期间记录 {len(emu.events) - mark} 条事件")
        if args.trace_path:
            print(f"执行路径 {len(emu.path)} 个基本块，首次进入顺序：")
            seen = set()
            order = []
            for st, a in emu.path:
                if a not in seen:
                    seen.add(a)
                    order.append((st, a))
            for st, a in order[:120]:
                print(f"  {st:>10} {a:#010x}")

    for spec in args.dump:
        rng, _, nm = spec.partition(":")
        lo, _, ln = rng.partition("+")
        lo_i = int(lo, 16)
        n = int(ln, 16) if ln else 64
        data = bytes(emu.uc.mem_read(lo_i, n))
        printable = "".join(chr(c) if 32 <= c < 127 else "." for c in data)
        print(f"转储 {nm or hex(lo_i)} {lo_i:#010x}+{n:#x}:")
        print(f"  {data.hex()}")
        print(f"  |{printable}|")
    with open(args.out, "w", encoding="utf-8") as f:
        for step, msg in emu.events:
            f.write(f"{step:>10} {msg}\n")
    print(f"事件 {len(emu.events)} 条已写入 {args.out}")


if __name__ == "__main__":
    main()
