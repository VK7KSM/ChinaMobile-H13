| 命令 | 等级 | 取参 | 实现例程 |
|---|---|---|---|
| `get2571lockflag` | 只读 | 0 | `0x0800b73c` |
| `getchandcnt` | 只读 | 0 |  |
| `getsleepstat` | 只读 | 0 |  |
| `getslotint` | 只读 | 0 |  |
| `getsm` | 只读 | 0 | `0x0801e07c` |
| `gettick` | 只读 | 0 |  |
| `hobibstat` | 只读 | 0 | `0x0800e470` `0x0800c408` |
| `memread` | 只读 | 1 |  |
| `nandgetfeature` | 只读 | 1 | `0x0800e878` |
| `nandlistbadblock` | 只读 | 0 | `0x0800eb8c` |
| `nandreadmain` | 只读 | 1 | `0x0800ef70` `0x08012d5c` |
| `nandreadspare` | 只读 | 1 | `0x0800f0b8` `0x08012d5c` |
| `print` | 只读 | 1 |  |
| `ramlog` | 只读 | 0 | `0x08012a1c` |
| `read2571` | 只读 | 1 | `0x0800b7a8` |
| `readeeprom` | 只读 | 1 | `0x0800e340` `0x08012d5c` |
| `readreg17val` | 只读 | 0 | `0x0801e088` |
| `sct3258cidsn` | 只读 | 0 | `0x0800a044` |
| `sct3258getoobe` | 只读 | 1 | `0x08006194` `0x0800e6a0` `0x0801aaf0` |
| `sct3258hwver` | 只读 | 0 | `0x0800a0ec` |
| `sct3258prostr` | 只读 | 0 | `0x0800a194` |
| `sct3258read` | 只读 | 0 | `0x0800ffc8` `0x08023a28` |
| `sct3258read1` | 只读 | 0 | `0x0800ffc8` |
| `sct3258swver` | 只读 | 0 | `0x0800a23c` |
| `showcurrsq` | 只读 | 0 |  |
| `showsyscfg` | 只读 | 0 | `0x08007afc` |
| `showtestpara` | 只读 | 0 | `0x08007cd8` |
| `version` | 只读 | 0 |  |
| `sct3258anaenterrx` | 进接收态 | 0 | `0x08007ff4` |
| `sct3258anainitcfg` | 进接收态 | 1 | `0x08008050` |
| `sct3258anarxstart` | 进接收态 | 1 | `0x08008108` |
| `sct3258anarxstop` | 进接收态 | 0 | `0x0800811a` |
| `sct3258enterrx` | 进接收态 | 1 | `0x0800889c` |
| `sct3258initcfg` | 进接收态 | 1 | `0x08008908` |
| `sct3258rxstart` | 进接收态 | 1 | `0x080089c8` |
| `sct3258rxstop` | 进接收态 | 0 | `0x080089da` |
| `2571cectrl` | 改状态 | 1 | `0x0800b754` |
| `bypassctrl` | 改状态 | 1 |  |
| `codecresethigh` | 改状态 | 0 |  |
| `codecresetlow` | 改状态 | 0 |  |
| `dropvoice` | 改状态 | 1 |  |
| `earadjustval` | 改状态 | 1 |  |
| `int0ctrl` | 改状态 | 1 |  |
| `memwrite1` | 改状态 | 1 |  |
| `memwrite2` | 改状态 | 1 |  |
| `memwrite4` | 改状态 | 1 |  |
| `meshswitchval` | 改状态 | 1 | `0x08006204` `0x0800e484` `0x08010a00` |
| `pllctrl` | 改状态 | 1 | `0x0800f5e4` `0x0800f534` |
| `sct3258anacallstop` | 改状态 | 0 | `0x08007fdc` |
| `sct3258anacfg` | 改状态 | 1 | `0x0801acbc` |
| `sct3258callstop` | 改状态 | 0 | `0x08008878` |
| `sct3258chgpro` | 改状态 | 1 | `0x0800ffb4` |
| `sct3258codecsel` | 改状态 | 1 | `0x080092f0` |
| `sct3258dcoffset` | 改状态 | 1 | `0x0801b880` |
| `sct3258dmroffset` | 改状态 | 1 | `0x0801c444` |
| `sct3258dspreset` | 改状态 | 0 | `0x0801ff84` |
| `sct3258micgain` | 改状态 | 1 | `0x0800fc14` |
| `sct3258reset` | 改状态 | 0 | `0x08010290` |
| `sct3258send1` | 改状态 | 1 | `0x08012d5c` `0x08010560` |
| `sct3258send2` | 改状态 | 1 | `0x08012d5c` `0x08010560` |
| `sct3258setoobe` | 改状态 | 1 | `0x08019cb4` |
| `sct3258sleep` | 改状态 | 0 | `0x080089ea` |
| `sct3258vosel` | 改状态 | 1 | `0x0800a73c` |
| `sct3258wakeup` | 改状态 | 0 | `0x0800a9f8` |
| `sctsleep` | 改状态 | 0 | `0x08020388` |
| `sctwakeup` | 改状态 | 0 | `0x08021880` |
| `set2571chargepump` | 改状态 | 2 | `0x08023a14` `0x0800b7d0` `0x08012a1c` |
| `set2571freq` | 改状态 | 1 | `0x0800f6d8` `0x0800f624` |
| `set2571multi` | 改状态 | 1 | `0x08023a14` `0x0800b390` |
| `set2571postdiv` | 改状态 | 1 | `0x08023a14` `0x0800b390` |
| `set2571prediv` | 改状态 | 1 | `0x08023a14` `0x0800b390` |
| `set2571rxfreq` | 改状态 | 1 | `0x0800f624` |
| `set2571tr` | 改状态 | 1 | `0x0800b9d0` `0x0800b9dc` |
| `setfreq` | 改状态 | 1 | `0x0800f6d8` `0x0800f624` |
| `setmicgain` | 改状态 | 1 | `0x0800fc14` |
| `setsleeptime` | 改状态 | 1 |  |
| `setsqlevelformat` | 改状态 | 1 |  |
| `timer3start` | 改状态 | 0 | `0x0800e6e8` `0x080109f0` `0x08023a14` |
| `write2571` | 改状态 | 1 | `0x0800b9e8` |
| `zerovoice` | 改状态 | 0 |  |
| `dacset` | 会开射频 | 1 | `0x0800e098` |
| `pttctrl` | 会开射频 | 1 |  |
| `rfswoff` | 会开射频 | 0 | `0x0800e6d8` |
| `rfswon` | 会开射频 | 0 | `0x0800e6e8` |
| `sct3258anacallstart` | 会开射频 | 0 | `0x08007fc0` |
| `sct3258anaentertx` | 会开射频 | 1 | `0x0800802c` |
| `sct3258callstart` | 会开射频 | 0 | `0x08008840` |
| `sct3258dc13calstart` | 会开射频 | 0 | `0x0800812c` |
| `sct3258dc17calstart` | 会开射频 | 0 | `0x08008250` |
| `sct3258dmrcallstart` | 会开射频 | 1 | `0x08006162` `0x08010560` `0x08023a14` |
| `sct3258entertx` | 会开射频 | 1 | `0x080088d8` |
| `sct3258pwrval` | 会开射频 | 1 | `0x0800e098` |
| `setdbslottx` | 会开射频 | 1 |  |
| `txvcovccoff` | 会开射频 | 0 | `0x0800e748` |
| `txvcovccon` | 会开射频 | 0 | `0x0800e754` |
| `ddreboot` | 写非易失 | 0 | `0x0800e7b4` |
| `nanderaseblock` | 写非易失 | 1 | `0x0800eb44` |
| `nandmarkbadblock` | 写非易失 | 1 | `0x0800ecfc` |
| `nandwritepage` | 写非易失 | 1 | `0x08006186` `0x0800eda2` |
| `sct3258dspupdate` | 写非易失 | 0 | `0x0800ffb4` `0x0800fff8` `0x08023a14` |
| `writeeeprom` | 写非易失 | 1 | `0x08006186` `0x0800e3a6` |
