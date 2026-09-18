## 2026-08-09 23:20 本地时间 - v1.38 - 步骤1离线握手状态机与负向验收

### Gemini：测试与代码重构记录

- **执行范围**：步骤1纯离线开发与单元测试。未安装 APK，未打开 `/dev/ttyHS0`，未发送 AT/HPI，未改 MCU/SCT 状态，未 PTT/GPIO/射频，未把频道改为 encrypt on。
- **假设与单一目标**：在步骤0已闭合的 `ExternalDmrStep0Contract`（producer `0x080177B0`、privacy on、完整 signaling 快照）上，完成可复核的五前序 + 五 VLC 离线握手状态机：每步只接受模型给出的下一请求与对应确认；失败后零后续请求；明确禁止 data36。
- **版本**：`versionCode 138`，`versionName 1.38-step1-offline-handshake`。

#### 一、相对 v1.37 的步骤1增量

1. **`ExternalDmrTxModel` 强化**
   - 仅 `SETUP`/`VLC_SESSION` 可 `expectedRequest()`；`FAILED`/`COMPLETE` 返回 null。
   - `acceptSetupExchange` / `acceptVlcExchange`：空确认、错误字段/类型/状态、乱序请求、错误阶段一律 `FAILED`。
   - 新增 `acceptTimeout()` 硬失败。
   - `canSerializeData36()` 恒为 false（步骤1合同边界）。
2. **离线证明入口**
   - `evaluateExternalDmrStep1OfflineHandshake()`：无 Android Log 依赖的静态评估，便于 JVM 单测。
   - `runExternalDmrStep1OfflineHandshakeProof()`：设备 UI 路径包装（仍不打开串口）。
   - 使用 privacy-on 合成快照夹具（`0x2000040D=0x05`、`0x2000040E=0xA3`、`0x20004CD3..D6=11 22 33 44`）仅验证状态机与线级构包，**不宣称**为当前真机运行时值。
3. **真机入口继续阻断**
   - `runExternalDmrVlcSessionNoRfProof()` 仍在打开串口前失败关闭（当前 CH1 privacy off）。
4. **MainActivity**
   - 增加 Intent 模式 `dmr_external_step1_offline_handshake`。
5. **负向单测新增**
   - 五 setup 线帧字节夹具；
   - null ACK / 错误 status / setup 阶段误用 `0x43`；
   - 超时后零后续；
   - 错误 VLC ACK；完成后重复 ACK 失败；
   - 离线握手全流程通过；
   - privacy-off 合同拒绝。

#### 二、步骤1合同摘要（继承步骤0，不新发明）

| 阶段 | 请求 | 最小确认夹具 |
|------|------|----------------|
| setup0 | type0 `[19 00]` | type0 `[19 00]` |
| setup1 | type0 `[02 18]` | type0 `[02 00]` |
| setup2 | type0 `[3E 60]` | type0 `[3E 00]` |
| setup3 | type5 `[6F 81]` | type5 `[6F 00]` |
| setup4 | type0 `[18 02 00 00]` | type0 `[18 00]` |
| vlc0..1 | type5 mode `01` len9 LC9 | type5 首字节 `43` |
| vlc2 | type5 mode `11` len10 | type5 首字节 `43` |
| vlc3 | type5 mode `1F` len2 | type5 首字节 `43` |
| vlc4 | type5 mode `11` len9 同 LC9 | type5 首字节 `43` |

- LC9（group / privacy-on）：`00 00 40 00 00 63 00 00 0D`
- data36：**不发送、不序列化**
- 当前冻结 CH1（privacy off）：**不得**用本状态机对真机下发

#### 三、构建与测试结果

- 命令：`gradlew.bat testDebugUnitTest --offline --no-daemon`（脚本 `research/h13_radio/analysis/run_step1_tests.ps1`）
- 结果：`BUILD SUCCESSFUL`
- JUnit XML：`tests="103" skipped="0" failures="0" errors="0"`
- XML 路径：`research/h13_interphone_probe/app/build/test-results/testDebugUnitTest/TEST-net.elfradio.h13interphoneprobe.InterphoneProbeTest.xml`
- XML SHA-256：`EDAF0B370FB18574040A441BF9D402A48272D3D20B8B18A3E707CB41A26C3BA6`
- 源码 SHA-256：
  - `InterphoneProbe.java`：`92C9C1584B51D4ED8033569B7992D05DE6B53D8259CD384B7AEF0743AE8EFE87`
  - `InterphoneProbeTest.java`：`1E71A6E72C78145A195192F2E2E444F1B06E0733E1AE616A6AF72BB0BEC03C0E`
  - `build.gradle`：`2D8609D20D0B034BAC35CD6D3CC77F1E704DDDBF3EAB8F38CF89F266D14ADFC7`
  - `MainActivity.java`：`381A5170E0C50F257BEEC2CE2A47E57F750B03AD23AD0330ED942765A4ECAB9F`
- 完整 Gradle 日志：`research/h13_radio/analysis/2026-08-09-step1-gradle-test.log`
- **未** `assembleDebug` 安装，**未**归档可发射 APK（本步不需要真机产物）

#### 四、步骤1自检对照 Implementation Plan

| 计划要求 | 结果 |
|----------|------|
| 补齐前序 VOCODER_CMD / IO / SLOT / WORK_MODE（及步骤0的 0x19） | 通过：合同 setup0..4 |
| 5-VLC 变长 9/9/10/2/9 与 payload 来源 | 通过：继承步骤0公式；夹具快照仅测状态机 |
| 独立有序 0x43 门 | 通过：逐步 `acceptVlcExchange` + 负向 |
| 失败后零后续 | 通过：`FAILED` 后 `expectedRequest()==null` |
| 不发 data36 | 通过：`canSerializeData36()==false` |
| 真机 57600 SysTick 会话 | **未做**（属步骤3）；真机入口仍阻断 |
| 当前 privacy-off CH1 上机 | **禁止且未执行** |

- **验收主张**：步骤1作为“离线握手状态机 + 严格确认门 + 负向测试 + 无串口证明入口”达到离线验收标准。
- **明确未主张**：Version A 真机可跑、slot/VLC 确认夹具等于本机 raw 捕获、signaling 夹具等于当前机 RAM。
- **请 ChatGPT 独立评审**：是否同意步骤1离线通过；是否允许进入步骤2（真实 AMBE 资产 PCM/WAV 等价），并继续禁止任何 External DMR 真机会话直至 privacy-on 快照路线或独立 host 路径获批。

### ChatGPT：independent review

- （等待）

### Gemini: response to review

- （等待）
