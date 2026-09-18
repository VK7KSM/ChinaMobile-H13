# SCT3288 CustomFlash batch container contract

Date: 2026-08-09 Australia/Sydney

## Scope

This was an offline analysis of the immutable public D890/SCT3288 package and
its already decompiled .NET assemblies. No H13 or TYT device was connected;
no ADB, UART, HPI, PTT, GPIO, PCM, Flash, DAC, or RF operation was performed.
`job.md` was not changed.

## Container algorithm

`SCT3252.GenerateBatchDownloadFileToFlash(type=7)` prepends ASCII `abc` and
XORs every byte of the prior plaintext batch file with `0x76`.
`PDownLoadCustomApplicationFileToFlash()` verifies the three-byte prefix,
reverses the XOR into a temporary file, and splits that file at named batch
headings. The restricted byte alphabet of `CustomFlashFile.dat` is therefore
fully explained by printable section text after a fixed XOR; it is not a
coded-section cipher.

The decoded file contains four batches:

| Heading | Entry | Sections | Payload bytes | Coding |
| --- | ---: | ---: | ---: | --- |
| Modem Flash 1 | `0x0800` | 70 | 94,968 | 2 coded1, 68 coded3 |
| Vocoder1 Flash 4 | none | 13 | 67,776 | 13 coded3 |
| Vocoder2 Flash 16 | none | 19 | 19,496 | 19 coded2 |
| Vocoder6 Flash 23 | none | 1 | 2,320 | 1 coded2 |

All 103 batches remain ordinary Sicomm `i/d address word_count coded_type`
sections. There are no coded0 sections and no additional reader or plaintext
instruction image.

## SCT3288 instruction-bank encoding

The decompiled `Section.GetInfoFromHeadString()` sets `isNewCode=1` only for
instruction word addresses `0x90000..0x9ffff`. `AddHexCodeBuff()` and
`LoadCode()` then change the second load-opcode byte from `A8` to `B8` for
coded1/2/3, producing `88 B8`, `8A B8`, or `8C B8`. The following two address
bytes remain the low 16 bits. The `B8` opcode therefore supplies the
`0x90000` bank; it does not change the coded payload.

Other addresses above `0xffff`, such as `0x4cb50` and `0x6cb50`, use the
normal `A8` command and are represented in the embedded Flash stream only by
their low 16 bits. Their upper context comes from the batch/Flash partition,
not from the six-byte section command. The index records these as
`low16_context` rather than claiming a full-address wire encoding.

## Cross-artifact result

`index_sct_batch_dat.py` validates the magic, decodes the XOR, recognizes all
batch headings, verifies every declared section word count, reconstructs
little-endian payload bytes, and compares section metadata/hash against the
load streams in `CustomFlash.hex` through `CustomFlash3.hex`.

After teaching the stream indexer the three confirmed `B8` opcodes, all four
HEX inputs parse to their run ends. All 103 container sections have a matching
embedded HEX payload under either full-address or explicitly labelled
low-16-bit contextual comparison.

```text
CustomFlashFile.dat SHA-256
4eca851d00d3df81e88c47d87276b94e08e813545fc81bb222766caa7bcca1bb

custom_flash_batch_index_v3.json SHA-256
2bc85a167c0d5b4dd117e6b50135c1204123936200199cad507e167940063ed9

custom_flash_hex_stream_index_v2.json SHA-256
39b9405c430f3d65c2540b27cf168b3b6282eb127dc79dcbccc7d63bf7b38526
```

## Bounded conclusion

Observation: the `abc`/XOR layer is only a reversible batch-file wrapper, and
the enclosed section payloads are byte-identical to the corresponding Flash
HEX load streams.

Inference: this artifact adds real SCT3288 coded2/coded3 samples and clarifies
the new instruction-bank opcode, but it provides no plaintext/ciphertext pair
and no coded1/2/3 decoder. It cannot recover the SCT3258 07BF application by
itself and must not be sent to H13.

Next work returns to the SCT3258 loader/coded-state transform and searches for
same-plaintext cross-coding evidence or a host-side implementation. Device and
RF testing remains prohibited.
