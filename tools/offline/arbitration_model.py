#!/usr/bin/env python3
"""单工会话仲裁模型：定规则、并用测试固化。

为什么先做模型而不是直接写服务：单机射频前端做不到同时收发（2.9.1），
因此本地 PTT、网络来话、扫描三者必须有明确的优先级与抢占规则。规则错了
会表现为"偶尔吞掉一次通话"这类难复现的故障，先把它写成可执行的状态机
并用用例钉死，比事后在服务里调试便宜得多。

状态：
    IDLE        空闲
    RX          空口正在接收（由 AT+DMOGETDIGITALRXINFO 判定，2.8.80）
    TX_LOCAL    本地 PTT 发射
    TX_NET      转发网络来话

规则（依据与理由写在各分支处）：

  1. 接收优先于一切发射。空口上已有人讲话时发射会压掉对方，单工设备
     无法察觉自己造成的干扰。
  2. 本地 PTT 优先于网络来话。操作者在现场，且可能是紧急情况。
  3. 已在发射时，新的网络来话进入队列而不是打断；队列只保留最近一条，
     因为语音过时即无意义。
  4. 发射有硬性时限（30 秒，2.9.4 安全与合规），到时强制回到空闲。
  5. 发射结束后有尾音期，期间不接受新发射，避免连续键控。

本模块只做决策，不碰硬件。
"""
from __future__ import annotations

from dataclasses import dataclass, field

IDLE, RX, TX_LOCAL, TX_NET = "IDLE", "RX", "TX_LOCAL", "TX_NET"

MAX_TX_MS = 30_000
HANG_MS = 500


@dataclass
class Arbiter:
    state: str = IDLE
    since_ms: int = 0
    hang_until_ms: int = 0
    queued_net: bool = False
    log: list[str] = field(default_factory=list)

    def _to(self, state: str, now_ms: int, why: str) -> None:
        self.log.append("%d %s→%s（%s）" % (now_ms, self.state, state, why))
        self.state = state
        self.since_ms = now_ms

    def on_rx_start(self, now_ms: int) -> None:
        """规则一：接收优先，正在发射也让位。"""
        if self.state in (TX_LOCAL, TX_NET):
            self._to(RX, now_ms, "接收优先于发射")
        elif self.state == IDLE:
            self._to(RX, now_ms, "空口来话")

    def on_rx_end(self, now_ms: int) -> None:
        if self.state == RX:
            self._to(IDLE, now_ms, "空口结束")
            if self.queued_net:
                self.queued_net = False
                self.request_net(now_ms)

    def request_local(self, now_ms: int) -> bool:
        """规则二：本地 PTT 优先于网络来话；规则一仍高于它。"""
        if self.state == RX:
            return False
        if now_ms < self.hang_until_ms:
            return False
        if self.state == TX_NET:
            self._to(TX_LOCAL, now_ms, "本地 PTT 抢占网络来话")
            return True
        if self.state == IDLE:
            self._to(TX_LOCAL, now_ms, "本地 PTT")
            return True
        return False

    def request_net(self, now_ms: int) -> bool:
        """规则三：发射中的网络来话入队，只留最近一条。"""
        if self.state == RX or self.state in (TX_LOCAL, TX_NET):
            self.queued_net = True
            return False
        if now_ms < self.hang_until_ms:
            self.queued_net = True
            return False
        self._to(TX_NET, now_ms, "网络来话")
        return True

    def release(self, now_ms: int) -> None:
        if self.state in (TX_LOCAL, TX_NET):
            self._to(IDLE, now_ms, "发射结束")
            self.hang_until_ms = now_ms + HANG_MS

    def tick(self, now_ms: int) -> None:
        """规则四：发射硬性时限。"""
        if self.state in (TX_LOCAL, TX_NET) and \
                now_ms - self.since_ms >= MAX_TX_MS:
            self._to(IDLE, now_ms, "超过 %d 毫秒发射上限" % MAX_TX_MS)
            self.hang_until_ms = now_ms + HANG_MS


def _selftest() -> int:
    bad = 0

    def check(cond: bool, what: str) -> None:
        nonlocal bad
        if not cond:
            bad += 1
            print("   不通过 %s" % what)
        else:
            print("   通过   %s" % what)

    print("规则一：接收优先")
    a = Arbiter()
    a.request_local(0)
    a.on_rx_start(100)
    check(a.state == RX, "发射中收到空口来话应让位")
    check(not a.request_local(200), "接收中不得发射")

    print("规则二：本地优先于网络")
    a = Arbiter()
    a.request_net(0)
    check(a.state == TX_NET, "空闲时网络来话可发")
    check(a.request_local(100) and a.state == TX_LOCAL, "本地 PTT 抢占网络")

    print("规则三：发射中的网络来话入队")
    a = Arbiter()
    a.request_local(0)
    check(not a.request_net(10) and a.queued_net, "发射中网络来话入队")
    a.release(20)
    check(a.state == IDLE, "松键回空闲")

    print("规则四：发射硬性时限")
    a = Arbiter()
    a.request_local(0)
    a.tick(MAX_TX_MS)
    check(a.state == IDLE, "到时强制回空闲")

    print("规则五：尾音期不接受新发射")
    a = Arbiter()
    a.request_local(0)
    a.release(100)
    check(not a.request_local(200), "尾音期内拒绝新发射")
    check(a.request_local(100 + HANG_MS), "尾音期后可发射")

    print("\n不通过项 %d" % bad)
    return 1 if bad else 0


if __name__ == "__main__":
    raise SystemExit(_selftest())
