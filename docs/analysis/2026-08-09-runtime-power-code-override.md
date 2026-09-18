# H13 runtime TX power-code path and v1.17 offline override

Date: 2026-08-09 10:02 +10:00

## Scope

Offline analysis of the immutable MCU `0.3.66` image/disassembly and implementation of a not-installed Probe mode. No device, UART, HPI, GPIO, PTT, RF, reset, Flash, or TYT operation occurred during this work.

Fixed MCU image:

```text
research/h13_radio/Module_current_0.3.66.bin
SHA-256 5D41BE2A734838ABFD4EF13C8BCDCFF424B634CD69B3202AEC1E16E0261A484A
```

## Static power path

`0x0801FF52(code)` performs only this sequence:

```text
PE6 low -> PC13 high -> PF3 low -> 0x0800E098(code)
```

There is no comparison, table lookup, arithmetic conversion, or software clamp in this helper. `0x0800E098` moves its input unchanged to `r3` and calls `0x08010F24(DAC1, channel=0, alignment=0, value=r3)`. The latter stores `r3` directly to the DAC channel-1 12-bit-right data-register offset. The peripheral defines the effective 12-bit range; the recovered firmware helper itself does not validate it.

The normal digital slot path at `0x08010FC0` uses the same setter. On accepted PC6/RF_TIMING high, it loads the 16-bit runtime code from `0x20002DE6` and calls `0x0800E098`; on PC6 low it writes zero. Therefore the normal slot-gated path and the immediate helper share the same unscaled runtime code.

The H13 snapshot established factory values at 433.550 MHz:

```text
PowerLow  = 2030
PowerHigh = 3340
runtime   = 2030
```

The higher factory tier using the higher DAC code supports a monotonic direction inference, but does not calibrate any intermediate code to watts. H11/H12 data place factory low near the 1 W class, not milliwatts.

## v1.17 design

New mode: `fm_voice_in_one_block_power_230400`.

- It requires an explicit integer `power_code`; missing or outside `0..2030` is rejected before serial open. The host wrapper enforces the same range before any ADB call.
- After the known analogue channel ACK, it requires the live factory-low runtime code to equal 2030.
- Before any RF preparation, it writes the requested little-endian two-byte runtime code at `0x20002DE6`, reads both bytes back, and requires an exact match.
- It then reuses the unchanged one-block VoiceIn sequence proven by v1.15.
- After STOP/IDLE and exact stock RF-off, it restores the original two bytes and requires exact readback before continuing normal baud/channel/SRAM recovery.
- The exception path also restores the original two bytes after bridge exit/RF-off and recovery to the text face. A restore mismatch keeps the result failed and adds the reboot/recovery warning.
- Existing `fm_voice_in_one_block_230400` callers pass no override and retain their previous behavior.

This mode controls a DAC code. It does not claim an exact output power or make a lower code intrinsically safe. A load/attenuator and wattmeter remain necessary for calibration.

## Offline verification

- versionCode `117`, versionName `1.17-fm-voice-in-power-override`.
- Missing `PowerCode` wrapper invocation: exit 1 before ADB, with `PowerCode 0..2030 is required`.
- PowerShell parser: PASS.
- JUnit: 82 tests, 0 failures, 0 errors, 0 skipped.
- `assembleDebug`: BUILD SUCCESSFUL.
- APK: `research/h13_interphone_probe/dist/H13_Interphone_Probe_v1.17_FmVoiceInPowerOverride.apk`, 209270 bytes, SHA-256 `AD7579956984D28BFF417E0629E6E438EF7DA3609A86EE116269C7BA5C879B1A`.
- JUnit XML SHA-256: `E87243A2B561A3174CC9B17C3EDBAAEA94FB7621B9835A56C10617EB86038E3D`.
- `InterphoneProbe.java` SHA-256: `8688D350582664AA6D1F0FAB0FC633E540107B7EE85769EF613403BAADCEFD58`.
- `MainActivity.java` SHA-256: `7957BA2A7A5336F9438433E407D778037FE7BE9C8BB085906811D94B0248450C`.
- `InterphoneProbeTest.java` SHA-256: `8046B2781AAAE960713019A3903B09A17E52825BAF6B1D11F9685151297C92F3`.
- `h13_probe_session.ps1` SHA-256: `A661903DACEF3603EF9B49E73806DF9D3F8C12F8955A1B8BDE3DA37BDD404ADD`.

## Next gate

Do not install or run v1.17 merely to verify software construction. A later power-calibration milestone must pre-register one code, one short transmission, measurement equipment/load, expected readback, and exact restoration. It requires a fresh explicit RF authorization.
