from pathlib import Path
import argparse
import base64
import hashlib
import re
import subprocess

parser = argparse.ArgumentParser()
parser.add_argument("--step6", action="store_true")
args = parser.parse_args()

tools = Path(__file__).resolve().parent
bin_path = tools / "h13_mcu_frame_hpi_adapter.bin"
elf_path = tools / "h13_mcu_frame_hpi_adapter.elf"
probe = Path(
    r"C:/Dev/H13_D22/research/h13_interphone_probe/app/src/main/java/"
    r"net/elfradio/h13interphoneprobe/InterphoneProbe.java"
)

raw = bin_path.read_bytes()
b64 = base64.b64encode(raw).decode("ascii")
sha = hashlib.sha256(raw).hexdigest()
print("len", len(raw), "sha", sha)

nm = subprocess.check_output(
    [r"C:\Program Files\LLVM\bin\llvm-nm.exe", "-n", str(elf_path)], text=True
)
irq = arm = tick = thread = None
for line in nm.splitlines():
    if line.endswith("h13_frame_hpi_usart1_irq"):
        irq = int(line.split()[0], 16) | 1
    if line.endswith("h13_frame_hpi_arm_systick"):
        arm = int(line.split()[0], 16) | 1
    if line.endswith("h13_frame_hpi_systick"):
        tick = int(line.split()[0], 16) | 1
    if line.endswith("h13_frame_hpi_thread"):
        thread = int(line.split()[0], 16) | 1
print("irq", hex(irq), "arm", hex(arm), "tick", hex(tick),
      "thread", hex(thread))

t = probe.read_text(encoding="utf-8")
if args.step6:
    t = re.sub(
        r"static final int STEP6_FRAME_ADAPTER_CODE_LENGTH = \d+;",
        f"static final int STEP6_FRAME_ADAPTER_CODE_LENGTH = {len(raw)};",
        t,
    )
    t = re.sub(
        r'static final String STEP6_FRAME_ADAPTER_SHA256 =\s*\n\s*"[0-9a-f]+"',
        f'static final String STEP6_FRAME_ADAPTER_SHA256 =\n            "{sha}"',
        t,
    )
    t = re.sub(
        r"static final int STEP6_FRAME_ADAPTER_ARM_ENTRY = 0x[0-9a-fA-F]+;",
        f"static final int STEP6_FRAME_ADAPTER_ARM_ENTRY = 0x{arm:x};",
        t,
    )
    base64_name = "STEP6_FRAME_ADAPTER_BASE64"
else:
    t = re.sub(
        r"static final int FRAME_ADAPTER_CODE_ADDRESS = 0x[0-9a-fA-F]+;",
        "static final int FRAME_ADAPTER_CODE_ADDRESS = 0x20001bd4;",
        t,
    )
    t = re.sub(
        r"static final int FRAME_ADAPTER_CODE_LENGTH = \d+;",
        f"static final int FRAME_ADAPTER_CODE_LENGTH = {len(raw)};",
        t,
    )
    t = re.sub(
        r'static final String FRAME_ADAPTER_SHA256 =\s*\n\s*"[0-9a-f]+"',
        f'static final String FRAME_ADAPTER_SHA256 =\n            "{sha}"',
        t,
    )
    t = re.sub(
        r"static final int FRAME_ADAPTER_USART1_ENTRY = 0x[0-9a-fA-F]+;",
        f"static final int FRAME_ADAPTER_USART1_ENTRY = 0x{irq:x};",
        t,
    )
    t = re.sub(
        r"static final int FRAME_ADAPTER_SYSTICK_ENTRY = 0x[0-9a-fA-F]+;",
        f"static final int FRAME_ADAPTER_SYSTICK_ENTRY = 0x{tick:x};",
        t,
    )
    t = re.sub(
        r"static final int FRAME_ADAPTER_ARM_ENTRY = 0x[0-9a-fA-F]+;",
        f"static final int FRAME_ADAPTER_ARM_ENTRY = 0x{arm:x};",
        t,
    )
    t = re.sub(
        r"static final int FRAME_ADAPTER_THREAD_ENTRY = 0x[0-9a-fA-F]+;",
        f"static final int FRAME_ADAPTER_THREAD_ENTRY = 0x{thread:x};",
        t,
    )
    t = re.sub(
        r"static final int FRAME_ADAPTER_THREAD_CODE = 0x[0-9a-fA-F]+;",
        f"static final int FRAME_ADAPTER_THREAD_CODE = 0x{thread & ~1:x};",
        t,
    )
    base64_name = "FRAME_ADAPTER_BASE64"

start = t.find(f"private static final String {base64_name} =")
if start < 0:
    raise SystemExit("base64 missing")
j = t.find('"', start)
end = None
while j < len(t):
    if t[j] == '"':
        j += 1
        while j < len(t) and t[j] != '"':
            j += 2 if t[j] == "\\" else 1
        j += 1
        continue
    if t[j] == ";":
        end = j + 1
        break
    j += 1
if end is None:
    raise SystemExit("base64 end missing")
chunks = [b64[i : i + 80] for i in range(0, len(b64), 80)]
body = f"private static final String {base64_name} =\n"
for i, c in enumerate(chunks):
    body += '            "' + c + '"' + ("" if i == len(chunks) - 1 else " +") + "\n"
body = body.rstrip() + ";"
t = t[:start] + body + t[end:]
probe.write_text(t, encoding="utf-8")

if not args.step6:
    # host evidence hashes / lengths for truncated mode
    host = tools / "h13_probe_session.ps1"
    ht = host.read_text(encoding="utf-8")
    ht = re.sub(
        r"OneH 'h13_frame_adapter_code_\*\.bin' \d+",
        f"OneH 'h13_frame_adapter_code_*.bin' {len(raw)}",
        ht,
    )
    # replace known old adapter hashes used in PASS checks for truncated mode
    for old in [
        "2832d509c40e3d331c94e518b2385a9d8ad09e025999963d975c485848e84109",
        "dc426337f293bb0f6475c945629d9feab558f665b72bfe6daabe2d6347184e6d",
        "2832D509C40E3D331C94E518B2385A9D8AD09E025999963D975C485848E84109",
        "DC426337F293BB0F6475C945629D9FEAB558F665B72BFE6DAABE2D6347184E6D",
    ]:
        ht = ht.replace(old, sha if old.islower() else sha.upper())
    ht = ht.replace('adapter_code_bytes"] -ne "1212"', f'adapter_code_bytes"] -ne "{len(raw)}"')
    ht = ht.replace('adapter_code_bytes"] -ne "1248"', f'adapter_code_bytes"] -ne "{len(raw)}"')
    host.write_text(ht, encoding="utf-8")
print("embed ok")
