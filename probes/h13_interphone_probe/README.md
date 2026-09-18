# H13 Interphone Probe

Read-only investigation probes for the H13/MAX11 DMR module.

The current v0.58 entry uses the verified bounded mode0 UART-HPI bridge to enter
the vendor PCM control state, verifies the status-zero ACKs, and sends exactly
one type-2 engineering `SPEECH_SAMPLES` packet containing 640 zero-valued
16-bit samples.
The 1283-byte payload remains encoded as length `0x0503`; one trailing zero is
appended outside LENGTH so the complete HPI transaction has an even byte count.
It stops on the first complete HPI response, sends no second PCM packet, closes
the route, returns to idle, and reloads mode0. Historical system-DPMR
`DPMRREADY`/Flash probes remain in the source but are not selected by the
current `sct_flash_dump` entry. This selected path sends no call/PTT, RF,
channel, Flash, `READ_MEM`, or `0x93` command.

The app reuses the factory `libserial_port.so`, opens `/dev/ttyHS0` at 57600 baud,
enables the module clock, and uses commands recovered from the H13 module firmware.
Historical versions include RSSI, memory-read, and Flash-dump probes. The current
live CHAN_D probe uses:

```text
AT+DMOGETSOFTVERSION
AT+DMOGETDIGITALCH
AT+DMOSETRECORDSW=1,<current tape cursor>
print 1 / memread / print 0
```

The module requires its normal `+DMOSTARTUP:0` / `AT+DMOCONNECT` session
handshake before it accepts those queries, so the probe performs that handshake
first. It captures every response and always closes the port. It does not register
PTT key receivers, enter test mode, send PTT, or use memory/Flash/NAND/calibration
write commands. Since v0.14 it may restore the already approved CH1 as volatile
module state when an MCU/clock restart has returned the module to its default channel.

`DMOGETDIGITALRXINFO` and `DMOGETMESGINFO` return cached receive/message
status. They do not start reception, transmission, test mode, or a calibration
operation.

Version 0.10 uses the subsequently verified `print 1` / `memread` path to take a
bounded snapshot of the current firmware's NAND tape RAM buffer and CHAN_D TX
queue. Versions 0.11 onward perform a live RX snapshot after enabling the same
recording state used by the factory app. Version 0.12 reflects the firmware's
exact RAM layout: the 44-byte management header is followed by the little-endian
block marker `0xa1b2c3d4`, and complete CHAN_D units begin at `tape + 0x30`.
It validates the 27/36-byte unit length, marker, data bounds, and header coherence.
Version 0.13 waits until a non-empty tape block has stopped growing for 800 ms,
then captures that complete block. This avoids v0.12's intentionally early
three-unit snapshot and provides enough consecutive frames for offline audio
decoding. A metadata/block transition restarts the stable-period check.
Version 0.14 also handles a module-clock restart deterministically: if the module
reports its factory-default digital channel, the probe applies the already
approved H13 CH1 (`433.550 MHz / ID13 / TG99 / TS1 / CC8 / low / RX group99`)
and requires an exact readback before opening the capture window. It does not
write the Interphone database or change any other channel.
Version 0.15 determines end-of-call from the unit count remaining unchanged for
800 ms instead of requiring every other management-header byte to remain equal
between polls. The final before/data/after snapshot still requires identical
metadata and count. Every count transition is logged, and a timeout reports the
maximum observed count and last header, so a missed RF reception is distinct
from a snapshot-coherence failure.
Version 0.16 reflects the observed end-of-call rollover: the module commits the
voice block and resets the active count immediately instead of leaving a stable
completed block. It therefore snapshots during RX after at least 18 CHAN_D
units (about 1.08 seconds) are present. The final before/after headers must still
describe the same block, while the count may only increase during the read.
Version 0.17 turns the one-shot snapshot into a bounded stream capture. Fixed-size
`memread` calls return as soon as their expected payload is parsed instead of
waiting an additional serial quiet period. The probe reads only units added since
the previous committed count, validates the same tape generation before and after
each read, and writes a binary stream plus a CSV chunk/generation index. A torn or
cross-generation read is rejected instead of silently joining unrelated data.
It waits up to 90 seconds for RX, captures for at most 15 seconds after the first
valid units, and stops after 1.2 seconds without new units.
Version 0.18 reuses each verified after-header as the next before-header when
there are already unread units, avoiding one serial round trip while catching up.
When a generation changes during a tail read, it preserves the rejected bytes
and performs a second read only while the new generation count is still below
that high-address range. A tail is recovered into the indexed stream only when
both reads match, the new metadata remains stable, the range is still not
overwritten, the bytes are nonzero, and every 9-byte DMR frame has the observed
zero high nibble in its final byte. All boundary candidates, rechecks, and the
decision fields are also saved separately for offline audit.

Version 0.19 adds an intent-selected `ram_survey` mode for the SCT3258 control
work. It performs the normal module handshake, enables the already verified
diagnostic print gate, reads `0x20000000..0x200055ff` in 256-byte chunks through
`memread`, repeats the first 512 bytes before and after the survey, saves all
three binary artifacts, restores `print 0`, and verifies that the module still
answers its version query. This mode does not run the CHAN_D capture and never
uses `memwrite`, PTT, loader switching, or any Flash/NAND write command.

Version 0.20 adds a separately selected `ram_exec_proof` mode. It first proves a
single reversible 32-bit RAM write and restoration in the idle tape buffer. It
then uploads a 32-byte Cortex-M0 routine, verifies every byte, and points only
the SRAM SysTick vector at that routine. The routine restores the original
SysTick vector before writing the `HPI1` marker and returning. The app verifies
the vector, marker, temporary-window restoration, diagnostic-log restoration,
and final module version response. Both normal and exception cleanup paths write
back the original vector and backed-up RAM contents. No MCU or SCT3258 Flash is
changed, and this proof does not access HPI or transmit RF.

Version 0.27 adds the separately selected `segmented_baseline_loader_proof` mode.
It verifies MCU 0.3.66 and mode0, reads the resident 2048-byte base loader from
MCU Flash, requires its known SHA-256, then uses the factory `sct3258reset` and
64 bounded 32-byte `sct3258send2` commands to reload the same baseline image.
Every segment must return the factory completion token before the next is sent.
The proof requires startup, AT reconnect, version, and mode0 readback afterward;
on failure after reset it issues at most one already verified `sct3258chgpro 0`
recovery. It does not issue PTT, RF transmit, HPI Flash read, erase, or program
commands.

Version 0.28 keeps the v0.27 loader, 32-byte segment, timeout, and recovery
conditions unchanged, but adds a fixed 50 ms delay after each factory completion
token. This gives the MCU UART command handler time to finish its completion print
and return to the receive loop before the next text command arrives. Every segment
is logged before transmission with its index, offset, length, and SHA-256.

`DMOSETRECORDSW` and the v0.14 CH1 restore change volatile module state. The
probe never sends PTT, `memwrite`, flash programming, EEPROM, NAND-write,
upgrade, or calibration commands.

Only run it while the active `net.elfradio.h13interphone` service is fully
stopped. Android 8.1 can schedule one sticky-service restart after the first
`am force-stop`, so wait briefly, stop it a second time if it reappears, and
verify that the process is absent. Two processes must not access the radio
serial port at once.
