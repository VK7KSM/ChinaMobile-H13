#!/usr/bin/env python3
"""射频服务的接口契约：把已建模的规则串成一份可执行的接口定义。

2.9.4 第三块要的是"射频核心服务"，前面已分别建模了仲裁（2.9.32）、
抖动缓冲（2.9.33）、TG 订阅与 Last Heard（2.9.35）、功率标定（2.9.34）。
这里定义它们对上暴露的接口——这是应用层"搭积木"的接缝，也是服务实现时
必须满足的行为。

契约的意义在于把**边界条件**写死。真正难缠的不是主流程，是拒绝的时机：
接收中不得发射、标定不足不得设功率、未订阅的 TG 不得占用射频。这些如果
到服务里再补，就会变成偶发故障。

接口（服务实现须逐条满足，用例即验收标准）：

    set_channel(freq, color_code, slot, tg, encrypted)   配置信道
    subscribe(tg, static)                                订阅 TG
    start_tx(source)                                     请求发射
    push_net_frame(frame)                                网络帧入缓冲
    on_module_offer()                                    模块交帧，取一帧回送
    stop_tx()                                            结束发射
    set_power(watts)                                     设定功率
    status()                                             当前状态

本模块只定义与验证行为，不碰硬件。
"""
from __future__ import annotations

from dataclasses import dataclass, field

import arbitration_model as arb
import jitter_buffer_model as jb
import power_calibration as pwr
import talkgroup_model as tgm

LOCAL, NET = "local", "net"


@dataclass
class RadioService:
    arbiter: arb.Arbiter = field(default_factory=arb.Arbiter)
    buffer: jb.JitterBuffer = field(default_factory=jb.JitterBuffer)
    router: tgm.TalkgroupRouter = field(default_factory=tgm.TalkgroupRouter)
    channel: dict | None = None
    power_code: int | None = None
    tx_source: str | None = None

    # ---- 配置 ----
    def set_channel(self, freq: int, color_code: int, slot: int,
                    tg: int, encrypted: bool) -> tuple[bool, str]:
        """明文是硬要求：加密开启时对端没有密钥，收到的必然是噪音（2.8.71）。"""
        if encrypted:
            return False, "拒绝：本项目只在明文信道工作"
        if not 0 <= color_code <= 15:
            return False, "色码超范围"
        if slot not in (0, 1):
            return False, "时隙须为 0 或 1"
        self.channel = dict(freq=freq, color_code=color_code, slot=slot,
                            tg=tg, encrypted=False)
        return True, "信道已设"

    def subscribe(self, tg: int, static: bool = True) -> None:
        if static:
            self.router.add_static(tg)

    def set_power(self, watts: float) -> tuple[bool, str]:
        code, why = pwr.code_for_watts(watts)
        if code is None:
            return False, "拒绝：%s" % why
        self.power_code = code
        return True, "功率码 %d（%s）" % (code, why)

    # ---- 发射 ----
    def start_tx(self, source: str, now_ms: int) -> tuple[bool, str]:
        if self.channel is None:
            return False, "拒绝：信道未配置"
        ok = (self.arbiter.request_local(now_ms) if source == LOCAL
              else self.arbiter.request_net(now_ms))
        if not ok:
            return False, "拒绝：仲裁不允许（当前 %s）" % self.arbiter.state
        self.tx_source = source
        return True, "开始发射（%s）" % source

    def push_net_frame(self, frame: bytes) -> None:
        """入缓冲的是一个 60 毫秒的供数单元（27 字节），不是单帧 AMBE。"""
        self.buffer.push(frame)

    def on_module_offer(self) -> bytes:
        """模块交一帧，必须回一帧——回不上就补静音，绝不断流（2.8.73）。"""
        return self.buffer.pop()

    def stop_tx(self, now_ms: int) -> None:
        self.arbiter.release(now_ms)
        self.tx_source = None

    # ---- 接收 ----
    def on_rx_start(self, now_ms: int) -> None:
        self.arbiter.on_rx_start(now_ms)

    def on_rx_frame(self, src: int, dst: int, stream: int, slot: int,
                    now_ms: int, errors: int = 0) -> bool:
        return self.router.on_frame(src, dst, stream, slot, now_ms, errors)

    def on_rx_end(self, now_ms: int) -> None:
        self.arbiter.on_rx_end(now_ms)
        self.router.flush(now_ms + tgm.CALL_GAP_MS + 1)

    def status(self) -> str:
        return ("状态 %s  信道 %s  功率码 %s  缓冲 %d 帧  Last Heard %d 条"
                % (self.arbiter.state,
                   "已设" if self.channel else "未设",
                   self.power_code if self.power_code else "未设",
                   len(self.buffer.queue), len(self.router.last_heard)))


def _selftest() -> int:
    bad = 0

    def check(cond: bool, what: str) -> None:
        nonlocal bad
        if not cond:
            bad += 1
            print("   不通过 %s" % what)
        else:
            print("   通过   %s" % what)

    print("信道：加密必须被拒绝")
    s = RadioService()
    ok, why = s.set_channel(433_550_000, 8, 0, 99, encrypted=True)
    check(not ok and "明文" in why, "加密信道被拒：%s" % why)
    ok, _ = s.set_channel(433_550_000, 8, 0, 99, encrypted=False)
    check(ok, "明文信道接受")

    print("未配置信道不得发射")
    s2 = RadioService()
    ok, why = s2.start_tx(LOCAL, 0)
    check(not ok and "信道未配置" in why, "拒绝：%s" % why)

    print("功率：标定不足必须拒绝而非外推")
    ok, why = s.set_power(1.0)
    check(not ok and "拒绝" in why, "拒绝：%s" % why)

    print("接收中不得发射")
    s.on_rx_start(1000)
    ok, why = s.start_tx(LOCAL, 1100)
    check(not ok, "拒绝：%s" % why)
    s.on_rx_end(2000)

    print("发射与供数：回送帧数必须等于交帧数")
    ok, _ = s.start_tx(LOCAL, 3000)
    check(ok, "空闲后可发射")
    for i in range(3):
        s.push_net_frame(bytes([i]) * jb.UNIT_BYTES)
    got = [s.on_module_offer() for _ in range(10)]
    check(len(got) == 10, "交 10 帧回 10 帧")
    check(s.buffer.underruns == 7, "缺的 7 帧补静音（实际 %d）"
          % s.buffer.underruns)
    s.stop_tx(4000)

    print("TG 订阅：未订阅不得占用射频")
    s.subscribe(99)
    check(s.on_rx_frame(1701, 99, 1, 0, 5000), "已订阅放行")
    check(not s.on_rx_frame(1701, 505, 1, 0, 5000), "未订阅丢弃")

    print("\n" + s.status())
    print("\n不通过项 %d" % bad)
    return 1 if bad else 0


if __name__ == "__main__":
    raise SystemExit(_selftest())
