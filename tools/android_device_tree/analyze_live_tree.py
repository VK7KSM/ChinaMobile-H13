#!/usr/bin/env python3
"""Decode the H13 live device-tree and pinctrl snapshots without device access."""

from __future__ import annotations

import argparse
import re
import struct
from pathlib import Path


GPIO_PROPERTIES = {
    "gpios",
    "gpio_audio_switch",
    "gpio_dmr_switch",
    "gpio_kpd_en",
    "gpio_otg_en",
    "gpio_pa_en",
    "gpio_pa_switch",
    "gpio_ptt_d",
    "gpio_usbid_ctrl",
    "gpio_wake",
}

PHANDLE_LIST_PROPERTIES = {
    "asoc-codec",
    "asoc-cpu",
    "asoc-platform",
}


def u32_cells(data: bytes) -> tuple[int, ...]:
    if len(data) % 4:
        raise ValueError(f"property length {len(data)} is not a multiple of four")
    return struct.unpack(f">{len(data) // 4}I", data)


def string_list(data: bytes) -> list[str] | None:
    if not data or data[-1] != 0:
        return None
    parts = data.rstrip(b"\0").split(b"\0")
    try:
        values = [part.decode("ascii") for part in parts]
    except UnicodeDecodeError:
        return None
    if all(value and all(0x20 <= ord(char) < 0x7F for char in value) for value in values):
        return values
    return None


def property_value(path: Path, phandles: dict[int, str]) -> str:
    data = path.read_bytes()
    if not data:
        return "<boolean>"
    strings = string_list(data)
    if strings is not None:
        return ", ".join(repr(value) for value in strings)
    if len(data) % 4 == 0:
        cells = u32_cells(data)
        rendered = " ".join(f"0x{cell:08x}" for cell in cells)
        if path.name in GPIO_PROPERTIES and len(cells) >= 2:
            controller = phandles.get(cells[0], f"phandle 0x{cells[0]:x}")
            flags = cells[2] if len(cells) >= 3 else 0
            rendered += f"  ({controller}, GPIO {cells[1]}, flags 0x{flags:x})"
        elif len(cells) == 1 and cells[0] in phandles:
            rendered += f"  ({phandles[cells[0]]})"
        elif path.name.startswith("pinctrl-") or path.name in PHANDLE_LIST_PROPERTIES:
            resolved = [
                f"0x{cell:x}={phandles[cell]}"
                for cell in cells
                if cell in phandles
            ]
            if resolved:
                rendered += "  (" + ", ".join(resolved) + ")"
        return rendered
    return data.hex(" ")


def index_phandles(root: Path) -> dict[int, str]:
    result: dict[int, str] = {}
    for name in ("phandle", "linux,phandle"):
        for path in root.rglob(name):
            data = path.read_bytes()
            if len(data) != 4:
                continue
            value = u32_cells(data)[0]
            result[value] = path.parent.relative_to(root).as_posix()
    return result


def dump_node(title: str, node: Path, phandles: dict[int, str], recursive: bool = False) -> None:
    print(f"\n{title}: {node}")
    if not node.is_dir():
        print("  <not captured>")
        return
    paths = node.rglob("*") if recursive else node.iterdir()
    files = sorted((path for path in paths if path.is_file()), key=lambda item: item.as_posix())
    for path in files:
        name = path.relative_to(node).as_posix()
        print(f"  {name:<40} {property_value(path, phandles)}")


def print_pinmux(path: Path, pins: list[int]) -> None:
    print(f"\nCurrent TLMM pinmux snapshot: {path}")
    if not path.is_file():
        print("  <not captured>")
        return
    wanted = set(pins)
    pattern = re.compile(r"^pin (\d+) \(GPIO_\d+\): (.*)$")
    for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
        match = pattern.match(line)
        if match and int(match.group(1)) in wanted:
            print(f"  GPIO {int(match.group(1)):3d}: {match.group(2)}")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--root",
        type=Path,
        default=Path(__file__).with_name("device_tree_full"),
        help="root of the pulled /sys/firmware/devicetree/base tree",
    )
    parser.add_argument(
        "--selected",
        type=Path,
        default=Path(__file__).with_name("device_tree"),
        help="directory containing separately pulled critical nodes",
    )
    args = parser.parse_args()

    phandles = index_phandles(args.root)
    print(f"Indexed {len(phandles)} phandles from {args.root}")
    dump_node(
        "BOPTT board GPIOs",
        args.selected / "boptt_sysfs",
        phandles,
    )
    dump_node(
        "DMR module detector",
        args.root
        / "soc"
        / "qcom,spmi@200f000"
        / "qcom,pm8909@0"
        / "boptt,dmr_module_id",
        phandles,
    )
    dump_node(
        "Primary AUXPCM",
        args.selected / "qcom,msm-pri-auxpcm",
        phandles,
    )
    dump_node(
        "DMR UART",
        args.selected / "uart@78b0000",
        phandles,
    )
    dump_node(
        "Sound card",
        args.selected / "sound",
        phandles,
        recursive=True,
    )
    dump_node(
        "GPIO keys",
        args.root / "soc" / "gpio_keys",
        phandles,
        recursive=True,
    )
    print_pinmux(
        Path(__file__).with_name("pinctrl") / "1000000.pinctrl" / "pinmux-pins",
        [16, 20, 21, 23, 32, 59, 60, 61, 62, 63, 64, 69, 90, 91, 92, 93, 94, 97, 98, 111, 112],
    )


if __name__ == "__main__":
    main()
