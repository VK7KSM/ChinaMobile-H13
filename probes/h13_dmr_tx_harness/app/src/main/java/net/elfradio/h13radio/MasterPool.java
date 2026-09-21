package net.elfradio.h13radio;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 多 master 轮换与重连节流。
 *
 * <p>热点长期无人值守，链路一定会断——master 维护、网络切换、NAT 超时。
 * 断了之后的行为比断本身更要紧：**不节流的重连会把对方当成攻击**，
 * 而且掉线期间越急越连不上。
 *
 * <p>三条规则：
 *
 * <ul>
 *   <li><b>退避是指数的，且有上限。</b> 第一次失败等一个基础间隔，
 *       此后翻倍，封顶。</li>
 *   <li><b>换 master 之前先把当前这个试满次数。</b> 一断就换会掩盖
 *       "这一个其实能用、只是刚好抖了一下"。</li>
 *   <li><b>记住上次成功的那个，重启后优先用它。</b> 不要每次开机都从
 *       列表头开始，那会让首选 master 承担全部重连压力。</li>
 * </ul>
 *
 * <p>本类只做"该连谁、什么时候能连"的决策，**不碰 socket**。
 */
public final class MasterPool {

    /** 首次退避。 */
    public static final long BASE_BACKOFF_MS = 5_000L;
    /** 退避上限。再久就不如干脆换一个。 */
    public static final long MAX_BACKOFF_MS = 300_000L;
    /** 同一个 master 连续失败多少次之后换下一个。 */
    public static final int FAILURES_BEFORE_ROTATE = 3;

    /** 一个 master 端点。口令不放这里——它不该跟着端点到处走。 */
    public static final class Master {
        public final String host;
        public final int port;

        public Master(String host, int port) {
            if (host == null || host.trim().isEmpty()) {
                throw new IllegalArgumentException("master 地址不能为空");
            }
            if (port <= 0 || port > 65535) {
                throw new IllegalArgumentException("端口越界");
            }
            this.host = host.trim();
            this.port = port;
        }

        @Override
        public String toString() {
            return host + ":" + port;
        }
    }

    private final List<Master> masters = new ArrayList<>();
    private int index;
    private int failures;
    private long backoffMs = BASE_BACKOFF_MS;
    private long nextAttemptMs;
    private Master lastGood;

    public MasterPool(List<Master> masters) {
        if (masters == null || masters.isEmpty()) {
            throw new IllegalArgumentException("至少要有一个 master");
        }
        this.masters.addAll(masters);
    }

    public List<Master> masters() {
        return Collections.unmodifiableList(masters);
    }

    public Master current() {
        return masters.get(index);
    }

    public Master lastGood() {
        return lastGood;
    }

    public long backoffMs() {
        return backoffMs;
    }

    public int failures() {
        return failures;
    }

    /** 现在能不能发起连接。退避未到就不能。 */
    public boolean mayAttempt(long nowMs) {
        return nowMs >= nextAttemptMs;
    }

    public long msUntilNextAttempt(long nowMs) {
        return Math.max(0, nextAttemptMs - nowMs);
    }

    /** 连上了：清退避、清失败计数、记住这一个。 */
    public void onSuccess(long nowMs) {
        lastGood = current();
        failures = 0;
        backoffMs = BASE_BACKOFF_MS;
        nextAttemptMs = nowMs;
    }

    /**
     * 连失败了：加退避，必要时换下一个。
     *
     * @return 是否换了 master
     */
    public boolean onFailure(long nowMs) {
        failures++;
        nextAttemptMs = nowMs + backoffMs;
        backoffMs = Math.min(backoffMs * 2, MAX_BACKOFF_MS);
        if (failures >= FAILURES_BEFORE_ROTATE && masters.size() > 1) {
            index = (index + 1) % masters.size();
            failures = 0;
            // 换了人从头退避：新的一个不该背上一个的账。
            backoffMs = BASE_BACKOFF_MS;
            return true;
        }
        return false;
    }

    /** 重启时调用：上次成功的那个排到当前位置。 */
    public void preferLastGood() {
        if (lastGood == null) {
            return;
        }
        for (int i = 0; i < masters.size(); i++) {
            Master m = masters.get(i);
            if (m.host.equals(lastGood.host) && m.port == lastGood.port) {
                index = i;
                return;
            }
        }
    }

    public String status(long nowMs) {
        return String.format(Locale.US,
                "当前 %s（共 %d 个）  连续失败 %d  退避 %d 毫秒  还需等 %d 毫秒"
                + "  上次成功 %s",
                current(), masters.size(), failures, backoffMs,
                msUntilNextAttempt(nowMs),
                lastGood == null ? "无" : lastGood.toString());
    }
}
