# SCT3258 major-A nibble dataflow (2026-08-09)

## Scope

Offline-only analysis of the immutable 2 KiB vendor loader. No device, ADB,
Probe, UART, HPI, PTT, VoiceIn, GPIO, DAC, Flash, reset, or RF action was
performed. `job.md` was not changed.

## Repeated producer/consumer shape

The proposed major-A layout is kept explicitly hypothetical:

```text
[major A] [result-like field] [input-like field 1] [input-like field 2]
```

Two cloned paths independently contain the same one-word producer/consumer
chain:

```text
FB21 A|9|0|6  ->  FB22 A|5|9|3
FB37 A|9|0|7  ->  FB38 A|5|9|3
```

In both cases field `9` is produced in one word and appears as the first input
field in the next word. The clone then contains `A|7|9|3`, and its cleanup uses
`A|8|0|6` or `A|8|0|7`. This repeated local dependency materially supports the
result/input field ordering; it does not identify the major-A operation.

## Tight-loop state fields

The isolated loop begins with:

```text
FB6C A|5|9|2
FB6D 4|5|FE -> FB6C
```

Its `FB6E..FB78` body contains:

```text
A|9|0|7   A|8|0|7   A|9|0|6   A|8|0|6
```

Under the field model, the body writes result-like field `9` and field `8`, but
does not write result-like field `2`. The `FB78` back edge returns to `FB6D`,
which can re-enter the `A|5|9|2` operation. This supports a loop predicate or
state update computed from a changing field `9` and a locally invariant field
`2`. Addition, subtraction, compare, XOR, register identity, and hardware roles
remain unknown.

The nearest earlier major-A definitions in linear address order are `FB37
A|9|0|7` for field `9` and `FB05 A|2|A|2` for field `2`. These are not claimed
as path-sensitive reaching definitions because branch conditions and call rules
are still anonymous.

## Evidence

The auditor is
`research/h13_radio/tools/audit_sct3258_major_a_dataflow.py`, SHA-256
`E077714BF585457398391AA21E34F39E93A17ACC0325217B63E5D831D784D28E`.
The report is
`public_sct3258/analysis_20260809/sct3258_major_a_nibble_dataflow_v1.json`,
SHA-256
`54477A06FF386BF8B784676F9BDF3C320E3264FB48D9F4725C4F7B714BB5A38E`.
The tool passes `py_compile` and refuses to overwrite output.

## Next static target

Track all writes and reads of fields `9`, `2`, and `5` across candidate basic
blocks, while keeping control-flow alternatives separate. The acceptance gate
for naming an HPI or memory primitive remains an independent instruction shape
linked to a documented FIFO or destination transaction, not merely a plausible
major-A dataflow.
