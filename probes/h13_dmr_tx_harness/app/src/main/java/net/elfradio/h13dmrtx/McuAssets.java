package net.elfradio.h13dmrtx;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

final class McuAssets {
    static final int VECTOR = 0x2000003c;
    static final int BRIDGE_FLAG = 0x2000015c;
    // 发射 codec 增益档位表：3 字节，按信道的麦克风增益档位取值。
    // 原厂外部DMR配置链把该地址传给信道与功率寄存器组写函数。
    // 接收磁带缓冲区：44 字节管理头（+0x28 为块内单元数），4 字节固定块
    // 标记（小端 d4 c3 b2 a1），随后是连续的 27 字节 CHAN_D 单元。
    // 该结构与读法由 2026-08-06 的接收捕获实机闭环确认。
    static final int TAPE_HEADER = 0x20001c00;
    static final int TAPE_HEADER_LENGTH = 44;
    static final int TAPE_UNIT_COUNT_OFFSET = 0x28;
    static final int TAPE_MARKER = 0x20001c2c;
    static final int TAPE_MARKER_VALUE = 0xa1b2c3d4;
    static final int TAPE_DATA = 0x20001c30;
    static final int TAPE_UNIT_BYTES = 27;

    static final int CODEC_GAIN_TABLE = 0x20002e25;
    static final int CODEC_GAIN_PRESETS = 3;
    static final int RF_EDGE_COUNTER = 0x20000134;
    static final int RF_EDGE_COUNTER_LENGTH = 4;
    static final int RF_TIMING_CONTROL = 0x20000138;
    static final int RF_TIMING_CONTROL_LENGTH = 8;
    static final int RF_TIMING_STATE = RF_EDGE_COUNTER;
    static final int RF_TIMING_STATE_LENGTH = RF_EDGE_COUNTER_LENGTH
            + RF_TIMING_CONTROL_LENGTH;
    static final int ACTIVE_GATE_OFFSET = 4;
    static final int ACTIVE_GATE = 0x20000138;
    static final int DATA_GATE = 0x2000045c;
    static final int SIGNALING_FLAGS = 0x2000040c;
    static final int SESSION_SCALARS = 0x20000410;
    static final int SESSION_SCALARS_LENGTH = 20;
    static final int QUEUES = 0x20004b56;
    static final int QUEUES_LENGTH = 280;
    static final int SESSION_RECORD = 0x20004cce;
    static final int SESSION_RECORD_LENGTH = 9;
    static final int MCU_TICK = 0x200000d0;
    static final int POWER_SAVE_FLAG = 0x200017e1;

    // MCU 调试控制台的三条诊断命令其实就是读这几个固定地址（H13_new.md
    // 2.9.46）。会话期间宿主握着串口，用 memread 直接取即可，不必另开
    // 控制台会话——欠载因此成为会话内指标，且是**模块自己报的数**，
    // 不是我们从时间轴推算的。
    /** `getchandcnt`：模块因宿主未按时供数而自行补的零 CHAN_D 单元数。 */
    static final int ZERO_CHAN_D_COUNT = 0x2000042c;
    static final int ZERO_CHAN_D_COUNT_LENGTH = 2;
    /** `getsm`：呼叫状态字节，与 2.9.19 的前置条件同一个。 */
    static final int CALL_STATE_BYTE = 0x20000160;
    /** `dropvoice` 的丢帧计数与 `zerovoice` 的供零开关，故障注入用。 */
    static final int DROP_VOICE_COUNT = 0x200001e2;
    static final int ZERO_VOICE_FLAG = 0x200001e3;

    static final int FULLPREP_CODE = 0x20002f00;
    static final int FULLPREP_ENTRY = 0x20002f01;
    static final int FULLPREP_METADATA = 0x20003050;
    static final int FULLPREP_METADATA_LENGTH = 0x24;
    static final int FULLPREP_MIRROR = 0x20003068;
    static final int FULLPREP_HELPER = 0x20003080;

    static final int RF_OFF_CODE = 0x20003140;
    static final int RF_OFF_ENTRY = 0x20003141;
    static final int RF_MARKER = 0x200031c0;
    static final int RF_PREP_CODE = 0x20003200;
    static final int RF_PREP_ENTRY = 0x20003201;

    static final int SECOND_BRIDGE_CODE = 0x20001600;
    static final int SECOND_BRIDGE_ENTRY = 0x20001601;
    static final int SECOND_BRIDGE_COUNTER = 0x20001680;
    static final int SECOND_BRIDGE_MARKER = 0x20001684;

    static final String FULLPREP_FILE =
            "mcu/h13_mcu_combined_multitick_runtime_mirror_v005.bin";
    static final String FULLPREP_SHA256 =
            "F998B11BC800891ADA9F9653D0EE567BBF233F450691F42BA8F779305F873226";
    static final int FULLPREP_LENGTH = 336;

    static final String FULLPREP_RF_FILE =
            "mcu/h13_mcu_combined_multitick_external_dmr_rf_v009.bin";
    static final String FULLPREP_RF_SHA256 =
            "5E53D9D65A4C1BC63C822F59F0C759B0495B1DE46DD8E2F9ABE54BF0812B2871";
    // 通道功率寄存器：射频时序回调每个时隙读取它并写入功率 DAC。
    // 探针历史上从不设置它，发射功率取决于上次残留值——2026-09-19 五次
    // 发射实测 0.05/0.279/0.803/1.24 瓦，同配置相差 25 倍。
    // 出厂低功率档码值为 2030，记录 §18.189 实测约 2.3 瓦。
    static final int CHANNEL_POWER_CODE = 0x20002DE6;
    static final int CHANNEL_POWER_CODE_LENGTH = 2;
    static final int FACTORY_LOW_POWER_CODE = 2030;

    static final int RF_HOLD_COUNTER = 0x20003074;
    static final int RF_HOLD_COUNTER_LENGTH = 4;
    static final int INBRIDGE_RF_OFF_CODE = 0x20003200;
    static final int INBRIDGE_RF_OFF_ENTRY = 0x20003201;
    static final int INBRIDGE_RF_OFF_LENGTH = 84;
    static final String INBRIDGE_RF_OFF_FILE =
            "mcu/h13_mcu_external_dmr_rf_off_keep_systick_v001.bin";
    static final String INBRIDGE_RF_OFF_SHA256 =
            "05AE01B18A7BA8CE9DDAFF3A8FC84BA0272CEB05ECF1EE5B8870DC563F078AD7";

    static final int RF_PREP_CHAIN_CODE = 0x20003100;
    static final int RF_PREP_CHAIN_ENTRY = 0x20003101;
    static final int RF_PREP_CHAIN_LENGTH = 60;
    static final int RF_SLOT_COUNT = 0x2000013a;
    static final int RF_COMPLETION = 0x2000013c;
    static final int RF_SLOT_COUNT_VALUE = 160;
    static final String RF_PREP_CHAIN_FILE =
            "mcu/h13_mcu_external_dmr_rf_prep_v009.bin";
    static final String RF_PREP_CHAIN_SHA256 =
            "B47DED848E14879CDDB5EFA424F060FF84E04F69384D38E32CCF97B9035EBC33";
    static final String RF_PREP_CHAIN_V007_FORBIDDEN_SHA256 =
            "0599E611C3BFC5BAEA542AD6A73D3CA80CFDEF218D47AF4B9533D76A079318D1";
    static final String RF_PREP_CHAIN_V008_FORBIDDEN_SHA256 =
            "C242231DCE31E96356140924C698D667528103BB9F07639717839E77396FB040";

    static final String FULLPREP_HELPER_FILE =
            "mcu/h13_mcu_runtime_mirror_helper_v005.bin";
    static final String FULLPREP_HELPER_SHA256 =
            "40326A970D244EEDE132EE4A05E6F078FFDC09D992F60B688061384809D6E503";
    static final int FULLPREP_HELPER_LENGTH = 112;

    static final String RF_PREP_FILE =
            "mcu/h13_mcu_external_dmr_rf_prep_oneshot_v001.bin";
    static final String RF_PREP_SHA256 =
            "4D64784ADF30CD7BCB91E8BCF8E83030424326FC880E6D1096BAB4F7B7B1CF40";
    static final int RF_PREP_LENGTH = 72;

    static final String RF_OFF_FILE =
            "mcu/h13_mcu_external_dmr_rf_off_v007.bin";
    static final String RF_OFF_SHA256 =
            "B295C416579490A1A1BCD4D96632E28CC9BF683D49E21E75458B192F62DD8B59";
    static final int RF_OFF_LENGTH = 60;

    static final String SECOND_BRIDGE_FILE =
            "mcu/h13_mcu_uart_hpi_bridge_timeout_cmdtail_v061.bin";
    static final String SECOND_BRIDGE_SHA256 =
            "5C531F291C56481A120B59D5244826797D9575E7C24E8935CA8AD8DB00339CD7";
    static final int SECOND_BRIDGE_LENGTH = 64;

    private McuAssets() {}

    static byte[] loadVerified(InputStream input, int length, String sha256)
            throws IOException {
        byte[] value = readAll(input);
        if (value.length != length || !sha256.equals(Bytes.sha256(value))) {
            throw new IOException("MCU资产长度或SHA-256不匹配");
        }
        return value;
    }

    static boolean containsLe32(byte[] value, int expected) {
        byte[] target = new byte[] {(byte) expected,
                (byte) (expected >>> 8), (byte) (expected >>> 16),
                (byte) (expected >>> 24)};
        for (int offset = 0; offset + 4 <= value.length; offset++) {
            if (Arrays.equals(target, Arrays.copyOfRange(value, offset,
                    offset + 4))) {
                return true;
            }
        }
        return false;
    }

    static boolean containsThumb16(byte[] value, int halfword) {
        byte low = (byte) halfword;
        byte high = (byte) (halfword >>> 8);
        for (int offset = 0; offset + 1 < value.length; offset++) {
            if (value[offset] == low && value[offset + 1] == high) {
                return true;
            }
        }
        return false;
    }

    private static byte[] readAll(InputStream input) throws IOException {
        try (InputStream source = input;
                ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[1024];
            int count;
            while ((count = source.read(buffer)) >= 0) {
                out.write(buffer, 0, count);
            }
            return out.toByteArray();
        }
    }
}
