package net.elfradio.h13dmrtx;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * v2.03 实时 relay：三个 type=0x20/field=1/length=27 单元到达后写出第一包；
 * job.md 里程碑三把最大单元数从 1 改为 3，后续每包必须消费一条新鲜短信用。
 */
final class RealtimeRelay {
    static final int REQUIRED_UNITS = 3;
    static final int EXTERNAL_SOURCE_TRIGGER_UNITS = 0;
    static final int SOFTWARE_REPLACEMENT_TRIGGER_UNITS = 1;
    static final int COMBINED_BYTES = 81;
    static final int PLAIN_BYTES = 36;
    static final int CONTINUOUS_UNITS = 3;
    static final int CONTINUOUS_BYTES = 108;
    static final int SUPERFRAME_UNITS = 5;
    static final int SUPERFRAME_BYTES = 180;
    static final int SPEECH_UNITS = 25;
    static final int SPEECH_BYTES = 900;
    static final int TRIPLE_SOS_UNITS = 78;
    static final int TRIPLE_SOS_BYTES = TRIPLE_SOS_UNITS * PLAIN_BYTES;

    // 人声测试素材：26 个字母加 10 个数字逐个朗读，28.8 秒、1440 个语音帧。
    // 选用人声而非音调，是因为语音声码器按人声建模；实测摩尔斯经该编解码链
    // 之后已无法辨认字符，而人声内容完整可辨。
    // 1440 帧同时能被 3 和 4 整除，两种打包格式都不需要补位，可直接对照。
    static final int SPEECH_AZ09_FRAMES = 1440;
    static final int SPEECH_AZ09_BYTES =
            SPEECH_AZ09_FRAMES * TxPlan.AMBE_BYTES_PER_FRAME;

    // 首次真机低功率发射验证素材：只念 A、B、C，填充静音后正好 204 帧、
    // 4.08 秒。204 同时能被 3 和 4 整除，两种打包格式都不需要补位：
    //   27 字节 DMR 格式 68 包 × 60 毫秒 = 4.08 秒（当前发射用这个）
    //   36 字节 dPMR 格式 51 包 × 80 毫秒 = 4.08 秒
    // 最初做成 200 帧只考虑了 36 字节格式；2026-09-19 两次发射证明 36 字节
    // 是接口文档里 dPMR 的定义，DMR 每突发是 3 帧 27 字节 60 毫秒，发错格式
    // 会让对端解出机械噪音，因此改为两种格式都整除。
    // 长度还要登记进下面构造函数的历史格式长度白名单（仅 36 字节格式走该
    // 白名单），否则会被判为"替换明文或来源无效"。
    static final int SPEECH_SHORT_ABC_FRAMES = 204;
    static final int SPEECH_SHORT_ABC_BYTES =
            SPEECH_SHORT_ABC_FRAMES * TxPlan.AMBE_BYTES_PER_FRAME;
    // 单元数按 27 字节 DMR 突发算：204 帧 = 68 单元 = 4.08 秒。
    static final int SPEECH_SHORT_ABC_UNITS =
            SPEECH_SHORT_ABC_BYTES / DmrProtocol.VOICE_BURST_BYTES;
    // 重放载荷：2026-09-19 用已验收的长流采集从真实 TYT 发射中取得的
    // 27 字节 CHAN_D 单元，操作者已听感确认为清晰人声。取能量最高的
    // 132 个单元 = 7.92 秒——不取全部 244 个单元（14.6 秒），因为射频
    // 保持窗只有 16 秒，按 ackPacedActiveRfBudgetWellFormedFor 算 244 与
    // 200 个单元都会超预算，132 个留约 1.7 秒余量。
    // 载荷已是模块交出来的线上单元本身，发射时原样送出，不再经过
    // 编码器或隐私流水线。
    // 当前重放资产：204 帧，按 27 字节 DMR 突发 = 68 单元 = 1836 字节。
    static final int DMR_REPLAY_UNITS = 68;
    static final int DMR_REPLAY_BYTES = DMR_REPLAY_UNITS * 27;

    static final int MAX_ACCEPTED_RAW_BYTES = 1024 * 1024;
    static final int SPEECH_BYTE_OFFSET = 1296;

    static final int FROZEN_ASSET_BYTES = 6588;
    static final String FROZEN_ASSET_SHA256 =
            "AD266884474DDF3D79484790DEB3038A42A2C6E81099BF61B687F1ECD29C7602";
    static final String FROZEN_UNIT0_SHA256 =
            "83FA8EA05FB8DAB16E78944209F86B4FE5D06A567F82FFD290D7E3DD842DC0EB";
    static final String FROZEN_UNIT1_SHA256 =
            "40EF98A1996BE0CBB5B7FAA6A64B1D7E943B4A3C124EE34FA8B034D137038F7D";
    static final String FROZEN_UNIT2_SHA256 =
            "319CDCAAE7CCBE3C6BDE11D01B110ED5F894EB2BBA6B6A22E4008B49E99CB43E";
    static final String FROZEN_108_SHA256 =
            "20DF1122CDD7E325B01582DAB9DB073545C91FE08F87812D8F26E5F2AF7F52BC";
    static final String FROZEN_UNIT3_SHA256 =
            "7F91502066FBDCDC3DF97839941CC278AE5FAC69EFFFED4DB484472DEF78F910";
    static final String FROZEN_UNIT4_SHA256 =
            "179B2C2F0C6EBC36260BDA984D89E19C5FC212EDCD42752AA0C8C81A251AA815";
    static final String FROZEN_180_SHA256 =
            "68301713EA546B3FE878B47D36E907B6DFD9B81B3A69E6736CC74B9AC290B1C1";
    static final String FROZEN_SPEECH_900_SHA256 =
            "2107C38C0D59960E0CC839AB69E3EACF177A929B6F42957C4225CAAA83483097";
    static final String[] FROZEN_SPEECH_UNIT_SHA256 = {
            "8DA5EFF98AA0CFF913981A1E87A6E59EE36DB0808C57BD9AC246A806DCCDD393",
            "6152B1B7453554154DF2F5272D48938F62858BCB90A972B9E29595E60F692DF2",
            "723323BC09CF5BE04665FBAE7804C359669796B51D9A31011FFAA4BB3A44228F",
            "79865E74D6AB17939CDBBE1877E30CC3CE1A595C8A13304F27A16991F4C0A578",
            "BCB760CD6EEF169F30748B30B085EF1090749E54CB21F062244BDC23C01EED56",
            "E55D676FD5CDC96441D0ECF62F3142DE2D8FF5326A7168D438BD8DEE19B398CA",
            "61A93C662630546DB153809BFA872A58EB901525BE9B9C2F8A4E9AA75EFC2158",
            "9ECB067F1E5B9CDC3BD2A4EE7D92A1BA2A7F62C0A82971E1673918C00F175321",
            "B2BBB0206E275C6EE51B2F7F795F4A13B50082D51DADD2C4F097D29C1AA1631B",
            "30BBBCA72DB3BDEC00C477A1CDD724915A04D4E37B325FDAB552FEA037BA4533",
            "7332BAFF5FA6A2B52A33223D12C6D6D0C2C5238EFB196A848BA097D308CBFF76",
            "04DDB514A01E49B51C1533DDAF803BACCA3322BA45600E5A0B45B16C47C499A2",
            "8F83F5889D11FEEE4D95C063B539FAEA36CD69DBE37AF64A6903914DCACABF29",
            "F44D6098D7AD6FA0401B6B584D7DC359F1A1C37CBCFAF130F5B17D855D2BDACB",
            "2DB2D491B4F860B5A2BE7C4649E11CDF3D95E835BCD5E2FC518AD2B275A03102",
            "9B3837ED4A1D70D930E8DDF7487E1B1F3557A3AEAA2A277E450E26D3083161F0",
            "F8D926DCE6B21CDC82F3029944DF7711DB62E5D82F3941A0FDD00EBEF624BA40",
            "8D6BB3DE0CD323FFD973780B0347BD406C1505A5AF46B267E4D605922358AE8E",
            "03ED828C27251F57C678BF87A6B9051EDCBA601D77449E340D452E3676F5C55D",
            "FA9B384CF289BAB73E53D1BCAC0C784C72DFA050AF2F95D531A33A7E02F45B22",
            "A25BFE604D6F41D6A56EEABC915CF212ABD2842D02DAC2CFB05F9E97D4363536",
            "CC788948D99D97B7F97B7021B0F44326A0D88B0893A088FB3F7E096AF50AEB06",
            "54BEFFAD3056A779111BACEB1E5FA40F761799909D7C6F22676311415E21ABC5",
            "06ACBE1615B7E5173B2665B11DBDD9525E152BFD6FF053C864659D65DCAB3152",
            "FDF29197F184975DB3858C480D01F4A7F72B7195C35602A18CB441A9C288E42F"
    };
    static final String[] FROZEN_SPEECH_REQUEST_SHA256 = {
            "86DA1C498EEA1678B6ED2306DF3A8335D46C837337E97DFA69F0819827C8C63D",
            "BCF6FEBB720F517568FF283ABE0D5F583141775AEE135E3DC8F86C27ED52FEFF",
            "713F186FC9AB5B1A34BDEF95B1A80A53C2D476D1AB08C760A1C3FDF43457E1B3",
            "499CB6565BB8A3B57FC37F88774DEFC30D6277520B856B113BC40E28B2FB5403",
            "0E116E143A5DE57A1B48A383BB005CB610048A052A31DDE318F01317409D1D47",
            "8E6F5A4FDCB8A32794A92A92BB8337BEB812AC5F1B164133DABDEDA8A8D63294",
            "7D985C9C870A176EE3FD307D4AA53A9040FF28A8B851F8763C11D4E00DDB3C72",
            "579E487607E29C7E188440024B7ED92CEC142F8084BEEE017EA08D40F76349D9",
            "2AE3D13D9E72D7AF543193EBCCDDD0E5D681EADBABA7DD6DA05658B083C5B197",
            "B1EADF15C095EE56C3CF81997AA26C5D539BECBFE3186841D30F91408D752456",
            "53D0FD10667D47D61F735DBAEEBE8B53F77BC55DB144747F8C52B6342597ACEE",
            "DFAAB8110D5B15FBCD4FF6DB3EAF13BE4EECF5ADDDB9CB66D9DCAFC52FC5878B",
            "CB905241B29D3F2A6B7E71F52EF4A988902BF3C5FB964A85382294C3DADE8683",
            "3D0956FFC7C25DF832BDF9C900FC724C05BE82B4AEA3126EB92E10E20C2E1A82",
            "389E7B51B0AB7D442DD80EB10F23E81D52A777292F96450A3990412124C5CBA2",
            "0FB417A1537457F941EE054D1541F520BA8208FA425446C93D3C31DA5F7423A7",
            "7995EDB89F0C1CFE1D6C75072A39FB6D9DFD08B375F2B79A65D80C381B0F2B42",
            "D6FB4727DCFA567339A9EC284ADEF66DA9A96AD917E3D56F7CC210765A72E037",
            "AB0E26D07668A46967D32377CA99CCB4B49701A93A41B638446AF344B1D7F385",
            "CCA60559FAF31958013A5FD95E0E83657FE94507492AC81B28D694D38D6A17E1",
            "C77529FCCD00497C959A23E2D35E882BBC0C3ADA6A9C7A1288E9F83549CDFD9D",
            "7A46CD1CB1A086093527B171F7C5340147F3F5ADC22428D3D6CE491B7F3ED5FF",
            "1D032C3132D0C7856ED5BCFF2BE2EBC5C648327047D2DAD33E940D3ADC4C70D6",
            "90B7DF9C247D69F6E3115097988C4B6907648946DCA826E2A9528FAEAB86C0DC",
            "21353FF2C18DB9C8F54B02E369A0E682A72C4587B6DB1B6C72215EE151D1C12B"
    };
    static final String ENCODE_DMR_SILENCE_AMBE9_SHA256 =
            "118DFE909D6CDEFE1A3FBA1ECB715AB7E55DB2979EEE9C169BC2D8824DC11B0D";
    static final String ENCODE_DMR_SILENCE_36_SHA256 =
            "3BF8A6DFD5C5D9F2341E9693552C9A51D163CE3CD414B658567B9EE1C6580842";
    static final String ENCODE_DMR_TONE800_AMBE9_SHA256 =
            "234EE9C8C7956943548352A0B40C8279511CA6E2E8215DF07A7249256BDF470A";
    static final String ENCODE_DMR_TONE800_36_SHA256 =
            "09D2D1ABD97FCFAB3030C10316DC1DD76BF82E551649487FDAAB4F9038300567";
    static final String ENCODE_DMR_MORSE_AMBE9_SHA256 =
            "D4217935BF2F046A9DDEC9EC6AF358A4788AA82A466AFFE5DD76D118BD1BF5D2";
    static final int ENCODE_DMR_MORSE_UNIQUE_OFFSET = 36;
    static final String ENCODE_DMR_MORSE_UNIQUE_36_SHA256 =
            "34079A913D8133A30875B78AFB1FFA55BE5D68C41BC6A1E94A25C651E0B0B7F9";
    static final String ENCODE_DMR_MORSE_UNIQUE_UNIT2_SHA256 =
            "19200C829A98BBB5D5230E0B81B78D353CF2C85C9231A9A51BDD0BE4E506EDC8";
    static final String ENCODE_DMR_MORSE_UNIQUE_UNIT3_SHA256 =
            "7622E8AC0E922D1A8BB7D2F9F1982EE1F413F7A90396F1560E3550E61E08AE12";
    static final String ENCODE_DMR_MORSE_UNIQUE_108_SHA256 =
            "BAA477B1FCB10B0D1B3DC34D308E6D33295AD835356B219FBB8E0DA4E0EF57D8";
    static final String ENCODE_DMR_MORSE_UNIQUE_UNIT4_SHA256 =
            "5D1B8D92E8664CA3F980CA28EDEFAB438F8C9094EC191975EF45292360592390";
    static final String ENCODE_DMR_MORSE_UNIQUE_UNIT5_SHA256 =
            "50C73675CFE06F029121DB68DFF83C40173FD5097506A405DF1AAE50ED55F533";
    static final String ENCODE_DMR_MORSE_UNIQUE_180_SHA256 =
            "B1E2EEA143C717B81F6BF5079F3A8405AF92F66702D6AF9BCDDE418ED8A419A5";
    static final String ENCODE_DMR_TONE800_UNIT1_SHA256 =
            "00E86260E276E6A188A68EC75B1C99CF1DBD2F07D5CDB6F6D358DCE8A55B76A7";
    static final String ENCODE_DMR_TONE800_UNIT2_SHA256 =
            "DCC557CF9E28B2E23AFF969A7B91CA88A8F2C8D26BA77028AFFEA2BDA25E7638";
    static final String ENCODE_DMR_TONE800_108_SHA256 =
            "3759967F6A9C61B917C6D4AB6CB0703C740AC73729CB0A3DBB077492A870C3DC";
    static final String ENCODE_DMR_TONE800_UNIT3_SHA256 =
            "F8AD6756C9D2B616F6B4402D38EF5BBFFFCA0B2BCE4A16B296529EE6B5EA5881";
    static final String ENCODE_DMR_TONE800_UNIT4_SHA256 =
            "D23F7B244E4FF70FB72898CCF025577346ED0D75836D38EDAD6536DF599C1EE8";
    static final String ENCODE_DMR_TONE800_180_SHA256 =
            "1236C109F7A1ADFECF75AF0FEDBA3319325DBF0942874D907A29C990A7872949";

    private final byte[] runtime14;
    private final byte[] replacementStream;
    private final String replacementSource;
    private final boolean replacementIsFinalWirePayload;
    private final int maximumUnits;
    /**
     * 语音注入格式。默认为历史实现，切换前后语音帧流逐字节相同，
     * 只改变打包粒度与节拍。见 DmrProtocol.VoiceFormat。
     */
    private final DmrProtocol.VoiceFormat voiceFormat;
    private final int requiredTriggerUnits;
    private final boolean triggerOnlyAfterArm;
    private final boolean activePacedWrites;
    private final DmrPrivacy privacy;
    private final ByteArrayOutputStream units = new ByteArrayOutputStream();
    private final ByteArrayOutputStream acceptedRaw =
            new ByteArrayOutputStream();
    private final List<byte[]> data27 = new ArrayList<byte[]>();
    private final List<byte[]> creditCandidates = new ArrayList<byte[]>();
    private final List<byte[]> vlcAckFrames = new ArrayList<byte[]>();
    private final List<byte[]> activePacedCredits = new ArrayList<byte[]>();
    private byte[] carry = new byte[0];
    private boolean writeAttempted;
    private boolean flushed;
    private byte[] combined81 = new byte[0];
    private byte[] combinedTrigger = new byte[0];
    private byte[] candidate36 = new byte[0];
    private byte[] plain36 = new byte[0];
    private byte[] request44 = new byte[0];
    private int unitsWritten;
    private int creditsConsumed;
    private boolean postVlcArmed;
    private long rawBytesAccepted;
    private long postVlcArmRawOffset = -1;
    private long carryStartRawOffset;
    private int preArmData27Count;
    private int postArmData27Total;
    private int postArmOtherFrameTotal;
    private final List<Long> postArmData27StartOffsets =
            new ArrayList<Long>();
    private final List<Long> postArmData27EndOffsets =
            new ArrayList<Long>();

    RealtimeRelay(byte[] runtime14) {
        this(runtime14, null, null);
    }

    RealtimeRelay(byte[] runtime14, byte[] replacementPlain36) {
        this(runtime14, replacementPlain36,
                replacementPlain36 == null ? null : "fixed_asset");
    }

    RealtimeRelay(byte[] runtime14, byte[] replacementPlain36,
            String replacementSource) {
        this(runtime14, replacementPlain36, replacementSource, false);
    }

    RealtimeRelay(byte[] runtime14, byte[] replacementPlain36,
            String replacementSource, boolean replacementIsFinalWirePayload) {
        this(runtime14, replacementPlain36, replacementSource,
                replacementIsFinalWirePayload, REQUIRED_UNITS);
    }

    RealtimeRelay(byte[] runtime14, byte[] replacementPlain36,
            String replacementSource, boolean replacementIsFinalWirePayload,
            int requiredTriggerUnits) {
        this(runtime14, replacementPlain36, replacementSource,
                replacementIsFinalWirePayload, requiredTriggerUnits, false);
    }

    RealtimeRelay(byte[] runtime14, byte[] replacementPlain36,
            String replacementSource, boolean replacementIsFinalWirePayload,
            int requiredTriggerUnits, boolean triggerOnlyAfterArm) {
        this(runtime14, replacementPlain36, replacementSource,
                replacementIsFinalWirePayload, requiredTriggerUnits,
                triggerOnlyAfterArm, false);
    }

    RealtimeRelay(byte[] runtime14, byte[] replacementPlain36,
            String replacementSource, boolean replacementIsFinalWirePayload,
            int requiredTriggerUnits, boolean triggerOnlyAfterArm,
            boolean activePacedWrites) {
        this(runtime14, replacementPlain36, replacementSource,
                replacementIsFinalWirePayload, requiredTriggerUnits,
                triggerOnlyAfterArm, activePacedWrites,
                DmrProtocol.VoiceFormat.LEGACY_CHAN_D36);
    }

    /**
     * 指定语音注入格式。默认格式与历史实现逐字节相同；其余格式改变打包
     * 粒度与节拍，但语音帧流不变。格式由会话开始时确定，中途不得更改。
     */
    RealtimeRelay(byte[] runtime14, byte[] replacementPlain36,
            String replacementSource, boolean replacementIsFinalWirePayload,
            int requiredTriggerUnits, boolean triggerOnlyAfterArm,
            boolean activePacedWrites,
            DmrProtocol.VoiceFormat voiceFormat) {
        if (voiceFormat == null) {
            throw new IllegalArgumentException("语音格式不得为空");
        }
        this.voiceFormat = voiceFormat;
        if (runtime14 == null || runtime14.length != 14) {
            throw new IllegalArgumentException("实时relay需要同次14字节运行快照");
        }
        if (requiredTriggerUnits != EXTERNAL_SOURCE_TRIGGER_UNITS
                && requiredTriggerUnits != REQUIRED_UNITS
                && requiredTriggerUnits != SOFTWARE_REPLACEMENT_TRIGGER_UNITS) {
            throw new IllegalArgumentException("实时relay触发单元数无效");
        }
        if (requiredTriggerUnits < REQUIRED_UNITS
                && replacementPlain36 == null) {
            throw new IllegalArgumentException("实时取材模式必须保留三个触发单元");
        }
        this.runtime14 = runtime14.clone();
        this.requiredTriggerUnits = requiredTriggerUnits;
        this.triggerOnlyAfterArm = triggerOnlyAfterArm;
        this.activePacedWrites = activePacedWrites;
        this.privacy = DmrPrivacy.fromRuntime14(this.runtime14);
        if (replacementPlain36 == null) {
            if (replacementSource != null) {
                throw new IllegalArgumentException("无替换明文时不得指定来源");
            }
            this.replacementStream = null;
            this.replacementSource = null;
            this.replacementIsFinalWirePayload = false;
            this.maximumUnits = 1;
        } else {
            boolean legacyFormat = this.voiceFormat
                    == DmrProtocol.VoiceFormat.LEGACY_CHAN_D36;
            if (replacementSource == null || replacementSource.length() == 0
                    || (legacyFormat
                    && replacementPlain36.length != PLAIN_BYTES
                    && replacementPlain36.length != CONTINUOUS_BYTES
                    && replacementPlain36.length != SUPERFRAME_BYTES
                    && replacementPlain36.length != SPEECH_BYTES
                    && replacementPlain36.length != SPEECH_SHORT_ABC_BYTES
                    && replacementPlain36.length != TRIPLE_SOS_BYTES)) {
                throw new IllegalArgumentException("替换明文或来源无效");
            }
            if (replacementPlain36.length == CONTINUOUS_BYTES
                    && replacementPlain36.length / PLAIN_BYTES
                    != CONTINUOUS_UNITS) {
                throw new IllegalArgumentException("连续三单元明文长度必须为108字节");
            }
            if (replacementPlain36.length == SUPERFRAME_BYTES
                    && replacementPlain36.length / PLAIN_BYTES
                    != SUPERFRAME_UNITS) {
                throw new IllegalArgumentException("连续五单元明文长度必须为180字节");
            }
            if (replacementPlain36.length == SPEECH_BYTES
                    && replacementPlain36.length / PLAIN_BYTES
                    != SPEECH_UNITS) {
                throw new IllegalArgumentException("连续25单元明文长度必须为900字节");
            }
            int unitSize = TxPlan.unitBytes(this.voiceFormat);
            if (replacementPlain36.length % unitSize != 0) {
                throw new IllegalArgumentException("明文长度"
                        + replacementPlain36.length + "不能被单元长度"
                        + unitSize + "整除");
            }
            if (replacementPlain36.length == TRIPLE_SOS_BYTES
                    && this.voiceFormat
                    == DmrProtocol.VoiceFormat.LEGACY_CHAN_D36
                    && replacementPlain36.length / unitSize
                    != TRIPLE_SOS_UNITS) {
                throw new IllegalArgumentException("三遍SOS明文长度必须为2808字节");
            }
            this.replacementStream = replacementPlain36.clone();
            this.replacementSource = replacementSource;
            this.replacementIsFinalWirePayload = replacementIsFinalWirePayload;
            this.maximumUnits = replacementPlain36.length / unitSize;
        }
    }

    /** 本次会话的正文字节数。没有替换正文时为零。 */
    int bodyBytes() {
        return replacementStream == null ? 0 : replacementStream.length;
    }

    /** 本次会话所用格式的单元载荷长度。历史格式为 36 字节，语音突发为 27 字节。 */
    int unitBytes() {
        return TxPlan.unitBytes(voiceFormat);
    }

    /** 本次会话所用格式的绝对节拍。 */
    long unitIntervalMs() {
        return TxPlan.unitIntervalMs(voiceFormat);
    }

    DmrProtocol.VoiceFormat voiceFormat() {
        return voiceFormat;
    }

    static void requireChanDLastNibbleZero(byte[] packed) {
        if (packed == null || packed.length == 0 || packed.length % 9 != 0) {
            throw new IllegalArgumentException("49位装箱必须为9字节倍数");
        }
        for (int index = 8; index < packed.length; index += 9) {
            if ((packed[index] & 0xf0) != 0) {
                throw new IllegalArgumentException(
                        "49位装箱末字节高半字节非零，不是H13原生形状");
            }
        }
    }

    static byte[] requireFrozenUnit0(byte[] asset) {
        if (asset == null || asset.length != FROZEN_ASSET_BYTES) {
            throw new IllegalArgumentException("冻结AMBE资产长度必须为6588字节");
        }
        if (!FROZEN_ASSET_SHA256.equals(Bytes.sha256(asset))) {
            throw new IllegalArgumentException("冻结AMBE资产SHA-256不符");
        }
        byte[] unit0 = Arrays.copyOf(asset, PLAIN_BYTES);
        if (!FROZEN_UNIT0_SHA256.equals(Bytes.sha256(unit0))) {
            throw new IllegalArgumentException("冻结资产unit0 SHA-256不符");
        }
        return unit0;
    }

    static byte[] requireFrozenChanD108(byte[] asset) {
        byte[] unit0 = requireFrozenUnit0(asset);
        if (asset.length < CONTINUOUS_BYTES) {
            throw new IllegalArgumentException("冻结CHAN_D不足连续108字节");
        }
        byte[] window = Arrays.copyOf(asset, CONTINUOUS_BYTES);
        requireChanDLastNibbleZero(window);
        if (!FROZEN_108_SHA256.equals(Bytes.sha256(window))) {
            throw new IllegalArgumentException("冻结CHAN_D连续108字节SHA-256不符");
        }
        byte[] unit1 = Arrays.copyOfRange(window, PLAIN_BYTES, PLAIN_BYTES * 2);
        byte[] unit2 = Arrays.copyOfRange(window, PLAIN_BYTES * 2,
                CONTINUOUS_BYTES);
        if (!FROZEN_UNIT1_SHA256.equals(Bytes.sha256(unit1))
                || !FROZEN_UNIT2_SHA256.equals(Bytes.sha256(unit2))) {
            throw new IllegalArgumentException("冻结CHAN_D unit1/unit2 SHA-256不符");
        }
        if (Arrays.equals(unit0, unit1) || Arrays.equals(unit0, unit2)
                || Arrays.equals(unit1, unit2)) {
            throw new IllegalArgumentException("连续三单元不得重复unit0或彼此相同");
        }
        return window;
    }

    static byte[] requireFrozenChanD180(byte[] asset) {
        byte[] first108 = requireFrozenChanD108(asset);
        if (asset.length < SUPERFRAME_BYTES) {
            throw new IllegalArgumentException("冻结CHAN_D不足连续180字节");
        }
        byte[] window = Arrays.copyOf(asset, SUPERFRAME_BYTES);
        requireChanDLastNibbleZero(window);
        if (!FROZEN_180_SHA256.equals(Bytes.sha256(window))) {
            throw new IllegalArgumentException("冻结CHAN_D连续180字节SHA-256不符");
        }
        byte[] unit3 = Arrays.copyOfRange(window, PLAIN_BYTES * 3,
                PLAIN_BYTES * 4);
        byte[] unit4 = Arrays.copyOfRange(window, PLAIN_BYTES * 4,
                SUPERFRAME_BYTES);
        if (!FROZEN_UNIT3_SHA256.equals(Bytes.sha256(unit3))
                || !FROZEN_UNIT4_SHA256.equals(Bytes.sha256(unit4))) {
            throw new IllegalArgumentException("冻结CHAN_D unit3/unit4 SHA-256不符");
        }
        if (FROZEN_UNIT0_SHA256.equals(Bytes.sha256(unit3))
                || FROZEN_UNIT0_SHA256.equals(Bytes.sha256(unit4))
                || Arrays.equals(unit3, unit4)) {
            throw new IllegalArgumentException("连续五单元不得重复unit0或彼此相同");
        }
        if (!Arrays.equals(first108, Arrays.copyOf(window, CONTINUOUS_BYTES))) {
            throw new IllegalArgumentException("五单元窗口前108字节必须等于已冻结三单元窗口");
        }
        return window;
    }

    static byte[] requireFrozenChanD900(byte[] asset) {
        if (asset == null || asset.length != FROZEN_ASSET_BYTES) {
            throw new IllegalArgumentException("冻结AMBE资产长度必须为6588字节");
        }
        if (!FROZEN_ASSET_SHA256.equals(Bytes.sha256(asset))) {
            throw new IllegalArgumentException("冻结AMBE资产SHA-256不符");
        }
        if (SPEECH_BYTE_OFFSET + SPEECH_BYTES > asset.length
                || SPEECH_BYTE_OFFSET / PLAIN_BYTES < SUPERFRAME_UNITS) {
            throw new IllegalArgumentException("25单元语音窗偏移无效或与会话B重叠");
        }
        byte[] window = Arrays.copyOfRange(asset, SPEECH_BYTE_OFFSET,
                SPEECH_BYTE_OFFSET + SPEECH_BYTES);
        requireChanDLastNibbleZero(window);
        if (!FROZEN_SPEECH_900_SHA256.equals(Bytes.sha256(window))) {
            throw new IllegalArgumentException("冻结CHAN_D 25单元窗SHA-256不符");
        }
        byte[] sessionB = Arrays.copyOf(asset, SUPERFRAME_BYTES);
        if (Arrays.equals(Arrays.copyOf(window, SUPERFRAME_BYTES), sessionB)) {
            throw new IllegalArgumentException("25单元窗不得等于会话B前180字节");
        }
        if (FROZEN_UNIT0_SHA256.equals(Bytes.sha256(
                Arrays.copyOf(window, PLAIN_BYTES)))) {
            throw new IllegalArgumentException("25单元窗不得从源流unit0开始");
        }
        if (FROZEN_SPEECH_UNIT_SHA256.length != SPEECH_UNITS) {
            throw new IllegalArgumentException("25单元明文哈希表长度错误");
        }
        for (int index = 0; index < SPEECH_UNITS; index++) {
            byte[] unit = Arrays.copyOfRange(window, index * PLAIN_BYTES,
                    (index + 1) * PLAIN_BYTES);
            if (!FROZEN_SPEECH_UNIT_SHA256[index].equals(Bytes.sha256(unit))) {
                throw new IllegalArgumentException(
                        "冻结CHAN_D 25单元窗 unit" + index + " SHA-256不符");
            }
        }
        return window;
    }

    static byte[] requireFrozenEncodeDmrSilence36(byte[] ambe9) {
        if (ambe9 == null || ambe9.length < PLAIN_BYTES
                || ambe9.length % 9 != 0) {
            throw new IllegalArgumentException("冻结encode_dmr静音向量长度无效");
        }
        if (!ENCODE_DMR_SILENCE_AMBE9_SHA256.equals(Bytes.sha256(ambe9))) {
            throw new IllegalArgumentException("冻结encode_dmr静音向量SHA-256不符");
        }
        byte[] unit0 = Arrays.copyOf(ambe9, PLAIN_BYTES);
        if (!ENCODE_DMR_SILENCE_36_SHA256.equals(Bytes.sha256(unit0))) {
            throw new IllegalArgumentException("冻结encode_dmr静音前36字节SHA-256不符");
        }
        if (FROZEN_UNIT0_SHA256.equals(Bytes.sha256(unit0))) {
            throw new IllegalArgumentException("encode_dmr静音前36字节不得等于方言A unit0");
        }
        return unit0;
    }

    static byte[] requireFrozenEncodeDmrTone80036(byte[] ambe9) {
        if (ambe9 == null || ambe9.length < PLAIN_BYTES
                || ambe9.length % 9 != 0) {
            throw new IllegalArgumentException("冻结encode_dmr 800赫兹向量长度无效");
        }
        if (!ENCODE_DMR_TONE800_AMBE9_SHA256.equals(Bytes.sha256(ambe9))) {
            throw new IllegalArgumentException("冻结encode_dmr 800赫兹向量SHA-256不符");
        }
        byte[] unit0 = Arrays.copyOf(ambe9, PLAIN_BYTES);
        if (!ENCODE_DMR_TONE800_36_SHA256.equals(Bytes.sha256(unit0))) {
            throw new IllegalArgumentException("冻结encode_dmr 800赫兹前36字节SHA-256不符");
        }
        if (FROZEN_UNIT0_SHA256.equals(Bytes.sha256(unit0))) {
            throw new IllegalArgumentException("encode_dmr 800赫兹前36字节不得等于方言A unit0");
        }
        if (ENCODE_DMR_SILENCE_36_SHA256.equals(Bytes.sha256(unit0))) {
            throw new IllegalArgumentException("encode_dmr 800赫兹前36字节不得等于静音前36字节");
        }
        return unit0;
    }

    static byte[] requireFrozenEncodeDmrMorseUnique36(byte[] ambe9) {
        if (ambe9 == null || ambe9.length < ENCODE_DMR_MORSE_UNIQUE_OFFSET
                + PLAIN_BYTES || ambe9.length % 9 != 0) {
            throw new IllegalArgumentException("冻结encode_dmr摩尔斯向量长度无效");
        }
        if (!ENCODE_DMR_MORSE_AMBE9_SHA256.equals(Bytes.sha256(ambe9))) {
            throw new IllegalArgumentException("冻结encode_dmr摩尔斯向量SHA-256不符");
        }
        byte[] unit0 = Arrays.copyOf(ambe9, PLAIN_BYTES);
        if (!ENCODE_DMR_TONE800_36_SHA256.equals(Bytes.sha256(unit0))) {
            throw new IllegalArgumentException("摩尔斯unit0必须仍等于冻结800赫兹前36字节");
        }
        byte[] unique = Arrays.copyOfRange(ambe9, ENCODE_DMR_MORSE_UNIQUE_OFFSET,
                ENCODE_DMR_MORSE_UNIQUE_OFFSET + PLAIN_BYTES);
        if (!ENCODE_DMR_MORSE_UNIQUE_36_SHA256.equals(Bytes.sha256(unique))) {
            throw new IllegalArgumentException("冻结encode_dmr摩尔斯独特36字节SHA-256不符");
        }
        if (ENCODE_DMR_TONE800_36_SHA256.equals(Bytes.sha256(unique))
                || ENCODE_DMR_SILENCE_36_SHA256.equals(Bytes.sha256(unique))
                || Arrays.equals(unique, unit0)) {
            throw new IllegalArgumentException("摩尔斯独特36字节不得等于静音或800赫兹前36字节");
        }
        return unique;
    }

    static byte[] requireFrozenEncodeDmrMorseUnique108(byte[] ambe9) {
        byte[] unique36 = requireFrozenEncodeDmrMorseUnique36(ambe9);
        int end = ENCODE_DMR_MORSE_UNIQUE_OFFSET + CONTINUOUS_BYTES;
        if (ambe9.length < end) {
            throw new IllegalArgumentException("冻结encode_dmr摩尔斯不足独特连续108字节");
        }
        byte[] window = Arrays.copyOfRange(ambe9, ENCODE_DMR_MORSE_UNIQUE_OFFSET,
                end);
        if (!ENCODE_DMR_MORSE_UNIQUE_108_SHA256.equals(Bytes.sha256(window))) {
            throw new IllegalArgumentException("冻结encode_dmr摩尔斯独特连续108字节SHA-256不符");
        }
        if (!Arrays.equals(unique36, Arrays.copyOf(window, PLAIN_BYTES))) {
            throw new IllegalArgumentException("摩尔斯三单元窗第一包必须仍是独特unit1");
        }
        byte[] unit2 = Arrays.copyOfRange(window, PLAIN_BYTES, PLAIN_BYTES * 2);
        byte[] unit3 = Arrays.copyOfRange(window, PLAIN_BYTES * 2,
                CONTINUOUS_BYTES);
        if (!ENCODE_DMR_MORSE_UNIQUE_UNIT2_SHA256.equals(Bytes.sha256(unit2))
                || !ENCODE_DMR_MORSE_UNIQUE_UNIT3_SHA256.equals(
                        Bytes.sha256(unit3))) {
            throw new IllegalArgumentException(
                    "冻结encode_dmr摩尔斯独特unit2/unit3 SHA-256不符");
        }
        if (Arrays.equals(unique36, unit2) || Arrays.equals(unique36, unit3)
                || Arrays.equals(unit2, unit3)) {
            throw new IllegalArgumentException("摩尔斯连续三单元不得重复独特unit1或彼此相同");
        }
        if (ENCODE_DMR_TONE800_36_SHA256.equals(Bytes.sha256(unit2))
                || ENCODE_DMR_TONE800_36_SHA256.equals(Bytes.sha256(unit3))
                || ENCODE_DMR_SILENCE_36_SHA256.equals(Bytes.sha256(unit2))
                || ENCODE_DMR_SILENCE_36_SHA256.equals(Bytes.sha256(unit3))
                || ENCODE_DMR_TONE800_108_SHA256.equals(Bytes.sha256(window))) {
            throw new IllegalArgumentException("摩尔斯独特108字节不得等于静音或800赫兹窗口");
        }
        return window;
    }

    static byte[] requireFrozenEncodeDmrMorseUnique180(byte[] ambe9) {
        byte[] first108 = requireFrozenEncodeDmrMorseUnique108(ambe9);
        int end = ENCODE_DMR_MORSE_UNIQUE_OFFSET + SUPERFRAME_BYTES;
        if (ambe9.length < end) {
            throw new IllegalArgumentException("冻结encode_dmr摩尔斯不足独特连续180字节");
        }
        byte[] window = Arrays.copyOfRange(ambe9, ENCODE_DMR_MORSE_UNIQUE_OFFSET,
                end);
        if (!ENCODE_DMR_MORSE_UNIQUE_180_SHA256.equals(Bytes.sha256(window))) {
            throw new IllegalArgumentException("冻结encode_dmr摩尔斯独特连续180字节SHA-256不符");
        }
        byte[] unit4 = Arrays.copyOfRange(window, PLAIN_BYTES * 3,
                PLAIN_BYTES * 4);
        byte[] unit5 = Arrays.copyOfRange(window, PLAIN_BYTES * 4,
                SUPERFRAME_BYTES);
        if (!ENCODE_DMR_MORSE_UNIQUE_UNIT4_SHA256.equals(Bytes.sha256(unit4))
                || !ENCODE_DMR_MORSE_UNIQUE_UNIT5_SHA256.equals(
                        Bytes.sha256(unit5))) {
            throw new IllegalArgumentException(
                    "冻结encode_dmr摩尔斯独特unit4/unit5 SHA-256不符");
        }
        if (ENCODE_DMR_MORSE_UNIQUE_36_SHA256.equals(Bytes.sha256(unit4))
                || ENCODE_DMR_MORSE_UNIQUE_36_SHA256.equals(Bytes.sha256(unit5))
                || Arrays.equals(unit4, unit5)) {
            throw new IllegalArgumentException("摩尔斯连续五单元不得重复独特unit1或彼此相同");
        }
        if (!Arrays.equals(first108, Arrays.copyOf(window, CONTINUOUS_BYTES))) {
            throw new IllegalArgumentException("五单元窗口前108字节必须等于已冻结三单元窗口");
        }
        if (ENCODE_DMR_TONE800_180_SHA256.equals(Bytes.sha256(window))) {
            throw new IllegalArgumentException("摩尔斯独特180字节不得等于800赫兹180字节");
        }
        return window;
    }

    static byte[] requireFrozenEncodeDmrTone800108(byte[] ambe9) {
        byte[] unit0 = requireFrozenEncodeDmrTone80036(ambe9);
        if (ambe9.length < CONTINUOUS_BYTES) {
            throw new IllegalArgumentException("冻结encode_dmr 800赫兹不足连续108字节");
        }
        byte[] window = Arrays.copyOf(ambe9, CONTINUOUS_BYTES);
        if (!ENCODE_DMR_TONE800_108_SHA256.equals(Bytes.sha256(window))) {
            throw new IllegalArgumentException("冻结encode_dmr 800赫兹连续108字节SHA-256不符");
        }
        byte[] unit1 = Arrays.copyOfRange(window, PLAIN_BYTES, PLAIN_BYTES * 2);
        byte[] unit2 = Arrays.copyOfRange(window, PLAIN_BYTES * 2,
                CONTINUOUS_BYTES);
        if (!ENCODE_DMR_TONE800_UNIT1_SHA256.equals(Bytes.sha256(unit1))
                || !ENCODE_DMR_TONE800_UNIT2_SHA256.equals(Bytes.sha256(unit2))) {
            throw new IllegalArgumentException("冻结encode_dmr 800赫兹unit1/unit2 SHA-256不符");
        }
        if (Arrays.equals(unit0, unit1) || Arrays.equals(unit0, unit2)
                || Arrays.equals(unit1, unit2)) {
            throw new IllegalArgumentException("连续三单元不得重复unit0或彼此相同");
        }
        return window;
    }

    static byte[] requireFrozenEncodeDmrTone800180(byte[] ambe9) {
        byte[] first108 = requireFrozenEncodeDmrTone800108(ambe9);
        if (ambe9.length < SUPERFRAME_BYTES) {
            throw new IllegalArgumentException("冻结encode_dmr 800赫兹不足连续180字节");
        }
        byte[] window = Arrays.copyOf(ambe9, SUPERFRAME_BYTES);
        if (!ENCODE_DMR_TONE800_180_SHA256.equals(Bytes.sha256(window))) {
            throw new IllegalArgumentException("冻结encode_dmr 800赫兹连续180字节SHA-256不符");
        }
        byte[] unit3 = Arrays.copyOfRange(window, PLAIN_BYTES * 3,
                PLAIN_BYTES * 4);
        byte[] unit4 = Arrays.copyOfRange(window, PLAIN_BYTES * 4,
                SUPERFRAME_BYTES);
        if (!ENCODE_DMR_TONE800_UNIT3_SHA256.equals(Bytes.sha256(unit3))
                || !ENCODE_DMR_TONE800_UNIT4_SHA256.equals(Bytes.sha256(unit4))) {
            throw new IllegalArgumentException("冻结encode_dmr 800赫兹unit3/unit4 SHA-256不符");
        }
        if (ENCODE_DMR_TONE800_36_SHA256.equals(Bytes.sha256(unit3))
                || ENCODE_DMR_TONE800_36_SHA256.equals(Bytes.sha256(unit4))
                || Arrays.equals(unit3, unit4)) {
            throw new IllegalArgumentException("连续五单元不得重复unit0或彼此相同");
        }
        if (!Arrays.equals(first108, Arrays.copyOf(window, CONTINUOUS_BYTES))) {
            throw new IllegalArgumentException("五单元窗口前108字节必须等于已冻结三单元窗口");
        }
        return window;
    }

    void acceptRaw(byte[] raw) {
        if (raw == null || raw.length == 0) {
            return;
        }
        if (carry.length == 0) {
            carryStartRawOffset = rawBytesAccepted;
        }
        if (acceptedRaw.size() + raw.length > MAX_ACCEPTED_RAW_BYTES) {
            throw new IllegalStateException("实时relay原始流超过一兆字节证据上限");
        }
        acceptedRaw.write(raw, 0, raw.length);
        carry = Bytes.concat(carry, raw);
        rawBytesAccepted += raw.length;
        while (true) {
            int frameLength = completeFrameLength(carry);
            if (frameLength <= 0) {
                return;
            }
            byte[] frame = Arrays.copyOf(carry, frameLength);
            carry = Arrays.copyOfRange(carry, frameLength, carry.length);
            long frameStart = carryStartRawOffset;
            long frameEnd = frameStart + frameLength;
            carryStartRawOffset = frameEnd;
            onCompleteFrame(frame, frameStart, frameEnd);
        }
    }

    void armPostVlcTrigger() {
        if (!triggerOnlyAfterArm || postVlcArmed || writeAttempted
                || unitsWritten != 0 || data27.size() != 0) {
            throw new IllegalStateException("VLC后触发武装状态错误");
        }
        postVlcArmRawOffset = rawBytesAccepted;
        postVlcArmed = true;
    }

    boolean readyToWrite() {
        return data27.size() >= requiredTriggerUnits && unitsWritten == 0
                && !writeAttempted;
    }

    boolean readyToWriteNext() {
        return unitsWritten > 0
                && unitsWritten == creditsConsumed
                && unitsWritten < maximumUnits
                && !writeAttempted;
    }

    byte[] takeRequest44() {
        if (unitsWritten == 0) {
            if (!readyToWrite()) {
                throw new IllegalStateException("实时relay尚未到唯一写出点");
            }
            int triggerBytes = requiredTriggerUnits * 27;
            if (units.size() < triggerBytes) {
                throw new IllegalStateException("实时触发单元字节不足");
            }
            combinedTrigger = Arrays.copyOf(units.toByteArray(), triggerBytes);
            if (requiredTriggerUnits == REQUIRED_UNITS) {
                combined81 = combinedTrigger.clone();
            }
            candidate36 = Arrays.copyOf(combinedTrigger, PLAIN_BYTES);
        } else if (!readyToWriteNext()) {
            throw new IllegalStateException("上一单元信用尚未消费，禁止写下一单元");
        }
        if (replacementStream != null) {
            int offset = unitsWritten * unitBytes();
            byte[] next = Arrays.copyOfRange(replacementStream, offset,
                    offset + unitBytes());
            if (unitsWritten == 0 && Arrays.equals(next, candidate36)) {
                throw new IllegalStateException(
                        "独立载荷与本次实时前36字节相同，无法证明音源独立性");
            }
            plain36 = next;
        } else {
            if (unitsWritten != 0) {
                throw new IllegalStateException("实时流模式只允许第一包");
            }
            plain36 = candidate36.clone();
        }
        byte[] encrypted = replacementIsFinalWirePayload
                ? plain36.clone() : privacy.encrypt(plain36);
        request44 = DmrProtocol.voiceUnit(voiceFormat, encrypted);
        writeAttempted = true;
        unitsWritten++;
        return request44.clone();
    }

    byte[] takeActivePacedRequest44() {
        if (!activePacedWrites
                || requiredTriggerUnits != EXTERNAL_SOURCE_TRIGGER_UNITS
                || replacementStream == null || !replacementIsFinalWirePayload
                || writeAttempted || flushed || unitsWritten >= maximumUnits
                || creditsConsumed != 0) {
            throw new IllegalStateException("主动绝对节拍写出状态错误: "
                    + activePacedState());
        }
        int offset = unitsWritten * unitBytes();
        plain36 = Arrays.copyOfRange(replacementStream, offset,
                offset + unitBytes());
        request44 = DmrProtocol.voiceUnit(voiceFormat, plain36);
        writeAttempted = true;
        unitsWritten++;
        return request44.clone();
    }

    void markFlushed() {
        if (!writeAttempted) {
            throw new IllegalStateException("尚未写出，禁止进入写后信用窗");
        }
        flushed = true;
    }

    void completeActivePacedFlush() {
        if (!activePacedWrites
                || requiredTriggerUnits != EXTERNAL_SOURCE_TRIGGER_UNITS
                || !replacementIsFinalWirePayload || !writeAttempted
                || !flushed || creditsConsumed != 0
                || !creditCandidates.isEmpty()) {
            throw new IllegalStateException("主动绝对节拍flush闭环错误");
        }
        flushed = false;
        writeAttempted = false;
    }

    void completeActivePacedWriteAfterTransportFlush() {
        markFlushed();
        completeActivePacedFlush();
    }

    String activePacedState() {
        return "active_paced=" + activePacedWrites
                + " trigger_units=" + requiredTriggerUnits
                + " replacement_present=" + (replacementStream != null)
                + " final_wire=" + replacementIsFinalWirePayload
                + " write_attempted=" + writeAttempted
                + " flushed=" + flushed
                + " units_written=" + unitsWritten
                + " maximum_units=" + maximumUnits
                + " credits_consumed=" + creditsConsumed
                + " credit_candidates=" + creditCandidates.size();
    }

    boolean activePacedComplete() {
        return activePacedWrites
                && requiredTriggerUnits == EXTERNAL_SOURCE_TRIGGER_UNITS
                && replacementIsFinalWirePayload
                && unitsWritten == maximumUnits && creditsConsumed == 0
                && !writeAttempted && !flushed;
    }

    int activePacedCreditCount() {
        return activePacedCredits.size();
    }

    boolean writeAttempted() {
        return writeAttempted;
    }

    boolean lastUnitCreditPending() {
        return flushed && writeAttempted && creditCandidates.size() == 1
                && unitsWritten == creditsConsumed + 1;
    }

    boolean creditAccepted() {
        return creditsConsumed == maximumUnits
                || (unitsWritten == maximumUnits && lastUnitCreditPending());
    }

    void consumeCredit() {
        if (!lastUnitCreditPending()) {
            throw new IllegalStateException("当前单元没有唯一待消费短信用");
        }
        creditsConsumed++;
        creditCandidates.clear();
        flushed = false;
        writeAttempted = false;
    }

    int data27Count() {
        return data27.size();
    }

    int preArmData27Count() {
        return preArmData27Count;
    }

    int postArmData27Total() {
        return postArmData27Total;
    }

    int postArmOtherFrameTotal() {
        return postArmOtherFrameTotal;
    }

    long postVlcArmRawOffset() {
        return postVlcArmRawOffset;
    }

    long rawBytesAccepted() {
        return rawBytesAccepted;
    }

    byte[] acceptedRawStream() {
        return acceptedRaw.toByteArray();
    }

    List<Long> postArmData27StartOffsets() {
        return new ArrayList<Long>(postArmData27StartOffsets);
    }

    List<Long> postArmData27EndOffsets() {
        return new ArrayList<Long>(postArmData27EndOffsets);
    }

    int creditCount() {
        return creditCandidates.size();
    }

    int unitsWritten() {
        return unitsWritten;
    }

    int creditsConsumed() {
        return creditsConsumed;
    }

    int vlcAckCount() {
        return vlcAckFrames.size();
    }

    byte[] carrySnapshot() {
        return carry.clone();
    }

    byte[] requireSingleVlcAckAfter(int countBeforeWrite) {
        if (countBeforeWrite < 0 || countBeforeWrite > vlcAckFrames.size()) {
            throw new IllegalArgumentException("VLC确认基线计数越界");
        }
        int added = vlcAckFrames.size() - countBeforeWrite;
        if (added != 1) {
            throw new IllegalStateException("当前VLC交换要求唯一完整确认，实际新增="
                    + added);
        }
        return vlcAckFrames.get(countBeforeWrite).clone();
    }

    int maximumUnits() {
        return maximumUnits;
    }

    byte[] peekCredit() {
        if (creditCandidates.size() != 1) {
            throw new IllegalStateException("没有唯一待消费短信用可读取");
        }
        return creditCandidates.get(0).clone();
    }

    byte[] combined81() {
        return combined81.clone();
    }

    byte[] combinedTrigger() {
        return combinedTrigger.clone();
    }

    int requiredTriggerUnits() {
        return requiredTriggerUnits;
    }

    byte[] candidate36() {
        return candidate36.clone();
    }

    byte[] plain36() {
        return plain36.clone();
    }

    String payloadSource() {
        return replacementSource != null ? replacementSource : "realtime_stream";
    }

    byte[] request44() {
        return request44.clone();
    }

    byte[] encrypted36() {
        if (request44.length != DmrProtocol.DATA36_WIRE_BYTES) {
            return new byte[0];
        }
        return Arrays.copyOfRange(request44, 8, 44);
    }

    List<byte[]> data27Units() {
        List<byte[]> copy = new ArrayList<byte[]>(data27.size());
        for (byte[] unit : data27) {
            copy.add(unit.clone());
        }
        return copy;
    }

    static boolean isData27(byte[] frame) {
        List<HpiCodec.WireFrame> parsed = HpiCodec.parseComplete(frame);
        if (parsed == null || parsed.size() != 1) {
            return false;
        }
        HpiCodec.WireFrame wire = parsed.get(0);
        return wire.packetType == 0x20
                && wire.payload.length == 29
                && (wire.payload[0] & 0xff) == 0x01
                && (wire.payload[1] & 0xff) == 27;
    }

    static boolean isRelayCredit(byte[] frame) {
        return DmrProtocol.isExternalDmrRelayCredit(frame);
    }

    static byte[] data27Payload(byte[] frame) {
        if (!isData27(frame)) {
            throw new IllegalArgumentException("不是实时27字节单元");
        }
        List<HpiCodec.WireFrame> parsed = HpiCodec.parseComplete(frame);
        byte[] payload = parsed.get(0).payload;
        return Arrays.copyOfRange(payload, 2, 29);
    }

    private void onCompleteFrame(byte[] frame, long frameStart,
            long frameEnd) {
        if (isData27(frame)) {
            if (triggerOnlyAfterArm && (!postVlcArmed
                    || frameStart < postVlcArmRawOffset)) {
                preArmData27Count++;
                return;
            }
            if (triggerOnlyAfterArm) {
                postArmData27Total++;
                postArmData27StartOffsets.add(frameStart);
                postArmData27EndOffsets.add(frameEnd);
            }
            if (data27.size() >= REQUIRED_UNITS) {
                return;
            }
            byte[] unit = data27Payload(frame);
            data27.add(unit);
            units.write(unit, 0, unit.length);
            return;
        }
        if (DmrProtocol.isVlcAckFrame(frame)) {
            vlcAckFrames.add(frame.clone());
            return;
        }
        if (isRelayCredit(frame)) {
            if (activePacedWrites) {
                activePacedCredits.add(frame.clone());
            } else if (flushed) {
                creditCandidates.add(frame.clone());
            }
            return;
        }
        if (triggerOnlyAfterArm && postVlcArmed
                && frameStart >= postVlcArmRawOffset) {
            postArmOtherFrameTotal++;
        }
    }

    private static int completeFrameLength(byte[] raw) {
        if (raw == null || raw.length < 6) {
            return 0;
        }
        if ((raw[0] & 0xff) != HpiCodec.SYNC0
                || (raw[1] & 0xff) != HpiCodec.SYNC1
                || (raw[2] & 0xff) != HpiCodec.COMMAND) {
            throw new IllegalStateException("实时relay失去HPI帧同步");
        }
        int payloadLength = ((raw[3] & 0xff) << 8) | (raw[4] & 0xff);
        int declared = 6 + payloadLength;
        int wire = declared + (declared & 1);
        if (wire > 4096) {
            throw new IllegalStateException("实时relay帧声明长度异常");
        }
        if (raw.length < wire) {
            return 0;
        }
        if (wire != declared && raw[declared] != 0) {
            throw new IllegalStateException("实时relay补齐字节非零");
        }
        return wire;
    }
}
