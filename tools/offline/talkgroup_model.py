#!/usr/bin/env python3
"""TG 订阅与 Last Heard 模型。

网络侧已有 Homebrew 客户端（plan_b `src/homebrew.rs`，每帧带 src_id、
dst_id、stream_id、slot），缺的是两件事：**哪些 TG 应当放行**，以及
**把逐帧数据聚合成一次通话**。规则错了表现为"收不到某个群"或"Last Heard
一次通话拆成几十条"，都很难事后查，因此先写成带用例的模型。

TG 订阅（沿用 BrandMeister 的通行做法）：

  静态 TG   始终放行
  动态 TG   向该 TG 发射即激活，此后一段时间内放行；超时自动失效
  其余      丢弃，不占用射频

Last Heard 聚合：

  一次通话由 (主叫, 目标, 流水号) 唯一确定，逐帧并入同一条
  记录起止时刻、帧数、误码累计；通话结束或超时后落定

本模块只做决策与聚合，不碰网络。
"""
from __future__ import annotations

from dataclasses import dataclass, field

DYNAMIC_TTL_MS = 15 * 60 * 1000      # 动态 TG 15 分钟无活动即失效
CALL_GAP_MS = 2000                   # 超过此间隔视为新通话
MAX_LAST_HEARD = 50


@dataclass
class Call:
    src: int
    dst: int
    stream: int
    slot: int
    start_ms: int
    last_ms: int
    frames: int = 0
    errors: int = 0

    @property
    def duration_ms(self) -> int:
        return self.last_ms - self.start_ms

    def __str__(self) -> str:
        return ("主叫 %d → 目标 %d  时隙%d  %.1f 秒  %d 帧  误码 %d"
                % (self.src, self.dst, self.slot + 1,
                   self.duration_ms / 1000.0, self.frames, self.errors))


@dataclass
class TalkgroupRouter:
    static_tgs: set[int] = field(default_factory=set)
    dynamic: dict[int, int] = field(default_factory=dict)   # tg -> 最后活动
    active: dict[tuple[int, int, int], Call] = field(default_factory=dict)
    last_heard: list[Call] = field(default_factory=list)

    # ---- TG 订阅 ----
    def add_static(self, tg: int) -> None:
        self.static_tgs.add(tg)

    def on_local_transmit(self, tg: int, now_ms: int) -> None:
        """向某 TG 发射即激活动态订阅。"""
        if tg not in self.static_tgs:
            self.dynamic[tg] = now_ms

    def expire(self, now_ms: int) -> list[int]:
        dead = [tg for tg, t in self.dynamic.items()
                if now_ms - t >= DYNAMIC_TTL_MS]
        for tg in dead:
            del self.dynamic[tg]
        return dead

    def allows(self, tg: int, now_ms: int) -> bool:
        self.expire(now_ms)
        if tg in self.static_tgs:
            return True
        if tg in self.dynamic:
            self.dynamic[tg] = now_ms      # 有流量即续期
            return True
        return False

    # ---- Last Heard 聚合 ----
    def on_frame(self, src: int, dst: int, stream: int, slot: int,
                 now_ms: int, errors: int = 0) -> bool:
        """并入一次通话；返回该帧是否放行。"""
        if not self.allows(dst, now_ms):
            return False
        key = (src, dst, stream)
        call = self.active.get(key)
        if call is None or now_ms - call.last_ms > CALL_GAP_MS:
            if call is not None:
                self._finish(key)
            call = Call(src, dst, stream, slot, now_ms, now_ms)
            self.active[key] = call
        call.last_ms = now_ms
        call.frames += 1
        call.errors += errors
        return True

    def _finish(self, key) -> None:
        call = self.active.pop(key, None)
        if call is None:
            return
        self.last_heard.insert(0, call)
        del self.last_heard[MAX_LAST_HEARD:]

    def flush(self, now_ms: int) -> None:
        for key, call in list(self.active.items()):
            if now_ms - call.last_ms > CALL_GAP_MS:
                self._finish(key)


def _selftest() -> int:
    bad = 0

    def check(cond: bool, what: str) -> None:
        nonlocal bad
        if not cond:
            bad += 1
            print("   不通过 %s" % what)
        else:
            print("   通过   %s" % what)

    print("静态 TG 始终放行")
    r = TalkgroupRouter()
    r.add_static(91)
    check(r.allows(91, 0), "静态 TG 放行")
    check(not r.allows(505, 0), "未订阅 TG 丢弃")

    print("动态 TG：发射即激活，超时失效")
    r = TalkgroupRouter()
    r.on_local_transmit(505, 0)
    check(r.allows(505, 1000), "发射后放行")
    check(r.allows(505, DYNAMIC_TTL_MS - 1), "有效期内放行")
    r2 = TalkgroupRouter()
    r2.on_local_transmit(505, 0)
    check(not r2.allows(505, DYNAMIC_TTL_MS + 1), "超时后失效")

    print("动态 TG 有流量即续期")
    r = TalkgroupRouter()
    r.on_local_transmit(505, 0)
    r.allows(505, DYNAMIC_TTL_MS - 1000)
    check(r.allows(505, DYNAMIC_TTL_MS + 1000), "续期后仍放行")

    print("Last Heard：逐帧并入一次通话")
    r = TalkgroupRouter()
    r.add_static(99)
    for i in range(50):
        r.on_frame(1701, 99, 0xABCD, 0, i * 60, errors=1)
    r.flush(50 * 60 + CALL_GAP_MS + 1)
    check(len(r.last_heard) == 1, "50 帧聚合成一条")
    c = r.last_heard[0]
    check(c.frames == 50 and c.errors == 50, "帧数与误码累计正确")
    check(abs(c.duration_ms - 49 * 60) < 1, "时长 %.1f 秒" % (c.duration_ms / 1000))
    print("   " + str(c))

    print("间隔超限视为新通话")
    r = TalkgroupRouter()
    r.add_static(99)
    r.on_frame(1701, 99, 1, 0, 0)
    r.on_frame(1701, 99, 1, 0, CALL_GAP_MS + 100)
    r.flush(CALL_GAP_MS * 3)
    check(len(r.last_heard) == 2, "间隔超限拆成两条")

    print("未订阅 TG 的帧不进 Last Heard")
    r = TalkgroupRouter()
    check(not r.on_frame(1701, 505, 1, 0, 0), "未订阅帧被丢弃")
    r.flush(CALL_GAP_MS * 2)
    check(len(r.last_heard) == 0, "不产生记录")

    print("\n不通过项 %d" % bad)
    return 1 if bad else 0


if __name__ == "__main__":
    raise SystemExit(_selftest())
