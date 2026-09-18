package net.elfradio.h13dmrtx;

/**
 * 节拍回放：核对各格式下逐包的绝对目标时刻与总时长。
 *
 * 检查三件事：首包与末包的时间跨度、相邻包间隔是否恒等于该格式的节拍、
 * 以及不同格式的总时长是否一致。全部为离线计算，不涉及设备。
 */
public final class PacingReplay {

    public static void main(String[] args) {
        long firstFlushAt = 1_000_000L;

        for (DmrProtocol.VoiceFormat format : DmrProtocol.VoiceFormat.values()) {
            int units = 2808 / TxPlan.unitBytes(format);
            long interval = TxPlan.unitIntervalMs(format);

            long previous = firstFlushAt;
            boolean intervalOk = true;
            long lastTarget = firstFlushAt;

            for (int index = 1; index < units; index++) {
                long target = DmrTxController.activePacedTargetAt(
                        firstFlushAt, index, format, units);
                if (target - previous != interval) {
                    intervalOk = false;
                }
                previous = target;
                lastTarget = target;
            }

            long span = lastTarget - firstFlushAt;
            long minNext = DmrTxController.relayMinimumTargetAt(
                    firstFlushAt, format);

            System.out.printf("%-18s %3d包 节拍%2d毫秒 间隔恒定=%s "
                    + "首末跨度%d毫秒(%.2f秒) 最早下一包=+%d毫秒%n",
                    format, units, interval, intervalOk ? "是" : "否",
                    span, span / 1000.0, minNext - firstFlushAt);

            if (!intervalOk || minNext - firstFlushAt != interval) {
                throw new AssertionError("格式 " + format + " 节拍核对未通过");
            }
        }

        // 各格式总时长必须一致：语音内容相同，只是打包粒度不同
        long legacy = span(DmrProtocol.VoiceFormat.LEGACY_CHAN_D36);
        for (DmrProtocol.VoiceFormat f : DmrProtocol.VoiceFormat.values()) {
            long s = span(f);
            if (Math.abs(s - legacy) > 100) {
                throw new AssertionError("格式 " + f + " 总时长偏离历史基线 "
                        + (s - legacy) + " 毫秒");
            }
        }
        System.out.println("\n各格式总时长相互偏差在100毫秒以内，语音内容时长一致。");
    }

    private static long span(DmrProtocol.VoiceFormat format) {
        int units = 2808 / TxPlan.unitBytes(format);
        return (units - 1L) * TxPlan.unitIntervalMs(format);
    }
}
