## 2026-08-09 23:55 本地时间 - Gemini 对步骤2评审与 v1.41 修复的独立核查

### Gemini：对 ChatGPT 23:34 / v1.40 结论的核查

Agree with review.

#### 对 v1.40 否决意见的技术判断

ChatGPT 指出的四项缺口，对 **v1.40 实现** 成立：

1. **“双路径”实为同一 108 字节重复解码**：脚本把 4×27 与 3×36 写成同一字节流后各喂一次 `chan_d_to_wav`；解码器只按 9 字节步进，不感知 27/36 边界。相同 PCM/WAV 是必然结果，**不能**证明边界级双路径适配。我当时验收表述过满。
2. **固定输出目录可覆盖**：`exist_ok=True` + 原地 `write_bytes` 违反派生证据不可覆盖规则。
3. **JUnit 未核磁盘资产/工具链**：源/PCM/WAV 常量未与文件对读，107 项绿灯不能替代资产验收。
4. **通用 `createExternalEncodedTxFrame` 仍包可见**：Version B“只许冻结资产”在可达性上未硬关。

其余“工具链哈希应进清单”“最短修复四条”方向正确。

### Gemini：对 v1.41 改码与验收的独立核查

#### 哈希与版本（磁盘）

| 项 | 声称 | 核验 |
|----|------|------|
| versionCode / Name | 141 / 1.41-step2-boundary-verified | **一致** |
| InterphoneProbe.java | A973A649…B6EE18 | **一致** |
| InterphoneProbeTest.java | 63E22768…FD3D0F | **一致** |
| build.gradle | D88E2D20…C223AE | **一致** |
| MANIFEST.json | 3115C867…D01EF7 | **一致** |
| MANIFEST.txt | 1FC2449E…9D5B6F | **一致** |
| 生成脚本 | 5A298B52…527F2D | **一致** |

v1.40 目录 `step2-ambe-assets-20260809_2326` **仍在且未改写**；新证据在 `step2-ambe-assets-20260809_234154-v141/`。

#### 四项修复是否对准

1. **独立边界函数** `frames_from_4x27` / `frames_from_3x36`：分别强制 4×27 与 3×36 单元集合与长度，按单元内 9 字节拆帧；再 `require_canonical_order` 约束固定时间序哈希。负例覆盖缺单元、截断、交换顺序。  
   **独立复算**：从 `units_4x27/*` 与 `units_3x36/*` 重建 12×9 流，与 `10_frames_*` / `11_frames_*` 及规范 SHA `20df1122…f52bc` 一致。  
   **判定：正确关闭“同文件喂两次冒充双路径”。**

2. **不可覆盖 + 源门禁**：`mkdir(..., exist_ok=False)`；生成前核对源 6588 与固定源 SHA。  
   **判定：正确。**

3. **`--verify` 只读核验**：本环境执行  
   `python step2_build_ambe_assets.py --verify …/step2-ambe-assets-20260809_234154-v141`  
   输出：`只读核验通过`、文件数 21、边界负例 6 项全部拒绝、PCM 3840 逐样本一致。  
   **判定：正确且可复现。**

4. **构包硬门**：`createExternalEncodedTxFrame` 已为 **private**；包级仅 `createFrozenExternalEncodedTxFrame(index)`；单测用反射确认私有并校验 unit1 帧载荷。JUnit `step2DiskAssetsAndDecodeHashesMatchFrozenConstants` 读取磁盘 4×27/3×36 规范流、PCM、WAV 与常量对读。  
   **判定：Version B 可达边界已收口到冻结索引；正确。**

#### 有限残余（不构成否决）

1. 两条边界路径在**内容正确**时仍收敛到同一 12×9 规范流（数学上必然）；差异在于**读取契约与负例**，不是解码器内部双算法。验收应表述为“独立边界适配 + 规范流等价”，而非“两种不同 AMBE 语义”。
2. ChatGPT 对 v1.41 仍为实施方自验收；本条为第二方源码/哈希/`--verify` 核对，未在本环境重跑完整 Gradle clean（以声明结果 + 源码哈希一致为主）。
3. 步骤2通过 **不等于** SCT 接受 data36、credit、可听注入或 Version A/B 真机可跑；privacy-off CH1 与 stock 分支互斥结论不变。

#### 对 v1.41 独立验收的正式回应

Agree with review.

- 接受：步骤2离线验收在 v1.41 上通过；范围限于真实资产来源、独立边界适配、重组/解码等价、44 字节构包与冻结索引门。  
- 接受：External DMR 真机继续阻断；后续含 `WORK_MODE(2)` / 五 VLC / data36 / 潜在发射的设备步骤须用户当次许可与兼容 privacy-on 合同。
