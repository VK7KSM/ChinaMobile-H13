package net.elfradio.h13dmrtx;

/**
 * 离线核验：各语音格式下与包数、长度、节拍相关的量是否自洽。
 *
 * 针对的历史教训是：格式贯通后仍有校验常量散落各处未跟随，这类常量不影响
 * 正常构造，只在校验时触发，因此构造与写出的回放覆盖不到。本检查直接调用
 * 控制器的一致性自检，把这类问题拉到上机之前。
 */
public final class FormatConsistencyCheck {

    public static void main(String[] args) {
        boolean allOk = true;

        for (DmrProtocol.VoiceFormat format : DmrProtocol.VoiceFormat.values()) {
            int expected = DmrTxController.expectedTripleSosUnits(format);

            // 正例：中继包数与期望一致
            String err = DmrTxController.voiceFormatConsistencyError(
                    format, expected);
            boolean ok = err == null;
            System.out.printf("%-18s 期望%3d包 单包%2d字节 节拍%2d毫秒 自洽=%s%s%n",
                    format, expected, TxPlan.unitBytes(format),
                    TxPlan.unitIntervalMs(format), ok ? "是" : "否",
                    ok ? "" : "  → " + err);
            if (!ok) {
                allOk = false;
            }

            // 反例：故意传入历史格式的包数，必须被检出
            String injected = DmrTxController.voiceFormatConsistencyError(
                    format, RealtimeRelay.TRIPLE_SOS_UNITS);
            boolean shouldDetect =
                    expected != RealtimeRelay.TRIPLE_SOS_UNITS;
            if (shouldDetect && injected == null) {
                System.out.println("  故障注入未被检出：包数错配应当报错");
                allOk = false;
            } else if (shouldDetect) {
                System.out.println("  故障注入已检出：" + injected);
            }
        }

        // 与中继实际构造的包数交叉核对
        byte[] runtime14 = new byte[14];
        byte[] body = new byte[RealtimeRelay.TRIPLE_SOS_BYTES];
        System.out.println();
        for (DmrProtocol.VoiceFormat format : DmrProtocol.VoiceFormat.values()) {
            RealtimeRelay relay = new RealtimeRelay(runtime14, body,
                    "consistency_fixture", true,
                    RealtimeRelay.EXTERNAL_SOURCE_TRIGGER_UNITS, false, true,
                    format);
            int actual = relay.maximumUnits();
            int expected = DmrTxController.expectedTripleSosUnits(format);
            boolean match = actual == expected;
            System.out.printf("%-18s 中继实际构造%3d包 控制器期望%3d包 一致=%s%n",
                    format, actual, expected, match ? "是" : "否");
            if (!match) {
                allOk = false;
            }
        }

        System.out.println();
        if (!allOk) {
            throw new AssertionError("格式一致性核验未通过");
        }
        System.out.println("全部格式自洽，故障注入均被检出，中继与控制器包数一致。");
    }
}
