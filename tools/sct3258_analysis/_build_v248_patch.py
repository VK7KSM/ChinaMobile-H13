from pathlib import Path
import hashlib
import struct

frozen = Path(__file__).with_name("h13_mcu_frame_hpi_adapter_v245_frozen.bin")
b = bytearray(frozen.read_bytes())
assert len(b) == 1212
assert (
    hashlib.sha256(b).hexdigest().upper()
    == "2832D509C40E3D331C94E518B2385A9D8AD09E025999963D975C485848E84109"
)
base = 0x20001D00


def enc_bl(frm, to):
    offset = to - (frm + 4)
    imm = offset // 2
    imm_u = imm & ((1 << 24) - 1)
    S = (imm_u >> 23) & 1
    I1 = (imm_u >> 22) & 1
    I2 = (imm_u >> 21) & 1
    imm10 = (imm_u >> 11) & 0x3FF
    imm11 = imm_u & 0x7FF
    J1 = (not (I1 ^ S)) & 1
    J2 = (not (I2 ^ S)) & 1
    hw1 = 0xF000 | (S << 10) | imm10
    hw2 = 0xD000 | (J1 << 13) | (1 << 12) | (J2 << 11) | imm11
    return struct.pack("<HH", hw1, hw2)


def enc_b(frm, to):
    offset = to - (frm + 4)
    imm = offset // 2
    assert -1024 <= imm <= 1023, imm
    return struct.pack("<H", 0xE000 | (imm & 0x7FF))


def patch_ldr_pc(buf, pos, rt, target_addr, insn_addr):
    pc = (insn_addr + 4) & ~3
    diff = target_addr - pc
    assert 0 <= diff <= 0x3FC and diff % 4 == 0, (
        hex(diff),
        hex(insn_addr),
        hex(target_addr),
    )
    half = 0x4800 | (rt << 8) | (diff // 4)
    struct.pack_into("<H", buf, pos, half)


start = base + len(b)

# Gate A: if rx_len >= 64 then pend PendSV
codeA = bytearray()
codeA += struct.pack("<H", 0x6A40)  # ldr r0,[r4,#0x24]
codeA += struct.pack("<H", 0x2840)  # cmp r0,#64
codeA += struct.pack("<H", 0xDB03)  # blt +3
l0 = len(codeA)
codeA += struct.pack("<H", 0)
l1 = len(codeA)
codeA += struct.pack("<H", 0)
codeA += struct.pack("<H", 0x6001)  # str r1,[r0]
codeA += struct.pack("<H", 0x4770)  # bx lr
while (start + len(codeA)) & 3:
    codeA += b"\x00"
w = len(codeA)
codeA += struct.pack("<II", 0xE000ED04, 0x10000000)
patch_ldr_pc(codeA, l0, 0, start + w, start + l0)
patch_ldr_pc(codeA, l1, 1, start + w + 4, start + l1)

gateA = start
gateB = start + len(codeA)

# Gate B: if allow!=2 set RECEIVING; always go to accept_byte
codeB = bytearray()
codeB += struct.pack("<H", 0x69F0)  # ldr r0,[r6,#0x1c]
codeB += struct.pack("<H", 0x2802)  # cmp r0,#2
codeB += struct.pack("<H", 0xD002)  # beq +2
codeB += struct.pack("<H", 0x2001)  # movs r0,#1
codeB += struct.pack("<H", 0x60F0)  # str r0,[r6,#0xc]
bp = gateB + len(codeB)
codeB += enc_b(bp, 0x20001D88)

assert b[0x20002096 - base : 0x20002096 - base + 2] == bytes.fromhex("02d0")
b[0x20002096 - base : 0x20002096 - base + 2] = bytes.fromhex("00bf")

assert b[0x20001EDC - base : 0x20001EDC - base + 8] == bytes.fromhex(
    "aa48ab49016054e0"
)
b[0x20001EDC - base : 0x20001EDC - base + 8] = (
    enc_bl(0x20001EDC, gateA)
    + enc_b(0x20001EE0, 0x20001F8E)
    + struct.pack("<H", 0xBF00)
)

assert b[0x20001D84 - base : 0x20001D84 - base + 4] == bytes.fromhex("0120f060")
b[0x20001D84 - base : 0x20001D84 - base + 4] = enc_bl(0x20001D84, gateB)

b.extend(codeA)
b.extend(codeB)
if len(b) % 4:
    b.extend(b"\x00" * (4 - len(b) % 4))
assert len(b) <= 0x500

sha = hashlib.sha256(b).hexdigest().upper()
print("len", len(b))
print("sha", sha)
print("gateA", hex(gateA), "gateB", hex(gateB))
print("A", bytes(codeA).hex())
print("B", bytes(codeB).hex())

out = Path(__file__).with_name("h13_mcu_frame_hpi_adapter.bin")
out.write_bytes(b)
Path(__file__).with_name("h13_mcu_frame_hpi_adapter_v248.bin").write_bytes(b)

# base64 for Java
import base64

b64 = base64.b64encode(b).decode("ascii")
Path(__file__).with_name("h13_mcu_frame_hpi_adapter.b64.txt").write_text(
    b64 + "\n", encoding="ascii"
)
print("b64_len", len(b64))
print("ok")
