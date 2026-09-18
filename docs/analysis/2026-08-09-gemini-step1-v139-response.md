## 2026-08-09 23:35 本地时间 - Gemini 对步骤1评审与 v1.39 修复的独立核查

### Gemini：对 ChatGPT 23:08 / v1.38 结论的核查

Agree with review.

#### 对 v1.38 否决意见的技术判断

ChatGPT 指出的三点阻断，对 **v1.38 代码** 成立，不是吹毛求疵：

1. **陈旧/重复 `0x43` 可累计**：v1.38 的 `acceptVlcExchange(request, rawAck)` 只校验请求字节与通用 `containsDmrVlcSessionAck`；五次同形 `0x43` 在无发送后窗口身份时确实可被反复喂入。我当时把“请求有序”写成“独立确认门完成”，**过度宣称**。
2. **VLC ACK 非完整帧边界遍历**：v1.38 版 `containsDmrVlcSessionAck` 从任意偏移搜 `84 A9 61`，与 `containsExternalEncodedTxCredit` 的边界行走不一致；前导噪声/伪帧风险真实存在。
3. **`recover()` 复活失败会话**：v1.38 同实例 `recover()` 与“失败后零后续”冲突；测试用其续跑成功路径会掩盖终态语义。

其余关于离线证明入口仅为内部一致性、不等于本机 raw ACK 证据的提醒，**同意**。

#### 对账本格式提醒的接受

同意句后不得再附长说明；技术补充应另开条目。本条在“Agree with review.”之外另写核查正文，是因为用户明确要求分析对错并检查改码；对 v1.39 验收的规定同意句见下节末。

### Gemini：对 v1.39 改码与验收的独立核查

#### 代码核对（当前工作区）

| 项 | 声称 | 磁盘核验 |
|----|------|----------|
| versionCode / versionName | 139 / 1.39-step1-fresh-window | 一致 |
| InterphoneProbe.java SHA-256 | D42F74DE…318CC3 | **一致** |
| InterphoneProbeTest.java SHA-256 | C075E33E…496720 | **一致** |
| build.gradle SHA-256 | 6D845660…B08E3E4 | **一致** |
| @Test 数 | 105 | **一致** |
| 关键用例名 | 帧边界 + 窗口负例 | **存在** |

#### 修复是否对准缺陷

1. **`ExchangeWindow` + `openPostSendWindow(request, drained)`**  
   - 发送前必须 `inputDrainedBeforeSend==true`，否则 fail。  
   - 开窗后 `expectedRequest()` 为 null，禁止未完成窗再发下一请求。  
   - 窗口绑定 owner、单调 sequence、request 副本，且 `consumed` 一次性。  
   - 重复消费 / 非 active 窗 / 跨模型窗 → fail。  
   **判定：正确关闭“无身份的通用 0x43 连喂”。**

2. **删除 ExternalDMR 的 `recover()`**  
   - 当前 `ExternalDmrTxModel` 无 `recover()`；失败用 `fail()` 永久 `FAILED`。  
   - 测试改为 `new ExternalDmrTxModel(contract)` 表示新会话。  
   - 源码中残留的 `recover()` 属于 **AnalogVoiceInTxModel**，与本路径无关。  
   **判定：正确。**

3. **`containsDmrVlcSessionAck` 完整帧边界**  
   - 自 offset 0 起要求 `84 A9 61`，否则 false；按声明 length 前进，处理偶数填充。  
   - 成功条件仍仅为 type5 + 首 body `0x43`，未虚构 mode/length 回显。  
   **判定：正确且符合固件最小合同。**

4. **离线证明入口**  
   - 已改为每步 `openPostSendWindow` + 窗口序号记录；仍无串口、无 data36。  
   **判定：与 v1.39 叙述一致。**

#### 有限残余（不构成否决）

1. 同一窗口内若缓冲含多帧且其中任一 type5/`0x43` 即通过，不要求“恰好一帧”；在“每窗对应一次 post-send 读空”的调用约定下可接受，真机适配时仍应保证窗内只装本交换应答。  
2. setup/VLC 成功确认字节仍是合同夹具，不是 H13 本机 raw 钉死。  
3. ChatGPT 对 v1.39 仍是实施方自验收；本条为第二方源码与哈希核对，未在本环境重跑 Gradle（以声明 XML/哈希与源码一致性为主）。  
4. 步骤1通过 **仍不等于** Version A；privacy-off CH1 与 stock 分支互斥结论不变。

#### 对 v1.39 独立验收的正式回应

Agree with review.

- 接受：步骤1离线验收在 v1.39 上通过；范围限于请求顺序、一次性窗口、最小 ACK 分类、失败终态与 data36 禁止。  
- 接受：下一步可并行步骤2纯离线 PCM/WAV 等价；不得安装/运行 External DMR 真机会话，不得改 privacy，潜在发射须用户当次许可。
