package net.elfradio.h13radio;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * TG 订阅与 Last Heard 聚合。规则与 {@code tools/offline/talkgroup_model.py} 一致。
 *
 * <p>订阅分静态与动态：静态是配置进来的，动态由"向该 TG 发过射"激活、
 * 十五分钟无活动失效。**未订阅的 TG 不得占用射频**——热点上这条不守，
 * 本地就会被无关话务占满。
 */
public final class TalkgroupRouter {
    public static final long DYNAMIC_TTL_MS = 15L * 60L * 1000L;
    /** 超过此间隔视为新通话。 */
    public static final long CALL_GAP_MS = 2000L;
    public static final int MAX_LAST_HEARD = 50;

    /** 一次通话的聚合结果。 */
    public static final class Call {
        public final int src;
        public final int dst;
        public final int stream;
        public final int slot;
        public final long startMs;
        public long lastMs;
        public int frames;
        public int errors;

        Call(int src, int dst, int stream, int slot, long startMs) {
            this.src = src;
            this.dst = dst;
            this.stream = stream;
            this.slot = slot;
            this.startMs = startMs;
            this.lastMs = startMs;
        }

        public long durationMs() {
            return lastMs - startMs;
        }

        @Override
        public String toString() {
            return String.format(Locale.US,
                    "主叫 %d → 目标 %d  时隙%d  %.1f 秒  %d 帧  误码 %d",
                    src, dst, slot + 1, durationMs() / 1000.0, frames, errors);
        }
    }

    private final Set<Integer> staticTgs = new HashSet<>();
    private final Map<Integer, Long> dynamic = new HashMap<>();
    private final Map<String, Call> active = new LinkedHashMap<>();
    private final List<Call> lastHeard = new ArrayList<>();

    public void addStatic(int tg) {
        staticTgs.add(tg);
    }

    /** 向某 TG 发射即激活动态订阅。 */
    public void onLocalTransmit(int tg, long nowMs) {
        if (!staticTgs.contains(tg)) {
            dynamic.put(tg, nowMs);
        }
    }

    public List<Integer> expire(long nowMs) {
        List<Integer> dead = new ArrayList<>();
        Iterator<Map.Entry<Integer, Long>> it = dynamic.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, Long> e = it.next();
            if (nowMs - e.getValue() >= DYNAMIC_TTL_MS) {
                dead.add(e.getKey());
                it.remove();
            }
        }
        return dead;
    }

    public boolean allows(int tg, long nowMs) {
        expire(nowMs);
        if (staticTgs.contains(tg)) {
            return true;
        }
        if (dynamic.containsKey(tg)) {
            dynamic.put(tg, nowMs);          // 有流量即续期
            return true;
        }
        return false;
    }

    /** 并入一次通话；返回该帧是否放行。 */
    public boolean onFrame(int src, int dst, int stream, int slot, long nowMs,
            int errors) {
        if (!allows(dst, nowMs)) {
            return false;
        }
        String key = src + "/" + dst + "/" + stream;
        Call call = active.get(key);
        if (call == null || nowMs - call.lastMs > CALL_GAP_MS) {
            if (call != null) {
                finish(key);
            }
            call = new Call(src, dst, stream, slot, nowMs);
            active.put(key, call);
        }
        call.lastMs = nowMs;
        call.frames++;
        call.errors += errors;
        return true;
    }

    private void finish(String key) {
        Call call = active.remove(key);
        if (call == null) {
            return;
        }
        lastHeard.add(0, call);
        while (lastHeard.size() > MAX_LAST_HEARD) {
            lastHeard.remove(lastHeard.size() - 1);
        }
    }

    public void flush(long nowMs) {
        for (String key : new ArrayList<>(active.keySet())) {
            Call call = active.get(key);
            if (call != null && nowMs - call.lastMs > CALL_GAP_MS) {
                finish(key);
            }
        }
    }

    public List<Call> lastHeard() {
        return Collections.unmodifiableList(lastHeard);
    }

    public int activeCalls() {
        return active.size();
    }
}
