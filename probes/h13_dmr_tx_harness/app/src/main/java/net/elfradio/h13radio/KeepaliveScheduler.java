package net.elfradio.h13radio;

import java.util.Locale;

/**
 * 网络切换与 NAT 保活的节拍决策。**不碰 socket，也不碰 Android 网络回调**
 * ——只回答「现在该不该发保活、该不该重连」。
 *
 * <p>热点挂在家里的路由器后面，长期无人值守，两件事一定会发生：
 *
 * <ul>
 *   <li><b>NAT 映射超时。</b> UDP 的映射在没有流量时会被回收，短则半分钟。
 *       映射一丢，master 发过来的语音就再也到不了。</li>
 *   <li><b>网络切换。</b> WiFi 掉了换蜂窝、IP 变了、睡醒之后接口重建。
 *       旧的 socket 还"连着"，但发出去的包再也没人收——
 *       <b>这种坏法比断开更难发现</b>，因为本端看不到任何错误。</li>
 * </ul>
 *
 * <p>因此两条规则：
 *
 * <ul>
 *   <li>保活间隔要**明显短于**常见的 NAT 超时，宁可多发几个字节；</li>
 *   <li>网络一变就**立刻重连，不等超时**——等超时意味着这段时间里
 *       所有来话都丢了，而且本端毫无察觉。</li>
 * </ul>
 */
public final class KeepaliveScheduler {

    /**
     * 保活间隔。常见家用 NAT 的 UDP 映射寿命在 30 秒到几分钟之间，
     * 取 20 秒是为了在最短的那一档下也留出余量。
     */
    public static final long KEEPALIVE_INTERVAL_MS = 20_000L;
    /** 连续几次保活没有任何回应就判定链路已经哑了。 */
    public static final int SILENT_KEEPALIVES_BEFORE_RECONNECT = 3;

    private long lastSentMs = Long.MIN_VALUE;
    private long lastHeardMs = Long.MIN_VALUE;
    private int silentStreak;
    private String networkKey;
    private boolean reconnectPending;
    private String reconnectReason;

    /** 网络标识变了就必须重连。传入的可以是接口名＋本地地址之类的组合。 */
    public void onNetworkChanged(String key, long nowMs) {
        if (networkKey != null && !networkKey.equals(key)) {
            reconnectPending = true;
            reconnectReason = "网络从 " + networkKey + " 变为 " + key;
            silentStreak = 0;
            lastSentMs = Long.MIN_VALUE;
        }
        networkKey = key;
    }

    /** 收到任何来自 master 的东西。 */
    public void onHeard(long nowMs) {
        lastHeardMs = nowMs;
        silentStreak = 0;
    }

    public boolean keepaliveDue(long nowMs) {
        return lastSentMs == Long.MIN_VALUE
                || nowMs - lastSentMs >= KEEPALIVE_INTERVAL_MS;
    }

    /** 记一次保活已发出；若此前那一次至今没被回应，静默计数加一。 */
    public void onKeepaliveSent(long nowMs) {
        if (lastSentMs != Long.MIN_VALUE && lastHeardMs < lastSentMs) {
            silentStreak++;
            if (silentStreak >= SILENT_KEEPALIVES_BEFORE_RECONNECT) {
                reconnectPending = true;
                reconnectReason = "连续 " + silentStreak + " 次保活无回应";
            }
        }
        lastSentMs = nowMs;
    }

    public boolean reconnectDue() {
        return reconnectPending;
    }

    public String reconnectReason() {
        return reconnectReason;
    }

    /** 重连动作已执行，清掉待办。 */
    public void onReconnected(long nowMs) {
        reconnectPending = false;
        reconnectReason = null;
        silentStreak = 0;
        lastSentMs = Long.MIN_VALUE;
        lastHeardMs = nowMs;
    }

    public int silentStreak() {
        return silentStreak;
    }

    public String status(long nowMs) {
        return String.format(Locale.US,
                "网络 %s  静默 %d 次  %s",
                networkKey == null ? "未知" : networkKey, silentStreak,
                reconnectPending ? "待重连：" + reconnectReason : "无待办");
    }
}
