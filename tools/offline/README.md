# 离线工具

不接触设备的分析、建模与判据工具。设备侧流程在 `../device/`。

## 一条命令跑回归

```bash
python run_all_selftests.py
```

覆盖五个模型的全部用例，外加两项工具冒烟检查。改动任何模型后先跑这个。

入口脚本是 `run_all_selftests.py`；它同时汇总模型用例数与工具冒烟结果，
**用例数以它的输出为准**，不要手数。

## 判据与分析

| 工具 | 用途 |
|---|---|
| `audit_capture.py` | 对一次捕获逐条核对已确立的不变量。已接入 `run_candidate.sh`，每次会话自动执行 |
| `compare_frames.py` | 帧级比对：对齐后给出误码率、覆盖率、首处不一致。SDR 闭环的判分端 |
| `call_stats.py` | 单次通话的供数节拍、丢帧、延迟分布 |
| `stage_breakdown.py` | 会话各阶段耗时。**只用主机写出事件计时**，证据落盘时间不可用于耗时判断 |
| `summarize_capture.py` | 捕获目录概览 |
| `analyze_session_uplink.py` | 上行原件分析 |
| `compare_received_audio.py` | 接收录音与参照音频分段比对，给出从第几段开始劣化 |
| `scan_relay_frames.py` | 扫描供数帧，用于核对线上帧结构 |

## 编解码

| 工具 | 用途 |
|---|---|
| `chan_d_to_params.py` | 剥信道编码：空口帧 → 未编码 49 位参数。**网关必需**，模块要的是参数不是空口帧 |
| `parse_link_control.py` | 解析呼叫类型、被叫、主叫；单独标出加密标志 |
| `deinterleave_frames.py` | 交织还原 |
| `verify_voice_formats.py` | 语音格式核验 |
| `stitch_tape.py` | 录音磁带快照按重叠拼接 |

## 服务层模型（带用例，服务实现的验收标准）

这五个模型在 `probes/h13_dmr_tx_harness/app/src/main/java/net/elfradio/h13radio/`
下各有一个 Java 实现，**同一组行为在两边都有用例**：模型定行为、Java 跑设备，
一旦分叉就会被用例抓住。

| 模型 | 规则要点 |
|---|---|
| `arbitration_model.py` | 单工仲裁：接收优先、本地优先于网络、发射时限、尾音期 |
| `jitter_buffer_model.py` | 抖动缓冲：欠载补静音**绝不断流**、过载保新弃旧 |
| `talkgroup_model.py` | TG 静态/动态订阅、Last Heard 逐帧聚合 |
| `power_calibration.py` | 功率标定：**标定点不足时拒绝外推** |
| `radio_service_contract.py` | 串起以上四者的接口契约，重点是拒绝的时机 |
| `latency_model.py` | 端到端延迟预算 |

## 素材生成

| 工具 | 用途 |
|---|---|
| `make_probe_tones.py` | 单音阶梯与对数扫频，用于找声码器失锁边界 |
| `make_morse_test_audio.py` | 26 字母加 10 数字，便于逐项核对丢失位置 |
| `make_speech_test_audio.ps1` / `make_speech_short_abc_audio.ps1` | 语音素材 |

全部内置单次发射 30 秒上限校验。

## 固件与设备侧工具（不在本目录，列在这里便于索引）

| 工具 | 用途 |
|---|---|
| `../firmware/fw_xref.py` | 固件静态分析：按字符串找引用（字面量池与 **ADR** 两路，只扫字面量池会漏掉绝大多数调试字符串）、定位函数边界、找 BL 调用者、反汇编 |
| `../firmware/dump_mcu_console.py` | 导出 MCU 调试控制台整表：101 条命令的等级、取参、输出格式串、实现例程与 SRAM 地址 |
| `../firmware/dump_at_commands.py` | 导出 AT 命令表（35 条）与模块主动上报消息（8 条） |
| `../emulator/trace_sct_seq.py` | 在模拟器里直调固件入口，截取发往 SCT3258 的每条 HPI 包 |
| `../device/mcu_console.sh` | MCU 调试控制台客户端。只读白名单、`--rx` 才放行接收类、发射类一律不放行；下发期间持续采样 `pa_enable` |
| `../device/batch_repeatability.sh` | 零射频复现性批次 |
| `../device/at_timing.sh` | AT 命令往返计时，含信道/TG 切换代价；带 PA 看护 |

`mcu_console.sh` 的由来见 H13_new.md 2.9.44：固件自带约一百条控制台命令，
一直挂在我们用的那条串口上，`memread`/`memwrite` 只是其中三条。

## 几条反复踩过的坑

1. **证据落盘时间不等于事件发生时间**——热路径会推迟写入，用它算耗时会
   把 0.2 秒的呼叫头算成 13.7 秒。
2. **单一来源的计数不足以判断**——宿主计数可能因会话后段失败而归零，
   设备侧其实已写满，必须两侧并看。
3. **改了源码要出包才生效**——否则测的是旧版本。
4. **后台批次要互斥**——两个批次争抢设备会让结果交错作废。
5. **跑 gradle 要先设 `JAVA_HOME`**——系统 PATH 里没有 java，
   用 `C:\Users\x\.jdks\jdk-17.0.20.1+1`（Windows 路径形式）。
