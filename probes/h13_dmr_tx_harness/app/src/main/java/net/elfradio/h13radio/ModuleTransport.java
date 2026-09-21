package net.elfradio.h13radio;

/**
 * 帧泵与基带模块之间的接缝。**实现放在设备侧**（内存桥、串口），
 * 这里只定义形状，好让帧泵可以对着假的实现逐条验收。
 *
 * <p>形状由已证实的供数契约决定，不是随便设计的：
 * 发射态下模块**只持续交帧、不发回执短帧**（2.8.75）。因此接缝是
 * 「等一次交帧 → 回送一个单元」，一对一，没有确认往返。
 * 按回执节拍供数会把串口往返延迟串进时间轴——实测 68 单元被拉到
 * 10.4 秒而音频只有 4.08 秒，对端完全无声。
 */
public interface ModuleTransport {

    /**
     * 等模块交出一帧。
     *
     * @param timeoutMs 最长等待
     * @return 等到了返回 true；超时返回 false。**超时不是可以补写的理由**
     *         ——没等到交帧就写出去，就是无许可写出，会破坏一对一。
     */
    boolean awaitOffer(long timeoutMs) throws Exception;

    /** 回送一个语音单元。调用方保证每次 {@link #awaitOffer} 成功后只调一次。 */
    void writeUnit(byte[] unit) throws Exception;

    /** 当前时刻，毫秒。抽出来是为了让用例能控制时间轴。 */
    long nowMs();
}
