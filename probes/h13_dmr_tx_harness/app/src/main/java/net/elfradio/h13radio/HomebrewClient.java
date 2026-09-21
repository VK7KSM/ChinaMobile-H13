package net.elfradio.h13radio;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Homebrew 中继链接的状态机。**不碰 socket**——收发交给
 * {@link PacketLink}，这样整条握手与重连可以对着假 master 逐条验收。
 *
 * <p>状态：
 *
 * <pre>
 *   断开 →(RPTL)→ 等盐 →(RPTK)→ 等认证 →(RPTC)→ 等配置 → 在线
 *                                                      ↑ ↓(超时/NAK)
 *                                                      └─ 断开
 * </pre>
 *
 * <p>三条取向写死在这里：
 *
 * <ul>
 *   <li><b>口令只进摘要，不留副本。</b> 算完 SHA-256 立刻把口令字节清零，
 *       并且永不出现在任何日志或 {@link #status()} 里。</li>
 *   <li><b>收到 `MSTNAK` 立刻回到断开态。</b> 不重试同一份凭据——
 *       口令错了重试只会更快被封。</li>
 *   <li><b>没到在线态，一个语音包都不发。</b></li>
 * </ul>
 */
public final class HomebrewClient {

    /** 收发接缝。真实实现是 UDP；用例用假的。 */
    public interface PacketLink {
        void send(byte[] packet) throws Exception;

        /** 收一个包；没有就返回 null。 */
        byte[] receive(long timeoutMs) throws Exception;

        long nowMs();
    }

    public enum State { DISCONNECTED, AWAIT_SALT, AWAIT_AUTH, AWAIT_CONFIG, ONLINE }

    /** 心跳间隔。 */
    public static final long PING_INTERVAL_MS = 5_000L;
    /** 多久收不到任何回应就判定掉线。 */
    public static final long LINK_TIMEOUT_MS = 30_000L;

    private final PacketLink link;
    private final int repeaterId;
    private final byte[] configBody;
    private final List<HomebrewPacket.Dmrd> inbox = new ArrayList<>();
    private final List<String> log = new ArrayList<>();

    private State state = State.DISCONNECTED;
    private long lastHeardMs;
    private long lastPingMs;

    public HomebrewClient(PacketLink link, int repeaterId, byte[] configBody) {
        if (link == null || configBody == null) {
            throw new IllegalArgumentException("链路与配置正文都不能为空");
        }
        this.link = link;
        this.repeaterId = repeaterId;
        this.configBody = configBody.clone();
    }

    public State state() {
        return state;
    }

    public List<HomebrewPacket.Dmrd> drainInbox() {
        List<HomebrewPacket.Dmrd> out = new ArrayList<>(inbox);
        inbox.clear();
        return out;
    }

    public List<String> log() {
        return new ArrayList<>(log);
    }

    /** 状态摘要。**刻意不含口令，也不含摘要**。 */
    public String status() {
        return String.format(Locale.US, "链路 %s  中继 %d  收件箱 %d 条",
                state, repeaterId, inbox.size());
    }

    private void to(State next, String why) {
        log.add(state + "→" + next + "（" + why + "）");
        state = next;
    }

    /** 发起登录。已在别的状态时先回断开，不叠加。 */
    public void connect() throws Exception {
        state = State.DISCONNECTED;
        inbox.clear();
        link.send(HomebrewPacket.login(repeaterId));
        lastHeardMs = link.nowMs();
        lastPingMs = link.nowMs();
        to(State.AWAIT_SALT, "已发 RPTL");
    }

    /**
     * 处理一个收到的包，推进状态机。
     *
     * @param passphrase 口令；仅在 {@link State#AWAIT_SALT} 时用于算摘要，
     *                   用完**当场清零**。调用方传进来的数组会被就地清空。
     */
    public void onPacket(byte[] data, byte[] passphrase) throws Exception {
        if (data == null || data.length == 0) {
            return;
        }
        lastHeardMs = link.nowMs();

        if (HomebrewPacket.startsWith(data, "MSTNAK")) {
            // 不重试同一份凭据：口令错了重试只会更快被封。
            to(State.DISCONNECTED, "收到 MSTNAK");
            return;
        }
        if (HomebrewPacket.startsWith(data, "MSTCL")) {
            to(State.DISCONNECTED, "master 关闭链接");
            return;
        }
        if (HomebrewPacket.startsWith(data, "MSTPONG")) {
            return;                                  // 心跳回应，只更新时间
        }

        switch (state) {
        case AWAIT_SALT: {
            byte[] salt = HomebrewPacket.saltFromAck(data);
            if (salt == null) {
                return;                              // 不是认识的应答，不猜
            }
            byte[] digest = digest(salt, passphrase);
            link.send(HomebrewPacket.authenticate(repeaterId, digest));
            to(State.AWAIT_AUTH, "已发 RPTK");
            return;
        }
        case AWAIT_AUTH:
            if (HomebrewPacket.startsWith(data, "RPTACK")) {
                link.send(HomebrewPacket.config(repeaterId, configBody));
                to(State.AWAIT_CONFIG, "已发 RPTC");
            }
            return;
        case AWAIT_CONFIG:
            if (HomebrewPacket.startsWith(data, "RPTACK")) {
                to(State.ONLINE, "配置被接受");
            }
            return;
        case ONLINE: {
            HomebrewPacket.Dmrd voice = HomebrewPacket.decodeDmrd(data);
            if (voice != null) {
                inbox.add(voice);
            }
            return;
        }
        default:
            return;
        }
    }

    /** 到点发心跳、超时判掉线。调用方周期性调用。 */
    public void tick() throws Exception {
        long now = link.nowMs();
        if (state == State.DISCONNECTED) {
            return;
        }
        if (now - lastHeardMs >= LINK_TIMEOUT_MS) {
            to(State.DISCONNECTED, "超过 " + LINK_TIMEOUT_MS + " 毫秒无应答");
            return;
        }
        if (state == State.ONLINE && now - lastPingMs >= PING_INTERVAL_MS) {
            link.send(HomebrewPacket.ping(repeaterId));
            lastPingMs = now;
        }
    }

    /** 发一个语音包。**没到在线态一律拒绝**，返回 false。 */
    public boolean sendVoice(byte[] dmrd) throws Exception {
        if (state != State.ONLINE) {
            return false;
        }
        link.send(dmrd);
        return true;
    }

    public void disconnect() throws Exception {
        if (state != State.DISCONNECTED) {
            link.send(HomebrewPacket.close(repeaterId));
        }
        to(State.DISCONNECTED, "本端主动关闭");
    }

    /**
     * SHA-256(盐 ‖ 口令)。算完把传入的口令数组清零——口令不该在内存里
     * 多留一份，更不该进日志。
     */
    private static byte[] digest(byte[] salt, byte[] passphrase)
            throws Exception {
        if (passphrase == null) {
            throw new IllegalArgumentException("口令不能为空");
        }
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        sha.update(salt);
        sha.update(passphrase);
        byte[] out = sha.digest();
        java.util.Arrays.fill(passphrase, (byte) 0);
        return out;
    }

    /** 便于调用方构造口令字节，避免 String 在堆上留副本。 */
    public static byte[] passphraseBytes(CharSequence text) {
        byte[] out = new byte[text.length()];
        for (int i = 0; i < text.length(); i++) {
            out[i] = (byte) text.charAt(i);
        }
        return out;
    }

    static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }
}
