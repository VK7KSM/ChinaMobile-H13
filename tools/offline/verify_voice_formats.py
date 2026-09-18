#!/usr/bin/env python3
"""离线核验语音注入格式候选构造出的线上字节。

与设备无关，不需要连接任何硬件。用于在上机之前确认每个候选的信封头、
字段标识、长度字段与载荷位置都符合预期，并与既有接收样本的格式对照。

依据：
- 信封为 84 A9 61 + 两字节大端正文长度 + 包类型，正文从偏移 6 开始；
  正文长度为奇数时在末尾补一个不计入长度的零字节。
- 模块上报接收语音的实测格式为包类型 0x20、字段 0x01、长度 27。
"""

SYNC = bytes([0x84, 0xA9, 0x61])


def frame(packet_type: int, body: bytes) -> bytes:
    raw = SYNC + bytes([len(body) >> 8, len(body) & 0xFF, packet_type]) + body
    return raw if len(raw) % 2 == 0 else raw + b"\x00"


def legacy_chan_d36(payload36: bytes) -> bytes:
    assert len(payload36) == 36
    return frame(3, bytes([0x01, 0x24]) + payload36)


def chan_d27(payload27: bytes, packet_type: int) -> bytes:
    assert len(payload27) == 27
    return frame(packet_type, bytes([0x01, 27]) + payload27)


def digc_voice_burst(payload27: bytes) -> bytes:
    assert len(payload27) == 27
    return frame(5, bytes([0x43, 0x10, 27]) + payload27)


def describe(name: str, wire: bytes, body_offset: int, payload_len: int):
    body = wire[6:]
    print(f"  {name}")
    print(f"    线上总长 {len(wire)} 字节，正文长度字段 "
          f"{(wire[3] << 8) | wire[4]}，包类型 {wire[5]:#04x}")
    print(f"    正文前若干字节 {body[:body_offset].hex(' ')}")
    print(f"    载荷起点偏移 {6 + body_offset}，载荷长度 {payload_len}")
    print(f"    完整帧 {wire[:10].hex(' ')} …")


def main():
    # 三个 9 字节语音帧组成一个 27 字节突发；四个组成历史的 36 字节单元
    f = [bytes([0xA0 + i] * 9) for i in range(4)]
    burst27 = f[0] + f[1] + f[2]
    unit36 = f[0] + f[1] + f[2] + f[3]

    print("语音注入格式候选核验\n")
    print("历史实现（当前基线，默认启用）：")
    describe("字段 0x01、36 字节、包类型 3",
             legacy_chan_d36(unit36), 2, 36)

    print("\n候选一 只改载荷长度：")
    describe("字段 0x01、27 字节、包类型 3",
             chan_d27(burst27, 3), 2, 27)

    print("\n候选二 与接收方向对称：")
    describe("字段 0x01、27 字节、包类型 0",
             chan_d27(burst27, 0), 2, 27)

    print("\n候选三 手册规定的 DMR 数据帧：")
    describe("字段 0x43、帧属性 0x10、27 字节、包类型 5",
             digc_voice_burst(burst27), 3, 27)

    print("\n对照：模块上报接收语音的实测格式")
    rx = frame(0x20, bytes([0x01, 27]) + burst27)
    describe("字段 0x01、27 字节、包类型 0x20（读方向）", rx, 2, 27)

    print("\n要点")
    print("  接收方向包类型 0x20 的位 5 为 1 表示读，低四位为 0 即控制包。")
    print("  候选二把写方向设为同一低四位，是与接收最对称的写法。")
    print("  27 字节为三个语音帧约 60 毫秒，是 DMR 一个语音突发的原生长度。")
    print("  36 字节为四个语音帧约 80 毫秒，属 dPMR 的单位。")


if __name__ == "__main__":
    main()
