package net.elfradio.h13dmrtx;

final class McuDebugOutput {
    private static final long COMMAND_TIMEOUT_MS = 500;
    private final McuMemory.TextExchange exchange;
    private boolean mayBeEnabled;

    McuDebugOutput(McuMemory.TextExchange exchange) {
        if (exchange == null) {
            throw new IllegalArgumentException("文本交换器为空");
        }
        this.exchange = exchange;
    }

    void enable() throws Exception {
        // 写出过程中即使证据持久化失败，也必须在收尾时尝试关闭。
        mayBeEnabled = true;
        exchange.exchange("print 1", COMMAND_TIMEOUT_MS);
    }

    void disable() throws Exception {
        if (!mayBeEnabled) {
            return;
        }
        exchange.exchange("print 0", COMMAND_TIMEOUT_MS);
        mayBeEnabled = false;
    }

    boolean restored() {
        return !mayBeEnabled;
    }

    boolean enabled() {
        return mayBeEnabled;
    }
}
