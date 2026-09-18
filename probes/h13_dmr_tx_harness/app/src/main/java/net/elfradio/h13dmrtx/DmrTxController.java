package net.elfradio.h13dmrtx;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.res.AssetManager;
import android.os.Build;
import android.os.SystemClock;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

final class DmrTxController {
    static final String MODE_SETUP0_ONLY = "setup0_only_no_rf";
    static final String MODE_CLEAR_ONLY = "clear_channel_only_no_rf";
    static final String MODE_NO_RF = "session_prepare_no_rf";
    static final String MODE_DEADLINE_HANDSHAKE_NO_RF =
            "deadline_handshake_no_rf";
    static final String MODE_LOW_POWER_RF = "morse_low_power_rf";
    static final String MODE_RELAY_ONE_NO_RF =
            "realtime_relay_one_data36_no_rf";
    static final String MODE_RELAY_FIXED_ASSET_NO_RF =
            "realtime_relay_fixed_asset_one_data36_no_rf";
    static final String MODE_RELAY_FIXED_ASSET_THREE_NO_RF =
            "realtime_relay_fixed_asset_three_data36_no_rf";
    static final String MODE_RELAY_FIXED_ASSET_FIVE_NO_RF =
            "realtime_relay_fixed_asset_five_data36_no_rf";
    static final String MODE_RELAY_FIXED_ASSET_TWENTYFIVE_NO_RF =
            "realtime_relay_fixed_asset_twentyfive_data36_no_rf";
    static final String MODE_RELAY_SOFTWARE_49BIT_NO_RF =
            "realtime_relay_software_49bit_one_data36_no_rf";
    static final String MODE_RELAY_SOFTWARE_49BIT_TONE800_NO_RF =
            "realtime_relay_software_49bit_tone800_one_data36_no_rf";
    static final String MODE_RELAY_SOFTWARE_49BIT_MORSE_NO_RF =
            "realtime_relay_software_49bit_morse_one_data36_no_rf";
    static final String MODE_RELAY_ENCODE_DMR_SILENCE_NO_RF =
            "realtime_relay_encode_dmr_silence_one_data36_no_rf";
    static final String MODE_RELAY_ENCODE_DMR_TONE800_NO_RF =
            "realtime_relay_encode_dmr_tone800_one_data36_no_rf";
    static final String MODE_RELAY_ENCODE_DMR_TONE800_THREE_NO_RF =
            "realtime_relay_encode_dmr_tone800_three_data36_no_rf";
    static final String MODE_RELAY_ENCODE_DMR_TONE800_FIVE_NO_RF =
            "realtime_relay_encode_dmr_tone800_five_data36_no_rf";
    static final String MODE_RELAY_ENCODE_DMR_MORSE_UNIQUE_NO_RF =
            "realtime_relay_encode_dmr_morse_unique_one_data36_no_rf";
    static final String MODE_RELAY_ENCODE_DMR_MORSE_UNIQUE_THREE_NO_RF =
            "realtime_relay_encode_dmr_morse_unique_three_data36_no_rf";
    static final String MODE_RELAY_ENCODE_DMR_MORSE_UNIQUE_FIVE_NO_RF =
            "realtime_relay_encode_dmr_morse_unique_five_data36_no_rf";
    static final String MODE_RELAY_ENCODE_DMR_MORSE_UNIQUE_FIVE_LOW_POWER_RF =
            "realtime_relay_encode_dmr_morse_unique_five_low_power_rf";
    static final String MODE_RELAY_SOFTWARE_PRIVACY_MORSE_TWENTYFIVE_NO_RF =
            "realtime_relay_software_privacy_morse_twentyfive_no_rf";
    static final String MODE_RELAY_SOFTWARE_PRIVACY_MORSE_TWENTYFIVE_LOW_POWER_RF =
            "realtime_relay_software_privacy_morse_twentyfive_low_power_rf";
    static final String MODE_RELAY_SOFTWARE_PRIVACY_TRIPLE_SOS_NO_RF =
            "realtime_relay_software_privacy_triple_sos_no_rf";
    static final String MODE_RELAY_SOFTWARE_PRIVACY_TRIPLE_SOS_LOW_POWER_RF =
            "realtime_relay_software_privacy_triple_sos_low_power_rf";
    static final String MODE_POST_VLC_THREE_LIVE_SOFTWARE_ONE_NO_RF =
            "realtime_post_vlc_three_live_units_software_one_data36_no_rf";
    static final String MODE_ACK_PACED_VLC_SOFTWARE_ONE_NO_RF =
            "ack_paced_vlc_software_one_data36_no_rf";
    static final String MODE_ACK_PACED_VLC_SOFTWARE_TRIPLE_SOS_NO_RF =
            "ack_paced_vlc_software_triple_sos_active_no_rf";
    // 语音突发格式验证模式（2026-09-18）。三者与 v0.79 的主动节拍供数完全相同，
    // 唯一差别是语音单元的线上格式：单元由 36 字节四帧改为 27 字节三帧，
    // 节拍由 80 毫秒改为 60 毫秒，语音帧流逐字节不变。均为无射频模式。
    static final String MODE_VOICE_BURST_TYPE3_NO_RF =
            "voice_burst_chan_d27_type3_no_rf";
    static final String MODE_VOICE_BURST_TYPE0_NO_RF =
            "voice_burst_chan_d27_type0_no_rf";
    static final String MODE_VOICE_BURST_DIGC_NO_RF =
            "voice_burst_digc_frame_no_rf";

    // 人声素材验证模式（2026-09-18）。正文为 26 字母加 10 数字逐个朗读、
    // 28.80 秒、1440 个语音帧，替代此前 6.24 秒的摩尔斯正文。
    // 摩尔斯经该软件编解码链之后字符已无法辨认，不适合作为听辨判据；
    // 人声内容可正常识别，且能逐项核对从第几个条目开始丢失。
    // 三者均为无射频模式，正文长度与节拍由素材实际长度决定。
    static final String MODE_SPEECH_AZ09_TYPE3_NO_RF =
            "speech_az09_chan_d27_type3_no_rf";
    static final String MODE_SPEECH_AZ09_TYPE0_NO_RF =
            "speech_az09_chan_d27_type0_no_rf";
    static final String MODE_SPEECH_AZ09_DIGC_NO_RF =
            "speech_az09_digc_frame_no_rf";

    static final String RF_PERMISSION = "authorized_low_power_once";
    /**
     * 会话所用的语音注入格式。除三个语音突发验证模式外一律为历史实现，
     * 保证既有模式的行为逐字节不变。
     */
    static DmrProtocol.VoiceFormat voiceFormatForMode(String mode) {
        if (MODE_VOICE_BURST_TYPE3_NO_RF.equals(mode)) {
            return DmrProtocol.VoiceFormat.CHAN_D27_TYPE3;
        }
        if (MODE_VOICE_BURST_TYPE0_NO_RF.equals(mode)) {
            return DmrProtocol.VoiceFormat.CHAN_D27_TYPE0;
        }
        if (MODE_VOICE_BURST_DIGC_NO_RF.equals(mode)) {
            return DmrProtocol.VoiceFormat.DIGC_VOICE_BURST;
        }
        if (MODE_SPEECH_AZ09_TYPE3_NO_RF.equals(mode)) {
            return DmrProtocol.VoiceFormat.CHAN_D27_TYPE3;
        }
        if (MODE_SPEECH_AZ09_TYPE0_NO_RF.equals(mode)) {
            return DmrProtocol.VoiceFormat.CHAN_D27_TYPE0;
        }
        if (MODE_SPEECH_AZ09_DIGC_NO_RF.equals(mode)) {
            return DmrProtocol.VoiceFormat.DIGC_VOICE_BURST;
        }
        return DmrProtocol.VoiceFormat.LEGACY_CHAN_D36;
    }

    /** 三个语音突发验证模式共用 v0.79 的主动节拍供数路径。 */
    /**
     * 按正文字节数与格式计算期望包数。正文长度不再写死：
     * 三遍摩尔斯为 2808 字节，人声素材为 12960 字节，
     * 换素材时只需换正文，包数与节拍随之而变。
     */
    static int expectedUnitsFor(int bodyBytes, DmrProtocol.VoiceFormat format) {
        int unitBytes = TxPlan.unitBytes(format);
        if (bodyBytes <= 0 || bodyBytes % unitBytes != 0) {
            throw new IllegalArgumentException("正文" + bodyBytes
                    + "字节不能被单元长度" + unitBytes + "整除");
        }
        return bodyBytes / unitBytes;
    }

    /** 历史三遍摩尔斯正文的期望包数，保留给既有路径。 */
    static int expectedTripleSosUnits(DmrProtocol.VoiceFormat format) {
        return expectedUnitsFor(RealtimeRelay.TRIPLE_SOS_BYTES, format);
    }

    /**
     * 会话开始前的格式一致性自检。
     *
     * 历史教训：格式贯通后仍有校验常量散落各处未跟随，这类常量不影响正常构造，
     * 只在校验时触发，因此构造与写出的离线回放覆盖不到。此处在接触协议之前
     * 集中核对各处按格式计算的量是否自洽，一旦不一致立即以明确信息拒绝，
     * 避免把实现缺陷表现成协议层失败。
     *
     * @return 不一致时返回描述，自洽时返回 null
     */
    static String voiceFormatConsistencyError(DmrProtocol.VoiceFormat format,
            int relayMaximumUnits) {
        return voiceFormatConsistencyError(format, relayMaximumUnits,
                RealtimeRelay.TRIPLE_SOS_BYTES);
    }

    static String voiceFormatConsistencyError(DmrProtocol.VoiceFormat format,
            int relayMaximumUnits, int bodyBytes) {
        int unitBytes = TxPlan.unitBytes(format);
        int framesPerUnit = TxPlan.framesPerUnit(format);
        long interval = TxPlan.unitIntervalMs(format);
        if (bodyBytes <= 0 || bodyBytes % unitBytes != 0) {
            return "正文" + bodyBytes + "字节不能被单元长度" + unitBytes + "整除";
        }
        int expected = bodyBytes / unitBytes;

        if (unitBytes != framesPerUnit * TxPlan.AMBE_BYTES_PER_FRAME) {
            return "单元长度与每包帧数不自洽: " + unitBytes + " vs " + framesPerUnit;
        }
        if (interval != framesPerUnit * 20L) {
            return "节拍与每包帧数不自洽: " + interval + " vs " + framesPerUnit;
        }
        if (relayMaximumUnits != expected) {
            return "中继包数" + relayMaximumUnits + "与本格式期望" + expected + "不一致";
        }
        // 同一段正文在不同格式下总时长应当一致：内容相同，只是打包粒度不同
        // 热路径证据缓冲容量必须覆盖本次包数。历史上该容量按 78 包写死，
        // 换用 480 包的人声正文后在第 257 包触上限，会话中途失败。
        // 此处在准入期核对，避免把容量不足表现成协议失败。
        if (expected > RelayHotPathEvidence.MAX_EVENTS) {
            return "本次" + expected + "包超出热路径证据容量"
                    + RelayHotPathEvidence.MAX_EVENTS + "项";
        }

        long totalMs = (expected - 1L) * interval;
        long legacyMs = (bodyBytes / TxPlan.unitBytes(
                DmrProtocol.VoiceFormat.LEGACY_CHAN_D36) - 1L)
                * TxPlan.UNIT_INTERVAL_MS;
        if (Math.abs(totalMs - legacyMs) > 500L) {
            return "总时长" + totalMs + "毫秒与同正文历史格式" + legacyMs + "毫秒偏离过多";
        }
        return null;
    }

    /** 使用人声素材的模式。正文为 28.80 秒、1440 帧。 */
    static boolean isSpeechAz09Mode(String mode) {
        return MODE_SPEECH_AZ09_TYPE3_NO_RF.equals(mode)
                || MODE_SPEECH_AZ09_TYPE0_NO_RF.equals(mode)
                || MODE_SPEECH_AZ09_DIGC_NO_RF.equals(mode);
    }

    static boolean isVoiceBurstMode(String mode) {
        return MODE_VOICE_BURST_TYPE3_NO_RF.equals(mode)
                || MODE_VOICE_BURST_TYPE0_NO_RF.equals(mode)
                || MODE_VOICE_BURST_DIGC_NO_RF.equals(mode);
    }

    static final String RELAY_SOURCE_SPEECH_AZ09 = "speech_az09";
    static final String RELAY_SOURCE_ENCODE_DMR_SILENCE =
            "encode_dmr_silence";
    static final String RELAY_SOURCE_ENCODE_DMR_TONE800 =
            "encode_dmr_tone800";
    static final String RELAY_SOURCE_ENCODE_DMR_TONE800_THREE =
            "encode_dmr_tone800_three";
    static final String RELAY_SOURCE_ENCODE_DMR_TONE800_FIVE =
            "encode_dmr_tone800_five";
    static final String RELAY_SOURCE_ENCODE_DMR_MORSE_UNIQUE =
            "encode_dmr_morse_unique";
    static final String RELAY_SOURCE_ENCODE_DMR_MORSE_UNIQUE_THREE =
            "encode_dmr_morse_unique_three";
    static final String RELAY_SOURCE_ENCODE_DMR_MORSE_UNIQUE_FIVE =
            "encode_dmr_morse_unique_five";
    static final String RELAY_SOURCE_FIXED_ASSET_THREE = "fixed_asset_three";
    static final String RELAY_SOURCE_FIXED_ASSET_FIVE = "fixed_asset_five";
    static final String RELAY_SOURCE_FIXED_ASSET_TWENTYFIVE =
            "fixed_asset_twentyfive";
    static final String RELAY_SOURCE_SOFTWARE_49BIT = "software_49bit";
    static final String RELAY_SOURCE_SOFTWARE_49BIT_TONE800 =
            "software_49bit_tone800";
    static final String RELAY_SOURCE_SOFTWARE_49BIT_MORSE =
            "software_49bit_morse";
    static final String RELAY_SOURCE_SOFTWARE_PRIVACY_MORSE =
            "software_privacy_morse_channel72";
    static final String RELAY_SOURCE_SOFTWARE_PRIVACY_TRIPLE_SOS =
            "software_privacy_triple_sos_channel72";
    static final String RELAY_SOURCE_POST_VLC_SOFTWARE_MORSE =
            "post_vlc_software_privacy_morse_first_data36";
    static final String RELAY_SOURCE_ACK_PACED_SOFTWARE_MORSE =
            "ack_paced_vlc_software_privacy_morse_first_data36";
    static final String RELAY_SOURCE_ACK_PACED_SOFTWARE_TRIPLE_SOS =
            "ack_paced_vlc_software_privacy_triple_sos_active";
    static final String TRIPLE_SOS_CLEAR_CHANNEL72_SHA256 =
            "65692AE4CCAAF6F71F6F1A4B3BD41888ADD9E658AAB58B65EFCC059A5A696536";
    static final String TRIPLE_SOS_FINAL_CHANNEL72_SHA256 =
            "2DDB4896900F8FE763C296D47AC6BE4D4E8BA1A95F5E63ED567B8C763D9E0654";
    static final String SILENCE_49BIT_36_SHA256 =
            "EAE3B34A8967E93800F392DAFFE10EEE1DAFC0E6430003637B6E2BBCB1830F2B";
    static final String TONE800_49BIT_36_SHA256 =
            "6A4C431FC334FAD68A48E214920E09773497F1FD36F6C2BFA4EED22334835992";
    static final int SOFTWARE_49BIT_FRAMES = 4;
    static final int MORSE_49BIT_START_FRAME = 3;

    private static final String SERIAL = "/dev/ttyHS0";
    private static final int BAUD = 57600;
    private static final String MODULE_VERSION = "0.3.66;V2.01.07I3";
    private static final String CHANNEL_OFF =
            "433550000,433550000,13,99,12345678,directmode,group,"
            + "slot1,slot1,off,low,8,2,2,99";
    private static final String CHANNEL_ON =
            "433550000,433550000,13,99,12345678,directmode,group,"
            + "slot1,slot1,on,low,8,2,2,99";
    private static final long FULLPREP_DELAY_MS = 10000;
    static final long POST_BRIDGE_SETTLE_MS = 500;
    private static final long FIRST_BRIDGE_EXIT_MS = 33500;
    private static final long FIRST_BRIDGE_MARGIN_MS = 2000;
    static final long RF_HOLD_MS = 16000;
    static final long RELAY_PRE_VLC2_WAIT_MS = 1000;
    static final long RELAY_CREDIT_TIMEOUT_MS = 1500;
    static final long RELAY_VLC_ACK_DURATION_MS = 500;
    static final long ACTIVE_PACED_TAIL_MS = 500;
    static final long POST_VLC_TRIGGER_TIMEOUT_MS = 1500;
    static final long POST_VLC_OBSERVATION_MS = 1500;
    static final long TRIPLE_SOS_PLAYOUT_MS = RealtimeRelay.TRIPLE_SOS_UNITS
            * TxPlan.UNIT_INTERVAL_MS;
    private static final long SECOND_BRIDGE_EXIT_MS = 3000;
    private static final long SECOND_BRIDGE_MARGIN_MS = 1500;
    private static final int[] FIRMWARE_SLICE_ADDRESSES = {
            0x0801568c, 0x0801db08, 0x080202a0, 0x08024dc0,
            0x0802680c, 0x0801d834, 0x0802314c, 0x080235c8
    };
    private static final int[] FIRMWARE_SLICE_LENGTHS = {
            88, 80, 232, 0x374, 0x220, 0x2d4, 0x46c, 0xe8
    };
    private static final String[] FIRMWARE_SLICE_SHA256 = {
            "4EDB3A2F88C4319BD5B11564C91FD90F0CB25DD0580D0FE577657E95D9953E82",
            "2CAF35C22705F3BF65EB34D6430FC52C2A3B137B40F362A888FE522AB2D70F34",
            "904F9344D9D4C9BE5AE48949392FE46374EF73829E99D1BEDF69730E02B86EE7",
            "E6F166904E5964B3180ECD7B5BD3720CC4798A02F5A15F08E1FFC903587780F4",
            "BEA0BFA5D0D01EBD1BF33D119CA0E30A7760A7F2775FC62CA78BA58D7AC49624",
            "C4C414452E7D8363060EF4001D762F45769351B160C0D9D02F3E9F70DC72FEAC",
            "09E3B786395FE419E08F998C8946E048B10E78663F795058D65B603965137867",
            "BA64930D73F4186D90B0D1AF206DBBB295619EC1543D315892C2A6A99DB2B7D6"
    };
    private static final String FIRMWARE_SLICES_COMBINED_SHA256 =
            "4D50CD81A4D0856A96F2B4D04D84682B502812D2024822B082729E9D85358FBA";

    interface StatusSink {
        void update(String value);
    }

    private final Context context;
    private final AssetManager assets;
    private final StatusSink status;
    private EvidenceStore evidence;
    private SerialTransport transport;
    private McuMemory memory;
    private McuDebugOutput debugOutput;
    private SramTransaction sram;
    private boolean privacyMutation;
    private boolean rfMayBeActive;
    private long bridgeExpectedExitAt;
    private double measuredTickHz;
    private byte[] privacyOffBaseline14;
    private TxStateMachine activeMachine;
    private RealtimeRelay activeRelay;
    private boolean firstBridgeExitConfirmed;
    private boolean secondBridgeExitConfirmed;
    private boolean rfPrepareExecuted;
    private boolean rfOffConfirmed;
    private boolean textRecoveryConfirmed;
    private boolean rebootRequired;
    private final List<String> recoveryErrors = new ArrayList<>();
    private boolean used;
    private String sessionId;
    private boolean deviceDeadlineArmRequested;
    private boolean deviceDeadlineArmed;
    private boolean powerSaveMutation;
    private boolean powerSaveRestored = true;
    private boolean powerSaveWasDisabled;
    private boolean powerSaveRestoreAttempted;
    private int powerSaveEntryValue = -1;
    private int powerSaveRestoredValue = -1;
    private String postVlcObservationClass = "";

    DmrTxController(Context context, StatusSink status) {
        this.context = context.getApplicationContext();
        this.assets = context.getAssets();
        this.status = status;
    }

    String run(String mode, String permission) throws Exception {
        if (used) {
            throw new IllegalStateException("每个控制器实例只允许一个会话");
        }
        used = true;
        boolean setup0Only = MODE_SETUP0_ONLY.equals(mode);
        boolean clearOnly = MODE_CLEAR_ONLY.equals(mode);
        boolean deadlineHandshakeOnly =
                MODE_DEADLINE_HANDSHAKE_NO_RF.equals(mode);
        if (MODE_LOW_POWER_RF.equals(mode)) {
            throw new IllegalArgumentException("本版本拒绝第二桥26包摩尔斯射频路径");
        }
        if (MODE_RELAY_ENCODE_DMR_MORSE_UNIQUE_FIVE_LOW_POWER_RF.equals(mode)) {
            throw new IllegalArgumentException("v0.41已知错误射频载荷已永久禁用");
        }
        if (MODE_RELAY_SOFTWARE_PRIVACY_MORSE_TWENTYFIVE_LOW_POWER_RF
                .equals(mode)) {
            throw new IllegalArgumentException("v0.55短时隙单遍射频路径已永久禁用");
        }
        boolean requestedRf = requestsLowPowerRf(mode);
        boolean allowRf = requestedRf;
        if (allowRf && !RF_PERMISSION.equals(permission)) {
            throw new IllegalArgumentException("低功率射频需要当次授权");
        }
        boolean software49bitRelay =
                MODE_RELAY_SOFTWARE_49BIT_NO_RF.equals(mode)
                || MODE_RELAY_SOFTWARE_49BIT_TONE800_NO_RF.equals(mode)
                || MODE_RELAY_SOFTWARE_49BIT_MORSE_NO_RF.equals(mode);
        boolean software49bitTone800 =
                MODE_RELAY_SOFTWARE_49BIT_TONE800_NO_RF.equals(mode);
        boolean software49bitMorse =
                MODE_RELAY_SOFTWARE_49BIT_MORSE_NO_RF.equals(mode);
        boolean softwarePrivacyMorse =
                MODE_RELAY_SOFTWARE_PRIVACY_MORSE_TWENTYFIVE_NO_RF
                        .equals(mode)
                || MODE_RELAY_SOFTWARE_PRIVACY_MORSE_TWENTYFIVE_LOW_POWER_RF
                        .equals(mode)
                || MODE_POST_VLC_THREE_LIVE_SOFTWARE_ONE_NO_RF.equals(mode)
                || MODE_ACK_PACED_VLC_SOFTWARE_ONE_NO_RF.equals(mode);
        boolean postVlcThreeLiveSoftwareOne =
                MODE_POST_VLC_THREE_LIVE_SOFTWARE_ONE_NO_RF.equals(mode);
        boolean ackPacedVlcSoftwareOne =
                MODE_ACK_PACED_VLC_SOFTWARE_ONE_NO_RF.equals(mode);
        boolean ackPacedVlcSoftwareTripleSos =
                usesAckPacedTripleSos(mode);
        boolean ackPacedVlcSoftware = ackPacedVlcSoftwareOne
                || ackPacedVlcSoftwareTripleSos;
        boolean speechAz09 = isSpeechAz09Mode(mode);
        boolean softwarePrivacyTripleSos =
                MODE_RELAY_SOFTWARE_PRIVACY_TRIPLE_SOS_NO_RF.equals(mode)
                || MODE_RELAY_SOFTWARE_PRIVACY_TRIPLE_SOS_LOW_POWER_RF
                        .equals(mode)
                || ackPacedVlcSoftwareTripleSos;
        boolean encodeDmrSilenceRelay =
                MODE_RELAY_ENCODE_DMR_SILENCE_NO_RF.equals(mode);
        boolean encodeDmrTone800Relay =
                MODE_RELAY_ENCODE_DMR_TONE800_NO_RF.equals(mode);
        boolean encodeDmrTone800ThreeRelay =
                MODE_RELAY_ENCODE_DMR_TONE800_THREE_NO_RF.equals(mode);
        boolean encodeDmrTone800FiveRelay =
                MODE_RELAY_ENCODE_DMR_TONE800_FIVE_NO_RF.equals(mode);
        boolean encodeDmrMorseUniqueRelay =
                MODE_RELAY_ENCODE_DMR_MORSE_UNIQUE_NO_RF.equals(mode);
        boolean encodeDmrMorseUniqueThreeRelay =
                MODE_RELAY_ENCODE_DMR_MORSE_UNIQUE_THREE_NO_RF.equals(mode);
        boolean encodeDmrMorseUniqueFiveRelay =
                MODE_RELAY_ENCODE_DMR_MORSE_UNIQUE_FIVE_NO_RF.equals(mode)
                || MODE_RELAY_ENCODE_DMR_MORSE_UNIQUE_FIVE_LOW_POWER_RF
                        .equals(mode);
        boolean encodeDmrRelay = encodeDmrSilenceRelay
                || encodeDmrTone800Relay
                || encodeDmrTone800ThreeRelay
                || encodeDmrTone800FiveRelay
                || encodeDmrMorseUniqueRelay
                || encodeDmrMorseUniqueThreeRelay
                || encodeDmrMorseUniqueFiveRelay;
        boolean fixedAssetThreeRelay =
                MODE_RELAY_FIXED_ASSET_THREE_NO_RF.equals(mode);
        boolean fixedAssetFiveRelay =
                MODE_RELAY_FIXED_ASSET_FIVE_NO_RF.equals(mode);
        boolean fixedAssetTwentyFiveRelay =
                MODE_RELAY_FIXED_ASSET_TWENTYFIVE_NO_RF.equals(mode);
        boolean relayOne = MODE_RELAY_ONE_NO_RF.equals(mode)
                || MODE_RELAY_FIXED_ASSET_NO_RF.equals(mode)
                || fixedAssetThreeRelay
                || fixedAssetFiveRelay
                || fixedAssetTwentyFiveRelay
                || software49bitRelay
                || softwarePrivacyMorse
                || softwarePrivacyTripleSos
                || encodeDmrRelay;
        boolean fixedAssetRelay = MODE_RELAY_FIXED_ASSET_NO_RF.equals(mode);
        if (!MODE_NO_RF.equals(mode) && !setup0Only && !clearOnly
                && !deadlineHandshakeOnly && !allowRf && !relayOne) {
            throw new IllegalArgumentException("设备模式或低功率许可参数无效");
        }
        sessionId = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS",
                Locale.US).format(new Date()) + (allowRf ? "_rf"
                : relayOne ? "_relay_no_rf"
                : deadlineHandshakeOnly ? "_deadline_no_rf"
                : setup0Only ? "_setup0"
                : clearOnly ? "_clear" : "_no_rf");
        File root = new File(context.getFilesDir(), "captures");
        if (!root.exists() && !root.mkdirs()) {
            throw new IOException("无法建立捕获根目录");
        }
        evidence = new EvidenceStore(root, sessionId);
        boolean success = false;
        String failure = "";
        try {
            PackageInfo packageInfo = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0);
            evidence.saveText("pre", "device_build",
                    "fingerprint=" + Build.FINGERPRINT + "\n"
                    + "model=" + Build.MODEL + "\n"
                    + "package=" + context.getPackageName() + "\n"
                    + "version_code=" + packageInfo.versionCode + "\n"
                    + "version_name=" + packageInfo.versionName + "\n"
                    + "mode=" + mode + "\n"
                    + "rf_allowed=" + allowRf + "\n");
            status("打开57600文本面");
            transport = new SerialTransport(new File(SERIAL), BAUD);
            transport.setTraceSink(this::recordSerialTrace);
            McuMemory.TextExchange textExchange = (command, timeoutMs) -> {
                byte[] response = transport.exchangeText(command, timeoutMs);
                evidence.saveEvent("text", "response", response);
                evidence.saveText("text", "command", command + "\n");
                return response;
            };
            memory = new McuMemory(textExchange,
                    (command, expectedLength, timeoutMs) -> {
                byte[] response = transport.exchangeMemread(command,
                        expectedLength, timeoutMs);
                evidence.saveEvent("text", "response", response);
                evidence.saveText("text", "command", command + "\n");
                return response;
            }, attempt -> evidence.saveReadAttempt(attempt));
            debugOutput = new McuDebugOutput(textExchange);
            verifyTextBaseline();
            verifyFirmwareSlices();
            byte[] runtime14 = preparePrivacySessionAndMeasureRuntime();
            if (clearOnly) {
                restorePrivacyOff();
                verifyPostRestoreText();
                disableDebugOutputAndVerify();
                success = !privacyMutation && textRecoveryConfirmed
                        && debugOutput.restored();
            } else {
                DmrProtocol.Session session = DmrProtocol.session(13, 99, 0,
                        runtime14);
                TxStateMachine machine;
                if (relayOne) {
                    machine = new TxStateMachine(session, true, allowRf,
                            postVlcThreeLiveSoftwareOne
                                    || ackPacedVlcSoftware);
                } else {
                    byte[] encoded = encodeAndVerifyAllVectors();
                    machine = new TxStateMachine(session,
                            TxPlan.create(encoded));
                }
                activeMachine = machine;

                byte[] software49Bit36 = null;
                String software49BitSource = null;
                boolean softwareFinalWirePayload = false;
                if (speechAz09) {
                    // 人声素材：26 字母加 10 数字逐个朗读，28.80 秒、1440 帧。
                    // 与摩尔斯分支并列，不复用其帧数与单元数的固定判定。
                    status("人声字母数字素材经软件AMBE编解码闭环");
                    byte[] inputPcmRaw = readAll(assets.open(
                            "software_ambe_vectors/speech_az09.pcm_s16le"));
                    short[] inputPcm = TxPlan.decodePcmS16Le(inputPcmRaw);
                    byte[] goldenChannel72 = readAll(assets.open(
                            "software_ambe_vectors/speech_az09.ambe9_sequence.bin"));
                    long encodeStarted = System.nanoTime();
                    byte[] actualChannel72;
                    try (SoftwareAmbeEncoder encoder =
                            new SoftwareAmbeEncoder()) {
                        actualChannel72 = encoder.encode(inputPcm);
                    }
                    long encodeElapsedUs = (System.nanoTime() - encodeStarted)
                            / 1000L;
                    if (!Arrays.equals(actualChannel72, goldenChannel72)) {
                        throw new IOException("人声素材软件AMBE输出不匹配冻结向量");
                    }
                    if (actualChannel72.length != RealtimeRelay.SPEECH_AZ09_BYTES) {
                        throw new IOException("人声素材帧流长度错误："
                                + actualChannel72.length);
                    }
                    SoftwareDmrPrivacyPipeline.Result pipeline =
                            SoftwareDmrPrivacyPipeline.buildFromClearChannel72(
                                    runtime14, actualChannel72);
                    if (pipeline.frames() != RealtimeRelay.SPEECH_AZ09_FRAMES) {
                        throw new IOException("人声素材帧数错误："
                                + pipeline.frames());
                    }
                    software49Bit36 = pipeline.channel72;
                    software49BitSource = RELAY_SOURCE_SPEECH_AZ09;
                    softwareFinalWirePayload = true;
                    evidence.saveEvent("ambe", "speech_az09_input_pcm",
                            inputPcmRaw);
                    evidence.saveEvent("ambe", "speech_az09_channel72",
                            pipeline.channel72);
                    evidence.saveText("ambe", "speech_az09_summary",
                            "frames=" + pipeline.frames() + "\n"
                            + "channel72_bytes="
                            + pipeline.channel72.length + "\n"
                            + "encode_elapsed_us=" + encodeElapsedUs + "\n"
                            + "seconds=" + (pipeline.frames() * 0.02) + "\n");
                } else if (softwarePrivacyMorse || softwarePrivacyTripleSos) {
                    int repetitions = softwarePrivacyTripleSos ? 3 : 1;
                    status(repetitions == 3
                            ? "三遍完整SOS经软件AMBE编解码闭环"
                            : "冻结摩尔斯PCM经软件AMBE编解码闭环");
                    byte[] inputPcmRaw = readAll(assets.open(
                            "software_ambe_vectors/morse_sos.pcm_s16le"));
                    short[] fullInputPcm = TxPlan.decodePcmS16Le(inputPcmRaw);
                    byte[] goldenChannel72 = readAll(assets.open(
                            "software_ambe_vectors/morse_sos.ambe9_sequence.bin"));
                    short[] inputPcm = repeat(fullInputPcm, repetitions);
                    byte[] actualChannel72 = new byte[
                            goldenChannel72.length * repetitions];
                    long encodeStarted = System.nanoTime();
                    for (int repetition = 0; repetition < repetitions;
                            repetition++) {
                        byte[] encoded;
                        try (SoftwareAmbeEncoder encoder =
                                new SoftwareAmbeEncoder()) {
                            encoded = encoder.encode(fullInputPcm);
                        }
                        if (!Arrays.equals(encoded, goldenChannel72)) {
                            throw new IOException("第" + (repetition + 1)
                                    + "遍SOS软件AMBE输出不匹配冻结黄金向量");
                        }
                        System.arraycopy(encoded, 0, actualChannel72,
                                repetition * encoded.length, encoded.length);
                    }
                    long encodeElapsedUs = (System.nanoTime() - encodeStarted)
                            / 1000L;
                    if (actualChannel72.length != repetitions * 104 * 9) {
                        throw new IOException("摩尔斯软件AMBE输出不匹配冻结黄金向量");
                    }
                    byte[] clearChannel72 = softwarePrivacyTripleSos
                            ? actualChannel72
                            : Arrays.copyOf(actualChannel72,
                                    RealtimeRelay.SPEECH_BYTES);
                    if (!softwarePrivacyTripleSos) {
                        inputPcm = Arrays.copyOf(fullInputPcm,
                                RealtimeRelay.SPEECH_UNITS * 4
                                        * TxPlan.PCM_SAMPLES_PER_FRAME);
                    }
                    SoftwareDmrPrivacyPipeline.Result pipeline =
                            SoftwareDmrPrivacyPipeline.buildFromClearChannel72(
                                    runtime14, clearChannel72);
                    int expectedFrames = softwarePrivacyTripleSos ? 312 : 100;
                    int expectedUnits = softwarePrivacyTripleSos ? 78 : 25;
                    if (pipeline.frames() != expectedFrames
                            || pipeline.data36Units() != expectedUnits) {
                        throw new IOException("软件AMBE发送向量帧数错误");
                    }
                    if (softwarePrivacyTripleSos) {
                        requireTripleSosWireHashes(actualChannel72,
                                pipeline.channel72);
                    }
                    MorseMetrics morseMetrics = MorseMetrics.measure(inputPcm,
                            pipeline.decodedPcm);
                    software49Bit36 = pipeline.channel72;
                    software49BitSource = ackPacedVlcSoftwareOne
                            ? RELAY_SOURCE_ACK_PACED_SOFTWARE_MORSE
                            : ackPacedVlcSoftwareTripleSos
                            ? RELAY_SOURCE_ACK_PACED_SOFTWARE_TRIPLE_SOS
                            : postVlcThreeLiveSoftwareOne
                            ? RELAY_SOURCE_POST_VLC_SOFTWARE_MORSE
                            : softwarePrivacyTripleSos
                            ? RELAY_SOURCE_SOFTWARE_PRIVACY_TRIPLE_SOS
                            : RELAY_SOURCE_SOFTWARE_PRIVACY_MORSE;
                    if (postVlcThreeLiveSoftwareOne
                            || ackPacedVlcSoftwareOne) {
                        software49Bit36 = Arrays.copyOf(software49Bit36,
                                RealtimeRelay.PLAIN_BYTES);
                    }
                    softwareFinalWirePayload = true;
                    evidence.saveEvent("ambe", "software_morse_input_pcm_full_s16le",
                            inputPcmRaw);
                    evidence.saveEvent("ambe", "software_morse_input_pcm_sent_s16le",
                            SoftwareDmrPrivacyPipeline.pcmS16Le(inputPcm));
                    evidence.saveEvent("ambe", "software_morse_golden_channel72",
                            goldenChannel72);
                    evidence.saveEvent("ambe", "software_morse_actual_channel72",
                            actualChannel72);
                    evidence.saveEvent("ambe", "software_morse_clear_channel72_sent",
                            clearChannel72);
                    evidence.saveEvent("ambe", "software_morse_raw49",
                            pipeline.raw49);
                    evidence.saveEvent("ambe", "software_morse_privacy49",
                            pipeline.privacy49);
                    evidence.saveEvent("ambe", "software_morse_channel72_before_c3",
                            pipeline.channel72BeforeC3);
                    evidence.saveEvent("ambe", "software_morse_channel72",
                            pipeline.channel72);
                    evidence.saveEvent("ambe", "software_morse_receiver_privacy49",
                            pipeline.receiverPrivacy49);
                    evidence.saveEvent("ambe", "software_morse_receiver_plain49",
                            pipeline.receiverPlain49);
                    evidence.saveEvent("ambe", "software_morse_decoded_pcm_s16le",
                            SoftwareDmrPrivacyPipeline.pcmS16Le(
                                    pipeline.decodedPcm));
                    evidence.saveEvent("ambe", "software_morse_decode_errors_i32le",
                            intArrayLe(pipeline.decodeErrors));
                    evidence.saveEvent("ambe", "software_morse_channel_hamming_i32le",
                            intArrayLe(pipeline.channelHamming));
                    evidence.saveText("ambe", "software_morse_audio_metrics",
                            pipeline.audioMetrics.report());
                    evidence.saveText("ambe", "software_morse_metrics",
                            morseMetrics.report());
                    evidence.saveText("ambe", "software_morse_pipeline",
                            "frames=" + pipeline.frames() + "\n"
                            + "data36_units=" + pipeline.data36Units() + "\n"
                            + "sos_repetitions=" + repetitions + "\n"
                            + "audio_duration_ms="
                            + (inputPcm.length * 1000L / 8000L) + "\n"
                            + "software_encoder=project_mbelib_ambe\n"
                            + "software_decoder=project_mbelib\n"
                            + "h13_hardware_ambe_used=false\n"
                            + "full_pcm_sha256=" + Bytes.sha256(inputPcmRaw) + "\n"
                            + "full_golden_channel72_sha256="
                            + Bytes.sha256(goldenChannel72) + "\n"
                            + "full_actual_channel72_sha256="
                            + Bytes.sha256(actualChannel72) + "\n"
                            + "encode_elapsed_us=" + encodeElapsedUs + "\n"
                            + "raw49_sha256=" + Bytes.sha256(pipeline.raw49) + "\n"
                            + "privacy49_sha256=" + Bytes.sha256(pipeline.privacy49) + "\n"
                            + "channel72_before_c3_sha256="
                            + Bytes.sha256(pipeline.channel72BeforeC3) + "\n"
                            + "channel72_sha256=" + Bytes.sha256(pipeline.channel72) + "\n"
                            + "receiver_plain49_sha256="
                            + Bytes.sha256(pipeline.receiverPlain49) + "\n"
                            + "decoded_pcm_sha256=" + Bytes.sha256(
                            SoftwareDmrPrivacyPipeline.pcmS16Le(
                                    pipeline.decodedPcm)) + "\n"
                            + "decode_error_sum="
                            + sum(pipeline.decodeErrors) + "\n"
                            + "channel_hamming_sum="
                            + sum(pipeline.channelHamming) + "\n"
                            + "decoder=software_mbelib_49bit\n");
                    if (!softwarePrivacyTripleSos) {
                        morseMetrics.requireRecognizableSos();
                    }
                    if (softwarePrivacyTripleSos) {
                        for (int repetition = 0; repetition < 3;
                                repetition++) {
                            int start = repetition * fullInputPcm.length;
                            int end = start + fullInputPcm.length;
                            MorseMetrics segmentMetrics = MorseMetrics.measure(
                                    fullInputPcm, Arrays.copyOfRange(
                                            pipeline.decodedPcm, start, end));
                            evidence.saveText("ambe", "software_morse_segment_"
                                    + (repetition + 1) + "_metrics",
                                    segmentMetrics.report());
                        }
                    }
                } else if (fixedAssetTwentyFiveRelay) {
                    status("冻结CHAN_D unit36起900字节二十五单元");
                    byte[] asset = readAll(assets.open(
                            "v090_chan_d_244units.bin"));
                    software49Bit36 = RealtimeRelay.requireFrozenChanD900(
                            asset);
                    software49BitSource = RELAY_SOURCE_FIXED_ASSET_TWENTYFIVE;
                    evidence.saveEvent("relay", "fixed_asset_complete", asset);
                    evidence.saveEvent("relay", "fixed_asset_plain900",
                            software49Bit36);
                    for (int index = 0; index < RealtimeRelay.SPEECH_UNITS;
                            index++) {
                        evidence.saveEvent("relay", String.format(Locale.US,
                                "fixed_asset_unit%02d", index),
                                Arrays.copyOfRange(software49Bit36,
                                        index * 36, (index + 1) * 36));
                    }
                } else if (fixedAssetFiveRelay) {
                    status("冻结CHAN_D连续180字节五单元");
                    byte[] asset = readAll(assets.open(
                            "v090_chan_d_244units.bin"));
                    software49Bit36 = RealtimeRelay.requireFrozenChanD180(
                            asset);
                    software49BitSource = RELAY_SOURCE_FIXED_ASSET_FIVE;
                    evidence.saveEvent("relay", "fixed_asset_complete", asset);
                    evidence.saveEvent("relay", "fixed_asset_plain180",
                            software49Bit36);
                    evidence.saveEvent("relay", "fixed_asset_unit0",
                            Arrays.copyOf(software49Bit36, 36));
                    evidence.saveEvent("relay", "fixed_asset_unit1",
                            Arrays.copyOfRange(software49Bit36, 36, 72));
                    evidence.saveEvent("relay", "fixed_asset_unit2",
                            Arrays.copyOfRange(software49Bit36, 72, 108));
                    evidence.saveEvent("relay", "fixed_asset_unit3",
                            Arrays.copyOfRange(software49Bit36, 108, 144));
                    evidence.saveEvent("relay", "fixed_asset_unit4",
                            Arrays.copyOfRange(software49Bit36, 144, 180));
                } else if (fixedAssetThreeRelay) {
                    status("冻结CHAN_D连续108字节三单元");
                    byte[] asset = readAll(assets.open(
                            "v090_chan_d_244units.bin"));
                    software49Bit36 = RealtimeRelay.requireFrozenChanD108(
                            asset);
                    software49BitSource = RELAY_SOURCE_FIXED_ASSET_THREE;
                    evidence.saveEvent("relay", "fixed_asset_complete", asset);
                    evidence.saveEvent("relay", "fixed_asset_plain108",
                            software49Bit36);
                    evidence.saveEvent("relay", "fixed_asset_unit0",
                            Arrays.copyOf(software49Bit36, 36));
                    evidence.saveEvent("relay", "fixed_asset_unit1",
                            Arrays.copyOfRange(software49Bit36, 36, 72));
                    evidence.saveEvent("relay", "fixed_asset_unit2",
                            Arrays.copyOfRange(software49Bit36, 72, 108));
                } else if (encodeDmrTone800FiveRelay) {
                    status("冻结encode_dmr 800赫兹连续180字节五单元");
                    byte[] ambe9 = readAll(assets.open(
                            "software_ambe_vectors/tone_800hz.ambe9_sequence.bin"));
                    software49Bit36 = RealtimeRelay
                            .requireFrozenEncodeDmrTone800180(ambe9);
                    software49BitSource = RELAY_SOURCE_ENCODE_DMR_TONE800_FIVE;
                    evidence.saveEvent("ambe", "encode_dmr_tone800_ambe9",
                            ambe9);
                    evidence.saveEvent("relay", "encode_dmr_tone800_plain180",
                            software49Bit36);
                    evidence.saveEvent("relay", "encode_dmr_tone800_unit0",
                            Arrays.copyOf(software49Bit36, 36));
                    evidence.saveEvent("relay", "encode_dmr_tone800_unit1",
                            Arrays.copyOfRange(software49Bit36, 36, 72));
                    evidence.saveEvent("relay", "encode_dmr_tone800_unit2",
                            Arrays.copyOfRange(software49Bit36, 72, 108));
                    evidence.saveEvent("relay", "encode_dmr_tone800_unit3",
                            Arrays.copyOfRange(software49Bit36, 108, 144));
                    evidence.saveEvent("relay", "encode_dmr_tone800_unit4",
                            Arrays.copyOfRange(software49Bit36, 144, 180));
                } else if (encodeDmrTone800ThreeRelay) {
                    status("冻结encode_dmr 800赫兹连续108字节三单元");
                    byte[] ambe9 = readAll(assets.open(
                            "software_ambe_vectors/tone_800hz.ambe9_sequence.bin"));
                    software49Bit36 = RealtimeRelay
                            .requireFrozenEncodeDmrTone800108(ambe9);
                    software49BitSource = RELAY_SOURCE_ENCODE_DMR_TONE800_THREE;
                    evidence.saveEvent("ambe", "encode_dmr_tone800_ambe9",
                            ambe9);
                    evidence.saveEvent("relay", "encode_dmr_tone800_plain108",
                            software49Bit36);
                    evidence.saveEvent("relay", "encode_dmr_tone800_unit0",
                            Arrays.copyOf(software49Bit36, 36));
                    evidence.saveEvent("relay", "encode_dmr_tone800_unit1",
                            Arrays.copyOfRange(software49Bit36, 36, 72));
                    evidence.saveEvent("relay", "encode_dmr_tone800_unit2",
                            Arrays.copyOfRange(software49Bit36, 72, 108));
                } else if (encodeDmrMorseUniqueFiveRelay) {
                    status("冻结encode_dmr摩尔斯独特unit1起连续180字节五单元");
                    byte[] ambe9 = readAll(assets.open(
                            "software_ambe_vectors/morse_sos.ambe9_sequence.bin"));
                    software49Bit36 = RealtimeRelay
                            .requireFrozenEncodeDmrMorseUnique180(ambe9);
                    software49BitSource =
                            RELAY_SOURCE_ENCODE_DMR_MORSE_UNIQUE_FIVE;
                    evidence.saveEvent("ambe", "encode_dmr_morse_ambe9",
                            ambe9);
                    evidence.saveEvent("relay", "encode_dmr_morse_unique180",
                            software49Bit36);
                    evidence.saveEvent("relay", "encode_dmr_morse_unique_unit1",
                            Arrays.copyOf(software49Bit36, 36));
                    evidence.saveEvent("relay", "encode_dmr_morse_unique_unit2",
                            Arrays.copyOfRange(software49Bit36, 36, 72));
                    evidence.saveEvent("relay", "encode_dmr_morse_unique_unit3",
                            Arrays.copyOfRange(software49Bit36, 72, 108));
                    evidence.saveEvent("relay", "encode_dmr_morse_unique_unit4",
                            Arrays.copyOfRange(software49Bit36, 108, 144));
                    evidence.saveEvent("relay", "encode_dmr_morse_unique_unit5",
                            Arrays.copyOfRange(software49Bit36, 144, 180));
                } else if (encodeDmrMorseUniqueThreeRelay) {
                    status("冻结encode_dmr摩尔斯独特unit1起连续108字节三单元");
                    byte[] ambe9 = readAll(assets.open(
                            "software_ambe_vectors/morse_sos.ambe9_sequence.bin"));
                    software49Bit36 = RealtimeRelay
                            .requireFrozenEncodeDmrMorseUnique108(ambe9);
                    software49BitSource =
                            RELAY_SOURCE_ENCODE_DMR_MORSE_UNIQUE_THREE;
                    evidence.saveEvent("ambe", "encode_dmr_morse_ambe9",
                            ambe9);
                    evidence.saveEvent("relay", "encode_dmr_morse_unique108",
                            software49Bit36);
                    evidence.saveEvent("relay", "encode_dmr_morse_unique_unit1",
                            Arrays.copyOf(software49Bit36, 36));
                    evidence.saveEvent("relay", "encode_dmr_morse_unique_unit2",
                            Arrays.copyOfRange(software49Bit36, 36, 72));
                    evidence.saveEvent("relay", "encode_dmr_morse_unique_unit3",
                            Arrays.copyOfRange(software49Bit36, 72, 108));
                } else if (encodeDmrMorseUniqueRelay) {
                    status("冻结encode_dmr摩尔斯独特unit1共36字节");
                    byte[] ambe9 = readAll(assets.open(
                            "software_ambe_vectors/morse_sos.ambe9_sequence.bin"));
                    software49Bit36 = RealtimeRelay
                            .requireFrozenEncodeDmrMorseUnique36(ambe9);
                    software49BitSource = RELAY_SOURCE_ENCODE_DMR_MORSE_UNIQUE;
                    evidence.saveEvent("ambe", "encode_dmr_morse_ambe9",
                            ambe9);
                    evidence.saveEvent("relay", "encode_dmr_morse_unique36",
                            software49Bit36);
                } else if (encodeDmrTone800Relay) {
                    status("冻结encode_dmr 800赫兹前36字节");
                    byte[] ambe9 = readAll(assets.open(
                            "software_ambe_vectors/tone_800hz.ambe9_sequence.bin"));
                    software49Bit36 = RealtimeRelay
                            .requireFrozenEncodeDmrTone80036(ambe9);
                    software49BitSource = RELAY_SOURCE_ENCODE_DMR_TONE800;
                    evidence.saveEvent("ambe", "encode_dmr_tone800_ambe9",
                            ambe9);
                    evidence.saveEvent("relay", "encode_dmr_tone800_plain36",
                            software49Bit36);
                } else if (encodeDmrSilenceRelay) {
                    status("冻结encode_dmr静音前36字节");
                    byte[] ambe9 = readAll(assets.open(
                            "software_ambe_vectors/silence.ambe9_sequence.bin"));
                    software49Bit36 = RealtimeRelay
                            .requireFrozenEncodeDmrSilence36(ambe9);
                    software49BitSource = RELAY_SOURCE_ENCODE_DMR_SILENCE;
                    evidence.saveEvent("ambe", "encode_dmr_silence_ambe9",
                            ambe9);
                    evidence.saveEvent("relay", "encode_dmr_silence_plain36",
                            software49Bit36);
                } else if (software49bitMorse) {
                    status("软件49位装箱摩尔斯从第4帧起4帧");
                    software49Bit36 = encodeSoftware49BitFirst36(
                            "morse_sos", "morse_frames_3_to_6",
                            MORSE_49BIT_START_FRAME,
                            SILENCE_49BIT_36_SHA256, TONE800_49BIT_36_SHA256);
                    software49BitSource = RELAY_SOURCE_SOFTWARE_49BIT_MORSE;
                } else if (software49bitTone800) {
                    status("软件49位装箱800赫兹前4帧");
                    software49Bit36 = encodeSoftware49BitFirst36(
                            "tone_800hz", "800hz_first_640_samples", 0,
                            SILENCE_49BIT_36_SHA256);
                    software49BitSource = RELAY_SOURCE_SOFTWARE_49BIT_TONE800;
                } else if (software49bitRelay) {
                    status("软件49位装箱静音前4帧");
                    software49Bit36 = encodeSoftware49BitFirst36(
                            "silence", "silence_first_640_samples", 0);
                    software49BitSource = RELAY_SOURCE_SOFTWARE_49BIT;
                }

                status("保存全部SRAM真实原像");
                backupAllTouchedSram();
                disablePowerSaveAndVerify();
                runFirstNoRfBridge(machine, runtime14, setup0Only, relayOne,
                        fixedAssetRelay, software49Bit36, software49BitSource,
                        softwareFinalWirePayload, allowRf,
                        postVlcThreeLiveSoftwareOne,
                        ackPacedVlcSoftware,
                        ackPacedVlcSoftwareTripleSos,
                        voiceFormatForMode(mode));
                if (allowRf) {
                    status("第一桥射频窗已关闭，禁止第二桥");
                    if (secondBridgeExitConfirmed || !rfPrepareExecuted
                            || !rfOffConfirmed || rfMayBeActive) {
                        throw new IOException("第一桥射频窗关闭证据不成立");
                    }
                } else if (deadlineHandshakeOnly) {
                    status("第一桥已验收，验证截止器延后武装");
                    requestAndRequireDeviceDeadlineAfterFirstBridge();
                    machine.completeNoRfAfterFirstBridge();
                } else {
                    machine.completeNoRfAfterFirstBridge();
                }
                status("恢复SRAM真实原像");
                sram.restoreAll();
                captureRfEdgeCounter("恢复后");
                restorePrivacyOff();
                verifyPostRestoreText();
                restorePowerSavePreimage();
                disableDebugOutputAndVerify();
                if (allowRf) {
                    publishRfReleaseAfterRestore();
                }
                success = machine.phase() == TxStateMachine.Phase.COMPLETE
                        && sram.restored() && !privacyMutation
                        && !rfMayBeActive && textRecoveryConfirmed
                        && debugOutput.restored() && powerSaveWasDisabled
                        && powerSaveRestored
                        && (!allowRf || (rfPrepareExecuted && rfOffConfirmed
                        && !secondBridgeExitConfirmed
                        && deviceDeadlineArmRequested
                        && deviceDeadlineArmed))
                        && (!relayOne
                        || (activeRelay != null
                        && (!postVlcThreeLiveSoftwareOne
                        ? (ackPacedVlcSoftwareTripleSos
                        ? activeRelay.activePacedComplete()
                        : activeRelay.creditAccepted())
                        : postVlcObservationClass.length() > 0)
                        && activeRelay.data27Count()
                        >= activeRelay.requiredTriggerUnits()
                        && machine.relayComplete()
                        && machine.dataWritten()
                        == activeRelay.maximumUnits()));
            }
            if (!success) {
                throw new IOException("发送状态机未到完整结束态");
            }
        } catch (Throwable error) {
            failure = error.getClass().getSimpleName() + ":"
                    + String.valueOf(error.getMessage());
            if (evidence != null) {
                try {
                    flushRelayEvidence();
                    evidence.log("失败边界 " + failure);
                } catch (Throwable ignored) {
                }
            }
            recoverAfterFailure();
        } finally {
            closeTransport();
        }
        return finishResult(success, mode, failure);
    }

    private void verifyTextBaseline() throws Exception {
        byte[] connect = text("AT+DMOCONNECT", 1800);
        byte[] version = text("AT+DMOGETSOFTVERSION", 1800);
        if (!containsLine(connect, "+DMOCONNECT:0")
                || !containsLine(version,
                "+DMOGETSOFTVERSION:" + MODULE_VERSION)) {
            throw new IOException("模块57600文本基线失败");
        }
        debugOutput.enable();
        McuMemory.ReadResult flag = memory.read(McuAssets.BRIDGE_FLAG, 1);
        McuMemory.ReadResult vector = memory.read(McuAssets.VECTOR, 4);
        saveRead("baseline_bridge", flag);
        saveRead("baseline_vector", vector);
        if ((flag.parsed[0] & 0xff) != 0
                || Bytes.u32le(vector.parsed, 0)
                != TxStateMachine.ORIGINAL_SYSTICK) {
            throw new IOException("bridge或SysTick不是生产基线");
        }
    }

    private byte[] preparePrivacySessionAndMeasureRuntime() throws Exception {
        byte[] channel = text("AT+DMOGETDIGITALCH", 1800);
        if (!containsLine(channel, "+DMOGETDIGITALCH:" + CHANNEL_OFF)) {
            privacyMutation = true;
            requireLine(text("AT+DMOSETDIGITALCH=" + CHANNEL_OFF, 1800),
                    "+DMOSETDIGITALCH:0", "privacy-off基线设置失败");
        }
        requireLine(text("AT+DMOGETDIGITALCH", 1800),
                "+DMOGETDIGITALCH:" + CHANNEL_OFF,
                "privacy-off频道回读失败");
        byte[][] offRounds = captureRuntimeRounds("clear_channel");
        privacyOffBaseline14 = requireStableRuntime(offRounds,
                "明文频道基线");
        evidence.saveEvent("privacy", "clear_channel_runtime14",
                privacyOffBaseline14);

        // External DMR控制链的历史真机基线要求privacy-on运行态。
        // AMBE正文仍由TxPlan原样发送，不在主机侧做后置异或。
        privacyMutation = true;
        requireLine(text("AT+DMOSETDIGITALCH=" + CHANNEL_ON, 1800),
                "+DMOSETDIGITALCH:0", "privacy-on会话设置失败");
        requireLine(text("AT+DMOGETDIGITALCH", 1800),
                "+DMOGETDIGITALCH:" + CHANNEL_ON,
                "privacy-on会话频道回读失败");
        byte[][] onRounds = captureRuntimeRounds("privacy_on");
        byte[] runtime14 = requireStableRuntime(onRounds,
                "privacy-on会话运行态");
        evidence.saveEvent("privacy", "privacy_on_runtime14", runtime14);
        return runtime14;
    }

    private void verifyFirmwareSlices() throws Exception {
        if (!firmwareContractWellFormed()) {
            throw new IOException("固件切片静态合同损坏");
        }
        byte[][] slices = new byte[FIRMWARE_SLICE_ADDRESSES.length][];
        for (int index = 0; index < slices.length; index++) {
            slices[index] = read("firmware_slice_" + index,
                    FIRMWARE_SLICE_ADDRESSES[index],
                    FIRMWARE_SLICE_LENGTHS[index]);
            if (!FIRMWARE_SLICE_SHA256[index].equals(
                    Bytes.sha256(slices[index]))) {
                throw new IOException("固件切片哈希不匹配 index=" + index);
            }
        }
        if (!FIRMWARE_SLICES_COMBINED_SHA256.equals(
                Bytes.sha256(join(slices)))) {
            throw new IOException("固件切片合并哈希不匹配");
        }
    }

    static boolean firmwareContractWellFormed() {
        if (FIRMWARE_SLICE_ADDRESSES.length != FIRMWARE_SLICE_LENGTHS.length
                || FIRMWARE_SLICE_ADDRESSES.length
                != FIRMWARE_SLICE_SHA256.length
                || !FIRMWARE_SLICES_COMBINED_SHA256.matches("[0-9A-F]{64}")) {
            return false;
        }
        for (int index = 0; index < FIRMWARE_SLICE_ADDRESSES.length; index++) {
            if (FIRMWARE_SLICE_LENGTHS[index] <= 0
                    || !FIRMWARE_SLICE_SHA256[index].matches("[0-9A-F]{64}")) {
                return false;
            }
        }
        return true;
    }

    static boolean firstBridgeBudgetWellFormed() {
        // 每次交换包含300毫秒静默门、500毫秒确认窗、400毫秒迟到窗，
        // 再为原始证据同步落盘预留100毫秒；setup0最多额外重试一次。
        long guardedExchangeMs = 1300;
        long setupWorstMs = POST_BRIDGE_SETTLE_MS + guardedExchangeMs * 6;
        long vlcWorstEndMs = FULLPREP_DELAY_MS
                + guardedExchangeMs * DmrProtocol.VLC_COUNT;
        return setupWorstMs <= FULLPREP_DELAY_MS - 1000
                && vlcWorstEndMs <= FIRST_BRIDGE_EXIT_MS
                - FIRST_BRIDGE_MARGIN_MS;
    }

    static boolean firstBridgeRelayBudgetWellFormed() {
        // 三遍SOS采用信用背压和相邻写出至少80毫秒；正文连续完成后
        // 再执行其余VLC，保留完整播放窗，并另计末包信用和终止VLC。
        long relayVlcExchangeMs = 300 + RELAY_VLC_ACK_DURATION_MS;
        long terminationExchangeMs = 300 + 500;
        long relayWorstEndMs = FULLPREP_DELAY_MS
                + RELAY_CREDIT_TIMEOUT_MS
                + (RealtimeRelay.TRIPLE_SOS_UNITS - 1L)
                * TxPlan.UNIT_INTERVAL_MS
                + RELAY_CREDIT_TIMEOUT_MS
                + relayVlcExchangeMs * 3
                + TRIPLE_SOS_PLAYOUT_MS
                + terminationExchangeMs;
        return firstBridgeBudgetWellFormed()
                && RELAY_VLC_ACK_DURATION_MS == 500
                && relayWorstEndMs <= FIRST_BRIDGE_EXIT_MS
                - FIRST_BRIDGE_MARGIN_MS;
    }

    static boolean ackPacedActiveBudgetWellFormed() {
        long ackPacedVlcWorstMs = RELAY_VLC_ACK_DURATION_MS
                * DmrProtocol.VLC_COUNT;
        long bodyWorstMs = (RealtimeRelay.TRIPLE_SOS_UNITS - 1L)
                * TxPlan.UNIT_INTERVAL_MS + TxPlan.MAX_LATE_MS;
        long terminationWorstMs = 300L + 500L;
        long worstEndMs = FULLPREP_DELAY_MS + ackPacedVlcWorstMs
                + bodyWorstMs + ACTIVE_PACED_TAIL_MS + terminationWorstMs;
        return firstBridgeBudgetWellFormed()
                && ACTIVE_PACED_TAIL_MS == 500L
                && worstEndMs <= FIRST_BRIDGE_EXIT_MS
                - FIRST_BRIDGE_MARGIN_MS;
    }

    static boolean ackPacedActiveRfBudgetWellFormed() {
        long cleanupMs = 1300L * DmrProtocol.CLEANUP_COUNT;
        long ackPacedVlcWorstMs = RELAY_VLC_ACK_DURATION_MS
                * DmrProtocol.VLC_COUNT;
        long bodyWorstMs = (RealtimeRelay.TRIPLE_SOS_UNITS - 1L)
                * TxPlan.UNIT_INTERVAL_MS + TxPlan.MAX_LATE_MS;
        long terminationWorstMs = 300L + 500L;
        long worstEndMs = FULLPREP_DELAY_MS + ackPacedVlcWorstMs
                + bodyWorstMs + ACTIVE_PACED_TAIL_MS
                + terminationWorstMs + cleanupMs;
        return ackPacedActiveBudgetWellFormed()
                && DmrProtocol.CLEANUP_COUNT == 2
                && worstEndMs <= FIRST_BRIDGE_EXIT_MS
                - FIRST_BRIDGE_MARGIN_MS
                && FULLPREP_DELAY_MS + RF_HOLD_MS > worstEndMs;
    }

    static boolean usesAckPacedTripleSos(String mode) {
        // 三个语音突发验证模式共用这条 v0.79 已验证的主动节拍供数路径，
        // 唯一差别是语音单元的线上格式，由 voiceFormatForMode 决定。
        return MODE_ACK_PACED_VLC_SOFTWARE_TRIPLE_SOS_NO_RF.equals(mode)
                || MODE_RELAY_SOFTWARE_PRIVACY_TRIPLE_SOS_LOW_POWER_RF
                        .equals(mode)
                || isVoiceBurstMode(mode)
                || isSpeechAz09Mode(mode);
    }

    static boolean requestsLowPowerRf(String mode) {
        return MODE_RELAY_SOFTWARE_PRIVACY_TRIPLE_SOS_LOW_POWER_RF
                .equals(mode);
    }

    static long activePacedTargetAt(long firstFlushAt, int unitIndex) {
        return activePacedTargetAt(firstFlushAt, unitIndex,
                DmrProtocol.VoiceFormat.LEGACY_CHAN_D36,
                RealtimeRelay.TRIPLE_SOS_UNITS);
    }

    /**
     * 按会话格式计算第 unitIndex 包的绝对节拍目标时刻。
     * 历史格式为每包 80 毫秒共 78 包，语音突发格式为每包 60 毫秒共 104 包，
     * 两者总时长相同。
     */
    static long activePacedTargetAt(long firstFlushAt, int unitIndex,
            DmrProtocol.VoiceFormat format, int maximumUnits) {
        if (firstFlushAt <= 0 || unitIndex <= 0
                || unitIndex >= maximumUnits) {
            throw new IllegalArgumentException("主动绝对节拍参数无效");
        }
        return firstFlushAt + unitIndex * TxPlan.unitIntervalMs(format);
    }

    static boolean firstBridgeRfCleanupBudgetWellFormed() {
        // 三遍数据、末包信用、vlc2-4、完整播放窗、终止VLC和两帧收尾
        // 均须位于边距前；RF硬关闭仍由独立16秒保持计数控制。
        long guardedExchangeMs = 1300;
        long cleanupMs = guardedExchangeMs * DmrProtocol.CLEANUP_COUNT;
        long relayVlcExchangeMs = 300 + RELAY_VLC_ACK_DURATION_MS;
        long terminationExchangeMs = 300 + 500;
        long tripleSosWorstEndMs = FULLPREP_DELAY_MS
                + RELAY_CREDIT_TIMEOUT_MS
                + (RealtimeRelay.TRIPLE_SOS_UNITS - 1L)
                * TxPlan.UNIT_INTERVAL_MS
                + RELAY_CREDIT_TIMEOUT_MS
                + relayVlcExchangeMs * 3
                + TRIPLE_SOS_PLAYOUT_MS
                + terminationExchangeMs
                + cleanupMs;
        return firstBridgeRelayBudgetWellFormed()
                && DmrProtocol.CLEANUP_COUNT == 2
                && tripleSosWorstEndMs <= FIRST_BRIDGE_EXIT_MS
                - FIRST_BRIDGE_MARGIN_MS;
    }

    static boolean rfHoldWindowWellFormed() {
        // RF从fullprep完成后开始；16秒保持窗必须在第一桥自动退出前
        // 至少提前1秒触发桥内原厂关断。
        return RF_HOLD_MS == 16000
                && FULLPREP_DELAY_MS + RF_HOLD_MS
                <= FIRST_BRIDGE_EXIT_MS - 1000L
                && RF_HOLD_MS < TxStateMachine.WATCHDOG_MS;
    }

    static boolean isEncodeDmrRelaySource(String source) {
        return RELAY_SOURCE_ENCODE_DMR_SILENCE.equals(source)
                || RELAY_SOURCE_ENCODE_DMR_TONE800.equals(source)
                || RELAY_SOURCE_ENCODE_DMR_TONE800_THREE.equals(source)
                || RELAY_SOURCE_ENCODE_DMR_TONE800_FIVE.equals(source)
                || RELAY_SOURCE_ENCODE_DMR_MORSE_UNIQUE.equals(source)
                || RELAY_SOURCE_ENCODE_DMR_MORSE_UNIQUE_THREE.equals(source)
                || RELAY_SOURCE_ENCODE_DMR_MORSE_UNIQUE_FIVE.equals(source);
    }

    static boolean secondBridgeBudgetWellFormed() {
        long lastScheduledUnitMs = (TxPlan.EXPECTED_UNIT_COUNT - 1L)
                * TxPlan.UNIT_INTERVAL_MS;
        return lastScheduledUnitMs + TxPlan.MAX_LATE_MS + 500
                < SECOND_BRIDGE_EXIT_MS;
    }

    private byte[][] captureRuntimeRounds(String label) throws Exception {
        byte[][] rounds = new byte[3][];
        for (int round = 0; round < rounds.length; round++) {
            McuMemory.ReadResult flags = memory.read(McuAssets.SIGNALING_FLAGS, 4);
            McuMemory.ReadResult gate = memory.read(McuAssets.DATA_GATE, 1);
            McuMemory.ReadResult record = memory.read(McuAssets.SESSION_RECORD,
                    McuAssets.SESSION_RECORD_LENGTH);
            saveRead(label + "_flags_" + round, flags);
            saveRead(label + "_gate_" + round, gate);
            saveRead(label + "_record_" + round, record);
            rounds[round] = join(flags.parsed, gate.parsed, record.parsed);
            evidence.saveEvent("privacy", label + "_runtime14_" + round,
                    rounds[round]);
            if (round + 1 < rounds.length) {
                SystemClock.sleep(200);
            }
        }
        return rounds;
    }

    private byte[] encodeSoftware49BitFirst36(String fileBase, String origin,
            int startFrame, String... forbiddenSha256) throws Exception {
        byte[] pcmRaw;
        try (InputStream pcm = assets.open(
                "software_ambe_vectors/" + fileBase + ".pcm_s16le")) {
            pcmRaw = readAll(pcm);
        }
        short[] pcm = TxPlan.decodePcmS16Le(pcmRaw);
        int neededSamples = TxPlan.PCM_SAMPLES_PER_FRAME
                * SOFTWARE_49BIT_FRAMES;
        int startSample = startFrame * TxPlan.PCM_SAMPLES_PER_FRAME;
        if (startFrame < 0 || pcm.length < startSample + neededSamples) {
            throw new IOException(fileBase + " PCM不足所选4帧，无法生成49位首包");
        }
        short[] prefix = Arrays.copyOfRange(pcm, startSample,
                startSample + neededSamples);
        byte[] packed = SoftwareAmbeEncoder.encode49BitPacked9(prefix);
        if (packed == null || packed.length != RealtimeRelay.PLAIN_BYTES) {
            throw new IOException("49位装箱长度不是36字节");
        }
        RealtimeRelay.requireChanDLastNibbleZero(packed);
        String sha = Bytes.sha256(packed);
        evidence.saveEvent("ambe", "software_49bit_" + fileBase
                + "_pcm_prefix", toS16Le(prefix));
        evidence.saveEvent("relay", "software_49bit_plain36", packed);
        evidence.saveText("relay", "software_49bit",
                "source=" + origin + "\n"
                + "pcm_file=" + fileBase + ".pcm_s16le\n"
                + "start_frame=" + startFrame + "\n"
                + "frames=" + SOFTWARE_49BIT_FRAMES + "\n"
                + "pcm_samples=" + neededSamples + "\n"
                + "plain36_sha256=" + sha + "\n"
                + "silence_36_sha256=" + SILENCE_49BIT_36_SHA256 + "\n"
                + "tone800_36_sha256=" + TONE800_49BIT_36_SHA256 + "\n"
                + "differs_from_silence="
                + !SILENCE_49BIT_36_SHA256.equals(sha) + "\n"
                + "differs_from_tone800="
                + !TONE800_49BIT_36_SHA256.equals(sha) + "\n"
                + "last_nibble_zero=true\n"
                + "encoder=set_49bit_mode+encode_49bit_packed9\n"
                + "encode_dmr_used=false\n");
        if (forbiddenSha256 != null) {
            for (String forbidden : forbiddenSha256) {
                if (forbidden != null && forbidden.equals(sha)) {
                    throw new IOException(
                            "49位装箱与已冻结对照首包相同，内容因果未形成 sha="
                            + sha);
                }
            }
        }
        return packed;
    }

    private void validateNaturalSoftwareDecoder() throws Exception {
        byte[] channel72 = readAll(assets.open("v090_chan_d_244units.bin"));
        if (channel72.length == 0 || channel72.length % 9 != 0) {
            throw new IOException("自然RX资产不是完整9字节帧序列");
        }
        byte[] packed49 = SoftwareAmbeDecoder
                .channelDecodeTo49BitPacked9(channel72);
        int[] hamming = SoftwareDmrPrivacyPipeline.channelHammingDistances(
                channel72, packed49, null);
        int exactFrames = 0;
        int maximumHamming = 0;
        for (int value : hamming) {
            if (value == 0) {
                exactFrames++;
            }
            maximumHamming = Math.max(maximumHamming, value);
        }
        evidence.saveEvent("ambe", "natural_rx_channel72", channel72);
        evidence.saveEvent("ambe", "natural_rx_packed49", packed49);
        evidence.saveEvent("ambe", "natural_rx_channel_hamming_i32le",
                intArrayLe(hamming));
        if (sum(hamming) != 1521 || maximumHamming != 4
                || exactFrames != 29) {
            throw new IOException("自然RX信道误码轮廓不匹配历史冻结证据 sum="
                    + sum(hamming) + " max=" + maximumHamming
                    + " exact=" + exactFrames);
        }
        SoftwareAmbeDecoder.DecodeResult decoded;
        try (SoftwareAmbeDecoder decoder = new SoftwareAmbeDecoder()) {
            decoded = decoder.decodeDetailed49BitPacked9(packed49);
        }
        if (decoded.pcm.length != channel72.length / 9 * 160) {
            throw new IOException("自然RX软件解码采样数错误");
        }
        short[] metricWindow = Arrays.copyOf(decoded.pcm,
                Math.min(decoded.pcm.length, 8000 * 4));
        AudioMetrics metrics = AudioMetrics.measure(metricWindow, 8000,
                800.0);
        if (metrics.rms < 20.0 || metrics.activeRatio < 0.01) {
            throw new IOException("自然RX软件解码阳性对照没有有效音频: "
                    + metrics.report());
        }
        evidence.saveEvent("ambe", "natural_rx_decoded_pcm_s16le",
                SoftwareDmrPrivacyPipeline.pcmS16Le(decoded.pcm));
        evidence.saveEvent("ambe", "natural_rx_decode_errors_i32le",
                intArrayLe(decoded.frameErrors));
        evidence.saveText("ambe", "natural_rx_software_decode",
                "frames=" + channel72.length / 9 + "\n"
                + "pcm_samples=" + decoded.pcm.length + "\n"
                + "channel72_sha256=" + Bytes.sha256(channel72) + "\n"
                + "packed49_sha256=" + Bytes.sha256(packed49) + "\n"
                + "pcm_sha256=" + Bytes.sha256(
                        SoftwareDmrPrivacyPipeline.pcmS16Le(decoded.pcm)) + "\n"
                + "decode_error_sum=" + sum(decoded.frameErrors) + "\n"
                + "channel_hamming_sum=" + sum(hamming) + "\n"
                + "channel_hamming_max=" + maximumHamming + "\n"
                + "channel_exact_frames=" + exactFrames + "\n"
                + metrics.report()
                + "decoder=software_mbelib_49bit\n"
                + "h13_hardware_decoder_used=false\n");
    }

    private static int sum(int[] values) {
        int result = 0;
        for (int value : values) {
            result += value;
        }
        return result;
    }

    private static byte[] intArrayLe(int[] values) {
        byte[] result = new byte[values.length * 4];
        for (int index = 0; index < values.length; index++) {
            int value = values[index];
            result[index * 4] = (byte) value;
            result[index * 4 + 1] = (byte) (value >>> 8);
            result[index * 4 + 2] = (byte) (value >>> 16);
            result[index * 4 + 3] = (byte) (value >>> 24);
        }
        return result;
    }

    static short[] repeat(short[] source, int repetitions) {
        if (source == null || source.length == 0 || repetitions <= 0) {
            throw new IllegalArgumentException("PCM重复参数无效");
        }
        short[] result = new short[source.length * repetitions];
        for (int repetition = 0; repetition < repetitions; repetition++) {
            System.arraycopy(source, 0, result,
                    repetition * source.length, source.length);
        }
        return result;
    }

    static void requireTripleSosWireHashes(byte[] clearChannel72,
            byte[] finalChannel72) {
        String clearHash = Bytes.sha256(clearChannel72);
        String finalHash = Bytes.sha256(finalChannel72);
        if (!TRIPLE_SOS_CLEAR_CHANNEL72_SHA256.equals(clearHash)
                || !TRIPLE_SOS_FINAL_CHANNEL72_SHA256.equals(finalHash)) {
            throw new IllegalStateException("三遍SOS确定性信道字节门失败：clear="
                    + clearHash + " final=" + finalHash);
        }
    }

    private static byte[] toS16Le(short[] samples) {
        byte[] raw = new byte[samples.length * 2];
        for (int index = 0; index < samples.length; index++) {
            raw[index * 2] = (byte) (samples[index] & 0xff);
            raw[index * 2 + 1] = (byte) ((samples[index] >> 8) & 0xff);
        }
        return raw;
    }

    private byte[] encodeAndVerifyAllVectors() throws Exception {
        VectorEncoding silence = encodeVectorMeasured("silence", "silence");
        VectorEncoding tone = encodeVectorMeasured("tone_800hz", "tone_800hz");
        VectorEncoding morse = encodeVectorMeasured("morse_sos", "morse_sos");
        int common = Math.min(silence.encoded.length, morse.encoded.length);
        boolean silenceToneDifferent = !Arrays.equals(silence.encoded,
                tone.encoded);
        boolean silenceMorseDifferent = !Arrays.equals(
                Arrays.copyOf(silence.encoded, common),
                Arrays.copyOf(morse.encoded, common));
        evidence.saveText("ambe", "causality",
                "silence_tone_different=" + silenceToneDifferent + "\n"
                + "silence_morse_prefix_different="
                + silenceMorseDifferent + "\n"
                + "silence_sha256=" + Bytes.sha256(silence.encoded) + "\n"
                + "tone_800hz_sha256=" + Bytes.sha256(tone.encoded) + "\n"
                + "morse_sha256=" + Bytes.sha256(morse.encoded) + "\n");
        if (!silenceToneDifferent || !silenceMorseDifferent) {
            throw new IOException("三组AMBE内容因果对照未形成差异");
        }
        return morse.encoded;
    }

    private VectorEncoding encodeVectorMeasured(String label, String fileBase)
            throws Exception {
        byte[] pcmRaw;
        byte[] golden;
        try (InputStream pcm = assets.open("software_ambe_vectors/"
                + fileBase + ".pcm_s16le");
                InputStream ambe = assets.open("software_ambe_vectors/"
                + fileBase + ".ambe9_sequence.bin")) {
            pcmRaw = readAll(pcm);
            golden = readAll(ambe);
        }
        short[] pcm = TxPlan.decodePcmS16Le(pcmRaw);
        int frameCount = pcm.length / TxPlan.PCM_SAMPLES_PER_FRAME;
        byte[] encoded = new byte[frameCount * TxPlan.AMBE_BYTES_PER_FRAME];
        long[] frameUs = new long[frameCount];
        int deadlineMisses = 0;
        try (SoftwareAmbeEncoder encoder = new SoftwareAmbeEncoder()) {
            for (int frame = 0; frame < frameCount; frame++) {
                short[] one = Arrays.copyOfRange(pcm,
                        frame * TxPlan.PCM_SAMPLES_PER_FRAME,
                        (frame + 1) * TxPlan.PCM_SAMPLES_PER_FRAME);
                long started = SystemClock.elapsedRealtimeNanos();
                byte[] unit = encoder.encode(one);
                frameUs[frame] = (SystemClock.elapsedRealtimeNanos()
                        - started) / 1000L;
                if (frameUs[frame] > 20000L) {
                    deadlineMisses++;
                }
                if (unit == null
                        || unit.length != TxPlan.AMBE_BYTES_PER_FRAME) {
                    throw new IOException(label + "第" + frame
                            + "帧编码长度错误");
                }
                System.arraycopy(unit, 0, encoded,
                        frame * TxPlan.AMBE_BYTES_PER_FRAME, unit.length);
            }
        }
        evidence.saveEvent("ambe", label + "_pcm_s16le", pcmRaw);
        evidence.saveEvent("ambe", label + "_golden_ambe9", golden);
        evidence.saveEvent("ambe", label + "_actual_ambe9", encoded);
        evidence.saveText("ambe", label + "_frame_timing",
                timingReport(frameUs, deadlineMisses));
        if (!Arrays.equals(encoded, golden)) {
            throw new IOException(label + "软件AMBE输出不匹配冻结向量");
        }
        if (!encodingTimingAcceptable(frameUs)) {
            throw new IOException(label + "软件AMBE编码耗时p95超过20毫秒");
        }
        return new VectorEncoding(encoded);
    }

    static boolean encodingTimingAcceptable(long[] frameUs) {
        if (frameUs == null || frameUs.length == 0) {
            return false;
        }
        long[] sorted = frameUs.clone();
        Arrays.sort(sorted);
        return percentile(sorted, 95) <= 20000L;
    }

    private static String timingReport(long[] frameUs, int deadlineMisses) {
        long[] sorted = frameUs.clone();
        Arrays.sort(sorted);
        StringBuilder result = new StringBuilder();
        result.append("frames=").append(frameUs.length).append('\n');
        result.append("deadline_us=20000\n");
        result.append("deadline_misses=").append(deadlineMisses).append('\n');
        result.append("p50_us=").append(percentile(sorted, 50)).append('\n');
        result.append("p95_us=").append(percentile(sorted, 95)).append('\n');
        result.append("p99_us=").append(percentile(sorted, 99)).append('\n');
        result.append("max_us=").append(sorted[sorted.length - 1]).append('\n');
        result.append("frame_index\telapsed_us\n");
        for (int index = 0; index < frameUs.length; index++) {
            result.append(index).append('\t').append(frameUs[index]).append('\n');
        }
        return result.toString();
    }

    private static long percentile(long[] sorted, int percentile) {
        int index = (int) Math.ceil(sorted.length * percentile / 100.0) - 1;
        return sorted[Math.max(0, Math.min(sorted.length - 1, index))];
    }

    private static final class VectorEncoding {
        final byte[] encoded;

        VectorEncoding(byte[] encoded) {
            this.encoded = encoded;
        }
    }

    private void backupAllTouchedSram() throws Exception {
        sram = new SramTransaction(memory, evidence);
        sram.backupStable("vector", McuAssets.VECTOR, 4);
        // 0x20000134是PC6边沿累计计数，运行中会自然递增，只观察而不写回。
        // 0x20000138..0x2000013f才是active gate、短计数和完成状态原像。
        captureRfEdgeCounter("写入前第1轮");
        SystemClock.sleep(100);
        captureRfEdgeCounter("写入前第2轮");
        sram.backupStable("rf_timing_control", McuAssets.RF_TIMING_CONTROL,
                McuAssets.RF_TIMING_CONTROL_LENGTH);
        sram.backupStable("bridge_flag", McuAssets.BRIDGE_FLAG, 1);
        sram.backupStable("data_gate", McuAssets.DATA_GATE, 1);
        sram.backupStable("session_scalars", McuAssets.SESSION_SCALARS,
                McuAssets.SESSION_SCALARS_LENGTH);
        sram.backupStable("session_queues", McuAssets.QUEUES,
                McuAssets.QUEUES_LENGTH);
        sram.backupStable("second_bridge_code", McuAssets.SECOND_BRIDGE_CODE,
                McuAssets.SECOND_BRIDGE_LENGTH);
        sram.backupStable("second_bridge_counter", McuAssets.SECOND_BRIDGE_COUNTER,
                4);
        sram.backupStable("second_bridge_marker", McuAssets.SECOND_BRIDGE_MARKER,
                4);
        sram.backupStable("fullprep_code", McuAssets.FULLPREP_CODE,
                McuAssets.FULLPREP_LENGTH);
        for (int offset = 0; offset < McuAssets.FULLPREP_METADATA_LENGTH;
                offset += 4) {
            sram.backupStable("fullprep_meta_" + offset,
                    McuAssets.FULLPREP_METADATA + offset, 4);
        }
        sram.backupStable("fullprep_helper", McuAssets.FULLPREP_HELPER,
                McuAssets.FULLPREP_HELPER_LENGTH);
        sram.backupStable("rf_off", McuAssets.RF_OFF_CODE,
                McuAssets.RF_OFF_LENGTH);
        sram.backupStable("rf_marker", McuAssets.RF_MARKER, 4);
        sram.backupStable("rf_prep_chain", McuAssets.RF_PREP_CHAIN_CODE,
                McuAssets.RF_PREP_CHAIN_LENGTH);
        sram.backupStable("inbridge_rf_off", McuAssets.INBRIDGE_RF_OFF_CODE,
                McuAssets.INBRIDGE_RF_OFF_LENGTH);
        sram.backupStable("rf_hold_counter", McuAssets.RF_HOLD_COUNTER,
                McuAssets.RF_HOLD_COUNTER_LENGTH);
    }

    private void runFirstNoRfBridge(TxStateMachine machine, byte[] runtime14,
            boolean setup0Only, boolean relayOne,
            boolean fixedAssetRelay, byte[] software49Bit36,
            String software49BitSource, boolean softwareFinalWirePayload,
            boolean allowRf, boolean postVlcThreeLiveSoftwareOne,
            boolean ackPacedVlcSoftware,
            boolean ackPacedVlcSoftwareTripleSos,
            DmrProtocol.VoiceFormat voiceFormat)
            throws Exception {
        byte[] fullprep = asset(allowRf
                ? McuAssets.FULLPREP_RF_FILE : McuAssets.FULLPREP_FILE,
                McuAssets.FULLPREP_LENGTH, allowRf
                ? McuAssets.FULLPREP_RF_SHA256 : McuAssets.FULLPREP_SHA256);
        byte[] helper = asset(McuAssets.FULLPREP_HELPER_FILE,
                McuAssets.FULLPREP_HELPER_LENGTH,
                McuAssets.FULLPREP_HELPER_SHA256);
        if (allowRf && (!McuAssets.containsLe32(fullprep,
                McuAssets.RF_PREP_CHAIN_ENTRY)
                || !McuAssets.containsLe32(fullprep,
                McuAssets.INBRIDGE_RF_OFF_ENTRY)
                || McuAssets.containsLe32(fullprep,
                McuAssets.FULLPREP_HELPER | 1))) {
            throw new IOException("射频合成fullprep未指向链上RF准备桩和桥内关断调度");
        }
        if (!allowRf && (McuAssets.containsLe32(fullprep,
                McuAssets.RF_PREP_CHAIN_ENTRY)
                || !McuAssets.containsLe32(fullprep,
                McuAssets.FULLPREP_HELPER | 1))) {
            throw new IOException("无射频fullprep不得指向链上RF准备桩");
        }
        upload("fullprep_code", fullprep);
        upload("fullprep_helper", helper);
        for (int offset = 0; offset < McuAssets.FULLPREP_METADATA_LENGTH;
                offset += 4) {
            upload("fullprep_meta_" + offset, new byte[4]);
        }
        measuredTickHz = measureTickHz();
        upload("fullprep_meta_4", le32((int) Math.round(
                measuredTickHz * FULLPREP_DELAY_MS / 1000.0)));
        upload("fullprep_meta_8", le32((int) Math.round(
                measuredTickHz * FIRST_BRIDGE_EXIT_MS / 1000.0)));
        if (allowRf) {
            upload("rf_hold_counter", le32((int) Math.round(
                    measuredTickHz * RF_HOLD_MS / 1000.0)));
            byte[] chain = asset(McuAssets.RF_PREP_CHAIN_FILE,
                    McuAssets.RF_PREP_CHAIN_LENGTH,
                    McuAssets.RF_PREP_CHAIN_SHA256);
            byte[] inbridgeOff = asset(McuAssets.INBRIDGE_RF_OFF_FILE,
                    McuAssets.INBRIDGE_RF_OFF_LENGTH,
                    McuAssets.INBRIDGE_RF_OFF_SHA256);
            byte[] off = asset(McuAssets.RF_OFF_FILE,
                    McuAssets.RF_OFF_LENGTH, McuAssets.RF_OFF_SHA256);
            if (!McuAssets.containsLe32(inbridgeOff, 0x0801ff35)
                    || McuAssets.containsLe32(inbridgeOff, McuAssets.VECTOR)
                    || !McuAssets.containsLe32(inbridgeOff,
                    TxStateMachine.RF_OFF_MARKER)
                    || !McuAssets.containsLe32(inbridgeOff,
                    TxStateMachine.ORIGINAL_SYSTICK)) {
                throw new IOException("桥内关断桩必须调用0x0801FF34且不得写SysTick向量");
            }
            if (McuAssets.RF_PREP_CHAIN_V007_FORBIDDEN_SHA256.equals(
                    Bytes.sha256(chain))
                    || McuAssets.RF_PREP_CHAIN_V008_FORBIDDEN_SHA256.equals(
                    Bytes.sha256(chain))
                    || !McuAssets.containsLe32(chain, 0x48001028)
                    || !McuAssets.containsLe32(chain, 0x48001428)
                    || !McuAssets.containsLe32(chain, McuAssets.ACTIVE_GATE)
                    || !McuAssets.containsLe32(chain, McuAssets.RF_SLOT_COUNT)
                    || !McuAssets.containsLe32(chain, McuAssets.RF_COMPLETION)
                    || !McuAssets.containsLe32(chain,
                    McuAssets.FULLPREP_HELPER | 1)
                    || !McuAssets.containsThumb16(chain, 0x21a0)
                    || McuAssets.containsLe32(chain, McuAssets.VECTOR)
                    || McuAssets.containsLe32(chain,
                    TxStateMachine.RF_PREP_MARKER)) {
                    throw new IOException("链上RF准备桩不是冻结v009长时隙门制品");
            }
            upload("rf_prep_chain", chain);
            upload("inbridge_rf_off", inbridgeOff);
            upload("rf_off", off);
            upload("rf_marker", new byte[4]);
            requestAndRequireDeviceDeadlineBeforeFirstBridge();
            rfMayBeActive = true;
            evidence.saveText("rf", "first_bridge_window_armed",
                    "prep=v009_slot_gate\n"
                    + "inbridge_off=keep_systick_v001\n"
                    + "hold_ms=" + RF_HOLD_MS + "\n"
                    + "sequence=PE6_low,PF3_low,completion=0,count=160,"
                    + "strb_gate,mirror_helper;phase4_hold,0x0801ff34,"
                    + "strb_gate=0\n");
        }
        long armedAt = SystemClock.elapsedRealtime();
        if (allowRf) {
            long rfMayStartEpoch = System.currentTimeMillis() / 1000L
                    + (FULLPREP_DELAY_MS + 999L) / 1000L;
            evidence.saveCredential("rf_prep_imminent",
                    deadlinePreparationCredential(rfMayStartEpoch));
        }
        evidence.saveText("bridge1", "arm_timing",
                "before_vector_write_ms=" + armedAt + "\n");
        machine.registerFullprepDeadline(armedAt + FULLPREP_DELAY_MS);
        upload("vector", le32(McuAssets.FULLPREP_ENTRY));
        byte[] bridgeEntryRaw = memory.writeByte(McuAssets.BRIDGE_FLAG, 1);
        long bridgeFlagWrittenAt = SystemClock.elapsedRealtime();
        evidence.saveText("bridge1", "flag_write",
                "value=1\n"
                + "completed_elapsed_realtime_ms=" + bridgeFlagWrittenAt
                + "\n");
        transport.markBridgeActive();
        bridgeExpectedExitAt = armedAt + FIRST_BRIDGE_EXIT_MS
                + FIRST_BRIDGE_MARGIN_MS;
        abortOnBridgeEntrySessionSignal("bridge1", bridgeEntryRaw);
        sleepUntil(bridgeFlagWrittenAt + POST_BRIDGE_SETTLE_MS);
        long setupStartAt = SystemClock.elapsedRealtime();
        evidence.saveText("bridge1", "post_bridge_settle",
                "bridge_flag_written_elapsed_realtime_ms="
                + bridgeFlagWrittenAt + "\n"
                + "setup0_before_write_elapsed_realtime_ms="
                + setupStartAt + "\n"
                + "required_settle_ms=" + POST_BRIDGE_SETTLE_MS + "\n"
                + "actual_settle_ms="
                + (setupStartAt - bridgeFlagWrittenAt) + "\n");

        int setupCount = setup0Only ? 1 : DmrProtocol.SETUP_COUNT;
        for (int index = 0; index < setupCount; index++) {
            exchangeControl("setup_" + index, machine);
        }
        long latestSetup = armedAt + FULLPREP_DELAY_MS - 200;
        if (SystemClock.elapsedRealtime() > latestSetup) {
            throw new IOException("setup未在fullprep安全边距前完成");
        }
        if (setup0Only) {
            machine.stopAfterSetup0();
        }
        sleepUntil(armedAt + FULLPREP_DELAY_MS);
        if (!setup0Only) {
            if (allowRf) {
                long rfAt = SystemClock.elapsedRealtime();
                evidence.saveCredential("rf_actual_start",
                        deadlineWindowCredential(rfAt,
                                TxStateMachine.RF_PREP_MARKER));
            }
            machine.beginVlcAfterFullprepDeadline(SystemClock.elapsedRealtime());
            if (relayOne) {
                if (software49Bit36 != null) {
                    if (software49BitSource == null
                            || software49BitSource.length() == 0) {
                        throw new IOException("软件替换首包缺少来源标签");
                    }
                    if (!isEncodeDmrRelaySource(software49BitSource)
                            && !softwareFinalWirePayload) {
                        RealtimeRelay.requireChanDLastNibbleZero(
                                software49Bit36);
                    }
                    activeRelay = new RealtimeRelay(runtime14, software49Bit36,
                            software49BitSource, softwareFinalWirePayload,
                            ackPacedVlcSoftware
                                    ? RealtimeRelay.EXTERNAL_SOURCE_TRIGGER_UNITS
                                    : postVlcThreeLiveSoftwareOne
                                    ? RealtimeRelay.REQUIRED_UNITS
                                    : RELAY_SOURCE_SOFTWARE_PRIVACY_TRIPLE_SOS.equals(
                                    software49BitSource)
                                    ? RealtimeRelay.SOFTWARE_REPLACEMENT_TRIGGER_UNITS
                                    : RealtimeRelay.REQUIRED_UNITS,
                            postVlcThreeLiveSoftwareOne,
                            ackPacedVlcSoftwareTripleSos,
                            voiceFormat);
                } else if (fixedAssetRelay) {
                    byte[] asset = readAll(assets.open(
                            "v090_chan_d_244units.bin"));
                    byte[] unit0 = RealtimeRelay.requireFrozenUnit0(asset);
                    evidence.saveEvent("relay", "fixed_asset_complete", asset);
                    evidence.saveEvent("relay", "fixed_asset_unit0", unit0);
                    activeRelay = new RealtimeRelay(runtime14, unit0);
                } else {
                    activeRelay = new RealtimeRelay(runtime14);
                }
            }
            for (int index = 0; index < DmrProtocol.VLC_COUNT; index++) {
                if (relayOne && !postVlcThreeLiveSoftwareOne
                        && !ackPacedVlcSoftware && index == 1) {
                    completeRelayBodyBeforeRemainingVlc(machine);
                }
                if (relayOne && !postVlcThreeLiveSoftwareOne
                        && !ackPacedVlcSoftware
                        && activeRelay.unitsWritten()
                        < relayUnitsRequiredBeforeVlc(index,
                                activeRelay.maximumUnits())) {
                    throw new IOException("VLC前连续正文尚未完成 index="
                            + index + " written=" + activeRelay.unitsWritten()
                            + " required=" + relayUnitsRequiredBeforeVlc(index,
                                    activeRelay.maximumUnits()));
                }
                if (ackPacedVlcSoftware) {
                    exchangeAckPacedRelayVlc("vlc_" + index, machine);
                } else {
                    exchangeControl("vlc_" + index, machine);
                }
            }
            if (relayOne) {
                if (ackPacedVlcSoftwareTripleSos) {
                    runAckPacedPostVlcTripleSos(machine);
                    machine.markRealtimeRelaySequenceComplete();
                    exchangeAckPacedRelayVlc("termination_vlc", machine);
                } else if (ackPacedVlcSoftware) {
                    runAckPacedPostVlcOneData36(machine);
                    machine.markRealtimeRelaySequenceComplete();
                    exchangePostVlcTermination(machine);
                } else if (postVlcThreeLiveSoftwareOne) {
                    runPostVlcThreeLiveOneData36(machine);
                    machine.markRealtimeRelaySequenceComplete();
                    exchangePostVlcTermination(machine);
                } else {
                saveHotPathAwareText("relay", "post_vlc_completed_body",
                        "observed_ms=" + SystemClock.elapsedRealtime() + "\n"
                        + "vlc_acks=" + activeRelay.vlcAckCount() + "\n"
                        + "units_after_vlc="
                        + activeRelay.unitsWritten() + "\n"
                        + "credits_after_vlc="
                        + activeRelay.creditsConsumed() + "\n");
                if (activeRelay.unitsWritten() != activeRelay.maximumUnits()
                        || activeRelay.creditsConsumed()
                        != activeRelay.maximumUnits()) {
                    throw new IOException("五条VLC后连续正文计数改变"
                            + " written=" + activeRelay.unitsWritten()
                            + " credits=" + activeRelay.creditsConsumed());
                }
                awaitPostVlcPlayoutWindow(armedAt);
                machine.markRealtimeRelaySequenceComplete();
                exchangeControl("termination_vlc", machine);
                }
            }
            if (allowRf) {
                long latestCleanup = armedAt + FIRST_BRIDGE_EXIT_MS
                        - FIRST_BRIDGE_MARGIN_MS;
                if (SystemClock.elapsedRealtime() + 1300L
                        * DmrProtocol.CLEANUP_COUNT > latestCleanup) {
                    throw new IOException("桥内HPI收尾无法在退桥安全边距前完成");
                }
                exchangeControl("cleanup_vocoder_io_off", machine);
                exchangeControl("cleanup_work_mode_idle", machine);
            }
            if (SystemClock.elapsedRealtime()
                    > armedAt + FIRST_BRIDGE_EXIT_MS - FIRST_BRIDGE_MARGIN_MS) {
                throw new IOException("会话未在第一桥退桥安全边距前完成");
            }
        }
        sleepUntil(armedAt + FIRST_BRIDGE_EXIT_MS + FIRST_BRIDGE_MARGIN_MS);
        transport.markAutomaticBridgeExit();
        bridgeExpectedExitAt = 0;

        int bridge = readInt("bridge1_exit_flag", McuAssets.BRIDGE_FLAG, 1);
        int vector = readInt("bridge1_exit_vector", McuAssets.VECTOR, 4);
        int prep = readInt("bridge1_dgat", McuAssets.FULLPREP_METADATA, 4);
        int exit = readInt("bridge1_exit_marker",
                McuAssets.FULLPREP_METADATA + 12, 4);
        byte[] mirror = read("bridge1_runtime_mirror",
                McuAssets.FULLPREP_MIRROR, 12);
        if (!runtimeMirrorMatches(runtime14, mirror)) {
            throw new IOException("fullprep实时镜像与同次privacy快照不一致");
        }
        machine.attestFirstBridgeExit(prep, exit, bridge, vector, mirror);
        firstBridgeExitConfirmed = true;
        if (allowRf) {
            completeFirstBridgeRfWithoutSecondBridge(machine);
        } else {
            captureAllSramRegions("bridge1_postexit");
        }
        if (relayOne) {
            // 78包原件逐文件持久化不得占用第一桥或RF窗口。
            flushRelayEvidence();
        }
    }

    private void completeFirstBridgeRfWithoutSecondBridge(
            TxStateMachine machine) throws Exception {
        byte[] rfTimingState = read("bridge1_rf_timing_state",
                McuAssets.RF_TIMING_STATE, McuAssets.RF_TIMING_STATE_LENGTH);
        int gate = rfTimingState[McuAssets.ACTIVE_GATE_OFFSET] & 0xff;
        int slotCount = (rfTimingState[6] & 0xff)
                | ((rfTimingState[7] & 0xff) << 8);
        int completion = rfTimingState[8] & 0xff;
        int marker = readInt("bridge1_rf_marker", McuAssets.RF_MARKER, 4);
        long observedAt = SystemClock.elapsedRealtime();
        machine.attestFirstBridgeRfWithoutSecondBridge(marker, gate,
                completion, slotCount, observedAt);
        rfPrepareExecuted = true;
        evidence.saveText("rf", "first_bridge_gate",
                "elapsed_realtime_ms=" + observedAt + "\n"
                + "active_gate=" + gate + "\n"
                + "slot_count=" + slotCount + "\n"
                + "completion=" + completion + "\n"
                + "rf_marker=" + String.format(Locale.US, "0x%08x", marker)
                + "\n");
        executeRfOff(machine);
        if (secondBridgeExitConfirmed) {
            throw new IOException("本版本禁止第二桥");
        }
    }

    private void runRfWindow(TxStateMachine machine) throws Exception {
        requestAndRequireDeviceDeadlineAfterFirstBridge();
        byte[] prep = asset(McuAssets.RF_PREP_FILE, McuAssets.RF_PREP_LENGTH,
                McuAssets.RF_PREP_SHA256);
        byte[] off = asset(McuAssets.RF_OFF_FILE, McuAssets.RF_OFF_LENGTH,
                McuAssets.RF_OFF_SHA256);
        byte[] bridge = asset(McuAssets.SECOND_BRIDGE_FILE,
                McuAssets.SECOND_BRIDGE_LENGTH,
                McuAssets.SECOND_BRIDGE_SHA256);
        upload("rf_prep", prep);
        upload("rf_off", off);
        upload("rf_marker", new byte[4]);
        upload("second_bridge_code", bridge);
        upload("second_bridge_marker", new byte[4]);
        if (measuredTickHz <= 0) {
            throw new IOException("缺少第一桥前MCU tick实测值");
        }
        upload("second_bridge_counter", le32((int) Math.round(
                measuredTickHz * SECOND_BRIDGE_EXIT_MS / 1000.0)));
        evidence.saveText("rf", "prep_imminent",
                "elapsed_realtime_ms=" + SystemClock.elapsedRealtime() + "\n");
        evidence.saveCredential("rf_prep_imminent",
                deadlinePreparationCredential());
        // 一次性桩可能在写后回读前已经执行并恢复向量，不能套用普通上传回读门。
        // 从写向量前起即按“RF可能已活动”处理，任何异常都必须执行关闭桩。
        rfMayBeActive = true;
        memory.writeWord(McuAssets.VECTOR, McuAssets.RF_PREP_ENTRY);
        evidence.saveText("rf", "prep_vector_arm",
                "entry=" + String.format(Locale.US, "0x%08x",
                        McuAssets.RF_PREP_ENTRY) + "\n");
        SystemClock.sleep(120);
        int marker = readInt("rf_prep_marker", McuAssets.RF_MARKER, 4);
        byte[] rfTimingState = read("rf_prep_timing_state",
                McuAssets.RF_TIMING_STATE, McuAssets.RF_TIMING_STATE_LENGTH);
        int gate = Bytes.u32le(rfTimingState, McuAssets.ACTIVE_GATE_OFFSET);
        int vector = readInt("rf_prep_vector", McuAssets.VECTOR, 4);
        long rfAt = SystemClock.elapsedRealtime();
        if (vector != TxStateMachine.ORIGINAL_SYSTICK) {
            throw new IOException("RF准备一次性桩未恢复SysTick");
        }
        machine.attestActualRf(marker, gate, rfAt);
        rfPrepareExecuted = true;
        evidence.saveText("rf", "actual_start",
                "elapsed_realtime_ms=" + rfAt + "\nmarker="
                        + String.format(Locale.US, "0x%08x", marker) + "\n");
        evidence.saveCredential("rf_actual_start",
                deadlineWindowCredential(rfAt, marker));

        upload("vector", le32(McuAssets.SECOND_BRIDGE_ENTRY));
        int armedVector = readInt("bridge2_vector_armed", McuAssets.VECTOR, 4);
        long secondArmedAt = SystemClock.elapsedRealtime();
        byte[] bridgeEntryRaw = memory.writeByte(McuAssets.BRIDGE_FLAG, 1);
        evidence.saveText("bridge2", "flag_write", "value=1\n");
        transport.markBridgeActive();
        bridgeExpectedExitAt = secondArmedAt + SECOND_BRIDGE_EXIT_MS
                + SECOND_BRIDGE_MARGIN_MS;
        abortOnBridgeEntrySessionSignal("bridge2", bridgeEntryRaw);
        machine.attestSecondBridgeArmed(armedVector, secondArmedAt);

        for (int index = 0; index < TxPlan.EXPECTED_UNIT_COUNT; index++) {
            if (index > 0) {
                sleepUntil(firstDataOrigin + index * TxPlan.UNIT_INTERVAL_MS);
            }
            long callAt = SystemClock.elapsedRealtime();
            byte[] request = machine.nextDataUnit(callAt);
            evidence.saveEvent("data36", String.format(Locale.US,
                    "request_%02d", index), request);
            transport.rawWrite(request);
            long completedAt = SystemClock.elapsedRealtime();
            activeRelay.markFlushed();
            activeRelay.completeActivePacedFlush();
            if (index == 0) {
                firstDataOrigin = completedAt;
            }
            evidence.saveText("data36", String.format(Locale.US,
                    "timing_%02d", index), "call_ms=" + callAt + "\n"
                            + "flush_ms=" + completedAt + "\n"
                            + "target_ms=" + (index == 0 ? completedAt
                            : firstDataOrigin
                            + index * TxPlan.UNIT_INTERVAL_MS) + "\n");
            machine.recordDataWriteComplete(request, completedAt);
        }
        byte[] async = transport.readWindow(Math.max(0,
                bridgeExpectedExitAt - SECOND_BRIDGE_MARGIN_MS
                        - SystemClock.elapsedRealtime()));
        evidence.saveEvent("bridge2", "async_until_exit", async);
        transport.markAutomaticBridgeExit();
        bridgeExpectedExitAt = 0;
        int flag = readInt("bridge2_exit_flag", McuAssets.BRIDGE_FLAG, 1);
        int exitVector = readInt("bridge2_exit_vector", McuAssets.VECTOR, 4);
        int exitMarker = readInt("bridge2_exit_marker",
                McuAssets.SECOND_BRIDGE_MARKER, 4);
        machine.attestSecondBridgeExit(flag, exitVector, exitMarker);
        secondBridgeExitConfirmed = true;
        executeRfOff(machine);
    }

    private void requestAndRequireDeviceDeadlineAfterFirstBridge()
            throws Exception {
        if (!firstBridgeExitConfirmed || rfPrepareExecuted || rfMayBeActive) {
            throw new IllegalStateException("截止器武装请求阶段错误");
        }
        evidence.saveCredential("rf_deadline_arm_request",
                deadlineArmRequestCredential(sessionId));
        deviceDeadlineArmRequested = true;
        requireDeviceDeadlineArmed();
    }

    private void requestAndRequireDeviceDeadlineBeforeFirstBridge()
            throws Exception {
        if (firstBridgeExitConfirmed || rfPrepareExecuted || rfMayBeActive) {
            throw new IllegalStateException("第一桥截止器武装请求阶段错误");
        }
        evidence.saveCredential("rf_first_bridge_arm_request",
                firstBridgeDeadlineArmRequestCredential(sessionId));
        deviceDeadlineArmRequested = true;
        requireDeviceDeadlineArmed();
    }

    private long firstDataOrigin;
    private long relayLastWriteAt = -1L;
    private final List<byte[]> bufferedRelayPlain = new ArrayList<>();
    private final List<byte[]> bufferedRelayWirePayload = new ArrayList<>();
    private final List<byte[]> bufferedRelayRequests = new ArrayList<>();
    private final List<byte[]> bufferedRelayCredits = new ArrayList<>();
    private final List<String> bufferedRelayTimings = new ArrayList<>();
    private String bufferedFirstWriteBoundary;
    private final RelayHotPathEvidence relayHotPathEvidence =
            new RelayHotPathEvidence();
    private boolean relayHotPathActive;
    private boolean relayEvidenceFlushed;

    private void recordSerialTrace(String direction, String stage, byte[] value)
            throws Exception {
        if (relayHotPathActive) {
            relayHotPathEvidence.addEvent("serial", direction + "_" + stage,
                    value);
            return;
        }
        evidence.saveEvent("serial", direction + "_" + stage, value);
    }

    private void saveHotPathAwareEvent(String category, String label,
            byte[] value) throws IOException {
        if (relayHotPathActive) {
            relayHotPathEvidence.addEvent(category, label, value);
            return;
        }
        evidence.saveEvent(category, label, value);
    }

    private void saveHotPathAwareText(String category, String label,
            String value) throws IOException {
        saveHotPathAwareEvent(category, label,
                value.getBytes(StandardCharsets.UTF_8));
    }

    private void beginRelayHotPath() throws IOException {
        if (!relayHotPathActive) {
            relayHotPathActive = true;
        }
    }

    private void recordRelayRead(String point, int unitBefore,
            int creditBefore, long beginMs, long requestedMs,
            long returnMs, byte[] value) throws IOException {
        relayHotPathEvidence.addRead(point, unitBefore, creditBefore,
                beginMs, requestedMs, returnMs, value);
    }

    private void executeRfOff(TxStateMachine machine) throws Exception {
        captureRfStateBeforeOff("normal_rf_pre_off");
        memory.writeWord(McuAssets.RF_MARKER, 0);
        memory.writeWord(McuAssets.VECTOR, McuAssets.RF_OFF_ENTRY);
        SystemClock.sleep(120);
        int marker = readInt("rf_off_marker", McuAssets.RF_MARKER, 4);
        byte[] rfTimingState = read("rf_off_timing_state",
                McuAssets.RF_TIMING_STATE, McuAssets.RF_TIMING_STATE_LENGTH);
        int gate = Bytes.u32le(rfTimingState, McuAssets.ACTIVE_GATE_OFFSET);
        int vector = readInt("rf_off_vector", McuAssets.VECTOR, 4);
        if (vector != TxStateMachine.ORIGINAL_SYSTICK) {
            throw new IOException("RF关闭桩未恢复SysTick");
        }
        machine.attestRfOff(marker, gate);
        rfMayBeActive = false;
        rfOffConfirmed = true;
        evidence.saveText("rf", "actual_stop",
                "elapsed_realtime_ms=" + SystemClock.elapsedRealtime() + "\n");
        evidence.saveText("rf", "restore_priority",
                "rf_off_minimum_state_confirmed=true\n"
                + "full_post_state_deferred_to_per_region_restore=true\n");
    }

    private void exchangeControl(String label, TxStateMachine machine)
            throws Exception {
        byte[] request = machine.nextControlRequest();
        evidence.saveEvent("hpi", label + "_request", request);
        boolean relaySessionVlc = label.startsWith("vlc_");
        boolean vlc = relaySessionVlc || "termination_vlc".equals(label);
        boolean relayVlc = vlc && activeRelay != null;
        SerialTransport.RawExchange exchange;
        try {
            if (relayVlc) {
                exchange = exchangeRelayVlcDuration(label, request, machine);
            } else if (vlc) {
                exchange = transport.rawExchangeActiveDetailed(request,
                        300, 500, frame -> DmrProtocol.containsVlcAck(frame));
            } else {
                exchange = transport.rawExchangeDetailed(request, 500, 400);
            }
        } catch (SerialTransport.PreWriteSessionResetException error) {
            evidence.saveEvent("hpi", label + "_predrain", error.preDrain);
            evidence.saveText("hpi", label + "_aborted_before_write",
                    "reason=" + error.signal + "\n"
                    + "control_write_attempts="
                    + transport.controlWriteAttempts() + "\n"
                    + "control_flush_completed="
                    + transport.controlFlushCompleted() + "\n");
            throw error;
        }
        saveHotPathAwareEvent("hpi", label + "_predrain",
                exchange.preDrain);
        saveHotPathAwareEvent("hpi", label + "_primary", exchange.primary);
        saveHotPathAwareEvent("hpi", label + "_late", exchange.late);
        saveHotPathAwareEvent("hpi", label + "_carry", exchange.carry);
        if (exchange.matchedFrame != null) {
            saveHotPathAwareEvent("hpi", label + "_matched",
                    exchange.matchedFrame);
        }
        byte[] combined = exchange.combined();
        if ("setup_0".equals(label) && combined.length == 0
                && machine.abandonSetup0ForStrictEmptyRetry(combined)) {
            evidence.saveText("hpi", "setup_0_retry_decision",
                    "reason=strict_primary_and_late_empty\nretry_count=1\n");
            exchangeControl("setup_0_retry1", machine);
            return;
        }
        machine.acceptControlResponse(vlc ? exchange.matchedFrame : combined);
    }

    /**
     * 冻结 v2.02/v2.03：VLC 发送前静默排空交给同一增量解析器，随后整窗
     * 500 毫秒持续读取，不在首个 {@code 0x43} 处返回。第三单元一到立刻写出。
     */
    private SerialTransport.RawExchange exchangeRelayVlcDuration(
            String label, byte[] request, TxStateMachine machine)
            throws Exception {
        SerialTransport.QuietResult quiet;
        if (firstDataOrigin <= 0) {
            quiet = transport.drainControlQuiet();
        } else {
            // 首包之后信用与data36每80毫秒推进，不可能再取得150毫秒静默窗。
            // 此处只非阻塞排空当前活动流，并继续交给同一增量relay解析器。
            long drainBegin = SystemClock.elapsedRealtime();
            byte[] drained = transport.readAvailable(0);
            recordRelayRead("vlc_predrain", activeRelay.unitsWritten(),
                    activeRelay.creditsConsumed(), drainBegin, 0,
                    SystemClock.elapsedRealtime(), drained);
            String rebuildSignal = DmrProtocol.sessionRebuildSignal(drained);
            if (rebuildSignal != null) {
                throw new SerialTransport.PreWriteSessionResetException(
                        rebuildSignal, drained);
            }
            quiet = new SerialTransport.QuietResult(true, 0, drained);
        }
        feedRelayAndWriteIfReady(quiet.drained, machine);
        int vlcAckCountBeforeWrite = activeRelay.vlcAckCount();
        transport.writeControl(request);
        long controlFlushAt = SystemClock.elapsedRealtime();
        int unitsAtControlFlush = activeRelay.unitsWritten();
        boolean requiresDataPause = laterVlcPausesData(label);
        long ackObservedAt = -1L;
        int unitsAtAck = -1;
        ByteArrayOutputStream primary = new ByteArrayOutputStream();
        long deadline = SystemClock.elapsedRealtime()
                + RELAY_VLC_ACK_DURATION_MS;
        while (SystemClock.elapsedRealtime() < deadline) {
            boolean dataWriteAllowed = relayDataWriteAllowedDuringVlc(label,
                    vlcAckCountBeforeWrite, activeRelay.vlcAckCount(),
                    activeRelay.data27Count(), activeRelay.data27Count(),
                    activeRelay.unitsWritten());
            if (dataWriteAllowed) {
                serviceRelayTimeline(machine);
            }
            long now = SystemClock.elapsedRealtime();
            long wakeAt = dataWriteAllowed
                    ? Math.min(deadline, relayNextWakeAt(now))
                    : Math.min(deadline, now + 20L);
            long remain = wakeAt - now;
            int unitBefore = activeRelay.unitsWritten();
            int creditBefore = activeRelay.creditsConsumed();
            long readBegin = SystemClock.elapsedRealtime();
            byte[] chunk = transport.readAvailable(Math.max(1, remain));
            long readReturn = SystemClock.elapsedRealtime();
            try {
                if (chunk.length > 0) {
                    primary.write(chunk);
                    feedRelayAndWriteIfReady(chunk, machine, label,
                            "vlc_1".equals(label) ? vlcAckCountBeforeWrite
                                    : Integer.MAX_VALUE);
                }
                if (ackObservedAt < 0
                        && requiresDataPause
                        && activeRelay.vlcAckCount()
                        == vlcAckCountBeforeWrite + 1) {
                    ackObservedAt = SystemClock.elapsedRealtime();
                    unitsAtAck = activeRelay.unitsWritten();
                }
            } finally {
                recordRelayRead("vlc", unitBefore, creditBefore, readBegin,
                        Math.max(1, remain), readReturn, chunk);
            }
        }
        int tailUnitBefore = activeRelay.unitsWritten();
        int tailCreditBefore = activeRelay.creditsConsumed();
        long tailBegin = SystemClock.elapsedRealtime();
        byte[] tail = transport.readAvailable(0);
        recordRelayRead("vlc_tail", tailUnitBefore, tailCreditBefore,
                tailBegin, 0, SystemClock.elapsedRealtime(), tail);
        if (tail.length > 0) {
            primary.write(tail);
            feedRelayAndWriteIfReady(tail, machine, label,
                    "vlc_1".equals(label) ? vlcAckCountBeforeWrite
                            : Integer.MAX_VALUE);
        }
        if (ackObservedAt < 0 && requiresDataPause
                && activeRelay.vlcAckCount()
                == vlcAckCountBeforeWrite + 1) {
            ackObservedAt = SystemClock.elapsedRealtime();
            unitsAtAck = activeRelay.unitsWritten();
        }
        if (requiresDataPause) {
            if (ackObservedAt < 0 || unitsAtAck != unitsAtControlFlush
                    || activeRelay.unitsWritten() != unitsAtControlFlush) {
                throw new IOException(label + "确认窗出现data36写出或缺确认"
                        + " flush_units=" + unitsAtControlFlush
                        + " ack_units=" + unitsAtAck
                        + " end_units=" + activeRelay.unitsWritten());
            }
            saveHotPathAwareText("relay", label + "_data_pause",
                    "label=" + label + "\n"
                    + "control_flush_ms=" + controlFlushAt + "\n"
                    + "ack_observed_ms=" + ackObservedAt + "\n"
                    + "units_at_control_flush=" + unitsAtControlFlush
                    + "\n"
                    + "units_at_ack=" + unitsAtAck + "\n"
                    + "units_at_window_end=" + activeRelay.unitsWritten()
                    + "\n"
                    + "credits_at_window_end="
                    + activeRelay.creditsConsumed() + "\n"
                    + "data27_at_window_end="
                    + activeRelay.data27Count() + "\n");
        }
        if (relayDataWriteAllowedDuringVlc(label,
                vlcAckCountBeforeWrite, activeRelay.vlcAckCount(),
                activeRelay.data27Count(), activeRelay.data27Count(),
                activeRelay.unitsWritten())) {
            serviceRelayTimeline(machine);
        }
        if (activeRelay.unitsWritten() > activeRelay.creditsConsumed()) {
            awaitAndConsumeRelayCredit();
        }
        byte[] observed = primary.toByteArray();
        byte[] matched = activeRelay.requireSingleVlcAckAfter(
                vlcAckCountBeforeWrite);
        return new SerialTransport.RawExchange(quiet.drained, observed,
                new byte[0], matched,
                activeRelay.carrySnapshot());
    }

    private void exchangeAckPacedRelayVlc(String label,
            TxStateMachine machine) throws Exception {
        byte[] request = machine.nextControlRequest();
        evidence.saveEvent("hpi", label + "_request", request);
        byte[] preDrain;
        if ("vlc_0".equals(label)) {
            SerialTransport.QuietResult quiet = transport.drainControlQuiet();
            if (!quiet.established) {
                throw new IOException("ACK节拍VLC0前未取得静默边界");
            }
            preDrain = quiet.drained;
        } else {
            preDrain = transport.readAvailable(0);
        }
        if (preDrain.length > 0) {
            activeRelay.acceptRaw(preDrain);
        }
        int ackBefore = activeRelay.vlcAckCount();
        int writesBefore = activeRelay.unitsWritten();
        long writeAt = SystemClock.elapsedRealtime();
        transport.writeControl(request);
        long flushAt = SystemClock.elapsedRealtime();
        ByteArrayOutputStream primary = new ByteArrayOutputStream();
        long ackAt = -1L;
        long deadline = flushAt + RELAY_VLC_ACK_DURATION_MS;
        int readIndex = 0;
        while (SystemClock.elapsedRealtime() < deadline && ackAt < 0) {
            long remain = deadline - SystemClock.elapsedRealtime();
            long requested = Math.max(1L, Math.min(20L, remain));
            int unitBefore = activeRelay.unitsWritten();
            int creditBefore = activeRelay.creditsConsumed();
            long readBegin = SystemClock.elapsedRealtime();
            byte[] chunk = transport.readAvailable(requested);
            long readReturn = SystemClock.elapsedRealtime();
            try {
                if (chunk.length > 0) {
                    primary.write(chunk);
                    activeRelay.acceptRaw(chunk);
                    int ackNow = activeRelay.vlcAckCount();
                    if (ackNow > ackBefore + 1) {
                        throw new IOException(label + "出现重复VLC确认");
                    }
                    if (ackNow == ackBefore + 1) {
                        ackAt = readReturn;
                    }
                }
            } finally {
                recordRelayRead("ack_paced_" + label + "_" + readIndex++,
                        unitBefore, creditBefore, readBegin, requested,
                        readReturn, chunk);
            }
        }
        if (ackAt < 0 || activeRelay.vlcAckCount() != ackBefore + 1
                || activeRelay.unitsWritten() != writesBefore) {
            throw new IOException(label + " ACK节拍确认失败或VLC期间误写data36");
        }
        byte[] matched = activeRelay.requireSingleVlcAckAfter(ackBefore);
        saveHotPathAwareEvent("hpi", label + "_predrain", preDrain);
        saveHotPathAwareEvent("hpi", label + "_primary",
                primary.toByteArray());
        saveHotPathAwareEvent("hpi", label + "_late", new byte[0]);
        saveHotPathAwareEvent("hpi", label + "_carry",
                activeRelay.carrySnapshot());
        saveHotPathAwareEvent("hpi", label + "_matched", matched);
        saveHotPathAwareText("relay", label + "_ack_paced_timing",
                "write_call_ms=" + writeAt + "\n"
                + "flush_ms=" + flushAt + "\n"
                + "ack_ms=" + ackAt + "\n"
                + "ack_latency_ms=" + (ackAt - flushAt) + "\n"
                + "fixed_500ms_tail_wait=false\n"
                + "data36_writes=" + activeRelay.unitsWritten() + "\n");
        machine.acceptControlResponse(matched);
    }

    private void runAckPacedPostVlcOneData36(TxStateMachine machine)
            throws Exception {
        if (activeRelay == null || activeRelay.requiredTriggerUnits()
                != RealtimeRelay.EXTERNAL_SOURCE_TRIGGER_UNITS
                || activeRelay.unitsWritten() != 0
                || activeRelay.vlcAckCount() != DmrProtocol.VLC_COUNT
                || machine.phase() != TxStateMachine.Phase.WAIT_RELAY_COMPLETION) {
            throw new IOException("ACK节拍五VLC后单包准入状态错误");
        }
        long fifthAckCompleteAt = SystemClock.elapsedRealtime();
        if (!writeRelayIfReady(machine)) {
            throw new IOException("ACK节拍五VLC后未立即写出唯一data36");
        }
        saveHotPathAwareText("relay", "ack_paced_post_vlc_first_write",
                "fifth_ack_complete_ms=" + fifthAckCompleteAt + "\n"
                + "data36_flush_ms=" + relayLastWriteAt + "\n"
                + "gap_ms=" + (relayLastWriteAt - fifthAckCompleteAt) + "\n"
                + "units_written=" + activeRelay.unitsWritten() + "\n");
        awaitAndConsumeRelayCredit();
        if (activeRelay.unitsWritten() != 1
                || activeRelay.creditsConsumed() != 1) {
            throw new IOException("ACK节拍五VLC后单包信用未闭合");
        }
    }

    private void runAckPacedPostVlcTripleSos(TxStateMachine machine)
            throws Exception {
        if (activeRelay == null || activeRelay.requiredTriggerUnits()
                != RealtimeRelay.EXTERNAL_SOURCE_TRIGGER_UNITS
                || activeRelay.maximumUnits() != expectedUnitsFor(
                        activeRelay.bodyBytes(), activeRelay.voiceFormat())
                || !(RELAY_SOURCE_ACK_PACED_SOFTWARE_TRIPLE_SOS.equals(
                        activeRelay.payloadSource())
                        || RELAY_SOURCE_SPEECH_AZ09.equals(
                                activeRelay.payloadSource()))
                || activeRelay.unitsWritten() != 0
                || activeRelay.creditsConsumed() != 0
                || activeRelay.vlcAckCount() != DmrProtocol.VLC_COUNT
                || machine.phase() != TxStateMachine.Phase.WAIT_RELAY_COMPLETION) {
            String formatError = activeRelay == null ? "中继未建立"
                    : voiceFormatConsistencyError(activeRelay.voiceFormat(),
                            activeRelay.maximumUnits());
            throw new IOException("ACK节拍五VLC后主动三遍SOS准入状态错误"
                    + (formatError == null ? ""
                            : "（格式一致性：" + formatError + "）"));
        }
        beginRelayHotPath();
        long fifthAckCompleteAt = SystemClock.elapsedRealtime();
        long firstFlushAt = -1L;
        for (int index = 0; index < activeRelay.maximumUnits(); index++) {
            long targetAt = index == 0 ? SystemClock.elapsedRealtime()
                    : activePacedTargetAt(firstFlushAt, index,
                            activeRelay.voiceFormat(),
                            activeRelay.maximumUnits());
            sleepUntil(targetAt);
            long callAt = SystemClock.elapsedRealtime();
            if (callAt < targetAt || callAt > targetAt + TxPlan.MAX_LATE_MS) {
                throw new IOException("主动data36节拍越界 index=" + index
                        + " target=" + targetAt + " call=" + callAt);
            }
            byte[] request = activeRelay.takeActivePacedRequest44();
            transport.rawWrite(request);
            long flushAt = SystemClock.elapsedRealtime();
            activeRelay.completeActivePacedWriteAfterTransportFlush();
            if (index == 0) {
                firstFlushAt = flushAt;
                firstDataOrigin = flushAt;
                machine.markRealtimeRelayComplete();
                bufferedFirstWriteBoundary = "source=ack_paced_post_vlc_active\n"
                        + "fifth_ack_complete_ms=" + fifthAckCompleteAt + "\n"
                        + "first_data36_flush_ms=" + firstFlushAt + "\n"
                        + "gap_ms=" + (firstFlushAt - fifthAckCompleteAt) + "\n"
                        + "vlc_acks_at_write=" + activeRelay.vlcAckCount()
                        + "\n";
            } else {
                machine.noteAdditionalRelayUnitWritten();
            }
            relayLastWriteAt = flushAt;
            long recordedTarget = index == 0 ? callAt : targetAt;
            bufferRelayWrite(request, callAt, flushAt, recordedTarget,
                    recordedTarget);
        }
        if (!activeRelay.activePacedComplete()
                || activeRelay.unitsWritten() != expectedUnitsFor(
                        activeRelay.bodyBytes(), activeRelay.voiceFormat())
                || activeRelay.creditsConsumed() != 0) {
            throw new IOException("主动三遍SOS正文计数未闭合");
        }
        long tailStartedAt = SystemClock.elapsedRealtime();
        long tailTargetAt = tailStartedAt + ACTIVE_PACED_TAIL_MS;
        ByteArrayOutputStream tailRaw = new ByteArrayOutputStream();
        int readIndex = 0;
        while (SystemClock.elapsedRealtime() < tailTargetAt) {
            long remain = tailTargetAt - SystemClock.elapsedRealtime();
            long requested = Math.max(1L, Math.min(20L, remain));
            long readBegin = SystemClock.elapsedRealtime();
            byte[] chunk = transport.readAvailable(requested);
            long readReturn = SystemClock.elapsedRealtime();
            try {
                if (chunk.length > 0) {
                    tailRaw.write(chunk);
                    activeRelay.acceptRaw(chunk);
                }
            } finally {
                recordRelayRead("active_tail_" + readIndex++,
                        activeRelay.unitsWritten(),
                        activeRelay.creditsConsumed(), readBegin, requested,
                        readReturn, chunk);
            }
        }
        if (activeRelay.carrySnapshot().length != 0) {
            throw new IOException("主动正文尾窗结束时存在不完整HPI帧，禁止切换终止解析器");
        }
        saveHotPathAwareEvent("relay", "active_tail_raw", tailRaw.toByteArray());
        saveHotPathAwareText("relay", "active_paced_complete",
                "fifth_ack_complete_ms=" + fifthAckCompleteAt + "\n"
                + "first_flush_ms=" + firstFlushAt + "\n"
                + "last_flush_ms=" + relayLastWriteAt + "\n"
                + "units=" + activeRelay.unitsWritten() + "\n"
                + "credits_consumed=" + activeRelay.creditsConsumed() + "\n"
                + "short_credits_observed="
                + activeRelay.activePacedCreditCount() + "\n"
                + "tail_started_ms=" + tailStartedAt + "\n"
                + "tail_completed_ms=" + SystemClock.elapsedRealtime() + "\n"
                + "tail_raw_bytes=" + tailRaw.size() + "\n");
    }

    private void feedRelayAndWriteIfReady(byte[] raw, TxStateMachine machine)
            throws Exception {
        feedRelayAndWriteIfReady(raw, machine, "outside_vlc_read",
                Integer.MAX_VALUE);
    }

    private void feedRelayAndWriteIfReady(byte[] raw, TxStateMachine machine,
            String source, int firstWriteAckCeiling) throws Exception {
        if (activeRelay == null || raw == null || raw.length == 0) {
            return;
        }
        feedRelayIncrementally(activeRelay, raw, firstWriteAckCeiling,
                acceptedBytes -> {
                    boolean wrote = writeRelayIfReady(machine);
                    if (wrote && bufferedFirstWriteBoundary == null) {
                        bufferedFirstWriteBoundary = "source=" + source + "\n"
                                + "chunk_bytes=" + raw.length + "\n"
                                + "accepted_bytes_before_write="
                                + acceptedBytes + "\n"
                                + "data27_at_write="
                                + activeRelay.data27Count() + "\n"
                                + "vlc_acks_at_write="
                                + activeRelay.vlcAckCount() + "\n"
                                + "ack_ceiling=" + firstWriteAckCeiling
                                + "\n";
                    }
                    return wrote;
                });
    }

    interface RelayIncrementalAction {
        boolean afterAcceptedByte(int acceptedBytes) throws Exception;
    }

    static int feedRelayIncrementally(RealtimeRelay relay, byte[] raw,
            int firstWriteAckCeiling, RelayIncrementalAction action)
            throws Exception {
        if (relay == null || raw == null || action == null
                || firstWriteAckCeiling < 0) {
            throw new IllegalArgumentException("增量relay输入参数无效");
        }
        int firstWriteOffset = -1;
        for (int index = 0; index < raw.length; index++) {
            relay.acceptRaw(new byte[] {raw[index]});
            if (relay.readyToWrite()
                    && relay.vlcAckCount() > firstWriteAckCeiling) {
                throw new IOException("首包触发晚于当前VLC确认，拒绝写出"
                        + " accepted_bytes=" + (index + 1)
                        + " vlc_acks=" + relay.vlcAckCount()
                        + " ceiling=" + firstWriteAckCeiling);
            }
            if (action.afterAcceptedByte(index + 1)
                    && firstWriteOffset < 0) {
                firstWriteOffset = index + 1;
            }
        }
        return firstWriteOffset;
    }

    private void completeRelayHandshakeBeforeRemainingVlc(
            TxStateMachine machine)
            throws Exception {
        boolean waitNeeded = activeRelay.data27Count()
                < activeRelay.requiredTriggerUnits()
                && activeRelay.unitsWritten() == 0;
        long waitBegin = SystemClock.elapsedRealtime();
        ByteArrayOutputStream waitRaw = new ByteArrayOutputStream();
        if (waitNeeded) {
            long deadline = waitBegin + RELAY_PRE_VLC2_WAIT_MS;
            while (SystemClock.elapsedRealtime() < deadline) {
                long remain = deadline - SystemClock.elapsedRealtime();
                int unitBefore = activeRelay.unitsWritten();
                int creditBefore = activeRelay.creditsConsumed();
                long readBegin = SystemClock.elapsedRealtime();
                byte[] chunk = transport.readAvailable(Math.max(1, remain));
                long readReturn = SystemClock.elapsedRealtime();
                try {
                    if (chunk.length > 0) {
                        waitRaw.write(chunk);
                        feedRelayAndWriteIfReady(chunk, machine);
                    }
                } finally {
                    recordRelayRead("pre_vlc2", unitBefore, creditBefore,
                            readBegin, Math.max(1, remain), readReturn, chunk);
                }
            }
            int tailUnitBefore = activeRelay.unitsWritten();
            int tailCreditBefore = activeRelay.creditsConsumed();
            long tailBegin = SystemClock.elapsedRealtime();
            byte[] tail = transport.readAvailable(0);
            recordRelayRead("pre_vlc2_tail", tailUnitBefore,
                    tailCreditBefore, tailBegin, 0,
                    SystemClock.elapsedRealtime(), tail);
            if (tail.length > 0) {
                waitRaw.write(tail);
                feedRelayAndWriteIfReady(tail, machine);
            }
        }
        saveHotPathAwareEvent("relay", "pre_vlc2_wait_raw",
                waitRaw.toByteArray());
        saveHotPathAwareText("relay", "pre_vlc2_wait",
                "needed=" + waitNeeded + "\n"
                + "elapsed_ms="
                + (SystemClock.elapsedRealtime() - waitBegin) + "\n"
                + "required_trigger_units="
                + activeRelay.requiredTriggerUnits() + "\n"
                + "units=" + activeRelay.data27Count() + "\n");
        writeRelayIfReady(machine);
        if (activeRelay.unitsWritten() == 0) {
            throw new IOException("vlc1前实时27字节触发单元不足 required="
                    + activeRelay.requiredTriggerUnits() + " units="
                    + activeRelay.data27Count());
        }
        if (activeRelay.unitsWritten() != 1) {
            throw new IOException("vlc1前必须只有首包 written="
                    + activeRelay.unitsWritten());
        }
        if (activeRelay.unitsWritten() > activeRelay.creditsConsumed()) {
            awaitAndConsumeRelayCredit();
        }
        if (activeRelay.creditsConsumed() != 1) {
            throw new IOException("vlc1前首包新鲜信用不足 written="
                    + activeRelay.unitsWritten()
                    + " credits=" + activeRelay.creditsConsumed());
        }
    }

    private void completeRelayBodyBeforeRemainingVlc(
            TxStateMachine machine) throws Exception {
        completeRelayHandshakeBeforeRemainingVlc(machine);
        runRelayTimelineToCompletion(machine);
        if (!activeRelay.creditAccepted()
                || activeRelay.unitsWritten() != activeRelay.maximumUnits()
                || activeRelay.creditsConsumed()
                != activeRelay.maximumUnits()) {
            throw new IOException("后续VLC前连续正文或信用不足 written="
                    + activeRelay.unitsWritten() + " credits="
                    + activeRelay.creditsConsumed() + " required="
                    + activeRelay.maximumUnits());
        }
        saveHotPathAwareText("relay", "pre_remaining_vlc_completion",
                "completed_ms=" + SystemClock.elapsedRealtime() + "\n"
                + "next_vlc_index=1\n"
                + "units=" + activeRelay.unitsWritten() + "\n"
                + "credits=" + activeRelay.creditsConsumed() + "\n");
    }

    private void runPostVlcThreeLiveOneData36(TxStateMachine machine)
            throws Exception {
        if (activeRelay == null || activeRelay.unitsWritten() != 0
                || activeRelay.data27Count() != 0
                || machine.phase() != TxStateMachine.Phase.WAIT_RELAY_COMPLETION) {
            throw new IOException("VLC后单包实验准入状态错误");
        }
        activeRelay.armPostVlcTrigger();
        beginRelayHotPath();
        long armedAt = SystemClock.elapsedRealtime();
        saveHotPathAwareEvent("relay", "post_vlc_arm_carry",
                activeRelay.carrySnapshot());
        saveHotPathAwareText("relay", "post_vlc_arm",
                "armed_ms=" + armedAt + "\n"
                + "raw_offset=" + activeRelay.postVlcArmRawOffset() + "\n"
                + "pre_vlc_data27_count="
                + activeRelay.preArmData27Count() + "\n"
                + "carry_bytes=" + activeRelay.carrySnapshot().length + "\n");

        long triggerDeadline = armedAt + POST_VLC_TRIGGER_TIMEOUT_MS;
        int triggerReadIndex = 0;
        while (!activeRelay.readyToWrite()
                && SystemClock.elapsedRealtime() < triggerDeadline) {
            long remain = triggerDeadline - SystemClock.elapsedRealtime();
            int unitBefore = activeRelay.data27Count();
            int creditBefore = activeRelay.creditCount();
            long readBegin = SystemClock.elapsedRealtime();
            byte[] chunk = transport.readAvailable(Math.max(1L,
                    Math.min(20L, remain)), 1);
            long readReturn = SystemClock.elapsedRealtime();
            try {
                if (chunk.length > 0) {
                    activeRelay.acceptRaw(chunk);
                    if (activeRelay.data27Count() > unitBefore) {
                        int unitIndex = activeRelay.data27Count() - 1;
                        List<Long> starts =
                                activeRelay.postArmData27StartOffsets();
                        List<Long> ends =
                                activeRelay.postArmData27EndOffsets();
                        saveHotPathAwareText("relay",
                                "post_vlc_trigger_unit_" + unitIndex
                                + "_arrival",
                                "unit=" + unitIndex + "\n"
                                + "source_read=post_vlc_trigger_byte_"
                                + triggerReadIndex + "\n"
                                + "read_return_ms=" + readReturn + "\n"
                                + "start_raw_offset="
                                + starts.get(unitIndex) + "\n"
                                + "end_raw_offset="
                                + ends.get(unitIndex) + "\n");
                    }
                }
            } finally {
                recordRelayRead("post_vlc_trigger_byte_"
                        + triggerReadIndex++, unitBefore, creditBefore,
                        readBegin, Math.max(1L, Math.min(20L, remain)),
                        readReturn, chunk);
            }
        }
        if (!activeRelay.readyToWrite()) {
            postVlcObservationClass = "POST_VLC_THREE_UNITS_NOT_REACHED";
            saveHotPathAwareText("relay", "post_vlc_observation",
                    "classification=" + postVlcObservationClass + "\n"
                    + "trigger_units=" + activeRelay.data27Count() + "\n"
                    + "post_arm_data27_total="
                    + activeRelay.postArmData27Total() + "\n"
                    + "elapsed_ms="
                    + (SystemClock.elapsedRealtime() - armedAt) + "\n");
            throw new IOException("五VLC后1500毫秒未取得三个新鲜CHAN_D");
        }

        long acceptedRawAtWrite = activeRelay.rawBytesAccepted();
        if (!writeRelayIfReady(machine)) {
            throw new IOException("第三个VLC后新鲜CHAN_D边界未写出唯一data36");
        }
        bufferedFirstWriteBoundary = "source=post_vlc_one_byte_reads\n"
                + "physical_read_max_bytes=1\n"
                + "post_arm_data27_at_write="
                + activeRelay.postArmData27Total() + "\n"
                + "raw_bytes_accepted_at_write=" + acceptedRawAtWrite + "\n"
                + "vlc_acks_at_write=" + activeRelay.vlcAckCount() + "\n";

        int data27AtWrite = activeRelay.postArmData27Total();
        int otherAtWrite = activeRelay.postArmOtherFrameTotal();
        long observationStarted = SystemClock.elapsedRealtime();
        long observationDeadline = observationStarted
                + POST_VLC_OBSERVATION_MS;
        ByteArrayOutputStream observationRaw = new ByteArrayOutputStream();
        boolean malformed = false;
        String malformedReason = "";
        int observationReadIndex = 0;
        while (SystemClock.elapsedRealtime() < observationDeadline) {
            long remain = observationDeadline - SystemClock.elapsedRealtime();
            long requested = Math.max(1L, Math.min(50L, remain));
            int unitBefore = activeRelay.data27Count();
            int creditBefore = activeRelay.creditCount();
            long readBegin = SystemClock.elapsedRealtime();
            byte[] chunk = transport.readAvailable(requested);
            long readReturn = SystemClock.elapsedRealtime();
            try {
                if (chunk.length > 0) {
                    observationRaw.write(chunk);
                    if (!malformed) {
                        try {
                            activeRelay.acceptRaw(chunk);
                        } catch (IllegalStateException error) {
                            malformed = true;
                            malformedReason = String.valueOf(error.getMessage());
                        }
                    }
                }
            } finally {
                recordRelayRead("post_vlc_observe_"
                        + observationReadIndex++, unitBefore, creditBefore,
                        readBegin, requested, readReturn, chunk);
            }
        }
        int continuation = Math.max(0,
                activeRelay.postArmData27Total() - data27AtWrite);
        int other = Math.max(0,
                activeRelay.postArmOtherFrameTotal() - otherAtWrite);
        postVlcObservationClass = classifyPostVlcOneData36Observation(
                activeRelay.creditCount(), continuation, other,
                observationRaw.size(), malformed);
        saveHotPathAwareEvent("relay", "post_vlc_observation_raw",
                observationRaw.toByteArray());
        saveHotPathAwareEvent("relay", "post_vlc_observation_carry",
                activeRelay.carrySnapshot());
        saveHotPathAwareText("relay", "post_vlc_observation",
                "classification=" + postVlcObservationClass + "\n"
                + "started_ms=" + observationStarted + "\n"
                + "completed_ms=" + SystemClock.elapsedRealtime() + "\n"
                + "requested_ms=" + POST_VLC_OBSERVATION_MS + "\n"
                + "raw_bytes=" + observationRaw.size() + "\n"
                + "short_credits=" + activeRelay.creditCount() + "\n"
                + "chan_d_continuations=" + continuation + "\n"
                + "other_complete_frames=" + other + "\n"
                + "carry_bytes=" + activeRelay.carrySnapshot().length + "\n"
                + "malformed=" + malformed + "\n"
                + "malformed_reason=" + malformedReason + "\n");
    }

    static String classifyPostVlcOneData36Observation(int shortCredits,
            int chanDContinuations, int otherCompleteFrames, int rawBytes,
            boolean malformed) {
        if (shortCredits < 0 || chanDContinuations < 0
                || otherCompleteFrames < 0 || rawBytes < 0) {
            throw new IllegalArgumentException("VLC后观察分类参数无效");
        }
        if (malformed) {
            return "MALFORMED_RESPONSE";
        }
        if (shortCredits > 0 && chanDContinuations == 0
                && otherCompleteFrames == 0) {
            return "ONE_DATA36_SHORT_CREDIT";
        }
        if (shortCredits == 0 && chanDContinuations > 0
                && otherCompleteFrames == 0) {
            return "ONE_DATA36_CHAN_D_CONTINUATION";
        }
        if (shortCredits > 0 || chanDContinuations > 0
                || otherCompleteFrames > 0) {
            return "ONE_DATA36_MIXED_RESPONSE";
        }
        return rawBytes == 0 ? "ONE_DATA36_SILENT" : "MALFORMED_RESPONSE";
    }

    private void exchangePostVlcTermination(TxStateMachine machine)
            throws Exception {
        byte[] request = machine.nextControlRequest();
        saveHotPathAwareEvent("hpi", "termination_vlc_request", request);
        SerialTransport.RawExchange exchange =
                transport.rawExchangeActiveDetailed(request, 300, 500,
                        frame -> DmrProtocol.containsVlcAck(frame));
        saveHotPathAwareEvent("hpi", "termination_vlc_predrain",
                exchange.preDrain);
        saveHotPathAwareEvent("hpi", "termination_vlc_primary",
                exchange.primary);
        saveHotPathAwareEvent("hpi", "termination_vlc_late", exchange.late);
        saveHotPathAwareEvent("hpi", "termination_vlc_carry", exchange.carry);
        if (exchange.matchedFrame != null) {
            saveHotPathAwareEvent("hpi", "termination_vlc_matched",
                    exchange.matchedFrame);
        }
        machine.acceptControlResponse(exchange.matchedFrame);
    }

    static int relayUnitsRequiredBeforeVlc(int vlcIndex, int maximumUnits) {
        if (vlcIndex < 0 || vlcIndex >= DmrProtocol.VLC_COUNT
                || maximumUnits <= 0) {
            throw new IllegalArgumentException("VLC正文顺序参数无效");
        }
        return vlcIndex == 0 ? 0 : maximumUnits;
    }

    private boolean writeRelayIfReady(TxStateMachine machine)
            throws Exception {
        if (activeRelay == null || !activeRelay.readyToWrite()) {
            return false;
        }
        byte[] request = activeRelay.takeRequest44();
        beginRelayHotPath();
        long callAt = SystemClock.elapsedRealtime();
        transport.rawWrite(request);
        long flushAt = SystemClock.elapsedRealtime();
        firstDataOrigin = callAt;
        relayLastWriteAt = flushAt;
        activeRelay.markFlushed();
        machine.markRealtimeRelayComplete();
        bufferRelayWrite(request, callAt, flushAt, callAt, callAt);
        return true;
    }

    private void writeRelayNext(TxStateMachine machine) throws Exception {
        if (activeRelay == null || !activeRelay.readyToWriteNext()) {
            throw new IOException("连续发送尚未到下一单元写出点 written="
                    + (activeRelay == null ? -1 : activeRelay.unitsWritten())
                    + " credits="
                    + (activeRelay == null ? -1 : activeRelay.creditsConsumed()));
        }
        int unitIndex = activeRelay.unitsWritten();
        if (!relayHotPathActive) {
            throw new IOException("连续发送关键窗口未启用");
        }
        long creditReadyAt = SystemClock.elapsedRealtime();
        long minimumTargetAt = relayMinimumTargetAt(relayLastWriteAt,
                activeRelay == null
                        ? DmrProtocol.VoiceFormat.LEGACY_CHAN_D36
                        : activeRelay.voiceFormat());
        long targetAt = relayCreditGatedTargetAt(relayLastWriteAt,
                creditReadyAt);
        sleepUntil(targetAt);
        long callAt = SystemClock.elapsedRealtime();
        byte[] request = activeRelay.takeRequest44();
        transport.rawWrite(request);
        long flushAt = SystemClock.elapsedRealtime();
        relayLastWriteAt = flushAt;
        activeRelay.markFlushed();
        machine.noteAdditionalRelayUnitWritten();
        bufferRelayWrite(request, callAt, flushAt, minimumTargetAt,
                creditReadyAt);
    }

    private void awaitAndConsumeRelayCredit() throws Exception {
        if (activeRelay.lastUnitCreditPending()) {
            consumeRelayCredit();
            return;
        }
        if (!activeRelay.writeAttempted()) {
            throw new IOException("尚未写出，禁止进入写后信用窗");
        }
        long creditDeadline = relayLastWriteAt + RELAY_CREDIT_TIMEOUT_MS;
        while (!activeRelay.lastUnitCreditPending()
                && SystemClock.elapsedRealtime() < creditDeadline) {
            long remain = creditDeadline - SystemClock.elapsedRealtime();
            if (remain <= 0) {
                break;
            }
            int unitBefore = activeRelay.unitsWritten();
            int creditBefore = activeRelay.creditsConsumed();
            long readBegin = SystemClock.elapsedRealtime();
            long requested = Math.max(1, remain);
            byte[] chunk = transport.readAvailable(requested);
            recordRelayRead("credit_wait", unitBefore, creditBefore,
                    readBegin, requested, SystemClock.elapsedRealtime(),
                    chunk);
            if (chunk.length > 0) {
                activeRelay.acceptRaw(chunk);
            }
            if (activeRelay.creditCount() > 1) {
                throw new IOException("实时relay写后出现多余短信用");
            }
        }
        if (!activeRelay.lastUnitCreditPending()) {
            throw new IOException("实时relay写后未取得唯一短信用 unit="
                    + (activeRelay.unitsWritten() - 1));
        }
        consumeRelayCredit();
    }

    private void consumeRelayCredit() throws Exception {
        int unitIndex = activeRelay.unitsWritten() - 1;
        if (activeRelay.creditCount() == 1) {
            bufferedRelayCredits.add(activeRelay.peekCredit());
        }
        activeRelay.consumeCredit();
    }

    private long relayNextWakeAt(long now) throws IOException {
        if (activeRelay == null || firstDataOrigin <= 0
                || activeRelay.unitsWritten() >= activeRelay.maximumUnits()) {
            return now + 50L;
        }
        if (!activeRelay.readyToWriteNext()) {
            return Math.min(relayLastWriteAt + RELAY_CREDIT_TIMEOUT_MS,
                    now + 20L);
        }
        return relayCreditGatedTargetAt(relayLastWriteAt, now);
    }

    private void serviceRelayTimeline(TxStateMachine machine) throws Exception {
        if (activeRelay == null || firstDataOrigin <= 0) {
            return;
        }
        if (activeRelay.lastUnitCreditPending()) {
            consumeRelayCredit();
        }
        if (!activeRelay.readyToWriteNext()
                && activeRelay.unitsWritten() < activeRelay.maximumUnits()
                && SystemClock.elapsedRealtime() > relayLastWriteAt
                + RELAY_CREDIT_TIMEOUT_MS) {
            throw new IOException("实时relay写后未取得唯一短信用 unit="
                    + (activeRelay.unitsWritten() - 1));
        }
        if (activeRelay.readyToWriteNext()) {
            long target = relayCreditGatedTargetAt(relayLastWriteAt,
                    SystemClock.elapsedRealtime());
            if (SystemClock.elapsedRealtime() >= target) {
                writeRelayNext(machine);
            }
        }
    }

    private void runRelayTimelineToCompletion(TxStateMachine machine)
            throws Exception {
        if (firstDataOrigin <= 0) {
            throw new IOException("连续供数缺少首包时间原点");
        }
        long finalDeadline = firstDataOrigin
                + (activeRelay.maximumUnits() - 1L) * TxPlan.UNIT_INTERVAL_MS
                + DmrProtocol.VLC_COUNT_AFTER_FIRST_DATA
                * RELAY_VLC_ACK_DURATION_MS
                + RELAY_CREDIT_TIMEOUT_MS;
        while (activeRelay.creditsConsumed() < activeRelay.maximumUnits()) {
            serviceRelayTimeline(machine);
            if (activeRelay.creditsConsumed() >= activeRelay.maximumUnits()) {
                break;
            }
            long now = SystemClock.elapsedRealtime();
            if (now >= finalDeadline) {
                throw new IOException("连续供数或末包信用超时 written="
                        + activeRelay.unitsWritten() + " credits="
                        + activeRelay.creditsConsumed());
            }
            long wakeAt = Math.min(finalDeadline, relayNextWakeAt(now));
            long requested = Math.max(1, wakeAt - now);
            int unitBefore = activeRelay.unitsWritten();
            int creditBefore = activeRelay.creditsConsumed();
            long readBegin = SystemClock.elapsedRealtime();
            byte[] chunk = transport.readAvailable(requested);
            long readReturn = SystemClock.elapsedRealtime();
            try {
                if (chunk.length > 0) {
                    activeRelay.acceptRaw(chunk);
                }
            } finally {
                recordRelayRead("completion", unitBefore, creditBefore,
                        readBegin, requested, readReturn, chunk);
            }
        }
    }

    private void awaitPostVlcPlayoutWindow(long armedAt) throws Exception {
        if (!RELAY_SOURCE_SOFTWARE_PRIVACY_TRIPLE_SOS.equals(
                activeRelay.payloadSource())) {
            return;
        }
        if (activeRelay.maximumUnits() != expectedUnitsFor(
                        activeRelay.bodyBytes(), activeRelay.voiceFormat())
                || activeRelay.unitsWritten() != activeRelay.maximumUnits()
                || activeRelay.creditsConsumed() != activeRelay.maximumUnits()) {
            throw new IOException("三遍SOS播放窗前正文或信用未完成");
        }
        long startedAt = SystemClock.elapsedRealtime();
        long targetAt = postVlcPlayoutTargetAt(startedAt,
                activeRelay.maximumUnits());
        long latestTerminationAt = armedAt + FIRST_BRIDGE_EXIT_MS
                - FIRST_BRIDGE_MARGIN_MS - 3400L;
        if (targetAt > latestTerminationAt) {
            throw new IOException("三遍SOS完整播放窗超出终止和恢复预算"
                    + " target=" + targetAt
                    + " latest_termination=" + latestTerminationAt);
        }
        saveHotPathAwareText("relay", "post_vlc_playout_begin",
                "started_ms=" + startedAt + "\n"
                + "target_ms=" + targetAt + "\n"
                + "duration_ms=" + TRIPLE_SOS_PLAYOUT_MS + "\n"
                + "units=" + activeRelay.maximumUnits() + "\n");
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        while (SystemClock.elapsedRealtime() < targetAt) {
            long remain = targetAt - SystemClock.elapsedRealtime();
            long requested = Math.max(1, Math.min(50L, remain));
            int unitBefore = activeRelay.unitsWritten();
            int creditBefore = activeRelay.creditsConsumed();
            long readBegin = SystemClock.elapsedRealtime();
            byte[] chunk = transport.readAvailable(requested);
            long readReturn = SystemClock.elapsedRealtime();
            try {
                if (chunk.length > 0) {
                    raw.write(chunk);
                    activeRelay.acceptRaw(chunk);
                }
            } finally {
                recordRelayRead("post_vlc_playout", unitBefore, creditBefore,
                        readBegin, requested, readReturn, chunk);
            }
            if (activeRelay.creditCount() != 0) {
                throw new IOException("三遍SOS播放窗出现正文完成后的多余信用");
            }
        }
        saveHotPathAwareEvent("relay", "post_vlc_playout_raw",
                raw.toByteArray());
        saveHotPathAwareText("relay", "post_vlc_playout_complete",
                "started_ms=" + startedAt + "\n"
                + "target_ms=" + targetAt + "\n"
                + "completed_ms=" + SystemClock.elapsedRealtime() + "\n"
                + "raw_bytes=" + raw.size() + "\n"
                + "credits_after=" + activeRelay.creditsConsumed() + "\n");
    }

    static long postVlcPlayoutTargetAt(long startedAt, int units) {
        if (startedAt <= 0 || units <= 0) {
            throw new IllegalArgumentException("播放窗参数无效");
        }
        return startedAt + units * TxPlan.UNIT_INTERVAL_MS;
    }

    static long relayMinimumTargetAt(long previousFlushAt) {
        return relayMinimumTargetAt(previousFlushAt,
                DmrProtocol.VoiceFormat.LEGACY_CHAN_D36);
    }

    /** 按会话格式计算下一包的最早允许写出时刻。 */
    static long relayMinimumTargetAt(long previousFlushAt,
            DmrProtocol.VoiceFormat format) {
        if (previousFlushAt <= 0) {
            throw new IllegalArgumentException("data36上一包写出时刻错误");
        }
        return previousFlushAt + TxPlan.unitIntervalMs(format);
    }

    static boolean relayDataWriteAllowedDuringVlc(String label,
            int ackCountBeforeWrite, int currentAckCount,
            int data27CountBeforeWrite, int currentData27Count,
            int unitsWritten) {
        if (label == null || ackCountBeforeWrite < 0
                || currentAckCount < ackCountBeforeWrite
                || data27CountBeforeWrite < 0
                || currentData27Count < data27CountBeforeWrite
                || unitsWritten < 0) {
            throw new IllegalArgumentException("VLC数据写门参数无效");
        }
        if ("vlc_0".equals(label)) {
            return unitsWritten == 0;
        }
        if (laterVlcPausesData(label)) {
            return false;
        }
        return true;
    }

    private static boolean laterVlcPausesData(String label) {
        return "vlc_1".equals(label) || "vlc_2".equals(label)
                || "vlc_3".equals(label)
                || "vlc_4".equals(label);
    }

    static long relayCreditGatedTargetAt(long previousFlushAt,
            long creditReadyAt) {
        if (creditReadyAt <= 0) {
            throw new IllegalArgumentException("data36信用就绪时刻错误");
        }
        return Math.max(relayMinimumTargetAt(previousFlushAt), creditReadyAt);
    }

    private void bufferRelayWrite(byte[] request, long callAt, long flushAt,
            long minimumTargetAt, long creditReadyAt) {
        bufferedRelayPlain.add(activeRelay.plain36());
        bufferedRelayWirePayload.add(activeRelay.encrypted36());
        bufferedRelayRequests.add(request.clone());
        bufferedRelayTimings.add("call_ms=" + callAt + "\n"
                + "flush_ms=" + flushAt + "\n"
                + "minimum_target_ms=" + minimumTargetAt + "\n"
                + "credit_ready_ms=" + creditReadyAt + "\n"
                + "backpressure_ms="
                + Math.max(0L, creditReadyAt - minimumTargetAt) + "\n"
                + "minimum_interval_late_ms="
                + (callAt - minimumTargetAt) + "\n");
    }

    private void flushRelayEvidence() throws IOException {
        if (activeRelay == null || relayEvidenceFlushed) {
            return;
        }
        relayHotPathActive = false;
        relayEvidenceFlushed = true;
        int eventIndex = 0;
        for (RelayHotPathEvidence.Event event
                : relayHotPathEvidence.events()) {
            evidence.saveEvent(event.category, event.label, event.value);
            eventIndex++;
        }
        for (int index = 0; index < relayHotPathEvidence.reads().size();
                index++) {
            RelayHotPathEvidence.ReadSample sample =
                    relayHotPathEvidence.reads().get(index);
            evidence.saveText("relay", String.format(Locale.US,
                    "timeline_read_%03d", index),
                    sample.asText());
            evidence.saveEvent("relay", String.format(Locale.US,
                    "timeline_read_raw_%03d", index), sample.value);
        }
        evidence.saveText("relay", "hot_path_buffer_summary",
                "events=" + eventIndex + "\n"
                + "event_bytes=" + relayHotPathEvidence.eventBytes() + "\n"
                + "read_samples=" + relayHotPathEvidence.reads().size()
                + "\n"
                + "read_bytes=" + relayHotPathEvidence.readBytes() + "\n");
        evidence.saveEvent("relay", "accepted_raw_stream",
                activeRelay.acceptedRawStream());
        for (int index = 0; index < activeRelay.data27Units().size(); index++) {
            evidence.saveEvent("relay", String.format(Locale.US,
                    "data27_%02d", index), activeRelay.data27Units().get(index));
        }
        evidence.saveEvent("relay", "combined81", activeRelay.combined81());
        evidence.saveEvent("relay", "combined_trigger",
                activeRelay.combinedTrigger());
        evidence.saveText("relay", "trigger_contract",
                "required_trigger_units="
                + activeRelay.requiredTriggerUnits() + "\n"
                + "combined_trigger_bytes="
                + activeRelay.combinedTrigger().length + "\n"
                + "combined81_bytes="
                + activeRelay.combined81().length + "\n");
        List<Long> triggerStarts = activeRelay.postArmData27StartOffsets();
        List<Long> triggerEnds = activeRelay.postArmData27EndOffsets();
        if (!triggerStarts.isEmpty()) {
            StringBuilder offsets = new StringBuilder();
            for (int index = 0; index < triggerStarts.size(); index++) {
                offsets.append("unit=").append(index)
                        .append(" start_raw_offset=")
                        .append(triggerStarts.get(index))
                        .append(" end_raw_offset=")
                        .append(triggerEnds.get(index)).append('\n');
            }
            evidence.saveText("relay", "post_vlc_data27_raw_offsets",
                    offsets.toString());
        }
        evidence.saveEvent("relay", "candidate36", activeRelay.candidate36());
        for (int index = 0; index < bufferedRelayRequests.size(); index++) {
            evidence.saveEvent("relay", String.format(Locale.US,
                    "plain36_%02d", index), bufferedRelayPlain.get(index));
            evidence.saveEvent("relay", String.format(Locale.US,
                    "wire_payload36_%02d", index),
                    bufferedRelayWirePayload.get(index));
            evidence.saveEvent("data36", String.format(Locale.US,
                    "relay_request_%02d", index),
                    bufferedRelayRequests.get(index));
            evidence.saveText("relay", "timing_" + index,
                    bufferedRelayTimings.get(index));
            if (index < bufferedRelayCredits.size()) {
                evidence.saveEvent("relay", String.format(Locale.US,
                        "credit_raw_%02d", index),
                        bufferedRelayCredits.get(index));
            }
        }
        evidence.saveText("relay", "write_summary",
                "payload_source=" + activeRelay.payloadSource() + "\n"
                + "units=" + bufferedRelayRequests.size() + "\n"
                + "credits=" + bufferedRelayCredits.size() + "\n"
                + "first_call_ms=" + firstDataOrigin + "\n"
                + "last_flush_ms=" + relayLastWriteAt + "\n");
        if (postVlcObservationClass.length() > 0) {
            evidence.saveText("relay", "post_vlc_final_classification",
                    "classification=" + postVlcObservationClass + "\n");
        }
        evidence.saveText("relay", "first_write_boundary",
                bufferedFirstWriteBoundary == null
                        ? "observed=false\n" : bufferedFirstWriteBoundary);
    }

    private double measureTickHz() throws Exception {
        byte[] first = read("tick_first", McuAssets.MCU_TICK, 4);
        long started = SystemClock.elapsedRealtime();
        SystemClock.sleep(250);
        byte[] second = read("tick_second", McuAssets.MCU_TICK, 4);
        long elapsed = SystemClock.elapsedRealtime() - started;
        long delta = (Bytes.u32le(second, 0) - Bytes.u32le(first, 0))
                & 0xffffffffL;
        double hz = delta * 1000.0 / elapsed;
        if (hz < 10 || hz > 100000) {
            throw new IOException("MCU tick速率不可信：" + hz);
        }
        evidence.saveText("timing", "tick_rate", "hz=" + hz + "\n");
        return hz;
    }

    private void abortOnBridgeEntrySessionSignal(String label, byte[] raw)
            throws Exception {
        String signal = DmrProtocol.sessionRebuildSignal(raw);
        if (signal == null) {
            return;
        }
        evidence.saveEvent(label, "entry_session_signal_raw", raw);
        evidence.saveText(label, "aborted_before_first_hpi_write",
                "reason=" + signal + "\n"
                + "control_write_attempts="
                + transport.controlWriteAttempts() + "\n"
                + "control_flush_completed="
                + transport.controlFlushCompleted() + "\n");
        throw new IOException("桥接入口发现会话重建信号：" + signal);
    }

    private void recoverAfterFailure() {
        if (transport != null && transport.mode() == SerialTransport.Mode.BRIDGE) {
            try {
                long remaining = Math.max(0, bridgeExpectedExitAt
                        - SystemClock.elapsedRealtime());
                byte[] raw = transport.readWindow(remaining);
                evidence.saveEvent("recovery", "bridge_wait_raw", raw);
                transport.markAutomaticBridgeExit();
                bridgeExpectedExitAt = 0;
                int flag = readInt("recovery_bridge_flag",
                        McuAssets.BRIDGE_FLAG, 1);
                int vector = readInt("recovery_bridge_vector",
                        McuAssets.VECTOR, 4);
                if (flag != 0 || vector != TxStateMachine.ORIGINAL_SYSTICK) {
                    throw new IOException("异常恢复退桥状态不成立");
                }
                if (!rfMayBeActive) {
                    evidence.saveText("recovery",
                            "postexit_restore_priority",
                            "bridge_exit_confirmed=true\n"
                            + "full_post_state_deferred_to_per_region_restore=true\n");
                } else {
                    evidence.saveText("recovery",
                            "postexit_full_sram_deferred",
                            "reason=rf_may_be_active\n");
                }
            } catch (Throwable error) {
                recordRecoveryError("bridge_exit", error);
            }
        }
        if (rfMayBeActive) {
            if (memory == null || transport == null
                    || transport.mode() != SerialTransport.Mode.TEXT) {
                recordRecoveryError("rf_off", new IOException(
                        "串口未确认回到文本面，禁止发送RF关闭memwrite"));
            } else {
                try {
                    // RF仍可能活动时只读取关闭桩会改动的最小状态；
                    // 全量SRAM必须放到确认停发之后，不能延长载波。
                    captureRfStateBeforeOff(
                            "recovery_rf_pre_off_before_mutation");
                    memory.writeWord(McuAssets.RF_MARKER, 0);
                    memory.writeWord(McuAssets.VECTOR, McuAssets.RF_OFF_ENTRY);
                    SystemClock.sleep(150);
                    byte[] marker = read("recovery_rf_off_marker",
                            McuAssets.RF_MARKER, 4);
                    byte[] rfTimingState = read(
                            "recovery_rf_off_timing_state",
                            McuAssets.RF_TIMING_STATE,
                            McuAssets.RF_TIMING_STATE_LENGTH);
                    byte[] vector = read("recovery_rf_off_vector",
                            McuAssets.VECTOR, 4);
                    int gate = Bytes.u32le(rfTimingState,
                            McuAssets.ACTIVE_GATE_OFFSET);
                    if (Bytes.u32le(marker, 0)
                            == TxStateMachine.RF_OFF_MARKER
                            && gate == 0
                            && Bytes.u32le(vector, 0)
                            == TxStateMachine.ORIGINAL_SYSTICK) {
                        rfMayBeActive = false;
                        rfOffConfirmed = true;
                        evidence.saveText("recovery",
                                "rf_off_restore_priority",
                                "rf_off_minimum_state_confirmed=true\n"
                                + "full_post_state_deferred_to_per_region_restore=true\n");
                    } else {
                        throw new IOException("异常恢复RF关闭证据不成立");
                    }
                } catch (Throwable error) {
                    recordRecoveryError("rf_off", error);
                }
            }
        }
        if (!rfMayBeActive && transport != null
                && transport.mode() == SerialTransport.Mode.TEXT
                && sram != null) {
            try {
                sram.restoreAll();
                captureRfEdgeCounter("恢复后");
            } catch (Throwable error) {
                recordRecoveryError("sram_restore", error);
            }
        }
        if (!rfMayBeActive && transport != null
                && transport.mode() == SerialTransport.Mode.TEXT) {
            try {
                restorePrivacyOff();
            } catch (Throwable error) {
                recordRecoveryError("privacy_restore", error);
            }
            try {
                verifyPostRestoreText();
            } catch (Throwable error) {
                recordRecoveryError("text_baseline", error);
            }
            try {
                restorePowerSavePreimage();
            } catch (Throwable error) {
                recordRecoveryError("power_save_restore", error);
            }
            try {
                disableDebugOutputAndVerify();
            } catch (Throwable error) {
                recordRecoveryError("debug_print_restore", error);
            }
        }
        rebootRequired = rfMayBeActive
                || !recoveryErrors.isEmpty()
                || (transport != null
                && transport.mode() != SerialTransport.Mode.TEXT)
                || (sram != null && !sram.restored())
                || privacyMutation
                || powerSaveMutation
                || !powerSaveRestored
                || (transport != null && !textRecoveryConfirmed)
                || (debugOutput != null && !debugOutput.restored());
        if (rfOffConfirmed && !rebootRequired) {
            try {
                publishRfReleaseAfterRestore();
            } catch (Throwable error) {
                recordRecoveryError("rf_release_after_restore", error);
                rebootRequired = true;
            }
        }
    }

    private void publishRfReleaseAfterRestore() throws IOException {
        if (!rfOffConfirmed || rfMayBeActive || sram == null || !sram.restored()
                || privacyMutation || powerSaveMutation || !powerSaveRestored
                || !textRecoveryConfirmed || debugOutput == null
                || !debugOutput.restored() || !recoveryErrors.isEmpty()) {
            throw new IOException("RF关闭后恢复闭环尚未完整，禁止发布释放凭证");
        }
        evidence.saveCredential("rf_release_attestation",
                "launch_session_id=" + sessionId + "\n"
                + "production_rf_off_confirmed=true\n"
                + "session_restore_complete=true\n");
    }

    private void verifyPostRestoreText() throws Exception {
        byte[] connect = text("AT+DMOCONNECT", 1800);
        byte[] version = text("AT+DMOGETSOFTVERSION", 1800);
        if (!containsLine(connect, "+DMOCONNECT:0")
                || !containsLine(version,
                "+DMOGETSOFTVERSION:" + MODULE_VERSION)) {
            throw new IOException("恢复后模块连接或版本核验失败");
        }
    }

    private void disableDebugOutputAndVerify() throws Exception {
        if (debugOutput != null) {
            debugOutput.disable();
        }
        byte[] version = text("AT+DMOGETSOFTVERSION", 1800);
        if (!containsLine(version,
                "+DMOGETSOFTVERSION:" + MODULE_VERSION)) {
            throw new IOException("关闭print后模块版本核验失败");
        }
        textRecoveryConfirmed = true;
    }

    private void recordRecoveryError(String stage, Throwable error) {
        String value = stage + ":" + error.getClass().getSimpleName() + ":"
                + String.valueOf(error.getMessage());
        recoveryErrors.add(value);
        if (evidence != null) {
            try {
                evidence.saveText("recovery", "failure_" + recoveryErrors.size(),
                        value + "\n");
            } catch (Throwable evidenceError) {
                recoveryErrors.add("evidence:" + evidenceError.getClass()
                        .getSimpleName() + ":"
                        + String.valueOf(evidenceError.getMessage()));
            }
        }
    }

    private void closeTransport() {
        if (powerSaveMutation && !powerSaveRestoreAttempted
                && debugOutput != null && debugOutput.enabled()
                && transport != null
                && transport.mode() == SerialTransport.Mode.TEXT) {
            try {
                restorePowerSavePreimage();
            } catch (Throwable error) {
                recordRecoveryError("power_save_close", error);
            }
        }
        if (debugOutput != null && !debugOutput.restored()
                && transport != null
                && transport.mode() == SerialTransport.Mode.TEXT) {
            try {
                debugOutput.disable();
            } catch (Throwable error) {
                recordRecoveryError("debug_print_close", error);
            }
        }
        if (transport != null
                && transport.mode() != SerialTransport.Mode.CLOSED) {
            transport.close();
        }
    }

    private void restorePrivacyOff() throws Exception {
        if (!privacyMutation || transport == null
                || transport.mode() != SerialTransport.Mode.TEXT) {
            return;
        }
        requireLine(text("AT+DMOSETDIGITALCH=" + CHANNEL_OFF, 1800),
                "+DMOSETDIGITALCH:0", "privacy-off恢复设置失败");
        requireLine(text("AT+DMOGETDIGITALCH", 1800),
                "+DMOGETDIGITALCH:" + CHANNEL_OFF,
                "privacy-off恢复回读失败");
        byte[][] rounds = captureRuntimeRounds("privacy_off_restored");
        byte[] restored = requireStableRuntime(rounds, "privacy-off恢复");
        evidence.saveText("privacy", "restore_comparison",
                "matches_entry_baseline=" + Arrays.equals(
                        privacyOffBaseline14, restored) + "\n"
                + "stable_residual_accepted=true\n");
        privacyMutation = false;
    }

    private String finishResult(boolean success, String mode, String failure)
            throws IOException {
        if (evidence == null) {
            return success ? "通过" : "失败：" + failure;
        }
        String result = "result=" + (success ? "PASS" : "FAIL") + "\n"
                + "launch_session_id=" + sessionId + "\n"
                + "terminal_phase=DONE_" + (success ? "PASS" : "FAIL")
                + "\n"
                + "mode=" + mode + "\n"
                + "phase=" + (activeMachine == null ? "NOT_CREATED"
                : activeMachine.phase()) + "\n"
                + "setup_acks=" + (activeMachine == null ? 0
                : activeMachine.setupAcks()) + "\n"
                + "setup0_retry_used=" + (activeMachine != null
                && activeMachine.setup0RetryUsed()) + "\n"
                + "vlc_acks=" + (activeMachine == null ? 0
                : activeMachine.vlcAcks()) + "\n"
                + "termination_acks=" + (activeMachine == null ? 0
                : activeMachine.terminationAcks()) + "\n"
                + "cleanup_acks=" + (activeMachine == null ? 0
                : activeMachine.cleanupAcks()) + "\n"
                + "data36_written=" + (activeMachine == null ? 0
                : activeMachine.dataWritten()) + "\n"
                + "relay_data27_count=" + (activeRelay == null ? 0
                : activeRelay.data27Count()) + "\n"
                + "relay_pre_arm_data27_count=" + (activeRelay == null ? 0
                : activeRelay.preArmData27Count()) + "\n"
                + "relay_post_arm_data27_total=" + (activeRelay == null ? 0
                : activeRelay.postArmData27Total()) + "\n"
                + "post_vlc_observation_class="
                + (postVlcObservationClass.length() == 0
                ? "NOT_APPLICABLE" : postVlcObservationClass) + "\n"
                + "relay_trigger_required_units=" + (activeRelay == null ? 0
                : activeRelay.requiredTriggerUnits()) + "\n"
                + "relay_credit_accepted=" + (activeRelay != null
                && activeRelay.creditAccepted()) + "\n"
                + "relay_units_written=" + (activeRelay == null ? 0
                : activeRelay.unitsWritten()) + "\n"
                + "relay_credits_consumed=" + (activeRelay == null ? 0
                : activeRelay.creditsConsumed()) + "\n"
                + "relay_payload_source=" + (activeRelay == null
                ? "none" : activeRelay.payloadSource()) + "\n"
                + "control_write_attempts=" + (transport == null ? 0
                : transport.controlWriteAttempts()) + "\n"
                + "control_flush_completed=" + (transport == null ? 0
                : transport.controlFlushCompleted()) + "\n"
                + "data36_write_attempts=" + (transport == null ? 0
                : transport.dataWriteAttempts()) + "\n"
                + "data36_flush_completed=" + (transport == null ? 0
                : transport.dataFlushCompleted()) + "\n"
                + "first_bridge_exit_confirmed="
                + firstBridgeExitConfirmed + "\n"
                + "second_bridge_exit_confirmed="
                + secondBridgeExitConfirmed + "\n"
                + "rf_prepare_executed=" + rfPrepareExecuted + "\n"
                + "rf_off_confirmed=" + rfOffConfirmed + "\n"
                + "rf_may_be_active=" + rfMayBeActive + "\n"
                + "device_deadline_arm_requested="
                + deviceDeadlineArmRequested + "\n"
                + "device_deadline_armed=" + deviceDeadlineArmed + "\n"
                + "sram_transaction_started=" + (sram != null) + "\n"
                + "sram_mutation_started=" + (sram != null
                && sram.mutated()) + "\n"
                + "sram_restored=" + (sram == null
                || sram.restoredOrUntouched()) + "\n"
                + "debug_print_restored=" + (debugOutput == null
                || debugOutput.restored()) + "\n"
                + "privacy_restored=" + !privacyMutation + "\n"
                + "power_save_disabled=" + powerSaveWasDisabled + "\n"
                + "power_save_mutation_active=" + powerSaveMutation + "\n"
                + "power_save_restored=" + powerSaveRestored + "\n"
                + "power_save_entry_value=" + powerSaveEntryValue + "\n"
                + "power_save_restored_value=" + powerSaveRestoredValue + "\n"
                + "text_recovery_confirmed=" + textRecoveryConfirmed + "\n"
                + "reboot_required=" + rebootRequired + "\n"
                + "recovery_error_count=" + recoveryErrors.size() + "\n"
                + "recovery_errors=" + joinRecoveryErrors() + "\n"
                + "failure=" + failure + "\n";
        evidence.writeAtomicResult(result);
        File manifest = evidence.finishManifest();
        return (success ? "通过" : "失败") + "\n证据目录="
                + evidence.directory().getAbsolutePath() + "\n清单="
                + manifest.getAbsolutePath() + (failure.length() == 0
                ? "" : "\n原因=" + failure);
    }

    private String joinRecoveryErrors() {
        StringBuilder value = new StringBuilder();
        for (int index = 0; index < recoveryErrors.size(); index++) {
            if (index != 0) {
                value.append(" | ");
            }
            value.append(recoveryErrors.get(index).replace('\n', ' ')
                    .replace('\r', ' '));
        }
        return value.toString();
    }

    private void upload(String regionName, byte[] value) throws Exception {
        for (SramTransaction.Region region : sram.regions()) {
            if (region.name.equals(regionName)) {
                sram.upload(region, value);
                return;
            }
        }
        throw new IOException("未备份SRAM区域：" + regionName);
    }

    private byte[] asset(String name, int length, String sha) throws Exception {
        return McuAssets.loadVerified(assets.open(name), length, sha);
    }

    private byte[] text(String command, long timeoutMs) throws Exception {
        byte[] response = transport.exchangeText(command, timeoutMs);
        evidence.saveText("text", "command", command + "\n");
        evidence.saveEvent("text", "response", response);
        return response;
    }

    private void disablePowerSaveAndVerify() throws Exception {
        McuMemory.ReadResult first = memory.read(McuAssets.POWER_SAVE_FLAG, 1);
        SystemClock.sleep(100);
        McuMemory.ReadResult second = memory.read(McuAssets.POWER_SAVE_FLAG, 1);
        saveRead("power_save_before_0", first);
        saveRead("power_save_before_1", second);
        powerSaveEntryValue = requireStablePowerSaveValue(first.parsed,
                second.parsed, "省电入口原像");
        powerSaveMutation = true;
        powerSaveRestored = false;
        requireLine(text("AT+DMOSETPWRSAVELV=off", 1800),
                "+DMOSETPWRSAVELV:0", "关闭省电调度失败");
        McuMemory.ReadResult disabledFirst = memory.read(
                McuAssets.POWER_SAVE_FLAG, 1);
        SystemClock.sleep(100);
        McuMemory.ReadResult disabledSecond = memory.read(
                McuAssets.POWER_SAVE_FLAG, 1);
        saveRead("power_save_after_off_0", disabledFirst);
        saveRead("power_save_after_off_1", disabledSecond);
        if (requireStablePowerSaveValue(disabledFirst.parsed,
                disabledSecond.parsed, "省电off确认") != 0) {
            throw new IOException("省电off确认后固件标志不是稳定零值");
        }
        powerSaveWasDisabled = true;
    }

    private void restorePowerSavePreimage() throws Exception {
        if (!powerSaveMutation || transport == null
                || transport.mode() != SerialTransport.Mode.TEXT) {
            return;
        }
        if (powerSaveRestoreAttempted) {
            throw new IOException("省电原像恢复已经尝试，禁止重复长时回读");
        }
        powerSaveRestoreAttempted = true;
        if (debugOutput == null || !debugOutput.enabled()) {
            throw new IOException("调试输出已关闭，禁止执行必然无响应的memread");
        }
        if (powerSaveEntryValue != 0 && powerSaveEntryValue != 1) {
            throw new IOException("省电入口原像无效，禁止猜测恢复");
        }
        String value = powerSaveEntryValue == 1 ? "on" : "off";
        requireLine(text("AT+DMOSETPWRSAVELV=" + value, 1800),
                "+DMOSETPWRSAVELV:0", "恢复省电入口原像失败");
        McuMemory.ReadResult restoredFirst = memory.read(
                McuAssets.POWER_SAVE_FLAG, 1);
        SystemClock.sleep(100);
        McuMemory.ReadResult restoredSecond = memory.read(
                McuAssets.POWER_SAVE_FLAG, 1);
        saveRead("power_save_restored_0", restoredFirst);
        saveRead("power_save_restored_1", restoredSecond);
        powerSaveRestoredValue = requireStablePowerSaveValue(
                restoredFirst.parsed, restoredSecond.parsed,
                "省电原像恢复");
        if (powerSaveRestoredValue != powerSaveEntryValue) {
            throw new IOException("省电恢复值与入口真实原像不一致");
        }
        powerSaveMutation = false;
        powerSaveRestored = true;
    }

    static int requireStablePowerSaveValue(byte[] first, byte[] second,
            String stage) throws IOException {
        if (first == null || second == null || first.length != 1
                || second.length != 1) {
            throw new IOException(stage + "长度不是单字节");
        }
        int firstValue = first[0] & 0xff;
        int secondValue = second[0] & 0xff;
        if (firstValue != secondValue) {
            throw new IOException(stage + "两轮动态不一致");
        }
        if (firstValue != 0 && firstValue != 1) {
            throw new IOException(stage + "不是已知二值状态");
        }
        return firstValue;
    }

    private byte[] read(String label, int address, int length) throws Exception {
        McuMemory.ReadResult result = memory.read(address, length);
        saveRead(label, result);
        return result.parsed;
    }

    private int readInt(String label, int address, int length) throws Exception {
        byte[] value = read(label, address, length);
        return length == 1 ? value[0] & 0xff : Bytes.u32le(value, 0);
    }

    private void saveRead(String label, McuMemory.ReadResult result)
            throws Exception {
        evidence.saveEvent("memread", label + "_raw", result.raw);
        evidence.saveEvent("memread", label + "_parsed", result.parsed);
        evidence.saveText("memread", label + "_meta",
                "command=" + result.command + "\nattempt=" + result.attempt
                        + "\n");
    }

    private void captureAllSramRegions(String stage) throws Exception {
        captureRfEdgeCounter(stage);
        for (SramTransaction.Region region : sram.regions()) {
            McuMemory.ReadResult result = memory.read(region.address,
                    region.length);
            evidence.save(stage, region, result);
        }
    }

    private void captureRfEdgeCounter(String stage) throws Exception {
        McuMemory.ReadResult result = memory.read(McuAssets.RF_EDGE_COUNTER,
                McuAssets.RF_EDGE_COUNTER_LENGTH);
        saveRfEdgeCounterEvidence(evidence, stage, result);
    }

    static void saveRfEdgeCounterEvidence(EvidenceStore store, String stage,
            McuMemory.ReadResult result) throws Exception {
        store.save(stage, new SramTransaction.Region("rf_edge_counter",
                McuAssets.RF_EDGE_COUNTER,
                McuAssets.RF_EDGE_COUNTER_LENGTH), result);
    }

    private void captureRfStateBeforeOff(String stage) throws Exception {
        McuMemory.ReadResult marker = memory.read(McuAssets.RF_MARKER, 4);
        McuMemory.ReadResult timing = memory.read(McuAssets.RF_TIMING_STATE,
                McuAssets.RF_TIMING_STATE_LENGTH);
        McuMemory.ReadResult vector = memory.read(McuAssets.VECTOR, 4);
        saveRead(stage + "_rf_marker", marker);
        saveRead(stage + "_rf_timing_state", timing);
        saveRead(stage + "_systick_vector", vector);
    }

    private String deadlinePreparationCredential() {
        return deadlinePreparationCredential(System.currentTimeMillis() / 1000L);
    }

    private String deadlinePreparationCredential(long rfMayStartEpoch) {
        if (rfMayStartEpoch <= 0) {
            throw new IllegalArgumentException("RF可能开始时刻无效");
        }
        return "launch_session_id=" + sessionId + "\n"
                + "rf_preparation_armed=true\n"
                + "rf_timeout_sec=30\n"
                + "rf_may_start_epoch="
                + rfMayStartEpoch + "\n";
    }

    static String deadlineArmRequestCredential(String launchSessionId) {
        if (launchSessionId == null || launchSessionId.length() == 0) {
            throw new IllegalArgumentException("截止器武装请求缺少会话标识");
        }
        return "launch_session_id=" + launchSessionId + "\n"
                + "first_bridge_exit_confirmed=true\n"
                + "rf_prepare_executed=false\n"
                + "rf_may_be_active=false\n"
                + "rf_timeout_sec=30\n";
    }

    static String firstBridgeDeadlineArmRequestCredential(
            String launchSessionId) {
        if (launchSessionId == null || launchSessionId.length() == 0) {
            throw new IllegalArgumentException("第一桥截止器武装请求缺少会话标识");
        }
        return "launch_session_id=" + launchSessionId + "\n"
                + "first_bridge_exit_confirmed=false\n"
                + "rf_prepare_executed=false\n"
                + "rf_may_be_active=false\n"
                + "rf_window_kind=first_bridge\n"
                + "rf_timeout_sec=30\n";
    }

    private String deadlineWindowCredential(long elapsedMs, int marker) {
        return "launch_session_id=" + sessionId + "\n"
                + "rf_window_started=true\n"
                + "rf_timeout_sec=30\n"
                + "rf_window_started_epoch="
                + (System.currentTimeMillis() / 1000L) + "\n"
                + "elapsed_realtime_ms=" + elapsedMs + "\n"
                + "marker=" + String.format(Locale.US, "0x%08x", marker)
                + "\n";
    }

    private void requireDeviceDeadlineArmed() throws Exception {
        File external = context.getExternalFilesDir(null);
        if (external == null) {
            throw new IOException("无法定位设备侧截止器共享目录");
        }
        File marker = new File(new File(external, "deadlines"),
                sessionId + ".marker");
        long deadline = SystemClock.elapsedRealtime() + 20000;
        while (!marker.isFile() && SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(100);
        }
        if (!marker.isFile()) {
            throw new IOException("RF前未发现同会话设备侧截止器武装标记");
        }
        byte[] raw;
        try (FileInputStream input = new FileInputStream(marker)) {
            raw = readAll(input);
        }
        String value = new String(raw, StandardCharsets.UTF_8);
        if (!containsExactField(value, "launch_session_id", sessionId)
                || !containsExactField(value, "device_armed", "true")
                || !containsExactField(value, "rf_timeout_sec", "30")) {
            throw new IOException("设备侧截止器武装标记内容或会话绑定错误");
        }
        evidence.saveEvent("rf", "device_deadline_marker_before_prep", raw);
        deviceDeadlineArmed = true;
    }

    private static boolean containsExactField(String text, String key,
            String expected) {
        if (text == null || key == null || expected == null) {
            return false;
        }
        String wanted = key + "=" + expected;
        for (String line : text.split("\\r?\\n")) {
            if (wanted.equals(line)) {
                return true;
            }
        }
        return false;
    }

    private void status(String value) {
        if (status != null) {
            status.update(value);
        }
    }

    private static boolean runtimeMirrorMatches(byte[] runtime14,
            byte[] mirror) {
        int[] indices = {1, 2, 10, 11, 12, 13};
        if (runtime14 == null || runtime14.length != 14
                || mirror == null || mirror.length != 12) {
            return false;
        }
        for (int index = 0; index < indices.length; index++) {
            if (mirror[index] != runtime14[indices[index]]) {
                return false;
            }
        }
        return true;
    }

    private static void sleepUntil(long deadlineMs) {
        long remaining;
        while ((remaining = deadlineMs - SystemClock.elapsedRealtime()) > 0) {
            SystemClock.sleep(Math.min(remaining, 50));
        }
    }

    private static void requireLine(byte[] response, String line,
            String message) throws IOException {
        if (!containsLine(response, line)) {
            throw new IOException(message);
        }
    }

    private static boolean containsLine(byte[] response, String line) {
        String text = new String(response, StandardCharsets.US_ASCII)
                .replace("\r", "");
        for (String item : text.split("\n")) {
            if (item.trim().equals(line)) {
                return true;
            }
        }
        return false;
    }

    private static byte[] le32(int value) {
        return new byte[] {(byte) value, (byte) (value >>> 8),
                (byte) (value >>> 16), (byte) (value >>> 24)};
    }

    private static byte[] requireStableRuntime(byte[][] rounds, String label)
            throws IOException {
        if (rounds == null || rounds.length != 3 || rounds[0] == null
                || rounds[0].length != 14) {
            throw new IOException(label + "运行快照不完整");
        }
        for (int index = 1; index < rounds.length; index++) {
            if (!Arrays.equals(rounds[0], rounds[index])) {
                throw new IOException(label + "14字节运行快照不稳定");
            }
        }
        return rounds[0].clone();
    }

    private static byte[] join(byte[]... values) {
        int length = 0;
        for (byte[] value : values) {
            length += value.length;
        }
        byte[] result = new byte[length];
        int offset = 0;
        for (byte[] value : values) {
            System.arraycopy(value, 0, result, offset, value.length);
            offset += value.length;
        }
        return result;
    }

    private static byte[] readAll(InputStream input) throws IOException {
        byte[] buffer = new byte[4096];
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        int count;
        while ((count = input.read(buffer)) >= 0) {
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }
}
