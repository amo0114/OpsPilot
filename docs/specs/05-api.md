# OpsPilot V0.1 API 契约

> Status: FROZEN  
> Version: 0.1  
> Freeze revision: FINAL-FREEZE-20260925  
> 冻结日期：2026-09-25  
> 范围：公开产品API、内部AI协议、状态前置条件、错误与SSE。  
> 本文是已合并的正式实现规格。权威清单见 [SPEC-MANIFEST.md](SPEC-MANIFEST.md)。FROZEN 表示规格定稿，不表示代码、迁移或故障实验已经通过。

## 1. API 设计最高原则

OpsPilot API 围绕：

> **用户意图**

设计。

而不是围绕：

> **数据库表**

设计。

因此不会出现：

```text
ObservationController
EvidenceController
DiagnosisController
ApprovalRequestController
```

然后全部：

```text
POST
PUT
DELETE
```

任意修改。

用户真正做的是：

```text
创建故障

开始调查

停止调查

继续调查

查看调查结果

请求处理建议

批准操作

拒绝操作

声明已经外部处理并验证

取消故障

查看恢复情况
```

这些才应该成为 API 的中心。

---

## 2. API 分成三类

### 第一类：公开产品 API

浏览器使用：

```text
/api/v1/**
```

例如：

```text
/api/v1/incidents
```

---

### 第二类：事件流 API

浏览器通过 SSE 接收已经发生的 Incident 变化：

```text
/api/v1/incidents/{incidentKey}/events
```

---

### 第三类：内部 AI Runtime API

只有 Java 主服务可以调用：

```text
/internal/v1/**
```

浏览器不能访问。

例如：

```text
/internal/v1/investigation/step
```

---

## 3. 调用关系正式冻结

```text
Browser
   │
   │ REST / SSE
   ▼
Java Spring Boot
   │
   │ Internal HTTP + JSON
   ▼
Python AI Runtime
   │
   ▼
LLM Provider
```

禁止：

```text
Browser
→ Python
```

禁止：

```text
Python
→ Java Callback
```

V0.1 Java → Python 保持单向请求。

---

## 4. API JSON 风格

HTTP API 使用：

```text
camelCase
```

例如：

```json
{
  "incidentKey": "INC-20260925-0001",
  "impactSummary": "短链接跳转速度明显下降",
  "detectedAt": "2026-09-25T07:21:31.120Z"
}
```

数据库继续使用：

```text
snake_case
```

两者不要混淆。

---

## 5. 时间格式

所有 API 时间使用：

```text
ISO 8601 UTC
```

例如：

```text
2026-09-25T07:21:31.120Z
```

前端负责转换成本地显示时间。

---

## 6. 用户可见对象优先使用业务 Key

Incident：

```text
INC-20260925-0001
```

API Path 使用：

```text
incidentKey
```

而不是数据库：

```text
id = 172
```

例如：

```http
GET /api/v1/incidents/INC-20260925-0001
```

业务系统：

```text
shortlink-platform
```

使用：

```text
systemKey
```

资源：

```text
statistics-consumer
```

使用：

```text
resourceKey
```

数据库内部 ID 不作为产品 URL 的主要标识。

---

## 7. API 不直接暴露 JPA Entity

禁止：

```text
Controller
→ Repository
→ Entity
→ JSON
```

API 必须使用明确：

```text
Request DTO
Response DTO
View DTO
```

例如：

```text
IncidentDetailView
```

不等于：

```text
IncidentEntity
```

因为数据库模型与产品界面不是一回事。

---

## 8. 标准成功响应

单对象 API：

```json
{
  "data": {
  },
  "requestId": "req_01"
}
```

列表：

```json
{
  "data": [],
  "page": {
    "number": 0,
    "size": 20,
    "totalElements": 37,
    "totalPages": 2
  },
  "requestId": "req_01"
}
```

SSE 不使用该 Envelope。

---

## 9. 标准错误响应

统一：

```json
{
  "code": "INCIDENT_STATE_CONFLICT",
  "message": "当前故障状态不允许执行该操作。",
  "requestId": "req_01",
  "details": {
    "incidentKey": "INC-20260925-0001",
    "currentStatus": "VERIFYING",
    "expectedStatuses": [
      "DIAGNOSED"
    ]
  }
}
```

前端：

> 不解析异常字符串判断业务。

只根据：

```text
code
```

处理。

---

## 10. requestId

每个请求必须存在：

```text
X-Request-Id
```

浏览器可以传。

没有则 Java 自动生成。

Java：

- HTTP 日志；
- Timeline；
- Provider 调用；
- AI Runtime 请求；

都尽量沿用该 ID 或衍生：

```text
correlationId
```

便于排障 OpsPilot 自己。

注意：

> `X-Request-Id` 是追踪标识，不是幂等键。

---

## 11. 乐观并发在 API 中如何表现

Incident 有：

```text
lock_version
```

但 API 不向前端暴露数据库术语。

统一对外叫：

```text
version
```

例如：

```json
{
  "incidentKey": "INC-20260925-0001",
  "status": "DIAGNOSED",
  "version": 7
}
```

执行状态修改操作时：

```json
{
  "expectedVersion": 7
}
```

Java 最终执行：

```text
WHERE incident_key = ?
AND status = ?
AND lock_version = 7
```

如果已经被其他请求改变：

HTTP：

```text
409 Conflict
```

错误：

```text
INCIDENT_VERSION_CONFLICT
```

同时返回当前：

```text
status
version
```

前端刷新即可。

---

## 12. 前端不能自己复制状态机

这是非常重要的规则。

例如前端不能写：

```text
if status == DIAGNOSED
显示：
继续调查
请求处理
验证恢复
```

然后自己猜业务规则。

Java 在 Incident View 中返回：

```json
{
  "availableActions": [
    "CONTINUE_INVESTIGATION",
    "REQUEST_REMEDIATION",
    "VERIFY_RECOVERY",
    "CANCEL_INCIDENT"
  ]
}
```

前端只根据：

```text
availableActions
```

决定显示哪些操作。

这样状态机只有：

> Java 一份事实来源。

---

## 13. V0.1 Incident Action 枚举

公开产品 API 使用：

```text
START_INVESTIGATION
STOP_INVESTIGATION
CONTINUE_INVESTIGATION
REQUEST_REMEDIATION
VERIFY_RECOVERY
CANCEL_INCIDENT
```

Approval 单独拥有：

```text
APPROVE
REJECT
CANCEL
```

---

## 14. 第一组 API：业务系统查询

V0.1 不通过 UI 创建复杂业务系统。

ShortLink 的接入信息：

> 可以由初始化配置 / Seed 数据建立。

因此产品 API 只需要读取。

---

## 15. 获取业务系统列表

```http
GET /api/v1/systems
```

Response：

```json
{
  "data": [
    {
      "systemKey": "shortlink-platform",
      "name": "ShortLink Platform",
      "environment": "DEMO",
      "status": "ACTIVE",
      "resourceCount": 5
    }
  ]
}
```

---

## 16. 获取业务系统详情

```http
GET /api/v1/systems/{systemKey}
```

例如：

```http
GET /api/v1/systems/shortlink-platform
```

Response：

```json
{
  "data": {
    "systemKey": "shortlink-platform",
    "name": "ShortLink Platform",
    "description": "短链接业务系统",
    "environment": "DEMO",
    "status": "ACTIVE",
    "resources": [
      {
        "resourceKey": "redirect-service",
        "name": "ShortLink Redirect Service",
        "resourceType": "SERVICE",
        "status": "ACTIVE"
      },
      {
        "resourceKey": "statistics-consumer",
        "name": "Statistics Consumer",
        "resourceType": "CONSUMER",
        "status": "ACTIVE"
      }
    ]
  }
}
```

---

## 17. 获取系统组件详情

```http
GET /api/v1/systems/{systemKey}/resources/{resourceKey}
```

返回产品可见内容：

```json
{
  "data": {
    "resourceKey": "statistics-consumer",
    "name": "Statistics Consumer",
    "resourceType": "CONSUMER",
    "status": "ACTIVE",
    "capabilities": [
      {
        "key": "service.inspect",
        "mode": "OBSERVE"
      },
      {
        "key": "service.restart",
        "mode": "CHANGE"
      }
    ],
    "recoveryPolicy": {
      "name": "统计消费者恢复标准",
      "version": 1,
      "summary": "Consumer 恢复运行且消息积压持续下降"
    }
  }
}
```

不返回：

```text
Prometheus password
Redis password
Docker credential
```

---

## 18. 唯一恢复策略与执行前快照

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

## 19. 第二组 API：Incident

---

---

## 20. 创建 Incident

```http
POST /api/v1/incidents
```

适用于：

> 用户人工创建故障。

Request：

```json
{
  "systemKey": "shortlink-platform",
  "title": "短链接跳转明显变慢",
  "description": "用户反馈短链接跳转耗时显著增加。",
  "impactSummary": "短链接跳转速度明显下降",
  "startedAt": "2026-09-25T07:10:00.000Z",
  "affectedResourceKeys": [
    "redirect-service"
  ]
}
```

其中：

```text
startedAt
```

可以为空。

为空则：

> 暂时使用 detectedAt。

Java 自动：

```text
createdSource = MANUAL
createdBy = demo-user
detectedAt = now
status = CREATED
```

Response：

```text
201 Created
```

```json
{
  "data": {
    "incidentKey": "INC-20260925-0001",
    "status": "CREATED",
    "version": 0,
    "availableActions": [
      "START_INVESTIGATION",
      "CANCEL_INCIDENT"
    ]
  }
}
```

---

## 21. Incident 创建校验

必须检查：

- systemKey 存在；
- system 处于 ACTIVE；
- affectedResourceKeys 全部属于该 system；
- title 非空；
- impactSummary 非空；
- startedAt 不明显晚于当前时间。

失败：

```text
400 REQUEST_VALIDATION_FAILED
```

或者：

```text
422 RESOURCE_NOT_IN_SYSTEM
```

---

## 22. 获取 Incident 列表

```http
GET /api/v1/incidents
```

Query：

```text
systemKey
status
page
size
```

例如：

```http
GET /api/v1/incidents?status=INVESTIGATING&page=0&size=20
```

Response：

```json
{
  "data": [
    {
      "incidentKey": "INC-20260925-0001",
      "title": "短链接跳转明显变慢",
      "systemName": "ShortLink Platform",
      "status": "INVESTIGATING",
      "impactSummary": "短链接跳转速度明显下降",
      "detectedAt": "2026-09-25T07:21:00.000Z",
      "updatedAt": "2026-09-25T07:24:00.000Z"
    }
  ]
}
```

---

## 23. Incident 详情 API

```http
GET /api/v1/incidents/{incidentKey}
```

这是故障详情页默认接口。

Response 不应该只是：

```text
incident 表字段
```

而是：

```text
IncidentDetailView
```

例如：

```json
{
  "data": {
    "incidentKey": "INC-20260925-0001",
    "title": "统计消息持续积压",
    "system": {
      "systemKey": "shortlink-platform",
      "name": "ShortLink Platform"
    },
    "status": "DIAGNOSED",
    "statusLabel": "已有诊断",
    "version": 8,

    "impact": {
      "summary": "短链接统计消息持续积压",
      "startedAt": "2026-09-25T07:10:00.000Z",
      "detectedAt": "2026-09-25T07:12:00.000Z"
    },

    "currentAssessment": {
      "conclusionType": "PRIMARY_CAUSE_IDENTIFIED",
      "summary": "统计消费者已经停止运行，导致消息无法继续消费。",
      "why": [
        "Statistics Consumer 当前处于 DOWN",
        "连续采样显示消费者组未投递消息仍在积压",
        "消息积压持续增长"
      ]
    },

    "remediation": null,

    "recovery": null,

    "availableActions": [
      "CONTINUE_INVESTIGATION",
      "REQUEST_REMEDIATION",
      "VERIFY_RECOVERY",
      "CANCEL_INCIDENT"
    ]
  }
}
```

这就是：

> 默认给普通用户看的产品数据。

---

## 24. 开始调查

```http
POST /api/v1/incidents/{incidentKey}/actions/start-investigation
```

Request：

```json
{
  "expectedVersion": 0
}
```

前置状态：

```text
CREATED
```

事务：

```text
创建 Investigation

CREATED
→ INVESTIGATING

Timeline:
INVESTIGATION_STARTED
```

然后 Java 在事务提交后：

> 启动 InvestigationOrchestrator。

Response：

```text
202 Accepted
```

```json
{
  "data": {
    "incidentKey": "INC-20260925-0001",
    "status": "INVESTIGATING",
    "version": 1,
    "investigationStarted": true
  }
}
```

同事务调用 resumeInvestigation() 初始化 current_run_no=1 和本轮控制字段；提交后派发run任务。
响应中的investigationStarted表示业务已接受并落账，不承诺Worker此刻已经获得线程。
后台补派发负责已接受任务的可达性，见07。

---

## 25. 为什么 start-investigation 返回 202

调查包含：

```text
AI Runtime
Prometheus
Redis
MySQL
日志
```

不可能让：

```text
POST start-investigation
```

一直阻塞到调查完成。

所以：

```text
202 Accepted
```

表示：

> 调查已经被系统接受并开始。

前端通过：

```text
SSE
```

持续接收进展。

---

## 26. 重复调用开始调查

如果 Incident 已经：

```text
INVESTIGATING
```

不会启动第二条 Agent Loop。

返回：

```text
409 INCIDENT_STATE_CONFLICT
```

如果是因为浏览器网络重试：

前端读取 Incident 即可确认：

> 调查已经开始。

V0.1 不增加通用 HTTP 幂等请求表。

---

## 27. 停止调查

```http
POST /api/v1/incidents/{incidentKey}/actions/stop-investigation
```
```json
{"expectedVersion": 4}
```
前置状态为 INVESTIGATING。短事务按 Incident -> Investigation 锁顺序校验状态与版本。
首次 Stop 写当前轮 stop_requested_at、stop_requested_by，递增 Investigation.lock_version，
同时递增 Incident.lock_version 并写 INVESTIGATION_STOP_REQUESTED；
状态仍为 INVESTIGATING。重复请求在同一仍活跃run已经Stop时返回既有接受结果，不重复写事件／版本。
已经结束的Incident按状态冲突返回409，客户端GET读取最终结果，不能把Stop当任意状态的通用幂等操作。

成功响应：
```json
{
  "data": {
    "incidentKey": "INC-20260925-0001",
    "status": "INVESTIGATING",
    "version": 5,
    "runNo": 1,
    "stopRequested": true
  },
  "requestId": "req_stop_01"
}
```
HTTP **202 Accepted**；不等待 AI／Provider。UI显示“正在停止调查”，最终状态由GET与SSE获取。
Stop之后不得准入新工作，Stop之前已COMMIT准入的在途调用允许在原超时内完成。
没有在途调用时Orchestrator直接收束；有在途调用时只等待其有界结束。
最终创建本轮合法Diagnosis，无足够草稿则UNDETERMINED/USER_STOPPED，状态转DIAGNOSED。

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

## 28. 继续调查

```http
POST /api/v1/incidents/{incidentKey}/actions/continue-investigation
```
```json
{"expectedVersion": 8}
```
只能从 DIAGNOSED 显式调用，且不存在 PENDING Approval；等待审批时返回
409 PENDING_APPROVAL_EXISTS，先拒绝／撤回审批再继续。

同事务调用 resumeInvestigation()：复用唯一 Investigation、current_run_no+1、
重置本轮起点／当前调用计数／连续AI失败、清除Stop时间及身份、状态迁移并追加Timeline。
旧Invocation、Observation、Evidence、Diagnosis保留。提交后派发，返回202和新的runNo、version、stopRequested=false。

12次与480秒是新run额度；没有新建Investigation，也没有给模型任意增加预算的权限。
应用重启不能通过此入口擅自刷新预算。重复Continue不会创建第二个run，当前已INVESTIGATING返回状态冲突。

---

## 29. 请求处理建议

```http
POST /api/v1/incidents/{incidentKey}/actions/request-remediation
```

Request：

```json
{
  "expectedVersion": 8
}
```

前置：

```text
Incident = DIAGNOSED
```

最新 Diagnosis 必须为：

```text
PRIMARY_CAUSE_IDENTIFIED
或
POSSIBLE_CAUSE
```

如果：

```text
UNDETERMINED
```

返回：

```text
422 DIAGNOSIS_NOT_ACTIONABLE
```

---

## 30. 请求处理建议的内部流程

API 同步等待一次 AI Remediation Draft，但不是数据库长事务：
1. 普通一致性读取／短事务取得Incident=DIAGNOSED、expectedVersion、当前Diagnosis、允许的CHANGE动作；
2. 退出事务，调用内部remediation/draft；
3. 返回后开启新短事务，重新验证Incident状态和expectedVersion、当前Diagnosis身份、
   资源归属、Binding、Proposal参数及允许的动作；
4. 同事务创建Plan、Action、PENDING Approval，Incident转AWAITING_APPROVAL，写Timeline。

AI不返回riskLevel或requiresApproval；Java Registry决定风险和审批要求。
S1/S2没有符合场景的写方案，不能为了给按钮返回内容而建议重启无关Consumer。
AI失败或重新检查失效不产生半套业务记录。成功201；AI失败保持DIAGNOSED。

---

## 31. Remediation AI 调用失败

如果 AI Runtime：

```text
timeout
unavailable
invalid structured output
```

Incident：

> 保持 `DIAGNOSED`。

不会生成半套：

```text
Plan / Action / Approval
```

错误：

```text
503 AI_RUNTIME_UNAVAILABLE
```

或：

```text
502 AI_OUTPUT_INVALID
```

用户可以重试。

---

## 32. 请求处理建议成功响应

```text
201 Created
```

```json
{
  "data": {
    "incidentKey": "INC-20260925-0001",
    "status": "AWAITING_APPROVAL",
    "version": 9,

    "plan": {
      "planId": 31,
      "title": "恢复统计消息消费能力",
      "summary": "重新启动已停止的 Statistics Consumer。",
      "status": "ACTIVE"
    },

    "action": {
      "actionId": 42,
      "capabilityKey": "service.restart",
      "targetResource": {
        "resourceKey": "statistics-consumer",
        "name": "Statistics Consumer"
      },
      "summary": "重新启动 Statistics Consumer",
      "riskLevel": "MEDIUM",
      "expectedImpactSummary": "统计消息消费将在短暂中断后重新启动。"
    },

    "approval": {
      "approvalId": 53,
      "status": "PENDING"
    }
  }
}
```

---

## 33. Incident 取消

```http
POST /api/v1/incidents/{incidentKey}/actions/cancel
```

Request：

```json
{
  "expectedVersion": 3,
  "reason": "确认是测试数据，停止处理。"
}
```

允许状态：

```text
CREATED
INVESTIGATING
DIAGNOSED
AWAITING_APPROVAL
```

不允许：

```text
EXECUTING
VERIFYING
RESOLVED
CANCELLED
```

如果当前：

```text
AWAITING_APPROVAL
```

必须在同事务：

```text
ApprovalRequest → CANCELLED
Incident → CANCELLED
```

Response：

```text
200 OK
```

取消INVESTIGATING时，旧run后续AI结果只能保留审计，不能创建新的领域变化或恢复调查。
取消等待审批的Incident同事务令PENDING Approval CANCELLED，并使未执行Plan CANCELLED；
不能留下看似可批准执行的活动方案。

---

## 34. 外部处理后请求恢复验证

用户可能：

> 自己 SSH 到机器完成处理。

因此：

```http
POST /api/v1/incidents/{incidentKey}/actions/verify-recovery
```

Request：

```json
{
  "expectedVersion": 11,
  "resourceKey": "statistics-consumer",
  "note": "已在服务器手工恢复 Statistics Consumer。"
}
```

前置：

```text
DIAGNOSED
```

Java：

1. 找到该 Resource；
2. 找到其唯一 ACTIVE RecoveryPolicy；
3. 保存 Policy Snapshot；
4. 创建 RecoveryVerification；
5. `DIAGNOSED → VERIFYING`；
6. 事务提交；
7. 开始真实验证。

Response：

```text
202 Accepted
```

创建事务必须检查没有PENDING/RUNNING Verification，冻结deadline与策略的全部采样/阈值字段。
没有已批准Execution时action_execution_id必须为空；返回的verificationNo、status=PENDING及Incident版本用于后续GET。
结果采用FAILED > INCONCLUSIVE > PASSED；FAILED回调查时使用新run，但不会重放已有CHANGE。

---

## 35. INCONCLUSIVE 后重新验证

如果上一轮：

```text
RecoveryVerification = INCONCLUSIVE
```

Incident 已回：

```text
DIAGNOSED
```

用户再次调用同一个：

```text
POST .../actions/verify-recovery
```

即可。

系统创建：

```text
新的 RecoveryVerification
```

例如：

```text
verificationNo = 2
```

不会覆盖：

```text
verificationNo = 1
```

---

## 36. 第三组 API：Approval

---

---

## 37. 获取当前待审批内容

IncidentDetailView 已经可以返回摘要。

技术上也提供：

```http
GET /api/v1/approvals/{approvalId}
```

Response：

```json
{
  "data": {
    "approvalId": 53,
    "status": "PENDING",
    "version": 0,

    "incidentKey": "INC-20260925-0001",

    "action": {
      "actionId": 42,
      "summary": "重新启动 Statistics Consumer",
      "capabilityKey": "service.restart",
      "targetResource": {
        "resourceKey": "statistics-consumer",
        "name": "Statistics Consumer"
      },
      "riskLevel": "MEDIUM",
      "expectedImpactSummary": "统计消费短暂中断后恢复。"
    },

    "requestedAt": "2026-09-25T07:31:00.000Z"
  }
}
```

用户批准的必须是：

> 明确具体 Action。

不能只是：

> “批准这场事故”。

---

## 38. 批准操作

```http
POST /api/v1/approvals/{approvalId}/actions/approve
```

Request：

```json
{
  "expectedApprovalVersion": 0,
  "expectedIncidentVersion": 9,
  "comment": "批准执行"
}
```

Java 同事务重新校验：

```text
Approval = PENDING

Incident = AWAITING_APPROVAL

Plan = ACTIVE

Plan Diagnosis = 当前最新 Diagnosis

Action Resource 仍可用

service.restart Binding 仍 enabled
```

然后：

```text
Approval → APPROVED

创建 ActionExecution(PENDING)

Incident → EXECUTING
```

提交。

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

## 39. approve 不等待 Docker restart 完成

Response：

```text
202 Accepted
```

例如：

```json
{
  "data": {
    "approvalId": 53,
    "approvalStatus": "APPROVED",
    "incidentStatus": "EXECUTING",
    "incidentVersion": 10,
    "execution": {
      "executionId": 61,
      "status": "PENDING"
    }
  }
}
```

事务提交后：

Java Executor：

```text
PENDING → RUNNING
↓
DockerServiceExecutor
```

执行结果通过 SSE 推送。

---

## 40. 为什么审批 API 不直接同步执行 Docker

否则用户请求可能：

```text
审批成功
↓
Docker 等 20 秒
↓
浏览器断线
```

用户不知道：

> 到底审批了没、执行了没。

所以：

> 批准和实际外部执行分开。

Approval 是确定性短事务。

Execution 是后续外部操作。

---

## 41. 拒绝操作

```http
POST /api/v1/approvals/{approvalId}/actions/reject
```

Request：

```json
{
  "expectedApprovalVersion": 0,
  "expectedIncidentVersion": 9,
  "comment": "当前不希望重启消费者。"
}
```

成功：

```text
Approval → REJECTED

Incident:
AWAITING_APPROVAL → DIAGNOSED
```

Plan 保留历史记录。

Response：

```text
200 OK
```

---

## 42. 取消审批

```http
POST /api/v1/approvals/{approvalId}/actions/cancel
```

适用于：

> 不批准，也不做正式拒绝，撤回当前方案。

成功：

```text
Approval → CANCELLED

Incident → DIAGNOSED
```

之后用户可以：

```text
继续调查
重新请求处理建议
取消 Incident
```

---

## 43. Approval 决策重复提交

同一操作者对同一Approval重复提交相同决定与原comment，返回200及原决定／既有Execution，
幂等命中在过时expectedVersion错误之前识别；不改写审计、不再创建Execution、不再派发CHANGE。
元数据与原决定不一致，或对已REJECTED再approve，返回409 APPROVAL_ALREADY_DECIDED。

首次决定仍必须验证expectedApprovalVersion与expectedIncidentVersion并在同一事务落账。
不允许用新的幂等键为同一个Action绕过唯一约束；服务端生成的Execution身份始终稳定。

---

## 44. 第四组 API：Execution 与 Recovery

Execution 对用户：

> 只读。

不存在：

```text
PUT /executions/61
```

或者：

```text
POST /executions/61/success
```

这种接口。

---

## 45. 获取执行记录

```http
GET /api/v1/executions/{executionId}
```

Response：

```json
{
  "data": {
    "executionId": 61,
    "status": "SUCCEEDED",

    "action": {
      "capabilityKey": "service.restart",
      "targetResource": {
        "resourceKey": "statistics-consumer",
        "name": "Statistics Consumer"
      }
    },

    "startedAt": "2026-09-25T07:33:10.000Z",
    "finishedAt": "2026-09-25T07:33:14.000Z",

    "resultSummary": "Statistics Consumer 重启命令执行成功。"
  }
}
```

必须注意产品措辞：

> “操作执行成功”

不是：

> “故障已恢复”。

---

## 46. 获取 Incident 的执行历史

```http
GET /api/v1/incidents/{incidentKey}/executions
```

因为一次 Incident 可能：

```text
Diagnosis v1
→ Execution v1 FAILED

继续调查

Diagnosis v2
→ Execution v2 SUCCEEDED
```

所以不是只返回一个执行记录。

---

## 47. 获取恢复验证列表

```http
GET /api/v1/incidents/{incidentKey}/recovery-verifications
```

Response：

```json
{
  "data": [
    {
      "verificationNo": 1,
      "status": "FAILED",
      "resultSummary": "Consumer 已恢复，但消息积压仍持续增加。"
    },
    {
      "verificationNo": 2,
      "status": "PASSED",
      "resultSummary": "Consumer 正常运行且消息积压持续下降。"
    }
  ]
}
```

---

## 48. 获取一次恢复验证详情

```http
GET /api/v1/incidents/{incidentKey}/recovery-verifications/{verificationNo}
```
返回policy版本与摘要、PENDING/RUNNING/PASSED/FAILED/INCONCLUSIVE、startedAt、finishedAt、deadlineAt，
以及按Snapshot顺序排列的checks：
```json
{
  "data": {
    "verificationNo": 1,
    "status": "INCONCLUSIVE",
    "deadlineAt": "2026-09-25T07:35:00.000Z",
    "checks": [
      {
        "criterionKey": "stream-pending-healthy",
        "result": "UNKNOWN",
        "samples": [{"sampleIndex": 1, "invocationId": 91, "status": "FAILED", "errorCode": "TIMEOUT"}],
        "summary": "未取得足够有效的已投递未确认统计"
      }
    ],
    "resultSummary": "没有明确未恢复证据，但必要观测不完整，暂时无法确认。"
  },
  "requestId": "req_recovery_01"
}
```
这是局部checks示例；实际返回所有已定义检查，未执行项须标明原因，不伪造样本。
检查显示TRUE/FALSE/UNKNOWN，Verification显示整体业务状态；不要混用两套枚举。
只有没有明确FALSE但存在UNKNOWN时才显示上例的INCONCLUSIVE说明。

---

## 49. 第五组：调查技术详情 API

默认 Incident 页面：

> 不暴露大量技术对象。

用户点击：

> 查看技术详情

才调用下面的接口。

---

## 50. 获取 Investigation 概览

```http
GET /api/v1/incidents/{incidentKey}/investigation
```
```json
{
  "data": {
    "runNo": 2,
    "startedAt": "2026-09-25T07:00:00.000Z",
    "currentRunStartedAt": "2026-09-25T07:20:00.000Z",
    "lastActivityAt": "2026-09-25T07:22:05.000Z",
    "stopRequested": false,
    "budget": {
      "scope": "ACTIVE_RUN",
      "capabilityCallsUsed": 6,
      "capabilityCallsLimit": 12,
      "remainingCapabilityCalls": 6,
      "durationSeconds": 125,
      "durationLimitSeconds": 480
    },
    "totalCapabilityCalls": 15,
    "hypothesisCount": 3,
    "observationCount": 18,
    "evidenceCount": 7,
    "diagnosisVersions": 1
  },
  "requestId": "req_investigation_01"
}
```
本轮额度与历史累计明确分开。startedAt是首次调查，currentRunStartedAt才是本轮deadline的起点。

---

## 51. 获取 Hypothesis

```http
GET /api/v1/incidents/{incidentKey}/investigation/hypotheses
```

返回：

```json
{
  "data": [
    {
      "id": 11,
      "title": "Statistics Consumer 已停止",
      "description": "...",
      "status": "SUPPORTED"
    },
    {
      "id": 12,
      "title": "Producer 已停止生产消息",
      "status": "REFUTED"
    }
  ]
}
```

没有：

```text
POST /hypotheses
PUT /hypotheses
```

公开接口。

这些是 Agent 调查产生的数据。

---

## 52. 获取 Observation

```http
GET /api/v1/incidents/{incidentKey}/investigation/observations
```

支持：

```text
resourceKey
kind
page
size
```

Response：

```json
{
  "data": [
    {
      "id": 101,
      "kind": "QUEUE_STATUS",
      "resource": {
        "resourceKey": "statistics-stream",
        "name": "Statistics Stream"
      },
      "summary": "消息积压数量为 2180",
      "observedAt": "...",
      "capabilityInvocationId": 81
    }
  ]
}
```

默认不返回大型：

```text
payload
raw_result
```

---

## 53. 获取 Observation 详情

```http
GET /api/v1/incidents/{incidentKey}/investigation/observations/{observationId}
```

这里才返回：

```text
schema
structured payload
source window
invocation reference
```

已经脱敏后的内容。

---

## 54. 获取 Evidence

```http
GET /api/v1/incidents/{incidentKey}/investigation/evidence
```
```json
{
  "data": [
    {
      "id": 131,
      "relation": "SUPPORTS",
      "reason": "消费者在观测时已停止，支持统计消息无法继续处理的假设。",
      "observation": {"id": 101, "summary": "Statistics Consumer = STOPPED"},
      "hypothesis": {"id": 11, "title": "统计消费者停止导致积压"}
    }
  ],
  "requestId": "req_evidence_01"
}
```
Evidence没有内容版本或更新接口。同一Observation×Hypothesis最多一条，历史解释不可改。

---

## 55. 获取 Diagnosis 历史

```http
GET /api/v1/incidents/{incidentKey}/diagnoses
```

Response：

```json
{
  "data": [
    {
      "version": 1,
      "conclusionType": "POSSIBLE_CAUSE",
      "summary": "...",
      "createdAt": "..."
    },
    {
      "version": 2,
      "conclusionType": "PRIMARY_CAUSE_IDENTIFIED",
      "summary": "...",
      "createdAt": "..."
    }
  ]
}
```

---

## 56. 获取具体 Diagnosis

```http
GET /api/v1/incidents/{incidentKey}/diagnoses/{version}
```
返回该Diagnosis的runNo、结论、主假设及创建时冻结引用的具体evidenceIds和内容；
不得动态取Hypothesis当前所有Evidence作为历史依据。Diagnosis仍版本化，Evidence不版本化。

---

## 57. 获取 CapabilityInvocation

```http
GET /api/v1/incidents/{incidentKey}/technical/capability-invocations
```

Response：

```json
{
  "data": [
    {
      "id": 81,
      "capabilityKey": "queue.inspect",
      "resourceKey": "statistics-stream",
      "status": "SUCCEEDED",
      "startedAt": "...",
      "finishedAt": "...",
      "durationMs": 83
    }
  ]
}
```

技术列表还返回context=INVESTIGATION/RECOVERY_VERIFICATION的展示标签；
调查项返回runNo，恢复项返回verificationNo、criterionKey、sampleIndex。
这是View派生字段，数据库仍使用两个明确外键，而非新增泛型context_id。

---

## 58. 获取 Invocation 详情

```http
GET /api/v1/incidents/{incidentKey}/technical/capability-invocations/{invocationId}
```

可以查看：

- Capability；
- Target Resource；
- request payload；
- response summary；
- error；
- raw result reference。

但任何：

```text
credential
secret
token
```

绝不能返回。

---

## 59. 第六组：Timeline API

---

---

## 60. 获取时间线

```http
GET /api/v1/incidents/{incidentKey}/timeline
```

建议使用：

```text
afterId
limit
```

而不是传统页码。

因为 Timeline 是：

> append-only。

例如：

```http
GET /api/v1/incidents/INC-001/timeline?afterId=120&limit=50
```

Response：

```json
{
  "data": [
    {
      "id": 121,
      "eventType": "OBSERVATION_RECORDED",
      "occurredAt": "...",
      "actorType": "SYSTEM",
      "summary": "检查消息队列，发现积压消息数量为 2180。"
    },
    {
      "id": 122,
      "eventType": "HYPOTHESIS_CREATED",
      "occurredAt": "...",
      "actorType": "AI_RUNTIME",
      "summary": "开始验证统计消费者是否已经停止运行。"
    }
  ],
  "nextAfterId": 122
}
```

---

## 61. Timeline 默认返回人类可读 summary

默认页面不要展示：

```text
OBSERVATION_RECORDED
PROPOSE_EVIDENCE_LINK
```

作为主要文案。

应该显示：

> 检查消息队列，发现积压消息数量为 2180。

技术详情才显示：

```text
eventType
payload
```

---

## 62. SSE Endpoint

```http
GET /api/v1/incidents/{incidentKey}/events
Accept: text/event-stream
```

---

## 63. SSE 的事实来源

SSE **只推送 Java 已经成功提交的状态变化或 TimelineEvent**。

禁止：

```text
Python token stream
↓
直接发给浏览器
```

因为：

> 模型刚说“已发现 Redis 异常”不代表 Java 已经成功创建 Observation / Evidence。

只有落账以后才是产品事实。

---

## 64. SSE 事件格式

例如：

```text
id: 121
event: timeline
data: {"incidentKey":"INC-001","incidentStatus":"INVESTIGATING","event":{"id":121,"summary":"检查消息队列，发现积压数量为 2180。"}}
```

状态变化同样可以：

```text
event: incident-state
```

例如：

```json
{
  "incidentKey": "INC-001",
  "status": "DIAGNOSED",
  "version": 7,
  "availableActions": [
    "CONTINUE_INVESTIGATION",
    "REQUEST_REMEDIATION",
    "VERIFY_RECOVERY",
    "CANCEL_INCIDENT"
  ]
}
```

---

## 65. SSE 断线恢复

连接支持Last-Event-ID，以该Incident的Timeline ID作为游标，从数据库补发已提交事件。
首次打开页面先取得同一一致性读中的Incident Snapshot及lastTimelineEventId，
再以该游标连接SSE并补齐Snapshot之后的事件；首次无游标时从服务端规定的起点补发，前端按event id去重。

订阅注册与追赶数据库游标必须消除“查完历史、尚未注册实时连接”的空隙。
同Incident Timeline追加遵循Incident行锁顺序；afterCommit只是唤醒，发送端依数据库id顺序补读，
不能把多个回调抵达顺序当事件顺序。断连不丢业务事实，SSE不承担独立可靠消息队列职责。

---

## 66. SSE Heartbeat

为了避免代理层关闭空闲连接：

每隔一段时间发送：

```text
event: heartbeat
```

不产生：

```text
TimelineEvent
```

Heartbeat 只是连接保活。

---

## 67. SSE 不代替 GET

SSE：

> 推送变化。

GET：

> 获取当前完整事实。

前端重新打开 Incident：

必须先：

```text
GET /incidents/{key}
```

获得 Snapshot，

再连接 SSE。

不要靠：

> 从第一条 SSE 一直重放

重建完整页面。

OpsPilot 不是 Event Sourcing 系统。

Incident Detail 的Snapshot新增lastTimelineEventId，与业务状态在同一一致性读取中取得。
SSE仅提示变化；前端需要完整状态时重新GET，不从模型token流或过时事件payload猜状态。

---

## 68. 第七组：Fault Lab

Fault Lab 只作用于：

> 本地 / Demo / 测试环境。

不能对 Production ManagedSystem 暴露故障注入按钮。

---

## 69. 获取故障场景

```http
GET /api/v1/fault-lab/scenarios
```

Response：

```json
{
  "data": [
    {
      "scenarioKey": "redis-latency",
      "name": "Redis 响应延迟",
      "description": "人为增加 Redis 请求延迟，用于验证缓存异常场景。",
      "targetResourceKey": "shortlink-redis"
    },
    {
      "scenarioKey": "mysql-slow-query",
      "name": "MySQL 慢查询",
      "targetResourceKey": "shortlink-mysql"
    },
    {
      "scenarioKey": "statistics-consumer-stop",
      "name": "统计消费者停止",
      "targetResourceKey": "statistics-consumer"
    }
  ]
}
```

Ground Truth：

> 不返回。

---

## 70. 注入故障

```http
POST /api/v1/fault-lab/scenarios/{scenarioKey}/actions/inject
```

Request：

```json
{
  "systemKey": "shortlink-platform"
}
```

成功：

1. 创建 FaultExperiment；
2. 执行故障注入；
3. 标记 ACTIVE；
4. 创建 Incident；
5. Incident 保持 `CREATED`；
6. 返回 Incident。

Response：

```text
201 Created
```

```json
{
  "data": {
    "experimentId": 71,
    "status": "ACTIVE",
    "incident": {
      "incidentKey": "INC-20260925-0003",
      "status": "CREATED",
      "availableActions": [
        "START_INVESTIGATION",
        "CANCEL_INCIDENT"
      ]
    }
  }
}
```

---

## 71. Fault Lab 注入失败

如果故障实际上没有成功注入：

```text
FaultExperiment = FAILED
```

不创建虚假：

```text
ACTIVE Incident
```

返回：

```text
502 FAULT_INJECTION_FAILED
```

---

## 72. 恢复实验环境

```http
POST /api/v1/fault-lab/experiments/{experimentId}/actions/reset
```

该 API：

> 只负责恢复 Fault Lab 注入的实验环境。

它不等同于：

> OpsPilot Incident Recovery。

也不会直接把：

```text
Incident → RESOLVED
```

因为 Incident 是否恢复仍然必须经过：

```text
RecoveryVerification
```

---

## 73. Ground Truth 不存在公开读取接口

禁止：

```http
GET /fault-lab/experiments/71/ground-truth
```

出现在普通产品 API。

如果未来做自动评测：

使用单独：

```text
Evaluation 内部代码路径
```

读取。

Agent Context Builder：

> 不得调用。

---

## 74. 第八组：内部 Java → AI Runtime API

这部分不是产品公开 API。

前缀：

```text
/internal/v1
```

---

## 75. AI Runtime 健康检查

```http
GET /internal/v1/health
```

Response：

```json
{
  "status": "UP",
  "modelProvider": "openai-compatible",
  "model": "configured-model"
}
```

不返回：

```text
apiKey
baseUrl credential
```

等敏感数据。

---

## 76. Investigation Step API

```http
POST /internal/v1/investigation/step
```

这是 AI Runtime 最核心接口。

Java 每次问：

> “根据目前掌握的事实，下一步最值得做什么？”

---

## 77. InvestigationStepRequest

Request顶层包括protocolVersion=1、investigationId、runNo、stepId、correlationId和本次必要上下文。
runNo与stepId由Java生成，并在请求之前持久化；Python只能回显，不分配权威编号。

```json
{
  "protocolVersion": 1,
  "investigationId": 7,
  "runNo": 2,
  "stepId": 42,
  "correlationId": "corr_step_42",
  "incident": {
    "incidentKey": "INC-20260925-0001",
    "title": "统计消息持续积压",
    "impactSummary": "统计数据无法及时更新",
    "startedAt": "2026-09-25T07:00:00.000Z"
  },
  "affectedResources": [
    {"resourceId": 12, "resourceKey": "statistics-consumer", "resourceType": "CONSUMER"}
  ],
  "hypotheses": [
    {"id": 21, "title": "消费者已停止", "status": "PENDING"}
  ],
  "observations": [
    {"id": 31, "runNo": 2, "summary": "所选组lag=2180", "kind": "QUEUE_STATUS", "observedAt": "2026-09-25T07:21:00.000Z"}
  ],
  "evidence": [],
  "availableCapabilities": [
    {"key": "queue.inspect", "descriptorType": "QUEUE_INSPECT", "resourceId": 13, "resourceKey": "statistics-stream"},
    {"key": "service.inspect", "descriptorType": "SERVICE_INSPECT", "resourceId": 12, "resourceKey": "statistics-consumer"}
  ],
  "budget": {
    "scope": "ACTIVE_RUN",
    "capabilityCallsUsed": 1,
    "capabilityCallsLimit": 12,
    "remainingCapabilityCalls": 11,
    "elapsedSeconds": 31,
    "durationLimitSeconds": 480
  },
  "recentTimeline": []
}
```
上下文可含以前Diagnosis冻结的历史证据摘要，并标历史身份；不得混入旧run未被诊断引用的迟到结果。
FaultExperiment、控制器日志、真实密码和任意业务payload均不能进入请求。

---

## 78. InvestigationStepResponse

一次Response恰有一个主Intent，回显protocolVersion、runNo和stepId。
Java校验回显与已登记请求一致，不把回显当作授权。错误或迟到结果按原Step审计，不驱动新run。

```json
{
  "protocolVersion": 1,
  "runNo": 2,
  "stepId": 42,
  "intentType": "REQUEST_CAPABILITY",
  "requestCapability": {
    "capabilityKey": "service.inspect",
    "resourceId": 12,
    "arguments": {},
    "purpose": "核对消费者当前运行状态。"
  }
}
```
各Intent使用判别联合，禁止同时出现两个主payload或未定义字段；
PROPOSE_EVIDENCE_LINK允许附带与同一Hypothesis直接相关的hypothesisUpdate。

---

## 79. 允许的主 Intent

冻结：

```text
REQUEST_CAPABILITY

PROPOSE_HYPOTHESIS

UPDATE_HYPOTHESIS

PROPOSE_EVIDENCE_LINK

COMPLETE_INVESTIGATION
```

`PROPOSE_REMEDIATION`

不允许出现在：

```text
/investigation/step
```

中。

---

## 80. REQUEST_CAPABILITY

requestCapability必须包含capabilityKey、resourceId、arguments、purpose。
arguments由capabilityKey判别为六种OBSERVE强类型之一；无参数能力也必须显式 `{}`，不能缺省或接受额外字段。

```json
{
  "protocolVersion": 1,
  "runNo": 2,
  "stepId": 43,
  "intentType": "REQUEST_CAPABILITY",
  "requestCapability": {
    "capabilityKey": "metrics.query",
    "resourceId": 14,
    "arguments": {
      "metricKey": "http.request.latency.p99",
      "windowKey": "INCIDENT_CONTEXT",
      "comparePreviousWindow": true
    },
    "purpose": "确认实际业务HTTP影响。"
  }
}
```
Java重新验证资源属于该Incident系统、绑定可用、指标在Descriptor中、窗口合法、
当前run、Stop、预算与deadline，并通过同一准入事务后才调用Provider。

---

## 81. PROPOSE_HYPOTHESIS

```json
{
  "protocolVersion": 1,
  "runNo": 2,
  "stepId": 44,
  "intentType": "PROPOSE_HYPOTHESIS",
  "proposeHypothesis": {
    "title": "Statistics Consumer 已停止",
    "description": "消息仍持续进入 Stream，但长时间没有新的消费确认。"
  }
}
```

Java校验后：

> 才产生正式 Hypothesis。

本示例独立说明Intent结构；没有实际ACK时间观测时，描述不得声称“最后一次ACK”的具体时刻。

---

## 82. PROPOSE_EVIDENCE_LINK

```json
{
  "protocolVersion": 1,
  "runNo": 2,
  "stepId": 44,
  "intentType": "PROPOSE_EVIDENCE_LINK",
  "proposeEvidenceLink": {
    "observationId": 31,
    "hypothesisId": 21,
    "relation": "SUPPORTS",
    "reason": "所选消费者组存在未投递消息积压，与消费停滞假设一致。"
  },
  "hypothesisUpdate": {
    "hypothesisId": 21,
    "targetStatus": "SUPPORTED"
  }
}
```

这里体现冻结规则：

> 一个主 Intent。

允许附带：

> 与当前 Evidence 直接相关的 Hypothesis 状态更新。

---

## 83. Evidence 重复关系处理

同一Observation×Hypothesis已存在Evidence时，返回结构化Intent拒绝原因EVIDENCE_LINK_ALREADY_EXISTS，
保留Step输出与拒绝审计，不更新关系、不创建新版本、不复制Observation绕过约束。
Agent只能根据真实新观测建立新Evidence，或在新的Diagnosis中重新表达结论。

---

## 84. UPDATE_HYPOTHESIS

单独调整假设状态：

```json
{
  "protocolVersion": 1,
  "runNo": 2,
  "stepId": 44,
  "intentType": "UPDATE_HYPOTHESIS",
  "updateHypothesis": {
    "hypothesisId": 21,
    "targetStatus": "INSUFFICIENT_EVIDENCE",
    "reason": "当前信息不足以判断 Consumer 是否是主要原因。"
  }
}
```

Java仍需校验状态合法性。

---

## 85. COMPLETE_INVESTIGATION

COMPLETE_INVESTIGATION必须附Diagnosis草稿，包含conclusionType、primaryHypothesisId（按结论类型可空）、
summary、impactSummary、evidenceIds。返回仍带protocolVersion/runNo/stepId。

Java在结果短事务中检查：原run仍有效、Incident阶段允许、引用均真实且属于该Investigation、
PRIMARY/POSSIBLE至少引用一条关联主假设的SUPPORTS Evidence。
通过后新增Diagnosis（run_no、version_no）、冻结引用、失效旧未执行方案、迁移DIAGNOSED并写Timeline。

同轮Stop后在途合法COMPLETE可以收束；已换run或已CANCELLED的COMPLETE只能审计，不写新Diagnosis。
不会接受AI提交incidentStatus、execute或RESOLVED。

合法响应示例（编号仅用于说明引用关系，不代表已产生的运行记录）：
```json
{
  "protocolVersion": 1,
  "runNo": 2,
  "stepId": 45,
  "intentType": "COMPLETE_INVESTIGATION",
  "completeInvestigation": {
    "diagnosis": {
      "conclusionType": "PRIMARY_CAUSE_IDENTIFIED",
      "primaryHypothesisId": 21,
      "summary": "统计消费者停止运行，消息持续进入但未能继续消费。",
      "impactSummary": "访问统计更新滞后，短链跳转服务仍在运行。",
      "evidenceIds": [41, 42]
    }
  }
}
```
41/42必须是当前Investigation中真实存在且支持对应主假设的证据引用；服务运行、生产持续和影响描述均须有真实依据。

---

## 86. AI Runtime 不可以返回状态迁移

禁止 Response：

```json
{
  "incidentStatus": "DIAGNOSED"
}
```

或者：

```json
{
  "execute": true
}
```

AI只表达：

```text
Intent
Proposal
Draft
```

状态迁移永远由 Java决定。

---

## 87. Remediation Draft API

调查完成后 Java 单独调用：

```http
POST /internal/v1/remediation/draft
```

Request：

```json
{
  "protocolVersion": 1,
  "correlationId": "corr_remediation_01",
  "incident": {
    "incidentKey": "INC-001",
    "impactSummary": "统计消息持续积压"
  },
  "diagnosis": {
    "version": 1,
    "conclusionType": "PRIMARY_CAUSE_IDENTIFIED",
    "summary": "Statistics Consumer 已停止运行。",
    "evidence": [
      {
        "id": 41,
        "summary": "Statistics Consumer 当前处于 DOWN"
      }
    ]
  },
  "allowedActions": [
    {
      "capabilityKey": "service.restart",
      "resourceId": 12,
      "resourceKey": "statistics-consumer",
      "resourceName": "Statistics Consumer"
    }
  ]
}
```

---

## 88. Remediation Draft Response

```json
{
  "protocolVersion": 1,
  "correlationId": "corr_remediation_01",
  "intentType": "PROPOSE_REMEDIATION",
  "proposal": {
    "title": "恢复统计消息消费能力",
    "summary": "建议重新启动已停止的 Statistics Consumer。",
    "action": {
      "capabilityKey": "service.restart",
      "targetResourceId": 12,
      "parameters": {},
      "summary": "重新启动 Statistics Consumer",
      "expectedImpactSummary": "服务将发生短暂重启。"
    }
  }
}
```

Python：

> 只能从 Java 提供的 `allowedActions` 中选择。

即便模型输出：

```text
deployment.rollback
```

Java也会拒绝。

AI输出合同禁止riskLevel和requiresApproval，Java产生并持久化这两个确定性策略结果。
同样不能把RecoveryPolicy、容器身份或基础设施凭证放入AI参数。

---

## 89. Internal API 超时

Java调用Python的单步上限来自agent_step_timeout_seconds，默认60秒；
本轮墙钟deadline来自current_run_started_at+max_duration_seconds，默认480秒。
客户端实际等待不超过二者剩余时间中的较小值；调用失败只登记本Step，不做透明HTTP重试。
连续失败在当前run计数，达到阈值后确定性收束；应用中断不等同于模型自身失败。

---

## 90. Internal API 认证

虽然 V0.1 是 Demo，

Java → Python 也不能设计成：

> 公网上裸露随便调。

最低要求：

- AI Runtime 只监听内部网络；
- 不映射公网端口；
- Java 使用内部共享 Token 或容器网络隔离；
- Browser 无法访问。

V0.1 不建设：

```text
OAuth2 between services
mTLS PKI
Service Mesh
```

部署前提保持单用户Demo：内部Token不代替公开产品入口的访问隔离。
本规格未建设企业用户系统；实际部署不得把可批准restart的Demo API无保护暴露公网。

---

## 91. API 状态前置条件总表

| 用户动作 | 前置状态 | API接受后的状态 | HTTP／后续 |
|---|---|---|---|
| 创建 | 无 | CREATED | 201 |
| Start | CREATED | INVESTIGATING，新run=1 | 202，后台调查 |
| Stop | INVESTIGATING | 仍INVESTIGATING，stopRequested=true | 202，最终DIAGNOSED |
| Continue | DIAGNOSED且无待审批 | INVESTIGATING，新run | 202 |
| 请求处理建议 | DIAGNOSED且结论可操作 | 成功后AWAITING_APPROVAL | 同步一次AI，成功201 |
| Approve | AWAITING_APPROVAL | EXECUTING，Execution PENDING且有Policy快照 | 202 |
| Reject／Cancel Approval | AWAITING_APPROVAL | DIAGNOSED | 200 |
| 外部处理后验证 | DIAGNOSED | VERIFYING，Verification PENDING | 202 |
| 取消Incident | CREATED／INVESTIGATING／DIAGNOSED／AWAITING_APPROVAL | CANCELLED | 200 |

后台：Execution成功进入VERIFYING，失败或不确定收束回DIAGNOSED；
Verification FAILED开启新run、INCONCLUSIVE回DIAGNOSED、PASSED才RESOLVED。

---

## 92. API 禁止存在的接口

V0.1 明确禁止：

```http
PUT /api/v1/incidents/{id}/status
```

因为：

> 用户不能任意指定状态。

---

禁止：

```http
POST /api/v1/evidence
```

因为：

> 用户不能伪造证据。

---

禁止：

```http
PUT /api/v1/diagnoses/{id}
```

因为：

> Diagnosis 不覆盖修改。

---

禁止：

```http
POST /api/v1/executions
```

因为：

> Execution 只能由已批准 Action 创建。

---

禁止：

```http
POST /api/v1/executions/{id}/success
```

因为：

> 执行结果来自 Executor。

---

禁止：

```http
POST /api/v1/incidents/{id}/resolve
```

因为：

> RESOLVED 只能来自 PASSED RecoveryVerification。

---

禁止：

```http
POST /api/v1/ai/chat
```

作为 OpsPilot V0.1 核心入口。

OpsPilot 不是一个：

> 运维聊天机器人。

---

## 93. 核心错误码

### 通用

```text
REQUEST_VALIDATION_FAILED
RESOURCE_NOT_FOUND
SYSTEM_NOT_FOUND
INCIDENT_NOT_FOUND
```

---

### 状态 / 并发

```text
INCIDENT_STATE_CONFLICT
INCIDENT_VERSION_CONFLICT
APPROVAL_VERSION_CONFLICT
APPROVAL_ALREADY_DECIDED
PENDING_APPROVAL_EXISTS
```

---

### Diagnosis

```text
DIAGNOSIS_NOT_FOUND
DIAGNOSIS_NOT_ACTIONABLE
DIAGNOSIS_INVARIANT_VIOLATION
```

---

### Capability

```text
CAPABILITY_NOT_FOUND
CAPABILITY_NOT_BOUND
CAPABILITY_NOT_ALLOWED
CAPABILITY_BUDGET_EXHAUSTED
CAPABILITY_INVOCATION_FAILED
```

---

### AI Runtime

```text
AI_RUNTIME_UNAVAILABLE
AI_RUNTIME_TIMEOUT
AI_OUTPUT_INVALID
AI_INTENT_NOT_ALLOWED
```

---

### Remediation

```text
REMEDIATION_PLAN_SUPERSEDED
REMEDIATION_ACTION_NOT_EXECUTABLE
```

---

### Recovery

```text
RECOVERY_POLICY_NOT_FOUND
RECOVERY_POLICY_AMBIGUOUS
RECOVERY_VERIFICATION_ALREADY_RUNNING
```

`RECOVERY_POLICY_AMBIGUOUS`

理论上 V0.1 配置正确时不会出现。

如果出现：

> 在写操作准入前拒绝创建 Execution；外部处理验证则拒绝创建 Verification。

绝不能随便选一条 Policy。

---

### Fault Lab

```text
FAULT_SCENARIO_NOT_FOUND
FAULT_SCENARIO_NOT_ALLOWED
FAULT_INJECTION_FAILED
FAULT_EXPERIMENT_STATE_CONFLICT
FAULT_RESET_FAILED
```

### 运行控制拒绝与采样错误
旧run结果记内部审计处置 `STALE_RUN_RESULT`，不当作新的Incident状态；
已Stop后的准入按当前业务状态拒绝，不创建调用。
`PROCESS_INTERRUPTED`、`EXECUTION_RESULT_UNCERTAIN`为运行记录error_code；
`EVIDENCE_LINK_ALREADY_EXISTS`、`CAPABILITY_DUPLICATE_REQUEST`、
`CAPABILITY_ARGUMENT_INVALID`、`METRIC_COMPARISON_WINDOW_EXCEEDS_LIMIT`
为对应Guard／Intent的明确拒绝码。

机器Schema未知字段／联合类型不合法返回AI_OUTPUT_INVALID；错误文本不得回显Secret。

---

## 94. HTTP Status 建议

| 场景 | HTTP |
|---|---:|
| 创建成功 | `201` |
| 异步动作接受 | `202` |
| 普通成功 | `200` |
| 参数格式错误 | `400` |
| 未找到 | `404` |
| 状态/版本冲突 | `409` |
| 请求语义不满足业务规则 | `422` |
| AI/Provider 暂时不可用 | `503` |
| 下游返回非法结果 | `502` |
| 下游超时 | `504` |

---

## 95. Provider 失败不应该变成 500

例如：

Prometheus 暂时挂了。

这不是：

> OpsPilot Java 程序 NullPointerException。

内部应该产生：

```text
CapabilityInvocation = FAILED
```

调查可以继续。

只有具体同步 API 本身依赖该 Provider 且无法完成时，才返回适当：

```text
503
```

不要所有异常：

```text
catch Exception
→ 500
```

---

## 96. API 日志禁止记录敏感载荷

不要完整打印：

```text
Authorization
Credential
Raw Loki Log
MySQL Connection String with password
LLM API Key
```

日志至少要有：

```text
requestId
incidentKey
operation
result
duration
```

但敏感字段必须脱敏。

---

## 97. API 与状态机测试如何对应

前面 Frozen 的：

```text
INV-001 ～ INV-015
```

现在可以直接转为 API / Application Service 测试。

例如：

#### INV-001

测试：

```text
POST /incidents/{id}/resolve
```

根本不存在。

以及：

Verification FAILED 时：

```text
Incident != RESOLVED
```

---

#### INV-004

未批准 Action：

无法生成 Execution。

---

#### INV-012

最新 Diagnosis：

```text
UNDETERMINED
```

调用：

```text
request-remediation
```

必须：

```text
422 DIAGNOSIS_NOT_ACTIONABLE
```

---

#### INV-014

等待审批时：

```text
continue-investigation
```

必须：

```text
409 PENDING_APPROVAL_EXISTS
```

---

## 98. S3 通过 API 完整跑一遍

```text
注入statistics-consumer-stop -> 201 + Incident CREATED
Start -> 202 + INVESTIGATING/current run
GET Snapshot + SSE -> 获取已提交调查事实
合法Diagnosis -> DIAGNOSED
request-remediation -> 201 + Plan/Action/Approval
approve -> 202 + PENDING Execution + 已冻结Policy
Worker -> 一次CHANGE（必要时有界只读核对）
Execution成功 -> VERIFYING
按B/C/D/A执行真实恢复采样
全部required TRUE -> PASSED -> RESOLVED
```
任何required FALSE优先FAILED；没有FALSE但有UNKNOWN才INCONCLUSIVE。
用户界面严格区分“操作成功”“正在核对执行结果”“恢复验证通过”。

---

## 99. Controller 层应该非常薄

未来 Java 实现时：

Controller 负责：

```text
HTTP 参数
认证上下文
Request DTO
调用 Application Service
映射 Response DTO
```

不负责：

```text
判断状态机
判断 Evidence
访问 Repository
直接调用 Python
直接调用 Docker
```

例如：

```text
IncidentActionController
```

调用：

```text
InvestigationApplicationService
RemediationApplicationService
IncidentApplicationService
```

而不是自己：

```text
if (...) {
    repository.update(...)
}
```

---

## 100. API 分组建议

未来 OpenAPI 文档可以分：

```text
Systems

Incidents

Investigation

Approvals

Executions

Recovery

Timeline

Fault Lab
```

不要出现：

```text
Database CRUD
Internal Entity Management
```

这种后台管理风格。

---

## 101. OpenAPI 应作为实现契约

正式开发时：

Java 对外 API 应维护：

```text
OpenAPI 3.x
```

契约。

至少明确：

- Path；
- Method；
- Request；
- Response；
- Enum；
- Error Code；
- HTTP Status。

前端 TS 类型：

> 尽量从 OpenAPI 生成。

避免：

```text
Java DTO 改了
前端 interface 忘记改
```

---

## 102. Internal AI API 也必须有 Schema

Java 和 Python 之间不能靠：

```text
“大家约定一下这个 JSON 长这样”
```

应拥有：

```text
InvestigationStepRequest
InvestigationStepResponse
RemediationDraftRequest
RemediationDraftResponse
```

明确版本。

Python：

```text
Pydantic
```

Java：

```text
明确 DTO
```

双方都做验证。

---

## 103. Internal Schema Version

Internal V1统一使用JSON顶层`protocolVersion: 1`，不采用Header与Body二选一。
Investigation请求带investigationId/runNo/stepId/correlationId；响应回显protocolVersion/runNo/stepId。
Remediation请求与响应均带protocolVersion/correlationId。
Java与Python遇到不兼容版本或非法联合类型必须拒绝；不做自动降级或复杂版本协商。
具体字段见本文件§77～88，Schema由TASK-028建立唯一机器合同并通过双方fixture验证。

---

## 104. API 契约阶段冻结标准

实现和验收必须能够明确回答：

#### 用户如何创建故障？

```text
POST /incidents
```

#### 如何开始调查？

```text
POST /incidents/{key}/actions/start-investigation
```

#### 调查是同步还是异步？

异步，202 + SSE。

#### 谁决定前端显示哪些按钮？

Java 返回 `availableActions`。

#### 用户能不能改 Incident.status？

不能。

#### 用户能不能创建 Evidence？

不能。

#### 用户能不能直接执行 restart？

不能。

#### 谁调用 AI Runtime？

只有 Java。

#### Python 返回什么？

结构化 Intent / Proposal。

#### 谁执行 Capability？

Java。

#### 调查过程怎么实时显示？

Java SSE，且只推送已落账事实。

#### 外部手工处理后怎么办？

`verify-recovery`。

#### 执行成功就是恢复吗？

不是。

#### RESOLVED 有 API 可以直接点吗？

没有。

这些都清晰以后：

> API 阶段才算完成。

---

## 105. Capability 合同引用

Capability已在 [06-capability.md](06-capability.md) 提供，不再是占位协议。
OpenAPI公开合同与JSON Schema内部合同在各自任务实现；六种OBSERVE参数及Descriptor必须匹配，不接受万能JSON。

---

## 106. API 冻结声明

本API规范为FROZEN，包含产品查询、动作、SSE和内部AI边界。
不得按旧补丁恢复同步Stop、AI风险字段或可修改Evidence接口；实现只读本文件与其规范内引用。

---

## 107. API 不变量

| ID | 冻结规则 |
|---|---|
| API-INV-001 | 公开API不得任意修改Incident.status |
| API-INV-002 | Stop 202仅代表停止意图已持久化，不代表已DIAGNOSED |
| API-INV-003 | Stop提交后不得新准入Step／调查Invocation，先准入的在途工作按协作式规则完成 |
| API-INV-004 | Remediation失败不产生半套Plan／Action／Approval |
| API-INV-005 | 同Resource最多一个ACTIVE RecoveryPolicy |
| API-INV-006 | Evidence不可变且关系唯一，不进行内容版本化 |
| API-INV-007 | Diagnosis冻结具体Evidence ID，不动态解析当前证据集合 |

---
