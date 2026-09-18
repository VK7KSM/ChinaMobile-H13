package net.elfradio.h13dmrtx;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 回放检查：用真实的供数控制流逐包取出请求，核对换格式后实际写出的序列。
 *
 * 这一步针对的是历史教训——v0.78 的单元测试覆盖了辅助类，却没有覆盖真正
 * 串起来执行的控制路径，导致缺陷直到上机才暴露。本检查直接驱动 RealtimeRelay
 * 的主动节拍写出入口，取出每一包线上字节并逐项核对。
 */
public final class RelayFormatReplay {

    public static void main(String[] args) {
        byte[] runtime14 = new byte[14];
        for (int i = 0; i < 14; i++) {
            runtime14[i] = (byte) (i + 1);
        }
        byte[] body = new byte[2808];
        for (int i = 0; i < body.length; i++) {
            body[i] = (byte) (i * 31 + 7);
        }

        for (DmrProtocol.VoiceFormat format : DmrProtocol.VoiceFormat.values()) {
            replay(format, runtime14, body);
        }
    }

    private static void replay(DmrProtocol.VoiceFormat format,
            byte[] runtime14, byte[] body) {
        RealtimeRelay relay = new RealtimeRelay(runtime14, body,
                "replay_fixture", true,
                RealtimeRelay.EXTERNAL_SOURCE_TRIGGER_UNITS, false, true,
                format);

        List<byte[]> written = new ArrayList<>();
        while (written.size() < relay.maximumUnits()) {
            byte[] request = relay.takeActivePacedRequest44();
            written.add(request);
            relay.completeActivePacedWriteAfterTransportFlush();
        }

        int unitSize = relay.unitBytes();
        int headerLen = (format == DmrProtocol.VoiceFormat.DIGC_VOICE_BURST)
                ? 3 : 2;
        byte[] rebuilt = new byte[body.length];
        int cursor = 0;
        boolean lengthOk = true;
        boolean headerOk = true;

        for (byte[] request : written) {
            int declared = ((request[3] & 0xff) << 8) | (request[4] & 0xff);
            if (declared != headerLen + unitSize) {
                lengthOk = false;
            }
            if ((request[0] & 0xff) != 0x84 || (request[1] & 0xff) != 0xa9
                    || (request[2] & 0xff) != 0x61) {
                headerOk = false;
            }
            System.arraycopy(request, 6 + headerLen, rebuilt, cursor, unitSize);
            cursor += unitSize;
        }

        boolean bodyOk = Arrays.equals(rebuilt, body);
        byte[] first = written.get(0);

        System.out.printf("%-18s 写出%3d包 单包%2d字节 包类型%#04x 首包前%d字节=%s%n",
                format, written.size(), first.length, first[5] & 0xff,
                headerLen + 2,
                bytesToHex(Arrays.copyOfRange(first, 5, 5 + headerLen + 1)));
        System.out.printf("%-18s 信封头正确=%s 长度字段正确=%s 正文回拼一致=%s 节拍=%d毫秒%n%n",
                "", headerOk ? "是" : "否", lengthOk ? "是" : "否",
                bodyOk ? "是" : "否", relay.unitIntervalMs());

        if (!headerOk || !lengthOk || !bodyOk) {
            throw new AssertionError("格式 " + format + " 回放核对未通过");
        }
    }

    private static String bytesToHex(byte[] data) {
        StringBuilder sb = new StringBuilder();
        for (byte b : data) {
            sb.append(String.format("%02x ", b));
        }
        return sb.toString().trim();
    }
}
