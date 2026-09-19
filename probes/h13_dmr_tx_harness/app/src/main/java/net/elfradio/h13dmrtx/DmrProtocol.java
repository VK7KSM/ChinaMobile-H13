package net.elfradio.h13dmrtx;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

final class DmrProtocol {
    static final int SETUP_COUNT = 5;
    // 发射调制链配置：原厂外部 DMR 配置链在工作模式之后写五个 codec
    // 页/寄存器。线上格式与旧探针 createAnalogCodecWriteFrame 逐字节相同，
    // 确认帧为 packetType=0x40、正文 {0x17, 0x00}。
    static final int CODEC_COUNT = 5;
    static final int CODEC_PACKET_TYPE = 0x40;
    static final int CODEC_ACK_FIELD = 0x17;
    static final int VLC_COUNT = 5;
    static final int VLC_COUNT_AFTER_FIRST_DATA = VLC_COUNT - 2;
    static final int TERMINATION_COUNT = 1;
    static final int CLEANUP_COUNT = 2;
    static final int DATA36_BYTES = 36;
    static final int DATA36_WIRE_BYTES = 44;

    private static final int[] VLC_BASE_MODES = {0x01, 0x01, 0x00, 0x1f, 0x11};

    private DmrProtocol() {}

    static Session session(int ownId, int calledId, int outputSequence,
            byte[] runtime14) {
        return new Session(ownId, calledId, outputSequence, runtime14);
    }

    /**
     * 从信道配置串里取麦克风增益档位。字段顺序与 AT+DMOSETDIGITALCH
     * 一致，第 14 个字段（下标 13）为该档位，取值 0..2。
     */
    static int micGainPreset(String channel) {
        if (channel == null) {
            throw new IllegalArgumentException("信道配置为空");
        }
        String[] fields = channel.split(",");
        if (fields.length < 14) {
            throw new IllegalArgumentException("信道配置字段不足："
                    + fields.length);
        }
        int preset = Integer.parseInt(fields[13].trim());
        if (preset < 0 || preset >= 3) {
            throw new IllegalArgumentException("麦克风增益档位越界：" + preset);
        }
        return preset;
    }

    /** 单条 codec 页/寄存器写。第五条的值是增益，随信道档位而变。 */
    static byte[] codecWrite(int page, int register, int value) {
        if ((page & ~0xff) != 0 || (register & ~0xff) != 0
                || (value & ~0xff) != 0) {
            throw new IllegalArgumentException("codec页/寄存器/值必须为字节");
        }
        return HpiCodec.frame(CODEC_PACKET_TYPE, new byte[] {
                0x00, (byte) page, (byte) register, (byte) value, 0x00, 0x00
        });
    }

    /** 五条发射 codec 写，顺序与原厂链一致；gain 取自设备增益表。 */
    static byte[] codec(int index, int gain) {
        switch (index) {
        case 0: return codecWrite(1, 0x10, 0x40);
        case 1: return codecWrite(1, 0x3b, 0x11);
        case 2: return codecWrite(0, 0x56, 0xf3);
        case 3: return codecWrite(0, 0x57, 0xba);
        case 4: return codecWrite(0, 0x58, gain);
        default: throw new IndexOutOfBoundsException("codec索引");
        }
    }

    static byte[] data36(byte[] payload) {
        if (payload == null || payload.length != DATA36_BYTES) {
            throw new IllegalArgumentException("data36正文必须为36字节");
        }
        byte[] body = new byte[38];
        body[0] = 0x01;
        body[1] = 0x24;
        System.arraycopy(payload, 0, body, 2, payload.length);
        byte[] result = HpiCodec.paddedFrame(3, body);
        if (result.length != DATA36_WIRE_BYTES) {
            throw new AssertionError("data36信封长度异常");
        }
        return result;
    }

    // ==================== 语音注入格式候选 ====================
    // 历史实现（上面的 data36）使用字段 0x01、36 字节载荷、包类型 3。
    // 2026-09-18 的分析发现该格式与模块自身的行为不一致：
    // 模块上报接收语音时用的是字段 0x01、长度 27、包类型 0x20（读方向、
    // 控制包），27 字节即三个 9 字节语音帧、约 60 毫秒，正是 DMR 一个
    // 语音突发；36 字节是四帧、80 毫秒，属 dPMR 的单位。
    //
    // 下列候选各自只改变一个维度，供逐个验证。任一时刻只允许启用一个，
    // 由 VoiceFormat 选择。历史实现保留且为默认，不改变既有基线。

    static final int VOICE_BURST_BYTES = 27;

    enum VoiceFormat {
        /** 历史实现：字段 0x01、36 字节、包类型 3。保持既有基线。 */
        LEGACY_CHAN_D36,
        /** 候选一：只把载荷改为 27 字节语音突发，包类型仍为 3。 */
        CHAN_D27_TYPE3,
        /** 候选二：27 字节，且包类型改为 0，与接收方向对称。 */
        CHAN_D27_TYPE0,
        /** 候选三：按手册 DMR 规定改用数据帧字段，帧属性位 4 置 1。 */
        DIGC_VOICE_BURST
    }

    /**
     * 按选定格式构造一个语音单元的线上帧。
     *
     * @param payload 语音载荷。历史格式为 36 字节，其余候选为 27 字节。
     */
    static byte[] voiceUnit(VoiceFormat format, byte[] payload) {
        if (format == null || payload == null) {
            throw new IllegalArgumentException("语音单元参数为空");
        }
        switch (format) {
        case LEGACY_CHAN_D36:
            return data36(payload);
        case CHAN_D27_TYPE3:
            return chanD27(payload, 3);
        case CHAN_D27_TYPE0:
            return chanD27(payload, 0);
        case DIGC_VOICE_BURST:
            return digcVoiceBurst(payload);
        default:
            throw new IllegalArgumentException("未知语音格式");
        }
    }

    /** 字段 0x01 加 27 字节载荷，包类型由调用方给出。 */
    private static byte[] chanD27(byte[] payload, int packetType) {
        requireBurst(payload);
        byte[] body = new byte[2 + VOICE_BURST_BYTES];
        body[0] = 0x01;
        body[1] = (byte) VOICE_BURST_BYTES;
        System.arraycopy(payload, 0, body, 2, VOICE_BURST_BYTES);
        return HpiCodec.paddedFrame(packetType, body);
    }

    /**
     * 字段 0x43 数据帧，帧属性位 4 置 1 表示语音突发，包类型 5。
     * 帧属性的低四位按手册仅对数据突发有效，此处保持为零。
     */
    private static byte[] digcVoiceBurst(byte[] payload) {
        requireBurst(payload);
        byte[] body = new byte[3 + VOICE_BURST_BYTES];
        body[0] = 0x43;
        body[1] = 0x10;
        body[2] = (byte) VOICE_BURST_BYTES;
        System.arraycopy(payload, 0, body, 3, VOICE_BURST_BYTES);
        return HpiCodec.paddedFrame(5, body);
    }

    private static void requireBurst(byte[] payload) {
        if (payload.length != VOICE_BURST_BYTES) {
            throw new IllegalArgumentException(
                    "语音突发载荷必须为27字节，实际" + payload.length);
        }
    }

    static boolean exactStatusAck(byte[] raw, int packetType, int field) {
        List<HpiCodec.WireFrame> frames = HpiCodec.parseComplete(raw);
        if (frames == null || frames.size() != 1) {
            return false;
        }
        HpiCodec.WireFrame frame = frames.get(0);
        return frame.packetType == packetType
                && Arrays.equals(frame.payload,
                new byte[] {(byte) field, 0x00});
    }

    /**
     * 探针桥内收尾：状态确认可以不是唯一帧，后面允许粘连完整帧。
     * 不要求整段缓冲区都能解析到底。
     */
    static boolean containsControlStatusAck(byte[] raw, int packetType,
            int field) {
        if (raw == null || raw.length < 8) {
            return false;
        }
        int offset = 0;
        while (offset + 6 <= raw.length) {
            if ((raw[offset] & 0xff) != HpiCodec.SYNC0
                    || (raw[offset + 1] & 0xff) != HpiCodec.SYNC1
                    || (raw[offset + 2] & 0xff) != HpiCodec.COMMAND) {
                return false;
            }
            int payloadLength = ((raw[offset + 3] & 0xff) << 8)
                    | (raw[offset + 4] & 0xff);
            int unpadded = 6 + payloadLength;
            int wireLength = unpadded + (unpadded & 1);
            if (offset + unpadded > raw.length) {
                return false;
            }
            if (wireLength != unpadded && offset + wireLength <= raw.length
                    && raw[offset + unpadded] != 0) {
                return false;
            }
            if ((raw[offset + 5] & 0xff) == packetType
                    && payloadLength == 2
                    && (raw[offset + 6] & 0xff) == field
                    && raw[offset + 7] == 0) {
                return true;
            }
            if (offset + wireLength > raw.length) {
                return false;
            }
            offset += wireLength;
        }
        return false;
    }

    static boolean containsVlcAck(byte[] raw) {
        return firstVlcAckFrame(raw) != null;
    }

    static boolean isVlcAckFrame(byte[] raw) {
        List<HpiCodec.WireFrame> frames = HpiCodec.parseComplete(raw);
        if (frames == null || frames.size() != 1) {
            return false;
        }
        HpiCodec.WireFrame frame = frames.get(0);
        return (frame.packetType == 5 || frame.packetType == 0x20)
                && Arrays.equals(frame.payload, new byte[] {0x43});
    }

    /**
     * 冻结探针合同：ACK 可以不是首帧，后面允许粘连完整帧。
     * 返回窗口内第一帧 type5/0x20、payload 恰为 {@code 0x43} 的线帧。
     */
    static byte[] firstVlcAckFrame(byte[] raw) {
        List<HpiCodec.WireFrame> frames = HpiCodec.parseComplete(raw);
        if (frames == null) {
            return null;
        }
        for (HpiCodec.WireFrame frame : frames) {
            if ((frame.packetType == 5 || frame.packetType == 0x20)
                    && Arrays.equals(frame.payload, new byte[] {0x43})) {
                return Arrays.copyOfRange(raw, frame.offset,
                        frame.offset + frame.wireLength);
            }
        }
        return null;
    }

    static boolean isShortDataCredit(byte[] raw) {
        List<HpiCodec.WireFrame> frames = HpiCodec.parseComplete(raw);
        if (frames == null || frames.size() != 1) {
            return false;
        }
        HpiCodec.WireFrame frame = frames.get(0);
        return frame.packetType == 0x20
                && Arrays.equals(frame.payload, new byte[] {0x01, 0x00});
    }

    /**
     * External DMR实时data36路径已由真机原件冻结的短信用。
     */
    static boolean isExternalDmrRelayCredit(byte[] raw) {
        List<HpiCodec.WireFrame> frames = HpiCodec.parseComplete(raw);
        if (frames == null || frames.size() != 1 || raw.length != 8) {
            return false;
        }
        HpiCodec.WireFrame frame = frames.get(0);
        return frame.wireLength == 8
                && frame.packetType == 0x03
                && Arrays.equals(frame.payload, new byte[] {0x01, 0x00});
    }

    static String sessionRebuildSignal(byte[] raw) {
        if (raw == null || raw.length == 0) {
            return null;
        }
        byte[] startup = "DMOSTARTUP".getBytes(StandardCharsets.US_ASCII);
        if (containsBytes(raw, startup)) {
            return "ascii_dmostartup";
        }
        for (int offset = 0; offset + 8 <= raw.length; offset++) {
            if ((raw[offset] & 0xff) != HpiCodec.SYNC0
                    || (raw[offset + 1] & 0xff) != HpiCodec.SYNC1
                    || (raw[offset + 2] & 0xff) != HpiCodec.COMMAND) {
                continue;
            }
            int payloadLength = ((raw[offset + 3] & 0xff) << 8)
                    | (raw[offset + 4] & 0xff);
            int wireLength = 6 + payloadLength;
            wireLength += wireLength & 1;
            if (payloadLength != 2 || offset + wireLength > raw.length) {
                continue;
            }
            int field = raw[offset + 6] & 0xff;
            int value = raw[offset + 7] & 0xff;
            if (field == 0x15) {
                return "hpi_chip_lowpwr_15_"
                        + String.format(Locale.US, "%02x", value);
            }
            if (field == 0x17 && value == 0x01) {
                return "hpi_application_ready_17_01";
            }
        }
        return null;
    }

    private static boolean containsBytes(byte[] value, byte[] target) {
        for (int offset = 0; offset + target.length <= value.length; offset++) {
            boolean match = true;
            for (int index = 0; index < target.length; index++) {
                if (value[offset + index] != target[index]) {
                    match = false;
                    break;
                }
            }
            if (match) {
                return true;
            }
        }
        return false;
    }

    static final class Session {
        private final int ownId;
        private final int calledId;
        private final int outputSequence;
        private final byte[] runtime14;

        Session(int ownId, int calledId, int outputSequence, byte[] runtime14) {
            requireU24(ownId, "本机ID");
            requireU24(calledId, "被叫ID");
            if (outputSequence != 0 && outputSequence != 1) {
                throw new IllegalArgumentException("时隙必须为0或1");
            }
            if (runtime14 == null || runtime14.length != 14) {
                throw new IllegalArgumentException("必须提供同次14字节运行快照");
            }
            this.ownId = ownId;
            this.calledId = calledId;
            this.outputSequence = outputSequence;
            this.runtime14 = runtime14.clone();
        }

        int callSlot() {
            return 0x80 | (outputSequence + 1);
        }

        byte[] setup(int index) {
            switch (index) {
            case 0:
                return HpiCodec.frame(0, new byte[] {0x19, 0x00});
            case 1:
                return HpiCodec.frame(0, new byte[] {0x02, 0x18});
            case 2:
                return HpiCodec.frame(0, new byte[] {0x3e, 0x60});
            case 3:
                return HpiCodec.frame(5,
                        new byte[] {0x6f, (byte) callSlot()});
            case 4:
                return HpiCodec.frame(0,
                        new byte[] {0x18, 0x02, 0x00, 0x00});
            default:
                throw new IndexOutOfBoundsException("setup索引");
            }
        }

        int setupPacketType(int index) {
            return index == 3 ? 5 : 0;
        }

        int setupField(int index) {
            return setup(index)[6] & 0xff;
        }

        /**
         * 探针冻结桥内收尾：{@code VOCODER_IO_OFF} 然后 {@code WORK_MODE_IDLE}。
         * 与 {@code InterphoneProbe.hpiExitTrafficPlaneWhileBridged} 逐字节相同。
         */
        byte[] cleanup(int index) {
            switch (index) {
            case 0:
                return HpiCodec.frame(0, new byte[] {0x3e, 0x00});
            case 1:
                return HpiCodec.frame(0,
                        new byte[] {0x18, 0x00, 0x00, 0x00});
            default:
                throw new IndexOutOfBoundsException("cleanup索引");
            }
        }

        int cleanupPacketType(int index) {
            return 0;
        }

        int cleanupField(int index) {
            return cleanup(index)[6] & 0xff;
        }

        /**
         * 公开厂家 DMR_STOP_CALL 使用 callmode=2，并复用当前会话的 LC9。
         */
        byte[] terminationVlc() {
            byte[] body = lc9();
            byte[] payload = new byte[3 + body.length];
            payload[0] = 0x43;
            payload[1] = (byte) (0x02 | (outputSequence << 7));
            payload[2] = (byte) body.length;
            System.arraycopy(body, 0, payload, 3, body.length);
            return HpiCodec.paddedFrame(5, payload);
        }

        byte[] vlc(int index) {
            if (index < 0 || index >= VLC_COUNT) {
                throw new IndexOutOfBoundsException("VLC索引");
            }
            byte[] body;
            if (index == 0 || index == 1 || index == 4) {
                body = lc9();
            } else if (index == 2) {
                body = signaling10();
            } else {
                body = signaling2();
            }
            byte[] payload = new byte[3 + body.length];
            payload[0] = 0x43;
            payload[1] = (byte) (VLC_BASE_MODES[index]
                    | (outputSequence << 7));
            payload[2] = (byte) body.length;
            System.arraycopy(body, 0, payload, 3, body.length);
            return HpiCodec.paddedFrame(5, payload);
        }

        byte[] runtime14() {
            return runtime14.clone();
        }

        private byte[] lc9() {
            byte[] result = new byte[9];
            result[2] = 0x40;
            putU24(result, 3, calledId);
            putU24(result, 6, ownId);
            return result;
        }

        private byte[] signaling10() {
            byte[] result = new byte[10];
            result[0] = (byte) (runtime14[1] & 0x07);
            result[1] = 0x10;
            result[2] = runtime14[2];
            System.arraycopy(runtime14, 10, result, 3, 4);
            putU24(result, 7, calledId);
            return result;
        }

        private byte[] signaling2() {
            int d = runtime14[1] & 0xff;
            int e = runtime14[2] & 0xff;
            return new byte[] {(byte) (e >>> 5),
                    (byte) (((e << 3) & 0xf8) | (d & 0x07))};
        }

        private static void putU24(byte[] target, int offset, int value) {
            target[offset] = (byte) (value >>> 16);
            target[offset + 1] = (byte) (value >>> 8);
            target[offset + 2] = (byte) value;
        }

        private static void requireU24(int value, String label) {
            if (value < 0 || value > 0xffffff) {
                throw new IllegalArgumentException(label + "超出24位");
            }
        }
    }
}
