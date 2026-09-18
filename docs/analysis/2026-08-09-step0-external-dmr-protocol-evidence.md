## 2026-08-09 22:13 本地时间 - 步骤 0：External DMR 生产协议参数静态恢复（离线）

### Gemini：步骤 0 工作记录

- **执行范围**：仅步骤 0（离线静态恢复与证据表）。不安装 APK、不连接串口、不发送 AT/HPI、不 PTT、不 RF、不改 Probe 业务代码。在既有 H13.md 第 14.6.7 节与第 18.120-18.146 节、固定固件 Module_current_0.3.66.bin（SHA-256 5D41BE2A734838ABFD4EF13C8BCDCFF424B634CD69B3202AEC1E16E0261A484A）、反汇编 Module_current_0.3.66_thumb_disasm.txt、公开 SCT3252T.cs / SCT3252.cs / Form_SaveVocoder.cs 与历史 capture 上推进；不推倒 v1.36 已有构包能力。
- **假设**：生产 External Encoded DMR 的线级前序与五 VLC 合同可由 0x08019480 -> 0x08024DC0 -> 0x0801BCC4 与公开工具交叉闭合到可指导步骤 1 的程度；CH1 尾字段与 10/2 字节 signaling 的运行时真值仍可能需要一次只读冻结。
- **唯一变量**：相对 21:55/22:01 账本，本条只新增步骤 0 证据整理，不改变设备状态。

#### 一、已确认的生产控制前序（观察）

| 次序 | 线级合同 | 证据 | 同步成功门（已证实最小合同） |
|------|----------|------|------------------------------|
| 0（配置函数内） | HPI packet type 0，body 以 0x19 起；本路径参数使 body 为 [0x19, 0x00]（CARRIER_READY 族） | 0x08019480 在 0x080194C6 调用 0x0801B0E4(r0=5,r1=0)；H13.md 18.130 更正首 body 为 [0x19,0x00] | 同步返回成功才继续 |
| 1 | type 0，body [0x02, 0x18] = VOCODER_CMD_SET(0x18) | 公开 VOCODER_CMD_SET：CreatCmd(0,[2,v])；0x0801DFA4(0x60) 经 0x0801CFD0 写入 body 首字节 0x02、次字节=参数 | type 0 控制成功门；不得把 18 00 当作本命令确认（0x18 是 WORK_MODE 字段号） |
| 2 | type 0，body [0x3E, 0x60] = VOCODER_IO_SET(EXT_ENC_TX) | 同 helper 链 0x0801D0C8 写入 0x3E+route；v1.05 实机精确应答 84 a9 61 00 02 00 3e 00 | 状态零：3E 00（已实机） |
| 3 | type 5，body [0x6F, callslot] = DMR_CALL_SLOT | 0x0801C16C 写入 0x6F 与 callslot，发送 type=5；公开 DMR_CALL_SLOT 同形 | 同步发送成功门；ACK 形状尚未被本步骤用真机字节钉死（不得复用 type0 analogue ACK helper） |
| 4 | type 0，body [0x18, 0x02, 0x00, 0x00] = WORK_MODE(2,0,0,0) | 0x08019480 在 0x0801962E 调 0x0801D2BC(2,0,0,0) | 状态零：18 00 族；成功后写 lifecycle 0x200017DD=2 |
| 5 | 同一 wrapper 随后 0x08024DC0 external 分支 | 0x08015C7C 在 0x08019480 成功后 BL 0x08024DC0 | 见下节五 VLC |

- **门控条件（观察）**：0x08019480 仅在 active channel 经 profile_base+4 后的 channel+30 == 1 时调用 0x0801DFA4(0x60)。channel+30 为派生 privacy-init 类标志（H13.md 14.6.7 表），不是 AT 明文直接字段。生产 CH1 encrypt=off 时该字节运行时是否为 1：本步骤未做 memread，标为未知。
- **候选步骤 1 发送顺序**：`[0x19,0x00]` -> `[0x02,0x18]` -> `[0x3E,0x60]` -> type5 `[0x6F,callslot]` -> `[0x18,0x02,0x00,0x00]` -> 五 VLC。其中 `[0x19,0x00]` 是否必须由 raw-HPI 主机重放：未决（实现前须显式二选一并记录）。

#### 二、DMR_CALL_SLOT 的 callslot 字节（观察 + 有界推断）

- **观察（公开）**：DMR_CALL_SLOT(byte) 发送 type 5、`[0x6F, callslot]`；多处 UI 使用 128（0x80）。
- **观察（固件）**：0x08019480 在 0x08019506..0x080195D2 与 0x080195E0..0x0801961A 构造局部 byte 后 BL 0x0801C16C。构造中出现 bit7 的 clear 后加 0x80（该分支 bit7=1）、第二参数 r4 参与 bit6、以及把低位收敛为 1 或 2（与 slot1/slot2 相容，不是 0-based）。
- **有界推断**：对生产 CH1 slot1/slot1 且走 bit7 置位分支，候选 callslot = 0x81（0x80|1）。
- **排除**：v1.36 的 callslot=0x01 不能再称为已恢复生产值。
- **未知**：wrapper 传入的 r4 在生产路径是否恒 0；另一构造分支是否在 external 路径可达。步骤 1 在无运行时捕获前应把 callslot 标为候选 0x81，不得写成最终真理。

#### 三、五 VLC / session 合同（观察）

| 序号 | mode（bit7=0 时） | length | payload 来源（固件） | 同步 ACK |
|------|-------------------|--------|----------------------|----------|
| 1 | 0x01 | 9 | 0x080225EC/0x08010DCC 与 0x080224D8 生成的 9-byte LC 缓冲 | 0x0801BCC4：type 5，body 以 0x43 起；收包后比较首 body 字节 == 0x43 则成功 |
| 2 | 0x01 | 9 | 同上；循环 2 次（cmp r4,#2） | 同上 |
| 3 | 0x11 | 10 | 另建 10-byte 缓冲；额外 RAM 字段打包后 0x080224D8 | 同上 |
| 4 | 0x1F | 2 | 2-byte 缓冲；两处全局 byte 拼入 | 同上 |
| 5 | 0x11 | 9 | 回到 9-byte LC 缓冲 | 同上 |

- **mode bit7（有界推断）**：发 VLC 前用 channel 相关字节左移 7 后与 0x80 写入 mode。结合 channel+26=colorCode：生产 CC=8 时 8<<7 的 bit7 为 0，mode 保持 01/01/11/1F/11，不必 OR 0x80。
- **9-byte LC 布局（公开 + 18.146）**：type | reserved/options | calledID[3] | ownID[3]；ID 为大端三字节。
- **生产身份（观察）**：
  - own id = 13 -> 00 00 0D
  - called/TG = 99 -> 00 00 63
  - secretKey 文本 12345678 是密钥字段，不是 Radio ID；encrypt=off 时不启用隐私。
  - 排除 v1.36 把 own=12345678（BC 61 4E）写入 LC 的做法。
- **候选 9-byte 夹具（有界推断，待 session 对照）**：
  - VLC1 group type 0x03：`03 00 00 00 00 63 00 00 0D`
  - VLC2 type 0x00 变体：`00 00 00 00 00 63 00 00 0D`
  - VLC5：与 VLC2 同形 9 字节
- **10-byte / 2-byte**：结构位置已定位，逐字节生产值未闭合。禁止再以 00 填充冒充生产真实 payload。

#### 四、VOCODER_CMD 确认值（纠正旧错误）

- **观察**：VOCODER_CMD_SET body 字段号是 0x02，值 0x18。
- **有界推断**：成功确认应为 `[0x02, 0x00]`（字段 + 状态零），类比 v1.05 的 3E 60 -> 3E 00。
- **排除**：确认是 18 00。

#### 五、CH1 冻结状态（未完成真机冻结）

- **稳定前缀（多条生产恢复日志一致）**：
  `433550000,433550000,13,99,12345678,directmode,group,slot1,slot1,off,low,8,<vol>,2,99`
- **历史第 13 字段（音量映射）**：曾出现 4（探针早期）、6（H13.md 一处）、8（多次生产冷启动）、2（v1.36 硬编码且无当次冻结证据）。
- **验收**：本步未上机，未完成当前真机完整字符串冻结 + 原始回读哈希。禁止选取任一陈旧 tail 作为 Version A 唯一真值。已完成字段语义与“尾字段可变、前缀稳定”的边界。

#### 六、与 v1.36 / ChatGPT 22:01 的衔接（可复用，不返工）

| 项目 | 步骤 0 结论 |
|------|-------------|
| createDmrVlcSessionFrame 变长 9/9/10/2/9 | 保留 |
| VOCODER_CMD_SET [0x02,0x18] | 保留（与本步一致） |
| slot 请求 type 5 | 保留；callslot 改为候选 0x81 并待证实 |
| Version A 不发 data36 | 保留 |
| VLC ACK = type5 + 首字节 0x43 | 保留（与 0x0801BCC4 一致） |
| LC own/called 用 12345678/99 | 作废；改为 own=13 / called=99 |
| callslot=0x01 作为最终值 | 作废 |
| 10/2 字节补零作为生产夹具 | 作废 |
| CH1 硬编码 8,2,2,99 | 作废；待只读冻结 |
| SysTick 恢复 / dummy AMBE | 属步骤 1-3，本步不改代码 |

#### 七、步骤 0 验收结论

| 计划要求 | 结果 |
|----------|------|
| 恢复生产前序线帧与确认语义 | 部分通过：次序与主要 body 已钉死；[0x19,0x00] 是否由 host 重放、slot ACK 真机形状、channel+30 运行时值仍开放 |
| 冻结当前 CH1 完整字段 | 未通过：仅历史证据表 |
| DMR_CALL_SLOT 准确 slot/options | 部分通过：type/body 形已钉死；候选 0x81 非最终 |
| mode bit7 | 部分通过：与 colorCode 等相关；CC=8 时不 OR 0x80 为有界推断 |
| 五个 VLC 真实 payload 逐字节 | 部分通过：9-byte 身份可候选；10/2 未闭合 |
| 不预设 VOCODER_CMD 确认为 18 00 | 通过：纠正为字段 0x02 的状态零 |

- **总评**：步骤 0 未达到可无条件放行步骤 1 硬编码全部生产夹具，但已足够指导在 v1.36 上修补而非推倒；并关闭密钥当 ID、callslot=0x01 当最终值、10/2 补零当生产值等错误。
- **本条不申请 Version A，不安装 APK，不改业务代码。**
- **请 ChatGPT 独立评审**：是否同意观察/推断/未知划分；步骤 1 是否允许在 callslot 候选 0x81、9-byte LC 候选、[0x19,0x00] 显式策略分支、10/2 标 UNKNOWN 的前提下做离线状态机与单测；CH1 是否必须先完成一次只读冻结再写死 Version A 常量。

### ChatGPT：independent review

- （等待）

### Gemini: response to review

- （等待）
