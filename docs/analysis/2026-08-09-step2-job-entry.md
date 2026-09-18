## 2026-08-09 23:40 本地时间 - v1.40 - 步骤2真实 AMBE 资产与双路径解码等价

### Gemini：测试与代码重构记录

- **执行范围**：步骤2纯离线。未安装 APK，未打开 `/dev/ttyHS0`，未发送 AT/HPI，未改 MCU/SCT、频道 privacy、PTT、GPIO 或射频。
- **假设与单一目标**：从不可变 v0.90 连续 RX `CHAN_D` 流按时间序取 12 个 9 字节 AMBE 帧，分别以 4×27 与 3×36 分组保存；证明重组不改变 9 字节序列，且两种分组经同一 mbelib 路径解码得到相同 PCM/WAV；构造首单元 44 字节 External TX 信封；版本 B 硬门只允许冻结资产。
- **版本**：`versionCode 140`，`versionName 1.40-step2-ambe-assets`。

#### 一、不可变来源

| 项 | 值 |
|----|-----|
| 源文件 | `research/h13_radio/captures/2026-08-08/p2-chand-long-v090-14s/h13_chan_d_stream.bin` |
| 源字节数 | 6588（244×27） |
| 源 SHA-256 | `ad266884474ddf3d79484790deb3038a42a2c6e81099bf61b687f1ecd29c7602` |
| 导出窗口 | 前 108 字节 = 12×9 字节 AMBE 帧 ≈ 240 ms |

#### 二、派生资产目录（未覆盖原 capture）

`research/h13_radio/captures/2026-08-09/step2-ambe-assets-20260809_2326/`

| 文件 | 字节 | SHA-256 |
|------|-----:|---------|
| 01_chan_d_108bytes_4x27.bin | 108 | `20df1122cdd7e325b01582dab9db073545c91fe08f87812d8f26e5f2af7f52bc` |
| 02_data36_108bytes_3x36.bin | 108 | `20df1122cdd7e325b01582dab9db073545c91fe08f87812d8f26e5f2af7f52bc` |
| 03_ambe_108bytes_12x9_time_order.bin | 108 | 同上 |
| 04_data36_unit0_36bytes.bin | 36 | `83fa8ea05fb8dab16e78944209f86b4fe5d06a567f82ffd290d7e3dd842dc0eb` |
| 05_external_tx_hpi_unit0_44bytes.bin | 44 | `105d9bdcb8cd2137b89a458b7045aeb702e2d81f627d7876c3bc25ff288dbcf7` |
| 06_decode_from_4x27_grouping.wav | 3884 | `cff98fc8a38c5c524a0bcc7d07691e1d6125e7e5ca7f9c0867f9e6bcf3739261` |
| 07_decode_from_3x36_grouping.wav | 3884 | `cff98fc8a38c5c524a0bcc7d07691e1d6125e7e5ca7f9c0867f9e6bcf3739261` |
| 08_pcm_from_4x27_grouping.s16le | 3840 | `e423ac881de1aea9d48eb223778871b454fc638e1d3f9d0133cc729c99cd776d` |
| 09_pcm_from_3x36_grouping.s16le | 3840 | `e423ac881de1aea9d48eb223778871b454fc638e1d3f9d0133cc729c99cd776d` |
| MANIFEST.txt | — | `a3d95296ca4f3c2287eb4267af7fa9068f0850c4a3b9ea8656cb7cc92f1e3d5b` |

另含 `units_4x27/chan_d_unit_00..03.bin`、`units_3x36/data36_unit_00..02.bin` 与解码 CSV。

#### 三、等价性原始判定

- 4×27 与 3×36 **字节流逐字节相同**：是（12 帧时间序重分组在此长度下恒等）。
- 两路 WAV **逐字节相同**：是。
- 两路 PCM s16le **逐样本相同**：是；3840 = 12×160×2。
- 解码器：`research/h13_radio/tools/bin/chan_d_to_wav.exe`（输入须为 9 字节倍数）。
- 构建脚本：`research/h13_radio/analysis/step2_build_ambe_assets.py`。
- 44 字节信封前缀：`84 A9 61 00 26 03 01 24` + 36 字节 unit0。

#### 四、代码硬门（v1.40）

- `extractFrozenData36Unit(frozen, index)`：仅当 `frozen` 逐字节等于 `REAL_RX_AMBE_108_BYTES` 且 index∈0..2。
- `createFrozenExternalEncodedTxFrame(index)`：仅从冻结资产生成 44 字节帧。
- 常量写入源码：108/unit0/44/PCM/WAV 的 SHA-256，供单测与后续版本 B 引用。
- **未**打开串口；**未**接入 Version A/B 真机发送路径。

#### 五、单元测试

- 命令：`gradlew.bat testDebugUnitTest --offline --no-daemon`
- 结果：`BUILD SUCCESSFUL`
- JUnit：`tests="107" skipped="0" failures="0" errors="0"`
- XML SHA-256：`C57189603D0A95804DCC1F0F401B59EB0CC13828FACC2FC93B91EEE60E31DADE`
- 源码 SHA-256：
  - `InterphoneProbe.java`：`58D16D47599FB5B01DF62D4CBDAFD0354F726B9A0F5DE86814F51D47A905D293`
  - `InterphoneProbeTest.java`：`643B142907A56126357BC9CDE276738FEC20DE73E6A4AF7B1DC347EFDF56731C`
  - `build.gradle`：`02C5048520559E7BFF1D6B288AABE0B3D33C38F72284801EC976DACA9E94AA7A`

#### 六、步骤2自检对照计划

| 计划要求 | 结果 |
|----------|------|
| 禁止四次复制伪 AMBE | 通过：仅冻结 RX 资产 |
| 4×27 与 3×36 时间序 12 帧 | 通过 |
| 重组前后 9 字节序列一致 | 通过（字节流相同） |
| 两路解码 PCM/WAV 等价 | 通过（逐字节） |
| 路径/字节数/SHA-256 | 通过：见 MANIFEST |
| 44 字节 HPI 信封 | 通过：unit0 已生成并哈希 |
| 证明 SCT 接受 data36 / RF | **不主张** |

- **验收主张**：步骤2作为“真实 AMBE 资产冻结 + 分组无损 + 双路径解码等价 + 版本B硬门”**通过**。
- **明确未主张**：External TX 真机 credit、射频可听、Version A/B 可上机。
- **请 ChatGPT 独立评审**：是否同意步骤2离线通过；是否允许在步骤1已通过前提下保持真机阻断，直至 privacy-on 快照或独立 host 路径获批。

### ChatGPT：independent review

- （等待）

### Gemini: response to review

- （等待）
