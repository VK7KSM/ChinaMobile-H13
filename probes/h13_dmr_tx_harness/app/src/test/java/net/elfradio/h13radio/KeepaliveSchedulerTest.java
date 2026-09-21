package net.elfradio.h13radio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 保活与网络切换的节拍用例。
 *
 * <p>盯的是最难发现的那种坏法：**socket 还"连着"，包却再也到不了**。
 * 本端看不到任何错误，只有"很久没收到东西"这一个信号。
 */
public class KeepaliveSchedulerTest {

    @Test
    public void firstKeepaliveIsDueImmediately() {
        KeepaliveScheduler k = new KeepaliveScheduler();
        assertTrue("一开始就该发一次，别等一个周期", k.keepaliveDue(0));
    }

    @Test
    public void keepaliveFollowsTheInterval() {
        KeepaliveScheduler k = new KeepaliveScheduler();
        k.onKeepaliveSent(0);
        assertFalse(k.keepaliveDue(KeepaliveScheduler.KEEPALIVE_INTERVAL_MS - 1));
        assertTrue(k.keepaliveDue(KeepaliveScheduler.KEEPALIVE_INTERVAL_MS));
    }

    @Test
    public void intervalIsWellUnderCommonNatTimeouts() {
        assertTrue("保活间隔必须明显短于常见 NAT 超时（最短一档约 30 秒）",
                KeepaliveScheduler.KEEPALIVE_INTERVAL_MS <= 25_000L);
    }

    @Test
    public void answeredKeepalivesNeverTriggerReconnect() {
        KeepaliveScheduler k = new KeepaliveScheduler();
        long now = 0;
        for (int i = 0; i < 10; i++) {
            k.onKeepaliveSent(now);
            k.onHeard(now + 50);
            now += KeepaliveScheduler.KEEPALIVE_INTERVAL_MS;
        }
        assertFalse(k.reconnectDue());
        assertEquals(0, k.silentStreak());
    }

    @Test
    public void silentKeepalivesEventuallyForceReconnect() {
        KeepaliveScheduler k = new KeepaliveScheduler();
        long now = 0;
        k.onKeepaliveSent(now);                 // 第一次，之前没有可判的
        for (int i = 0; i < KeepaliveScheduler.SILENT_KEEPALIVES_BEFORE_RECONNECT
                - 1; i++) {
            now += KeepaliveScheduler.KEEPALIVE_INTERVAL_MS;
            k.onKeepaliveSent(now);
            assertFalse("还没到次数不该重连", k.reconnectDue());
        }
        now += KeepaliveScheduler.KEEPALIVE_INTERVAL_MS;
        k.onKeepaliveSent(now);
        assertTrue("连续无回应必须重连", k.reconnectDue());
        assertTrue(k.reconnectReason().contains("保活无回应"));
    }

    @Test
    public void oneAnswerResetsTheStreak() {
        KeepaliveScheduler k = new KeepaliveScheduler();
        long now = 0;
        k.onKeepaliveSent(now);
        now += KeepaliveScheduler.KEEPALIVE_INTERVAL_MS;
        k.onKeepaliveSent(now);
        assertEquals(1, k.silentStreak());
        k.onHeard(now + 10);
        assertEquals("收到一次就清账", 0, k.silentStreak());
        assertFalse(k.reconnectDue());
    }

    /** 网络一变就立刻重连——等超时意味着这段时间的来话全丢且毫无察觉。 */
    @Test
    public void networkChangeForcesImmediateReconnect() {
        KeepaliveScheduler k = new KeepaliveScheduler();
        k.onNetworkChanged("wlan0/192.168.1.20", 0);
        assertFalse("第一次认识网络不算变化", k.reconnectDue());

        k.onNetworkChanged("rmnet0/10.1.2.3", 1000);
        assertTrue("换了网络必须立刻重连", k.reconnectDue());
        assertTrue(k.reconnectReason().contains("网络从"));
    }

    @Test
    public void sameNetworkKeyIsNotAChange() {
        KeepaliveScheduler k = new KeepaliveScheduler();
        k.onNetworkChanged("wlan0/192.168.1.20", 0);
        k.onNetworkChanged("wlan0/192.168.1.20", 5000);
        assertFalse(k.reconnectDue());
    }

    @Test
    public void reconnectClearsEverything() {
        KeepaliveScheduler k = new KeepaliveScheduler();
        k.onNetworkChanged("a", 0);
        k.onNetworkChanged("b", 1000);
        assertTrue(k.reconnectDue());

        k.onReconnected(2000);
        assertFalse(k.reconnectDue());
        assertEquals(0, k.silentStreak());
        assertTrue("重连之后立刻发一次保活", k.keepaliveDue(2000));
    }

    @Test
    public void statusIsRenderable() {
        KeepaliveScheduler k = new KeepaliveScheduler();
        assertTrue(k.status(0).contains("无待办"));
        k.onNetworkChanged("a", 0);
        k.onNetworkChanged("b", 1);
        assertTrue(k.status(1).contains("待重连"));
    }
}
