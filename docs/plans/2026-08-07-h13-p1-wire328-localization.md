# H13 P1：PCM 328/330 线距差异定位计划

> 对应 `H13.md` §18.53 P1、§18.48–18.52 已冻结观察。依赖 P0（v0.87）仪器可用。  
> **验收不是** 再采一份 230400 大样本，而是把问题**定位到一个层级**。

**Goal:** 判定 328-stride SPEECH 是（A）LENGTH/padding 协议语义、（B）SCT→MCU HPI 产出、还是（C）MCU UART / 内核 TTY 路径上的固定少 2 字节；并加固 walker 避免假切帧。

**Architecture:** 先纯离线、全捕获对照、可证伪分叉；仅当离线无法在 A/B/C 间判决时，才做**一次**有界 MCU pre-UART 观测。全程复用 v0.87 恢复纪律，不引入新波特/loader 变量。

**Tech Stack:** 不可变 `.bin` 捕获、Python 离线分析、`InterphoneProbe.resolveHpiFrameWireLength`、（可选）SRAM 短观测 + P0 交接脚本。

---

## 0. 问题陈述（已确认事实，不再重测）

| 事实 | 证据 |
|---|---|
| SPEECH 头常声明 `LENGTH=323`、count=160 | v0.84/v0.86 等 |
| 纸面线长 ≈ 6+323 = 329，偶对齐常写作 **330** | 协议解释 |
| 230400 连续流：下一 `84 A9 61` 几乎总在 **+328** | v0.84 690/690；v0.86 404/405 |
| 可用 PCM ≈ **318 B = 159×s16** + 常见尾 `0x00` | wire328 布局钉死 |
| 57600 旧捕获中**存在** dist=330 且 320 B 完整样本 | §18.51 |
| v0.86 8 KiB/1 ms Java 排空后仍几乎全 328 | **否证**“仅 Java 读慢” |

**P1 要回答的唯一科学问题：**

```text
少 2 字节发生在哪一层？
  L0  协议字段本意就不是 330（解析/语义）
  L1  SCT HPI 产出就是 328
  L2  MCU 读 HPI 后、送 UART 前已是 328
  L3  MCU UART 发送或 MSM8909/TTY 路径变成 328
```

---

## 1. 目标与非目标

### 目标（P1 Done）

1. Walker：提前同步字必须校验 LENGTH/type/field/边界（不能只认 `84 A9 61`）。  
2. 全库统一 stride 表（57600 + 230400 原始 bin）。  
3. 样本连续性分析：缺 1 样点 vs LENGTH 语义 vs 解析假象。  
4. 输出**分层结论**（L0–L3 之一，或“离线只能排除到 L0/L1 边界，需 MCU 观测”）。  
5. 若进入 MCU 观测：一次单变量实验后给出 L2 vs L3 判决。

### 非目标

- 再采“更大”的 230400 PCM 只为重复 328 现象  
- Flash 导出、TX 注入、I/Q  
- 产品级实时流（→ P2，可与 P1 后期并行设计，但上机实时流另约）  
- 改生产 Interphone v0.7 业务逻辑  

---

## 2. 是否需要你操作 TYT？

| 阶段 | 是否需要 TYT | 说明 |
|---|---|---|
| **P1-A 离线（主战场）** | **不需要** | 已有 v0.76–v0.86 多份不可变捕获足够制表与连续性分析 |
| **P1-B walker/单测** | **不需要** | 纯代码 + 对既有 bin 回归 |
| **P1-C MCU pre-UART（仅当 A 判不了）** | **需要短时发射** | 要在 bridge 内看到连续 SPEECH 才能比 MCU 侧长度 vs Android bin；约 **一次**、协调 cue、短窗（目标 ≤15–30 s 有效语音），用 v0.87 恢复梯 |
| **P1 若 A 已定论到 L0** | **永远不需要** | 若证明是 LENGTH/padding 语义而非丢字节，则关闭 P1，不进 C |

**默认承诺：**  
先做完 P1-A/B 并写结论。**在离线报告明确写“必须 MCU 观测”之前，不会叫你按 TYT。**

若进入 P1-C，会提前给你：

- 预计发射时长与 cue 文案  
- 单变量是什么  
- 通过/失败观察  
- 恢复检查清单（Interphone / GPIO / CH1）

---

## 3. 分阶段执行

### 阶段 P1-A：离线全捕获对照（0 设备 RF）

**Create/Modify:**

- `research/h13_radio/tools/hpi_stride_census.py`（新建）  
  - 扫描 `captures/**/hpi_*.bin`（及已知 voice_out 命名）  
  - 对每个 SPEECH 候选输出 CSV 行：  
    `file, offset, type, field, declared_len, paper_wire, next_sync_dist, next_header_valid, sample_bytes, last_byte, rms, next_rms, gap_flag`  
  - 汇总：按文件的 328/329/330 计数、57600 vs 230400 分层  

- 输出目录（时间戳，不覆盖）：  
  `research/h13_radio/captures/2026-08-07/p1-stride-census-<ts>/`  
  - `all_frames.csv`  
  - `summary_by_file.txt`  
  - `summary_by_baud.txt`  
  - `continuity_notes.txt`（跨帧样点/RMS）

**判定规则（写进报告）：**

| 离线结果 | 倾向 |
|---|---|
| 下一头合法且 LENGTH 自洽、payload 恰 318、尾 0 系统出现 | 强支持 **L0/L1 打包语义**（非随机丢） |
| 57600 完整 330 与 230400 全 328 稳定共存 | 速率/路径相关，**仍可能 L1–L3**，但排除“格式从不完整” |
| 若“合法头”仅靠 3 字节 sync 且 LENGTH 荒谬 | walker 假阳性 → 先修 L0 解析 |

**验收：** CSV 覆盖全部主要 230400 大样本 + 至少一份含 330 的 57600 对照；结论章节明确“是否需要 P1-C”。

---

### 阶段 P1-B：Walker 加固 + 回归（0 RF）

**Modify:**

- `InterphoneProbe.resolveHpiFrameWireLength(...)`  
  候选下一帧必须同时满足例如：  
  - `84 A9 61`  
  - body length 合理（SPEECH 类 300–400 量级或已知集合）  
  - type 为已知 HPI type（至少 0x00/0x20/0x30 等已见表）  
  - field/count 与 SPEECH 规则相容  
  - 不越过 buffer  

**Test:**

- 用 v0.84 Control B 226416 B 与 57600 混合样本：  
  - wire328 链 unframed=0  
  - 不得因载荷内偶然 `84 A9 61` 切碎  

**同步：** `analyze_hpi_stream_v061.py` 采用同一校验规则，避免 APK/Python 分叉。

**验收：** 单测 + 对冻结 bin 的 before/after 表一致或可解释。

---

### 阶段 P1-C：MCU pre-UART 短观测（**有条件**；可能需 TYT）

**仅当** P1-A 报告写明：无法区分 L1/L2/L3。

**单变量：**  
在 bridge 活跃、Voice OUT 路由开启时，对**少量**（如 3–10）SPEECH 帧记录：

- MCU 侧“即将/正在 UART 发送”的帧长或边界计数（地址与方法须先静态从 0.3.66 固件钉死，**禁止**现场猜地址）  
- 同会话 Android 原始 bin 的对应 stride  

**比较判决：**

| MCU pre-UART | Android | 定位 |
|---|---|---|
| 330 | 328 | **L3** UART/驱动/TTY |
| 328 | 328 | **L1/L2** SCT 或 MCU HPI 读路径 / 语义 |
| 一致且解析冲突 | — | 回 **L0** 改协议解释 |

**安全：**

- 仅 v0.68+ 栈安全桩纪律；P0 单次 reload + handoff  
- 不确定向量/flag/窗口 → **立刻停**  
- 不长时间完整缓存；不做 Flash  

**TYT：** 是，**一次**短窗协调发射。若可无 RF 仍产生可数 SPEECH（当前证据不支持），则可不发射——以实机前置检查为准。

---

## 4. 推荐日程（高效路径）

```text
Day 0–1  P1-A  census 工具 + 全表 + 连续性笔记     [无你]
         P1-B  walker 加固 + 单测 + Python 对齐   [无你]
         写 job.md / H13.md 离线结论
         若 L0 闭合 → P1 结束，开 P2 产品化设计
         若需 L2/L3 → 写 P1-C 实验单，约你一次 TYT
Day 1–2  P1-C（仅必要时）一次设备窗 + 文档闭环
```

**不** 在 A/B 未完成时约发射。

---

## 5. 与 P2 的关系

- P2（159→160 补样实时流）**不阻塞** 于 P1 完全闭合。  
- 科学上：P1 定 L0 则产品直接按语义解析；定 L3 则产品仍可补样但要标 gap。  
- 工程上：P1-A/B 期间可并行起草 P2 接口，**不上机实时流** 直到你方便做 30 s 验收。

---

## 6. 文档与台账

- 开始 P1-A 前：`job.md` 追加 `v0.88` 或 `P1-offline` 条目（离线无 APK 也写假设与输出路径）  
- 每阶段结束：`H13.md` 追加 §18.xx  
- 原始 bin **只读**；派生表进时间戳目录  

---

## 7. 一句话回答“要不要我发射？”

**现阶段制定并执行 P1 时：不要。**  
先吃光已有捕获做分层；只有离线报告写明必须 MCU 对照时，才需要你**短时、一次** TYT 配合。  
若离线已证明是协议语义（L0），则 P1 全程不需要你碰 TYT。
