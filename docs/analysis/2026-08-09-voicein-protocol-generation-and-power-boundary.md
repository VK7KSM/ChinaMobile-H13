# VoiceIn protocol generation, teardown, and RF power boundary

## Scope

This is an offline review of the fixed H13 MCU image, the public SCT3258
packet interface and engineering tool, the H11/H12 product sheets, and Probe
v1.13 source/tests. No H13 or TYT device was connected. No APK was installed or
started, no UART/HPI/GPIO/PTT command was sent, and no RF transmission occurred.
`job.md` is unchanged.

Fixed MCU evidence:

```text
research/h13_radio/Module_current_0.3.66.bin
SHA-256 5D41BE2A734838ABFD4EF13C8BCDCFF424B634CD69B3202AEC1E16E0261A484A
```

## Observation: `VOCODER_IO_SET(0x80)` is a later vendor extension

The 2015 `SCT3258_Packet_Interface.pdf`, page 52, states that
`VOCODER_IO_SET` is currently used only in dPMR mode. Its bit table marks bit 7
as reserved. It documents bit 6 `MOD_IN`, bits 5:4 `ENC_OUT`, bit 3 `DEC_IN`,
and bit 2 `DEMOD_OUT`; it does not document a bit-7 Voice File source.

The later public engineering GUI does define bit 7 as Voice IN, with
`0 = Codec` and `1 = Voice File`, and its analogue VoiceIn transaction sends
`VOCODER_IO_SET(0x80)`. This proves a later vendor-tool extension and its host
transaction. It does not prove that the H13 active SCT application
`V2.01.07I3` implements that extension in its DMR/analogue mode.

Probe v1.13 therefore records only these two compatibility states:

```text
VENDOR_EXTENSION_UNCONFIRMED
CONTROL_ACKED_DATA_PLANE_UNCONFIRMED
```

Only the exact status-zero response to the exact `0x3E 0x80` request advances
the state. Even that state does not claim input credit, PCM consumption,
recognizable FM modulation, or RF output.

## Observation: the public teardown variants are not interchangeable

`FormCustomRecordingInterface.button_endcall_Click()` calls `STOP_CALL()` for
the analogue VoiceIn branch. The public base implementation constructs packet
type 0 with body `[0x21]`. The separate `DMR_EndCommand()` constructs packet
type 3 with body `[0x21]` and belongs to the DMR T1 interface; it must not
replace the analogue VoiceIn stop.

The public scripts show more than one cleanup policy:

```text
AnalogEnd.txt: CALL_END [21] -> CARRIER_LOST [19,00] -> WORK_MODE_IDLE
TXEnd.txt:     CALL_END [21] -> WORK_MODE_IDLE
GUI button:   STOP_CALL [21]
```

The H13 stock `DMOPTT=0` path is also different. Its delayed descriptor
consumer reaches `0x08013A1C`, which calls `0x08013EA0`. That function first
calls full hardware-off helper `0x0801FF34` and later restores
`CARRIER_READY [0x19,1/2]`. The exact hardware-off order is:

```text
DAC1=0 -> PF3 high -> PC13 low -> PE6 high
```

No fixed-image builder that sends SCT body `[0x21]` has been recovered. This
does not negate the public GUI contract, but it means the stock MCU release
path cannot be used to prove that H13 itself sends `STOP_CALL` or an extra
`CARRIER_LOST` frame.

The bounded implementation decision is to keep Probe teardown as
`STOP_CALL [21] -> WORK_MODE_IDLE`, followed by the independent stock full-off
helper after bridge exit. Do not add `CARRIER_LOST` merely because it appears
in `AnalogEnd.txt`; the GUI, `TXEnd.txt`, and H13 stock release path demonstrate
different valid owners and cleanup policies.

## Observation: H13 low power is a watt-class setting

The product owner identifies H11/H12 as the same hardware as H13. The H11
product sheet specifies UHF high/low as 5 W/1 W. The H12 product sheet specifies
4 W/1 W. The H13 read-only v1.06 calibration snapshot at 433.550 MHz found:

```text
PowerLow code  2030
PowerHigh code 3340
runtime code   2030
```

The product sheets and calibration snapshot support the inference that H13
`low`, runtime code 2030, is the manufacturer's approximately 1 W class, not a
milliwatt test mode. They do not calibrate code 2030 to an exact antenna-port
power on this unit.

Consequently, no future RF plan may describe `low` as `<=50 mW` or inherently
safe. A wattmeter plus suitable load/attenuation is required before attaching
an exact power value. Each future transmission still requires a new explicit
authorization, a bounded duration, and the documented force-off/recovery path.

## Probe v1.13 offline gate

Version `1.13-fm-voice-in-protocol-gate` adds the compatibility state above and
two tests: a wrong/nonzero route response leaves the vendor extension
unconfirmed and fails closed; an exact route ACK advances only to
`CONTROL_ACKED_DATA_PLANE_UNCONFIRMED` and recovery resets it.

```text
79 tests, 0 skipped, 0 failures, 0 errors

InterphoneProbe.java
620D04889BF0F036DFFD976B1E6B4C83FCD9A849AA3639A12737D062C78C4BD0

InterphoneProbeTest.java
4AB9C6CCC8653C106D916B258C173432ACD7CB8DC3ADA59097C36C37743C26D7

JUnit XML
FD29154292F6DC56A6E843C4A93702797E9EF321A7DCB96B7DEE4C7A42544A6C

H13_Interphone_Probe_v1.13_FmVoiceInProtocolGate.apk, 206185 bytes
AF5E0C993D44C5D35EFFD4B79F100D07F90F41AD4510F07FE01A54E5A125E373
```

The APK reports versionCode 113/versionName
`1.13-fm-voice-in-protocol-gate`; APK Signature Scheme v1/v2 verification
passes. It was not installed or started.

## Observation: exact public VoiceIn block and pacing contract

The public tool has no SCT application capability negotiation for VoiceIn.
`Form_Vocoder_In_Out` receives only the current bitfield value and always
exposes bit 7. `FormMain` selects a product family manually; it does not query
an application version before opening `FormCustomRecordingInterface` or before
enabling the Voice File control. `SCT3252T.VOCODER_IO_SET()` sends the request,
discards the returned frame, and returns `true` unconditionally.

The actual host-side VoiceIn packet flow is nevertheless precise:

```text
input file: 8 kHz signed 16-bit PCM in host byte order
ChangeByteForAMBE: swap every adjacent byte pair
TransportChanelFileDataBase(1280, 0, data):
    body [00, 02, 80, 1280 swapped PCM bytes]
CreatCmd(3): packet type 3
wire length with even padding: 1290 bytes
```

The `0x0280` count is 640 16-bit samples, hence 80 ms at 8 kHz. The GUI sends
the first block immediately after call setup. `StartReceiveStream()` sends each
later block only after a complete short response whose first body byte is 0 or
1 and whose body length is one or two bytes. It does not constrain packet type
or require a VoiceIn-specific field. Once the queue is empty, the analogue path
calls `STOP_CALL()`.

This is a vendor-tool pacing contract, not proof that H13 will produce such a
short credit. It also means a control ACK, an arbitrary short response, and a
session-specific data credit must remain distinct in H13 evidence. At 57,600
8N1, one padded block takes about 224 ms on the UART and cannot sustain an
80 ms source cadence. At 230,400 it takes about 56 ms and is line-rate viable.

## Observation: the nearest public application remains encoded

There is no exact `V2.01.07I3` application image in the local materials. The
nearest public SCT3258 family sample is `V2.01.07BF`, and the local tooling has
losslessly reconstructed its `000100_user_application.dat` container. That
container still stores all executable application sections as vendor
`coded1/2/3` payloads. The existing five-version analysis has not recovered the
coding algorithm, so neither the `0x3E` bit-7 selector nor the 1283-byte
packet-type-3 consumer can currently be disassembled from it.

The updater/tool bundle therefore strengthens chronology only: bit-7 VoiceIn
exists in a later vendor engineering environment near the `2.01.07` family.
It does not close the H13 `I3` implementation gap.

## Probe v1.14 offline framing gate

Review of v1.13 found that codec-write ACKs were parsed on declared HPI frame
boundaries, while ordinary VoiceIn control ACKs still used a raw byte-substring
search. An ACK-shaped byte sequence embedded in an unrelated data payload could
therefore have advanced the state machine despite the documented exact-ACK
claim.

Version `1.14-fm-voice-in-framing-gate` now uses the same strict parser for
both classes: it walks only complete declared frames, accepts only the exact
current ACK frame, rejects truncation, and permits only zero even-byte padding.
Tests cover embedded fake ACKs, fake frames after truncation, valid zero padding,
and nonzero padding rejection.

```text
80 tests, 0 skipped, 0 failures, 0 errors

InterphoneProbe.java
A079FD8BDCFDAA5F6ABD8F4E639E09BA762581914D67164FC5DD0242213B833A

InterphoneProbeTest.java
D6F57D27DE182607DB45666A79E2029CD9601E846767A056068BA4D722B2D835

JUnit XML
C99087E1851F1FC1B37D1774195BDD8B93943801621A1F8E9D6C4388C6AA4726

H13_Interphone_Probe_v1.14_FmVoiceInFramingGate.apk, 206196 bytes
5C6220650CD3EF96A8713DF5FD119E9393622AEED1AA55AEBACDBAE5A85C8642
```

The APK reports versionCode 114/versionName
`1.14-fm-voice-in-framing-gate`; APK Signature Scheme v1/v2 verification
passes. It was not installed or started.

## Open questions and next static work

1. The local materials do not expose the active SCT application's internal
   bit-7 source selector or the point where Voice File PCM would take ownership
   of DAC samples. This remains the primary compatibility gap.
2. The closest public `V2.01.07BF` application remains coded. Recovering the
   coding algorithm or obtaining an exact `I3` image is required for an
   internal bit-7 selector trace.
3. Keep `STOP_CALL -> IDLE -> stock full-off` until new evidence proves that
   H13 requires an additional `CARRIER_LOST`; do not add it speculatively.
4. Keep all RF tests blocked until the owner grants a new per-test permission.
