# -*- coding: utf-8 -*-
from pathlib import Path
import base64, hashlib, re
java = Path(r"C:\Dev\H13_D22\research\h13_interphone_probe\app\src\main\java\net\elfradio\h13interphoneprobe\InterphoneProbe.java")
binp = Path(r"C:\Dev\H13_D22\research\h13_radio\tools\h13_mcu_frame_hpi_adapter.bin")
b = binp.read_bytes()
sha = hashlib.sha256(b).hexdigest()
b64 = base64.b64encode(b).decode("ascii")
chunks = [b64[i:i+76] for i in range(0, len(b64), 76)]
b64_java = " +\n            ".join('"' + c + '"' for c in chunks)
text = java.read_text(encoding="utf-8")
text2 = text.replace(
    "static final int FRAME_ADAPTER_CODE_LENGTH = 1052;",
    "static final int FRAME_ADAPTER_CODE_LENGTH = 1056;",
)
text2 = text2.replace(
    "static final int FRAME_ADAPTER_ARM_ENTRY = 0x20002013;",
    "static final int FRAME_ADAPTER_ARM_ENTRY = 0x20001fe5;\n"
    "    static final int FRAME_ADAPTER_PENDSV_ENTRY = 0x20001f4d;\n"
    "    static final int FRAME_ADAPTER_PENDSV_VECTOR_ADDRESS = 0x20000038;",
)
text2 = text2.replace(
    "static final int FRAME_ADAPTER_PHASE_HPI_RETURN = 8;",
    "static final int FRAME_ADAPTER_PHASE_HPI_RETURN = 8;\n"
    "    static final int FRAME_ADAPTER_PHASE_PENDSV_PENDING = 9;",
)
text2 = re.sub(
    r"static final String FRAME_ADAPTER_SHA256 =\s*\"[0-9a-fA-F]+\"\s*\.toLowerCase\(Locale\.US\);",
    "static final String FRAME_ADAPTER_SHA256 =\n            \"%s\"\n                    .toLowerCase(Locale.US);" % sha,
    text2,
    count=1,
)
text2 = re.sub(
    r"private static final String FRAME_ADAPTER_BASE64 =\s*\"[^\"]+\"(?:\s*\+\s*\"[^\"]+\")*\s*;",
    "private static final String FRAME_ADAPTER_BASE64 =\n            " + b64_java + ";",
    text2,
    count=1,
    flags=re.S,
)
if "FRAME_ADAPTER_PENDSV_ENTRY" not in text2:
    raise SystemExit("pendsv not inserted")
if sha not in text2:
    raise SystemExit("sha not inserted")
if str(len(b)) not in text2 and "1056" not in text2:
    raise SystemExit("len not updated")
java.write_text(text2, encoding="utf-8")
print("OK", len(b), sha)
