# SCT artifact equivalence and loader dispatcher audit (2026-08-09)

## Scope

This work was entirely offline. It read immutable public SCT3258/SCT3288 artifacts
and the existing 2 KiB uncoded loader. It did not connect to H13 or TYT, run ADB or
Probe, transmit RF, or send UART/HPI/PTT/PCM/data/Flash/DAC/GPIO commands. `job.md`
was not changed.

## Plat4m application comparison

The two SCT3288 application containers have the same entry (`0x0800`) and the same
14-section role/address/coded-type sequence. Two sections remain coded1 and twelve
remain coded3; there is no cross-coded aligned section.

Nine payloads are byte-identical. Changed section ordinals are 4, 7, 9, 10, and 13:

| Ordinal | Memory/address | Words left/right | Common prefix | Common suffix | Differing bytes in common length |
| --- | --- | ---: | ---: | ---: | ---: |
| 4 | instruction `0x002dc` | 952 / 952 | 608 | 1288 | 8 |
| 7 | instruction `0x074b4` | 1276 / 1276 | 1152 | 1392 | 8 |
| 9 | data `0x015d8` | 1588 / 1588 | 784 | 1896 | 48 |
| 10 | data `0x02cf9` | 540 / 540 | 664 | 24 | 24 |
| 13 | instruction `0x00800` | 7924 / 7900 | 112 | 0 | 9416 |

The files are therefore related application/build variants, not one payload moved
to alternate Flash addresses. They cannot provide a coded1/2/3 equivalence pair.

Evidence:

- `plat4m_flash3_application_comparison_v1.json`: SHA-256
  `D5DD5D123E37992252B57B039AB874669503729C5A53CB63E5E704E3212CAE0C`.
- `compare_sct_dat_applications.py`: SHA-256
  `8BF55F99DCE63990315FE51D9C09B3DBB5B208E94BC9C797F7F01C07F6D3A40B`.

## Global HEX/DAT audit

The v2 audit discovered 31 `.hex`/`.dat` files and reduced five byte-identical
archive copies to 26 unique raw artifacts. Classification was:

- 10 Intel HEX download streams;
- 8 text section DAT files, including entryless reconstructed vocoder DAT files;
- 1 `abc`/XOR batch DAT;
- 7 non-section binary/recording/test DAT files.

The canonical artifacts contain 1758 section occurrences, 598 distinct
role-scoped section payloads, and 530 strict metadata groups keyed by chip
generation, application role/run, memory, address, and word count. No group
contains more than one coded type. Consequently:

- mixed-coded metadata groups: 0;
- provenance-backed same-plaintext cross-coded pairs: 0.

The first v1 report classified four entryless vocoder DAT files as non-section
data. It is retained as a diagnostic artifact and must not be used as the final
coverage result. `index_sct_dat.parse_dat(..., require_entry=False)` explicitly
adds the required entryless-DAT path; default indexing still requires an entry.

Evidence:

- final v2 JSON: SHA-256
  `99D5682E766815AF0966ACD9B380528C4DD0B0EEF0E2A62369B8CF678D1D56CE`;
- retained incomplete v1 JSON: SHA-256
  `7443B933C4B8409B7A3C21DF3F1BA06F2965EC95AFD283FC849333F8EEEA7072`;
- global auditor: SHA-256
  `BEF0D146604B28365C3CE239C7CDDB963B62338B97D8F09EC6CFD64F0C44587F`;
- DAT indexer: SHA-256
  `8E4B089810C57CA8FF8B4B814D7DE57CDE30054EBBBB425C9C159F8D659E1C82`.

Bounded conclusion: ciphertext fitting against the old 37 cross-role pairs is
closed. There is not merely a lack of a verified pair; the complete local section
inventory contains no role-scoped address/length group whose coded type changes.

## Uncoded loader dispatcher boundary

The immutable base loader is 2048 bytes, mapped to Boot RAM words `0xF800..0xFBFF`,
SHA-256 `743515B9F7CD90737C1FFF29B652B7558BC5A56487EC38DF4FDEA8877E7A619A`.
The vendor HPI variant changes only `FA2E:44FB -> CBF2`. Under the supported short
branch model, `44FB` returns to `FA2A`; replacing it exposes direct fall-through at
`FA2F`. The region `FA2F..FBFF` is 465 words. This is a reachability boundary, not
a claim that every word is executable or that it does not call earlier helpers.

Within that region, exact documented two-byte controls occur as follows:

```text
80A8 0  81A7 1  88A8 0  89A7 0  8AA8 0
8BA7 0  8CA8 0  8DA7 0  82A3 2  85AB 0
```

All known loader variants have the same three occurrences; the independent BootLED
uncoded program has none. The aligned words containing them cannot yet be called
literal compare constants. The decisive negative evidence is that no full table
of instruction/data/coded controls exists, so dispatch must at least share or
decompose fields instead of independently matching every two-byte value.

The supported short-branch model finds 12 candidates in the fall-through region:
7 backward and 5 forward; 11 targets remain inside the 2 KiB loader. Self-similarity
finds two cloned handler fragments:

- `FB1C` and `FB32` share five words;
- `FB22` and `FB38` share seven words through the identical `04F1` back-branch.

Their immediate suffixes exchange `7CFD/6CF6` with `7CF6/6CFD`. This supports two
parallel handlers, plausibly instruction/data destination paths, but does not yet
identify a coded transform or memory-write mnemonic.

Evidence:

- dispatcher v2 JSON: SHA-256
  `81CDAD8E274FA12923BFC51403E99987EB159A695C7AB5286DBF2DFA8D91CA21`;
- dispatcher analyzer: SHA-256
  `CE836F2719147BEE21BD3ED5F4D1AD3DDD349DAEC38CD5375606316D20ACE726`.

## Open questions and next offline work

1. Build a candidate basic-block graph for `FA2F..FBFF`, keeping branch condition
   names unknown and treating the `FBC5 -> FC30` candidate as a likely false
   classification until the ISA is identified.
2. Separate the cloned `FB1C/FB32` loops from header receive, payload receive,
   destination write, and acknowledgement paths using only shared structure and
   known host transaction lengths.
3. Locate the state that selects coded0/1/2/3. The absence of full control literals
   makes masks/bit extraction the priority; raw word shapes alone are insufficient.
4. Do not design an on-device loader patch until HPI receive, destination write,
   coded-state update, response transport, and bounded rollback are all identified.
