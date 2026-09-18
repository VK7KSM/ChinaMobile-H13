package net.elfradio.h13dmrtx;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

final class SramTransaction {
    private static final long OPTIONAL_PRE_RESTORE_READ_MS = 750;

    interface EvidenceSink {
        void save(String stage, Region region, McuMemory.ReadResult result)
                throws Exception;
    }

    private final McuMemory memory;
    private final EvidenceSink evidence;
    private final List<Region> regions = new ArrayList<>();
    private boolean mutated;
    private boolean restored;

    SramTransaction(McuMemory memory, EvidenceSink evidence) {
        if (memory == null || evidence == null) {
            throw new IllegalArgumentException("SRAM事务依赖不完整");
        }
        this.memory = memory;
        this.evidence = evidence;
    }

    Region backupStable(String name, int address, int length)
            throws Exception {
        if (mutated) {
            throw new IllegalStateException("写入开始后禁止新增原像");
        }
        Region candidate = new Region(name, address, length);
        for (Region existing : regions) {
            if (candidate.overlaps(existing)) {
                throw new IllegalArgumentException("SRAM原像范围重叠："
                        + name + " / " + existing.name);
            }
        }
        McuMemory.ReadResult first = memory.read(address, length);
        evidence.save("写入前第1轮", candidate, first);
        McuMemory.ReadResult second = memory.read(address, length);
        evidence.save("写入前第2轮", candidate, second);
        if (!Arrays.equals(first.parsed, second.parsed)) {
            throw new IOException("动态SRAM原像两轮不一致：" + name);
        }
        candidate.original = first.parsed.clone();
        regions.add(candidate);
        return candidate;
    }

    void upload(Region region, byte[] value) throws Exception {
        requireKnown(region);
        if (value == null || value.length != region.length) {
            throw new IllegalArgumentException("上传长度不匹配：" + region.name);
        }
        mutated = true;
        memory.writeExact(region.address, value);
        McuMemory.ReadResult after = memory.read(region.address, region.length);
        evidence.save("写入后", region, after);
        if (!Arrays.equals(after.parsed, value)) {
            throw new IOException("SRAM上传回读不一致：" + region.name);
        }
        region.uploaded = value.clone();
    }

    void restoreAll() throws Exception {
        if (restored) {
            return;
        }
        if (!mutated) {
            restored = true;
            return;
        }
        List<Region> reverse = new ArrayList<>(regions);
        Collections.sort(reverse, new Comparator<Region>() {
            @Override
            public int compare(Region left, Region right) {
                return Integer.compareUnsigned(right.address, left.address);
            }
        });
        IOException firstWriteFailure = null;
        IOException firstVerifyFailure = null;
        IOException firstEvidenceFailure = null;
        boolean optionalCaptureHealthy = true;
        for (Region region : reverse) {
            if (optionalCaptureHealthy) {
                try {
                    McuMemory.ReadResult before = memory.readOnce(region.address,
                            region.length, OPTIONAL_PRE_RESTORE_READ_MS);
                    evidence.save("恢复前", region, before);
                } catch (Exception error) {
                    optionalCaptureHealthy = false;
                    firstEvidenceFailure = new IOException(
                            "SRAM恢复前态采集失败，已停止可选快照并继续恢复："
                            + region.name, error);
                }
            }
            try {
                memory.writeExact(region.address, region.original);
            } catch (Exception error) {
                if (firstWriteFailure == null) {
                    firstWriteFailure = error instanceof IOException
                            ? (IOException) error
                            : new IOException(error);
                }
            }
        }
        // 所有原像写回都已尝试后再做严格回读，避免某一读取超时拖住后续写回。
        for (Region region : reverse) {
            try {
                McuMemory.ReadResult after = memory.read(region.address,
                        region.length);
                evidence.save("恢复后", region, after);
                if (!Arrays.equals(after.parsed, region.original)) {
                    throw new IOException("SRAM恢复回读不一致：" + region.name);
                }
            } catch (Exception error) {
                if (firstVerifyFailure == null) {
                    firstVerifyFailure = error instanceof IOException
                            ? (IOException) error
                            : new IOException(error);
                }
            }
        }
        restored = firstWriteFailure == null && firstVerifyFailure == null;
        IOException firstRestoreFailure = firstWriteFailure != null
                ? firstWriteFailure : firstVerifyFailure;
        if (firstRestoreFailure != null) {
            if (firstEvidenceFailure != null) {
                firstRestoreFailure.addSuppressed(firstEvidenceFailure);
            }
            throw firstRestoreFailure;
        }
        if (firstEvidenceFailure != null) {
            throw firstEvidenceFailure;
        }
    }

    boolean restored() {
        return restored;
    }

    boolean mutated() {
        return mutated;
    }

    boolean restoredOrUntouched() {
        return !mutated || restored;
    }

    List<Region> regions() {
        return Collections.unmodifiableList(regions);
    }

    private void requireKnown(Region region) {
        if (region == null || !regions.contains(region)
                || region.original == null || restored) {
            throw new IllegalStateException("SRAM区域不在活动事务中");
        }
    }

    static final class Region {
        final String name;
        final int address;
        final int length;
        byte[] original;
        byte[] uploaded;

        Region(String name, int address, int length) {
            if (name == null || name.length() == 0 || length <= 0) {
                throw new IllegalArgumentException("SRAM区域参数无效");
            }
            this.name = name;
            this.address = address;
            this.length = length;
        }

        boolean overlaps(Region other) {
            long leftEnd = (address & 0xffffffffL) + length;
            long rightEnd = (other.address & 0xffffffffL) + other.length;
            return (address & 0xffffffffL) < rightEnd
                    && (other.address & 0xffffffffL) < leftEnd;
        }
    }
}
