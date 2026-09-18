package net.elfradio.h13interphoneprobe;

final class ProbeResult {
    final boolean success;
    final String report;

    ProbeResult(boolean success, String report) {
        this.success = success;
        this.report = report;
    }
}
