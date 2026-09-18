package net.elfradio.h13dmrtx;

import java.util.Arrays;
import java.util.List;

/** 离线核验：同一段语音帧流在不同格式下的切分等价性。不涉及设备。 */
public final class SliceVerify {
    public static void main(String[] args) {
        byte[] body = new byte[2808];
        for (int i = 0; i < body.length; i++) {
            body[i] = (byte) (i * 31 + 7);
        }
        System.out.println("正文 " + body.length + " 字节，共 "
                + (body.length / 9) + " 个语音帧\n");

        for (DmrProtocol.VoiceFormat f : DmrProtocol.VoiceFormat.values()) {
            int fpu = TxPlan.framesPerUnit(f);
            long iv = TxPlan.unitIntervalMs(f);
            List<byte[]> units = TxPlan.sliceUnits(f, body);
            int payloadBytes = TxPlan.unitBytes(f);
            int off = (f == DmrProtocol.VoiceFormat.DIGC_VOICE_BURST) ? 9 : 8;

            byte[] back = new byte[body.length];
            int p = 0;
            for (byte[] u : units) {
                System.arraycopy(u, off, back, p, payloadBytes);
                p += payloadBytes;
            }
            boolean same = Arrays.equals(back, body);

            System.out.printf(
                "%-18s 每包%d帧 共%3d包 节拍%2dms 总时长%.2f秒 线上单包%d字节 回拼一致=%s%n",
                f, fpu, units.size(), iv, units.size() * iv / 1000.0,
                units.get(0).length, same ? "是" : "否");
        }
    }
}
