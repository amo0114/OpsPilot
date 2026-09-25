# OpsPilot V0.1 Capability 详细契约

> Status: FROZEN  
> Version: 0.1  
> Freeze revision: FINAL-FREEZE-20260925  
> 冻结日期：2026-09-25  
> 范围：七个能力的输入输出、受控参数、Provider、脱敏、恢复谓词与S3策略。  
> 本文是已合并的正式实现规格。权威清单见 [SPEC-MANIFEST.md](SPEC-MANIFEST.md)。FROZEN 表示规格定稿，不表示代码、迁移或故障实验已经通过。

## 1. 先回答最重要的问题：接一个业务系统，要不要改目标系统代码？

答案是：

### 不应该默认需要。

OpsPilot 的目标不是：

> 每接入一个系统，就往目标业务系统里塞一套 OpsPilot SDK。

正确目标应该是：

> **优先通过目标系统已经存在的标准可观测接口和基础设施接口完成接入。**

例如一个普通 Java 系统可能已经有：

```text
Prometheus
日志
MySQL
Redis
Docker
```

那么 OpsPilot 完全可以在业务系统外部完成：

```text
查询指标
查询日志
读取数据库运行状态
读取 Redis 状态
读取消息积压
检查容器状态
```

目标业务代码：

> 可以一行都不改。

---

## 2. OpsPilot 的接入分成 4 个等级

不是所有系统接入方式都一样。

正式定义四级。

---

### L0：零代码接入

这是 OpsPilot 最优先的接入方式。

目标系统已经拥有：

```text
Prometheus / Metrics
Loki / 日志
MySQL
Redis
Docker
```

OpsPilot 只需要配置：

```text
ManagedSystem

ManagedResource

DataSourceConnection

ResourceBinding

CapabilityBinding

RecoveryPolicy
```

例如：

```text
业务系统：
shortlink-platform
```

内部资源：

```text
redirect-service
statistics-consumer
shortlink-redis
shortlink-mysql
statistics-stream
```

再告诉 OpsPilot：

```text
shortlink-redis
通过 redis-local 访问

statistics-consumer
通过 docker-local 管理

redirect-service
指标在 prometheus-local
```

即可。

此时：

> **目标业务系统完全不用新增 OpsPilot 代码。**

---

## 3. L1：标准可观测性增强

有些目标系统虽然正常运行，

但没有：

```text
HTTP P99
错误率
HikariCP 连接池
JVM
业务请求量
```

这些指标。

例如一个 Spring Boot 项目没有启用：

```text
Actuator
Micrometer
Prometheus Registry
```

那么为了 S1 / S2，

可能需要在目标项目增加：

```text
Spring Boot Actuator
Micrometer
Prometheus endpoint
```

或者：

```text
OpenTelemetry
```

这种改动属于：

> **通用可观测性建设。**

而不是：

> “为 OpsPilot 写业务接口”。

即使以后不用 OpsPilot：

这些监控依然有价值。

因此这是允许的。

---

## 4. L2：独立 Ops Adapter

还有一种系统：

它的重要运行信息没有标准接口。

例如：

```text
某个内部任务系统
```

只有它自己知道：

```text
当前任务状态
业务积压
处理队列
最近成功时间
```

这时优先考虑：

> 在目标系统旁边建设一个独立只读 Adapter。

例如：

```text
ShortLink
        │
        │ existing API / DB / Metrics
        ▼
Java Ops Adapter
        │
        ▼
OpsPilot
```

Adapter 可以：

- 调目标系统已有管理接口；
- 使用只读账号；
- 查询标准监控；
- 将复杂数据整理成稳定的运维语义。

它不应该：

> 成为 Agent。

也不应该：

> 拥有任意写权限。

---

## 5. L3：必须修改目标系统

只有当目标系统：

```text
没有指标

没有日志访问

没有管理接口

没有只读数据库

没有外部运行状态

也没有任何可观察入口
```

OpsPilot 才无法凭空知道：

> 系统里面发生了什么。

此时确实可能需要：

- 增加指标；
- 增加 Health Endpoint；
- 增加业务运行状态接口；
- 增加 Trace；
- 增加结构化日志。

这是客观限制。

---

## 6. 因此接入原则正式冻结

优先级：

```text
已有标准接口
    ↓
直接接入

已有基础设施但缺少部分可观测性
    ↓
增强通用 Observability

已有信息但没有统一接口
    ↓
独立 Ops Adapter

完全无法观察
    ↓
最后才修改业务系统
```

正式原则：

> **OpsPilot 不要求业务系统依赖 OpsPilot SDK。**

---

## 7. 写操作同样不应该往业务系统塞 OpsPilot Endpoint

例如不要为了 OpsPilot：

在 ShortLink 中新增：

```http
POST /internal/opspilot/restart-me
```

这种接口。

因为：

> 服务重启属于运行环境职责。

更合理：

```text
OpsPilot
↓
Docker
↓
restart container
```

未来：

```text
OpsPilot
↓
Kubernetes
↓
restart workload
```

业务应用根本不需要知道：

> OpsPilot 正在重启我。

---

## 8. ShortLink V0.1 预计接入方式

第一版目标结构建议如下：

| ManagedResource | 数据来源 | Capability | 是否需要改业务代码 |
|---|---|---|---|
| `redirect-service` | Prometheus | `metrics.query` | 可能只需开启 Micrometer |
| `redirect-service` | Loki | `logs.search` | 通常不需要 |
| `shortlink-redis` | Redis | `cache.inspect` | 不需要 |
| `shortlink-mysql` | MySQL 只读账号 | `database.inspect` | 不需要 |
| `statistics-stream` | Redis Stream | `queue.inspect` | 不需要 |
| `statistics-consumer` | Docker | `service.inspect` | 不需要 |
| `statistics-consumer` | Loki | `logs.search` | 不需要 |
| `statistics-consumer` | Docker | `service.restart` | 不需要 |

如果 ShortLink 当前没有：

```text
HTTP P99
HikariCP Metrics
```

则增加：

```text
Actuator + Micrometer
```

属于 L1。

---

## 9. Capability 的本质

Capability 中文：

> **OpsPilot 被允许对一个 ManagedResource 做的一种明确操作。**

Capability 不是：

```text
一段 Prompt
```

不是：

```text
一个 Shell 命令
```

也不是：

```text
Agent Tool = 任意函数
```

它是：

> Java 主服务中拥有正式输入、输出、安全规则和执行约束的系统能力。

---

## 10. V0.1 正式 Capability

只冻结 7 个：

```text
metrics.query

logs.search

cache.inspect

database.inspect

queue.inspect

service.inspect

service.restart
```

其中：

### OBSERVE

只读能力：

```text
metrics.query
logs.search
cache.inspect
database.inspect
queue.inspect
service.inspect
```

---

### CHANGE

写能力：

```text
service.restart
```

---

## 11. CapabilityMode

Java Registry 中每个 Capability 必须明确：

```text
OBSERVE
CHANGE
```

不能运行时由 AI 判断：

> “这应该算危险还是安全。”

---

## 12. CHANGE 能力必须人工审批

V0.1：

```text
CapabilityMode = CHANGE
```

必然：

```text
requiresApproval = true
```

不存在：

```text
AI说风险很低
↓
跳过审批
```

这种路径。

---

## 13. RiskLevel 不能由 AI 决定

AI Remediation 只返回允许的动作、目标、强类型参数、说明和预计影响。
`riskLevel` 与 `requiresApproval` 不属于 AI 输出合同；未知或越权字段在 Schema 验证时拒绝。
Java Registry 为 service.restart 产生 MEDIUM 和 requiresApproval=true，并持久化 RemediationAction。

---

## 14. Capability Registry

V0.1 不建立：

```text
capability_definition
```

数据库表。

定义存在于 Java：

```text
CapabilityRegistry
```

逻辑上每项包含：

```text
key

mode

supportedResourceTypes

requestSchema

resultSchema

supportedProviderTypes

defaultTimeout

requiresApproval

defaultRiskLevel
```

---

## 15. CapabilityBinding

数据库里的：

```text
capability_binding
```

只回答：

> 某个具体资源允不允许使用某个 Capability。

例如：

```text
statistics-consumer

service.inspect
enabled = true

service.restart
enabled = true
```

AI不能通过：

> 请求一个 Registry 中存在但 Resource 没绑定的能力

绕过配置。

---

## 16. 一个 Capability 真正执行前至少经过 6 层

以：

```text
cache.inspect
```

为例。

AI：

```text
我想检查 shortlink-redis
```

Java：

```text
① Capability Registry 存不存在？

② 当前阶段允许 OBSERVE 吗？

③ Resource 是否存在、是否 ACTIVE？

④ ResourceType 是否支持？

⑤ CapabilityBinding 是否 enabled？

⑥ 是否存在唯一合法 Provider Binding？
```

全部成功：

> 才执行。

---

## 17. Provider 如何选择

例如：

```text
cache.inspect
```

Registry 声明：

```text
supportedProviderTypes:
REDIS
```

目标 Resource：

```text
shortlink-redis
```

ResourceBinding：

```text
shortlink-redis
→
redis-local
```

DataSourceConnection：

```text
providerType = REDIS
```

最终：

```text
RedisCacheInspectProvider
```

被选择。

---

## 18. V0.1 不做复杂 Provider 路由

对于：

```text
一个 Resource
+
一个 Capability
```

V0.1 要求：

> 必须能够确定唯一一个 ACTIVE Provider Binding。

如果：

```text
0 个
```

返回：

```text
CAPABILITY_PROVIDER_NOT_CONFIGURED
```

如果：

```text
2 个 Prometheus 都能执行
```

返回：

```text
CAPABILITY_PROVIDER_AMBIGUOUS
```

绝不能：

> 随便挑一个。

以后再做：

```text
primary / fallback
```

不属于 V0.1。

---

## 19. ResourceBinding 是接入系统的核心

它回答：

> “在外部系统中怎样找到这个 Resource？”

例如 Prometheus：

```json
{
  "labels": {
    "application": "shortlink-project"
  }
}
```

Loki：

```json
{
  "labels": {
    "app": "shortlink-project"
  }
}
```

Redis Stream：

```json
{
  "streamKey": "shortlink:stats",
  "consumerGroup": "stats-consumer-group"
}
```

Docker：

```json
{
  "containerName": "shortlink-statistics-consumer"
}
```

这些：

> 由配置提供。

不是 AI 自己生成。

---

## 20. Credential 永远不进入 Capability Request

例如 AI 请求：

```text
database.inspect
shortlink-mysql
```

AI永远看不到：

```text
jdbc:mysql://...
username
password
```

Java通过：

```text
ResourceBinding
↓
DataSourceConnection
↓
credential_ref
```

自己解析凭证。

---

## 21. AI Request 不能使用 Map<String,Object>

内部 AgentStep 中的：

```text
REQUEST_CAPABILITY
```

需要增加：

```text
arguments
```

但这不是万能 JSON Map。

Java逻辑类型应该类似：

```text
CapabilityArguments
```

下面拥有明确子类型：

```text
MetricsQueryArgumentsV1

LogsSearchArgumentsV1

DatabaseInspectArgumentsV1

CacheInspectArgumentsV1

QueueInspectArgumentsV1

ServiceInspectArgumentsV1
```

Python 使用：

> Pydantic 判别联合类型。

Java 使用：

> 明确的强类型 DTO / sealed hierarchy。

JSON 只是传输格式。

---

## 22. AI 看到的 Capability 描述也必须类型化

availableCapabilities 为有 discriminator 的强类型 Descriptor，至少包含 key、descriptorType、resourceId、resourceKey 和对应受控参数域。
| key | descriptorType | 可供模型选择的字段 |
|---|---|---|
| metrics.query | METRICS_QUERY | metricKeys、windowKeys、supportsPreviousWindowComparison |
| logs.search | LOGS_SEARCH | windowKeys、severities、maxKeywords=5、maxKeywordLength=64 |
| database.inspect | DATABASE_INSPECT | inspectionTypes、limitMin=1、limitMax=20 |
| cache.inspect | CACHE_INSPECT | arguments 为显式空对象 |
| queue.inspect | QUEUE_INSPECT | arguments 为显式空对象 |
| service.inspect | SERVICE_INSPECT | arguments 为显式空对象 |

Descriptor 从受信配置构造；未绑定、禁用、归属错误或 Provider 不唯一的能力不进入可执行空间。
AI 选择不在 Descriptor 中的值，Java 仍拒绝 CAPABILITY_ARGUMENT_INVALID。
service.restart 不属于调查 Descriptor 联合，只在独立 Remediation allowedActions 出现。

---

## 23. 禁止 AI 直接写 PromQL

正式禁止：

```json
{
  "promql": "..."
}
```

来自 AI。

原因不是模型不会写 PromQL。

而是这会导致：

```text
查询范围不可控
高基数查询
极大时间窗
复杂正则
查询错误
数据泄露
Provider 强耦合
```

Agent 应该请求：

```text
http.request.latency.p99
```

Prometheus Provider 再把它翻译成真正 PromQL。

---

## 24. 禁止 AI 直接写 LogQL

同理禁止：

```text
{app="xxx"} |= "password" ...
```

Agent只能使用：

```text
window
severity
keywords
```

这种受限参数。

---

## 25. 禁止 AI 直接写 SQL

`database.inspect`

不是：

```text
executeSql(sql)
```

即便账号只读：

也不允许 AI 生成任意：

```sql
SELECT ...
```

原因包括：

- 大表全扫；
- 敏感数据；
- 长事务；
- 锁影响；
- SQL 注入式 Prompt；
- 难以审计。

---

## 26. 禁止 AI 提供 Shell

`service.restart`

请求中不存在：

```text
command
shell
containerName
host
```

AI只允许指定：

```text
ManagedResource
```

例如：

```text
statistics-consumer
```

真正 Docker Container：

由：

```text
ResourceBinding
```

解析。

---

## 27. Capability 的两种执行上下文

OBSERVE 能力可能由两个地方调用。

---

### Investigation

```text
Incident = INVESTIGATING
```

AI请求：

```text
REQUEST_CAPABILITY
```

产生：

```text
CapabilityInvocation
investigation_id != null
```

并消耗：

```text
Investigation Capability Budget
```

---

### RecoveryVerification

```text
Incident = VERIFYING
```

由：

```text
RecoveryPolicy Engine
```

确定性调用。

产生：

```text
CapabilityInvocation
recovery_verification_id != null
```

不消耗：

```text
Investigation Capability Budget
```

AI 不参与。

调查调用须绑定当前 run_no 并使用当前 run 预算；恢复调用绑定 Verification、criterion_key、sample_index。
恢复采样不受调查 Duplicate Guard 的30秒窗口约束，否则5秒／10秒正式采样计划无法执行。
两种上下文都必须通过各自生命周期、授权及类型检查。

---

## 28. CHANGE 能力使用另一条执行路径

`service.restart`

不会创建：

```text
CapabilityInvocation
```

因为数据库已经有：

```text
RemediationAction

ApprovalRequest

ActionExecution
```

保存写操作全链路。

因此：

```text
OBSERVE
→ CapabilityInvocation

CHANGE
→ ActionExecution
```

不要重复记两份执行事实。

---

## 29. OBSERVE 成功不一定只产生一个 Observation

一个 CapabilityInvocation：

```text
1
```

可以产生：

```text
N 个 Observation
```

例如：

`logs.search`

可能识别：

```text
Redis timeout × 147

Connection refused × 32

Request cancelled × 9
```

可以形成 3 条 Observation。

---

## 30. Observation Extraction 必须确定性

Provider：

```text
返回真实数据
```

然后 Java：

```text
ObservationExtractor
```

根据 Capability Result：

> 产生正式 Observation。

不允许：

```text
Raw Result
↓
LLM
↓
“我总结它是 Observation”
```

再落库。

否则：

> 事实层已经被模型加工。

---

## 31. Sanitizer 在 Observation 之前

正式数据流：

```text
Provider
↓
Raw Provider Result
↓
Sanitizer
↓
Typed Result
↓
ObservationExtractor
↓
Observation
↓
AI Context
```

因此：

> Secret 在进入数据库和模型以前就应该被清理。

---

## 32. 原始结果也必须是脱敏后的

`raw_result_ref`

不应成为：

> “藏着未脱敏 Secret 的后门”。

V0.1：

```text
raw_result_ref
```

只允许指向：

> 已经经过基础 Sanitizer 的原始结果。

不保存：

```text
未脱敏原始 Authorization
密码
Token
完整 Cookie
```

---

## 33. Capability 失败规则

Provider 连接失败、超时、授权失败或返回不合法，Invocation FAILED，保存脱敏错误，不伪造业务 Observation。
调查仍可在当前run预算和deadline允许时选择其他方向。
在恢复中，这次采样为 UNKNOWN；只有其他 required 明确 FALSE 才由合取矩阵判整体 FAILED，
否则不能把观测渠道失败等同于业务未恢复。

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

## 34. V0.1 Provider 不做自动业务重试

例如 Prometheus timeout。

第一版不做：

```text
Java内部自动重试 5 次
```

导致：

> 一次 CapabilityInvocation 到底执行了几次

变得不透明。

V0.1：

> 一次 Invocation 对应一次 Provider 调用。

失败以后 Agent 可以：

- 换能力；
- 稍后重新请求；
- 最终 UNDETERMINED。

RecoveryPolicy 的多次采样：

> 显式产生多次 Invocation。

执行结果核对属于独立的显式只读重试协议，次数和deadline持久化；这是明确允许的例外，不是隐藏 Provider retry。
见本文件 §110。普通 Investigation／Recovery Invocation 仍各对应一次 Provider 调用；
丢失样本不在同一 sampleIndex 上悄悄重发。

---

## 35. 统一 Provider 错误类型

建议冻结：

```text
CONNECTION_FAILED

TIMEOUT

AUTHENTICATION_FAILED

AUTHORIZATION_DENIED

RESOURCE_NOT_FOUND

INVALID_BINDING

QUERY_REJECTED

PROVIDER_RESPONSE_INVALID

RESULT_TOO_LARGE

PROVIDER_UNAVAILABLE
```

CHANGE 能力额外：

```text
EXECUTION_RESULT_UNCERTAIN
```

---

## 36. Capability 1：metrics.query

中文：

> **查询一个已经预先定义好的系统指标。**

---

## 37. metrics.query 基本属性

```text
key:
metrics.query

mode:
OBSERVE

Provider:
PROMETHEUS
```

允许 ResourceType：

```text
SERVICE
CONSUMER
DATABASE
CACHE
MESSAGE_QUEUE
EXTERNAL_DEPENDENCY
```

前提：

> Resource 真正拥有对应 metric binding。

---

## 38. metrics.query 不等于 PromQL Tool

Agent请求：

```text
http.request.latency.p99
```

Provider内部：

```text
metricKey
↓
MetricBinding
↓
PromQL Template
↓
Prometheus
```

AI完全不需要看到 PromQL。

---

## 39. MetricKey

MetricKey 是：

> 稳定语义指标名称。

例如 ShortLink V0.1：

```text
http.request.latency.p99

http.request.error_rate

http.request.rate

jvm.cpu.usage

jvm.memory.heap.usage

db.pool.active

db.pool.pending

db.pool.max

mysql.query.rate

redis.command.latency.p95
```

注意：

这不是要求每个系统全部拥有。

每个 Resource：

> 只向 AI 暴露它实际支持的 MetricKey。

---

## 40. MetricBinding 放在哪里

V0.1 不新增数据库表。

可以作为：

```text
PrometheusResourceBindingV1
```

的一部分。

逻辑示例：

```json
{
  "labels": {
    "application": "shortlink-project"
  },
  "metrics": {
    "http.request.latency.p99": {
      "queryTemplate": "trusted-template-or-config",
      "unit": "ms"
    }
  }
}
```

这是：

> 运维受信配置。

不是 AI 输入。

S1/S2 的 HTTP P99 需要明确直方图／百分位数发布配置、指标标签、Prometheus查询模板及秒到毫秒转换。
本包不以“已加入Actuator”替代可查询P99证明；实际导出指标名与模板在 TASK-052／106 固定并验证。

---

## 41. metrics.query Request

AI只允许：

```text
metricKey
windowKey
comparePreviousWindow
```

示例：

```json
{
  "metricKey": "http.request.latency.p99",
  "windowKey": "INCIDENT_CONTEXT",
  "comparePreviousWindow": true
}
```

---

## 42. windowKey

允许 INCIDENT_CONTEXT、LAST_15_MIN、LAST_30_MIN、LAST_60_MIN。
INCIDENT_CONTEXT 由 Java 从 Incident 时间和当前时间解析并限制查询范围；
如最初故障已久，选择当前允许范围，不让无限历史进入模型。

comparePreviousWindow=true 时，previous 与 current 必须等长、紧邻：
`previous.end = current.start`，且 `previous.start -> current.end <= 60min`。
LAST_60_MIN + comparison 一律准入前拒绝 METRIC_COMPARISON_WINDOW_EXCEEDS_LIMIT；
LAST_30_MIN + comparison 为60分钟总范围，LAST_15_MIN + comparison为30分钟。
不得静默裁短其中一边，导致非等长比较。INCIDENT_CONTEXT 先解析再按同一规则判断。

changePercent／trend 由 Java 根据真实样本计算；没有前窗口有效数据则相应字段未知，
不能把缺失前窗当0，也不能在没有基线时写出“较正常上升”的确定性 Observation。

---

## 43. metrics.query Result

逻辑结构：

```json
{
  "metricKey": "http.request.latency.p99",
  "unit": "ms",
  "window": {
    "start": "...",
    "end": "..."
  },
  "sampleCount": 34,
  "latest": 1640,
  "min": 78,
  "max": 1812,
  "average": 1301,
  "previousWindow": {
    "average": 84
  },
  "changePercent": 1448.8,
  "trend": "INCREASING"
}
```

Trend：

```text
INCREASING
STABLE
DECREASING
UNKNOWN
```

由 Java / Provider 确定。

不是 LLM 判断。

---

## 44. metrics.query Observation

成功后：

通常生成 1 条：

```text
Observation.kind = METRIC
```

例如：

```text
短链接跳转 P99 在故障窗口平均 1301ms，
前一窗口平均 84ms，
当前约增加 14.5 倍。
```

payload 保存结构化值。

---

## 45. metrics.query 给 AI 什么

发送：

```text
metricKey
unit
window
latest
average
previousAverage
changePercent
trend
```

默认不发送：

> 上千个时序采样点。

完整点序列：

可以保留在：

```text
response_payload
raw_result_ref
```

技术详情中查看。

---

## 46. metrics.query 超时

默认：

```text
10s
```

可配置。

超时：

```text
CapabilityInvocation = FAILED

error_code = TIMEOUT
```

---

## 47. Capability 2：logs.search

中文：

> **在目标 Resource 的日志中搜索受控范围内的异常模式。**

---

## 48. logs.search 基本属性

```text
key:
logs.search

mode:
OBSERVE

Provider:
LOKI
```

V0.1 ResourceType：

```text
SERVICE
CONSUMER
```

---

## 49. Agent 不能提交 LogQL

Request 只允许：

```text
windowKey

severity

keywords
```

例如：

```json
{
  "windowKey": "INCIDENT_CONTEXT",
  "severity": [
    "ERROR",
    "WARN"
  ],
  "keywords": [
    "redis",
    "timeout"
  ]
}
```

---

## 50. keywords 约束

```text
最多 5 个
```

每个：

```text
最长 64 字符
```

只允许：

> 普通文本匹配。

V0.1 不允许 AI：

```text
正则表达式
LogQL
Pipeline
JSON Path
```

---

## 51. 日志查询结果不能把 5000 行直接丢给模型

Provider 可以获取很多日志。

但 Result 必须整理成：

```text
Pattern Summary
```

例如：

```json
{
  "totalMatches": 188,
  "truncated": false,
  "patterns": [
    {
      "pattern": "Redis command timeout",
      "count": 147,
      "firstSeen": "...",
      "lastSeen": "...",
      "severity": "ERROR",
      "samples": [
        "Redis command timed out after [REDACTED]"
      ]
    }
  ]
}
```

---

## 52. Pattern 产生方式

V0.1 不用 LLM 做日志聚类。

Java可以使用简单确定性归一化：

```text
UUID → <ID>

数字 ID → <NUM>

IP → <IP>

时间 → <TIME>

长十六进制串 → <HEX>
```

再按模板聚合。

不要求第一版做复杂日志异常检测算法。

---

## 53. logs.search 数量限制

默认：

```text
Provider 原始匹配上限：
500

进入 Result 的 Pattern：
最多 10

进入 AI Context：
最多 5

每个 Pattern Sample：
最多 2
```

全部可配置。

---

## 54. 日志 Sanitizer

进入 Observation 前必须清理：

```text
Authorization

Bearer Token

Cookie

Set-Cookie

password

secret

api_key

access_token

refresh_token

JDBC 密码

邮箱等可识别个人信息视配置脱敏
```

最少：

```text
Credential / Token
```

必须强制清理。

---

## 55. logs.search Observation

通常：

> 一个重要日志模式形成一条 `LOG_PATTERN Observation`。

例如：

```text
O-015

过去 10 分钟出现 147 次 Redis command timeout。

firstSeen = 14:02
lastSeen = 14:11
```

这样 Evidence 可以精确引用：

> 哪个日志模式支持哪个 Hypothesis。

---

## 56. logs.search 给 AI 什么

AI可以看：

```text
Pattern
Count
FirstSeen
LastSeen
Severity
少量脱敏 Sample
```

AI看不到：

> 全部 5000 行日志。

---

## 57. logs.search 超时

默认：

```text
15s
```

---

## 58. Capability 3：cache.inspect

中文：

> **检查缓存系统运行状态，而不是查看业务缓存内容。**

这是非常重要的边界。

---

## 59. cache.inspect 基本属性

```text
key:
cache.inspect

mode:
OBSERVE

ResourceType:
CACHE

Provider:
REDIS
```

---

## 60. cache.inspect 不允许什么

明确禁止：

```text
GET arbitrary-key

SCAN *

KEYS *

HGETALL business-data

DEL

SET

FLUSHDB

CONFIG SET
```

OpsPilot 不需要：

> 读取用户缓存数据

才能判断：

> Redis 是否异常。

---

## 61. cache.inspect Request

V0.1：

> 无 AI 可控参数。

逻辑：

```json
{}
```

Agent只能说：

```text
检查 shortlink-redis
```

不能说：

> 给我 Redis 中 key xxx 的 value。

---

## 62. cache.inspect Result

推荐：

```json
{
  "reachable": true,

  "pingLatencyMs": 623,

  "usedMemoryBytes": 913428480,
  "maxMemoryBytes": 2147483648,

  "connectedClients": 43,
  "blockedClients": 8,

  "instantOpsPerSec": 1850,

  "keyspaceHits": 18374231,
  "keyspaceMisses": 2811021,
  "hitRate": 0.867,

  "evictedKeys": 12431,
  "expiredKeys": 92811,

  "uptimeSeconds": 73124
}
```

---

## 63. Redis ACL

用于：

```text
cache.inspect
```

的凭证应该只允许必要只读管理命令。

例如：

```text
PING
INFO
```

以及经过审查的诊断命令。

不允许：

```text
SET
DEL
FLUSHALL
CONFIG SET
```

安全：

> 靠 Redis ACL。

不是靠 Prompt。

---

## 64. Redis Slowlog

V0.1 如使用：

```text
SLOWLOG GET
```

只允许：

> Provider 内部受限读取。

并必须在持久化前：

- 截断参数；
- 对可能含业务值的参数脱敏。

第一版如果没必要：

> 可以先不启用。

---

## 65. cache.inspect Observation

一次调用通常生成：

```text
1 条 CACHE_STATUS Observation
```

例如：

```text
Redis 可达，但 ping latency 为 623ms，
同时存在 8 个 blocked clients。
```

---

## 66. cache.inspect 给 AI 什么

允许：

```text
延迟
内存
连接数
blockedClients
ops
hitRate
eviction
```

禁止：

```text
key name
value
业务数据
```

---

## 67. cache.inspect 超时

默认：

```text
5s
```

---

## 68. Capability 4：database.inspect

中文：

> **检查数据库运行状态与性能摘要。**

它不是：

> SQL Tool。

---

## 69. database.inspect 基本属性

```text
key:
database.inspect

mode:
OBSERVE

ResourceType:
DATABASE

Provider:
MYSQL
```

---

## 70. V0.1 InspectionType

冻结：

```text
SERVER_SUMMARY

CONNECTION_SUMMARY

SLOW_QUERIES

LOCK_WAITS
```

不做：

```text
ARBITRARY_SQL
```

V0.1 暂不做：

```text
EXPLAIN arbitrary SQL
```

后续有必要再加。

---

## 71. database.inspect Request

例如：

```json
{
  "inspectionType": "SLOW_QUERIES",
  "limit": 10
}
```

或者：

```json
{
  "inspectionType": "LOCK_WAITS"
}
```

---

## 72. limit 约束

```text
1 ～ 20
```

超过：

Java拒绝。

---

## 73. MySQL 账号必须只读

数据库调查账号：

不拥有：

```text
INSERT
UPDATE
DELETE
DROP
ALTER
CREATE
TRUNCATE
```

主要访问：

```text
performance_schema
information_schema
SHOW STATUS
```

具体最小权限在实现阶段确定。

---

## 74. SERVER_SUMMARY

返回类似：

```text
threadsConnected
threadsRunning
questions
slowQueries
uptime
bufferPoolUsage
```

不用读取业务表。

---

## 75. CONNECTION_SUMMARY

返回：

```text
当前连接数
运行连接数
等待状态分布
最长运行连接
```

注意：

> HikariCP 是应用连接池。

不是 MySQL Server 自身连接池。

所以：

```text
db.pool.active
db.pool.pending
db.pool.max
```

应该来自：

```text
metrics.query
```

而不是让 database.inspect 假装知道。

这能避免 S2 领域概念混乱。

---

## 76. SLOW_QUERIES

优先读取：

```text
performance_schema
events_statements_summary_by_digest
```

返回：

```json
{
  "queries": [
    {
      "digest": "abc123",
      "normalizedSql": "SELECT ... WHERE id = ?",
      "executionCount": 4132,
      "averageLatencyMs": 6210,
      "maxLatencyMs": 8301,
      "averageRowsExamined": 186231,
      "lastSeen": "..."
    }
  ]
}
```

---

## 77. SQL 必须 Normalized

尽量返回：

```text
SELECT * FROM t WHERE user_id = ?
```

而不是：

```text
SELECT * FROM t WHERE user_id = '真实用户ID'
```

避免把业务数据带入：

```text
Observation
AI Context
日志
```

---

## 78. LOCK_WAITS

返回：

```text
等待数量
最长等待时间
阻塞关系摘要
```

V0.1 不要求：

> 自动 kill transaction。

---

## 79. database.inspect Observation

SLOW_QUERIES 可以生成一条汇总和前 N 条真实慢语句 Observation，必须来自 Provider 返回的规范化统计。
若只获得当前 averageLatencyMs、maxLatencyMs、executionCount，就只描述这些当前值；
没有真实基线或前后样本不得生成“从正常几十毫秒升高”的比较。
诊断可推断因果，但不能让确定性 ObservationExtractor 写出未测事实。

---

## 80. database.inspect 给 AI 什么

允许：

```text
Normalized SQL
Digest
耗时
次数
Rows Examined
连接状态
锁等待摘要
```

禁止：

```text
业务查询结果
表中真实用户数据
密码
原始字面量
```

---

## 81. database.inspect 超时

默认：

```text
10s
```

---

## 82. Capability 5：queue.inspect

中文：

> **检查消息系统/消息队列积压和消费者状态摘要。**

V0.1 首个 Provider：

```text
Redis Stream
```

---

## 83. queue.inspect 基本属性

```text
key:
queue.inspect

mode:
OBSERVE

ResourceType:
MESSAGE_QUEUE

Provider:
REDIS
```

---

## 84. Stream Key 不由 AI 提供

ResourceBinding：

```json
{
  "streamKey": "shortlink:stats",
  "consumerGroup": "stats-consumer-group"
}
```

AI只能：

> 请求检查 `statistics-stream`。

不能：

```text
自己写 stream key
```

防止扫描任意 Redis 数据。

---

## 85. queue.inspect Request

V0.1：

```json
{}
```

无需 AI 参数。

---

## 86. queue.inspect Result

```json
{
  "queueType": "REDIS_STREAM",
  "streamLength": 2400,
  "lastGeneratedId": "1789992000000-0",
  "lastGeneratedAt": "2026-09-21T12:00:00.000Z",
  "consumerGroups": [
    {
      "group": "stats-consumer-group",
      "consumerCount": 1,
      "pendingCount": 4,
      "lag": 2180,
      "lastDeliveredId": "1789991999000-0",
      "lastDeliveredAt": "2026-09-21T11:59:59.000Z"
    }
  ]
}
```
示例为说明值，不是实测。Provider 只返回 Binding 指定组需要的统计，不扫描无关业务组。
lag 表示尚未投递；pendingCount 表示已投递未ACK；streamLength 为保留Entry数。
lag 不可可靠取得时为 null，不得估算成0。consumerCount不等于当前真实运行进程数。
lastDeliveredAt是投递时间，不是ACK时间，不能改名为lastAckAt。

---

## 87. queue.inspect 不读取消息正文

正式禁止：

```text
XRANGE 读取业务 payload
```

作为默认调查行为。

OpsPilot 只看：

```text
长度
积压
消费者组
Pending
时间
```

足以完成 S3。

---

## 88. Producer 是否仍在生产怎么判断

通过两个以上真实采样中的 lastGeneratedId 持续前进，或真实业务生产指标，判断 Producer 在观测窗口继续生产。
单个“最近时间戳”只能作为有限背景信息；pending 增长不能单独证明持续生产。
消费者停止后，尚未投递的 lag 通常是主要增长指标；pending 在没有新投递时可能不增长。

单次 queue.inspect 只返回本次统计，不硬编码趋势。需要趋势时收集多次真实调用，
由 Recovery Policy 确定性判断，或由 AI 在调查中解释真实时间序列。

---

## 89. queue.inspect Observation

通常产生一条 QUEUE_STATUS Observation，包含所选组的 lag、pendingCount、streamLength及真实采样时间。
示例：“目标组尚未投递积压2180，已投递未确认4；本次采样的最近生成消息时间为……。”
只有确实取得多个时点才能描述“持续增长”，不能把单个数值自动改写成趋势。

---

## 90. queue.inspect 给 AI 什么

可以：

```text
长度
Pending
Lag
消费者数量
最后消息时间
最后投递时间
```

禁止：

```text
Message Payload
用户业务数据
```

---

## 91. queue.inspect 超时

默认：

```text
5s
```

---

## 92. Capability 6：service.inspect

中文：

> **检查某个服务实例当前运行状态。**

V0.1 Provider：

```text
Docker
```

---

## 93. service.inspect 基本属性

```text
key:
service.inspect

mode:
OBSERVE

ResourceType:
SERVICE
CONSUMER

Provider:
DOCKER
```

---

## 94. Container 不由 AI 指定

ResourceBinding：

```json
{
  "containerName": "shortlink-statistics-consumer"
}
```

AI请求：

```text
service.inspect
resource = statistics-consumer
```

Java自己解析：

```text
containerName
```

---

## 95. service.inspect Request

V0.1：

```json
{}
```

---

## 96. service.inspect Result

建议：

```json
{
  "runtimeState": "RUNNING",

  "healthStatus": "HEALTHY",

  "startedAt": "...",

  "restartCount": 1,

  "image": "shortlink-consumer:demo",

  "exitCode": null,

  "finishedAt": null
}
```

`runtimeState`：

```text
RUNNING
STOPPED
RESTARTING
PAUSED
UNKNOWN
```

`healthStatus`：

```text
HEALTHY
UNHEALTHY
STARTING
NOT_CONFIGURED
UNKNOWN
```

---

## 97. service.inspect 明确不能返回什么

不能返回 Docker：

```text
Environment
```

因为其中非常可能包含：

```text
MYSQL_PASSWORD
REDIS_PASSWORD
JWT_SECRET
API_KEY
```

也不发送完整：

```text
Mount
Network
Labels
```

除非某字段被明确加入安全白名单。

---

## 98. service.inspect Observation

生成：

```text
SERVICE_STATUS
```

例如：

```text
Statistics Consumer 当前状态为 STOPPED，
exitCode = 1，
最后停止时间为 14:03。
```

这就是 S3：

> 最重要的真实证据之一。

---

## 99. service.inspect 给 AI 什么

只发送：

```text
运行状态
Health
启动时间
结束时间
退出码
重启次数
```

---

## 100. service.inspect 超时

默认：

```text
5s
```

---

## 101. Capability 7：service.restart

中文：

> **重启一个明确允许被 OpsPilot 操作的 ManagedResource。**

这是 V0.1：

### 唯一 CHANGE Capability。

---

## 102. service.restart 基本属性

```text
key:
service.restart

mode:
CHANGE

Provider:
DOCKER

ResourceType:
SERVICE
CONSUMER

requiresApproval:
true

riskLevel:
MEDIUM
```

但 V0.1 实际：

> 只给 `statistics-consumer` 绑定这个能力。

不要因为 Capability 支持 SERVICE：

就给所有服务加 restart。

---

## 103. service.restart 绝不会出现在 Investigation availableCapabilities

当：

```text
Incident = INVESTIGATING
```

AI只能看到：

```text
OBSERVE
```

能力。

因此调查 Agent 不可能输出：

```text
REQUEST_CAPABILITY
service.restart
```

因为：

> Java根本不把它放进允许列表。

即使模型强行输出：

返回：

```text
AI_INTENT_NOT_ALLOWED
```

---

## 104. service.restart 只在 Remediation 阶段出现

Java调用：

```text
/internal/v1/remediation/draft
```

时，

可以向 AI 提供：

```text
allowedActions
```

例如：

```json
[
  {
    "capabilityKey": "service.restart",
    "resourceId": 12,
    "resourceKey": "statistics-consumer",
    "resourceName": "Statistics Consumer"
  }
]
```

AI只能：

> 从列表中选择。

---

## 105. service.restart Request 不接受任意参数

V0.1：

```json
{}
```

正式禁止：

```text
containerName
host
command
signal
script
shell
```

由 AI 提供。

真正 Container：

```text
ManagedResource
↓
ResourceBinding
↓
Docker Provider
```

解析。

---

## 106. service.restart 的完整安全链

```text
Diagnosis
PRIMARY / POSSIBLE
        ↓
Remediation Draft
        ↓
AI 选择允许的 service.restart
        ↓
Java 校验
        ↓
RemediationPlan
        ↓
RemediationAction
        ↓
ApprovalRequest
        ↓
用户 APPROVED
        ↓
ActionExecution(PENDING)
        ↓
Java重新校验 Binding
        ↓
DockerServiceExecutor
        ↓
restart
```

任何一步失败：

> 都不能跨越。

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

## 107. 批准以后仍要重新检查 CapabilityBinding

假设等待审批期间：

管理员把：

```text
service.restart
enabled = false
```

用户此时点击批准。

Java必须重新检查：

```text
Binding enabled?
```

否则返回：

```text
REMEDIATION_ACTION_NOT_EXECUTABLE
```

不能因为：

> “申请审批时是允许的”

就永久获得写权限。

---

## 108. Action 参数批准后不可变化

进入：

```text
AWAITING_APPROVAL
```

以后：

```text
capabilityKey
targetResourceId
parameterPayload
```

全部冻结。

需要改变：

> 重新创建 Plan / Action / Approval。

---

## 109. service.restart Result

ActionExecution 可以保存：

```json
{
  "provider": "DOCKER",
  "restartRequestedAt": "...",
  "completedAt": "...",
  "stateAfterOperation": "RUNNING"
}
```

但产品必须继续写：

> “重启操作执行成功。”

不能写：

> “故障已经恢复。”

---

## 110. 结果不确定的有界只读 reconciliation

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

## 111. 为什么这里不增加 UNKNOWN Execution 状态

因为生命周期状态已经 Frozen。

V0.1 不为低概率 Demo 情况：

> 再扩整套状态机。

通过：

```text
FAILED + error_code
```

表达即可。

重点是：

> 不盲目重试。

---

## 112. service.restart 不产生 Observation

它产生：

```text
ActionExecution
TimelineEvent
```

真正用于判断恢复的事实：

在随后的恢复验证阶段：

```text
RecoveryVerification
```

通过：

```text
service.inspect
queue.inspect
metrics.query
```

产生。

因此：

> 写操作执行结果 ≠ Observation ≠ Recovery Evidence。

边界保持清晰。

---

## 113. RecoveryPolicy Criteria V1 完整协议

#### 采样身份、顺序与恢复

`recovery.policy.criteria / 1` 使用非空、有序 Criteria 数组；V0.1 逐项串行执行，不建设 DAG。
`criterionKey` 在一个快照内唯一。每个恢复 Invocation 保存
`recovery_verification_id + criterion_key + sample_index`（sampleIndex 从 1 开始），
并建立唯一约束；Observation 通过 capability_invocation_id 追溯样本身份。
一次调用可产生多个 Observation，但同一逻辑样本只能使用该唯一 Invocation 的确定性结果。

每个 Criterion 的 `arguments` 使用该 Capability 的强类型输入。字段选择使用注册的标量投影：
service.inspect 的 runtimeState／healthStatus；queue.inspect 中 Binding 指定 consumerGroup 的 lag／pendingCount；
metrics.query 的 latest 等。不得执行任意 JSONPath、表达式或默认取 consumerGroups 数组第一项。
快照保存配置选定的组；运行时组缺失或字段未知即 UNKNOWN。

第一次样本在该 Criterion 开始时准入；后续样本的最早准入时间为上一实际样本完成时间加 intervalSeconds。
存储实际 started_at／finished_at／observed_at，不把计划时间伪装为实测时间，不复制样本补空位。
采样等待不持有数据库事务。

恢复期限在 Verification 创建时冻结为 `deadline_at = created_at + maxDurationSeconds`。
样本的时间有效性按 Snapshot 中 `maxSampleAgeSeconds` 与必要的 `maxGapSeconds` 检查。
过期样本不能支持 TRUE 或 FALSE；时间不足、字段缺失及无有效样本都是 UNKNOWN。

重启读取快照、持久化样本槽位和调用状态：
- 未准入样本可在原 deadline 内继续；未到期就等待到最早准入时间。
- 已成功且仍有效的样本可以复用；失败／中断样本保持原记录，禁止在同一 sampleIndex 上隐藏重试。
- 遗留 RUNNING 调用标 FAILED/PROCESS_INTERRUPTED；剩余尚未准入样本可继续采集，以便发现其他明确 FALSE。
- 若前后间隔已超过原 maxGapSeconds，不补造连续性；该时间序列不能凭不连续数据宣布通过。
- 不重置 Verification deadline，不将 Investigation Observation 填入恢复样本。
最后统一按 required 三值矩阵结案；重新验证必须创建新 Verification，而不是覆盖旧结果。

本次定稿补齐的可配置 Demo 默认值：`maxDurationSeconds=120`、`maxSampleAgeSeconds=120`；
多样本 `maxGapSeconds=2*intervalSeconds`；单样本的 maxGapSeconds 为 null。
这些是实施默认值，不是本项目已经实测出的性能保证。



#### 受控趋势谓词

通用 `MONOTONIC_TREND` 没有健康区间参数时：
DECREASING 表示有效序列非增且至少一次严格下降；INCREASING 对称。
所需样本不齐且不能确定违反约束时为 UNKNOWN，不能仅凭一个点宣称有趋势。

S3 的同一谓词启用 `healthyThreshold` 与 `requireFinalHealthy=true`：
1. 所有样本必须为该 Consumer Group 的有效非负数；NULL 是 UNKNOWN。
2. 若首点已在 `[0, healthyThreshold]` 内，后续保持在区间内即 TRUE。
3. 若首点在区间外，要求本轮总体降低并进入健康区间；进入后后续样本不得重新超出区间。
   区间外阶段允许非单调噪声，不要求每一相邻点严格降低。
4. 完整采样仍未进入健康区间，或进入后反弹超界，结果 FALSE。
5. 最终 fresh sample 的 `lag <= healthyThreshold` 还由独立末次检查确认。

S3 示例（lag 健康上限 20）：
| 样本 | 结果 | 解释 |
|---|---|---|
| 0, 0, 0, 0 | TRUE | 已健康并保持 |
| 2, 0, 1, 0 | TRUE | 健康区间内波动 |
| 200, 210, 50, 10 | TRUE | 总体下降并进入健康区间 |
| 200, 150, 100, 50 | FALSE | 尚未达健康区间 |
| 50, 10, 30, 0 | FALSE | 入健康区后再次超界 |
| 200, null, 10, 0 | UNKNOWN | 缺有效必要样本，不能宣布通过 |

`FIELD_EQUALS`／`NUMERIC_COMPARE` 在多样本场景要求全部有效样本满足。
其中任何仍有效的样本明确违反“全部满足”可决定 FALSE；没有明确违反但采样缺失则 UNKNOWN。



#### S3 恢复合同

Policy 挂在 `statistics-consumer`；所有检查目标属于同一 `shortlink-platform`。
逻辑 A=consumer-running，B=stream-lag-decreasing，C=stream-lag-drained，D=stream-pending-healthy；
执行顺序冻结为 **B -> C -> D -> A**，把服务存活检查安排在最终判断附近。

| 执行序 | criterionKey | 能力／目标 | 采样 | required 判据 |
|---|---|---|---|---|
| 1 | stream-lag-decreasing | queue.inspect／statistics-stream | 4 次，间隔 10 秒 | 总体下降进入 lag 健康区间，进入后保持；已健康序列也可通过 |
| 2 | stream-lag-drained | queue.inspect／statistics-stream | 1 次新采样 | lag <= 20 |
| 3 | stream-pending-healthy | queue.inspect／statistics-stream | 2 次，间隔 5 秒 | pendingCount <= pendingHealthyThreshold |
| 4 | consumer-running | service.inspect／statistics-consumer | 2 次，间隔 5 秒 | runtimeState == RUNNING |

四项均 required。每一次显式采样建立独立 Invocation，恢复调用不经过调查 Duplicate Guard、不扣调查预算。
下方 Seed 示例把 `pendingHealthyThreshold` 明确设为 20，这是本次定稿补齐的 Demo 可配置默认值，
而非原资料已经实测的健康水平；必须在 Preflight 证明正常基线符合范围，并验证 ACK 发生在成功处理之后。
如靶场批量消费参数要求调整该阈值，在场景实施任务中记录校准依据并生成新的 Policy 版本；不扩大平台能力。

lag 为尚未投递积压，pending 为已投递未确认积压；streamLength 不是处理积压，
lastDeliveredAt 不是 lastAckAt。不能用 lag 降低代替成功处理，也不声称有限阈值证明零消息丢失。
#### 完整 Criteria 示例
```json
{
  "schemaName": "recovery.policy.criteria",
  "schemaVersion": 1,
  "maxDurationSeconds": 120,
  "maxSampleAgeSeconds": 120,
  "criteria": [
    {
      "criterionKey": "stream-lag-decreasing",
      "name": "未投递积压进入并保持健康区间",
      "capabilityKey": "queue.inspect",
      "targetResourceKey": "statistics-stream",
      "arguments": {},
      "sampling": {
        "sampleCount": 4,
        "intervalSeconds": 10,
        "maxGapSeconds": 20
      },
      "predicate": {
        "type": "MONOTONIC_TREND",
        "field": "lag",
        "direction": "DECREASING",
        "healthyThreshold": 20,
        "requireFinalHealthy": true
      },
      "required": true
    },
    {
      "criterionKey": "stream-lag-drained",
      "name": "末次未投递积压达标",
      "capabilityKey": "queue.inspect",
      "targetResourceKey": "statistics-stream",
      "arguments": {},
      "sampling": {
        "sampleCount": 1,
        "intervalSeconds": 0,
        "maxGapSeconds": null
      },
      "predicate": {
        "type": "NUMERIC_COMPARE",
        "field": "lag",
        "operator": "LTE",
        "value": 20
      },
      "required": true
    },
    {
      "criterionKey": "stream-pending-healthy",
      "name": "已投递未确认积压保持健康",
      "capabilityKey": "queue.inspect",
      "targetResourceKey": "statistics-stream",
      "arguments": {},
      "sampling": {
        "sampleCount": 2,
        "intervalSeconds": 5,
        "maxGapSeconds": 10
      },
      "predicate": {
        "type": "NUMERIC_COMPARE",
        "field": "pendingCount",
        "operator": "LTE",
        "value": 20
      },
      "required": true
    },
    {
      "criterionKey": "consumer-running",
      "name": "消费者持续运行",
      "capabilityKey": "service.inspect",
      "targetResourceKey": "statistics-consumer",
      "arguments": {},
      "sampling": {
        "sampleCount": 2,
        "intervalSeconds": 5,
        "maxGapSeconds": 10
      },
      "predicate": {
        "type": "FIELD_EQUALS",
        "field": "runtimeState",
        "value": "RUNNING"
      },
      "required": true
    }
  ]
}
```

---

## 114. RecoveryVerification 完全不问 AI

执行：

```text
RecoveryPolicy Snapshot
        ↓
RecoveryVerificationService
        ↓
Capability Registry
        ↓
Provider
        ↓
Observation
        ↓
Predicate Evaluator
```

最后：

```text
PASSED
FAILED
INCONCLUSIVE
```

全部确定性。

---

## 115. RecoveryPolicy 允许使用的能力

V0.1：

```text
metrics.query

cache.inspect

database.inspect

queue.inspect

service.inspect
```

理论上都可以。

但具体 Policy：

只使用自己需要的能力。

`logs.search`

不建议作为：

> “是否恢复”的主要确定性标准。

日志更适合调查。

---

## 116. 恢复中数据缺失与整体结果的三值矩阵

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

## 117. Capability 与 Observation Schema 对照

| Capability | Observation Kind | Schema |
|---|---|---|
| `metrics.query` | `METRIC` | `metric.observation / v1` |
| `logs.search` | `LOG_PATTERN` | `log-pattern.observation / v1` |
| `cache.inspect` | `CACHE_STATUS` | `cache-status.observation / v1` |
| `database.inspect` | `DATABASE_STATUS` | `database-status.observation / v1` |
| `queue.inspect` | `QUEUE_STATUS` | `queue-status.observation / v1` |
| `service.inspect` | `SERVICE_STATUS` | `service-status.observation / v1` |
| `service.restart` | 不创建 Observation | — |

---

## 118. Capability Request / Result Schema 命名

统一建议：

```text
metrics.query.request / 1
metrics.query.result / 1

logs.search.request / 1
logs.search.result / 1

cache.inspect.request / 1
cache.inspect.result / 1

database.inspect.request / 1
database.inspect.result / 1

queue.inspect.request / 1
queue.inspect.result / 1

service.inspect.request / 1
service.inspect.result / 1

service.restart.request / 1
service.restart.result / 1
```

数据库继续保存：

```text
schema_name
schema_version
```

---

## 119. CapabilityInvocation.response_payload 放什么

只保存：

> 已经结构化和脱敏的 Capability Result。

例如：

```text
cache.inspect.result / 1
```

不是 Provider 原始 Java Object。

大型 Provider 原始数据：

```text
raw_result_ref
```

另存。

---

## 120. AI Context 与技术详情不是同一个东西

技术详情用户可能看到：

```text
更多指标
更多日志 pattern
query digest
Provider latency
```

AI Context：

应该更小。

原则：

> **数据库保存足够复盘的信息，AI 只获得当前决策真正需要的信息。**

---

## 121. AI Context Builder

Java负责：

```text
Observation
Evidence
Hypothesis
Capability Descriptor
```

选择。

不把：

```text
整个 CapabilityInvocation.response_payload
```

全部扔给 Python。

---

## 122. 各 Capability AI Context 上限

### Metrics

最多：

```text
关键统计值
```

不发送全部时间序列。

---

### Logs

最多：

```text
Top 5 Pattern

每 Pattern 最多 2 条脱敏 Sample
```

---

### Database

最多：

```text
Top 10 Slow Query Digest
```

SQL：

```text
Normalized + 截断
```

---

### Queue

只发：

```text
统计信息
```

不发消息正文。

---

### Cache

只发：

```text
系统状态
```

不发 key/value。

---

### Service

只发：

```text
运行状态
```

不发环境变量。

---

## 123. Capability Budget 怎么计算

只有当前调查run成功完成准入的 OBSERVE 调用消耗当前run预算：
准入事务建立RUNNING Invocation，同时current_run_capability_count+1、累计capability_call_count+1。
不等待Provider结果后再扣；成功、失败、进程中断均不返还已准入额度。
准入已提交但网络发包不确定时，此计数是安全预算记账，不证明远端曾收到请求。

参数非法、未授权／未绑定、Provider不唯一、状态不允许和Duplicate拒绝发生在准入前：
不建Invocation、不扣预算。Recovery采样、CHANGE执行与reconciliation均不扣调查run预算。
Continue与FAILED回到调查开启新run；应用重启恢复原run，不刷新额度。

---

## 124. AI 不能靠重复失败调用烧光系统

指纹为 investigationId + capabilityKey + resourceId + schemaName + schemaVersion + canonical(arguments)。
**run_no 不加入指纹**：Continue不会让刚完成的同一请求立即绕过短时保护，所有在途调用也仍受保护。
检查范围为该 Investigation 同指纹全部PENDING/RUNNING，以及finished_at距今小于30秒的终态调用；
不以created_at代替finished_at，不因历史总数可超过12而无限制加载全表。

Duplicate Guard 与最后一次状态／预算检查、Invocation登记在同一准入事务内完成。
拒绝CAPABILITY_DUPLICATE_REQUEST，不建Invocation、不扣预算；返回给当前run的结构化反馈促使改选或正常收束。
30秒是可配置默认保护窗口，范围仅调查上下文；Recovery按样本唯一身份调度。

---

## 125. Capability 执行超时表

V0.1 默认：

| Capability | Timeout |
|---|---:|
| `metrics.query` | 10s |
| `logs.search` | 15s |
| `cache.inspect` | 5s |
| `database.inspect` | 10s |
| `queue.inspect` | 5s |
| `service.inspect` | 5s |
| `service.restart` | 30s |

均：

> 配置值。

不是 Java magic number。

---

## 126. Capability 错误不能直接结束 Incident

例如：

```text
metrics.query FAILED
```

Incident仍然：

```text
INVESTIGATING
```

AI可以决定：

```text
查日志
```

只有：

- 调查预算耗尽；
- 时间耗尽；
- 用户停止；
- AI Runtime 连续失败；
- AI完成 Diagnosis；

才结束调查循环。

---

## 127. Capability 安全不变量

正式冻结以下规则。

---

### CAP-INV-001

AI 永远不能提交任意 PromQL。

---

### CAP-INV-002

AI 永远不能提交任意 LogQL。

---

### CAP-INV-003

AI 永远不能提交任意 SQL。

---

### CAP-INV-004

AI 永远不能提交任意 Shell / Docker Command。

---

### CAP-INV-005

所有 Capability 都必须经过：

```text
Registry
+
Resource
+
CapabilityBinding
+
Provider Binding
```

校验。

---

### CAP-INV-006

OBSERVE Capability 调用失败不得产生虚假 Observation。

---

### CAP-INV-007

所有进入 Observation 和 AI Context 的外部数据必须先经过 Sanitizer。

---

### CAP-INV-008

`cache.inspect` 不允许读取业务 Key Value。

---

### CAP-INV-009

`queue.inspect` 不允许读取业务 Message Payload。

---

### CAP-INV-010

`database.inspect` 不允许读取任意业务表数据。

---

### CAP-INV-011

`service.inspect` 不允许返回 Container Environment Secret。

---

### CAP-INV-012

`service.restart` 不允许接收 AI 提供的 host、container 或 command。

---

### CAP-INV-013

`service.restart` 必须绑定具体 ManagedResource。

---

### CAP-INV-014

所有 CHANGE Capability 必须经过 Approval。

---

### CAP-INV-015

Capability 风险等级由 Java Policy 决定，不由 AI 决定。

---

### CAP-INV-016

Investigation 阶段只向 AI 暴露 OBSERVE Capability。

---

### CAP-INV-017

RecoveryVerification 中 Capability 由 RecoveryPolicy 决定，不由 AI 决定。

---

### CAP-INV-018

RecoveryVerification 调用不消耗 Investigation Capability Budget。

---

### CAP-INV-019

CHANGE Capability 不创建 CapabilityInvocation；其执行事实由 ActionExecution 保存。

---

### CAP-INV-020

`service.restart` 成功不能直接导致 Incident RESOLVED。

---

## 128. S1 如何用 Capability 跑起来

S1 的调查顺序由 AI 决定。至少获得业务 HTTP 退化与 Redis 访问路径异常的 Observation，
关联到同一主假设的 SUPPORTS Evidence，并冻结在最终 Diagnosis 中。
日志和 MySQL 指标可帮助排除替代解释，但不强制MySQL QPS上升；详见09。

---

## 129. S2 如何跑起来

场景：

> MySQL 慢查询导致连接池耗尽。

先：

```text
metrics.query

http.request.latency.p99
```

---

再：

```text
metrics.query

http.request.error_rate
```

---

检查：

```text
jvm.cpu.usage
```

正常。

---

检查：

```text
db.pool.active
db.pool.pending
db.pool.max
```

发现：

```text
active = max
pending 增长
```

---

AI提出：

> 数据库连接长期占用。

再调用：

```text
database.inspect
SLOW_QUERIES
```

发现：

```text
某 SQL avg = 6.2s
```

最终形成原因链：

```text
Slow Query
↓
连接长时间占用
↓
HikariCP Exhaustion
↓
Request Wait
↓
HTTP P99 / 500 上升
```

---

## 130. S3 如何跑起来

S3 通过多个 queue.inspect 观察 Producer 继续和 lag 增长，通过 service.inspect 取得Consumer STOPPED；
形成有真实支持Evidence的Diagnosis。Remediation独立提议允许的service.restart。
用户批准时先冻结RecoveryPolicy；Java派发CHANGE，不确定则有界只读核对。
确定执行成功后按本文件 §113 的 B->C->D->A 采样计划验证；只有全部required TRUE才RESOLVED。
pending异常累积、lag未知或服务停止均不能被“命令成功”抵消。

---

## 131. ShortLink 接入时真正要配置什么

现在可以明确回答。

不是：

> 去 ShortLink 每个模块写 OpsPilot 代码。

而是配置：

```text
ManagedSystem
```

---

创建 Resource：

```text
redirect-service
statistics-consumer
shortlink-redis
shortlink-mysql
statistics-stream
```

---

创建 Connection：

```text
prometheus-local
loki-local
redis-local
mysql-readonly
docker-local
```

---

创建 ResourceBinding：

```text
redirect-service
→ prometheus-local

redirect-service
→ loki-local

statistics-consumer
→ docker-local

statistics-consumer
→ loki-local

shortlink-redis
→ redis-local

statistics-stream
→ redis-local

shortlink-mysql
→ mysql-readonly
```

---

创建 CapabilityBinding：

```text
redirect-service
→ metrics.query
→ logs.search

statistics-consumer
→ service.inspect
→ logs.search
→ service.restart

shortlink-redis
→ cache.inspect

shortlink-mysql
→ database.inspect

statistics-stream
→ queue.inspect
```

然后配置：

```text
RecoveryPolicy
```

即可。

---

## 132. 如果第二天要接 OrderService 怎么办

假设 OrderService 已经有：

```text
Prometheus
Loki
MySQL
Redis
Docker
```

那么主要工作是：

```text
新增 ManagedSystem
新增 Resource
新增 Binding
配置 MetricKey
配置 CapabilityBinding
配置 RecoveryPolicy
```

理论上：

> 不需要重新开发 7 个 Capability。

这才证明 Capability 抽象是成立的。

---

## 133. 什么时候需要写新 Provider

例如以后公司不用 Loki。

而是：

```text
Elasticsearch
```

业务系统不用改。

OpsPilot 新增：

```text
ElasticsearchLogProvider
```

继续实现：

```text
logs.search
```

Agent仍然只看到：

```text
logs.search
```

---

## 134. 什么时候需要写新 Capability

不是：

> 换了一个业务系统

就写新 Capability。

只有产生了真正不同的业务语义时才加。

例如未来需要：

```text
deployment.inspect
```

这是：

> 新能力。

而：

```text
restart-order-service
```

不是新能力。

它仍然只是：

```text
service.restart
```

绑定到另一个 Resource。

---

## 135. 这就是 OpsPilot 能扩展但不是插件内核的关键

扩展维度：

```text
新业务系统
→ 配置 Resource / Binding
```

---

```text
新基础设施实现
→ 新 Provider
```

---

```text
真正新的系统语义
→ 新 Capability
```

三者完全不同。

不能混在一起。

---

## 136. Capability 阶段冻结后 Java 代码应该能够回答

#### AI可以查任意 PromQL吗？

不能。

#### AI可以写任意 SQL吗？

不能。

#### AI可以 GET Redis Key 吗？

不能。

#### AI可以读取 Queue Message Body 吗？

不能。

#### AI知道 Docker container name 吗？

不需要知道。

#### AI能指定 Docker command 吗？

不能。

#### 谁知道真正 Provider？

Java。

#### 谁知道 Credential？

Java。

#### 接一个新业务系统必须修改目标代码吗？

不必须。

#### 什么情况下才修改？

目标系统缺少必要可观测能力时。

#### 一个新业务系统要重新写 Capability 吗？

通常不需要。

#### 换 Prometheus 为 VictoriaMetrics 怎么办？

增加 Provider。

#### 换 Docker 为 Kubernetes 怎么办？

增加 Service Provider / Executor。

#### service.restart 风险是谁定？

Java。

#### restart 后谁判断恢复？

RecoveryPolicy Engine。

#### AI能说“已经恢复”吗？

不能。

---

## 137. Capability 实施完成要求

七个Capability的职责、输入、输出、授权、来源、脱敏、预算及Recovery复用均为本文件的正式合同。
contracts/ 下的机器可执行Schema与双端fixture由TASK-028～032创建；实现不得把本文件的强类型联合退化为任意Map。
实际Provider网络权限、指标名称和目标版本组合在对应Task验证，不以本文件作为已运行证明。

---

## 138. 本阶段之后不再讨论什么

Capability 冻结后：

不要重新讨论：

```text
是否让 AI 写 SQL
是否给 AI Shell
是否让 Python 直接查 Redis
是否让 AI 直接调用 Docker
是否为每个系统写一个 Tool
```

答案都已经冻结：

> 不允许。

---

## 139. 工程落位

工程依赖、事务、Dispatcher、SSE、错误映射和测试分层见 [07-engineering.md](07-engineering.md)。
任务入口见 [08-implementation-plan.md](08-implementation-plan.md)，不再开启新一轮Capability设计。

---

## 140. 冻结声明

本文件为合并后的唯一Capability规范；旧补丁已经归档，不作为编码解释链。
扩展新系统通过Resource/Binding配置，替换基础设施通过Provider；新能力需要新的业务需求，不顺手添加。

---
