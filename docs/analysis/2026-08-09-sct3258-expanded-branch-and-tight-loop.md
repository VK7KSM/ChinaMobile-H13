# SCT3258 expanded branch family and tight-loop constraints (2026-08-09)

## Scope

This was entirely offline analysis of immutable vendor Boot RAM images and
existing derived reports. No H13 or TYT device was connected. No ADB, Probe,
UART, HPI, PTT, VoiceIn, GPIO, DAC, Flash, reset, or RF action was performed.
`job.md` was not changed.

## Correction to the old branch model

The prior anonymous CFG recognized only opcode high bytes `04/44/84/C4`, using
the predicate `(opcode_high_byte & 0x3f) == 0x04`. That model is incomplete.
The independent 44-word BootLED program contains five mechanically coherent
local back edges when the signed low byte is interpreted as a PC-relative word
displacement:

```text
F81A 4EFC -> F817
F81B 4FF9 -> F815
F826 4EFC -> F823
F827 4FF9 -> F821
F82B 0FE7 -> F813
```

The first four form two repeated nested-delay-loop shapes and the final edge
returns to the first loop body. None belongs to the old narrow family. The old
statement that the HPI fall-through contains 12 candidate branches is therefore
retained only as a count under that narrow predicate, not as a complete CFG.

The first diagnostic v1 expanded report accidentally omitted the old `84/C4`
members from its comparison universe. It is preserved unchanged. The corrected
v2 predicate is the union of the old family and the BootLED-constrained high
nibbles `0x0/0x4`. All five BootLED anchors and all six old loader anchors pass.
The corrected HPI counts are:

```text
all expanded candidates:       50
old narrow-family candidates:  12
new candidates:                38
```

The auditor is `research/h13_radio/tools/audit_sct3258_branch_family.py`,
SHA-256
`58647FC1E5E195C44B117F16E8E97D139711723EBC20AC2F4EB799C3427D0D7F`.
The v2 report is
`public_sct3258/analysis_20260809/sct3258_loader_expanded_branch_family_v2.json`,
SHA-256
`4DBC426CD645A4AA879D66CBE0471D6A2BB0A409CA357C45C05FF2B8AC0B9562`.

## Code/data quarantine

Applying the expanded predicate indiscriminately also finds branch-shaped words
inside two high-entropy regions:

```text
FB8C..FB9B   32 bytes   29 unique bytes   entropy 4.812500 bits/byte
FBA0..FBFF  192 bytes  137 unique bytes   entropy 6.961137 bits/byte
```

The first region is bracketed by structured code-like words; the second follows
`A2B2 BBF3 CBF2 CBF2` at `FB9C..FB9F` and runs to the Boot RAM end. Their exact
roles are not known, but treating every branch-shaped value in them as code
creates implausible in/out-of-image targets. The v2 CFG therefore labels them
suspected literal/table regions and excludes their candidate edges instead of
silently declaring them instructions.

Of the 50 HPI candidates, 27 remain as structured-region edges, two have a
target identical to sequential fall-through (`FAD7 4500` and `FB6B 4B00`), and
21 are quarantined because their source or target crosses a suspected data
boundary. The result has 54 anonymous basic blocks. It remains an
over-approximation: no condition name, mnemonic, call/return rule, register, or
memory operation is assigned.

The generator is
`research/h13_radio/tools/build_sct3258_loader_cfg_v2.py`, SHA-256
`4646B0795791DEB73FA6910850E48C41B84006865A61E266DC10D52A2CB0753E`.
The report is
`public_sct3258/analysis_20260809/sct3258_loader_quarantined_candidate_cfg_v2.json`,
SHA-256
`E5C32B855A6D4C2D456B7D54813A85912A4607AFAB68978E8249E05E85E7B01B`.
Both generators pass `py_compile`; both outputs were created with overwrite
refusal enabled.

## Nested paired-loop structure

The expanded model materially refines the two previously known 15-word loops.
The 12 words at `FB1D..FB28` and `FB33..FB3E` are exact near-clones with only
one changed word at relative offset four:

```text
FB21 A906
FB37 A907
```

The seven following words at `FB29..FB2F` and `FB3F..FB45` differ only in three
places:

```text
7CFD 6CF6 ... A806
7CF6 6CFD ... A807
```

The expanded branches add matching inner and outer back-edge shapes to both
copies:

```text
FB20 45F0 -> FB11    FB36 45F0 -> FB27
FB23 4DF9 -> FB1D    FB39 4DF9 -> FB33
FB28 04F1 -> FB1A    FB3E 04F1 -> FB30
```

This symmetry is stronger than the old CFG showed, but it does not identify
the two paths as HPI RX/TX or instruction/data destinations.

## Tight loop and A8/A9 operand pairs

The exact word `A592` occurs once in the 1024-word image, at `FB6C`. The next
word is an expanded-family back edge:

```text
FB6C A592
FB6D 45FE -> FB6C
```

If the expanded branch encoding is correct at `FB6D`, `A592` is the only
non-branch word in this two-word tight loop. This supports a bounded inference
that `A592` produces or tests a condition repeatedly, but it does not prove a
memory read, HPI FIFO status read, hardware register address, or ready bit.
Calling this a one-word loop is incorrect; it is a two-word loop with one
non-branch body word.

Immediately after it, `FB6E..FB76` contains the `A9/A8` forms for low operands
`07` and `06`. A global exact-occurrence audit shows the same opcode-family
pairing for low operand `0E` elsewhere:

```text
operand 06: A906 at FB21/FB73; A806 at FB2E/FB76
operand 07: A907 at FB37/FB6E; A807 at FB44/FB70
operand 0E: A90E at F99C/FADC; A80E at F99F/FAEE
```

Observation: `06/07/0E` are repeated low-byte operands shared by paired `A9xx`
and `A8xx` word families. Supported inference: `A9xx` and `A8xx` likely perform
related or opposing operations on the same encoded operand. Unconfirmed: the
operand may encode a register, HPI endpoint, memory-path selector, or another
resource. The prior convenience label "selector word" is therefore downgraded
to "A8/A9 low operand" until an instruction semantic is recovered.

## Consequences and next offline work

- The old candidate CFG and constraints JSON remain valid as immutable records
  of their inputs and exact word motifs, but not as a complete control-flow
  model.
- `CBF2` remains a strong NOP/padding candidate. The expanded predicate does not
  classify it as a branch and does not weaken the HPI-mode `FA2E` patch result.
- No loader modification or device experiment follows from these results.
- The next static target is the unique `A592` operation family and its condition
  producer/consumer context, followed by recovery of one confirmed memory or
  HPI access primitive. Only then can the `A8/A9` low operands be assigned a
  hardware or memory role.
- The two suspected literal/table regions should be analyzed as possible coded
  state/key material independently of the CFG, without treating their random
  branch-shaped words as executable edges.
