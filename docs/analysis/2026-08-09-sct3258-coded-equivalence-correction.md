# SCT3258 coded2/coded3 equivalence correction

## Scope

This is an offline audit of the five immutable public SCT3258 HEX files
(`05NJ`, `06PN`, `07B5`, `07BA`, and `07BF`). No device, ADB, UART, HPI,
GPIO, Flash, or RF action was used.

The audit corrects the pairing premise used by the earlier
`analyze_sct3258_coded_relations.py` and the diagnostic
`sct3258_coded_state_tests_v1/v2.json` reports. Those files are preserved as
historical derived evidence; they must not be used as decoder training data.

## Decisive observations

The five files contain 954 parsed sections. Their coded domains are separated
by Flash role:

| Role | Coded classes present |
|---|---|
| User Application | coded1, coded3 |
| Modem2 Application | coded1, coded3 |
| vocoder index 04 | coded3 |
| vocoder index 12 | coded2 |
| vocoder index 16 | coded2 |
| vocoder index 23 | coded2 |

The legacy matcher grouped by version, memory kind, low target word address,
and word count, but omitted the HEX run/Flash role. It produced 195 candidate
pairs (37 distinct payload pairs). All 195 cross roles. A matcher that also
requires either the same role or the same run finds zero coded3/coded2 pairs.

Nine exact coded2 sections are each paired by the legacy matcher with more
than one distinct coded3 payload. The smallest decisive witness is `05NJ`
instruction address `0xF3F0`, 96 words:

- User Application coded3 and Modem2 coded3 differ in 8 bytes at offsets
  `128..135` and have different SHA-256 hashes.
- Both are assigned to the exact same vocoder-index-16 coded2 section and
  payload hash only because its low address and length happen to match.

This falsifies use of that address/length match as evidence that either
coded3 payload and the coded2 payload encode the same plaintext.

## Why the feedback result is not an algorithm

The v2 diagnostic localized the previously reported short-history result:

| History from each side | Repeated keys | Conflicts |
|---:|---:|---:|
| 0 bytes | 144,008 | 142,817 |
| 1 byte | 70,702 | 318 |
| 2 bytes | 70,115 | 3 |
| 3 bytes | 69,681 | 1 |
| 4 bytes | 69,249 | 1 |

Leave-one-payload-pair-out lookup covered 79.65% of bytes and was correct for
114,836 of 114,839 deterministic seen keys. This is not independent
generalization: the public firmware roles and versions reuse long identical
ciphertext substrings, so the held-out lookup sees the same source/output
windows from another application or version. The remaining conflicts are
also internal, not section-initial-state observations.

Therefore neither the low conflict count nor the lookup holdout establishes a
coded3-to-coded2 state transition. It only measures repeated structure among
unrelated coded payloads.

## Artifacts and gates

- `audit_sct3258_coded_equivalence.py`, SHA-256
  `CE091B9147577B4276C4F84136EE11C52AC05D97843EBB23C89847976D06D82D`
- `sct3258_coded_equivalence_audit_v1.json`, 21,331 bytes, SHA-256
  `184410718584D6F9165D563F9C31B1AE50926AD5DB8CEBE1343E794769D387F0`
- Preserved diagnostic `sct3258_coded_state_tests_v2.json`, 848,903 bytes,
  SHA-256
  `528B4DE4139332993DE1FFCF8DD73C5EE2DD1575455C23E060063341820EA1B6`

`analyze_sct3258_coded_state.py` now refuses to run unless the caller passes
`--allow-cross-role-diagnostic`. Its output warning states that the pairs are
not known-equivalent plaintext. The refusal and explicit diagnostic paths
both passed offline checks; both analysis tools pass `py_compile`.

## Bounded conclusion and next branch

Observation: current public samples provide no role-preserving coded3/coded2
pair and hence no known-equivalent plaintext pair across those coding modes.

Correction: the relation claims in `H13.md` section 18.02 and the later short
feedback hypothesis are withdrawn as decoder evidence. The raw counts remain
valid only as properties of the legacy cross-role alignment.

Next branch: recover the coder from the uncoded 2 KiB loader or locate a real
same-application artifact intentionally emitted under two coded modes. Loader
work should focus on the coded-control dispatcher and state updates; it should
not fit more transform models to the 37 invalid cross-role pairs.
