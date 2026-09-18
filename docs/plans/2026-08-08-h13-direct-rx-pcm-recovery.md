# H13 SCT3258 直接 RX PCM 恢复计划

日期：2026-08-08  
目标设备：H13/MAX11，MCU `0.3.66`，SCT `V2.01.07I3`，Android 8.1  
目标：证明并打通 SCT3258 DMR 解码器输出的真实 8 kHz PCM。`CHAN_D -> mbelib` 保留为阳性对照和当前可用路径，但不替代本目标。

## 1. 当前结论

1. `type=0x30, field=0x00, count=160` 是稳定的 20 ms、16-bit-like 数据流；wire328 walker 已成立。
2. 它在现有 `VOCODER_IO_SET=0x01`、呼叫建立后切路由的流程中不包含可识别人声，因此不能再称为 decoded RX PCM。
3. v0.90 的 244 个 `CHAN_D` 单元可稳定解码为约 14.6 s 清晰人声，已经提供同一设备、同一 DMR 模式的阳性对照和已知编码语音输入。
4. 厂家 GUI 的 DMR 接收顺序是先确定 `VOCODER_IO_SET`，再执行 `WORK_MODE_DUPLEX -> PROCESS_MODE -> CARRIER_LOST -> CARRIER_READY`。当前 Probe 在 tape 过门、呼叫已经活动后才写 `0x01`，路由建立时机不一致，是最高优先级根因假设。
5. 2015 Packet Interface 将 bits 1:0 标为保留；后期 GUI 才把它们解释为 Voice OUT。必须证明 H13 `0.3.66` 的固件扩展语义，不能只引用 GUI 名称。
6. v0.91 已在真实 RF/tape 阳性条件下测试“呼叫活动后补 `PROCESS_MODE(2) -> VOCODER_IO_SET=0x01`”：600 个 LENGTH=323 帧仍为底噪，LENGTH=1283 为 0。该分支已经否证，不再重复。
7. v0.91 捕获结束后出现一次 `restore-57600` stub 上传校验失败。Interphone 最终重新持有 UART 且 GPIO 恢复，但这不等于 Probe 的 transport/control recovery 完整 PASS；下一设备里程碑前必须先修复并验证恢复路径。

## 2. 硬性验收标准

下列项目全部满足才可写“直接 RX PCM PASS”：

- 原始 HPI 帧、命令 ACK、route/work/process 状态和时间戳完整保存；
- 输出为连续、可复算的 8 kHz 单声道样本；
- 对已知输入满足至少一个内容判据：
  - 已知音调在正确频率形成明显谱峰；
  - 与参考 PCM 对齐后的归一化相关显著高于静音/错位对照；
  - 操作者确认语音可懂；
- 静音输入和语音输入的帧能量、谱形或相关性存在可重复差异；
- Probe 退出后 Interphone、UART owner、GPIO、CH1、Wi-Fi 和正常 DMR 收发恢复。

以下内容只算结构 PASS，不能算语音 PASS：合法 header、帧数正确、WAV 时长正确、RMS 非零、归一化后有声音。

## 3. 里程碑

### M0：冻结证据与术语

无设备操作。

- 保持 r3、v0.84、v0.90 `CHAN_D` 和所有派生产物不变；
- 将 type30 统一称为 `PCM-like stream`，直到内容阳性对照通过；
- 建立离线判据：每帧 RMS、零值比例、短时谱、lag correlation、重复帧、已知输入相关性；
- 禁止再用 RMS、时长或合法 WAV 单独判定成功。

出门条件：同一工具能把 r3 判为“无语音相关证据”，把 v0.90 mbelib PCM 判为“存在语音结构”。

### M1：静态确定 route 和会话状态机

无设备操作。

1. 在 `Form_Vocoder_In_Out` 中冻结完整 route bit matrix 和互斥关系；特别检查 GUI 为什么禁止 `Decoder IN=file` 与 `Voice OUT=file` 同时选择。
2. 在 `FormCustomRecordingInterface` 和 `SCT3252T` 中还原 DMR RX 的准确调用顺序、PROCESS_MODE 值、WORK_MODE 参数、CARRIER 状态以及 route 设置时机。
3. 比对 SCT3288/DMR_T1 的 `P2_ReceiverInit`、`P2_VoiceCall`、`P2_CallEnd`，区分 DMR 与 dPMR 命令，禁止把 dPMR `0x04/0x08` 示例直接套到 H13 DMR。
4. 在 `Module_current_0.3.66_full_flash_256k.bin`/ELF/反汇编中追踪 MCU 是否在呼叫开始、carrier ready 或 tape 记录时重写 `VOCODER_IO_SET`、PROCESS_MODE 或 WORK_MODE。
5. 继续定位 SCT application/reader：追踪 `field 0x3E bit0` 和 `type30/field0/count160` 构包源。若该 handler 位于尚未解出的 SCT DSP application，明确记录边界，不用 MCU 反汇编结果冒充 DSP 数据源证明。

出门条件：得到一张 firmware-specific 状态表，至少能回答：

- `0x01` 是否在 H13 DMR application 中有效；
- route 必须在 RX session 前还是可在活动呼叫中改变；
- type30 源是 decoder、codec、loopback 还是未决；
- 是否允许 `DEC_IN from HPI + Voice OUT to HPI` 的组合 route。

### M1-R：恢复基础设施回归门

代码工作可与 M1/M2 并行；在通过前禁止新的 HPI/RF 数据面测试。下一版本建议固定为 v0.92，仅修仪器恢复，不改变 route 或 parser。

- 对 set/restore stub 的上传和完整回读加入有限次数、逐次记账的重试；
- 区分 UART 短读/帧噪声、上传内容不一致和 SRAM 窗口被改写，禁止把所有失败都吞成一次 retry；
- 任一 restore 验证失败时，不再继续发 HPI 猜测；保存日志并走已记录的 reboot/recovery 路径；
- 无 RF 验收一次 57,600 -> 230,400 -> bridge entry/exit -> 57,600，要求 marker/vector/flag/SRAM/CONNECT/version 全部通过；
- 主机恢复 Interphone 只能作为最后兜底，不能替代 Probe 内 transport recovery 证明。

出门条件：无 RF 往返稳定 PASS，且失败注入单测证明不会误报恢复成功。

### M2：准备已知输入和离线相关基线

无设备操作。

- 使用冻结的 v0.90 244-unit `CHAN_D` 作为已知编码语音；
- 使用 mbelib 输出作为参考 8 kHz PCM；
- 从其中截取 0.6-1.2 s 的稳定语音测试段，另生成静音和 1 kHz 音调对照；
- 实现允许固定延迟、增益和极性变化的相关性比较，不能要求逐样本完全一致，因为 SCT 与 mbelib 解码器可能使用不同后处理。

出门条件：参考语音对自身延迟副本显著相关，对 r3 type30 和静音对照不相关。

### M3：无 RF 的本地 decoder 阳性对照

只有 M1 明确支持相应 route 且 M1-R 恢复门通过后才构建 Probe；建议数据面版本从 v0.93 开始。单次只验证一个 route/state 组合。

首选路径：

1. 以 57,600 建立已验证文本基线；bridge flag 必须为 0；
2. 按已验证桩切至 230,400，因为 8 kHz s16 输出需要约 16 kB/s；
3. 按 M1 得出的厂家顺序建立本地 decoder/Voice OUT route；不预设 `0x09` 一定合法；
4. 先注入 0.6-1.2 s 的冻结 `CHAN_D`，严格使用 H13 已观察到的 27 bytes/60 ms 格式和背压；
5. 同时保存所有 type30、控制响应和原始 UART；
6. 离线与 mbelib 参考进行谱形、包络、相关性和人工试听对照。

分支判据：

- route 被 ACK 拒绝：低位扩展或组合 route 不受当前 application 支持；停止，不换值乱试；
- route ACK 但无 type30：状态机/启动顺序仍不完整，回到 M1；
- type30 仍为固定底噪：证明当前 type30 producer 不是 decoder output，转 M5；
- type30 包含可懂语音或与参考显著相关：直接 PCM 数据面首次 PASS，进入 M4。

恢复：桥内 `VOCODER_IO_OFF + WORK_MODE_IDLE`，自动退出桥，恢复 57,600，单次 `sct3258chgpro 0`，逐字节恢复 SRAM，再恢复 Interphone 并验证正常 DMR RX/TX。任何恢复门失败立即停止。

### M4：真实空口 RX 单变量验证

仅在 M3 PASS，或 M1 明确证明该 PCM route 只能在真实 carrier 中工作时实施。需要用户操作 TYT 的测试集中在这一阶段。

- 唯一变量：把 route 移到厂家要求的 RX session 建立时机；不再使用“tape 已过门后才写 0x01”的旧顺序；
- 已知刺激优先为 1 kHz 音调加固定短句，持续 10-15 s；
- 保存 type30 raw、相邻业务事件和独立 `CHAN_D` 阳性对照；若无法同时捕获，明确记录两者的时间关系，不称“同步相关”；
- PASS 必须看到正确音调或可懂短句；失败时依据 ACK、carrier、type30 内容落入单一分支。

不再运行只增加窗口长度、只改变波特率或只归一化音量的实验。

### M5：软件可见 PCM 的替代出口

若 M1/M3 证明 H13 当前 SCT application 不提供 decoder-to-HPI PCM，则转向同一真实 decoder 音频的数字出口，而不是继续猜 HPI opcode：

1. 复核 TLV320AIC3204 的 RX DAC/serial routing 和 H13 实际 codec 寄存器；
2. 复核 Qualcomm AUXPCM/I2S pinmux、ASoC DAI、audio HAL、debugfs 和 root 后可见 ALSA PCM；
3. 只做软件 route/tinycap 阳性对照，先录 H13 扬声器当前能听到的 DMR 语音；
4. 若数字总线未接 Qualcomm 或驱动未注册 capture DAI，记录为软件边界；在用户不拆机的前提下停止，不声称可通过 root 强行读取物理上未连接的数据。

### M6：产品化

只有 M3 或 M4 的内容验收通过后才恢复 P2 产品化：

- raw HPI 为唯一原始证据，WAV/AudioTrack/UDP 均为派生 sink；
- walker 保留 wire328 gap 记录，但在 L1/L2/L3 未定位前不把补零当成“无损恢复”；
- 在线输出必须与离线复算一致；
- 完成 30 s 已知语音、静音、恢复和正常 Interphone 回归测试。

## 4. 建议执行顺序和人工参与

| 顺序 | 工作 | 设备/TYT |
|---|---|---|
| 1 | M0 离线判据 | 不需要 |
| 2 | M1 route/state 静态追踪 | 不需要 |
| 3 | M1-R v0.92 恢复代码与无 RF 回归 | 设备需要，TYT 不需要 |
| 4 | M2 已知输入与相关基线 | 不需要 |
| 5 | M3 v0.93+ 本地 CHAN_D decoder 阳性对照 | 设备需要，TYT 不需要 |
| 6 | M4 空口单变量验收 | 需要 TYT 一次 |
| 7 | M5 软件音频出口，仅在 HPI 失败后 | 设备需要，TYT 最多一次 |
| 8 | M6 产品化 | 最终验收需要 TYT 一次 |

除 v0.92 恢复基础设施版本外，在 M0-M2 完成前不构建或安装新的数据面 APK。每个设备里程碑单独版本、单独捕获目录、单独恢复记录，不把 route、波特率、work mode 和 parser 同时改变。

## 5. 当前概率排序

1. **中高：route 建立时机/DMR session 状态错误。** 厂家流程与旧 Probe 顺序明确不同，但 v0.91 已否证“活动呼叫后仅补 PROCESS_MODE(2)”这一窄分支；剩余重点是 session 建立前 route 和本地 decoder 组合。
2. **中：`0x01` 在 H13 DMR build 中输出的是 idle/test/codec 侧缓冲，不是 decoder output。** 现有固定低电平内容支持此可能。
3. **中低：type30 是 PCM，但仍需未识别的通道重排或 DSP 格式变换。** 当前语音与静音缺少内容差异，使简单格式问题概率下降。
4. **低：WAV header、普通大小端、增益、wire328 补样导致不可懂。** 现有对照已基本排除。
