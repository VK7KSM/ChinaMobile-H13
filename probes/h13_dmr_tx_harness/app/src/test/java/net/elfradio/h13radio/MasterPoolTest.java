package net.elfradio.h13radio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;

import org.junit.Test;

/**
 * 多 master 轮换与重连节流用例。
 *
 * <p>重点不在"能不能换"，在**换得够不够克制**：不节流的重连会把对方
 * 当成攻击，而掉线期间越急越连不上。
 */
public class MasterPoolTest {

    private static MasterPool pool(int n) {
        MasterPool.Master[] m = new MasterPool.Master[n];
        for (int i = 0; i < n; i++) {
            m[i] = new MasterPool.Master("master" + i + ".example", 62031);
        }
        return new MasterPool(Arrays.asList(m));
    }

    @Test
    public void emptyPoolIsRefused() {
        try {
            new MasterPool(Collections.<MasterPool.Master>emptyList());
            throw new AssertionError("空池应当被拒");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("至少"));
        }
    }

    @Test
    public void endpointValidatesHostAndPort() {
        try {
            new MasterPool.Master("  ", 62031);
            throw new AssertionError("空地址应当被拒");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("地址"));
        }
        try {
            new MasterPool.Master("a.example", 0);
            throw new AssertionError("端口 0 应当被拒");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("端口"));
        }
        try {
            new MasterPool.Master("a.example", 65536);
            throw new AssertionError("端口越界应当被拒");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("端口"));
        }
    }

    @Test
    public void firstAttemptIsAllowedImmediately() {
        MasterPool p = pool(2);
        assertTrue("开机就该能连一次", p.mayAttempt(0));
        assertEquals(0, p.msUntilNextAttempt(0));
    }

    @Test
    public void backoffDoublesAndIsCapped() {
        MasterPool p = pool(1);                 // 只有一个，不会换
        long now = 0;
        long expected = MasterPool.BASE_BACKOFF_MS;
        for (int i = 0; i < 12; i++) {
            p.onFailure(now);
            assertFalse("退避期内不得再连", p.mayAttempt(now));
            assertTrue("退避期内不得再连", p.mayAttempt(now + expected));
            now += expected;
            expected = Math.min(expected * 2, MasterPool.MAX_BACKOFF_MS);
        }
        assertEquals("退避必须封顶", MasterPool.MAX_BACKOFF_MS, p.backoffMs());
    }

    /** 一断就换会掩盖"这个其实能用、只是刚好抖了一下"。 */
    @Test
    public void rotationWaitsUntilTheCurrentOneReallyFails() {
        MasterPool p = pool(3);
        String first = p.current().toString();
        for (int i = 0; i < MasterPool.FAILURES_BEFORE_ROTATE - 1; i++) {
            assertFalse("还没到次数就换太急了", p.onFailure(i * 1000L));
            assertEquals(first, p.current().toString());
        }
        assertTrue("试满次数才换", p.onFailure(99_000L));
        assertFalse(first.equals(p.current().toString()));
    }

    @Test
    public void rotationResetsBackoffForTheNewMaster() {
        MasterPool p = pool(2);
        for (int i = 0; i < MasterPool.FAILURES_BEFORE_ROTATE; i++) {
            p.onFailure(i * 1000L);
        }
        assertEquals("新的一个不该背上一个的账",
                MasterPool.BASE_BACKOFF_MS, p.backoffMs());
        assertEquals(0, p.failures());
    }

    @Test
    public void rotationWrapsAround() {
        MasterPool p = pool(2);
        String first = p.current().toString();
        long now = 0;
        for (int round = 0; round < 2; round++) {
            for (int i = 0; i < MasterPool.FAILURES_BEFORE_ROTATE; i++) {
                p.onFailure(now);
                now += 1000;
            }
        }
        assertEquals("两次轮换应当绕回第一个", first, p.current().toString());
    }

    @Test
    public void singleMasterNeverRotates() {
        MasterPool p = pool(1);
        String only = p.current().toString();
        for (int i = 0; i < 10; i++) {
            assertFalse("只有一个就没得换", p.onFailure(i * 1000L));
        }
        assertEquals(only, p.current().toString());
    }

    @Test
    public void successClearsBackoffAndRemembersTheMaster() {
        MasterPool p = pool(3);
        p.onFailure(0);
        p.onFailure(10_000);
        assertTrue(p.backoffMs() > MasterPool.BASE_BACKOFF_MS);

        p.onSuccess(20_000);
        assertEquals(MasterPool.BASE_BACKOFF_MS, p.backoffMs());
        assertEquals(0, p.failures());
        assertTrue("成功之后立刻可以再连", p.mayAttempt(20_000));
        assertEquals(p.current().toString(), p.lastGood().toString());
    }

    /** 每次开机都从列表头开始，会让首选 master 承担全部重连压力。 */
    @Test
    public void preferLastGoodOnRestart() {
        MasterPool p = pool(3);
        for (int i = 0; i < MasterPool.FAILURES_BEFORE_ROTATE; i++) {
            p.onFailure(i * 1000L);
        }
        p.onSuccess(50_000);
        String good = p.lastGood().toString();

        MasterPool restarted = pool(3);
        assertFalse("重启默认在列表头", good.equals(restarted.current().toString()));
        // 把上次成功的那个喂回去（真实场景里从持久化读出来）
        for (int i = 0; i < MasterPool.FAILURES_BEFORE_ROTATE; i++) {
            restarted.onFailure(i * 1000L);
        }
        restarted.onSuccess(50_000);
        restarted.preferLastGood();
        assertEquals(good, restarted.current().toString());
    }

    @Test
    public void preferLastGoodIsHarmlessWithoutHistory() {
        MasterPool p = pool(2);
        String before = p.current().toString();
        p.preferLastGood();
        assertEquals(before, p.current().toString());
    }

    @Test
    public void statusIsRenderable() {
        MasterPool p = pool(2);
        p.onFailure(0);
        assertTrue(p.status(0).contains("连续失败 1"));
        assertTrue(p.status(0).contains("上次成功 无"));
    }
}
