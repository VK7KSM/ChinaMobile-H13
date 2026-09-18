package net.elfradio.h13dmrtx;

import java.util.List;

/** 离线核验：人声素材在各格式下的包数、节拍与总时长。 */
public final class SpeechMaterialCheck {
    public static void main(String[] args) {
        int[] bodies = {RealtimeRelay.TRIPLE_SOS_BYTES,
                        RealtimeRelay.SPEECH_AZ09_BYTES};
        String[] names = {"三遍摩尔斯", "人声字母数字"};

        for (int b = 0; b < bodies.length; b++) {
            System.out.printf("%s：正文 %d 字节，%d 个语音帧%n",
                    names[b], bodies[b],
                    bodies[b] / TxPlan.AMBE_BYTES_PER_FRAME);
            for (DmrProtocol.VoiceFormat f : DmrProtocol.VoiceFormat.values()) {
                int units = DmrTxController.expectedUnitsFor(bodies[b], f);
                long interval = TxPlan.unitIntervalMs(f);
                double total = units * interval / 1000.0;
                String err = DmrTxController.voiceFormatConsistencyError(
                        f, units, bodies[b]);
                System.out.printf("  %-18s %4d 包 × %2d 毫秒 = %5.2f 秒  自洽=%s%s%n",
                        f, units, interval, total,
                        err == null ? "是" : "否",
                        err == null ? "" : "  → " + err);
                if (err != null) {
                    throw new AssertionError(names[b] + " " + f + " 不自洽");
                }
                if (total > 30.0) {
                    throw new AssertionError(names[b] + " " + f
                            + " 时长 " + total + " 秒超过 30 秒发射上限");
                }
            }
            // 切分核对
            byte[] body = new byte[bodies[b]];
            for (DmrProtocol.VoiceFormat f : DmrProtocol.VoiceFormat.values()) {
                List<byte[]> units = TxPlan.sliceUnits(f, body);
                if (units.size() != DmrTxController.expectedUnitsFor(bodies[b], f)) {
                    throw new AssertionError("切分包数与期望不符");
                }
            }
            System.out.println();
        }
        System.out.println("全部格式自洽，切分包数一致，时长均在 30 秒发射上限内。");
    }
}
