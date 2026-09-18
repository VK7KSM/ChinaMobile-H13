package net.elfradio.h13dmrtx;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import org.junit.Test;

public final class SramTransactionTest {
    @Test
    public void memreadParserRequiresExactEchoAndBytes() {
        String command = "memread 0x2000003c 0x4";
        byte[] raw = (command + "\r\ndd 1d 02 08\r\n")
                .getBytes(StandardCharsets.US_ASCII);
        assertArrayEquals(new byte[] {(byte) 0xdd, 0x1d, 0x02, 0x08},
                McuMemory.parseRead(raw, command, 4));
        assertEquals(0, McuMemory.parseRead(
                "dd 1d 02 08".getBytes(StandardCharsets.US_ASCII),
                command, 4).length);
        assertEquals(0, McuMemory.parseRead((command + "\r\ndd 1d 02")
                .getBytes(StandardCharsets.US_ASCII), command, 4).length);
    }

    @Test
    public void longMemreadIsIncompleteUntilEveryByteArrives() {
        String command = "memread 0x08024dc0 0x374";
        StringBuilder full = new StringBuilder(command).append("\r\n");
        for (int index = 0; index < 0x374; index++) {
            full.append(String.format(Locale.US, "%02x ", index & 0xff));
        }
        byte[] raw = full.append("\r\n").toString()
                .getBytes(StandardCharsets.US_ASCII);
        assertEquals(0, McuMemory.parseRead(Arrays.copyOf(raw, 512),
                command, 0x374).length);
        assertEquals(0x374, McuMemory.parseRead(raw, command, 0x374).length);
    }

    @Test
    public void everyMalformedMemreadAttemptIsExposedBeforeFailure()
            throws Exception {
        List<McuMemory.ReadAttempt> attempts = new ArrayList<>();
        McuMemory memory = new McuMemory((command, timeoutMs) -> new byte[0],
                (command, expectedLength, timeoutMs) ->
                        (command + "\r\n")
                                .getBytes(StandardCharsets.US_ASCII),
                attempts::add);

        McuMemory.ReadFailure failure = assertThrows(McuMemory.ReadFailure.class,
                () -> memory.read(0x20003064, 4));

        assertEquals(3, attempts.size());
        assertEquals(3, failure.attempts.size());
        for (int index = 0; index < attempts.size(); index++) {
            McuMemory.ReadAttempt attempt = attempts.get(index);
            assertEquals(index + 1, attempt.attempt);
            assertEquals(0x20003064, attempt.address);
            assertEquals(4, attempt.expectedLength);
            assertEquals(0, attempt.parsed.length);
            assertTrue(attempt.raw.length > 0);
            assertEquals("length_mismatch", attempt.failure);
            assertTrue(attempt.observedAtEpochMs > 0);
        }
    }

    @Test
    public void boundedSingleReadDoesNotUseTheNormalThreeAttemptBudget()
            throws Exception {
        List<McuMemory.ReadAttempt> attempts = new ArrayList<>();
        McuMemory memory = new McuMemory((command, timeoutMs) -> new byte[0],
                (command, expectedLength, timeoutMs) ->
                        (command + "\r\n")
                                .getBytes(StandardCharsets.US_ASCII),
                attempts::add);

        assertThrows(McuMemory.ReadFailure.class,
                () -> memory.readOnce(0x20003064, 4, 750));
        assertEquals(1, attempts.size());
        assertEquals(1, attempts.get(0).attempt);
    }

    @Test
    public void transportExceptionIsExposedAsOneFailedAttempt()
            throws Exception {
        List<McuMemory.ReadAttempt> attempts = new ArrayList<>();
        McuMemory memory = new McuMemory((command, timeoutMs) -> new byte[0],
                (command, expectedLength, timeoutMs) -> {
                    throw new java.io.IOException("串口已关闭");
                }, attempts::add);

        assertThrows(java.io.IOException.class,
                () -> memory.read(0x2000003c, 4));
        assertEquals(1, attempts.size());
        assertEquals(0, attempts.get(0).raw.length);
        assertTrue(attempts.get(0).failure.contains("串口已关闭"));
    }

    @Test
    public void transactionBacksUpUploadsAndRestoresExactBytes()
            throws Exception {
        FakeMemory fake = new FakeMemory(0x2000, 64);
        for (int index = 0; index < fake.bytes.length; index++) {
            fake.bytes[index] = (byte) (index * 3 + 1);
        }
        byte[] original = fake.bytes.clone();
        List<String> evidence = new ArrayList<>();
        McuMemory memory = new McuMemory(fake);
        SramTransaction tx = new SramTransaction(memory,
                (stage, region, result) -> evidence.add(stage + ":" + region.name
                        + ":" + result.raw.length + ":" + result.parsed.length));
        SramTransaction.Region region = tx.backupStable("测试窗", 0x2004, 12);
        byte[] replacement = new byte[12];
        Arrays.fill(replacement, (byte) 0x5a);
        tx.upload(region, replacement);
        assertTrue(tx.mutated());
        assertArrayEquals(replacement, Arrays.copyOfRange(fake.bytes, 4, 16));
        tx.restoreAll();
        assertTrue(tx.restored());
        assertArrayEquals(original, fake.bytes);
        assertEquals(5, evidence.size());
        assertTrue(evidence.get(3).startsWith("恢复前:"));
    }

    @Test
    public void untouchedTransactionRestoresWithoutAnyWrite() throws Exception {
        FakeMemory fake = new FakeMemory(0x2000, 64);
        SramTransaction tx = new SramTransaction(new McuMemory(fake),
                (stage, region, result) -> {});
        tx.backupStable("只读窗", 0x2004, 12);
        assertTrue(tx.restoredOrUntouched());
        tx.restoreAll();
        assertTrue(tx.restored());
        assertEquals(0, fake.writeCount);
    }

    @Test
    public void transactionRejectsOverlappingBackup() throws Exception {
        FakeMemory fake = new FakeMemory(0x2000, 64);
        SramTransaction tx = new SramTransaction(new McuMemory(fake),
                (stage, region, result) -> {});
        tx.backupStable("甲", 0x2004, 12);
        assertThrows(IllegalArgumentException.class,
                () -> tx.backupStable("乙", 0x2008, 8));
    }

    @Test
    public void unstablePreimageFailsBeforeAnyWrite() {
        FakeMemory fake = new FakeMemory(0x2000, 64);
        fake.mutateAfterFirstRead = true;
        SramTransaction tx = new SramTransaction(new McuMemory(fake),
                (stage, region, result) -> {});
        assertThrows(Exception.class,
                () -> tx.backupStable("动态窗", 0x2004, 4));
        assertEquals(0, fake.writeCount);
    }

    @Test
    public void failedPreRestoreCaptureDoesNotBlockExactRestoration()
            throws Exception {
        FakeMemory fake = new FakeMemory(0x2000, 64);
        for (int index = 0; index < fake.bytes.length; index++) {
            fake.bytes[index] = (byte) (index + 7);
        }
        byte[] original = fake.bytes.clone();
        SramTransaction tx = new SramTransaction(new McuMemory(fake),
                (stage, region, result) -> {});
        SramTransaction.Region region = tx.backupStable("恢复优先窗",
                0x2004, 12);
        byte[] replacement = new byte[12];
        Arrays.fill(replacement, (byte) 0x66);
        tx.upload(region, replacement);

        fake.malformedReadsRemaining = 1;
        Exception error = assertThrows(Exception.class, tx::restoreAll);

        assertTrue(error.getMessage().contains("停止可选快照并继续恢复"));
        assertTrue(tx.restored());
        assertArrayEquals(original, fake.bytes);
    }

    @Test
    public void everyRegionIsWrittenBeforeStrictVerificationStarts()
            throws Exception {
        FakeMemory fake = new FakeMemory(0x2000, 64);
        for (int index = 0; index < fake.bytes.length; index++) {
            fake.bytes[index] = (byte) (index + 1);
        }
        SramTransaction tx = new SramTransaction(new McuMemory(fake),
                (stage, region, result) -> {});
        SramTransaction.Region first = tx.backupStable("甲", 0x2004, 4);
        SramTransaction.Region second = tx.backupStable("乙", 0x2010, 4);
        tx.upload(first, new byte[] {9, 9, 9, 9});
        tx.upload(second, new byte[] {8, 8, 8, 8});
        fake.writeCountAtFirstPostRestoreRead = -1;
        fake.trackPostRestoreReads = true;

        tx.restoreAll();

        assertEquals(4, fake.writeCountAtFirstPostRestoreRead);
        assertTrue(tx.restored());
    }

    private static final class FakeMemory implements McuMemory.TextExchange {
        final int base;
        final byte[] bytes;
        int readCount;
        int writeCount;
        boolean mutateAfterFirstRead;
        int malformedReadsRemaining;
        boolean trackPostRestoreReads;
        int writeCountAtFirstPostRestoreRead;

        FakeMemory(int base, int length) {
            this.base = base;
            bytes = new byte[length];
        }

        @Override
        public byte[] exchange(String command, long timeoutMs) {
            String[] parts = command.split(" ");
            if (parts[0].equals("memread")) {
                if (trackPostRestoreReads
                        && timeoutMs == McuMemory.READ_TIMEOUT_MS
                        && writeCountAtFirstPostRestoreRead < 0) {
                    writeCountAtFirstPostRestoreRead = writeCount;
                }
                int address = (int) Long.decode(parts[1]).longValue();
                int length = Integer.decode(parts[2]);
                int offset = address - base;
                StringBuilder out = new StringBuilder(command).append("\r\n");
                for (int index = 0; index < length; index++) {
                    if (index != 0) {
                        out.append(' ');
                    }
                    out.append(String.format(Locale.US, "%02x",
                            bytes[offset + index] & 0xff));
                }
                out.append("\r\n");
                readCount++;
                if (malformedReadsRemaining > 0) {
                    malformedReadsRemaining--;
                    return (command + "\r\n")
                            .getBytes(StandardCharsets.US_ASCII);
                }
                byte[] result = out.toString().getBytes(StandardCharsets.US_ASCII);
                if (mutateAfterFirstRead && readCount == 1) {
                    bytes[offset] ^= 1;
                }
                return result;
            }
            int address = (int) Long.decode(parts[1]).longValue();
            int offset = address - base;
            long value = Long.decode(parts[2]);
            if (parts[0].equals("memwrite1")) {
                bytes[offset] = (byte) value;
            } else if (parts[0].equals("memwrite4")) {
                for (int index = 0; index < 4; index++) {
                    bytes[offset + index] = (byte) (value >>> (index * 8));
                }
            } else {
                throw new AssertionError(command);
            }
            writeCount++;
            return (command + "\r\nOK\r\n")
                    .getBytes(StandardCharsets.US_ASCII);
        }
    }
}
