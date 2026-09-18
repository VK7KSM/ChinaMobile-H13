# H13 P0：Probe 测试基础设施修复计划

> **For implementers:** 本计划对应 `H13.md` §18.53 P0。实施时一次只改一个可证伪点；每轮实机后立刻写 `job.md` 与 `H13.md`，禁止先开下一版再补账。  
> **REQUIRED context:** `Agents.md` H13 handoff、`H13.md` §17.45 / §18.44–18.53、`job.md` 2026-08-07 恢复梯与 v0.86 审查条目。

**Goal:** 把 Interphone Probe 修成可重复、可审计、**不污染生产专网收发** 的实验仪器，使后续 P1（328/330）与 P2（RX PCM 产品化）可以安全上机。

**Architecture:** 将“串口/波特/SRAM 适配器恢复”“MCU 文本控制面恢复”“RF traffic-plane 健康”拆成三层状态机；成功路径恰好一次 SCT baseline reload，失败路径 best-effort 一次且逐步记账；Interphone↔Probe UART 交接收口为单一脚本化流程；APK 版本/模式名/报告字段全链路一致。

**Tech Stack:** Android 8.1 APK（`research/h13_interphone_probe`）、`libserial_port.so`、`/dev/ttyHS0`、无线 ADB、JVM unit tests（Gradle）、`job.md` / `H13.md` 台账。

**Scope 边界（本计划不是什么）:**

| 是 P0 | 不是 P0 |
|---|---|
| Probe 恢复/退出/健康门/交接/记账 | 再采一份 230400 PCM 大样本 |
| 控制面 vs traffic-plane 字段拆分 | 定位 wire-328 根因（→ P1） |
| 无 RF 进出验收 | TX 注入、I/Q、SCT Flash 导出 |
| 版本名与 UI 一致性 | Radio OS / BM / 两小键预设频道产品功能 |

---

## 0. 为何必须先做 P0（证据链摘要）

### 0.1 设备是什么

H11 官方 PDF 定义、且与 H13 **硬件相同** 的产品：中国移动政企 **公专网融合对讲机**（PoC-H11 系）。Android MSM8909 做人机与公网；独立射频子板经 MCU 做 UHF DMR/模拟。研究不得把生产专网侧（定制 Interphone v0.7）长期弄瘫。

### 0.2 已经打通、因而 P0 不再重复证明的能力

- 文本面 57600↔230400 可逆（v0.70）
- raw HPI bridge 进出（v0.31 / v0.71）
- live 27-byte `CHAN_D`（v0.12–v0.18 tape；v0.60+ HPI 侧）
- Voice OUT 8 kHz s16 PCM @57600 与 @230400（v0.61 / v0.78 / v0.84 / v0.86）
- HPI 退出时 `VOCODER_IO_OFF + WORK_MODE_IDLE` + `sct3258chgpro 0` 可恢复后续 tape 生产（v0.84）

### 0.3 P0 要修的事故（每条对应实现任务）

| 事故 | 版本/记录 | 教训 |
|---|---|---|
| HPI 成功后只恢复 SRAM/波特 → 下一轮 tape count≡0 | v0.80→v0.81；§18.44 | transport restore ≠ radio restore |
| 成功路径主流程 reload 后又在 `finally` reload 第二次（~40 s） | v0.86 forensic；§18.52 | 成功恰好一次；失败 best-effort 一次 |
| `Traffic-plane baseline restored=true` 仅由 CONNECT/版本/CH1 证明 | `reloadSctBaselineTrafficPlane()` | 必须拆字段，禁止名过其实 |
| sticky Interphone 单次 force-stop 抢串口；`disable-user` 未 finally 恢复 | v0.7 memread 起反复；19:15–19:35 | 交接流程化 + 退出验证 |
| 双 Interphone 抢 UART → CH9/413.445 | 17:05–17:22 incident | 仅允许 custom v0.7；原厂已卸 |
| v0.86 两轮实机漏记 `job.md` | §18.52 | 测试前写版本、测后立刻 raw |
| UI/报告仍写 v0.79/v0.82 | MainActivity / Control B 文案 | 版本单一真相源 |

### 0.4 当前代码锚点（实施前必读）

| 文件 | 角色 |
|---|---|
| `research/h13_interphone_probe/app/src/main/java/.../InterphoneProbe.java` | 几乎全部状态机；Control A ~2364；Control B ~2678；`hpiExitTrafficPlaneWhileBridged` ~4170；`reloadSctBaselineTrafficPlane` ~4188 |
| `.../MainActivity.java` | mode 标题字符串陈旧（大量 v0.24–v0.75）；Control B 已标 v0.86 |
| `.../ProbeResult.java` | 仅 `success` + `report` 字符串 |
| `app/build.gradle` | `versionCode 86` / `versionName 0.86-tight-uart-drain` |
| `job.md` / `H13.md` §18.52–18.53 | 验收与台账规则 |

**已知双重 reload 位置（必须改）：**

- Control A：成功路径 `reloadSctBaselineTrafficPlane`（约 2588）+ `finally` 无条件再调（约 2656–2659）
- Control B：成功路径约 3020 + `finally` 约 3121–3123

---

## 1. 目标状态机（概念）

每个 Probe 会话只允许下列恢复层，且报告必须分层：

```text
Layer T  Transport
         baud 57600, bridge flag=0, SysTick 原向量, 临时 SRAM 窗口字节一致
         报告: temporaryStateRestored / transportRestored

Layer C  Control plane (MCU text)
         print 可关, AT+DMOCONNECT:0, version 0.3.66, CH1 字段回读一致
         报告: controlPlaneRestored=true|false
         禁止再写 "Traffic-plane baseline restored" 作为此层标签

Layer R  RF traffic plane health
         仅当存在下列之一才可 true:
           (a) 本会话 tape 门已见新 CHAN_D 单元增长（有 RF 时）; 或
           (b) 无 RF 验收时: 明确标记 rxTrafficPlaneHealthy=unverified
               且不得用 CONNECT 冒充 healthy
         有 RF 的后续实验才允许把 (a) 写成 healthy

Layer H  Host production handoff
         Probe 关串口/无 fd; Interphone enabled; 唯一 ttyHS0 owner;
         CH1 Provider/模块一致; GPIO 待机 1/0/0/in; Probe 进程停止
         报告: productionHandoffOk=true|false
```

**成功路径 reload 规则：**

```text
HPI 仍在桥内 → hpiExitTrafficPlaneWhileBridged (VOCODER_OFF + WORK_MODE_IDLE)
→ 等自动退桥 / 校验 flag+vector+marker
→ 若 230400: 栈安全 restore 57600 + Android 重开 57600
→ 恢复临时 SRAM / SysTick
→ 恰好一次 reloadSctBaselineControlPlane (原 reloadSctBaselineTrafficPlane 改名)
→ print 0
→ close serial
→ host handoff
→ finally: 若 controlPlaneReloadDone==false 才 best-effort 一次；否则跳过
```

**失败路径：** 同上但每步 try/catch，**逐步 append 真实结果**，禁止空 `catch (Throwable ignored)` 吞掉关键失败而不写报告。

**明确禁止（历史已否证）：**

- 成功 Control A/B 在 tape 门**之前**强制 `sct3258chgpro 0`（v0.82/v0.83 烧 TX 窗）
- 使用 v0.63–v0.67 的 48/52B EXC_RETURN 桩
- 在 bridge flag≠0 时调用 `0x08010A00` 设 230400（会逼 115200）
- 为 P0 验收要求操作者长时间 TYT 发射（P0 默认无 RF）

---

## 2. 版本与制品约定

| 项 | 值 |
|---|---|
| 计划实现版本 | **v0.87**（P0 总成；若需拆分见任务） |
| versionCode | **87** |
| versionName | `0.87-p0-probe-infra` |
| APK 归档名 | `research/h13_interphone_probe/dist/H13_Interphone_Probe_v0.87_P0ProbeInfra.apk` |
| 捕获目录 | `research/h13_radio/captures/2026-08-07/p0-probe-infra-v087-<timestamp>/`（或次日时间戳） |
| 禁止 | 覆盖 v0.86 及更早 dist APK |

可选拆分（若一次 diff 过大）：

- v0.87a：双重 reload + 报告字段  
- v0.87b：UI/版本名 + 退出清理  
- v0.87c：交接脚本 + 无 RF 双进出验收  

优先 **单版本 v0.87 一次验收**，减少设备轮次。

---

## 3. 实施任务

### Task 1：恢复结果类型与报告字段（无设备）

**Files:**

- Modify: `InterphoneProbe.java`（新增小类型或包内 static class）
- Modify: `ProbeResult.java`（可选扩展结构化字段；至少保证 report 文本含标准键）
- Test: `app/src/test/java/...` 新增纯字符串/状态标志单测

**设计：**

```text
class RestoreOutcome {
  boolean transportRestored;
  boolean controlPlaneRestored;   // CONNECT + version + CH1 only
  boolean controlPlaneReloadAttempted;
  boolean controlPlaneReloadSucceeded;
  String controlPlaneDetail;      // STARTUP/cmp/CONNECT/version/CH 原文摘要
  // 绝不根据 CONNECT 写 trafficPlaneHealthy=true
}
```

报告键名（固定英文，便于 grep）：

```text
transportRestored: true|false
controlPlaneRestored: true|false
controlPlaneReloadCount: 0|1
rxTrafficPlaneHealthy: true|false|unverified
productionHandoffOk: true|false|skipped
```

**删除/改写：** 任何 `Traffic-plane baseline restored` 字面量 → `controlPlaneRestored`。

**Step:** 写单测断言旧字符串不出现、新键出现 → 改实现 → 测试绿。

**验收：** JVM test 通过；无实机。

---

### Task 2：成功路径单次 reload；finally 条件化（核心）

**Files:**

- Modify: `InterphoneProbe.java`  
  - `runVoiceOutPcm57600ControlA`  
  - `runVoiceOutPcm230400ControlB`  
  - 共享 boolean `controlPlaneReloadDone`（方法局部，非全局静态）

**逻辑：**

```text
boolean controlPlaneReloadDone = false;
boolean transportRestored = false;
try {
  ...
  // success path after transport restore:
  reloadControlPlaneOnce(...);   // sets controlPlaneReloadDone=true on attempt
  ...
} finally {
  // emergency transport only if !transportRestored
  if (!controlPlaneReloadDone && input/output usable @57600) {
    bestEffortReloadControlPlane(...); // still sets controlPlaneReloadDone=true
    // each substep logged; failures appended, not swallowed silently
  } else if (controlPlaneReloadDone) {
    append(report, "controlPlaneReloadCount", 1);
    append(report, "finallyReloadSkipped", true);
  }
  close();
}
```

**禁止：** finally 在成功后无条件再调 `reloadSctBaseline*`。

**单测：** 用可注入的假计数器或 package-visible 钩子更难；优先 **代码审查 + 实机 logcat 计数 `sct3258chgpro`**。若可抽 `ReloadPolicy` 纯函数，则单测 policy。

**验收：** 一次成功 Control 路径的 logcat 中，`sct3258chgpro 0` **恰好 1 次**（不含历史无关进程）。

---

### Task 3：失败路径逐步记账（禁止静默吞异常）

**Files:**

- Modify: Control A/B `finally` 与 emergency 分支  
- Modify: `reloadSctBaselineTrafficPlane` 改名为 `reloadSctControlPlane`（或保留方法名但改报告）

**要求：**

每个恢复步骤：

```text
append(report, "restore.step.<name>", "ok|fail: <message>")
Log.i/w 同步
```

覆盖至少：

- `hpiExitTrafficPlaneWhileBridged`
- baud restore one-shot
- bridge flag / SysTick restore
- `sct3258chgpro 0` 响应摘要
- CONNECT / version / CH1
- `print 0`
- `close()`

**catch 策略：** 可继续下一步，但 **必须** 写入 report；最终 `controlPlaneRestored` 仅当硬门槛（CONNECT+version+CH1）全过。

**验收：** 人为制造早期失败（例如未停 Interphone）时，报告含逐步 fail，而非空 FAIL。

---

### Task 4：UI / mode 标题 / 报告版本单一真相源

**Files:**

- Modify: `MainActivity.java` 标题映射  
- Modify: `InterphoneProbe.java` 内过期文案（如 “Control B v0.82”、“v0.79”）  
- Modify: `app/build.gradle` → 87 / `0.87-p0-probe-infra`  
- Optional: 从 `BuildConfig.VERSION_NAME` 拼标题，避免三处手写

**规则：**

- 当前默认实验 mode：`voice_out_pcm_57600_control_a` / `voice_out_pcm_230400_control_b` 标题必须含 **v0.87**
- 历史 mode 标题可保留历史版本号，但须标注 `(legacy mode)`，避免误认为当前构建
- logcat 首行：`Probe build versionCode=87 versionName=... mode=...`

**验收：** `dumpsys package` 与 UI 标题、报告头、APK 文件名一致。

---

### Task 5：Probe 退出确定性（进程 / 串口 / Activity）

**Files:**

- Modify: `InterphoneProbe.close()` 与 `MainActivity` 结束路径  
- Optional: 结束时 `finishAffinity()` 仅当 intent extra `auto_exit=true`（ADB 无人值守）

**要求：**

1. 任何 `finish(...)` 路径最终 `close()` 串口  
2. 报告含 `serialClosed: true`  
3. ADB 验收：`lsof`/`ls -l /proc/*/fd` 无 Probe PID 持有 `/dev/ttyHS0`  
4. 默认不 `disable-user` Probe；用 `am force-stop net.elfradio.h13interphoneprobe`  
5. 不在测试结束后留下 RUN 线程占用（executor 任务结束；`onDestroy` shutdown）

**验收：** 两次运行后 `ps` 无探针，或仅 frozen/cached 且无 tty fd。

---

### Task 6：Interphone↔Probe 串口交接单一流程

**原则：** 交接逻辑优先放在 **主机 PowerShell/脚本**（可审计、可 finally），APK 内不执行 `pm disable-user`（无 shell 权限时也不可靠）。

**Create:**

- `research/h13_radio/tools/h13_probe_session.ps1`（或 `.sh` + 本机 adb 包装）

**会话状态机：**

```text
precheck_readonly
  → record: build fingerprint, Interphone enabled, tty owner, GPIO, CH1
stop_interphone
  → force-stop; sleep 1s; if PID reappears force-stop again; verify no owner
  → OPTIONAL: pm disable-user ONLY if env ALLOW_DISABLE_INTERPHONE=1
install_or_reuse_probe_v087
start_probe(mode)
await_probe_result (logcat / UI dump)
stop_probe
restore_interphone
  → pm enable if was disabled
  → start MainActivity
  → verify: enabled=1, sole tty owner, CH1, GPIO 1/0/0/in
append_job_md_snippet (operator/AI pastes)
```

**硬规则：**

- `finally` 等价路径：脚本 `try/finally` **永远** 跑 `restore_interphone`
- 禁止会话结束时 Interphone 仍为 disabled-user
- 禁止 enable 原厂 `com.bozhou.interphone`（已卸载；不得恢复安装用于测试）

**验收：** 人为中断（Ctrl+C / adb 断）后手动跑 restore 子命令仍能拉起 v0.7。

---

### Task 7：无 RF 双进出验收（P0 出门条件）

**不发射。** 使用 **无 RF 冒烟路径**，避免依赖 TYT：

**推荐 mode（二选一，优先改动小者）：**

1. **复用** 已有 `uart_hpi_bridge_230400_smoke` / 或 Control B 的“无 tape 门”变体 **仅当** 能在无 RF 下完整走：握手 →（可选短 bridge）→ 单次 control reload → handoff  
2. 或新增 mode `p0_session_smoke`：  
   - 握手 + version + CH1  
   - **不** 开 Voice OUT 长捕获  
   - 执行一次 `reloadSctControlPlane`  
   - close + 脚本 handoff  

**两次循环：**

```text
for i in 1..2:
  session_smoke
  assert controlPlaneReloadCount == 1
  assert finallyReloadSkipped == true on success
  assert Interphone productionHandoffOk
  assert GPIO baseline
  assert Probe no tty
sleep short
repeat
```

**捕获目录：** 每次独立时间戳目录；保存 logcat 全文、报告文本、脚本 precheck/postcheck。

**PASS 标准（与 §18.53 一致）：**

- [ ] 两次均 Interphone enabled  
- [ ] 两次均 Probe 无 `/dev/ttyHS0`  
- [ ] 唯一 UART owner = Interphone  
- [ ] CH1 正确（433.550 配置族，与当前 DB 一致）  
- [ ] GPIO `dmr_switch=1 audio_switch=0 pa_enable=0 ptt_d=in`  
- [ ] 每次成功路径 **没有第二次** `sct3258chgpro 0`  
- [ ] 报告无 `Traffic-plane baseline restored` 伪标签  
- [ ] `job.md` 本版本条目完整（假设、APK hash、raw 摘录、分析）  
- [ ] `H13.md` 追加 §18.xx 条目  

**FAIL 则：** 禁止进入 P1 实机；修 v0.87.x 后重跑双进出。

---

### Task 8：文档与台账（强制，非可选）

**Before first install of v0.87：**

```text
## YYYY-MM-DD HH:MM Local - v0.87 - P0 probe infrastructure

### Grok: test record
- Hypothesis and one changed variable:
- Preconditions and rollback:
- APK/version/hash and capture directory:
- Raw output / decisive verbatim excerpts: (pending run)
- Grok analysis: (pending run)
```

**After each run：** 同一条目下立即补 raw / hash / 分析。  
**ChatGPT 审查：** 若双进出结果与预期矛盾或恢复不确定，**暂停**等审查（`Agents.md` 规则 6）。  
**若结果符合本计划预批的成功标准：** 可写完记录后继续修小缺陷，无需空等。

**H13.md：** 追加一节，结构对齐 18.52（观察 / 推断 / 未决 / 下一步）。

---

## 4. 建议实施顺序与工期

| 顺序 | 任务 | 设备 | 预计 |
|---:|---|---|---|
| 1 | Task 1 字段与单测 | 否 | 短 |
| 2 | Task 2 单次 reload | 否 | 中 |
| 3 | Task 3 失败记账 | 否 | 短 |
| 4 | Task 4 版本/UI | 否 | 短 |
| 5 | Task 5 退出清理 | 否 | 短 |
| 6 | 构建 APK + 签名校验 | 否 | 短 |
| 7 | Task 6 交接脚本 | 否（可先 dry-run adb） | 中 |
| 8 | Task 7 无 RF 双进出 | **是** | 1 个设备会话 |
| 9 | Task 8 写 job.md / H13.md | 否 | 短 |

**总计：** 以代码为主的半日至一日 + 一次短设备窗。  
**不安排** 长时 TYT 发射；P0 通过后再谈 P1 离线表与可选 MCU 观测。

---

## 5. 风险与回滚

| 风险 | 缓解 |
|---|---|
| `sct3258chgpro 0` 后模块停在错误频道 | reload 内已有 CH1 set+readback；handoff 后再读 Provider |
| 脚本 disable 后 adb 掉线 | finally 本地可重连执行 enable；文档写明手动恢复命令 |
| 改 finally 后失败路径漏恢复 230400 | 保留 emergency baud restore 块；仅跳过**重复** control reload |
| 误开原厂包 | 脚本禁止 install 原厂；precheck 发现则 ABORT |
| 把 P0 验收做成有 RF | 计划明确无 RF；有 RF 留给 P1/P2 |

**设备回滚金命令（写进脚本注释）：**

```text
adb shell am force-stop net.elfradio.h13interphoneprobe
adb shell pm enable net.elfradio.h13interphone
adb shell am start -n net.elfradio.h13interphone/com.bozhou.interphone.ui.talk.MainActivity
# 然后人工确认专网可收发 / Provider CH1
```

**波特/桥不确定时：** 停止发协议猜测命令；保存日志；按既有 reboot/恢复路线（`Agents.md`）。

---

## 6. P0 完成后如何接到 P1 / P2（预告，不在本计划实施）

```text
P0 PASS
  → P1 离线：全捕获 328/330 表 + walker 候选头校验（无设备或只读）
  → P1 可选：单次 MCU pre-UART 长度观测（需 P0 仪器）
  → P2：159→160 补样实时流（依赖 P0 handoff，不依赖 P1 完全闭合）
```

P0 **不** 修改 wire-328 产品语义；v0.85 walker 保持，除非发现安全 bug。

---

## 7. 完成定义（Definition of Done）

1. dist 中存在 v0.87 APK，hash 记入 job.md  
2. 源码中成功路径无法双重 `sct3258chgpro`（代码审查 + 实机计数）  
3. 报告字段分层，无伪 “traffic restored”  
4. 交接脚本 + 双次无 RF 会话 PASS  
5. 生产 Interphone v0.7 收发未被本轮损坏（用户可做一次短听感确认，**非** 长 TX 实验）  
6. `H13.md` / `job.md` 闭环  

达到以上 6 点后，才允许宣称 “P0 完成”，并打开 P1。

---

## 8. 实现时不要做的事

- 不重放 SCT Flash loader 历史失败路径  
- 不把 Control B 大样本 PCM 当 P0 验收  
- 不启用双 Interphone  
- 不修改 Interphone v0.7 生产包业务逻辑（P0 只动 Probe + 主机脚本 + 文档）  
- 不在未写 job.md 条目时安装/启动新版本  

---

## 附录 A：关键历史引用

- `H13.md` §18.44 恢复根因  
- `H13.md` §18.46–18.47 v0.84 Control A/B PASS  
- `H13.md` §18.52 v0.86 双重 reload 与健康状态  
- `H13.md` §18.53 P0 列表  
- `job.md` 17:07 dual ownership incident；19:22–19:35 v0.86 forensic  
- `Agents.md` Proven USART ladder；Review ledger 格式  

## 附录 B：建议 grep 验收命令（主机）

```text
# 报告内不得再出现
Traffic-plane baseline restored

# 单次会话 logcat 中 chgpro 计数（示意）
sct3258chgpro
```
