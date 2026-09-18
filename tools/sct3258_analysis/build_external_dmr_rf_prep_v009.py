#!/usr/bin/env python3
"""生成v009链上RF准备桩：把已证实的时隙门从16扩为160。"""
from __future__ import annotations

import hashlib
import importlib.util
import re
import struct
from pathlib import Path


RF_PREP = 0x20003100
RF_PREP_LIMIT = 0x20003140
GPIO_PE_BRR = 0x48001028
GPIO_PF_BRR = 0x48001428
RF_ACTIVE_GATE = 0x20000138
RF_SLOT_COUNT = 0x2000013A
RF_COMPLETION = 0x2000013C
MIRROR_HELPER = 0x20003081
SLOT_COUNT_VALUE = 160
V008_SHA256 = "c242231dce31e96356140924c698d667528103bb9f07639717839e77396fb040"


def load_v008_builder(path: Path):
    spec = importlib.util.spec_from_file_location("h13_rf_v008_builder", path)
    if spec is None or spec.loader is None:
        raise RuntimeError("无法加载v008构建器")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def main() -> int:
    tools = Path(__file__).resolve().parent
    root = Path(__file__).resolve().parents[2]
    v008 = load_v008_builder(tools / "build_external_dmr_rf_prep_v008.py")
    llvm_bin = v008.find_llvm_bin(root)
    source = f"""
    ldr r0, pe_brr
    movs r1, #0x40
    str r1, [r0]
    ldr r0, pf_brr
    movs r1, #0x08
    str r1, [r0]
    ldr r0, completion
    movs r1, #0
    strb r1, [r0]
    ldr r0, slot_count
    movs r1, #{SLOT_COUNT_VALUE}
    strh r1, [r0]
    ldr r0, active_gate
    movs r1, #1
    strb r1, [r0]
    ldr r3, mirror_helper
    bx r3
    nop
pe_brr: .word {GPIO_PE_BRR}
pf_brr: .word {GPIO_PF_BRR}
completion: .word {RF_COMPLETION}
slot_count: .word {RF_SLOT_COUNT}
active_gate: .word {RF_ACTIVE_GATE}
mirror_helper: .word {MIRROR_HELPER}
"""
    blob, disassembly = v008.assemble(source, RF_PREP, llvm_bin)
    if RF_PREP + len(blob) > RF_PREP_LIMIT:
        raise RuntimeError(f"RF准备桩越界：{len(blob)}")
    digest = hashlib.sha256(blob).hexdigest()
    if digest == V008_SHA256:
        raise RuntimeError("v009不得与v008同哈希")
    lowered = disassembly.lower()
    if re.search(r"\bblx?\b", lowered):
        raise RuntimeError("链上准备桩禁止调用")
    if "strb" not in lowered or "strh" not in lowered:
        raise RuntimeError("缺少strb/strh时隙门写")
    literals = struct.unpack_from("<6I", blob, len(blob) - 24)
    if literals != (GPIO_PE_BRR, GPIO_PF_BRR, RF_COMPLETION,
                    RF_SLOT_COUNT, RF_ACTIVE_GATE, MIRROR_HELPER):
        raise RuntimeError("RF准备桩literal表不符")

    binary_name = "h13_mcu_external_dmr_rf_prep_v009.bin"
    (tools / binary_name).write_bytes(blob)
    lines = [
        "variant=external_dmr_slot_gate_rf_prep_v009_m0",
        f"rf_prep_address=0x{RF_PREP:08x} len={len(blob)} sha256={digest}",
        "rf_prepare_sequence=PE6_low,PF3_low,completion=0,count=160,strb_gate,mirror_helper",
        "historical_difference=v008_count16_to_v009_count160_only",
        "",
        "[rf_prep]",
        *disassembly.strip().splitlines(),
        "",
        "rf_prep_words=" + ",".join(
            f"0x{x:08x}" for x in struct.unpack(f"<{len(blob)//4}I", blob)),
    ]
    (tools / "h13_mcu_external_dmr_rf_prep_v009_disasm.txt").write_text(
        "\n".join(lines) + "\n", encoding="ascii")
    print(f"{binary_name} len={len(blob)} sha256={digest}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
