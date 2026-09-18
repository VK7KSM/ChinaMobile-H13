package net.elfradio.h13dmrtx;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class EvidenceStoreTest {
    @Rule
    public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void sessionNeverOverwritesAndFreezesCompleteManifest()
            throws Exception {
        EvidenceStore store = new EvidenceStore(temporary.getRoot(),
                "session_001");
        store.saveEvent("pre", "serial", new byte[] {1, 2, 3});
        store.saveEvent("pre", "serial", new byte[] {4});
        store.writeAtomicResult("结果=通过\n");
        File manifest = store.finishManifest();
        assertTrue(manifest.isFile() && manifest.length() > 0);
        assertTrue(!new File(manifest.getParentFile(),
                "artifacts_sha256.tmp").exists());
        String text = new String(Files.readAllBytes(manifest.toPath()),
                StandardCharsets.UTF_8);
        assertTrue(text.contains("0001_pre_serial.bin\t3\t"));
        assertTrue(text.contains("0002_pre_serial.bin\t1\t"));
        assertTrue(text.contains("atomic_result.txt"));
        assertTrue(text.contains("session.log"));
        assertThrows(IllegalStateException.class,
                () -> store.saveEvent("post", "late", new byte[0]));
    }

    @Test
    public void duplicateSessionDirectoryIsRejected() throws Exception {
        EvidenceStore first = new EvidenceStore(temporary.getRoot(), "same");
        assertThrows(Exception.class,
                () -> new EvidenceStore(temporary.getRoot(), "same"));
        first.writeAtomicResult("结果=失败\n");
        first.finishManifest();
        assertEquals(1, temporary.getRoot().listFiles().length);
    }

    @Test
    public void fixedCredentialCannotBeOverwrittenAndIsManifested()
            throws Exception {
        EvidenceStore store = new EvidenceStore(temporary.getRoot(),
                "credential");
        store.saveCredential("rf_actual_start", "时刻=123\n");
        assertThrows(Exception.class,
                () -> store.saveCredential("rf_actual_start", "时刻=456\n"));
        store.writeAtomicResult("结果=通过\n");
        String manifest = new String(Files.readAllBytes(
                store.finishManifest().toPath()), StandardCharsets.UTF_8);
        assertTrue(manifest.contains("rf_actual_start.txt"));
    }

    @Test
    public void dynamicCounterEvidenceSanitizesEveryStageAndStaysUnique()
            throws Exception {
        EvidenceStore store = new EvidenceStore(temporary.getRoot(),
                "counter_stage");
        McuMemory.ReadResult read = new McuMemory.ReadResult(
                "AT+DMOREADMEM", 1, new byte[] {1, 2},
                new byte[] {3, 4, 5, 6});
        DmrTxController.saveRfEdgeCounterEvidence(store, "写入前第1轮", read);
        DmrTxController.saveRfEdgeCounterEvidence(store, "写入前第2轮", read);
        DmrTxController.saveRfEdgeCounterEvidence(store, "恢复后", read);
        DmrTxController.saveRfEdgeCounterEvidence(store, "任意阶段", read);
        assertThrows(IllegalArgumentException.class,
                () -> store.saveEvent("", "counter", new byte[0]));
        store.writeAtomicResult("结果=通过\n");
        store.finishManifest();

        File[] files = new File(temporary.getRoot(), "counter_stage")
                .listFiles();
        assertTrue(files != null);
        String first = null;
        int counterArtifacts = 0;
        for (File file : files) {
            assertTrue(file.getName().matches("[A-Za-z0-9_.-]+"));
            if (file.getName().contains("rf_edge_counter")) {
                counterArtifacts++;
                if (first == null) {
                    first = file.getName();
                } else {
                    assertNotEquals(first, file.getName());
                }
            }
        }
        assertEquals(12, counterArtifacts);
    }

    @Test
    public void failedMemreadAttemptPersistsRawParsedAndMetadata()
            throws Exception {
        EvidenceStore store = new EvidenceStore(temporary.getRoot(),
                "memread_attempt");
        McuMemory.ReadAttempt attempt = new McuMemory.ReadAttempt(
                "memread 0x20003064 0x4", 0x20003064, 4, 2,
                123456789L, new byte[] {0x41, 0x42}, new byte[0],
                "length_mismatch");

        store.saveReadAttempt(attempt);

        File directory = new File(temporary.getRoot(), "memread_attempt");
        File raw = new File(directory,
                "0001_memread_attempt_0x20003064_try_2_raw.bin");
        File parsed = new File(directory,
                "0002_memread_attempt_0x20003064_try_2_parsed.bin");
        File metadata = new File(directory,
                "0003_memread_attempt_0x20003064_try_2_meta.bin");
        assertTrue(raw.isFile());
        assertTrue(parsed.isFile());
        assertTrue(metadata.isFile());
        assertEquals(2, raw.length());
        assertEquals(0, parsed.length());
        String text = new String(Files.readAllBytes(metadata.toPath()),
                StandardCharsets.UTF_8);
        assertTrue(text.contains("attempt=2"));
        assertTrue(text.contains("observed_at_epoch_ms=123456789"));
        assertTrue(text.contains("failure=length_mismatch"));
    }
}
