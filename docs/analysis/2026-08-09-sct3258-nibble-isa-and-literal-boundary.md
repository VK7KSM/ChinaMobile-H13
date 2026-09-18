# SCT3258 nibble ISA constraints and literal boundary (2026-08-09)

## Scope

This was entirely offline analysis of immutable vendor Boot RAM images and new
derived JSON reports. No H13 or TYT device was connected. No ADB, Probe, UART,
HPI, PTT, VoiceIn, GPIO, DAC, Flash, reset, or RF action was performed.
`job.md` was not changed.

## Correction to the compatibility-union v2

The v2 expanded report deliberately retained every member of the old
`(opcode_high_byte & 0x3f) == 0x04` model and unioned it with words whose top
nibble is `0` or `4`. That was useful as a compatibility over-approximation,
but four-nibble structural analysis shows that the old model itself included
three unsupported HPI words:

```text
FA6C 8478
FB69 C472
FB80 84BE
```

Unlike the confirmed `04/44` loop anchors, these words have major nibbles `8`
or `C`. The independent BootLED branches have major nibbles `0/4`, a second
condition nibble, and a signed low-byte displacement. The full loader's
repeated nested-loop shapes use the same form. The better constrained branch
candidate layout is therefore:

```text
[major 0 or 4] [condition nibble] [signed 8-bit word displacement]
target = current word address + 1 + signed8(low byte)
```

Under this model the HPI candidate count is:

```text
all 0?/4? branch candidates: 47
old 04/44 overlap:             9
additional conditions:        38
rejected old 84/C4 words:      3
```

This does not assert that every random `0?/4?` word is executable. It defines a
necessary candidate encoding that must still be filtered by code/data bounds
and control-flow structure. The earlier v1 diagnostic happened to produce the
same `47/9/38` counts but did not have this nibble-field justification; v1 and
v2 remain unchanged as historical diagnostics.

The v3 auditor is
`research/h13_radio/tools/audit_sct3258_branch_nibble_model.py`, SHA-256
`8A39BBD25E5DA951DBB5276325405CEF646AE9710DD156B7CFBB03BBD5AD3BAD`.
Its report is
`public_sct3258/analysis_20260809/sct3258_loader_branch_nibble_model_v3.json`,
SHA-256
`F4049B3AE29EA10DB88420D4ED0F03D0906C253FEE8312320CC89DA0FF6941D0`.

## Major-A operand field correction

The old convenience language treated `A906/A806` and `A907/A807` as different
`A9/A8` opcode families with endpoint-like low selectors. Their exact nibble
symmetry supports a different parse:

```text
A906 = A | 9 | 0 | 6    A806 = A | 8 | 0 | 6
A907 = A | 9 | 0 | 7    A807 = A | 8 | 0 | 7
A90E = A | 9 | 0 | E    A80E = A | 8 | 0 | E
A592 = A | 5 | 9 | 2
```

The top nibble is stable while three lower operand-like nibbles vary. Independent
cross-major pairs preserve the same low 12 bits:

```text
85A2 at FA2D       A5A2 at FAD6/FB1F/FB35/FB48
8582 at FA7D       A582 at FA6F
8502 at FA90       A502 at F860/F896
```

Observation: `A906/A806` differ in the first lower nibble `9/8`, not in the
major nibble; `06/07/0E` occupy the final two lower nibbles. Supported
inference: major `A` is one instruction class and the lower nibbles encode
operands or modes. Unconfirmed: destination/source ordering, register names,
memory addressing, and the operation itself. Consequently `06/07` cannot be
named as HPI RX/TX endpoints or instruction/data selectors.

`A592` should likewise be treated as one major-A operation with lower fields
`5/9/2`, followed by `45FE -> FB6C`. It is not a standalone opcode or a proven
direct status read. The tight-loop evidence now says:

```text
FB6C  A | 5 | 9 | 2
FB6D  branch-class 4 | condition 5 | displacement FE -> FB6C
```

This is a canonical operation-plus-condition loop shape. The next useful static
task is to trace the definitions and uses of operand-like fields `9` and `2`,
not to assume that `A592` itself reads an HPI register.

## v3 CFG effect

The v3 CFG removes the three unsupported `84/C4` edges before building blocks.
It contains 47 input candidates, 25 structured edges, two target-equals-
fallthrough candidates, 20 quarantined candidates, and 51 basic blocks. The
old v2 compatibility graph had 54 blocks and a 26-block cyclic component that
incorrectly merged the main cloned-loop region with the `FB6C` loop through
the `84BE` edge.

After removal, the `FB6C` region becomes its own three-block cyclic component:

```text
entry/predicate: FB6C A592; FB6D 45FE -> FB6C
body:            FB6E..FB78
body back edge:  FB78 04F4 -> FB6D
```

The body contains both `A?07` and `A?06` mirror forms in one iteration. This is
consistent with an internal paired-state transform. It is not yet proof of a
cipher, coder, FIFO direction, or memory destination.

The v3 CFG generator is
`research/h13_radio/tools/build_sct3258_loader_cfg_v3.py`, SHA-256
`633098ACE3723FB351F74E3A123A07015C6D98CFC4A5E011B48E79235A27C6CC`.
Its report is
`public_sct3258/analysis_20260809/sct3258_loader_quarantined_nibble_cfg_v3.json`,
SHA-256
`70AFB31B8C332CC614589EECC801FA035C24892A5FCAE79588095DFCDA2C4774`.
Both v3 tools pass `py_compile` and refuse to overwrite existing output.

## Literal-region evidence

All six base/HPI/User2/User3/System-DMR/System-DPMR loader variants contain
identical bytes in both suspected literal regions:

```text
FB8C..FB9B SHA-256 A7FF723EBC8C2956AF9F1FAC0F2F59C7A3530C9773A20BC60EE6EEFD3DB76CB9
FBA0..FBFF SHA-256 2F2B307B12AF2F130FD2A54BA32F801BC8BDC82AA9E9F35CB24C8E61E033A32E
```

The 192-byte tail has 96 unique words out of 96, 81 different opcode-high-byte
values, 67 singleton opcode-high-byte values, and no `CBF2`. Its major-nibble
histogram is close to uniform:

```text
0:4 1:1 2:4 3:7 4:9 5:5 6:6 7:7
8:6 9:7 A:9 B:7 C:6 D:7 E:5 F:6
chi-square against uniform = 9.667 (15 degrees of freedom)
```

By contrast, the 349-word structured service prefix has chi-square 327.080 and
strongly favors the known code major nibbles. This is strong evidence that the
tail is data-like rather than ordinary executable code. Branch-shaped words
inside it, and `FB7E 4A2C -> FBAB` entering it, remain quarantined rather than
being used to reclassify the whole tail as code.

The tail has no duplicate chunk at widths 16/24/32/48/64/96 bytes and contains
none of the tested AES forward/inverse S-box, SHA-256 K, Blowfish P, TEA delta,
or CRC32 table prefixes. A direct AES-256-ECB trial using the adjacent 32-byte
block as the key in raw, per-word-swapped, and reversed order left the output
high entropy (6.8998 to 6.9806 bits/byte). This rejects only that simplest
key-plus-ECB interpretation; it does not identify or exclude another coding
algorithm.

The literal auditor is
`research/h13_radio/tools/audit_sct3258_loader_literal_regions.py`, SHA-256
`442AB62AB8F751A745E8B62689D366A725BA70E6E2CD434188B6A35CBED4B237`.
Its JSON is
`public_sct3258/analysis_20260809/sct3258_loader_literal_region_audit_v1.json`,
SHA-256
`AEACB0642E7FF275706FE52D091FF2267BD6BEFF7D034EFF15E1B506189F7417`.

## Bounded conclusion and next work

- Confirmed: the old 12-branch predicate contained three unsupported `84/C4`
  words; the best current candidate branch universe is 47 top-nibble `0/4`
  words before code/data quarantine.
- Confirmed: `A906/A806/A907/A807` are better represented as one major-A class
  with changing lower operand-like nibbles. The old endpoint-selector labels
  are withdrawn.
- Supported inference: `FB1D..FB78` is more likely an internal paired-state
  transformation than two independent hardware endpoint handlers, especially
  given the isolated loop and adjacent invariant data-like regions.
- Unconfirmed: the major-A mnemonic, operand direction, state/register mapping,
  exact literal role, memory access primitive, and coded1/2/3 update rule.
- Next: build nibble-level def/use constraints around fields `5/8/9/2/6/7/E`,
  then locate the instruction that imports a received HPI word into that state
  and the instruction that commits a transformed word to instruction/data RAM.
  No loader modification or device test is justified by this result.
