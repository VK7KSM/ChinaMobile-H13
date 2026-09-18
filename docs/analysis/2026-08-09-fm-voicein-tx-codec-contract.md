# FM VoiceIn TX codec contract and Probe v1.12

Date: 2026-08-09 Australia/Sydney

## Scope

This was an offline review of the immutable H13 MCU image and Thumb
disassembly, the public SCT3258 tools, and Probe source/tests. No H13 or TYT
device was connected. No APK was installed or started, and no UART, HPI, PTT,
PCM/data36, GPIO, RF, Flash, reset, or service command was sent. `job.md` was
not changed.

## Fixed-image observations

`0x08014124(r0)` performs the following operations in order:

1. send CARRIER_LOST packet type 5, body `[19,00]`;
2. load the active profile index from `0x200001A9`;
3. calculate `0x2000180C + 188 * index`;
4. read the analogue mic preset at absolute profile offset `+176`;
5. index the three-byte runtime gain table at `0x20002E25`;
6. call `0x0800FBCE(gain)`;
7. send `WORK_MODE_IDLE [18,00,00,00]` and clear lifecycle state;
8. send profile-derived `SUB_AUDIO [26,...]`;
9. send `WORK_MODE_TX [18,02,00,00]`, set lifecycle state 2, and finally
   select the physical PE6/power helper according to `r0`.

The complete `0x0800FBCE` body is five calls to `0x0800FC24(page,reg,value)`:

```text
page 1, reg 0x10 = 0x40
page 1, reg 0x3B = 0x11
page 0, reg 0x56 = 0xF3
page 0, reg 0x57 = 0xBA
page 0, reg 0x58 = runtime gain
```

`0x0800FC24` constructs the six-byte packet-type-`0x40` body
`[00,page,reg,value,00,00]` and performs a synchronous SCT transaction. This
is why neither `0x08014124` nor `0x0800FBCE` may be called from the SysTick
one-shot: both can block on module response handling.

The literal pool at `0x080142F0..0x080142FB` independently decodes to
`0x200001A9`, `0x2000180C`, and `0x20002E25`. The profile preset load at
`0x08014188..0x08014190` is exactly `base + 188*index + 164 + 12`.

## Public protocol corroboration

The public SCT3258 command scripts repeatedly use this request and response
contract:

```text
request:  84 A9 61 00 06 40 00 PAGE REG VALUE 00 00
success:  84 A9 61 00 02 40 17 00
```

Examples are present in
`public_sct3258/msi_unpacked/SCT_File/SCT_MAIN/SCT_NXDN/Mod_Cali.txt` and the
corresponding DCR, DMR_T2, DPMR, WM8758, and VoiceCall scripts. The script
evidence corroborates framing and success status; it does not prove the H13
analogue output, audio intelligibility, or antenna power.

## Probe v1.12 implementation

Probe was advanced to versionCode 112 and versionName
`1.12-fm-voice-in-tx-codec`.

The analogue mode now reads, before changing baud or entering raw bridge:

```text
active profile index -> profile +176 mic preset -> 0x20002E25[preset]
```

It rejects a profile index that would make the `+176` read cross the runtime
gain-table boundary, rejects preset values outside `0..2`, and requires an
exact three-byte gain snapshot. These are read-only gates; the implementation
does not hard-code the currently expected fallback table `0,10,20`.

Inside the normal raw-bridge thread, the VoiceIn state machine is now:

```text
five ordered codec writes, each with exact 40/17/00 ACK
-> VOCODER_IO_SET(0x80)
-> PROCESS_MODE(0x80)
-> WORK_MODE(2,0,0,0)
-> SUB_AUDIO
-> DMR_CALL_START [78]
-> one 1280-byte PCM block
-> STOP_CALL [21]
-> WORK_MODE_IDLE
```

The codec ACK parser walks declared HPI frame boundaries, permits only the
single zero alignment byte after an odd wire frame, and rejects truncation,
junk, wrong packet type/status, and an ACK-shaped byte string embedded in
another payload. The state machine also checks the exact request for the
current phase, so an ACK cannot skip or reorder a codec register write.

Adding five 150 ms ACK gates changes the bounded successful worst case from
1356 ms to 2106 ms. The raw bridge target is therefore 3000 ms and the
independent PA/audio-switch force-low watchdog is 4000 ms.

## Offline verification and artifacts

`testDebugUnitTest --no-daemon --offline` completed with 77 tests, zero
skipped, failures, or errors. `assembleDebug --no-daemon --offline` completed
successfully. `aapt2` read back versionCode 112/versionName v1.12, and
`apksigner` verified both APK Signature Scheme v1 and v2.

```text
InterphoneProbe.java
E86323D8402F051B7E72F0EA6982E0B731D1C7C59934AD265A2081F5C33FD8C7

InterphoneProbeTest.java
28C6FB2DAFADBB76237B8B5A408108D615C07FD7CCC63170282A5016DC6E7E48

app/build.gradle
2DE684D333E3BC5E04A79CEBA117809AA324542C46F4F492D3252BB0203CFAF0

JUnit XML
CBA288C43CA7F44D1C61D9E66B014B4D0D3458DB0C6EDDF39F929E116EBD4EBA

H13_Interphone_Probe_v1.12_FmVoiceInTxCodec.apk (205814 bytes)
1F654D467F81C1B3DD01882797199EED1DD60933A68276819347A5858454F65B
```

## Bounded conclusion and next static work

Observation: v1.10/v1.11 omitted a confirmed stock TX codec setup that occurs
before work-mode TX. v1.12 now reproduces that setup with ordered response
gates and the live gain value.

Supported inference: Page 1 register `0x10=0x40` and the surrounding HPL/VCO
configuration are likely necessary for SCT-generated VoiceIn samples to reach
the analogue modulation path. This is a likely explanation for accepting PCM
without recognizable FM modulation, but it remains an inference until an
authorized over-the-air or instrumented test observes the output.

Unproven: SCT VoiceIn credit on H13, actual PCM consumption, recognizable FM
audio, antenna power in milliwatts, and complete emergency recovery after an
unexpected bridge-timeout failure. Audio injection is not yet an achieved
device milestone.

Next static work is to recover the exact TLV320AIC3204 register semantics and
the codec-to-VCO topology from the codec documentation/public scripts, then
audit whether the five writes need restoration after STOP_CALL or are already
covered by the stock idle/channel recovery path. No RF or device test is
allowed without a new, explicit, per-test authorization.

## Addendum: bit-level correction after TI PDF review

The final supported inference above is corrected by a visual and extracted
text review of the saved TI SLOS602E/SLAA557 register tables. The five writes
do not establish a DAC-to-VCO output route:

```text
P1_R16 = 0x40  HPL driver muted; programmed gain bits are 0 dB
P1_R59 = 0x11  Left MICPGA enabled at 8.5 dB
P0_R86 = 0xF3  Left AGC enabled, -24 dBFS target, +/-1.5 dB gain hysteresis
P0_R87 = 0xBA  4 dB AGC hysteresis, -86 dB noise threshold
P0_R88 = gain  Left AGC maximum gain, 0.5 dB per code through code 116
```

The current fallback gain bytes `0/10/20` therefore represent AGC maximum
gains of `0/5/10 dB`, not an output-modulation gain. `P1_R16` is a gain/mute
register; HPL routing is `P1_R12`. The VCO/VCTCXO line outputs are LOL/LOR,
whose routing, power, and gains use `P1_R14`, `P1_R15`, `P1_R9`, `P1_R18`, and
`P1_R19` respectively.

The public `Mod_Cali.txt` labels independently match this distinction:

```text
TI_HPL_OFF  P1_R9  = 0x0C  (LOL/LOR driver power state)
VCO         P1_R18 = 0x06  (LOL driver gain)
TCXO        P1_R19 = 0x06  (LOR driver gain)
```

The fixed H13 image function `0x0800FA6C(r0,r1)` writes runtime values to
`P1_R18/R19` and also updates MICPGA/HPL state. It is called during analogue
channel configuration at `0x08013708`, with bytes 0 and 1 of runtime config
`0x20002DDC`. Consequently the VCO/VCTCXO line-output gains are already
applied by `DMOSETANALOGCH` before the Probe changes baud or enters raw bridge.
The production module initialization also loads the persistent DAC filter and
codec routing state.

Corrected inference: v1.12 still closes a real mismatch with stock TX setup,
but those five writes are input-AGC/speaker-mute state, not the missing
DAC-to-VCO bridge. They are not a strong direct explanation for absent
VoiceIn FM modulation. The next static focus must return to SCT VoiceIn route,
DAC sample ownership, LOL/LOR power/routing, and call/work-mode state rather
than treating the microphone AGC block as the modulation route.

## Addendum: stock channel recovery closes the effective codec teardown

The fixed `0.3.66` image shows that restoring the approved digital channel is
not merely a frequency/configuration write. The relevant call chain is:

```text
DMOSETDIGITALCH
-> 0x080203C8 (digital profile branch, profile +2 == 0)
-> 0x08014B94                         runtime PowerLow/PowerHigh selection
-> 0x080133CC -> 0x0801529C           complete digital-channel setup
   -> 0x0800FA6C(runtime[0], runtime[1])
-> 0x08020014 -> 0x08017F78(..., r2=0) digital idle/configuration entry
   -> 0x0801FF34                      stock full RF-off helper
   -> 0x0800FB62(runtime[1])
```

`0x0800FA6C` writes the current runtime values to `P1_R18/R19`, derives and
writes `P1_R60/R59`, and writes `P1_R16=0x40`. This reapplies the LOL/LOR
gains, MICPGA state, and HPL mute/gain state associated with the selected
channel. `0x0800FB62` then writes `P0_R86=0x00` before reapplying
`P1_R60/R59`. The zero at `P0_R86` disables the left AGC that
`0x0800FBCE` enables for TX with `0xF3`.

Observation: the approved `DMOSETDIGITALCH` recovery command reaches both
helpers on its normal digital branch. It therefore restores every codec state
that remains operationally active after the five TX writes: LOL/LOR gains,
MICPGA, HPL, and AGC enable. `P0_R87/R88` are not rewritten by this idle path,
but they are latent while `P0_R86=0`; this is also the stock firmware's own
post-TX recovery model, not a Probe-specific omission.

Bounded inference: Probe v1.14 does not need speculative inverse writes or a
new codec snapshot mechanism in teardown. Its existing stock RF-off helper
followed by the approved digital-channel reapply is the correct effective
codec recovery path. This does not prove VoiceIn support, PCM consumption,
recognizable FM modulation, or RF power.

No device was connected and no UART, HPI, PTT, GPIO, DAC, or RF action was
performed for this addendum.
