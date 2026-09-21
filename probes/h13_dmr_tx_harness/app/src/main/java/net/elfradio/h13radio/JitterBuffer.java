package net.elfradio.h13radio;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;

/**
 * 抖动缓冲：网络帧到达不定时，空口要求 60 毫秒一帧，中间必须垫一层。
 *
 * <p>两条硬规则，与 {@code tools/offline/jitter_buffer_model.py} 一致：
 * <ul>
 *   <li>取帧时缓冲为空，**补静音，绝不断流**。模块交一帧就必须回一帧，
 *       回不上会话就散（2.8.73、2.8.75）。</li>
 *   <li>缓冲满了丢**最旧**的，保新不保旧。语音迟到没有价值。</li>
 * </ul>
 */
public final class JitterBuffer {
    /** 空口帧长。 */
    public static final int FRAME_BYTES = 9;
    /** 默认深度三帧 = 180 毫秒，与 2.9.33 的模型取值一致。 */
    public static final int DEFAULT_DEPTH = 3;

    private static final byte[] SILENCE = new byte[FRAME_BYTES];

    private final int depth;
    private final Deque<byte[]> queue = new ArrayDeque<>();
    private int underruns;
    private int drops;
    private int delivered;

    public JitterBuffer() {
        this(DEFAULT_DEPTH);
    }

    public JitterBuffer(int depth) {
        if (depth <= 0) {
            throw new IllegalArgumentException("缓冲深度必须为正");
        }
        this.depth = depth;
    }

    /** 网络侧到帧。满了就丢最旧的。 */
    public void push(byte[] frame) {
        if (frame == null || frame.length != FRAME_BYTES) {
            throw new IllegalArgumentException("帧必须为 " + FRAME_BYTES + " 字节");
        }
        if (queue.size() >= depth) {
            queue.pollFirst();
            drops++;
        }
        queue.addLast(frame.clone());
    }

    /** 模块交帧时取一帧回送；无帧则补静音。 */
    public byte[] pop() {
        delivered++;
        byte[] head = queue.pollFirst();
        if (head != null) {
            return head;
        }
        underruns++;
        return SILENCE.clone();
    }

    public int size() {
        return queue.size();
    }

    public int underruns() {
        return underruns;
    }

    public int drops() {
        return drops;
    }

    public int delivered() {
        return delivered;
    }

    public String stats() {
        return String.format(Locale.US,
                "回送 %d 帧，欠载补静音 %d（%.1f%%），过载丢弃 %d，当前深度 %d",
                delivered, underruns,
                100.0 * underruns / Math.max(delivered, 1), drops, queue.size());
    }
}
