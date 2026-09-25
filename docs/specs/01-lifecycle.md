# OpsPilot V0.1 核心生命周期与状态机

> Status: FROZEN  
> Version: 0.1  
> Freeze revision: FINAL-FREEZE-20260925  
> 冻结日期：2026-09-25  
> 范围：Incident与各核心对象的合法状态、run预算、停止与重新进入调查。  
> 本文是已合并的正式实现规格。权威清单见 [SPEC-MANIFEST.md](SPEC-MANIFEST.md)。FROZEN 表示规格定稿，不表示代码、迁移或故障实验已经通过。

## 1. 为什么需要状态机

“状态机”不用理解得很复杂。

它只是明确：

> 一个故障现在处于什么阶段，下一步允许去哪里，不允许跳到哪里。

例如：

```text
刚创建故障
不能直接变成
“已恢复”
```

必须经过：

```text
调查
→ 诊断
→ 处理
→ 验证
→ 已恢复
```

这样以后无论 Java 后端、前端还是 AI，都不能各自理解一套业务流程。

---

## 2. V0.1 的 Incident 状态正式冻结

V0.1 只存在以下 8 个故障状态：

```text
CREATED
INVESTIGATING
DIAGNOSED
AWAITING_APPROVAL
EXECUTING
VERIFYING
RESOLVED
CANCELLED
```

不增加：

```text
FAILED
PROCESSING
PENDING
FINISHED
SUCCESS
```

等含义模糊或重复的状态。

---

## 3. 每个状态到底是什么意思

### 3.1 CREATED

中文：

**待调查**

表示：

- Incident 已经创建；
- 基本故障信息已经存在；
- 尚未启动 AI 调查。

此时允许：

```text
开始调查
取消故障
```

---

### 3.2 INVESTIGATING

中文：

**调查中**

表示：

AI 正在：

- 查看已有观测；
- 管理待验证原因；
- 调用只读能力；
- 创建新的 Observation；
- 建立 Evidence；
- 排除或者支持 Hypothesis。

只有这个状态：

> Agent 调查循环允许自动调用只读能力。

---

### 3.3 DIAGNOSED

中文：

**已有诊断**

这是一个非常重要的定义。

`DIAGNOSED` 不表示：

> 已经 100% 找到根因。

它只表示：

> 当前这一轮调查已经停止，并且产生了一份合法的 Diagnosis。

因此下面三种情况都属于：

```text
Incident.status = DIAGNOSED
```

#### 已定位主要原因

```text
Diagnosis.conclusion_type
=
PRIMARY_CAUSE_IDENTIFIED
```

#### 存在可能原因

```text
POSSIBLE_CAUSE
```

#### 暂时无法确定

```text
UNDETERMINED
```

因此：

> `DIAGNOSED + UNDETERMINED`

完全合法。

调查无果不是系统失败。

---

### 3.4 AWAITING_APPROVAL

中文：

**等待批准**

表示：

- 已经存在一个处理方案；
- 处理方案包含会改变系统状态的操作；
- 已创建 ApprovalRequest；
- 正在等待用户决定。

例如：

> 是否批准重新启动 Statistics Consumer？

---

### 3.5 EXECUTING

中文：

**正在处理**

表示：

已经获得批准，确定性后端正在执行处理操作。

V0.1 唯一支持：

```text
service.restart
```

AI 在此阶段没有执行权。

---

### 3.6 VERIFYING

中文：

**正在验证恢复**

表示：

处理动作已经结束，或者用户已经在 OpsPilot 外部完成处理。

系统正在依据：

```text
RecoveryPolicy
```

判断业务是否真正恢复。

此时重点不是：

> 命令有没有成功。

而是：

> 故障影响是不是已经消失。

---

### 3.7 RESOLVED

中文：

**已恢复**

表示：

RecoveryVerification 已经明确满足恢复标准。

只有：

```text
VERIFYING → RESOLVED
```

可以产生 `RESOLVED`。

任何其他状态：

**都不能直接宣布故障已恢复。**

---

### 3.8 CANCELLED

中文：

**已取消**

表示：

用户明确终止 OpsPilot 对本次 Incident 的处理。

必须注意：

> CANCELLED ≠ RESOLVED

取消处理不代表业务已经恢复。

---

## 4. Incident 主状态机

```text
CREATED --Start--> INVESTIGATING --合法收束--> DIAGNOSED
DIAGNOSED --Continue--> INVESTIGATING
DIAGNOSED --合法写方案--> AWAITING_APPROVAL
DIAGNOSED --外部处理后验证--> VERIFYING
AWAITING_APPROVAL --Reject/Cancel Approval--> DIAGNOSED
AWAITING_APPROVAL --Approve + Execution准入--> EXECUTING
EXECUTING --Execution失败/结果不确定收束--> DIAGNOSED
EXECUTING --Execution成功--> VERIFYING
VERIFYING --PASSED--> RESOLVED
VERIFYING --FAILED--> INVESTIGATING（新run，同一Investigation）
VERIFYING --INCONCLUSIVE--> DIAGNOSED
```
CREATED、INVESTIGATING、DIAGNOSED、AWAITING_APPROVAL 可取消为 CANCELLED；
取消等待审批中的 Incident 时，同事务取消 PENDING Approval 并使未执行方案不可执行。
EXECUTING、VERIFYING 不允许用户中途强制取消；RESOLVED 和 CANCELLED 为终态，不支持 reopen。
Stop 不是第九种 Incident 状态，先持久化停止意图，后协作式收束。

---

## 5. 为什么验证失败以后回到 INVESTIGATING

例如：

Agent 判断：

> Consumer 停止。

提出：

> 重启 Consumer。

用户批准。

执行：

```text
service.restart
```

执行成功。

但是验证发现：

```text
Consumer = UP

但是：

backlog 仍然不断增加
```

这说明：

> 处理动作成功，不等于原来的诊断已经完整解释问题。

因此：

```text
VERIFYING
    ↓
FAILED
    ↓
INVESTIGATING
```

重新进入**同一次 Investigation**继续调查。

不能：

```text
→ RESOLVED
```

也不应该自动：

```text
→ CANCELLED
```

---

## 6. 执行失败为什么回到 DIAGNOSED，而不是 INVESTIGATING

例如：

诊断正确：

> Consumer 已停止。

但是：

```text
DockerServiceExecutor
```

执行重启时因为 Docker daemon 异常失败。

这不能证明：

> 原来的诊断错误。

因此：

```text
EXECUTING
    ↓
ActionExecution = FAILED
    ↓
DIAGNOSED
```

用户可以决定：

1. 重新提出处理方案；
2. 继续调查；
3. 取消故障处理。

---

## 7. Verification 无法确认怎么办

RecoveryVerification 存在三种主要终态：

```text
PASSED
FAILED
INCONCLUSIVE
```

#### PASSED

恢复标准已经满足：

```text
→ RESOLVED
```

#### FAILED

明确没有恢复：

```text
→ INVESTIGATING
```

#### INCONCLUSIVE

无法确认。

例如：

```text
Prometheus 暂时不可访问
```

系统无法知道：

> P99 是否已经恢复。

这时候：

```text
→ DIAGNOSED
```

而不是：

```text
→ RESOLVED
```

用户之后可以：

- 重新执行恢复验证；
- 继续调查；
- 取消处理。

原则：

> **不知道有没有恢复，就等于不能宣布恢复。**

---

## 8. 支持“用户在系统外完成处理”

OpsPilot 不应该要求：

> 所有故障都必须由 OpsPilot 自己执行修复。

现实中用户可能：

1. OpsPilot 完成调查；
2. 用户自己去服务器处理；
3. 回到 OpsPilot；
4. 点击：

> **验证恢复情况**

因此允许：

```text
DIAGNOSED
    ↓
用户声明已在外部处理
    ↓
VERIFYING
```

仍然必须跑：

```text
RecoveryPolicy
```

只有验证通过：

```text
→ RESOLVED
```

这样 OpsPilot 不会变成：

> “只有通过我执行命令的故障才能关闭。”

---

## 9. 唯一 Investigation 与多次逻辑运行周期

#### 运行周期与预算作用域

一个 Incident 最多一条 Investigation；首次开始后复用同一调查工作空间。运行周期是该工作空间内的逻辑序号，不创建 `InvestigationRun` 表或新的领域实体。

**12 次 Capability、480 秒墙钟时间正式归属于一次 active investigation run，不再是整个 Investigation 永久共享的一次性额度。** 这是本次预算作用域裁决；每次显式继续确实可以获得新一轮额度。它不构成 Incident 生命周期累计调用次数或累计费用上限。

持久化当前运行控制：
`current_run_no`、`current_run_started_at`、`current_run_capability_count`、
`consecutive_ai_failure_count`、`stop_requested_at`、`stop_requested_by`。
保留 `capability_call_count` 作为全工作空间累计准入次数；它不是当前轮剩余额度。

首次 Start、显式 Continue、RecoveryVerification FAILED 回到 INVESTIGATING，调用统一的
`resumeInvestigation()`。同一短事务中校验允许的来源状态，建立或读取唯一 Investigation，然后：
```text
current_run_no += 1
current_run_started_at = now
current_run_capability_count = 0
consecutive_ai_failure_count = 0
stop_requested_at = null
stop_requested_by = null
Incident -> INVESTIGATING（expectedStatus + lock_version）
Timeline：记录来源、旧轮号、新轮号和本轮预算
```
第一次提交的运行周期为 1；后续单调递增。不会删除 Invocation、AgentStep、Observation、Evidence 或 Diagnosis；`step_no` 在同一 Investigation 内继续累计。

`max_capability_calls`、`max_duration_seconds`、`agent_step_timeout_seconds`、
`max_consecutive_ai_failures` 是初次建立 Investigation 时的配置快照；V0.1 各轮复用这些限制值，Continue 不允许客户端传任意新额度。

本轮截止时间为 `current_run_started_at + max_duration_seconds`；DIAGNOSED 或等待审批的时间不占下一轮额度。
**应用重启不是新运行周期**：不递增轮号、不清 Stop、不重置计数、不刷新截止时间；停机时间计入当前轮墙钟时间。

INVESTIGATING 期间每次准入最多一个 AI Step 或调查 OBSERVE 外部工作；用户 Stop 后允许已经准入的在途工作在原超时边界内结束，不启动后续调查工作。

---

## 10. Investigation 不再维护第二套复杂状态机

Investigation 不维护第二套业务状态机。是否允许调查仍以 Incident.status 为准；
current_run_no 和停止标记只是运行控制属性，不是新的 Incident 状态。
AgentStepRecord 与调查 CapabilityInvocation 持久化 run_no；
Observation 的 run 由其唯一来源 Invocation 追溯，Diagnosis 保存产生时 run_no，便于审计。

---

## 11. 调查循环必须有确定性停止条件

本轮出现任一确定性退出条件，不得永久停在 INVESTIGATING：
- 用户已提交 Stop；
- current_run_capability_count 达到 max_capability_calls（默认 12）；
- 当前 run 的 480 秒墙钟截止时间到达；
- 当前 run 连续 AI 调用／非法输出失败达到阈值（默认 3）。

Agent 可在允许时提出 COMPLETE_INVESTIGATION，附合法 Diagnosis Draft。
Java 对本轮结果、主假设、冻结 Evidence 引用和来源进行校验，合格才创建新 Diagnosis。
到退出边界不启动额外 AI Step 来“凑答案”；只有已取得的本轮合法草稿可用于收束，否则创建 UNDETERMINED。
`AGENT_COMPLETED / USER_STOPPED / CAPABILITY_BUDGET_EXHAUSTED / INVESTIGATION_TIMEOUT / AI_RUNTIME_UNAVAILABLE`
记录实际收束原因。

Stop 202 只代表停止意图已提交。允许原轮已准入的外部调用在其超时内完成；
若当前没有在途调用，立即确定性收束。不得拿以前轮次的旧 Diagnosis 冒充本轮新诊断。

#### 停止与准入的唯一线性化边界

Stop、AgentStep 准入、Capability 准入统一按 `Incident -> Investigation` 顺序锁定。
同一短事务检查 `Incident.status == INVESTIGATING`、`expected run_no == current_run_no`、
`stop_requested_at IS NULL`、本轮 deadline 与预算；Capability 还要检查 Registry、归属、Binding、
参数、唯一 Provider 和 Duplicate Guard。Guard 全部通过才创建 RUNNING 记录。

Capability 准入同时增加 `current_run_capability_count` 与累计 `capability_call_count`，然后提交。
**准入事务 COMMIT 定义为调用已经获准开始，不代表远端已经收到请求。** 所有网络调用发生在提交之后，事务中不得等待网络。

Stop 事务先提交：后续准入必须拒绝；准入先提交：该在途工作允许完成，即使物理发包发生在 Stop 202 之后。
这是一致的协作式停止合同，不承诺中止已经获准的网络操作。

返回结果必须匹配其 `investigation_id + run_no + step/invocation id`。
AI 的领域变更还必须验证当前轮号、Incident 当前阶段和 Stop。
同轮 Stop 后，仅在途合法 COMPLETE_INVESTIGATION 可用于收束，其他 Intent 不再开展调查。
旧轮 AI 结果只保留运行审计，不产生新 Hypothesis／Evidence／Diagnosis 或新外部调用。

在途 Provider 的真实结果仍可写入原 Invocation 并产生归属原调用的不可变 Observation；
这不允许改变新轮状态或计数。新轮默认采用本轮观测和以前 Diagnosis 已冻结的历史证据摘要；
不得把旧轮未被诊断引用的迟到结果自动当成本轮新事实。历史完整性和当前控制权是两件事。

---

## 12. 到预算仍然查不出来怎么办

正式规定：

> OpsPilot 不允许为了“必须给答案”而强行猜根因。

因此允许：

```text
Diagnosis:
    conclusion_type = UNDETERMINED
```

用户界面：

> **暂时无法确定原因**

并展示：

- 已经检查了什么；
- 已经排除了什么；
- 当前掌握哪些证据。

这不是失败。

这是合法结果。

---

## 13. Hypothesis 生命周期

`Hypothesis`

中文：

**待验证原因**

状态：

```text
PENDING
SUPPORTED
INSUFFICIENT_EVIDENCE
REFUTED
```

含义：

#### PENDING

刚提出，尚未验证。

#### SUPPORTED

当前已有证据支持。

#### INSUFFICIENT_EVIDENCE

已有部分信息，但不足以支持或排除。

#### REFUTED

已有证据明确反驳。

---

## 14. Hypothesis 状态不是永久终态

真实调查过程中，新证据可能改变判断。

例如：

```text
H1 Redis 异常

一开始：
SUPPORTED

后来：
发现 Redis 延迟只是监控误差

变成：
REFUTED
```

因此 Hypothesis 当前状态允许变化。

但是：

> 每一次状态变化都必须写入 TimelineEvent。

这样当前状态可以修改，

历史不能消失。

---

## 15. Observation 生命周期

`Observation`

中文：

**观测结果**

规则非常简单：

### Observation 创建以后不可修改。

例如：

```text
O-001

Redis P95 latency = 623ms
时间：15:21:32
```

后面即使重新查询得到：

```text
Redis P95 = 14ms
```

也不能修改：

```text
O-001
```

而应该创建：

```text
O-002
```

这样才能保留：

> 当时到底看到了什么。

---

## 16. 查询失败不创建虚假的 Observation

例如调用：

```text
metrics.query
```

超时。

正确：

```text
CapabilityInvocation
status = FAILED
```

时间线：

> Prometheus 查询失败。

错误：

```text
Observation:
Prometheus unavailable = true
```

除非：

> “Prometheus 是否可用”

本身就是这次查询的真实目标。

原则：

> 工具调用失败和业务观测结果是两回事。

---

## 17. Evidence 生命周期

Evidence 不是复制一份 Observation。

而是：

```text
Observation
      │
      │ SUPPORTS / REFUTES / CONTEXT
      ▼
Hypothesis
```

一条 Evidence 表达：

> 某条真实观测，与某个待验证原因之间是什么关系。

---

### 17.1 Evidence 关系类型

V0.1 只保留：

```text
SUPPORTS
REFUTES
CONTEXT
```

#### SUPPORTS

支持该原因。

#### REFUTES

反驳该原因。

#### CONTEXT

提供背景信息，但不足以直接支持或者反驳。

---

### 17.2 Evidence 谁创建

流程：

```text
Agent
提出关系

↓

后端检查：

Observation 是否存在？
Hypothesis 是否存在？
是不是属于同一个 Investigation？
关系类型是否合法？

↓

后端创建 Evidence
```

后端不负责判断：

> Redis 623ms 是否真的意味着 Redis 故障。

后端只保证：

> AI 引用的证据是真实存在、关系合法、可审计的。

---

## 18. Evidence 创建以后不修改

V0.1 采用：

> **Evidence append-only**

如果后续调查推翻原判断：

不要删除旧 Evidence。

而是：

- 创建新的 Observation；
- 建立新的 Evidence；
- 修改 Hypothesis 当前状态；
- 创建新的 Diagnosis。

这样事故历史完整保留。

---

## 19. Diagnosis 不是覆盖更新，而是版本化新增

第一次调查可能得到：

```text
Diagnosis v1

POSSIBLE_CAUSE

可能是 Redis 性能异常。
```

执行某些操作后仍然没有恢复。

继续调查。

后来得到：

```text
Diagnosis v2

PRIMARY_CAUSE_IDENTIFIED

真正原因是数据库连接池耗尽。
```

不能把 v1 直接覆盖掉。

应该：

```text
Diagnosis v1
Diagnosis v2
```

都保留。

当前页面默认展示：

> 最新 Diagnosis。

---

## 20. Diagnosis 类型约束

| conclusion_type | 主假设 | 本 Diagnosis 冻结引用的证据 | 允许写方案 |
|---|---|---|---|
| PRIMARY_CAUSE_IDENTIFIED | 必须属于当前 Investigation | 至少一条关联该主假设的 SUPPORTS Evidence | 还需 Capability 与审批 |
| POSSIBLE_CAUSE | 必须属于当前 Investigation | 同样至少一条关联该主假设的 SUPPORTS Evidence | 还需 Capability 与审批 |
| UNDETERMINED | 可为空 | 可引用真实历史调查信息，禁止编造支持 | 不允许 |

Java 校验引用、归属及结构，不冒充通用语义裁判。PRIMARY 与 POSSIBLE 的强度差异属于模型提议及验收质量，不是有无真实证据的差异。
Evidence 一旦创建不可重解释、不可修改、不可删除、不版本化；重新形成结论通过新增 Diagnosis。

---

## 21. Diagnosis 和 Hypothesis 状态不能混用

Hypothesis 表达：

> 一个候选原因目前验证到什么程度。

```text
PENDING
SUPPORTED
INSUFFICIENT_EVIDENCE
REFUTED
```

Diagnosis 表达：

> 这次调查最终能得出多明确的结论。

```text
PRIMARY_CAUSE_IDENTIFIED
POSSIBLE_CAUSE
UNDETERMINED
```

这是两个完全不同的概念。

---

## 22. RemediationPlan 生命周期

V0.1 做进一步收缩：

> 一个 RemediationPlan 只包含一个 RemediationAction。

因为当前只有：

```text
service.restart
```

没必要第一版实现复杂多步骤执行计划。

关系：

```text
Diagnosis
   ↓
RemediationPlan
   ↓
RemediationAction
```

Plan 创建后关联：

> 当时最新的 Diagnosis。

---

## 23. 处理方案不能偷偷跟着新 Diagnosis 改

例如：

```text
Diagnosis v1
→ 建议重启 Consumer
```

此后用户选择：

> 继续调查。

生成：

```text
Diagnosis v2
```

那么旧：

```text
RemediationPlan v1
```

不能继续悄悄执行。

因为它基于旧诊断。

原则：

> 新 Diagnosis 产生以后，旧的未执行处理方案视为过期。

需要处理：

> 重新生成新的 RemediationPlan。

这是为了防止：

> 用昨天的判断执行今天的操作。

---

## 24. ApprovalRequest 生命周期

状态：

```text
PENDING
APPROVED
REJECTED
CANCELLED
```

状态只能向前。

例如：

```text
PENDING → APPROVED
```

以后不能改成：

```text
REJECTED
```

审批决定是审计记录。

---

### 24.1 批准

```text
AWAITING_APPROVAL
→
EXECUTING
```

前提：

后端重新检查：

- ApprovalRequest 仍有效；
- RemediationPlan 没有过期；
- Diagnosis 仍是当前版本；
- Capability 仍然允许；
- Target Resource 仍存在；
- 操作参数符合约束。

---

### 24.2 拒绝

```text
ApprovalRequest = REJECTED
```

Incident：

```text
AWAITING_APPROVAL
→
DIAGNOSED
```

拒绝处理方案：

不等于取消 Incident。

用户可以：

- 继续调查；
- 提出新方案；
- 取消故障处理。

#### 写操作之前冻结恢复合同

批准并创建 ActionExecution 的同一短事务，除审批与当前 Diagnosis 校验外，必须选择目标资源唯一合法 ACTIVE RecoveryPolicy，
解析所有 required Criterion、验证类型与资源归属、采样规则和 Provider Binding，
并保存 `recovery_policy_id`、`recovery_policy_version`、`recovery_policy_snapshot`。

没有策略或存在歧义时，在任何 CHANGE 发出前拒绝准入；Approval 保持 PENDING，Incident 保持 AWAITING_APPROVAL，
返回既有 `RECOVERY_POLICY_NOT_FOUND` 或 `RECOVERY_POLICY_AMBIGUOUS` 等明确错误。
快照属于 Execution 的受信执行上下文，不属于 AI Remediation 的 `{}` 参数。

快照固定 Policy 版本、Criterion 顺序、目标资源、受控参数、采样、谓词、required、阈值与时间限制。
执行成功后的完成事务直接复制此快照创建唯一 RecoveryVerification，
不重新 SELECT “此刻的 ACTIVE Policy”。Policy 后续退休或升级不得阻断真实执行结果的落账。

运行时仍检查访问授权与 Binding，不能通过旧快照绕过被撤销的访问权限；
无法读取 required 数据按 UNKNOWN 进入确定性结果矩阵，而不是回滚已经发生的外部操作。
外部人工处理的 `verify-recovery` 在自己的创建事务选择唯一 ACTIVE Policy 并冻结快照，不伪造 Execution。

---

## 25. V0.1 允许自审批

因为是单用户求职演示项目：

```text
创建人 == 审批人
```

允许。

但仍然必须记录：

```text
decided_by
decided_at
decision
comment
```

未来企业版的：

> 创建者与审批者职责分离

明确属于非 V0.1 范围。

---

## 26. ActionExecution 生命周期

只有：

```text
ApprovalRequest = APPROVED
```

才能创建 ActionExecution。

状态：

```text
PENDING
RUNNING
SUCCEEDED
FAILED
```

---

### 26.1 SUCCEEDED

这里只表示：

> 处理动作成功执行。

不是：

> 故障恢复。

接下来必须：

```text
→ VERIFYING
```

---

### 26.2 FAILED

表示：

> 动作自身没有成功完成。

Incident：

```text
→ DIAGNOSED
```

绝不允许因为执行失败：

```text
→ RESOLVED
```

#### CHANGE 不重放；只读 reconciliation 可有界重试

ActionExecution 保留 `PENDING / RUNNING / SUCCEEDED / FAILED`，不新增 UNKNOWN 状态。
只有成功条件更新 PENDING -> RUNNING 的 Worker 可以派发一次 CHANGE；HTTP Client 禁止隐藏的写请求自动重试。
应用看到 RUNNING 不足以证明请求未发送，恢复时绝不重新 restart。

结果不确定时复用 `ServiceRuntimeInspector`，执行显式、有界的只读核对。
该分支不创建 CapabilityInvocation 或 Observation，不进入 AI，不消耗 Investigation 预算；
每次核对计入 ActionExecution 自己的审计和 Timeline。

持久化 `reconciliation_attempt_count`、`max_reconciliation_attempts`、
`last_reconciliation_at`、`reconciliation_deadline_at`。
每次核对前在短事务中检查状态、次数和截止时间，并**先增加次数、写尝试时间、提交，再发出 inspect**。
崩溃消耗已登记的尝试，但只要总次数及截止时间仍允许，重启后可以继续只读核对；
不能把“曾开始核对”当成永久禁止后续 inspect 的标记。次数和截止时间不因重启刷新。

本包为实现化补齐的可配置默认值：最多 3 次，尝试间隔 5 秒，单次 inspect 超时 5 秒；
首次进入核对分支起总期限 60 秒。次数上限快照与截止时间持久化，单实例按 executionId 单飞。

核对只接受执行准入时解析的同一容器身份；`runtimeState == RUNNING`
且 `service.startedAt > ActionExecution.startedAt` 才认定观察到本次开始后的启动效果。
目标身份不符、时间不可比较或数据不足不得猜测成功；Demo 需排除并行外部重启造成的归因歧义。
`restartCount` 不是唯一判定依据。

次数耗尽或期限到达仍无法确定：`FAILED + error_code=EXECUTION_RESULT_UNCERTAIN`，
Plan -> EXECUTED，Incident -> DIAGNOSED。错误码表示结果未知，不声称远端一定没有发生。
核对确认启动效果后，仍只让 Execution SUCCEEDED，随后按冻结 Policy 进入恢复验证。
这不是对远端 exactly-once 执行的普遍保证。

---

## 27. RecoveryPolicy 生命周期

V0.1：

```text
RecoveryPolicy
→ ManagedResource
```

例如：

```text
statistics-consumer
```

拥有：

```text
Consumer = UP
AND
Stream backlog 持续下降
```

未来允许：

```text
SYSTEM
INCIDENT
```

级别，

但 V0.1 不实现。

---

## 28. RecoveryPolicy 修改不能改写历史

例如今天标准：

```text
P99 < 200ms
```

明天改成：

```text
P99 < 300ms
```

不能导致昨天原本失败的 Verification：

> 今天突然变成功。

因此：

`RecoveryVerification`

必须保存：

> 本次真正使用的 RecoveryPolicy 快照。

历史 Verification 永远根据当时快照解释。

---

## 29. RecoveryVerification 生命周期

状态：

```text
PENDING
RUNNING
PASSED
FAILED
INCONCLUSIVE
```

其中：

```text
PASSED
FAILED
INCONCLUSIVE
```

为终态。

如果需要重新验证：

创建：

> 新的 RecoveryVerification。

不要把旧结果覆盖。

---

## 30. 恢复验证三值合取与状态迁移

#### required 检查的三值求值与唯一结果矩阵

单个 Criterion 的结果是 TRUE、FALSE 或 UNKNOWN。FALSE 必须来自有效、未过期且能够决定该谓词的真实样本；
Provider 失败、字段缺失、`lag = null`、样本不足或无效只产生 UNKNOWN，不能伪造成 FALSE 或 TRUE。

| required Criterion 集合 | RecoveryVerification | Incident |
|---|---|---|
| 至少一项明确 FALSE，无论其他项是否 UNKNOWN | FAILED | INVESTIGATING，并开启新 run |
| 没有 FALSE，但至少一项 UNKNOWN | INCONCLUSIVE | DIAGNOSED |
| 全部 TRUE，且至少存在一项 required | PASSED | RESOLVED |

结果优先级为 **FAILED > INCONCLUSIVE > PASSED**，对应合取规则中的 FALSE > UNKNOWN > TRUE。
例如服务明确停止、队列读取失败，整体仍是 FAILED；服务运行但队列未知且没有明确失败，才是 INCONCLUSIVE。

发现 UNKNOWN 不能立即提前结束，否则会遗漏后续明确 FALSE。若已获得决定性的 FALSE，可以短路结束；
未执行项记 UNKNOWN／未执行及原因，不冒充已经完成采样。可选项不阻塞结果，但 Policy 不得为空或全部为可选项。
到期、中断和样本过期也通过同一结果矩阵收束；不能覆盖仍有效的明确 FALSE。

---

## 31. RESOLVED 的唯一条件

正式冻结：

> Incident 不能由用户直接点击“已解决”。

必须存在：

```text
RecoveryVerification.status = PASSED
```

然后：

```text
resolved_at
```

才能写入。

这是 OpsPilot 最核心的产品原则之一：

> **恢复是被系统验证出来的，不是被人随手点出来的。**

---

## 32. V0.1 不支持 Reopen

一旦：

```text
RESOLVED
```

就是终态。

不能：

```text
RESOLVED
→
INVESTIGATING
```

如果同类问题再次发生：

创建一个新的：

```text
Incident
```

未来可以增加：

> recurrence / related incident

但不属于 V0.1。

---

## 33. CANCELLED 同样是终态

```text
CANCELLED
```

以后不能恢复为：

```text
INVESTIGATING
```

需要重新处理：

> 新建 Incident。

这样避免第一版出现复杂的重新打开语义。

---

## 34. 关键对象的“能不能改”规则

| 对象 | 创建后是否允许修改 |
|---|---|
| Incident | 当前状态、影响摘要、时间等当前属性可更新 |
| Investigation | 调查计数与当前运行信息可更新 |
| Observation | **不可修改** |
| Evidence | **不可修改** |
| Hypothesis | 当前状态可变化，变化必须进时间线 |
| Diagnosis | **不可覆盖，新增版本** |
| RemediationPlan | 创建后核心内容不可修改 |
| ApprovalRequest | 决策后不可修改 |
| ActionExecution | 参数启动后不可修改，状态可向终态推进 |
| RecoveryPolicy | 可产生新版本，不改历史验证 |
| RecoveryVerification | 结果不可覆盖，需要重验则新建 |
| TimelineEvent | **只追加，不修改** |

---

## 35. TimelineEvent 是整个事故的历史账本

每一次重要变化必须进入时间线。

至少包括：

```text
INCIDENT_CREATED
INVESTIGATION_STARTED

CAPABILITY_INVOKED
CAPABILITY_FAILED

OBSERVATION_RECORDED

HYPOTHESIS_CREATED
HYPOTHESIS_STATUS_CHANGED

EVIDENCE_LINKED

DIAGNOSIS_CREATED

REMEDIATION_PROPOSED

APPROVAL_REQUESTED
APPROVAL_APPROVED
APPROVAL_REJECTED

ACTION_EXECUTION_STARTED
ACTION_EXECUTION_SUCCEEDED
ACTION_EXECUTION_FAILED

RECOVERY_VERIFICATION_STARTED
RECOVERY_VERIFICATION_PASSED
RECOVERY_VERIFICATION_FAILED
RECOVERY_VERIFICATION_INCONCLUSIVE

INCIDENT_RESOLVED
INCIDENT_CANCELLED
```

UI 默认显示人话：

> 15:21 检查 Redis，发现响应明显变慢。

技术详情才显示：

```text
OBSERVATION_RECORDED
O-00021
```

---

## 36. 关键并发约束

V0.1 正式规定：

一个 Incident：

#### 同时最多一个活跃调查循环

不能：

```text
Agent A
Agent B
```

同时调查同一个故障。

---

#### 同时最多一个等待中的 ApprovalRequest

不能同时弹：

```text
批准重启
批准另一个重启
批准回滚
```

---

#### 同时最多一个 ActionExecution

不能并行执行多个修改动作。

---

#### 同时最多一个 RUNNING 的 RecoveryVerification

避免多个恢复判断互相打架。

---

## 37. S3 完整生命周期演示

```text
Fault Lab 停止独立 statistics-consumer（不影响 project-api）
-> 创建 Incident，状态 CREATED
-> 用户 Start：唯一 Investigation，current_run_no=1
-> Java/AI 动态调查：Queue lag增长、Producer继续、Consumer STOPPED
-> AI 提出主假设及证据关系，Java 校验并落账
-> Diagnosis v1，Incident DIAGNOSED
-> 用户请求方案，AI 提议允许的 service.restart
-> Java 生成 Plan/Action/Approval，AWAITING_APPROVAL
-> 用户 Approve：同事务创建 PENDING Execution并冻结RecoveryPolicy
-> Worker准入并派发CHANGE，结果确定成功后进入VERIFYING
-> 按Snapshot采样：lag恢复、最终lag达标、pending健康、服务持续运行
-> 所有required TRUE：PASSED -> RESOLVED
```
示例中的数值是说明而非测量；真实 Gate 与恢复序列以 09-acceptance 为准。

---

## 38. 如果重启成功但仍然没恢复

Execution 成功不证明业务恢复。required 检查任一有效 FALSE，即 Verification FAILED；
同事务保存终态和 Timeline，调用 resumeInvestigation() 开启新 run 并将 Incident 转回 INVESTIGATING，
提交后派发。原 Diagnosis、Execution、Verification 不覆盖。该自动转入调查不自动批准或重放任何 CHANGE。

无明确 FALSE 但有 UNKNOWN：INCONCLUSIVE，回 DIAGNOSED，用户可以重新请求一次 Verification。

---

## 39. V0.1 核心不变量

下面这些以后应该直接转成后端测试。

#### INV-001

`RESOLVED` 必须存在 `PASSED RecoveryVerification`。

---

#### INV-002

`PRIMARY_CAUSE_IDENTIFIED` 必须至少引用一条 `SUPPORTS Evidence`。

---

#### INV-003

Evidence 引用的 Observation 和 Hypothesis 必须属于同一个 Investigation。

---

#### INV-004

未批准的 RemediationAction 永远不能创建 ActionExecution。

---

#### INV-005

AI 永远不能直接创建成功的 ActionExecution。

---

#### INV-006

ActionExecution 成功不能直接导致 Incident `RESOLVED`。

---

#### INV-007

新的 Diagnosis 产生后，基于旧 Diagnosis 且尚未执行的 RemediationPlan 不再允许执行。

---

#### INV-008

`Observation`、`Evidence`、`TimelineEvent` 不允许修改历史内容。

---

#### INV-009

同一个 Incident 同时只能运行一个 Agent 调查循环。

---

#### INV-010

`UNDETERMINED` 是合法 Diagnosis，不允许系统为了结束流程强行制造主要原因。

#### INV-011
AI Runtime 无权直接修改 Incident 状态。
#### INV-012
UNDETERMINED 禁止产生包含写能力的 RemediationPlan。
#### INV-013
POSSIBLE_CAUSE 必须引用关联主假设的真实 SUPPORTS Evidence。
#### INV-014
存在 PENDING ApprovalRequest 时，调查循环不得继续。
#### INV-015
当前 run 的预算耗尽必须确定性结束并形成合法 Diagnosis，不得永久停留 INVESTIGATING。

---

## 40. 本阶段正式冻结内容

到这里正式冻结：

#### Incident 8 个状态

```text
CREATED
INVESTIGATING
DIAGNOSED
AWAITING_APPROVAL
EXECUTING
VERIFYING
RESOLVED
CANCELLED
```

#### 一事故一调查

```text
Incident 1:1 Investigation
```

#### Diagnosis 三种结论

```text
PRIMARY_CAUSE_IDENTIFIED
POSSIBLE_CAUSE
UNDETERMINED
```

#### Hypothesis 四种状态

```text
PENDING
SUPPORTED
INSUFFICIENT_EVIDENCE
REFUTED
```

#### Evidence 三种关系

```text
SUPPORTS
REFUTES
CONTEXT
```

#### Verification 三种有效结果

```text
PASSED
FAILED
INCONCLUSIVE
```

#### 唯一写操作

```text
service.restart
```

#### 恢复唯一合法来源

```text
RecoveryVerification = PASSED
```

---

## 41. 生命周期实施约束

本生命周期已经与数据库、公开 API、内部 AI 协议、Capability 和验收规则合并。
实施顺序见 [08-implementation-plan.md](08-implementation-plan.md)；运行控制的完整事务与恢复矩阵见 [07-engineering.md](07-engineering.md)。

---

## 42. 冻结范围

8 个 Incident 状态、一个 Investigation、多次逻辑 run、追加式证据与版本化诊断均为 FROZEN。
代码验证仍由对应任务和三场景验收完成；本文件不是运行通过报告。

---
