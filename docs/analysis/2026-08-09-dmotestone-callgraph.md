# Current 0.3.66 DMOTESTONE call graph

Date: 2026-08-09 +10:00

## Scope

This is a read-only analysis of `Module_current_0.3.66.bin` and its fixed Thumb disassembly. No device, Probe, UART, HPI, PTT, PCM/data, or RF action was used.

## Command dispatch

The current-image command strings and dispatch edges are:

```text
DMOTESTONE=       @ 0x08007718
  compare block   @ 0x08007484
  handler         -> 0x08020F60

DMOTESTWRITEITEM= @ 0x08007724
  compare block   @ 0x0800749A
  wrapper         -> 0x080216C8(..., 1) -> 0x0802C504

DMOTESTWRITE=     @ 0x08007738
  compare block   @ 0x080074B2
  wrapper         -> 0x080216C8(..., 0) -> 0x0802C504
```

The `DMOTESTONE` switch helper is called at `0x08020FA4`. Its inline table at `0x08020FA8` has count 8 and offsets `05 14 24 34 44 54 64 75 8A`. Decoding that table gives:

```text
item 16 -> 0x0802BE18(input, store=0)          FreqCalibrate
item 17 -> 0x0802C348(input, store=0, kind=1)  PowerHigh
item 18 -> 0x0802C348(input, store=0, kind=0)  PowerLow
item 19 -> 0x0802C080(input, store=0, kind=1)  FreqOffsetDigit
item 20 -> 0x0802C080(input, store=0, kind=0)  FreqOffsetAnalog
item 21 -> 0x0802B2A4(input, store=0, kind=0)  AnalogSQNarrow
item 22 -> 0x0802B2A4(input, store=0, kind=1)  AnalogSQWide
item 23 -> 0x08019FFC                           SCT3811 offset search
default -> invalid-item report
```

The common `DMOTESTWRITE*` parser at `0x0802C504` calls the same item handlers with `store=1`: `0x0802BE18` at `0x0802C6DA`, `0x0802C348` at `0x0802C708/0x0802C736`, and `0x0802C080` at `0x0802C764/0x0802C794`. Those storage paths update the 412-byte calibration working copy and do not take the immediate `0x0802B108` path.

## 0x0802B108 ownership

All current-image direct callers are now assigned:

```text
0x0802BFE2 inside 0x0802BE18  DMOTESTONE item 16 immediate FreqCalibrate
0x0802C272 inside 0x0802C080  DMOTESTONE item 19/20 immediate frequency offset
0x0802C42C inside 0x0802C348  DMOTESTONE item 17/18 immediate power test
```

`0x0802B108` itself reads `0x200017DD`. If that external-TX lifecycle byte equals 2 it returns success without starting the stock path. Otherwise it calls `0x08013A44(1)`. On success it copies the tick word at `0x200000D0` to `0x20000404` and clears `0x20000408`.

`0x08013A44(1)` first calls `0x08014124(1)` for route/work-mode/power and RF preparation. It then conditionally sends `DMR_CALL_START(0x78)` when the active profile `+1` byte is zero. Therefore `0x0802B108` is a guarded stock TX/call-start wrapper, not merely a channel/calibration value reapply helper.

The production `DMOPTT=` command remains independently dispatched to `0x08029F5C`. There is no direct edge from that handler to `0x0802B108`; both paths may still share deeper stock state-machine functions.

## Bounded conclusion

The three `0x0802B108` call sites belong exclusively to immediate hidden calibration operations for items 16 through 20 in the recovered direct graph. `DMOTESTWRITE*` storage operations avoid them. This separates calibration-record editing from immediate RF-producing tests, but it does not make any hidden test command safe to run on a device.
