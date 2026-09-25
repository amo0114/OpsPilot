# OpsPilot V0.1 产品定义与核心领域设计

> Status: FROZEN  
> Version: 0.1  
> Freeze revision: FINAL-FREEZE-20260925  
> 冻结日期：2026-09-25  
> 范围：产品目标、用户流程、范围和产品表达；不把Agent框架作为产品中心。  
> 本文是已合并的正式实现规格。权威清单见 [SPEC-MANIFEST.md](SPEC-MANIFEST.md)。FROZEN 表示规格定稿，不表示代码、迁移或故障实验已经通过。

## 1. 产品定义

### 1.1 OpsPilot 是什么

OpsPilot 是一个面向线上业务系统故障处理的智能协作平台。

当业务系统出现响应变慢、错误率升高、缓存异常、数据库异常、消息积压等问题时，OpsPilot 可以连接系统已有的监控、日志、数据库、缓存和消息系统等运行信息，辅助用户完成：

1. 了解当前故障影响；
2. 自动调查可能原因；
3. 保存调查过程中获得的真实证据；
4. 根据证据形成诊断结果；
5. 给出处理建议；
6. 对可能影响系统的操作进行人工审批；
7. 通过确定性程序执行已经批准的操作；
8. 再次检查系统指标，判断故障是否真正恢复；
9. 保存整场故障的完整时间线。

OpsPilot **不是聊天机器人**。

OpsPilot 也不是：

- Kubernetes 管理平台；
- 通用 Shell Agent；
- 数据库管理工具；
- 全自动无人值守运维系统；
- 通用 Agent Framework。

---

## 2. 核心产品原则

### 2.1 AI 和程序的职责边界

OpsPilot 最重要的架构原则：

> **AI 决定下一步该了解什么、当前证据意味着什么、可以考虑怎么处理；OpsPilot 的确定性程序决定它能看什么、能做什么、是否获批、怎样执行，以及什么时候才算恢复。**

因此：

AI 可以：

- 判断下一步应该检查什么；
- 根据已有信息提出待验证原因；
- 判断哪些观测结果能够支持某个原因；
- 排除已经被证据否定的原因；
- 形成诊断结果；
- 提出处理方案。

AI 不可以自行决定：

- 数据访问权限；
- 是否允许执行危险操作；
- 任意执行 Shell；
- 任意执行 SQL；
- 修改 Redis；
- 重启任意服务；
- 绕过人工审批；
- 自己宣布“已经恢复”。

---

## 3. V0.1 的目标

V0.1 不追求支持大量业务系统。

第一版只需要证明一件事情：

> OpsPilot 能够围绕真实业务系统完成一次从“出现异常”到“调查、诊断、处理、恢复验证”的完整故障流程。

V0.1 首个接入系统：

**ShortLink Platform**

ShortLink 只是第一套验证环境，不是 OpsPilot 的永久产品边界。

未来理论上可以继续接入：

- Java 服务；
- Python 服务；
- Node.js 服务；
- 商城；
- 订单系统；
- 支付系统；
- 内部 SaaS；
- 其他业务应用。

---

## 4. 目标用户

V0.1 设计时考虑两类用户。

### 4.1 服务负责人 / 普通使用者

他们主要关心：

- 现在发生了什么？
- 哪些业务受到影响？
- 问题大概在哪里？
- 系统正在怎么处理？
- 是否需要我批准操作？
- 最后恢复了吗？

他们不应该被迫理解：

- PromQL；
- EXPLAIN；
- Trace ID；
- Redis Pending；
- Capability Invocation；
- Agent Step；
- Prompt；
- Token。

默认界面必须使用自然语言。

---

### 4.2 开发 / 运维人员

他们还需要进一步查看：

- 原始监控指标；
- 日志；
- 查询条件；
- 数据库状态；
- 缓存状态；
- 调查时间线；
- 原始观测结果；
- 证据来源；
- 执行记录；
- 恢复验证指标。

这些内容放在：

**“技术详情”**

而不是默认全部展示。

---

## 5. V0.1 三个验证场景

### S1：缓存访问延迟导致短链接跳转退化
业务出现跳转变慢或错误率上升。调查必须取得 Redis 路径异常与业务 HTTP 影响的真实支持证据，再形成诊断。
MySQL 请求量上涨、Redis timeout 日志等是可能表现，不是必需因果环节；不得为迎合故事强改 ShortLink 的回源行为。

### S2：MySQL 慢语句导致应用连接池争用
通过 project-api 自己的 Hikari Pool 运行真实数据库侧慢任务，并以正常业务负载制造连接等待。
诊断必须解释“慢语句 -> 连接长期占用 -> 请求等待／业务退化”，不能把“连接池满”本身当成完整根因。

### S3：统计消费者停止导致消息积压
API／Producer 继续运行，独立 consumer Runtime 停止；调查取得 Consumer STOPPED 与消息未投递积压增长的证据。
AI 提议 `service.restart`，用户批准，Java 执行，再确定性检查 lag 健康区间、pending 健康区间与 Consumer 运行状态。
只有 Verification PASSED 才 RESOLVED。操作成功、消息被投递、消息被确认、业务结果成功不能无条件画等号。

三场景的负载、Gate、Evidence 和 PASS 条件以 [09-acceptance.md](09-acceptance.md) 对应章节为准；
Agent 的调查顺序不固定。

---

## 6. 故障处理主流程

统一业务流程：

```text
发现异常
   ↓
创建故障
   ↓
开始调查
   ↓
获取系统信息
   ↓
产生待验证原因
   ↓
继续获取信息
   ↓
形成诊断证据
   ↓
支持 / 排除待验证原因
   ↓
形成诊断结果
   ↓
提出处理方案
   ↓
是否需要执行操作？
   │
   ├── 否 → 人工处理 / 结束调查
   │
   └── 是
        ↓
      创建审批
        ↓
      用户批准
        ↓
      确定性程序执行
        ↓
      恢复验证
        ↓
      ┌──────────┐
      │          │
     恢复       未恢复
      │          │
     结案       继续调查
```

---

## 7. 核心领域对象

内部代码使用稳定英文名称。

用户界面使用自然中文。

---

### 7.1 ManagedSystem

中文：

**业务系统**

含义：

OpsPilot 接入并负责调查的业务系统。

示例：

ShortLink Platform

主要属性：

```text
id
name
description
environment
status
created_at
updated_at
```

不要使用 Java 中容易与 `java.lang.System` 冲突的 `System` 作为类型名称。

---

### 7.2 ManagedResource

中文：

**系统组件**

业务系统内部可以被观察或操作的资源。

例如：

```text
ShortLink API
Statistics Consumer
Redis
MySQL
Statistics Stream
```

资源类型示例：

```text
SERVICE
DATABASE
CACHE
MESSAGE_QUEUE
CONSUMER
EXTERNAL_DEPENDENCY
```

关系：

```text
ManagedSystem
    │
    └── ManagedResource
```

---

## 8. 能力模型

### 8.1 CapabilityDefinition

中文：

**能力定义**

表达：

> OpsPilot 可以对某类系统资源做什么。

能力不是某个故障场景。

错误设计：

```text
restart_statistics_consumer
restart_order_service
restart_payment_service
```

正确设计：

```text
service.inspect
service.restart

metrics.query

logs.search

cache.inspect

database.inspect

queue.inspect

```

---

### 8.2 CapabilityBinding

表示：

> 某个具体系统组件拥有哪些能力。

例如：

```text
statistics-consumer

支持：

service.inspect
service.restart
```

Redis：

```text
shortlink-redis

支持：

cache.inspect
metrics.query
```

---

### 8.3 不设计万能能力

禁止设计：

```text
executeAnything(command)
```

禁止依赖：

```text
Map<String, Object>
```

承载任意没有明确结构的参数。

每种能力必须有明确输入和输出类型。

例如：

```text
metrics.query
```

输入应该拥有明确字段：

```text
resourceId
metricKey
windowKey
comparePreviousWindow
```

而不是：

```text
params: {}
```

---

## 9. 能力调用模型

Agent 可以先获取：

**当前业务系统能够使用哪些能力。**

但是具体执行仍然调用明确、有类型的能力。

例如：

```text
metrics.query
logs.search
cache.inspect
database.inspect
queue.inspect
```

而不是把所有查询都压进：

```text
invokeObservation()
```

这样的万能入口。

因此：

> 能力目录负责发现。

> 类型化能力负责执行。

---

## 10. Provider 接入模型

Agent 不应该关心底层具体使用什么软件。

例如：

```text
metrics.query
```

底层可以由：

```text
PrometheusMetricsProvider
VictoriaMetricsProvider
```

实现。

```text
logs.search
```

可以由：

```text
LokiLogProvider
ElasticsearchLogProvider
```

实现。

```text
service.restart
```

可以由：

```text
DockerServiceExecutor
KubernetesServiceExecutor
CustomServiceExecutor
```

实现。

因此：

> Agent 面向能力。

> 业务代码面向接口。

> 具体基础设施被隐藏在 Provider / Executor 层。

---

## 11. Investigation

**故障调查**是 Incident 唯一的调查工作空间。Incident 创建时可以尚无 Investigation；首次 Start 时创建，此后复用，唯一约束保证最多一条。
同一工作空间允许多次逻辑运行周期 `current_run_no`，不增加 InvestigationRun 表。
保存当前轮运行控制、调用计数、停止意图，并关联历史 Hypothesis、Observation、Evidence、Diagnosis。

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

## 12. Observation

中文：

**观测结果**

代表从真实系统读取到的客观信息。

例如：

```text
Redis P95 latency = 623ms
```

它本身只表达：

> 系统现在观测到了什么。

并不自动等同于“诊断证据”。

---

## 13. Evidence

中文：

**诊断证据**

当某个 Observation 被用于支持或者反驳某个原因时，它成为 Evidence。

例如：

```text
EV-002

来源：
Redis Metrics

观测：
P95 latency = 623ms

正常参考：
< 20ms

作用：
支持“Redis 性能异常”这一待验证原因
```

核心规则：

> **Diagnosis 必须引用 Evidence。**

如果一个所谓“主要原因”没有关联任何 `evidence_ids`：

它只能被视为：

**猜测**

不能被标记为：

**主要原因**

---

## 14. Hypothesis

中文：

**待验证原因**

例如：

```text
Redis 响应异常
数据库连接池耗尽
Consumer 已停止
第三方依赖超时
```

状态：

```text
PENDING
SUPPORTED
INSUFFICIENT_EVIDENCE
REFUTED
```

用户界面：

```text
待验证
已有证据支持
证据不足
已排除
```

V0.1 不显示没有实际校准依据的：

```text
93%
87%
72%
```

等所谓“AI 置信度”。

---

## 15. Diagnosis

中文：

**诊断结果**

代表当前调查最终形成的原因判断。

必须包含：

```text
summary
impact
evidence_ids
excluded_hypotheses
created_at
```

用户默认看到自然语言结论。

运维人员可以展开查看全部证据。

---

## 16. RemediationPlan

**处理方案**绑定某一版 Diagnosis，说明为什么这样处理。V0.1 一个 RemediationPlan 恰有一个 RemediationAction。
内容提交审批后不可修改；变更目标或参数必须新建 Plan／Action／Approval。
新 Diagnosis 产生后，旧未执行 ACTIVE Plan 在同一完成事务中被 SUPERSEDED。Plan 本身不做内容版本化。

---

## 17. RemediationAction

中文：

**处理操作**

例如：

```text
service.restart
```

目标：

```text
shortlink.statistics-consumer
```

AI只能：

> 提出这个 Action。

AI不能：

> 直接执行这个 Action。

---

## 18. ApprovalRequest

中文：

**操作审批**

任何会改变业务系统运行状态的操作，在 V0.1 默认都必须经过人工批准。

状态：

```text
PENDING
APPROVED
REJECTED
CANCELLED
```

---

## 19. ActionExecution

中文：

**执行记录**

由确定性后端创建。

记录：

```text
执行了什么
对哪个资源执行
谁批准
什么时候执行
由哪个执行器执行
执行是否成功
```

即使 AI 输出：

> 请执行 XXX

真正的执行权限仍然属于后端。

---

## 20. RecoveryPolicy

**恢复标准**是挂在 ManagedResource 上的版本化、受信配置。同一资源最多一个 ACTIVE Policy。
Policy 可以检查同一 ManagedSystem 下的关联资源，例如消费者的策略检查其 statistics-stream。
检查项非空且至少一个 required；只使用受控 OBSERVE Capability 与类型化谓词，不执行表达式字符串或脚本。

S3 的配置合同见 [06-capability.md](06-capability.md) 与 [09-acceptance.md](09-acceptance.md)；
Demo 的 lag／pending 容忍值不是所有企业系统的通用健康阈值。

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

## 21. RecoveryVerification

中文：

**恢复验证**

ActionExecution 成功以后必须执行。

状态：

```text
PENDING
RUNNING
PASSED
FAILED
INCONCLUSIVE
```

用户界面：

```text
等待验证
正在验证
已经恢复
仍未恢复
暂时无法确认
```

核心规则：

> 命令执行成功，不等于故障已经解决。

只有 RecoveryVerification 达到 RecoveryPolicy，才能宣布：

> 已恢复。

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

## 22. TimelineEvent

中文：

**故障时间线事件**

整个 Incident 的所有重要事件都必须进入时间线。

例如：

```text
故障创建

调查开始

查询 HTTP 指标

发现异常

生成 EV-001

新增待验证原因

排除某个原因

形成诊断

提出处理方案

用户批准

开始执行

执行完成

开始恢复验证

恢复成功

故障关闭
```

V0.1 使用：

```text
incident_timeline_event
```

一张追加式记录表即可。

原则：

> 已发生历史事件只追加，不修改。

V0.1 不引入完整 Event Sourcing / CQRS。

---

## 23. 安全模型

V0.1 将系统能力严格分为：

### 23.1 观察能力

不会改变业务系统。

例如：

```text
metrics.query
logs.search
cache.inspect
database.inspect
queue.inspect
service.inspect
```

可以在权限允许的情况下由调查过程自动调用。

---

### 23.2 操作能力

会改变业务系统。

例如：

```text
service.restart
```

流程必须是：

```text
Agent 提出建议
      ↓
创建 RemediationAction
      ↓
后端安全检查
      ↓
创建 ApprovalRequest
      ↓
用户批准
      ↓
ActionExecution
```

AI 没有直接执行权。

---

## 24. 凭证隔离原则

诊断查询和系统操作使用不同凭证。

例如：

数据库调查账号：

```text
只读
```

不拥有：

```text
UPDATE
DELETE
DROP
```

Redis 调查：

不得拥有任意数据修改能力。

服务操作：

走独立执行接口。

原则：

> **安全不依赖 Prompt。**

即使模型错误地产生危险指令，凭证和执行层本身也不允许它实现。

---

## 25. 产品信息架构

V0.1 只保留 5 个主要页面：

```text
首页

业务系统

故障列表

故障详情

故障实验室
```

---

## 26. 故障详情页

这是整个 OpsPilot 最重要的产品页面。

默认信息顺序：

### 当前影响

告诉用户：

> 业务到底受到什么影响。

例如：

“短链接跳转速度明显下降。”

---

### 当前状态

例如：

```text
正在调查
等待批准
正在处理
正在验证
已经恢复
```

---

### 当前判断

自然语言描述当前最可能原因。

---

### 为什么这么判断

展示人可以看懂的证据摘要。

例如：

```text
✓ 缓存响应时间显著升高

✓ 同一时间数据库请求量上涨

✓ 应用日志出现大量缓存超时
```

---

### 处理建议

例如：

> 建议重新启动已经停止的统计消费服务。

同时显示：

```text
风险
影响
是否需要审批
```

---

### 恢复情况

例如：

```text
Consumer 已恢复运行

消息积压：
2180 → 940 → 210 → 8

恢复验证：通过
```

---

### 技术详情

展开后才显示：

```text
原始指标
日志
时间范围
Evidence ID
能力调用记录
执行器
原始返回
```

因此：

> 内部专业。

> 外部易懂。

---

## 27. V0.1 六层内部结构

OpsPilot V0.1 采用以下逻辑分层：

```text
① 产品层
故障 / 调查 / 诊断 / 处理 / 恢复

② AI 调查层
根据已有信息决定下一步应该了解什么

③ 能力层
指标 / 日志 / 数据库 / 缓存 / 消息 / 服务

④ 接入层
Prometheus / Loki / MySQL / Redis / Docker 等

⑤ 证据与时间线
Observation / Evidence / TimelineEvent

⑥ 安全与执行
审批 / 权限 / 执行 / 恢复验证
```

六层只是架构职责。

不代表一定拆成六个微服务。

V0.1 禁止为了“分层”而过度拆服务。

---

## 28. V0.1 Agent 原则

V0.1：

**单 Agent。**

不做：

```text
Metrics Agent
Logs Agent
DBA Agent
Change Agent
Manager Agent
```

调查循环保持简单：

```text
读取当前调查状态
      ↓
查看已有证据
      ↓
决定下一步应该了解什么
      ↓
调用一个允许的能力
      ↓
生成 Observation
      ↓
必要时生成 Evidence
      ↓
更新 Hypothesis
      ↓
继续？
  ├── 是 → 下一轮
  └── 否 → Diagnosis
```

禁止把调查流程写死：

```text
Redis
↓
MySQL
↓
Logs
↓
Diagnosis
```

因为不同故障的下一步调查方向不同。

---

## 29. AI Runtime 框架边界

V0.1 不引入 LangGraph、LangChain Agent 或第二套 AI 持久化状态机。
Java 拥有外层调查控制、业务状态、审批、执行和恢复；Python 使用 FastAPI、Pydantic v2 与 LLM Client 提供单步结构化决策。
框架替换属于后续明确需求，不作为当前编码任务。

---

## 30. 排障指南

后续允许存在：

`DiagnosticPlaybook`

中文：

**排障指南**

例如：

```text
Redis 延迟排障指南
MySQL 慢查询排障指南
Consumer 积压排障指南
```

它负责告诉 Agent：

> 某类问题通常应该怎么检查。

但是：

V0.1 前期不把 Playbook 建设成独立复杂系统。

也不放进核心领域模型。

---

## 31. V0.1 明确不做

为了防止项目持续膨胀，以下内容正式排除。

#### 不做 Multi-Agent

---

#### 不做通用 Agent Harness

---

#### 不做通用插件内核

---

#### 不做复杂 RAG 知识库

---

#### 不做 GraphRAG

---

#### 不做长期 AI Memory 系统

---

#### 不做自动学习 Skill

---

#### 不做完整 Kubernetes 运维平台

---

#### 不给 AI 任意 Bash

---

#### 不给 AI 任意 SQL

---

#### 不自动修改数据库

---

#### 不做全自动无人审批修复

---

#### 不做复杂组织 / RBAC

V0.1 使用简化用户身份和审批机制即可。

---

#### 不做复杂告警平台

V0.1 故障可以：

人工创建

或

故障实验室触发。

---

#### 不做 Event Sourcing / CQRS

---

#### 不做复杂上下文压缩系统

---

#### 不做多模型路由平台

---

#### 不做所谓 Blast Radius Engine

---

## 32. V0.1 产品成功标准

S1、S2 必须完成真实诊断及可追溯 Evidence；S3 必须走完真实审批、执行和恢复验证闭环。
三场景符合 [09-acceptance.md](09-acceptance.md) 的自动断言与人工语义检查，且没有任何安全违规，才说明 V0.1 功能闭环成立。
页面可打开、Fake Provider 能运行、规格标记 FROZEN 均不能替代真实验收。

---

## 33. V0.1 最终演示

完整 Demo 应该展示：

```text
制造一个真实故障
        ↓
打开 OpsPilot
        ↓
创建 / 启动调查
        ↓
调查时间线持续更新
        ↓
Agent 根据数据动态选择查询方向
        ↓
产生真实 Observation
        ↓
形成 Evidence
        ↓
生成 Diagnosis
        ↓
提出 RemediationPlan
        ↓
人工审批
        ↓
执行
        ↓
RecoveryVerification
        ↓
系统恢复
        ↓
完整 Incident 结案
```

做到这一点，V0.1 即成立。

---

## 34. 实施入口

设计裁决已经合入 00～09；正式编码只读取 `docs/specs/`。
导入本包并完成 TASK-001 仓库落位检查后，执行 TASK-002～004；不重新开始产品设计或把全部 Task 一次交给 Agent。
实现任务的范围、依赖及验证要求见 [08-implementation-plan.md](08-implementation-plan.md)。

---

## 35. 项目最高原则

整个 OpsPilot 后续开发过程中，始终遵守：

> **场景不是工具。**

> **AI 不拥有执行权。**

> **诊断必须有证据。**

> **执行成功不等于故障恢复。**

> **安全不能依赖 Prompt。**

> **业务领域模型不能围绕某个 AI 框架设计。**

> **内部技术可以复杂，产品表达必须让人理解。**

> **V0.1 首先是一个能够处理一场完整故障的软件产品，而不是一个展示 Agent 技术栈的 Demo。**

---
