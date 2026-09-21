| 命令 | 等级 | 取参 | 输出 | 实现例程 |
|---|---|---|---|---|
| `get2571lockflag` | 只读 | 0 | `get2571lockflag %d cmp` | `0x0800b73c` |
| `getchandcnt` | 只读 | 0 | `supply zero chand count:%d` `getchandcnt cmp` |  |
| `getsleepstat` | 只读 | 0 | `getsleepstat cmp` |  |
| `getslotint` | 只读 | 0 | `slotint %d` |  |
| `getsm` | 只读 | 0 | `sm:0x%02x` `getsm cmp` | `0x0801e07c` |
| `gettick` | 只读 | 0 | `tick: %u` |  |
| `hobibstat` | 只读 | 0 | `hobibstat:0x%02x` | `0x0800e470` `0x0800c408` |
| `memread` | 只读 | 1 | `%02x` |  |
| `nandgetfeature` | 只读 | 1 | `nandgetfeature 0x%02x 0x%02x cmp` | `0x0800e878` |
| `nandlistbadblock` | 只读 | 0 | `nandlistbadblock cmp` | `0x0800eb8c` |
| `nandreadmain` | 只读 | 1 | `nandreadmain cmp %d` | `0x0800ef70` `0x08012d5c` |
| `nandreadspare` | 只读 | 1 | `nandreadspare cmp %d` | `0x0800f0b8` `0x08012d5c` |
| `print` | 只读 | 1 | — |  |
| `ramlog` | 只读 | 0 | — | `0x08012a1c` |
| `read2571` | 只读 | 1 | `read2571 0x%02x:0x%02x cmp` | `0x0800b7a8` |
| `readeeprom` | 只读 | 1 | `readeeprom cmp` | `0x0800e340` `0x08012d5c` |
| `readreg17val` | 只读 | 0 | `reg17val:0x%04x` `readreg17val cmp` | `0x0801e088` |
| `sct3258cidsn` | 只读 | 0 | — | `0x0800a044` |
| `sct3258getoobe` | 只读 | 1 | `%04x` `avg : %04x` `sct3258setoobe cmp` | `0x08006194` `0x0800e6a0` `0x0801aaf0` |
| `sct3258hwver` | 只读 | 0 | — | `0x0800a0ec` |
| `sct3258prostr` | 只读 | 0 | — | `0x0800a194` |
| `sct3258read` | 只读 | 0 | `sct3258 read cmp:0x%02x 0x%02x` | `0x0800ffc8` `0x08023a28` |
| `sct3258read1` | 只读 | 0 | `sct3258 read cmp:0x%02x` | `0x0800ffc8` |
| `sct3258swver` | 只读 | 0 | — | `0x0800a23c` |
| `showcurrsq` | 只读 | 0 | — |  |
| `showsyscfg` | 只读 | 0 | `showsyscfg cmp` | `0x08007afc` |
| `showtestpara` | 只读 | 0 | `showtestpara cmp` | `0x08007cd8` |
| `version` | 只读 | 0 | `16:03:16` `Aug 23 2022` `version: %s %s` |  |
| `sct3258anaenterrx` | 进接收态 | 0 | `sct3258anainitcfg cmp` | `0x08007ff4` |
| `sct3258anainitcfg` | 进接收态 | 1 | `sct3258anainitcfg cmp` | `0x08008050` |
| `sct3258anarxstart` | 进接收态 | 1 | `sct3258anarxstart cmp` | `0x08008108` |
| `sct3258anarxstop` | 进接收态 | 0 | `sct3258anarxstop cmp` | `0x0800811a` |
| `sct3258enterrx` | 进接收态 | 1 | `sct3258enterrx cmp` | `0x0800889c` |
| `sct3258initcfg` | 进接收态 | 1 | `sct3258initcfg cmp` | `0x08008908` |
| `sct3258rxstart` | 进接收态 | 1 | `sct3258rxstart cmp` | `0x080089c8` |
| `sct3258rxstop` | 进接收态 | 0 | `sct3258rxstop cmp` | `0x080089da` |
| `2571cectrl` | 改状态 | 1 | `2571cectrl cmp` | `0x0800b754` |
| `bypassctrl` | 改状态 | 1 | `bypassctrl cmp` |  |
| `codecresethigh` | 改状态 | 0 | `codecresethigh cmp` |  |
| `codecresetlow` | 改状态 | 0 | `codecresetlow cmp` |  |
| `dropvoice` | 改状态 | 1 | `dropvoice %d cmp` |  |
| `earadjustval` | 改状态 | 1 | `earadjustval %d cmp` |  |
| `int0ctrl` | 改状态 | 1 | `int0ctrl cmp` |  |
| `memwrite1` | 改状态 | 1 | `memwrite1 0x%x 0x%x` |  |
| `memwrite2` | 改状态 | 1 | `memwrite2 0x%x 0x%x` |  |
| `memwrite4` | 改状态 | 1 | `memwrite1 0x%x 0x%x` |  |
| `meshswitchval` | 改状态 | 1 | `meshswitchval %d cmp` `len = %d    err command:  %s` | `0x08006204` `0x0800e484` `0x08010a00` |
| `pllctrl` | 改状态 | 1 | `pllctrl cmp` | `0x0800f5e4` `0x0800f534` |
| `sct3258anacallstop` | 改状态 | 0 | `sct3258anacallstop cmp` | `0x08007fdc` |
| `sct3258anacfg` | 改状态 | 1 | `sct3258anacfg cmp` | `0x0801acbc` |
| `sct3258callstop` | 改状态 | 0 | `sct3258callstop cmp` | `0x08008878` |
| `sct3258chgpro` | 改状态 | 1 | `sct3258 change protocol cmp` | `0x0800ffb4` |
| `sct3258codecsel` | 改状态 | 1 | `sct3258 codec sel cmp` | `0x080092f0` |
| `sct3258dcoffset` | 改状态 | 1 | `sct3258dcoffset cmp` | `0x0801b880` |
| `sct3258dmroffset` | 改状态 | 1 | `sct3258dmroffset cmp` | `0x0801c444` |
| `sct3258dspreset` | 改状态 | 0 | — | `0x0801ff84` |
| `sct3258micgain` | 改状态 | 1 | `sct3258micgain cmp` | `0x0800fc14` |
| `sct3258reset` | 改状态 | 0 | `sct3258 reset cmp` | `0x08010290` |
| `sct3258send1` | 改状态 | 1 | `sct hpi send:` `sct3258send %d cmp` | `0x08012d5c` `0x08010560` |
| `sct3258send2` | 改状态 | 1 | `sct hpi send:` `sct3258send %d cmp` | `0x08012d5c` `0x08010560` |
| `sct3258setoobe` | 改状态 | 1 | `sct3258setoobe cmp` | `0x08019cb4` |
| `sct3258sleep` | 改状态 | 0 | `sct3258sleep cmp` | `0x080089ea` |
| `sct3258vosel` | 改状态 | 1 | `sct3258 vocoder sel cmp` | `0x0800a73c` |
| `sct3258wakeup` | 改状态 | 0 | `sct3258wakeup cmp` | `0x0800a9f8` |
| `sctsleep` | 改状态 | 0 | `sctsleep cmp` | `0x08020388` |
| `sctwakeup` | 改状态 | 0 | `sctwakeup cmp` | `0x08021880` |
| `set2571chargepump` | 改状态 | 2 | `set2571chargepump %d cmp` `ramlog` | `0x08023a14` `0x0800b7d0` `0x08012a1c` |
| `set2571freq` | 改状态 | 1 | `set2571freq %d %d cmp` | `0x0800f6d8` `0x0800f624` |
| `set2571multi` | 改状态 | 1 | `set2571multi %d cmp` | `0x08023a14` `0x0800b390` |
| `set2571postdiv` | 改状态 | 1 | `set2571postdiv %d cmp` | `0x08023a14` `0x0800b390` |
| `set2571prediv` | 改状态 | 1 | `set2571prediv %d cmp` | `0x08023a14` `0x0800b390` |
| `set2571rxfreq` | 改状态 | 1 | `set2571rxfreq %d cmp` | `0x0800f624` |
| `set2571tr` | 改状态 | 1 | `set2571tr %dcmp` | `0x0800b9d0` `0x0800b9dc` |
| `setfreq` | 改状态 | 1 | — | `0x0800f6d8` `0x0800f624` |
| `setmicgain` | 改状态 | 1 | `setmicgain 0x%02x cmp` | `0x0800fc14` |
| `setsleeptime` | 改状态 | 1 | `setsleeptime cmp` |  |
| `setsqlevelformat` | 改状态 | 1 | `setsqlevelformat cmp` |  |
| `timer3start` | 改状态 | 0 | `timer3start %d cmp` | `0x0800e6e8` `0x080109f0` `0x08023a14` |
| `write2571` | 改状态 | 1 | `write2571 0x%02x 0x%02x cmp` | `0x0800b9e8` |
| `zerovoice` | 改状态 | 0 | — |  |
| `dacset` | 会开射频 | 1 | `dacset %d cmp` | `0x0800e098` |
| `pttctrl` | 会开射频 | 1 | `pttctrl cmp` |  |
| `rfswoff` | 会开射频 | 0 | `rfswoff cmp` | `0x0800e6d8` |
| `rfswon` | 会开射频 | 0 | `rfswon cmp` | `0x0800e6e8` |
| `sct3258anacallstart` | 会开射频 | 0 | `sct3258anacallstart cmp` | `0x08007fc0` |
| `sct3258anaentertx` | 会开射频 | 1 | `sct3258anaentertx cmp` | `0x0800802c` |
| `sct3258callstart` | 会开射频 | 0 | `sct3258callstart cmp` | `0x08008840` |
| `sct3258dc13calstart` | 会开射频 | 0 | `sct3258dc13calstart cmp` | `0x0800812c` |
| `sct3258dc17calstart` | 会开射频 | 0 | `sct3258dc17calstart %04x cmp` | `0x08008250` |
| `sct3258dmrcallstart` | 会开射频 | 1 | `sct3258dmrcallstart cmp` | `0x08006162` `0x08010560` `0x08023a14` |
| `sct3258entertx` | 会开射频 | 1 | `sct3258entertx cmp` | `0x080088d8` |
| `sct3258pwrval` | 会开射频 | 1 | `sct3258pwrval cmp` | `0x0800e098` |
| `setdbslottx` | 会开射频 | 1 | `setdbslottx cmp` |  |
| `txvcovccoff` | 会开射频 | 0 | `txvcovccoff cmp` | `0x0800e748` |
| `txvcovccon` | 会开射频 | 0 | `txvcovccon cmp` | `0x0800e754` |
| `ddreboot` | 写非易失 | 0 | `ddrebooting ..` | `0x0800e7b4` |
| `nanderaseblock` | 写非易失 | 1 | `nanderaseblock cmp %d` | `0x0800eb44` |
| `nandmarkbadblock` | 写非易失 | 1 | `nandmarkbadblock cmp` | `0x0800ecfc` |
| `nandwritepage` | 写非易失 | 1 | `nandwritepage cmp %d` | `0x08006186` `0x0800eda2` |
| `sct3258dspupdate` | 写非易失 | 0 | `sct3258 boot OK` `sct3258 boot Failed` `sct3258dspupdate cmp` | `0x0800ffb4` `0x0800fff8` `0x08023a14` |
| `writeeeprom` | 写非易失 | 1 | `writeeeprom cmp` | `0x08006186` `0x0800e3a6` |
