# SCT3258 major-8/B formats and D/6 CFG sources (2026-08-09)

## Scope

Offline-only analysis of immutable uncoded SCT programs and the quarantined
loader CFG. No H13 or TYT device was connected or operated. No ADB, Probe,
UART, HPI, PTT, VoiceIn, GPIO, DAC, Flash, reset, or RF action was performed.
`job.md` was not changed.

## Major-8/B format correction

Major-8/B words must not inherit the three-register major-A layout. Two exact
anchors in the independent uncoded `0x2000` program uniquely feed bits 7:4 to
the next already-supported consumer:

```text
2005 86C0 -> 2006 A3C3   C feeds major-A input 1
206C 806C -> 206D 79D6   6 feeds major-7 address
```

Three loader anchors repeat the same major-8 direction:

```text
F895 8C0E -> F896 A502
F9C6 8CAF -> F9C7 A6BA
F9DB 8CAF -> F9DC A79A
```

Across 23 eligible adjacent major-8 pairs, bits 7:4 have five unique feed
matches; bits 3:0 have zero. This supports:

```text
[8x high-byte opcode family][result-like bits 7:4][input-like bits 3:0]
```

The independent program also contains:

```text
2039 BAED -> 203A A6D0   only D can feed the next known input
203B BA0D -> 203C A6D0   D repeats, but 0 is an ambiguous coincidental match
203D BA2D                 same BA family, varying first low operand, fixed D
```

This supports `[BA opcode][varying operand][result-like D]`. Aggregate major-B
scores are not strong enough to assert that every Bx family has the same
direction.

The immediate consequence is narrower but decisive for the F272 audit. Words
whose high byte is `8E` or `BE` do not contain a result field E under the best
current two-low-operand formats. The twelve prior “first-field-E” alternatives
are high-byte opcode aliases, not demonstrated writes to the register-like
field E. They no longer constitute F272 clobber alternatives.

## CFG-aware D/6 sources

The four mirrored transfer words are:

```text
FB29 7CFD; FB2A 6CF6   address field D -> address field 6
FB3F 7CF6; FB40 6CFD   address field 6 -> address field D
```

Two fixed-point models were run over every anonymous branch target and
fallthrough in CFG v3:

1. supported result writes from major 2/3, 7, 8, and A, with major B omitted;
2. the same writes plus an intentionally over-conservative assumption that
   every major-B word writes both low operands.

Both models produce exactly the same may-reaching sets at all four transfer
instructions:

```text
field D: FB10 ADA0, FB52 3D89
field 6: FB1E 76AE, FB34 76AE
```

The `FB52` alternative is loop-carried through the deliberately
over-approximating anonymous CFG. It prevents collapsing field D to `FB10`
alone. Field 6 is more constrained: both and only possible definitions are
major-7 read-like transfers from address field E. The dominating setup for E
is still `FA63 2E72; FA64 3EF2 -> F272`.

Therefore the stable transaction shape is:

```text
read-like [F272] -> field 6 -> use field 6 as a memory-transfer address
```

This is consistent with an HPI receive data word carrying a destination/source
address into the loader. It is not yet proof that F272 is the HPI RX FIFO or a
bidirectional HPI data register; the SCT3258 internal address map and exact
8x/Bx mnemonics remain unavailable.

## Evidence

```text
audit_sct3258_major_8_b_field_direction.py
  CDB34F3150E5D515CD0874CDD561B50B3EE3F60CCD1AFA2F08710C25352D012C
sct3258_major_8_b_field_direction_audit_v2.json
  3B15106A50449698ADB087EDD0D166D6713CA2B4D430B424C00B05BDEEEE2313

audit_sct3258_d6_cfg_sources.py
  BC5B50F3EE52D3C63F0B6C4C6D121D7FDEBA361F43046177D6D8C5A7B7FCE625
sct3258_d6_cfg_sources_v1.json
  3A56F23EE3E5D25ED4D8CA54295E0A962DC4731AC315D1F6CC913164B048501D
```

Both tools pass `py_compile` and refuse to overwrite existing reports. The v1
major-8/B JSON remains preserved as an intermediate audit; v2 is current
because it separates the high-byte opcode extension from the two low operands.

## Next static target

1. Align F272 reads with the known host control/address/word-count sequence.
2. Determine whether F272 write-like uses form the matching acknowledgement or
   transmit side of one bidirectional HPI data port.
3. Keep instruction/data destination labels anonymous until the paired clone
   branches can be tied to the documented control bits.
4. Do not design or run a loader modification from these constraints alone.
