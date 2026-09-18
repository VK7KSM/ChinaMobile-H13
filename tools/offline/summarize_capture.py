#!/usr/bin/env python3
"""从会话捕获目录提取关键指标，用于候选之间的横向对照。

手工比对多个会话容易遗漏，本脚本把每次会话的协议事实统一提取成同一组字段，
便于直接对照。只读取捕获目录，不接触设备。

提取内容：
  会话终态、各阶段确认数、语音包数与线上格式、时序、正文回拼哈希、
  模块主动上报的帧、失败原因。

用法：
    python summarize_capture.py <捕获目录> [<捕获目录> ...]
"""

from __future__ import annotations

import hashlib
import re
import sys
from pathlib import Path

FIELDS = [
    'result', 'terminal_phase', 'mode', 'setup_acks', 'vlc_acks',
    'termination_acks', 'cleanup_acks', 'data36_written',
    'relay_units_written', 'relay_credits_consumed',
    'recovery_error_count', 'reboot_required', 'failure',
]


def read_atomic(directory: Path) -> dict:
    """读取原子结果。宿主与设备侧各有一份，优先用设备侧。"""
    for name in ('device_capture/atomic_result.txt', 'atomic_result_host.txt'):
        path = directory / name
        if path.exists():
            text = path.read_bytes().decode('utf-8', 'replace')
            text = text.replace('﻿', '')
            out = {}
            for line in text.splitlines():
                if '=' in line:
                    key, _, value = line.partition('=')
                    out[key.strip()] = value.strip()
            return out
    return {}


def device_dir(directory: Path) -> Path | None:
    for name in ('device_capture', 'device_capture_480units'):
        path = directory / name
        if path.is_dir():
            # 抢救副本可能多一层
            inner = [p for p in path.iterdir() if p.is_dir()]
            if len(inner) == 1 and not any(path.glob('*.bin')):
                return inner[0]
            return path
    return None


def analyse_packets(devdir: Path) -> dict:
    """统计语音包并回拼正文。"""
    requests = sorted(devdir.glob('*data36_relay_request*.bin'),
                      key=lambda p: int(p.name.split('_')[0]))
    if not requests:
        return {}
    first = requests[0].read_bytes()
    # 信封: 同步3 + 长度2 + 包类型1，正文从偏移6起
    packet_type = first[5] if len(first) > 5 else None
    field = first[6] if len(first) > 6 else None
    # 载荷偏移: 字段0x43多一个帧属性字节
    payload_off = 9 if field == 0x43 else 8
    payload_len = len(first) - payload_off
    # 末尾补位字节不计入载荷
    declared = ((first[3] << 8) | first[4]) if len(first) > 4 else 0
    payload_len = declared - (payload_off - 6)

    body = b''.join(r.read_bytes()[payload_off:payload_off + payload_len]
                    for r in requests)
    return {
        '语音包数': len(requests),
        '线上单包字节': len(first),
        '包类型': f'0x{packet_type:02x}' if packet_type is not None else '?',
        '字段': f'0x{field:02x}' if field is not None else '?',
        '载荷字节': payload_len,
        '回拼正文字节': len(body),
        '回拼SHA256': hashlib.sha256(body).hexdigest().upper(),
    }


def analyse_uplink(devdir: Path) -> list:
    """找出模块主动上报的帧。"""
    found = []
    for path in sorted(devdir.glob('*predrain*.bin')):
        data = path.read_bytes()
        if len(data) < 8:
            continue
        offset = 0
        while offset + 6 <= len(data):
            if data[offset:offset + 3] != b'\x84\xa9\x61':
                break
            length = (data[offset + 3] << 8) | data[offset + 4]
            ptype = data[offset + 5]
            wire = 6 + length + ((6 + length) & 1)
            body = data[offset + 6:offset + 6 + length]
            if length >= 2:
                found.append({
                    '来源': path.name,
                    '包类型': f'0x{ptype:02x}',
                    '字段': f'0x{body[0]:02x}',
                    '正文长度': length,
                    '首字节': body[:3].hex(' '),
                })
            offset += wire
    return found


def timing(devdir: Path) -> dict:
    for path in devdir.glob('*relay_write_summary*.bin'):
        text = path.read_bytes().decode('utf-8', 'replace')
        out = {}
        for line in text.splitlines():
            if '=' in line:
                key, _, value = line.partition('=')
                out[key.strip()] = value.strip()
        if 'first_call_ms' in out and 'last_flush_ms' in out:
            span = int(out['last_flush_ms']) - int(out['first_call_ms'])
            out['首末间隔毫秒'] = span
        return out
    return {}


def main() -> None:
    if len(sys.argv) < 2:
        raise SystemExit('用法: summarize_capture.py <捕获目录> [...]')

    for arg in sys.argv[1:]:
        directory = Path(arg)
        print('=' * 70)
        print(directory.name)
        print('=' * 70)

        atomic = read_atomic(directory)
        if not atomic:
            print('  未找到原子结果')
            continue
        for key in FIELDS:
            if key in atomic and atomic[key]:
                print(f'  {key:<24} {atomic[key]}')

        devdir = device_dir(directory)
        if devdir is None:
            print('  未找到设备侧原件目录')
            print()
            continue

        pk = analyse_packets(devdir)
        if pk:
            print('  --- 语音包 ---')
            for key, value in pk.items():
                print(f'  {key:<24} {value}')

        tm = timing(devdir)
        if tm:
            print('  --- 时序 ---')
            for key in ('units', 'credits', 'first_call_ms',
                        'last_flush_ms', '首末间隔毫秒'):
                if key in tm:
                    print(f'  {key:<24} {tm[key]}')

        up = analyse_uplink(devdir)
        if up:
            print(f'  --- 模块主动上报 {len(up)} 帧 ---')
            for item in up[:6]:
                print(f'  包类型{item["包类型"]} 字段{item["字段"]} '
                      f'正文{item["正文长度"]}字节 首字节 {item["首字节"]}'
                      f'  ({item["来源"]})')
        print()


if __name__ == '__main__':
    main()
