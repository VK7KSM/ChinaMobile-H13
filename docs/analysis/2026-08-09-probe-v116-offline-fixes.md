# Probe v1.16 offline result-detection and reporting fixes

Date: 2026-08-09 09:53 +10:00

## Scope

Offline-only follow-up to the successful v1.15 FM VoiceIn one-block run. No APK was installed and no Probe, UART, HPI, GPIO, PTT, RF, reset, Flash, or TYT action occurred.

## Observed defects

1. `h13_probe_session.ps1` polled only for `Result: PASS/FAIL/INVALID`. Android truncated the v1.15 long report line before its final `Result:` field, while the separate short line `PHASE DONE_PASS` remained intact. The wrapper therefore waited 90 seconds and reported a false timeout after the device had already completed successfully.
2. The Probe sent a frame whose length is mechanically fixed at 1290 bytes, but the runtime report did not print the wire length, PCM byte count, or state-machine serialized-block count before the long report was emitted.

## Changes

- The wrapper now treats `PHASE DONE_PASS`, `PHASE DONE_FAIL`, and `PHASE DONE_INVALID` as authoritative completion markers in addition to the legacy `Result:` fields.
- Immediately after `output.write()` and `flush()`, the Probe reports `VoiceIn wire bytes`, `VoiceIn PCM bytes`, and `VoiceIn serialized blocks`.
- APK metadata advanced to versionCode `116`, versionName `1.16-fm-voice-in-reporting`. The v1.15 device-test APK and capture remain immutable.

## Verification

- The new matcher evaluated the immutable v1.15 `logcat_probe.txt` as `pass=True fail=False`.
- PowerShell parser check for `h13_probe_session.ps1`: PASS.
- An ASCII temporary `subst` path exposed the existing offline Gradle cache without copying or changing dependencies. The drive mapping was removed in `finally`.
- `testDebugUnitTest`: 80 tests, 0 failures, 0 errors, 0 skipped.
- `assembleDebug`: BUILD SUCCESSFUL.
- APK: `research/h13_interphone_probe/dist/H13_Interphone_Probe_v1.16_FmVoiceInReporting.apk`, 206383 bytes, SHA-256 `A61953B8D0EA64B63D6EF16C011CED380AAB88C1FCE60BCCD17199C1859731C4`.
- JUnit XML: `research/h13_radio/analysis/2026-08-09-probe-v116-offline-fixes/TEST-net.elfradio.h13interphoneprobe.InterphoneProbeTest.xml`, SHA-256 `7BCC22D4C186069A677AB78D22874B915DC3B4A39653B59D955B8F1383BCF5D8`.
- Wrapper SHA-256: `72843122E089F131F9F5B3991BB924E3203185E5039DC807E37BA9160683F191`.
- Probe Java SHA-256: `D9BBAF9DE05E20A466E57559BFADC84A6D99B4597EF064CE57D12DAFCBE48239`.
- `app/build.gradle` SHA-256: `5AC85369144A647ADEBF88D7C573D577A3C42D157005E1D34E4940079676F80D`.

## Boundary

This verification proves the host completion matcher and offline report construction compile and pass their existing protocol/framing tests. It does not add another device or RF result, and v1.16 has not been installed.
