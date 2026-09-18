# FM VoiceIn single call-start and slot-gate ownership

Date: 2026-08-09 +10:00

## Scope

This is an offline review of the fixed `Module_current_0.3.66.bin` image, its
fixed Thumb disassembly, Probe v1.10 source, and JUnit output. No H13/TYT
device action, UART command, PTT, HPI payload, GPIO write, APK install/start,
or RF transmission was performed. `job.md` is unchanged.

## Analogue channel path

The current-image command path is:

```text
DMOSETANALOGCH
-> 0x0802A310
-> active profile byte1=0, byte2=1
-> 0x080203C8
-> 0x080133CC -> 0x08013460
-> 0x08020014 -> 0x08013EA0
```

At `0x0802A384..0x0802A38A`, the handler writes profile byte1 zero and byte2
one before calling `0x080203C8`. The analogue branch subsequently uses
`0x08013460` and `0x08013EA0`. The latter begins by clearing related state and
calling the stock full RF-off helper `0x0801FF34`, then establishes the
analogue work-mode/configuration path.

There is no direct or indirect edge in this path to `0x08019480`,
`0x0800E45C`, or `0x0801C264`. Therefore channel application does not arm the
PC6 slot gate, initialize its count, or send DMR call-start `[78]`.

## Slot-gate writer audit

Exact little-endian literal occurrences for `0x20000134..0x2000013C` were
enumerated in the immutable image and every code reference was inspected.
For `0x20000138`, the only recovered write of value one is:

```text
0x08019480 external-DMR configuration
...
0x0801966A  PE6 low
0x0801966E  PF3 low
0x08019672  r0 = 1
0x08019674  load 0x20000138
0x08019676  strb r0, [r1]
```

The other direct writers at `0x08007FF4`, `0x08017F78`, `0x08018198`,
`0x08019814`, and `0x0802174C` clear the gate during initialization or
teardown. `0x08010FC0` consumes the gate from the PC6 EXTI callback and clears
it when the configured low-edge countdown finishes. `0x0800E45C` clears the
completion/count pair; it does not arm the gate.

This makes the `count=3/16` and PC6 active-gate mechanism an external-DMR
timeslot data path. It is not a generic prerequisite that can be copied into
FM VoiceIn merely because both paths eventually control RF hardware.

## Stock PTT and single call-start ownership

The production text PTT path remains:

```text
DMOPTT=1
-> 0x08029F5C
-> 0x08013E24
-> 0x08013A44(0)
-> 0x08014124(0)
-> 0x0801C264
-> DMR_CALL_START [78]
```

`0x08014124` performs several SCT transactions and waits for their results.
Calling the whole function from a SysTick one-shot would therefore execute a
potentially blocking control transaction inside exception context. Probe
v1.10 does not do this. Its pre-bridge 56-byte stack-safe one-shot calls only
the short stock helper `0x0801FF4A` (PE6 low), with marker `PREP` and SHA-256:

```text
5de22b8c7cd97abea4c53a56217d11854a28dc947fbb52b85d1919eeded77834
```

The raw VoiceIn state machine is now the sole call-start owner:

```text
VOCODER_IO_SET(0x80)
PROCESS_MODE(0x80)
WORK_MODE(2)
SUB_AUDIO
DMR_CALL_START [78]
one 640-sample PCM block
STOP_CALL [21]
WORK_MODE_IDLE
```

`AT+DMOPTT=1` and `AT+DMOPTT=0` are absent from the v1.10 source. Thus the
known double-`[78]` defect candidate in v1.09 is removed without importing the
unrelated external-DMR slot gate.

After bridge exit, the second 56-byte stack-safe one-shot calls stock full-off
helper `0x0801FF34`, with marker `OFF!` and SHA-256:

```text
44dc21a0144104f3b9791d34f0c7893b62731f28f926622a5927891eafd40043
```

## Timing and recovery review

The bounded worst-case successful transaction is:

```text
5 setup ACKs * 150 ms                 750 ms
1290-byte VoiceIn frame @230400 8N1   56 ms
post-block credit window              250 ms
2 teardown ACKs * 150 ms              300 ms
total                                1356 ms
```

This fits the 2000 ms automatic raw-bridge window with 644 ms nominal margin.
It does not prove SCT processing latency, credit, PCM consumption, modulation,
audio intelligibility, or antenna power.

The normal path confirms bridge flag/vector/counter/marker before executing
the OFF one-shot. The `finally` path attempts the same OFF helper whenever
`analogRfPrepared` remains true. One residual implementation risk remains:
after waiting out an exceptional raw-bridge window, `finally` marks
`bridgeMayBeActive=false` from elapsed time rather than independently proving
the timeout marker/vector. A command-mode probe itself could be unsafe if the
bridge did not exit, so this cannot be repaired by blindly emitting AT text.
The independent GPIO force-low guard and an explicit reboot recommendation
remain necessary rollback layers for any future authorized RF test.

## Offline verification

From the ASCII junction workspace, `testDebugUnitTest --no-daemon --offline`
completed successfully:

```text
74 tests, 0 skipped, 0 failures, 0 errors
```

Artifact hashes:

```text
InterphoneProbe.java
5154BB844CEC03BCD8A4313CA6D3B1CD1929361B65A75DD7CBD0D1FD72A5F9FB

InterphoneProbeTest.java
AC517FD6D5B3FCE71DE896807179922121546E1CA2E82363EE8D9B853CEFF35C

JUnit XML
DDB2AF1C785A253D1360BA08A7ED4C89F2D2E8657944A9C0CA4BDB24F2F195CC
```

These are offline host-side gates only. FM audio injection remains unproven
until a future separately authorized low-power device/RF test receives a
valid SCT credit and an independently observed intelligible analogue signal.
