package net.elfradio.h13dmrtx;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

final class McuMemory {
    interface TextExchange {
        byte[] exchange(String command, long timeoutMs) throws Exception;
    }

    interface ReadExchange {
        byte[] exchange(String command, int expectedLength, long timeoutMs)
                throws Exception;
    }

    interface ReadAttemptObserver {
        void observe(ReadAttempt attempt) throws Exception;
    }

    static final long READ_TIMEOUT_MS = 10000;
    private final TextExchange textExchange;
    private final ReadExchange readExchange;
    private final ReadAttemptObserver readAttemptObserver;

    McuMemory(TextExchange exchange) {
        this(exchange, (command, expectedLength, timeoutMs) ->
                exchange.exchange(command, timeoutMs), attempt -> { });
    }

    McuMemory(TextExchange textExchange, ReadExchange readExchange) {
        this(textExchange, readExchange, attempt -> { });
    }

    McuMemory(TextExchange textExchange, ReadExchange readExchange,
            ReadAttemptObserver readAttemptObserver) {
        if (textExchange == null || readExchange == null) {
            throw new IllegalArgumentException("文本交换器为空");
        }
        if (readAttemptObserver == null) {
            throw new IllegalArgumentException("读取尝试观察器为空");
        }
        this.textExchange = textExchange;
        this.readExchange = readExchange;
        this.readAttemptObserver = readAttemptObserver;
    }

    ReadResult read(int address, int length) throws Exception {
        return read(address, length, 3, READ_TIMEOUT_MS);
    }

    ReadResult readOnce(int address, int length, long timeoutMs)
            throws Exception {
        if (timeoutMs <= 0) {
            throw new IllegalArgumentException("读取超时必须为正数");
        }
        return read(address, length, 1, timeoutMs);
    }

    private ReadResult read(int address, int length, int maximumAttempts,
            long timeoutMs) throws Exception {
        if (length <= 0) {
            throw new IllegalArgumentException("读取长度必须为正数");
        }
        String command = String.format(Locale.US, "memread 0x%08x 0x%x",
                address, length);
        List<ReadAttempt> attempts = new ArrayList<>();
        for (int attempt = 1; attempt <= maximumAttempts; attempt++) {
            long observedAt = System.currentTimeMillis();
            byte[] raw;
            try {
                raw = readExchange.exchange(command, length, timeoutMs);
            } catch (Exception error) {
                ReadAttempt failed = new ReadAttempt(command, address, length,
                        attempt, observedAt, new byte[0], new byte[0],
                        error.getClass().getSimpleName() + ":"
                        + String.valueOf(error.getMessage()));
                readAttemptObserver.observe(failed);
                throw error;
            }
            byte[] parsed = parseRead(raw, command, length);
            ReadAttempt observed = new ReadAttempt(command, address, length,
                    attempt, observedAt, raw, parsed,
                    parsed.length == length ? "" : "length_mismatch");
            attempts.add(observed);
            readAttemptObserver.observe(observed);
            if (parsed.length == length) {
                return new ReadResult(command, attempt, raw, parsed);
            }
            if (attempt < maximumAttempts) {
                Thread.sleep(40);
            }
        }
        ReadAttempt last = attempts.get(attempts.size() - 1);
        throw new ReadFailure("memread长度错误 address="
                + String.format(Locale.US, "0x%08x", address)
                + " expected=" + length + " parsed=" + last.parsed.length
                + " raw=" + last.raw.length, attempts);
    }

    void writeExact(int address, byte[] value) throws Exception {
        if (value == null) {
            throw new IllegalArgumentException("写入数据为空");
        }
        int offset = 0;
        while (offset < value.length && ((address + offset) & 3) != 0) {
            writeByte(address + offset, value[offset] & 0xff);
            offset++;
        }
        while (offset + 4 <= value.length) {
            int word = (value[offset] & 0xff)
                    | ((value[offset + 1] & 0xff) << 8)
                    | ((value[offset + 2] & 0xff) << 16)
                    | ((value[offset + 3] & 0xff) << 24);
            writeWord(address + offset, word);
            offset += 4;
        }
        while (offset < value.length) {
            writeByte(address + offset, value[offset] & 0xff);
            offset++;
        }
    }

    byte[] writeWord(int address, int value) throws Exception {
        if ((address & 3) != 0) {
            throw new IllegalArgumentException("memwrite4地址未四字节对齐");
        }
        String command = String.format(Locale.US,
                "memwrite4 0x%08x 0x%08x", address, value);
        return textExchange.exchange(command, 500);
    }

    byte[] writeByte(int address, int value) throws Exception {
        String command = String.format(Locale.US,
                "memwrite1 0x%08x 0x%02x", address, value & 0xff);
        return textExchange.exchange(command, 500);
    }

    static byte[] parseRead(byte[] response, String command,
            int expectedLength) {
        if (response == null || command == null || expectedLength < 0) {
            return new byte[0];
        }
        String text = new String(response, StandardCharsets.US_ASCII);
        String echo = command + "\r\n";
        int echoOffset = text.indexOf(echo);
        if (echoOffset < 0) {
            return new byte[0];
        }
        String payload = text.substring(echoOffset + echo.length());
        ByteArrayOutputStream parsed = new ByteArrayOutputStream(expectedLength);
        int offset = 0;
        while (parsed.size() < expectedLength) {
            while (offset < payload.length()
                    && Character.isWhitespace(payload.charAt(offset))) {
                offset++;
            }
            if (offset + 2 > payload.length()) {
                return new byte[0];
            }
            int high = Character.digit(payload.charAt(offset), 16);
            int low = Character.digit(payload.charAt(offset + 1), 16);
            int after = offset + 2;
            if (high < 0 || low < 0 || (after < payload.length()
                    && !Character.isWhitespace(payload.charAt(after)))) {
                return new byte[0];
            }
            parsed.write((high << 4) | low);
            offset = after;
        }
        return parsed.toByteArray();
    }

    static final class ReadResult {
        final String command;
        final int attempt;
        final byte[] raw;
        final byte[] parsed;

        ReadResult(String command, int attempt, byte[] raw, byte[] parsed) {
            this.command = command;
            this.attempt = attempt;
            this.raw = raw.clone();
            this.parsed = parsed.clone();
        }

        boolean equalsBytes(byte[] expected) {
            return Arrays.equals(parsed, expected);
        }
    }

    static final class ReadAttempt {
        final String command;
        final int address;
        final int expectedLength;
        final int attempt;
        final long observedAtEpochMs;
        final byte[] raw;
        final byte[] parsed;
        final String failure;

        ReadAttempt(String command, int address, int expectedLength,
                int attempt, long observedAtEpochMs, byte[] raw,
                byte[] parsed, String failure) {
            this.command = command;
            this.address = address;
            this.expectedLength = expectedLength;
            this.attempt = attempt;
            this.observedAtEpochMs = observedAtEpochMs;
            this.raw = raw.clone();
            this.parsed = parsed.clone();
            this.failure = failure == null ? "" : failure;
        }
    }

    static final class ReadFailure extends IOException {
        final List<ReadAttempt> attempts;

        ReadFailure(String message, List<ReadAttempt> attempts) {
            super(message);
            this.attempts = Collections.unmodifiableList(
                    new ArrayList<>(attempts));
        }
    }
}
