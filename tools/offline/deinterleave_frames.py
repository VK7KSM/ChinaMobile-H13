"""把交织后的 9 字节 DMR 语音帧还原为交织前排列（C0,C1,C2,C3 顺序打包）。
映射表取自 research/h13_radio/tools/chan_d_to_wav.c。同时做逆变换核对。"""
import sys
rW=[0,1,0,1,0,1,0,1,0,1,0,1,0,1,0,1,0,1,0,1,0,1,0,2,0,2,0,2,0,2,0,2,0,2,0,2]
rX=[23,10,22,9,21,8,20,7,19,6,18,5,17,4,16,3,15,2,14,1,13,0,12,10,11,9,10,8,9,7,8,6,7,5,6,4]
rY=[0,2,0,2,0,2,0,2,0,3,0,3,1,3,1,3,1,3,1,3,1,3,1,3,1,3,1,3,1,3,1,3,1,3,1,3]
rZ=[5,3,4,2,3,1,2,0,1,13,0,12,22,11,21,10,20,9,19,8,18,7,17,6,16,5,15,4,14,3,13,2,12,1,11,0]
# 交织位 -> (row,col)
pos=[]
for t in range(36):
    pos.append((rW[t],rX[t])); pos.append((rY[t],rZ[t]))
assert len(set(pos))==72, "映射表不是双射"
rows={r:sorted(c for (rr,c) in pos if rr==r) for r in range(4)}
order=[(r,c) for r in range(4) for c in rows[r]]   # 交织前排列：C0,C1,C2,C3
print("各行位数:", {r:len(rows[r]) for r in rows})
inv={rc:i for i,rc in enumerate(order)}          # (row,col) -> 交织前位序
def bits(b9): return [(b9[i>>3]>>(7-(i&7)))&1 for i in range(72)]
def pack(bl):
    out=bytearray(9)
    for i,v in enumerate(bl):
        if v: out[i>>3]|=1<<(7-(i&7))
    return bytes(out)
def deint(b9):
    bl=bits(b9); o=[0]*72
    for i in range(72): o[inv[pos[i]]]=bl[i]
    return pack(o)
def reint(b9):
    bl=bits(b9); o=[0]*72
    for i in range(72): o[i]=bl[inv[pos[i]]]
    return pack(o)
src=open(sys.argv[1],"rb").read(); assert len(src)%9==0
out=b"".join(deint(src[i:i+9]) for i in range(0,len(src),9))
back=b"".join(reint(out[i:i+9]) for i in range(0,len(out),9))
assert back==src, "逆变换未还原"
open(sys.argv[2],"wb").write(out)
print(f"输入 {len(src)} 字节 -> 交织前排列 {len(out)} 字节；逆变换还原一致")
print("首帧 交织后:", src[:9].hex(), " 交织前:", out[:9].hex())
