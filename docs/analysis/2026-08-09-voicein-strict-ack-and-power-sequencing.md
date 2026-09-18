# VoiceIn strict ACK gates and RF power sequencing

Date: 2026-08-09 +10:00

## Scope

This is an offline review of the Probe source, its JUnit tests, the fixed
`Module_current_0.3.66.bin` image, and its fixed Thumb disassembly. No device,
UART, HPI, PTT, PCM/data, GPIO, or RF action was used. The installed H13 Probe
was not changed or started.

## Probe v1.09 control gates

The VoiceIn setup and teardown paths now require one complete status-zero ACK
before advancing to the next control field:

```text
setup:    3E 00 -> 1A 00 -> 18 00 -> 26 00 -> 78 00 -> first PCM block
teardown: 21 00 -> 18 00 -> STOPPED
```

A missing field, a different field, or a nonzero status transitions the model
to `FAILED`. The PCM frame is not serialized or written until all five setup
ACK gates pass. Unit tests also establish that these control ACKs are not
accepted as VoiceIn data credit.

The PA-off watchdog no longer treats termination of a long-lived `su` process
as the arm predicate. The root shell first validates both sysfs nodes, prints
`H13_PA_OFF_WATCHDOG_ARMED`, and only then begins its delayed force-low action.
The Probe advances on that marker instead of waiting for the root process to
exit.

Offline verification from the ASCII junction workspace produced 72 tests,
zero skipped, zero failures, and zero errors. Relevant SHA-256 values are:

```text
InterphoneProbe.java  98E9186D9F3A331A7B16C77CFAD898A2FE7B0EB9030D953698F48C21CF5D13EA
InterphoneProbeTest.java
                      1B81EF59DB2233920CE79A8CAF6475DA26B6EC677FEF578DF29D4B318D15800A
JUnit XML             1C921A6A4381CF1EFA376B33DD67B3FC2D80CD9B27AC26931E0842F01C75A68D
```

These tests prove fail-closed host sequencing only. They do not prove that the
H13 SCT application returns VoiceIn credit, consumes PCM, emits recognizable
audio, or transmits RF.

## Raw-bridge credit boundary

The MCU raw bridge controlled by `0x2000015C` transparently forwards Android
UART bytes to SCT HPI and SCT response bytes back to Android. It does not parse,
filter, synthesize, or bind a response as VoiceIn credit. Therefore a usable
credit can only be established from a complete SCT response frame captured on
the raw link.

The public `SCT3252T.StartReceiveStream()` code accepts a complete response
whose total size matches `header+1` or `header+2` and whose first body byte is
zero or one, without checking packet type. That is vendor reference behavior,
not proof of the H13 application contract.

The public call-start split is also now explicit:

```text
StartAnalogCommnd(..., mode=true)   -> START_CALL     body [20 02] (dPMR)
StartAnalogCommnd(..., mode=false)  -> DMR_START_CALL body [78]    (DMR)
```

`FormCustomRecordingInterface` passes the dPMR radio-button state directly as
that boolean. This supports `[78]` for the selected DMR VoiceIn path, but does
not yet prove that sending `[78]` after stock `DMOPTT=1` is necessary rather
than a duplicate call-start.

## Current-image GPIO and DAC helpers

The literal pools close the actual GPIO ports. In particular, the pool used by
`0x0800E6D8/0x0800E6E8` is `0x48000800`, GPIOC. Thus these helpers clear/set
PC13, not PB13.

```text
0x0800E6C0  GPIOE BRR  bit 6   -> PE6 low
0x0800E6CC  GPIOE BSRR bit 6   -> PE6 high
0x0800E6D8  GPIOC BRR  bit 13  -> PC13 low
0x0800E6E8  GPIOC BSRR bit 13  -> PC13 high
0x0800B9D0  GPIOF BSRR bit 3   -> PF3 high
0x0800B9DC  GPIOF BRR  bit 3   -> PF3 low
0x0800E098  DAC1 value wrapper
```

The composite helpers are exact:

```text
0x0801FF52(code): PE6 low -> PC13 high -> PF3 low -> DAC1=code
0x0801FF34():     DAC1=0 -> PF3 high -> PC13 low -> PE6 high
0x0801FF4A():     PE6 low only
```

`0x08014124(r0)` calls `0x0801FF4A` when `r0==0`; when `r0!=0` it reads the
runtime code at `0x20002DDC+10` and calls `0x0801FF52(code)`. The only recovered
`r0!=0` ownership is the guarded hidden immediate-calibration path through
`0x0802B108 -> 0x08013A44(1)`. Production digital `DMOPTT=1` instead reaches
`0x08013E24 -> 0x08013A44(0)` and initially lowers PE6 without forcing the
immediate PC13/PF3/DAC sequence.

## PC6 slot timing

`0x08011054` is the EXTI wrapper: it tests and clears the pending mask in EXTI
and calls `0x08010FC0(mask)`. For mask `0x40`, `0x08010FC0`:

1. increments the event counter at `0x20000134`;
2. requires the active gate at `0x20000138` to equal one;
3. rereads GPIOC pin 6;
4. on PC6 high, sets PC13 and writes `u16[0x20002DDC+10]` to DAC1;
5. on every accepted PC6 low, writes DAC1 zero and calls `0x080109F0`;
6. while completion byte `0x2000013C` is zero, decrements
   `u16[0x2000013A]`; at zero it sets completion to one and clears the active
   gate.

This separates the normal digital slot path from the hidden immediate
calibration helper. The established board interpretation remains PC6 as the
SCT `PIO3/RF_TIMING` input to the MCU and PC13 as RF-switch control. PE6 and PF3
participate in the surrounding rail/mode selection, but their exact external
loads remain a board-level inference unless traced physically.

## Bounded conclusion

The normal digital power path is now closed at instruction-order level:

```text
PowerLow/PowerHigh table -> runtime code -> PE6 low -> PC6 high
-> PC13 high -> DAC nonzero -> PC6 low -> DAC zero -> later global quiesce
-> PF3 high -> PC13 low -> PE6 high
```

This is not a milliwatt calibration and does not authorize RF. The next offline
question is whether stock `DMOPTT=1` has already issued the DMR `[78]` call-start
before the Probe enters raw bridge, because a second `[78]` may be redundant or
state-destructive.

## Correction: stock call-start ownership was already closed

The final paragraph above is superseded by the existing fixed-image evidence in
`H13.md` sections 18.114-18.116. Stock `DMOPTT=1` is already statically closed
to the SCT DMR call-start body `[78]`:

```text
DMOPTT=1 -> 0x08029F5C -> 0x08013E24 -> 0x08013A44
           -> 0x0801C264 -> DMR_CALL_START [78]
```

Therefore the current Probe sequence contains two confirmed call-starts: the
stock `[78]` inside `DMOPTT=1`, followed by the explicit raw-HPI `[78]` after
the VoiceIn route/process/work/sub-audio setup. Public VoiceIn code supports
the latter command's position in its own clean setup sequence, but no recovered
vendor sequence supports inserting that clean setup into an already-started
stock call. The double-start sequence must be treated as an implementation
defect candidate, not as an unresolved ownership question. No RF test is
justified until the MCU slot-gate setup and SCT VoiceIn call setup can be
separated or a single-start sequence is otherwise established statically.
