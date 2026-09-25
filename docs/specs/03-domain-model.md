# OpsPilot V0.1 核心领域关系与持久化边界

> Status: FROZEN  
> Version: 0.1  
> Freeze revision: FINAL-FREEZE-20260925  
> 冻结日期：2026-09-25  
> 范围：对象关系、所有权、可变性与持久化边界。  
> 本文是已合并的正式实现规格。权威清单见 [SPEC-MANIFEST.md](SPEC-MANIFEST.md)。FROZEN 表示规格定稿，不表示代码、迁移或故障实验已经通过。

## 1. 先冻结上一阶段最后四条协议约束

### BND-013：一次 AgentStep 只有一个主 Intent

一个 AI 决策步骤只能选择一个主要动作：

```text
REQUEST_CAPABILITY
PROPOSE_HYPOTHESIS
UPDATE_HYPOTHESIS
PROPOSE_EVIDENCE_LINK
COMPLETE_INVESTIGATION
PROPOSE_REMEDIATION
```

允许随主动作附带少量直接相关的信息更新。

例如读取到：

```text
Consumer = DOWN
```

时，AI 可以：

```text
主 Intent：
PROPOSE_EVIDENCE_LINK

附带：
UPDATE_HYPOTHESIS → SUPPORTED
```

但禁止：

```text
同一步：

REQUEST_CAPABILITY
+
COMPLETE_INVESTIGATION
```

否则 Java 无法确定应该继续查询还是结束调查。

---

## 2. Intent 正式冻结

### 调查阶段允许

```text
REQUEST_CAPABILITY

PROPOSE_HYPOTHESIS

UPDATE_HYPOTHESIS

PROPOSE_EVIDENCE_LINK

COMPLETE_INVESTIGATION
```

### 诊断完成后单独允许

```text
PROPOSE_REMEDIATION
```

`PROPOSE_REMEDIATION` 不属于调查循环。

---

## 3. 处理建议由 Java 主动触发

只有：

```text
Incident.status = DIAGNOSED
```

且：

```text
Diagnosis =
PRIMARY_CAUSE_IDENTIFIED
或
POSSIBLE_CAUSE
```

Java 才可以：

```text
Java
  ↓
请求 AI Runtime
生成处理建议
```

如果：

```text
UNDETERMINED
```

则不会请求处理建议。

---

## 4. 运行周期与四类预算

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

## 5. 现在开始真正看领域关系

整个 OpsPilot 可以分成四组核心数据。

```text
第一组：系统接入
ManagedSystem
ManagedResource
DataSourceConnection
ResourceBinding
CapabilityBinding


第二组：故障调查
Incident
Investigation
CapabilityInvocation
Observation
Hypothesis
Evidence
Diagnosis


第三组：故障处理
RemediationPlan
RemediationAction
ApprovalRequest
ActionExecution
RecoveryPolicy
RecoveryVerification


第四组：审计
TimelineEvent
```

先不要想：

> 这是不是 19 张表？

**对象数量不等于表数量。**

现在只考虑业务关系。

---

## 6. 第一组：业务系统

### ManagedSystem

中文：

**业务系统**

例如：

```text
ShortLink Platform
```

它代表：

> OpsPilot 管理和调查的一套业务系统。

V0.1：

```text
ManagedSystem
1 条
```

但模型允许未来增加更多系统。

---

## 7. ManagedSystem 和 ManagedResource

一个业务系统拥有多个系统组件。

```text
ManagedSystem

ShortLink Platform
      │
      ├── ShortLink API
      ├── Statistics Consumer
      ├── Redis
      ├── MySQL
      └── Statistics Stream
```

因此：

```text
ManagedSystem 1 ───── N ManagedResource
```

这是明确的一对多关系。

---

## 8. ManagedResource 是所有调查对象的统一入口

我们不希望代码里到处出现：

```text
RedisResource
MysqlResource
DockerResource
ConsumerResource
```

而使用：

```text
ManagedResource
```

通过：

```text
resource_type
```

区分：

```text
SERVICE

DATABASE

CACHE

MESSAGE_QUEUE

CONSUMER

EXTERNAL_DEPENDENCY
```

这样：

```text
statistics-consumer
```

和：

```text
shortlink-redis
```

都可以作为：

> Capability 的目标资源。

---

## 9. DataSourceConnection

中文：

**数据源连接**

它代表：

> OpsPilot 从哪里获取真实数据。

例如：

```text
Prometheus
Loki
MySQL
Redis
Docker
```

注意：

它不直接属于某个具体 Resource。

因为一个 Prometheus 很可能同时监控：

```text
ShortLink API
Gateway
Statistics Consumer
```

所以更合理的是：

```text
DataSourceConnection
```

独立存在。

---

## 10. ResourceBinding

中文内部可以理解成：

**资源数据绑定**

它回答：

> 在某个数据源里面，怎样找到这个 Resource？

例如：

```text
ManagedResource:

shortlink-project
```

绑定：

```text
DataSourceConnection:

prometheus-local
```

再加：

```text
selector:

application="shortlink-project"
```

因此：

```text
ManagedResource
       │
       │ N
       ▼
ResourceBinding
       ▲
       │ N
DataSourceConnection
```

本质上：

> ManagedResource 与 DataSourceConnection 是多对多。

中间通过 ResourceBinding 表达。

---

## 11. 为什么不能直接把 Prometheus URL 放 Resource 表里

因为以后：

```text
ShortLink API
```

可能同时拥有：

```text
Prometheus
Loki
OpenTelemetry
```

而：

```text
Prometheus
```

又同时服务：

```text
API
Gateway
Consumer
```

如果硬塞字段：

```text
prometheus_url
loki_url
mysql_url
```

模型很快会崩。

因此必须独立。

---

## 12. Credential 不直接存明文

`DataSourceConnection` 可以保存：

```text
credential_ref
```

而不是：

```text
password = "123456"
```

具体密码以后可以来自：

```text
环境变量
Docker Secret
系统 Secret Store
```

V0.1 不需要自己开发密钥管理平台。

但原则冻结：

> 数据库中不存可以直接使用的明文基础设施密码。

---

## 13. CapabilityDefinition 不建议做成业务表

这是一个重要决定。

例如：

```text
metrics.query
logs.search
cache.inspect
database.inspect
queue.inspect
service.inspect
service.restart
```

这些不是用户动态创造的业务数据。

它们是：

> OpsPilot 程序本身支持的能力契约。

因此 V0.1 建议：

```text
CapabilityDefinition
```

由 Java 代码里的强类型 Registry 管理。

而不是让用户数据库里随便：

```text
INSERT capability (...)
```

否则很容易重新退化成：

> 动态万能工具。

---

## 14. CapabilityBinding 才需要持久化

它表达：

> 某个 Resource 到底拥有哪些 Capability。

例如：

```text
statistics-consumer

service.inspect
service.restart
```

而：

```text
redis

cache.inspect
```

因此：

```text
ManagedResource
      │
      │ 1:N
      ▼
CapabilityBinding
```

`CapabilityBinding` 中的：

```text
capability_key
```

必须对应 Java Capability Registry 中真实存在的能力。

---

## 15. 第一组领域关系最终是

```text
ManagedSystem
      │
      │ 1:N
      ▼
ManagedResource
      │
      ├──────────────┐
      │              │
      │ 1:N          │ 1:N
      ▼              ▼
CapabilityBinding   ResourceBinding
                        │
                        │ N:1
                        ▼
                DataSourceConnection
```

这就是：

> 系统接入模型。

---

## 16. 第二组：Incident

`Incident`

是整个故障域真正的聚合中心。

可以理解成：

> 一场完整事故的文件夹。

里面所有：

```text
Investigation
Diagnosis
Remediation
Verification
Timeline
```

都最终属于这场 Incident。

---

## 17. Incident 与 ManagedSystem

V0.1：

```text
Incident N ───── 1 ManagedSystem
```

一个 Incident 只属于一个业务系统。

例如：

```text
INC-001
→
ShortLink Platform
```

暂时不处理：

> 一场事故同时属于五个业务系统。

未来需要再扩。

---

## 18. Incident 与受影响资源

一场 Incident 可以影响多个 Resource。

例如：

```text
Redis 延迟事故

受影响：

Redis
ShortLink API
MySQL
```

所以：

```text
Incident
   │
   │ N:M
   ▼
ManagedResource
```

不建议直接在 Incident 中存：

```text
affected_resource_ids = "[1,2,3]"
```

最终应该存在明确关联。

逻辑对象可称：

```text
IncidentAffectedResource
```

---

## 19. V0.1 Incident 与 Investigation

已经冻结：

```text
Incident 1 ───── 1 Investigation
```

一个事故只有一个调查工作空间。

即使：

```text
诊断
→
执行
→
恢复失败
→
继续调查
```

仍然回到同一个 Investigation。

---

## 20. Investigation 是什么

Investigation 保存当前 run 的控制状态和整个工作空间的累计运行审计关联，不拥有第二套业务状态机。
Incident 保存业务影响、生命周期及起止时间。首次 Start 建唯一 Investigation，以后 resumeInvestigation() 只重启逻辑 run。

每条调查 AgentStep／CapabilityInvocation 关联当时 run_no；Diagnosis 也保存其形成周期。
Observation 通过来源 Invocation 获取轮号，避免独立轮号与来源冲突。
Evidence 与 Hypothesis 继续属于整个 Investigation，允许历史诊断的支持信息作为后续调查背景，
但旧轮迟到 AI 结果不能产生本轮领域变化。

---

## 21. CapabilityInvocation

中文：

**能力调用记录**

每次真正调用：

```text
metrics.query
cache.inspect
logs.search
```

都应该形成一条：

```text
CapabilityInvocation
```

例如：

```text
CI-001

investigation:
INV-001

capability:
cache.inspect

resource:
shortlink.redis

status:
SUCCEEDED
```

---

## 22. CapabilityInvocation 为什么必须独立存在

因为：

```text
能力调用
```

和：

```text
观测结果
```

不是一回事。

例如：

```text
cache.inspect
```

超时：

```text
CapabilityInvocation
=
FAILED
```

但是：

> 没有 Observation。

如果把两者合并，就会被迫制造：

```text
Observation:
Redis 超时
```

这可能是假信息。

所以必须分开。

---

## 23. Investigation 与 CapabilityInvocation

关系：

```text
Investigation
      │
      │ 1:N
      ▼
CapabilityInvocation
```

每次调用只属于一个 Investigation。

---

## 24. CapabilityInvocation 与 Observation

通常：

```text
一次能力调用
```

可能返回：

```text
一个或者多个真实观测。
```

所以设计为：

```text
CapabilityInvocation
      │
      │ 1:N
      ▼
Observation
```

而不是强制 1:1。

例如一次：

```text
service.inspect
```

可能同时获得：

```text
CPU = 42%

Memory = 58%

Status = UP
```

可以形成多个结构化 Observation。

---

## 25. Observation 必须保留来源

每条 Observation 至少必须知道：

```text
来自哪次 CapabilityInvocation

属于哪个 Resource

观察时间

数据类型

实际值

来源时间范围
```

因此：

> Evidence 最终可以一路追溯回真实查询。

---

## 26. 原始 Provider 返回怎么办

不要完全丢掉。

但也不要把几 MB 日志全文塞进 Observation。

建议逻辑上分为：

```text
CapabilityInvocation
    ↓
raw_result
```

以及：

```text
Observation
    ↓
结构化抽取结果
```

例如 Loki 返回：

```text
5000 行日志
```

CapabilityInvocation 可以保留：

```text
查询条件
摘要
原始结果引用
```

Observation 则保存：

```text
Redis timeout pattern
count = 147
first_seen = ...
last_seen = ...
```

V0.1 是否保存完整原始大结果，等物理存储设计时再决定。

---

## 27. Observation 是不可变事实

已经冻结：

```text
创建后不修改
```

新查询得到新值：

创建：

```text
O-002
```

不能覆盖：

```text
O-001
```

---

## 28. Hypothesis

中文：

**待验证原因**

属于：

```text
Investigation
```

关系：

```text
Investigation
      │
      │ 1:N
      ▼
Hypothesis
```

例如：

```text
H-001
Redis 性能异常

H-002
数据库连接池耗尽
```

---

## 29. Hypothesis 的当前状态可以改变

例如：

```text
PENDING
→
SUPPORTED
→
REFUTED
```

当前状态保存在 Hypothesis。

历史变化不需要再创建一个：

```text
HypothesisHistory
```

因为：

```text
TimelineEvent
```

已经负责保留变化记录。

避免重复建模。

---

## 30. Evidence 的真正模型

这是最值得钉死的关系。

Evidence 不是“另一份数据”。

而是：

```text
Observation
     │
     │
     ▼
Evidence
     │
     │
     ▼
Hypothesis
```

也就是：

> 某条真实 Observation 与某个 Hypothesis 之间的一条有语义关系。

---

## 31. Evidence 核心字段逻辑

例如：

```text
evidence_id:
E-001

observation_id:
O-003

hypothesis_id:
H-001

relation:
SUPPORTS

reason:
Redis P95 明显高于配置基线
```

因此：

```text
Observation 1 ───── N Evidence

Hypothesis 1 ───── N Evidence
```

---

## 32. 同一 Observation 可以用于多个 Hypothesis

比如：

```text
MySQL QPS 暴增
```

可能：

```text
支持：
Redis 缓存失效

同时作为：
数据库过载的背景信息
```

所以不能写成：

```text
Observation.evidence_id
```

必须使用独立关系对象。

---

## 33. Evidence 不删除

如果后来发现之前判断错了：

不要删：

```text
E-001
```

而是：

- 创建新 Observation；
- 创建新 Evidence；
- 更新 Hypothesis 当前状态；
- Timeline 保存变化。

这样复盘时能看到：

> AI当时为什么会做出那个判断。

---

## 34. Diagnosis

Diagnosis 属于 Investigation。

关系：

```text
Investigation
      │
      │ 1:N
      ▼
Diagnosis
```

为什么是 1:N？

因为可能：

```text
Diagnosis v1
↓
执行
↓
恢复失败
↓
继续调查
↓
Diagnosis v2
```

---

## 35. Diagnosis 必须版本化

逻辑上：

```text
version_no = 1
version_no = 2
version_no = 3
```

同一个 Investigation 内：

```text
version_no
```

必须唯一。

当前 Diagnosis：

> version_no 最大的一条。

V0.1 不额外维护复杂的：

```text
current_diagnosis_id
```

避免双重真相。

---

## 36. Diagnosis 和 Evidence

虽然 Diagnosis 可以通过：

```text
primary_hypothesis
```

间接找到 Evidence，

但它必须明确记录：

> 当时诊断真正引用了哪些 Evidence。

因此需要关系：

```text
Diagnosis
     │
     │ N:M
     ▼
Evidence
```

逻辑上可以叫：

```text
DiagnosisEvidenceReference
```

为什么不能实时查询 Hypothesis 当前所有 Evidence？

因为后来可能新增 Evidence。

这样会导致：

> Diagnosis v1 的依据被未来数据偷偷改变。

所以 Diagnosis 必须冻结当时引用。

---

## 37. Diagnosis 与 primary Hypothesis

对于：

```text
PRIMARY_CAUSE_IDENTIFIED
POSSIBLE_CAUSE
```

必须：

```text
primary_hypothesis_id
```

并至少一条：

```text
SUPPORTS Evidence
```

对于：

```text
UNDETERMINED
```

允许为空。

---

## 38. Diagnosis 是否直接存“已排除原因”

V0.1 不新增 DiagnosisHypothesisReference。Diagnosis 保存主假设及冻结的 Evidence ID；
已排除原因通过当时的 Hypothesis 状态变化和 Timeline 查询，不用当前状态改写历史。
Hypothesis 的标题、描述在 V0.1 创建后不随意改写，当前状态变化必须追加 Timeline。

---

## 39. 第二组最终关系

```text
Incident
   │
   │ 1:1
   ▼
Investigation
   │
   ├─────────────┐
   │             │
   │ 1:N         │ 1:N
   ▼             ▼
Hypothesis   CapabilityInvocation
   │             │
   │             │ 1:N
   │             ▼
   │         Observation
   │             │
   │             │
   └──── Evidence ┘
            │
            ▼
        Hypothesis


Investigation
      │
      │ 1:N
      ▼
Diagnosis
      │
      │ N:M
      ▼
Evidence
```

---

## 40. 第三组：RemediationPlan

处理方案必须绑定：

```text
Diagnosis
```

因为：

> 处理方案一定是基于某一版诊断产生的。

关系：

```text
Diagnosis
     │
     │ 1:N
     ▼
RemediationPlan
```

为什么 1:N？

理论上：

同一个诊断可以存在：

> 方案 A
> 方案 B

即使 V0.1 通常只有一个。

保持 1:N 更符合语义，而且不会增加太多复杂度。

---

## 41. 新 Diagnosis 为什么能让旧 Plan 过期

因为 Plan 中保存：

```text
diagnosis_id
```

假设：

```text
Plan P-001
→ Diagnosis v1
```

后来产生：

```text
Diagnosis v2
```

Java 可以确定：

> P-001 已经不是基于当前 Diagnosis。

因此禁止再执行。

不需要模型自己判断。

---

## 42. RemediationPlan 和 RemediationAction

V0.1 已冻结：

```text
一个 Plan
=
一个 Action
```

逻辑关系仍然保留：

```text
RemediationPlan 1 ───── 1 RemediationAction
```

为什么不直接合并？

因为两者语义不同。

Plan：

> 为什么这么处理。

Action：

> 具体执行什么。

例如：

```text
Plan：

恢复统计消费能力

原因：

Consumer 已停止
```

Action：

```text
service.restart
target =
statistics-consumer
```

这两个概念未来也很可能继续存在。

---

## 43. RemediationAction 必须引用 Capability

Action 不存：

```text
shell = "docker restart xxx"
```

而存：

```text
capability_key =
service.restart

target_resource_id =
...
```

真正怎么执行：

> 由 Java Capability Executor 决定。

这是防止业务数据变成脚本仓库。

---

## 44. ApprovalRequest

V0.1：

```text
RemediationAction 1 ───── 0..1 ApprovalRequest
```

只有写操作才需要 Approval。

由于 V0.1 唯一处理动作：

```text
service.restart
```

所以正常都会创建 Approval。

---

## 45. 为什么 ApprovalRequest 必须绑定具体 Action

不能只绑定：

```text
Incident
```

否则用户看到：

> “批准”

到底批准什么？

不明确。

ApprovalRequest 必须冻结：

```text
action_id

target_resource

action_parameters

requested_at
```

用户批准的是：

> **这个具体操作。**

---

## 46. Approval 之后参数不能再变

例如用户批准：

```text
service.restart
target = statistics-consumer
```

批准以后不能偷偷变成：

```text
target = redis
```

因此：

> ApprovalRequest 对应的 Action 核心参数在进入审批后必须不可修改。

需要改变：

创建新 Plan / Action / Approval。

---

## 47. ActionExecution

关系：

```text
RemediationAction
       │
       │ 1:0..1
       ▼
ActionExecution
```

V0.1 一个 Action 最多执行一次。

如果执行失败：

回：

```text
DIAGNOSED
```

重新生成后续方案。

不在同一条 ActionExecution 上无限 Retry。

---

## 48. ActionExecution 必须有幂等键

每个ActionExecution拥有稳定且唯一的idempotency_key。重复批准或重复派发不能产生第二条Execution；
只有成功抢占PENDING→RUNNING的Worker拥有一次CHANGE派发权。
数据库唯一键不独立证明远端执行恰好一次；网络不确定性按照下述核对规则处理。

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

## 49. RecoveryPolicy

V0.1：

```text
ManagedResource
      │
      │ 1:N
      ▼
RecoveryPolicy
```

为什么 1:N？

因为标准未来可能升级。

例如：

```text
v1:
backlog decreasing

v2:
consumer = UP
AND
backlog < 100
```

因此 Policy 需要版本概念。

---

## 50. 同一个 Resource 同时只能有一个生效 Policy

同一个 ManagedResource 任一时刻最多一个 ACTIVE RecoveryPolicy，不是每个 policy_key 各允许一个。
旧版本 RETIRED、保留历史。激活事务锁定稳定存在的 ManagedResource 父记录，再校验和替换 ACTIVE，
以覆盖没有旧策略时的并发首次激活。唯一版本键继续为 resource + policy_key + version_no。

---

## 51. RecoveryVerification

属于：

```text
Incident
```

而不是只属于 ActionExecution。

因为用户可能：

> 在 OpsPilot 外部处理以后请求验证。

所以：

```text
Incident
   │
   │ 1:N
   ▼
RecoveryVerification
```

---

## 52. ActionExecution 与 RecoveryVerification

关系：

```text
ActionExecution
      │
      │ 1:0..1
      ▼
RecoveryVerification
```

但是：

```text
action_execution_id
```

必须允许为空。

因为：

```text
用户外部处理
↓
直接 VERIFYING
```

不存在 ActionExecution。

---

## 53. 执行与验证的恢复合同快照

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

## 54. Verification 产生的新 Observation 怎么办

Recovery 使用同一 Observation 表，不新增 VerificationObservation 实体。
`investigation_id` 与 `recovery_verification_id` 明确二选一；恢复观测必须关联恢复 Invocation，
后者保存 `criterion_key + sample_index`。这些字段在 [04-database.md](04-database.md) 定义。
恢复观测不能直接建立 Investigation Evidence；重新调查应获得新的真实调查观测。

---

## 55. 这意味着 Observation 不一定只属于 CapabilityInvocation？

严格来说：

恢复验证最终同样应该通过：

```text
Capability
```

查询。

因此还是：

```text
Recovery Verification
        ↓
CapabilityInvocation
        ↓
Observation
```

所以 CapabilityInvocation 还需要知道：

> 它是谁发起的。

逻辑上：

```text
invocation_context

INVESTIGATION
RECOVERY_VERIFICATION
```

而不是再造另一套查询系统。

---

## 56. CapabilityInvocation 的正式上下文关系

CapabilityInvocation 直接保存 incident_id，并使用 investigation_id／recovery_verification_id 两个可空外键，
数据库 XOR CHECK 与 Java 同时保证恰好一个非空；不使用 context_type/context_id 泛型外键。
调查上下文必须有 run_no、没有 criterion_key/sample_index；恢复上下文相反。
CHANGE 和执行核对不进入这张表，避免新增第三种调用上下文。

---

## 57. 第三组完整关系

```text
Diagnosis
   │
   │ 1:N
   ▼
RemediationPlan
   │
   │ 1:1
   ▼
RemediationAction
   │
   │ 0..1
   ▼
ApprovalRequest
   │
   │ approved
   ▼
ActionExecution
   │
   │ 0..1
   ▼
RecoveryVerification


ManagedResource
   │
   │ 1:N
   ▼
RecoveryPolicy

RecoveryVerification
      │
      └── 保存 RecoveryPolicy 快照
```

另外：

```text
Incident 1:N RecoveryVerification
```

因为可能：

```text
Verification v1 = FAILED

继续调查

再次处理

Verification v2 = PASSED
```

---

## 58. 第四组：TimelineEvent

TimelineEvent：

> 是事故的审计账本。

关系非常简单：

```text
Incident
   │
   │ 1:N
   ▼
TimelineEvent
```

---

## 59. TimelineEvent 不需要和每个对象建立外键

例如：

```text
EVIDENCE_LINKED
```

可以在 payload 中保存：

```text
evidence_id
```

不用：

```text
timeline_event.evidence_id
timeline_event.action_id
timeline_event.diagnosis_id
timeline_event.verification_id
...
```

否则 Timeline 表会越来越宽。

---

## 60. TimelineEvent 最少应该包含

逻辑字段：

```text
id

incident_id

event_type

occurred_at

actor_type

actor_id

summary

payload

correlation_id
```

其中：

`actor_type` 可以表达：

```text
USER
SYSTEM
AI_RUNTIME
```

---

## 61. correlation_id 是什么

中文理解：

> **把同一次操作产生的一串记录串起来。**

例如：

```text
用户点击“批准并执行”
```

会产生：

```text
Approval Approved
Action Execution Started
Docker Executor Called
Action Execution Succeeded
```

都可以带：

```text
correlation_id = C-1001
```

以后排查系统本身的问题会很方便。

---

## 62. TimelineEvent 永远只追加

已经冻结。

不：

```text
UPDATE timeline_event
```

不：

```text
DELETE timeline_event
```

V0.1 Demo 数据可以全部保留。

---

## 63. AgentStep 要不要成为领域对象？

这里要特别克制。

答案：

> **不是核心业务领域对象。**

用户真正关心的是：

```text
调查
证据
诊断
处理
恢复
```

而：

```text
AgentStep
```

只是 AI Runtime 与 Java 编排之间的技术执行记录。

所以不放进：

> 核心领域模型。

---

## 64. 但 AgentStep 是否需要持久化？

我建议：

> 需要记录，但作为内部运行记录。

原因：

我们需要知道：

```text
第几步
模型耗时
是否超时
返回什么 Intent
结构化输出是否合法
用了多少 Token
```

这对：

- 调试；
- Agent 评测；
- 成本分析；

非常有价值。

未来可以使用内部对象：

```text
AgentStepRecord
```

但：

> 它不是 Incident 产品模型的一部分。

这就是业务模型和运行模型的区别。

---

## 65. 核心领域对象与内部技术记录必须分开

### 业务核心

```text
ManagedSystem
ManagedResource

Incident
Investigation
Hypothesis
Observation
Evidence
Diagnosis

RemediationPlan
RemediationAction
ApprovalRequest
ActionExecution
RecoveryPolicy
RecoveryVerification

TimelineEvent
```

### 支撑配置

```text
DataSourceConnection
ResourceBinding
CapabilityBinding
```

### 内部运行记录

```text
CapabilityInvocation
AgentStepRecord
```

这样比把所有东西都叫：

> Entity

更专业。

---

## 66. 哪些对象应该拥有自己的身份 ID

建议拥有独立 ID：

```text
ManagedSystem
ManagedResource
DataSourceConnection
ResourceBinding
CapabilityBinding

Incident
Investigation
CapabilityInvocation
Observation
Hypothesis
Evidence
Diagnosis

RemediationPlan
RemediationAction
ApprovalRequest
ActionExecution
RecoveryPolicy
RecoveryVerification

TimelineEvent
AgentStepRecord
```

因为它们：

> 都可能被其他对象引用或者被独立审计。

---

## 67. 哪些东西不应该成为独立实体

例如：

```text
TimeRange
ImpactSummary
CapabilityParameters
ObservationValue
RecoveryCriterion
```

这些更适合作为：

> **值对象**

所谓值对象，就是：

> 它本身没有独立身份，只是描述另一个对象的一部分。

例如：

```text
TimeRange:
from
to
```

没有必要：

```text
time_range_id = 123
```

---

## 68. JSON 可以用，但不能偷懒

我们之前禁止：

```java
Map<String, Object>
```

不是说：

> 数据库永远不能出现 JSON。

而是：

> Java 运行时必须有类型。

比如：

```java
CacheInspectRequest
CacheInspectResult
```

都是明确类型。

持久化时为了支持不同 Capability：

可以将结果序列化成：

```text
payload_json
```

但同时必须保存：

```text
schema_name
schema_version
```

例如：

```text
schema_name:
cache.inspect.result

schema_version:
1
```

以后数据才能解释。

---

## 69. 不允许“万能 JSON 表”

错误设计：

```text
object_type
object_data JSON
```

然后：

> 所有业务数据全部塞进去。

这种架构初期很快，

两周以后几乎无法维护。

核心业务状态：

```text
Incident.status
Diagnosis.type
Hypothesis.status
Approval.decision
```

必须是明确字段。

JSON 只适合：

> 各 Capability 不同的结构化载荷。

---

## 70. 核心关系总图

最终可以形成：

```text
                         ManagedSystem
                              │
                        1     │     N
                              ▼
                       ManagedResource
                         │         │
                         │         │
                CapabilityBinding │
                                   │
                             ResourceBinding
                                   │
                                   ▼
                         DataSourceConnection


ManagedSystem
     │
     │ 1:N
     ▼
  Incident
     │
     ├──────────────► Affected Resources
     │
     │ 1:1
     ▼
Investigation
     │
     ├──────────────► Hypothesis
     │                    ▲
     │                    │
     │                Evidence
     │                    ▲
     │                    │
     ├─► CapabilityInvocation
     │           │
     │           ▼
     │      Observation
     │
     └──────────────► Diagnosis
                           │
                           │
                           ▼
                    RemediationPlan
                           │
                           ▼
                    RemediationAction
                           │
                           ▼
                    ApprovalRequest
                           │
                           ▼
                    ActionExecution
                           │
                           ▼
                 RecoveryVerification
                           ▲
                           │
                     RecoveryPolicy
                           ▲
                           │
                    ManagedResource


Incident
   │
   └────────────────────► TimelineEvent
```

这已经是整个 V0.1 的核心数据骨架。

---

## 71. 最重要的所有权关系

### ManagedSystem 拥有

```text
ManagedResource
```

---

### Incident 拥有

```text
Investigation
RecoveryVerification
TimelineEvent
```

---

### Investigation 拥有

```text
Hypothesis
CapabilityInvocation（调查阶段）
Observation（通过调用产生）
Evidence
Diagnosis
```

---

### Diagnosis 拥有业务上的

```text
RemediationPlan
```

---

### RemediationPlan 拥有

```text
RemediationAction
```

---

### RemediationAction 驱动

```text
ApprovalRequest
ActionExecution
```

---

## 72. 哪些东西必须不可变

冻结：

```text
Observation
Evidence
Diagnosis
Approval 决策
已开始执行后的 Action 参数
RecoveryVerification 结果
TimelineEvent
```

即：

> 创建以后不能改写历史语义。

---

## 73. 哪些允许更新“当前状态”

```text
Incident.status
Incident.impact_summary

Investigation 运行统计

Hypothesis.status

ApprovalRequest
PENDING → 终态

ActionExecution
PENDING → RUNNING → 终态

RecoveryPolicy active version
```

---

## 74. 版本化对象

Diagnosis 与 RecoveryPolicy 使用业务版本；Incident、Investigation等可变对象使用 lock_version 控制并发，二者语义不同。
Evidence、Hypothesis 和 RemediationPlan 不做内容版本化；运行周期 current_run_no 也不是新的实体版本。
Execution 持有恢复合同快照，Verification 继承快照；历史结果不得跟随当前配置变化。

---

## 75. 删除策略

OpsPilot 是故障审计产品。

所以核心事故记录不应该物理删除。

V0.1：

```text
Incident
Investigation
Observation
Evidence
Diagnosis
Approval
Execution
Verification
Timeline
```

不提供硬删除。

测试数据清理：

> 通过开发环境维护脚本处理。

不成为产品功能。

---

## 76. ManagedSystem 删除怎么办

如果已经有 Incident 引用：

不能直接物理删除。

未来可以：

```text
disabled = true
```

或者：

```text
archived_at
```

V0.1 只要求：

> 已被 Incident 使用的 ManagedSystem / Resource 不允许硬删除。

---

## 77. DataSourceConnection 删除怎么办

如果仍然有：

```text
ResourceBinding
```

存在：

拒绝删除。

必须先解除绑定。

这是典型的引用完整性约束。

---

## 78. CapabilityBinding 删除怎么办

如果历史 Incident 曾经使用过：

历史：

```text
CapabilityInvocation
```

仍然存在。

但 Binding 可以从当前配置删除。

因为历史 Invocation 保存：

```text
capability_key
resource_id
```

历史不会因此消失。

---

## 79. 为什么 CapabilityInvocation 要保存 capability_key 快照

假设未来：

```text
cache.inspect
```

被程序删除。

旧事故仍然应该知道：

> 当时调用的是哪个能力。

所以 Invocation 保存：

```text
capability_key
```

而不是只靠当前 Registry 查询。

---

## 80. ManagedResource 名称变化怎么办

例如：

```text
statistics-consumer
```

以后改名。

历史 Observation 如果每次都实时显示当前名称：

会改写用户对旧事故的理解。

所以对于关键审计记录，

后续可能需要保存：

```text
resource_name_snapshot
```

但是 V0.1 暂时不全部做快照。

优先保留：

```text
resource_id
```

以及 Timeline 中当时的人类可读 summary。

---

## 81. 领域边界完成标准

本文件确定对象关系、所有权、不可变性和运行记录的归属。具体列与约束以 [04-database.md](04-database.md) 为准。
实现不得把运行周期、采样点或核对尝试扩成新的业务微服务／通用工作流。

---

## 82. 领域与物理模型的对应关系

物理模型、API、Capability、工程和任务计划已经提供在 04～08 中。
新字段必须能够追溯到已冻结业务或运行控制规则；普通数据库类型长度、索引实现细节在对应 Task 解决并验证。

---

## 83. 冻结声明

本领域关系为 FROZEN。文档合并不代表仓库已生成业务代码，下一步是任务级实现。

---
