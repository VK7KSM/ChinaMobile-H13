#!/usr/bin/env python3
# Offline replay of v288-v290 captures using parseHpiEvidence-equivalent rules.
from __future__ import annotations

import hashlib
from pathlib import Path

# tools/ -> h13_radio/ -> research/ -> workspace root
ROOT = Path(__file__).resolve().parents[3]
OUT = ROOT / "research" / "h13_radio" / "analysis" / "2026-08-15-v288-v290-offline-replay"


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    h.update(path.read_bytes())
    return h.hexdigest().upper()


def parse_hpi_evidence(raw: bytes) -> dict:
    r = {
        "controlAcks": 0,
        "inputCredits": 0,
        "candidate27": 0,
        "candidate36": 0,
        "asyncEvents": 0,
        "malformedEvents": 0,
        "firstMal": -1,
        "firstMalDetail": "",
    }
    if not raw:
        return r
    n = len(raw)
    o = 0

    def has_sync(i: int) -> bool:
        return i + 3 <= n and raw[i] == 0x84 and raw[i + 1] == 0xA9 and raw[i + 2] == 0x61

    def next_sync(i: int) -> int:
        for j in range(i, n - 2):
            if has_sync(j):
                return j
        return -1

    def mark_mal(off: int, detail: str) -> None:
        r["malformedEvents"] += 1
        if r["firstMal"] < 0:
            r["firstMal"] = off
            r["firstMalDetail"] = detail

    while o < n:
        if not has_sync(o):
            ns = next_sync(o + 1)
            end = ns if ns >= 0 else n
            mark_mal(o, "non_hpi")
            o = end
            continue
        if n - o < 6:
            mark_mal(o, "header_trunc")
            break
        body_len = (raw[o + 3] << 8) | raw[o + 4]
        decl = 6 + body_len
        if body_len > 8192 or decl > n - o:
            mark_mal(o, "len_bad")
            break
        pad = decl & 1
        wire = decl + pad
        if pad:
            if wire > n - o or raw[o + decl] != 0:
                mark_mal(o, "pad_bad")
                o = max(o + 1, min(n, o + decl))
                continue
        ptype = raw[o + 5]
        body = raw[o + 6 : o + decl]
        field = body[0] if body else -1
        if ptype == 0 and len(body) == 2 and body[1] == 0 and field in (0x3E, 0x1A, 0x18):
            r["controlAcks"] += 1
        elif len(body) in (1, 2) and field in (0, 1):
            r["inputCredits"] += 1
        elif ptype == 0x20 and field == 0x01 and len(body) >= 2:
            dlen = body[1]
            if dlen in (27, 36) and len(body) == dlen + 2:
                if dlen == 27:
                    r["candidate27"] += 1
                else:
                    r["candidate36"] += 1
            else:
                mark_mal(o, "type20_bad")
        else:
            r["asyncEvents"] += 1
        o += wire
    return r


def main() -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    cases = [
        (
            "v288",
            ROOT
            / "research/h13_radio/captures/2026-08-15/v288-allow4-out10s-1-20260815_150645/device_pull",
            ["h13_frame_adapter_output_window_"],
        ),
        (
            "v289",
            ROOT
            / "research/h13_radio/captures/2026-08-15/v289-allow4-predrain-1-20260815_151508/device_pull",
            ["h13_frame_adapter_pre_drain_rx_", "h13_frame_adapter_output_window_"],
        ),
        (
            "v290",
            ROOT
            / "research/h13_radio/captures/2026-08-15/v290-allow4-prespeech-drain-1-20260815_152232/device_pull",
            [
                "h13_frame_adapter_pre_speech_drain_",
                "h13_frame_adapter_pre_speech_quiet_",
                "h13_frame_adapter_pre_drain_rx_",
                "h13_frame_adapter_output_window_",
            ],
        ),
    ]
    tsv = [
        "version\tfile\tbytes\tsha256\tcontrolAcks\tinputCredits\tc27\tc36\tasync\tmalformed\tfirstMalOff\tfirstMalDetail\thead16"
    ]
    md = [
        "# v288-v290 离线回放（对齐 parseHpiEvidence / isPcmInputCredit）",
        "",
        "规则：控制 ACK 优先；载荷长 1/2 且首字节 0/1 为 inputCredit（不限制 packet type）；"
        "type20/field01+27/36 为候选 AMBE；同步/长度/补位错误为 malformed。",
        "",
    ]
    for ver, pull, prefs in cases:
        md.append(f"## {ver}")
        for pref in prefs:
            fs = [
                p
                for p in pull.glob(pref + "*.bin")
                if "merged" not in p.name and p.is_file()
            ]
            if len(fs) != 1:
                md.append(f"- `{pref}*` 文件数={len(fs)}")
                continue
            path = fs[0]
            raw = path.read_bytes()
            digest = sha256_file(path)
            r = parse_hpi_evidence(raw)
            head = raw[:16].hex(" ")
            md.append(f"- **{path.name}** len={len(raw)} SHA-256=`{digest}`")
            md.append(
                "  - "
                f"credits={r['inputCredits']} mal={r['malformedEvents']} "
                f"c27={r['candidate27']} c36={r['candidate36']} "
                f"async={r['asyncEvents']} ctrl={r['controlAcks']} "
                f"firstMal={r['firstMal']}:{r['firstMalDetail']}"
            )
            md.append(f"  - head16=`{head}`")
            dest = OUT / f"{ver}_{path.name}"
            dest.write_bytes(raw)
            tsv.append(
                f"{ver}\t{path.name}\t{len(raw)}\t{digest}\t"
                f"{r['controlAcks']}\t{r['inputCredits']}\t{r['candidate27']}\t"
                f"{r['candidate36']}\t{r['asyncEvents']}\t{r['malformedEvents']}\t"
                f"{r['firstMal']}\t{r['firstMalDetail']}\t{head}"
            )
        md.append("")
    md.extend(
        [
            "## 锁定结论（阶段A）",
            "- v2.88 输出窗：3 合法 type20/27，0 信用，0 畸形",
            "- v2.89 pre_drain_rx：2 合法 type20；output_window：2 合法后畸形（偏移72 `84 a9 61 ff`）",
            "- v2.90 pre_speech_drain：大量合法 type20；quiet 仍有流（静默失败）；"
            "pre_drain_rx=0；output 畸形（`84 ff a9 61`）；0 信用",
            "- 步骤5 未关闭；禁止 RF；禁止在 0x90 上继续延时/排空/分片真机",
            "",
        ]
    )
    (OUT / "offline_replay_report.md").write_text("\n".join(md), encoding="utf-8")
    (OUT / "offline_replay_results.tsv").write_text("\n".join(tsv) + "\n", encoding="utf-8")
    art = ["path\tbytes\tsha256"]
    for p in sorted(OUT.iterdir()):
        if p.is_file():
            art.append(f"{p.name}\t{p.stat().st_size}\t{sha256_file(p)}")
    (OUT / "artifacts_sha256.tsv").write_text("\n".join(art) + "\n", encoding="utf-8")
    print("OUT", OUT)
    print((OUT / "offline_replay_results.tsv").read_text(encoding="utf-8"))


if __name__ == "__main__":
    main()
