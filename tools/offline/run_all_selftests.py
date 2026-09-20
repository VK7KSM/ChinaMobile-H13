#!/usr/bin/env python3
"""一条命令跑完所有模型自检，作为回归测试台的入口。

2.9.4 第五块要的回归测试台，第一步是让已固化的规则能被一次性验证。
九个模型各自有自检，散着跑容易漏；这里统一入口并汇总。

被纳入的模型及其验收用例数（截至 2026-09-21）：

    arbitration_model        单工仲裁，10 项
    jitter_buffer_model      抖动缓冲，7 项
    talkgroup_model          TG 订阅与 Last Heard，11 项
    power_calibration        功率标定，7 项
    radio_service_contract   服务接口契约，10 项

用法：
    python run_all_selftests.py
"""
from __future__ import annotations

import subprocess
import sys
from pathlib import Path

MODELS = [
    ("单工仲裁", "arbitration_model.py"),
    ("抖动缓冲", "jitter_buffer_model.py"),
    ("TG 与 Last Heard", "talkgroup_model.py"),
    ("功率标定", "power_calibration.py"),
    ("服务接口契约", "radio_service_contract.py"),
]

here = Path(__file__).parent


def main() -> int:
    failed = []
    print("%-20s %-6s %s" % ("模型", "结果", "用例"))
    print("-" * 46)
    for name, script in MODELS:
        proc = subprocess.run([sys.executable, str(here / script)],
                              capture_output=True, text=True, cwd=here)
        passed = proc.stdout.count("通过   ")
        bad = proc.stdout.count("不通过 ")
        ok = proc.returncode == 0 and bad == 0
        print("%-20s %-6s 通过 %d%s"
              % (name, "正常" if ok else "失败", passed,
                 "，不通过 %d" % bad if bad else ""))
        if not ok:
            failed.append((name, proc.stdout, proc.stderr))
    print("-" * 46)
    if failed:
        print("失败 %d 个模型：" % len(failed))
        for name, out, err in failed:
            print("\n== %s" % name)
            for line in out.splitlines():
                if "不通过" in line:
                    print("   " + line.strip())
            if err.strip():
                print("   stderr: " + err.strip()[:200])
        return 1
    print("全部通过")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
