# H13 Interphone Probe v1.14 offline build verification (2026-08-09)

## Scope

Offline build and unit-test verification only. No H13 or TYT device was
connected or operated. No ADB, Probe installation, UART, HPI, PTT, VoiceIn,
GPIO, DAC, Flash, reset, or RF action was performed. `job.md` was not changed.

## Environment and workaround

- JDK: local Temurin/OpenJDK `17.0.20+8` under `.tools/jdk17`.
- Gradle: local Gradle `8.9` under `.tools/gradle-8.9`.
- Android SDK: existing local `.tools/android-sdk`.
- Build isolation: `research/h13_interphone_probe/gradle/isolated-build-dir.init.gradle`.

The init script redirects the Gradle build root to a short ASCII path. This
avoids the JDK `zipfs` `AccessDeniedException` seen while closing `R.jar` under
the Chinese workspace path. It changes build-output placement only; it does
not change Probe source or device behavior.

## Results

```text
testDebugUnitTest
tests=80 failures=0 errors=0 skipped=0
BUILD SUCCESSFUL

assembleDebug
BUILD SUCCESSFUL
```

Preserved evidence:

```text
research/h13_radio/analysis/2026-08-09-probe-v114-offline-build/
  h13-interphone-probe-v1.14-debug.apk
    SHA-256 5C6220650CD3EF96A8713DF5FD119E9393622AEED1AA55AEBACDBAE5A85C8642
  TEST-net.elfradio.h13interphoneprobe.InterphoneProbeTest.xml
    SHA-256 AAB410D7F6174FBD53E4BA7DBD20F5F8CEF22EF8E3BC4D4D7A2C787026168D06
```

The isolation init script SHA-256 is
`70839BA3CD2588C7F4A7C1FFC9F335CC4B479B6FFD9FB61209043EBFD1C18F1F`.
The APK was built but not installed.

## Bounded conclusion

The current Probe v1.14 source compiles and its JVM unit suite passes in the
local offline toolchain. This establishes build reproducibility only. It does
not validate device transport, HPI behavior, audio injection, power control,
PTT, or RF transmission.
