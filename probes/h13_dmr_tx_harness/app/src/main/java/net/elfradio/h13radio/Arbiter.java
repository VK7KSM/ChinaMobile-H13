package net.elfradio.h13radio;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 单工会话仲裁：单工电台任一时刻只能收或只能发，不存在"同时"。
 *
 * <p>规则与 {@code tools/offline/arbitration_model.py} 逐条对应，用例也一一对照。
 * 两份实现并存不是重复：模型先定行为、Java 实现要真正跑在设备上，
 * 两边用同一组用例互相校验，行为一旦分叉就会被用例抓住。
 *
 * <p>难缠的不是主流程，是**拒绝的时机**——接收中不得发射、尾音期不接受新发射、
 * 发射有硬性时限。这些若留到服务里再补，就会表现为偶发故障。
 */
public final class Arbiter {
    public enum State { IDLE, RX, TX_LOCAL, TX_NET }

    /** 发射硬性时限。单次发射超过它一律强制落回空闲。 */
    public static final long MAX_TX_MS = 30_000L;
    /** 尾音期：松键后这段时间内不接受新的发射请求。 */
    public static final long HANG_MS = 500L;

    private State state = State.IDLE;
    private long sinceMs;
    private long hangUntilMs;
    private boolean queuedNet;
    private final List<String> log = new ArrayList<>();

    public State state() {
        return state;
    }

    public boolean queuedNet() {
        return queuedNet;
    }

    public List<String> log() {
        return Collections.unmodifiableList(log);
    }

    private void to(State next, long nowMs, String why) {
        log.add(nowMs + " " + state + "→" + next + "（" + why + "）");
        state = next;
        sinceMs = nowMs;
    }

    /** 规则一：接收优先，正在发射也让位。 */
    public void onRxStart(long nowMs) {
        if (state == State.TX_LOCAL || state == State.TX_NET) {
            to(State.RX, nowMs, "接收优先于发射");
        } else if (state == State.IDLE) {
            to(State.RX, nowMs, "空口来话");
        }
    }

    public void onRxEnd(long nowMs) {
        if (state != State.RX) {
            return;
        }
        to(State.IDLE, nowMs, "空口结束");
        if (queuedNet) {
            queuedNet = false;
            requestNet(nowMs);
        }
    }

    /** 规则二：本地 PTT 优先于网络来话；规则一仍高于它。 */
    public boolean requestLocal(long nowMs) {
        if (state == State.RX) {
            return false;
        }
        if (nowMs < hangUntilMs) {
            return false;
        }
        if (state == State.TX_NET) {
            to(State.TX_LOCAL, nowMs, "本地 PTT 抢占网络来话");
            return true;
        }
        if (state == State.IDLE) {
            to(State.TX_LOCAL, nowMs, "本地 PTT");
            return true;
        }
        return false;
    }

    /** 规则三：发射中的网络来话入队，只留最近一条。 */
    public boolean requestNet(long nowMs) {
        if (state != State.IDLE) {
            queuedNet = true;
            return false;
        }
        if (nowMs < hangUntilMs) {
            queuedNet = true;
            return false;
        }
        to(State.TX_NET, nowMs, "网络来话");
        return true;
    }

    public void release(long nowMs) {
        if (state == State.TX_LOCAL || state == State.TX_NET) {
            to(State.IDLE, nowMs, "发射结束");
            hangUntilMs = nowMs + HANG_MS;
        }
    }

    /** 规则四：发射硬性时限。调用方须周期性调用它，否则时限不会自己生效。 */
    public void tick(long nowMs) {
        boolean transmitting = state == State.TX_LOCAL || state == State.TX_NET;
        if (transmitting && nowMs - sinceMs >= MAX_TX_MS) {
            to(State.IDLE, nowMs, "超过 " + MAX_TX_MS + " 毫秒发射上限");
            hangUntilMs = nowMs + HANG_MS;
        }
    }
}
