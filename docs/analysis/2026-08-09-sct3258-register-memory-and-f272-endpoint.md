# SCT3258 register, memory-transfer, and F272 endpoint constraints (2026-08-09)

## Scope

Offline-only analysis of immutable uncoded SCT programs and derived CFG data.
No H13 or TYT device was connected or operated. No ADB, Probe, UART, HPI, PTT,
VoiceIn, GPIO, DAC, Flash, reset, or RF action was performed. `job.md` was not
changed.

## CFG-aware major-A definitions

The v3 CFG fixed point retains every anonymous branch and fallthrough, so its
results are conservative may-reach sets. At `FB6C A592`, predecessor states are
kept separate:

```text
initial predecessor FB6B:
  field 9: entry, FADC, FB21, FB37
  field 2: FB05

loop predecessor FB6D:
  field 9: entry, FADC, FB21, FB37, FB73
  field 2: FB05
```

Only `FB73 A906` is added to field 9 by the loop-carried path. Field 2 remains
uniquely defined by `FB05 A2A2` under the over-approximating CFG. `FB05` is
itself a recurrence: its second input is field 2 and it produces field 2.

## Cross-program register layout

The first lower nibble of major-A words is now supported as result-like by
three independent uncoded programs, not only by the loader clones. Across 41
adjacent major-A pairs, the three candidate result positions score as follows:

```text
lower position 1: 9 pairs feed either next input
lower position 2: 2 pairs feed either next input
lower position 3: 4 pairs feed either next input
```

Independent examples include:

```text
BootLED: F806 A0CC -> F807 A670
sample:  2006 A3C3 -> 2007 A2C3
sample:  2037 A0CE -> 2038 A6D0
```

This materially supports `[major A][result][input 1][input 2]`. It does not
identify the arithmetic or the register names beyond their encoded fields.

## Immediate halves and memory transfers

Major 2/3 pairs consistently materialize a 16-bit value for one register-like
field, with major 2 supplying the low byte and major 3 the high byte. BootLED
contains the decisive setup:

```text
F80D 3AF8; F80E 2A08 -> field A = F808
F80F 2008; F810 3003 -> field 0 = 0308
F811 600A
```

The independent uncoded `0x2000` sample repeats a read/write sequence twice:

```text
203E 2508; 203F 35F8 -> field 5 = F808
2040 2405; 2041 3430 -> field 4 = 3005
2042 7044
2043 6045

2064..2069 repeats the same setup and transfers exactly.
```

These sequences support major 7 as read-like and major 6 as write-like, with
the first lower field carrying data and the last lower field selecting an
address-bearing operand. The middle field remains mode-like and unnamed.

The loader then contains one exact reverse-copy pair:

```text
FB29 7CFD; FB2A 6CF6   endpoint field D -> field 6 through data field C
FB3F 7CF6; FB40 6CFD   endpoint field 6 -> field D through data field C
```

Only the last address-like fields reverse; data field C and middle field F are
unchanged. This identifies two opposite generic memory transfers. It still
does not assign HPI, instruction RAM, data RAM, or coded-state roles to fields
D and 6.

## F272 endpoint candidate

`FA63 2E72; FA64 3EF2` materializes `0xF272` in field E. Its CFG block
dominates all 22 later major-6/7 transfers whose address field is E: 9 are
read-like and 13 are write-like. These include write-like `FA87 66FE` and
`FA8A 65FE`, plus the cloned read-like entries `FB1E/FB34 76AE`.

No later recovered major-2/3, major-A, or major-7 result write targets field E.
Major-8/B field direction is still unknown; twelve such first-field-E words
are preserved in the JSON as possible clobber alternatives.

In the vendor HPI-maintenance loader context, F272 is the strongest current
I/O endpoint candidate. The SCT3258 data sheet independently documents 16-bit
HPI RX/TX FIFOs and memory-mapped control, RX, and TX registers, but does not
publish their internal addresses. Therefore F272 is not yet proven to be
`hpird`, `hpixd`, `hpictl`, instruction RAM, or data RAM.

## Evidence

```text
audit_sct3258_major_a_cfg_dataflow.py
  8AD8CD37D3F4D5D96363EAE40E05DC2736B58385DF14A0D4FFDA58C793707F1D
sct3258_major_a_cfg_reaching_definitions_v2.json
  50C24E21CB8C67754AF87524495BB4CD90A39B496279D96CA932A89CAD42E642

audit_sct3258_major_a_field_layout.py
  4C4F07379473B35379C6858AE27FC22BFBCF67A6AD1BBE034525DDF4BE7F81FC
sct3258_major_a_field_layout_crosscheck_v1.json
  85A78CB7C85C2F317761ACD851A6A9F0AD5EDBC168A7F6175E94F27D64FD0279

audit_sct3258_memory_transfer_shapes.py
  9D109D4DD8292FFAB4040FA0B583AF496625C58D21680BFA0A6F52DF0F003BC9
sct3258_memory_transfer_shapes_v2.json
  DFB7979B8F53C7AEB20116E2799EB891FAF04B05767338408F2EA35E47D3EB97

audit_sct3258_f272_endpoint.py
  52280F0C15FD673A6DA35106B9865F4908A906E7726C63454AAC4DA9EBD6A542
sct3258_f272_endpoint_cfg_audit_v1.json
  A364131358AC11A1751AD212539C884CB7A923AB2D443838E3C3ADC2DE001369
```

All four tools pass `py_compile` and refuse to overwrite existing outputs.
The earlier `sct3258_memory_transfer_shapes_v1.json` remains preserved; v2 is
the current report because it adds the independent `0x2000` sample gate.

## Next static target

1. Recover major-8/B field direction to resolve the listed F272 clobber
   alternative.
2. Track the values feeding address fields D and 6 at the reverse-copy loops.
3. Require an independent internal address map or a statically named HPI FIFO
   transaction before promoting F272 from candidate to confirmed HPI register.
4. Do not design or run a loader modification from these constraints alone.
