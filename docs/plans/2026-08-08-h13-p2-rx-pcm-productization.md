# H13 P2：RX PCM 产品化计划

> 依赖：P0 仪器（v0.87+）、P1 操作语义（wire328 = 159 s16 + pad；LENGTH 相对纸面偏大 2）。  
> **不依赖** P1-C MCU 分层；硬件 L1/L2/L3 可作为后台可选课题。

**Goal:** 把已证明的 Voice OUT HPI RX PCM 变成可稳定使用的 8 kHz mono s16le 实时流与录音管线，并与 `CHAN_D` 元数据对齐；停测后生产 Interphone 必须恢复。

**Architecture:** Probe/服务侧：raw bridge Voice OUT → 严格 header walker → wire328 恢复（318 B / pad→160）→ 20 ms 帧时钟 → 本地播放/WAV/UDP|WebSocket；并行或旁路 memread tape 提供 `CHAN_D`/呼叫元数据。恢复梯复用 P0 单次 control reload + 主机交接。

**Tech Stack:** Android 8.1（`h13_interphone_probe` 或后续 Radio Service）、现有 `libserial_port`、P0 脚本、离线 `recover_hpi_speech_wire328` 作黄金对照。

---

## 0. 已具备的证据（P2 不再重证）

| 能力 | 版本/证据 |
|---|---|
| live CHAN_D 27 B | v0.12–v0.18 / v0.60+ |
| Voice OUT 8 kHz s16 PCM | v0.61 @57600；v0.78/84/86 @230400 |
| 57600↔230400 文本 + bridge | v0.70–v0.71 |
| HPI 后生产恢复 | v0.84 / P0 v0.87 |
| wire328 语义 | P1 census：1972×328 vs 8×330；99.6% 尾 0x00；318 sample bytes |
| 三帧 PCM ≈ 一单元 CHAN_D 60 ms | §18.18 |

---

## 1. 目标与非目标

### 目标

1. **实时 8 kHz mono s16le** 输出，时间轴 20 ms 一帧（缺样补齐并记 gap）。  
2. **不可变保存**：raw HPI bin、恢复前 PCM、补样 PCM、WAV；禁止只留派生音频。  
3. **输送接口**（首版至少 2 个）：本地 AudioTrack 试听 + 文件 WAV；可选 UDP 或 WebSocket。  
4. **元数据并行**：`CHAN_D` 计数/可选流、slot/TG 若可得、帧缺失统计。  
5. **安全退出**：P0 恢复梯；Interphone 唯一 UART；GPIO 待机。  
6. **30 s 受控接收验收**：无崩溃、时间轴正确、可复算、生产恢复。

### 非目标

- TX PCM/AMBE 注入（P3）  
- I/Q / Flash  
- 完美 330 线（接受 159+pad 为常态）  
- 完整 Radio OS Launcher  

---

## 2. 帧恢复规范（产品契约）

```text
输入: HPI SPEECH type=0x30 LENGTH=323 count=160
线距: 优先下一合法头距离；常态 328
样本: s16le little-endian 自 offset+9
  - 若 sample_bytes==320 且 wire>=330 → 完整 160 样点
  - 若 wire==328 → 取 318 B (159 样点)，丢弃尾 pad 0x00，再 pad 1×s16 静音或线性插值到 160
  - 记录 gap: missing_samples=1, method=zero_pad|hold|linear
输出时钟: 每帧标称 20 ms；累积 drift 用 gap 元数据解释，不静默拉伸
```

与 CHAN_D：

```text
3 × PCM 帧 (60 ms) ↔ 1 × 27-byte CHAN_D（3×9 AMBE）
产品可同时：
  - HPI Voice OUT 作听感/STT
  - tape/HPI CHAN_D 作编码旁路与校验
```

---

## 3. 分阶段交付

### P2-0 离线黄金管线（无设备 / 无 TYT）

**Files:**

- 扩展 `recover_hpi_speech_wire328.py` 或新 `pcm_product_pipeline.py`  
- 输入：冻结 v0.84 / v0.86 bin  
- 输出：`raw_meta.json`、`pcm_unpadded.s16`、`pcm_padded_160.s16`、`out.wav`、`gaps.csv`

**验收：** 与 P1 census 帧数一致；WAV 时长 ≈ speech_frames × 20 ms；可人工试听既有样本。

### P2-1 Probe 流式模式 `rx_pcm_stream`（APK）

**version 建议：** v0.89+

行为：

1. P0 交接停 Interphone  
2. 握手 + CH1 +（可选）tape 门或固定 Voice OUT 路由  
3. 57600 文本 → 栈安全 230400 → bridge → `VOCODER_IO=Voice OUT`  
4. 环形读串口；walker 吐 20 ms 帧到：  
   - 文件 sink（强制）  
   - AudioTrack（可选 extra）  
   - UDP 127.0.0.1:port（可选）  
5. 超时/停止 → HPI exit + 单次 control reload + 关串口  
6. 主机脚本 restore Interphone  

**单变量首测：** 文件 sink only，15–30 s，**需要一次 TYT**（或对端发射）。

### P2-2 元数据与 UI

- 大字 banner：CAPTURING / frames / gaps / RMS  
- 报告：`pcmFrames`, `wire328`, `complete160`, `gapSamples`, `chanD27`  
- 与 P0 字段并存：`controlPlaneRestored`, `finallyReloadSkipped`

### P2-3 30 s 验收（有 RF，一次）

| 检查 | 标准 |
|---|---|
| 时长 | 输出 PCM ≈ 30 s ±0.5 s（含 gap 标注） |
| 稳定 | 无崩溃、无二次 chgpro |
| 复算 | raw bin → 离线管线 == 在线 WAV（允许 pad 策略一致） |
| 恢复 | Interphone 独占 tty、CH1、GPIO 待机 |
| 听感 | 可懂语音（主观） |

---

## 4. 是否需要你操作 TYT？

| 子阶段 | TYT |
|---|---|
| P2-0 离线 | **否** |
| P2-1 首次有数据上机 | **是，一次 15–30 s** |
| P2-3 正式验收 | **是，一次约 30 s** |

设计/编码阶段不需要你；有 RF 验收前会写 cue。

---

## 5. 建议实现顺序

```text
1. P2-0 离线黄金管线 + 单测（pad 策略固定）
2. APK rx_pcm_stream：只写文件 + P0 恢复
3. 一次 15 s TYT 冒烟
4. AudioTrack / UDP 可选
5. 30 s 验收 + H13.md / job.md
```

**不** 在未跑 P2-0 时上机。

---

## 6. 风险

| 风险 | 缓解 |
|---|---|
| 230400 丢字节被当 gap | 按 wire328 语义恢复，不报“随机丢包” |
| 长捕获污染 traffic plane | P0 恢复梯强制 |
| AudioTrack 与串口线程抢 | 单写者队列；播放可丢帧但文件不丢 |
| 与 Interphone 抢口 | 仅主机脚本 disable 窗口 + finally enable |

---

## 7. Done 定义

- [ ] 离线管线对 v0.84/v0.86 可复算  
- [ ] 在线 30 s 捕获 + WAV + raw 齐全  
- [ ] gap 元数据完整  
- [ ] 生产 Interphone 恢复  
- [ ] `job.md` + `H13.md` 闭环  
- [ ] 未引入 TX/Flash/I/Q 范围蔓延  

---

## 8. 与后续

- **P3 TX**：P2 稳定后单独里程碑  
- **P1-C**：仅当需要硬件层论文级定位时再约 MCU 观测 + TYT  
- **Radio OS**：P2 输出作为 `TransmitBackend` 的 RX 音频源接口草案  
