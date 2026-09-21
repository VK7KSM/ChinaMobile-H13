package net.elfradio.h13radio;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;

/**
 * Homebrew（HBlink/BrandMeister）中继协议的报文编解码。
 *
 * <p>**这是按公开实现的常见形态写的，不是照着规范抄的**——该协议没有
 * 权威公开规范，各家实现有出入。因此：
 *
 * <ul>
 *   <li>能确定的部分（命令字、字段顺序、字节序）按常见形态实现；</li>
 *   <li>不确定的部分（`DMRD` 尾部是否带 BER/RSSI）**两种长度都接受**，
 *       发送时只发不带尾部的那种；</li>
 *   <li>任何"猜"的地方都在注释里写明是猜的。</li>
 * </ul>
 *
 * <p>真正对上以前，**不要把这里的实现当成已验证**。与真实 master 对接
 * 属于对外发包，需要操作者同意后才做。
 */
public final class HomebrewPacket {

    /** `DMRD` 不带可选尾部时的长度。 */
    public static final int DMRD_CORE_BYTES = 53;
    /** 部分 master 在尾部追加两字节（常见说法是 BER 与 RSSI）。 */
    public static final int DMRD_WITH_TAIL_BYTES = 55;
    /** 一个 DMR 突发的净荷：三帧语音加同步/嵌入信令。 */
    public static final int DMR_PAYLOAD_BYTES = 33;

    private HomebrewPacket() {}

    /** 解析后的语音报文。 */
    public static final class Dmrd {
        public final int sequence;
        public final int sourceId;
        public final int destinationId;
        public final int repeaterId;
        public final int slot;            // 0 或 1
        public final boolean privateCall;
        public final int frameType;
        public final int dataType;
        public final int streamId;
        public final byte[] payload;      // 33 字节
        public final boolean hadTail;

        Dmrd(int sequence, int sourceId, int destinationId, int repeaterId,
                int slot, boolean privateCall, int frameType, int dataType,
                int streamId, byte[] payload, boolean hadTail) {
            this.sequence = sequence;
            this.sourceId = sourceId;
            this.destinationId = destinationId;
            this.repeaterId = repeaterId;
            this.slot = slot;
            this.privateCall = privateCall;
            this.frameType = frameType;
            this.dataType = dataType;
            this.streamId = streamId;
            this.payload = payload;
            this.hadTail = hadTail;
        }

        @Override
        public String toString() {
            return String.format(Locale.US,
                    "DMRD 序 %d  主叫 %d → 目标 %d  时隙%d  %s  流 %08x",
                    sequence, sourceId, destinationId, slot + 1,
                    privateCall ? "私呼" : "组呼", streamId);
        }
    }

    private static void putU24(byte[] out, int off, int value) {
        out[off] = (byte) (value >> 16);
        out[off + 1] = (byte) (value >> 8);
        out[off + 2] = (byte) value;
    }

    private static int u24(byte[] in, int off) {
        return ((in[off] & 0xff) << 16) | ((in[off + 1] & 0xff) << 8)
                | (in[off + 2] & 0xff);
    }

    private static void putU32(byte[] out, int off, int value) {
        out[off] = (byte) (value >> 24);
        out[off + 1] = (byte) (value >> 16);
        out[off + 2] = (byte) (value >> 8);
        out[off + 3] = (byte) value;
    }

    private static int u32(byte[] in, int off) {
        return ((in[off] & 0xff) << 24) | ((in[off + 1] & 0xff) << 16)
                | ((in[off + 2] & 0xff) << 8) | (in[off + 3] & 0xff);
    }

    static boolean startsWith(byte[] data, String tag) {
        byte[] t = tag.getBytes(StandardCharsets.US_ASCII);
        if (data == null || data.length < t.length) {
            return false;
        }
        for (int i = 0; i < t.length; i++) {
            if (data[i] != t[i]) {
                return false;
            }
        }
        return true;
    }

    private static byte[] tagWithId(String tag, int repeaterId) {
        byte[] t = tag.getBytes(StandardCharsets.US_ASCII);
        byte[] out = Arrays.copyOf(t, t.length + 4);
        putU32(out, t.length, repeaterId);
        return out;
    }

    public static byte[] login(int repeaterId) {
        return tagWithId("RPTL", repeaterId);
    }

    public static byte[] ping(int repeaterId) {
        return tagWithId("RPTPING", repeaterId);
    }

    public static byte[] close(int repeaterId) {
        return tagWithId("RPTCL", repeaterId);
    }

    /** `RPTK` = 标签 + 中继 ID + 32 字节摘要。摘要由调用方算，这里不碰口令。 */
    public static byte[] authenticate(int repeaterId, byte[] digest32) {
        if (digest32 == null || digest32.length != 32) {
            throw new IllegalArgumentException("摘要须为 32 字节");
        }
        byte[] head = tagWithId("RPTK", repeaterId);
        byte[] out = Arrays.copyOf(head, head.length + 32);
        System.arraycopy(digest32, 0, out, head.length, 32);
        return out;
    }

    /** `RPTC` = 标签 + 中继 ID + 配置正文。正文格式各家不同，原样透传。 */
    public static byte[] config(int repeaterId, byte[] body) {
        byte[] head = tagWithId("RPTC", repeaterId);
        byte[] out = Arrays.copyOf(head, head.length + body.length);
        System.arraycopy(body, 0, out, head.length, body.length);
        return out;
    }

    /** 从 `RPTACK` 回应里取 4 字节盐值。长度不对返回 null，不猜。 */
    public static byte[] saltFromAck(byte[] data) {
        if (!startsWith(data, "RPTACK") || data.length < 10) {
            return null;
        }
        return Arrays.copyOfRange(data, 6, 10);
    }

    public static byte[] encodeDmrd(int sequence, int sourceId,
            int destinationId, int repeaterId, int slot, boolean privateCall,
            int frameType, int dataType, int streamId, byte[] payload) {
        if (payload == null || payload.length != DMR_PAYLOAD_BYTES) {
            throw new IllegalArgumentException(
                    "净荷须为 " + DMR_PAYLOAD_BYTES + " 字节");
        }
        if (slot != 0 && slot != 1) {
            throw new IllegalArgumentException("时隙须为 0 或 1");
        }
        byte[] out = new byte[DMRD_CORE_BYTES];
        byte[] tag = "DMRD".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(tag, 0, out, 0, 4);
        out[4] = (byte) sequence;
        putU24(out, 5, sourceId);
        putU24(out, 8, destinationId);
        putU32(out, 11, repeaterId);
        int bits = (slot << 7) | ((privateCall ? 1 : 0) << 6)
                | ((frameType & 0x03) << 4) | (dataType & 0x0f);
        out[15] = (byte) bits;
        putU32(out, 16, streamId);
        System.arraycopy(payload, 0, out, 20, DMR_PAYLOAD_BYTES);
        return out;
    }

    /** 解析 `DMRD`。长度既接受 53 也接受 55；其它长度返回 null，不猜。 */
    public static Dmrd decodeDmrd(byte[] data) {
        if (!startsWith(data, "DMRD")) {
            return null;
        }
        if (data.length != DMRD_CORE_BYTES
                && data.length != DMRD_WITH_TAIL_BYTES) {
            return null;
        }
        int bits = data[15] & 0xff;
        return new Dmrd(data[4] & 0xff, u24(data, 5), u24(data, 8),
                u32(data, 11), (bits >> 7) & 1, ((bits >> 6) & 1) == 1,
                (bits >> 4) & 0x03, bits & 0x0f, u32(data, 16),
                Arrays.copyOfRange(data, 20, 20 + DMR_PAYLOAD_BYTES),
                data.length == DMRD_WITH_TAIL_BYTES);
    }
}
