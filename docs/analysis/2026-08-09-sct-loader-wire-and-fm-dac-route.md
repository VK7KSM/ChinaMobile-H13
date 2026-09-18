# SCT3258 loader wire constraints and FM DAC route (2026-08-09)

## Scope

This was an entirely offline review of immutable vendor binaries, decompiled
host source, reflection output, and local SCT3258 PDFs. No H13 or TYT device
was connected. No ADB, Probe, UART, HPI, PTT, PCM, GPIO, DAC, Flash, reset, or
RF action was performed. `job.md` was not changed.

## Verified loader host wire contract

The 2019 SCT3258 and 2025 SCT3288 `LoadCode`, `LoadData`, and `CreatCmd`
implementations agree. Reflection over `PCPortBaseLib.DLL` independently fixes
the port values at a six-byte packet header, `DataHeadID=84 A9`, master ID
`0x61`, default 115200 baud, and 64-byte serial write chunks. The final serial
writer calls `SerialPort.Write(Byte[], offset, count)` without another framing
layer.

The resulting section-download wire forms are:

```text
instruction:
84 A9 61 00 06 04 + CONTROL ADDRESS_LE WORD_COUNT_LE

data:
CONTROL ADDRESS_LE WORD_COUNT_LE

payload after either header:
exact section.GetCmd() bytes, two bytes per declared word, no CreatCmd wrapper
```

The machine-checked report is
`public_sct3258/analysis_20260809/sct3258_loader_host_wire_contract_v1.json`,
6089 bytes, SHA-256
`9B0D369E26C972D784E4A3708D0DA9309A6C1257872EF8CBDE2B364656D0C70C`.
The auditor SHA-256 is
`2ADBED01D6E366EA33F3FC3F7DF6A7AA23D46CA8530544CF727464C40A62847E`.

## HPI physical constraints

The rendered SCT3258 data-sheet pages 30 and 33 and packet-interface page 87
visually confirm:

- runtime HPI has separate 16-bit RX and TX FIFOs, each 64 words deep;
- runtime HPI transfers are even-byte units and the hardware packs words MSB
  first;
- reset boot is a distinct mode: exactly 1024 instruction words, low byte
  first, after which execution starts immediately.

The reset byte order must not be projected onto runtime packets, and the
runtime hardware byte order must not be used to silently rewrite the vendor
host byte stream. The current report records both layers separately.

## Anonymous CFG constraints

The host contract gives a six-word instruction header and a three-word data
header. It does not uniquely assign the early 9-word `FA3D..FA45` and 16-word
`FA4B..FA5A` loops; they remain receive/framing candidates only.

The two 15-word loops at `FB1A..FB28` and `FB30..FB3E` contain paired selector
shapes at the same relative position:

```text
FB21 A906    FB37 A907
FB2E A806    FB44 A807
```

A third 12-word loop at `FB6D..FB78` contains all four forms. Global exact
occurrences are:

```text
A906: FB21, FB73    A907: FB37, FB6E
A806: FB2E, FB76    A807: FB44, FB70
```

This creates two live, competing hypotheses:

1. the paired loops operate on HPI RX/TX endpoints, supported by the documented
   paired hardware FIFOs and the repeated 6/7 selector motif;
2. the paired loops operate on instruction/data destinations, supported by the
   two different host header forms and common raw-payload stage.

No SCT3258 ISA semantics currently choose between them. The prior preference
for an instruction/data handler is therefore downgraded. Neither pair may be
named as a coder, decoder, FIFO access, or memory writer yet.

The combined constraint report is
`public_sct3258/analysis_20260809/sct3258_loader_cfg_constraints_v2.json`,
SHA-256
`66384272AAC82ABC9F3295FE8C7DF83F84FB5B7F5CEFAA386502305BBE06E7C1`.
Its generator SHA-256 is
`EBE39BE6776F755BFD72C90FC74F918130E8F806291BD812D9CA7FE922AB0923`.

## FM VoiceIn and DAC ownership

Rendered packet-interface pages 22-23 establish that `WORK_MODE` low nibble 2
is TX and 3 is full duplex. Page 124 gives the native stereo-codec ownership:

```text
left ADC   -> microphone
right ADC  -> FM demodulator
right DAC  -> VCTCXO
left DAC   -> audio line in RX, VCO in TX
```

HDK page 7 independently maps codec `LOUT2/ROUT2` to `MOD_I/MOD_Q`. This is a
stronger hardware-level account of the analogue modulation path than the five
TX microphone AGC writes previously audited.

The 2015 packet interface page 52 still marks `VOCODER_IO_SET` bit 7 reserved;
bit 6 is the documented CHAN_D modulator input. Distinct 2017 SCT3258 and 2025
SCT3288 `SCT_Device_T.DLL` binaries both expose bit 7 as `Voice File`. Their
decompiled core `SCT3252T.cs` files are byte-identical, SHA-256
`76A56AFA38047D2B2BF788EB97972C3AAB2A724A2EB378458D2C502969DC3601`.
The implementation swaps every 16-bit PCM byte pair, wraps 1280 PCM bytes as
packet type 3 body `[00,02,80,<data>]`, sends the first block immediately after
call setup, and advances later blocks only on a complete one/two-byte short
response beginning with 0 or 1.

This is not one DLL duplicated into two packages. The 2017 binary is 512000
bytes with SHA-256
`DF57DDFA9B2266D55F55BD21DDF7759B36CB959C171ECDA04BCF7138BFB6AFCD`;
the 2025 binary is 516096 bytes with SHA-256
`162C4913E0C48CB41675C1D7F27944509CE9EB97B89345893078A5F60EFD727D`.
Their `Form_SaveVocoder` differences add codec/UI behavior and do not alter the
VoiceIn first-block path. The local implementation timeline is therefore
narrowed from reserved in 2015 to present in a vendor SCT3258 tool by 2017.

Confirmed consequence: `WORK_MODE_TX` is already the documented state in which
the left/right DAC outputs belong to VCO/VCTCXO modulation. If H13 application
`V2.01.07I3` accepts bit 7 and consumes the VoiceIn block as voice samples, the
expected analogue output owner is established without inventing an additional
codec-to-VCO register sequence.

Supported inference: because the exact same core VoiceIn implementation is
present in distinct 2017 and 2025 tools, bit 7 is a long-lived vendor extension
rather than a 2025-only SCT3288 addition. This materially improves the
compatibility prior for H13, but does not identify the H13 application build.

Unproven boundary: the exact I3 application remains unavailable in uncoded
form. A zero-status `0x3E/0x80` control ACK would prove only that the control
field was accepted. Only a post-block data credit plus an instrumented or
authorized RF observation can prove PCM consumption and recognizable FM
modulation. Audio injection is therefore still not an achieved device
milestone.

## Next offline work

1. Keep loader role labels anonymous and locate an actual HPI receive/read,
   HPI transmit/write, destination memory write, and coded-state update before
   any loader modification is considered.
2. Search for a versioned SCT3258 application or symbol-bearing tool that
   explicitly links bit 7 `Voice File` to the 1283-byte packet-type-3 consumer.
3. Do not spend another device session on speculative codec route writes. The
   next eventual VoiceIn gate, when separately authorized, is the existing
   exact control ACK -> first block -> short data credit sequence at factory
   low power, followed by the documented stop/recovery chain.

Source PDF SHA-256 values:

```text
SCT3258_datasheet_v2_0.pdf
9BEBA9651E55F1D644E502C8B489BA0D62629C2228595A5CC095F69B6FD4DFC9

SCT3258_Packet_Interface.pdf
2A081DDFC645E544D3B6B868E299E29A1B323846949780651407C5D1731A3F5D

SCT3258_HDK_User_Guide_V1_9.pdf
09A308403C59D67414294CF9119DC9364023DE2EE2A77ED4377BACA22784DABB
```
