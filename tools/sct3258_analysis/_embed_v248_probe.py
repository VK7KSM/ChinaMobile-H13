from pathlib import Path
import base64
import hashlib

probe = Path(
    r"C:/Dev/H13_D22/research/h13_interphone_probe/app/src/main/java/"
    r"net/elfradio/h13interphoneprobe/InterphoneProbe.java"
)
t = probe.read_text(encoding="utf-8")
b64 = Path(
    r"C:/Dev/H13_D22/research/h13_radio/tools/h13_mcu_frame_hpi_adapter.b64.txt"
).read_text(encoding="ascii").strip()
sha = "dc426337f293bb0f6475c945629d9feab558f665b72bfe6daabe2d6347184e6d"
raw = base64.b64decode(b64)
assert len(raw) == 1248
assert hashlib.sha256(raw).hexdigest() == sha

t = t.replace(
    "static final int FRAME_ADAPTER_CODE_LENGTH = 1212;",
    "static final int FRAME_ADAPTER_CODE_LENGTH = 1248;",
)
t = t.replace(
    '"2832d509c40e3d331c94e518b2385a9d8ad09e025999963d975c485848e84109"',
    '"' + sha + '"',
)

start = t.find("private static final String FRAME_ADAPTER_BASE64 =")
if start < 0:
    raise SystemExit("base64 not found")
q = t.find('"', start)
j = q
end = None
while j < len(t):
    if t[j] == '"':
        j += 1
        while j < len(t) and t[j] != '"':
            if t[j] == "\\":
                j += 2
            else:
                j += 1
        j += 1
        continue
    if t[j] == ";":
        end = j + 1
        break
    j += 1
if end is None:
    raise SystemExit("base64 end not found")

chunks = [b64[i : i + 80] for i in range(0, len(b64), 80)]
body = "private static final String FRAME_ADAPTER_BASE64 =\n"
for idx, c in enumerate(chunks):
    sep = "" if idx == len(chunks) - 1 else " +"
    body += '            "' + c + '"' + sep + "\n"
body = body.rstrip() + ";"
t = t[:start] + body + t[end:]

old = (
    "&& liveUsart == FRAME_ADAPTER_ORIGINAL_USART1\n"
    "                        && liveSystick == FRAME_ADAPTER_SYSTICK_ENTRY"
)
new = (
    "&& (liveUsart == FRAME_ADAPTER_ORIGINAL_USART1\n"
    "                                || liveUsart == FRAME_ADAPTER_USART1_ENTRY)\n"
    "                        && liveSystick == FRAME_ADAPTER_SYSTICK_ENTRY"
)
if old not in t:
    raise SystemExit("liveUsart pattern missing")
t = t.replace(old, new, 1)

# Also fix host expected range bytes comment in success for v2.48
t = t.replace(
    "失败：v2.47截断64字节PCM+PendSV最短HPI早写证据门未完成",
    "失败：v2.48截断64字节PCM+USART接收+PendSV最短HPI证据门未完成",
)
t = t.replace(
    "v2.47帧级适配器64字节截断PCM+PendSV最短HPI早写",
    "v2.48帧级适配器64字节截断PCM+USART接收+PendSV最短HPI",
)
t = t.replace(
    "allow=2粘性最短HPI；武装后短settle立即写64字节截断PCM",
    "allow=2装USART；rx>=64后PendSV最短HPI；写出64字节截断PCM",
)

probe.write_text(t, encoding="utf-8")
print("probe updated ok", len(raw), sha)
