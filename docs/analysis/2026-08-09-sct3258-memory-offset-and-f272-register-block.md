# SCT3258 memory-offset model and F272 register-block candidate (2026-08-09)

## Scope

Offline-only analysis of immutable loader, BootLED, and uncoded sample data. No
H13 or TYT device was connected or operated. No ADB, Probe, UART, HPI, PTT,
VoiceIn, GPIO, DAC, Flash, reset, or RF action was performed. `job.md` was not
changed.

## Major-6/7 middle nibble

The earlier structural model called the middle nibble of major-6/7 words
“mode-like”. Independent exact-copy sequences constrain it more specifically.
The uncoded `0x2000` sample executes twice:

```text
field 5 = F808
field 4 = 3005
7044
6045
```

Major 7 reads data field 0 using base field 4 and middle nibble 4. Major 6 then
writes the same data field 0 using base field 5 and the same middle nibble 4.
The repeated pair therefore has the generic form:

```text
[base field 4 + offset 4] -> data field 0 -> [base field 5 + offset 4]
```

BootLED independently sets field A to F808 and then uses `600A`, the zero-offset
write form. The best current field model is consequently:

```text
[major 6/7][data field][unsigned offset nibble][base field]
```

Exact vendor mnemonics and whether documentation would call these word or data
addresses remain unrecovered.

## F272 base correction

`FA63 2E72; FA64 3EF2` materializes F272 in base field E. The 22 later
major-6/7 uses of E span offsets 0, 5, 6, 7, 8, A, and F:

```text
offset 0 -> candidate F272   4 accesses
offset 5 -> candidate F277   1 access
offset 6 -> candidate F278   9 accesses
offset 7 -> candidate F279   1 access
offset 8 -> candidate F27A   2 accesses
offset A -> candidate F27C   2 accesses
offset F -> candidate F281   3 accesses
```

This corrects the earlier shorthand that treated all 22 words as accesses to a
single F272 endpoint. F272 is now the strongest base candidate for a compact
word-register block. It remains compatible with the documented memory-mapped
HPI control/RX/TX registers, but no internal address map names the block.

The D/6 transaction from the prior CFG audit must likewise be stated as:

```text
FB1E/FB34 76AE:
  read-like [field E base + offset A] -> field 6
  F272 + A -> candidate F27C -> field 6
```

The two major-6/7 reverse-copy pairs use offset F on both sides:

```text
FB29 7CFD; FB2A 6CF6   [D+F] -> C -> [6+F]
FB3F 7CF6; FB40 6CFD   [6+F] -> C -> [D+F]
```

This strengthens the generic memory-copy interpretation and prevents treating
D or 6 as complete effective addresses without their shared displacement.

## Evidence

```text
audit_sct3258_memory_offset_model.py
  9FB12574EA691A21E674D4613379E2B736279EFFE2AEBA7C4E989F5CD05DAD56
sct3258_memory_offset_model_v1.json
  E615CB84986A6079EF3E36C5ADADF7AC460E4B792C955415C87F854DCDC9D105
```

The tool passes `py_compile`, validates all loader/BootLED/sample anchors, and
refuses to overwrite existing output.

## Bounded conclusion

F272 is a register-block base candidate, not a confirmed individual HPI data
register. Candidate F27C is the only currently supported immediate source for
field 6 at the cloned address-copy transactions. This is a useful alignment
constraint for the host control/address/word-count parser, but it does not yet
identify which offset is HPI RX, HPI TX, status, control, instruction-memory
destination, or data-memory destination.

The next static step is to classify the per-offset read/write sequences and
their data-field definitions, then compare their order with the documented host
header forms. No loader modification or device experiment follows from this
result alone.
