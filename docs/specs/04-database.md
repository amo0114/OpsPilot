# OpsPilot V0.1 MySQL 物理数据模型

> Status: FROZEN  
> Version: 0.1  
> Freeze revision: FINAL-FREEZE-20260925  
> 冻结日期：2026-09-25  
> 范围：23张表的字段、约束、索引、事务和新增运行控制字段。  
> 本文是已合并的正式实现规格。权威清单见 [SPEC-MANIFEST.md](SPEC-MANIFEST.md)。FROZEN 表示规格定稿，不表示代码、迁移或故障实验已经通过。

## 1. 数据库总体原则

OpsPilot V0.1 不采用：

```text
一个领域类 = 一张表
```

也不采用：

```text
所有东西都塞一个 JSON
```

判断是否独立持久化主要看四件事：

| 判断 | 含义 |
|---|---|
| 是否拥有独立身份 | 会不会被其他对象引用 |
| 是否有独立生命周期 | 是否需要自己改变状态 |
| 是否需要单独查询 | 是否是常见查询对象 |
| 是否需要审计 | 是否必须保留历史 |

因此一些值对象不建表。

例如：

```text
TimeRange
ObservationValue
CapabilityParameters
RecoveryCriterion
```

作为结构化字段或 JSON 保存即可。

---

## 2. 主键策略

V0.1 全部内部表使用：

```text
BIGINT UNSIGNED
```

作为主键。

数据库内部：

```text
id BIGINT UNSIGNED AUTO_INCREMENT
```

原因：

- MySQL 索引简单；
- Java 使用方便；
- V0.1 不需要跨地域 ID 生成；
- 不为了“分布式”提前引入雪花 ID。

需要给用户看的对象另外拥有业务编号。

例如：

```text
Incident.id = 127
Incident.incident_key = INC-20260925-0001
```

用户界面展示：

```text
INC-20260925-0001
```

而不是数据库 ID。

---

## 3. 时间规范

所有时间统一：

```text
DATETIME(3)
```

应用层统一使用 UTC。

前端根据用户时区显示。

不在数据库中混杂：

```text
UTC
Asia/Shanghai
America/Los_Angeles
```

等不同时区语义。

---

## 4. 删除规范

故障审计数据禁止物理删除。

包括：

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

配置类对象：

```text
ManagedSystem
ManagedResource
DataSourceConnection
```

不使用全局：

```text
deleted = 0/1
```

这种到处存在的软删除字段。

而使用明确业务状态：

```text
ACTIVE
DISABLED
ARCHIVED
```

---

## 5. 外键原则

OpsPilot V0.1 不分库分表，因此使用数据库外键保证核心引用完整性。

统一：

```text
ON DELETE RESTRICT
```

核心审计数据禁止级联删除。

不会使用：

```text
ON DELETE CASCADE
```

把一场事故的 Evidence、Diagnosis 和 Timeline 一起删除。

---

## 6. 第一组：系统接入

需要 5 张表：

| 表 | 作用 |
|---|---|
| `managed_system` | 业务系统 |
| `managed_resource` | 系统组件 |
| `data_source_connection` | 数据源 |
| `resource_binding` | 资源与数据源绑定 |
| `capability_binding` | 资源拥有的能力 |

---

## 7. managed_system

表示 OpsPilot 管理的业务系统。

核心字段：

```text
id
system_key
name
description
environment
status
created_at
updated_at
lock_version
```

其中：

```text
system_key
```

是稳定机器标识。

例如：

```text
shortlink-platform
```

而：

```text
name
```

是用户展示名称：

```text
ShortLink Platform
```

唯一约束：

```text
UNIQUE(system_key)
```

状态：

```text
ACTIVE
DISABLED
ARCHIVED
```

禁止已被 Incident 使用的系统物理删除。

---

## 8. managed_resource

表示一个系统内部可以调查或者操作的组件。

字段：

```text
id
managed_system_id
resource_key
name
resource_type
description
status
created_at
updated_at
lock_version
```

例如：

```text
resource_key:
statistics-consumer

name:
Statistics Consumer

resource_type:
CONSUMER
```

资源类型 V0.1：

```text
SERVICE
DATABASE
CACHE
MESSAGE_QUEUE
CONSUMER
EXTERNAL_DEPENDENCY
```

唯一：

```text
UNIQUE(
    managed_system_id,
    resource_key
)
```

常用索引：

```text
INDEX(managed_system_id, status)
```

---

## 9. data_source_connection

代表：

> OpsPilot 从哪里获得数据或执行基础设施操作。

字段：

```text
id
connection_key
name
provider_type
endpoint
credential_ref
config_schema_name
config_schema_version
config_payload
status
created_at
updated_at
lock_version
```

例如：

```text
provider_type = PROMETHEUS
endpoint = http://prometheus:9090
```

或者：

```text
provider_type = MYSQL
```

重要规则：

数据库中不保存：

```text
password
api_key
token
```

明文。

只保存：

```text
credential_ref
```

例如：

```text
env://OPSPILOT_SHORTLINK_MYSQL_PASSWORD
```

或者未来：

```text
vault://...
```

V0.1 不开发自己的 Secret Manager。

唯一：

```text
UNIQUE(connection_key)
```

---

## 10. resource_binding

表示：

> 某个 ManagedResource 在某个 DataSourceConnection 中如何定位。

字段：

```text
id
managed_resource_id
data_source_connection_id
selector_schema_name
selector_schema_version
selector_payload
created_at
updated_at
```

例如：

```text
resource:
shortlink-project

connection:
prometheus-local

selector_payload:
{
  "labels": {
    "application": "shortlink-project"
  }
}
```

Java 中不能直接使用：

```java
Map<String, Object>
```

而应该有：

```java
PrometheusResourceSelector
LokiResourceSelector
MySqlResourceSelector
```

数据库只负责序列化存储。

唯一约束 V0.1：

```text
UNIQUE(
    managed_resource_id,
    data_source_connection_id
)
```

---

## 11. capability_binding

表示：

> 某个资源可以使用哪些 OpsPilot 能力。

字段：

```text
id
managed_resource_id
capability_key
enabled
created_at
updated_at
```

例如：

```text
statistics-consumer
→ service.inspect
→ service.restart
```

或者：

```text
redis
→ cache.inspect
```

`capability_key` 必须存在于 Java：

```text
CapabilityRegistry
```

但不建立：

```text
capability_definition
```

业务表。

唯一：

```text
UNIQUE(
    managed_resource_id,
    capability_key
)
```

---

## 12. 第二组：故障核心

需要：

| 表 | 作用 |
|---|---|
| `incident` | 故障 |
| `incident_affected_resource` | 受影响资源 |
| `investigation` | 唯一调查工作空间 |
| `capability_invocation` | 能力调用 |
| `observation` | 客观观测 |
| `hypothesis` | 待验证原因 |
| `evidence` | Observation 与 Hypothesis 的关系 |
| `diagnosis` | 版本化诊断 |
| `diagnosis_evidence_ref` | 诊断冻结引用的证据 |

---

## 13. incident

这是整个故障域的核心表。

字段：

```text
id

incident_key

managed_system_id

title
description
impact_summary

status

created_source
created_by

started_at
detected_at
resolved_at

created_at
updated_at

lock_version
```

状态就是已经冻结的 8 个：

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

唯一：

```text
UNIQUE(incident_key)
```

主要索引：

```text
INDEX(managed_system_id, status, created_at)

INDEX(status, created_at)

INDEX(detected_at)
```

---

## 14. 为什么 Incident 要有 lock_version

这是为了：

> 乐观锁。

例如：

用户正在批准操作。

与此同时另一个请求尝试：

```text
DIAGNOSED → INVESTIGATING
```

两者不能都成功。

Java 更新：

```text
WHERE id = ?
AND lock_version = ?
```

成功后：

```text
lock_version + 1
```

失败：

> 说明业务状态已经被其他线程改变。

重新读取。

这比单纯：

```text
先 SELECT
再 UPDATE
```

安全。

---

## 15. incident_affected_resource

表示：

```text
Incident N:M ManagedResource
```

字段：

```text
incident_id
managed_resource_id
created_at
```

组合主键或唯一约束：

```text
UNIQUE(
    incident_id,
    managed_resource_id
)
```

不把：

```text
resource_ids
```

存 JSON。

---

## 16. investigation

Incident 首次 Start 建立唯一 Investigation，`UNIQUE(incident_id)`；字段最终如下。

| 字段 | 物理类型／空值 | 语义 |
|---|---|---|
| id | BIGINT UNSIGNED PK | 内部身份 |
| incident_id | BIGINT UNSIGNED NOT NULL UNIQUE FK | 所属 Incident |
| started_at | DATETIME(3) NOT NULL | 第一次开始调查时间，历史起点 |
| last_activity_at | DATETIME(3) NOT NULL | 最近实际活动 |
| current_run_no | INT UNSIGNED NOT NULL | 第一次提交为 1，以后递增 |
| current_run_started_at | DATETIME(3) NOT NULL | 当前轮起点 |
| current_run_capability_count | INT UNSIGNED NOT NULL DEFAULT 0 | 当前轮已准入 OBSERVE 次数 |
| capability_call_count | BIGINT UNSIGNED NOT NULL DEFAULT 0 | 全工作空间累计准入次数 |
| consecutive_ai_failure_count | INT UNSIGNED NOT NULL DEFAULT 0 | 当前轮连续 AI 失败 |
| stop_requested_at | DATETIME(3) NULL | 当前轮协作式停止意图 |
| stop_requested_by | VARCHAR(128) NULL | 请求停止的演示身份 |
| max_capability_calls | INT UNSIGNED NOT NULL | 单轮上限，默认 12 |
| max_duration_seconds | INT UNSIGNED NOT NULL | 单轮上限，默认 480 |
| agent_step_timeout_seconds | INT UNSIGNED NOT NULL | 一次 AI 请求上限，默认 60 |
| max_consecutive_ai_failures | INT UNSIGNED NOT NULL | 连续失败阈值，默认 3 |
| created_at / updated_at | DATETIME(3) NOT NULL | 创建／更新 |
| lock_version | BIGINT UNSIGNED NOT NULL DEFAULT 0 | 并发版本 |

首次创建、run 初始化和 Incident 迁移在一个事务提交；不暴露半初始化记录。
限制值在首次创建时从配置快照；当前轮计数与累计计数在同一准入事务加一。
run 切换只清当前计数，不清累计计数、不删除历史。两种计数应分别等于相关已准入 Invocation 的数量；
Provider 是否已真实接收不是用计数证明的事实。

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

## 17. capability_invocation 的正式身份

`CapabilityInvocation` 被定义为：

> **业务相邻、必须持久化、技术详情可见的能力调用记录。**

它不是：

> 产品首页核心对象。

也不是：

> 纯 Agent 调试日志。

这是 Evidence 可追溯性的关键链路。

---

## 18. capability_invocation

字段：

```text
id

incident_id

investigation_id NULL

recovery_verification_id NULL

capability_key

managed_resource_id

status

request_schema_name
request_schema_version
request_payload

response_schema_name
response_schema_version
response_payload

raw_result_ref

started_at
finished_at
duration_ms

error_code
error_message

correlation_id

created_at
```

调用状态：

```text
PENDING
RUNNING
SUCCEEDED
FAILED
```

补齐以下列：
| 字段 | 类型 | 约束／语义 |
|---|---|---|
| run_no | INT UNSIGNED NULL | 调查调用必须有；恢复调用必须空 |
| criterion_key | VARCHAR(128) NULL | 恢复样本所属 Criterion |
| sample_index | INT UNSIGNED NULL | 恢复样本序号，从 1 开始 |
| updated_at | DATETIME(3) NOT NULL | 运行记录更新时间 |

新增唯一约束 `UNIQUE(recovery_verification_id, criterion_key, sample_index)`；
调查上下文这些恢复列均为 NULL，不限制调查的多次实际请求。
成功／失败终态只允许从 RUNNING 条件更新；受影响行数非 1 则不得覆盖。
V0.1 OBSERVE 准入直接创建 RUNNING，不把 PENDING 当作可任意重放的外部请求。

---

## 19. Invocation 上下文约束

用两个明确外键而非 context_type/context_id。CHECK 和 Java 同时保证：
```sql
CHECK (
  (investigation_id IS NOT NULL AND recovery_verification_id IS NULL
   AND run_no IS NOT NULL AND run_no >= 1
   AND criterion_key IS NULL AND sample_index IS NULL)
  OR
  (investigation_id IS NULL AND recovery_verification_id IS NOT NULL
   AND run_no IS NULL AND criterion_key IS NOT NULL
   AND sample_index IS NOT NULL AND sample_index >= 1)
)
```
上式是列约束示意，Flyway 按最终列名落地。Invocation.incident_id 与父上下文的 Incident 必须一致；
不能创建既非调查也非验证的第三种上下文。reconciliation 记录在 ActionExecution 自己的运行审计中。

---

## 20. 为什么 Invocation 直接保存 incident_id

理论上可以：

```text
Invocation
→ Investigation
→ Incident
```

间接查询。

但事故技术详情是极高频查询：

> 给我这个 Incident 全部查询记录。

所以直接保存：

```text
incident_id
```

降低查询复杂度。

这是有意识的冗余。

Java 必须保证：

```text
Invocation.incident_id
```

与其 Investigation / Verification 所属 Incident 一致。

---

## 21. capability_invocation 索引

核心索引：

```text
INDEX(
    incident_id,
    id
)
```

调查查询：

```text
INDEX(
    investigation_id,
    id
)
```

恢复查询：

```text
INDEX(
    recovery_verification_id,
    id
)
```

资源查询：

```text
INDEX(
    managed_resource_id,
    created_at
)
```

补派发及中断处理需要状态索引；具体复合索引在相应迁移任务按实际查询确定。
Duplicate 查询必须包括当前 Investigation 的全部 PENDING/RUNNING 调用，以及 `finished_at` 落在保护窗口内的终态调用，
不能只查最近 30 秒创建的行。工作空间总调用次数可能超过 12，因此不能基于“全历史最多 12 条”无限制全表加载。

---

## 22. observation

Observation 是：

> 已经从真实系统获得的不可变事实。

字段：

```text
id

incident_id

investigation_id NULL
recovery_verification_id NULL

capability_invocation_id

managed_resource_id

observation_kind

schema_name
schema_version
payload

summary

observed_at

window_start
window_end

created_at
```

补充 `INDEX(incident_id, id)`；保留调查／恢复查询索引。
Observation 的调查 run 或恢复 criterion/sample 身份通过不可变的 capability_invocation_id 获取，
Java 插入时必须校验 Observation 的 Incident／上下文／资源与来源 Invocation 一致。
无需再复制一套 run_no 或 sample_index 到 Observation 中形成第二份身份事实。

---

## 23. Observation 归属

和 Invocation 一样：

```text
investigation_id XOR recovery_verification_id
```

不能只通过：

```text
capability_invocation
```

间接找归属。

原因：

复盘时大量查询会直接问：

> 当前 Investigation 有哪些 Observation？

或者：

> 这次 Verification 使用了哪些观测？

直接字段能够让模型更清晰。

---

## 24. Observation 不使用万能字段

核心语义字段必须明确：

```text
observation_kind
observed_at
managed_resource_id
```

不同能力的具体载荷使用：

```text
schema_name
schema_version
payload JSON
```

例如：

```text
schema_name:
cache.inspect.result

schema_version:
1
```

Java 对应：

```java
CacheInspectResultV1
```

不是：

```java
Map<String, Object>
```

---

## 25. Observation 类型

V0.1 可以使用：

```text
METRIC
LOG_PATTERN
SERVICE_STATUS
DATABASE_STATUS
CACHE_STATUS
QUEUE_STATUS
OTHER
```

它只是较粗的分类。

真正的数据结构仍由：

```text
schema_name
```

确定。

---

## 26. immutable 规则

Observation 不提供：

```text
updated_at
```

也没有：

```text
UPDATE observation
```

业务路径。

任何新事实：

> INSERT 新 Observation。

---

## 27. hypothesis

字段：

```text
id
investigation_id

title
description

status

created_at
updated_at

lock_version
```

状态：

```text
PENDING
SUPPORTED
INSUFFICIENT_EVIDENCE
REFUTED
```

Hypothesis 是少数允许改变当前状态的调查对象。

历史变化：

> TimelineEvent 保存。

---

## 28. hypothesis 索引

```text
INDEX(
    investigation_id,
    status
)
```

足够。

---

## 29. evidence

Evidence 正式物理建模成：

```text
Observation × Hypothesis
```

关系。

字段：

```text
id

investigation_id

observation_id
hypothesis_id

relation

reason

created_at
```

关系：

```text
SUPPORTS
REFUTES
CONTEXT
```

补充 `INDEX(investigation_id, id)`。不存在内容版本字段或前一版本外键。
同 Observation × Hypothesis 重复提议返回 EVIDENCE_LINK_ALREADY_EXISTS，不修改原关系，
也不通过复制一个未发生的新 Observation 绕过唯一约束。

---

## 30. Evidence 去重约束

V0.1 一个 Observation 对同一个 Hypothesis 只允许建立一次证据关系。

唯一：

```text
UNIQUE(
    observation_id,
    hypothesis_id
)
```

避免出现：

```text
O-001 SUPPORTS H-001

以及

O-001 REFUTES H-001
```

这种同一个事实同时被系统登记成相互冲突关系。

如果后续新证据推翻：

> 使用新的 Observation。

不会修改旧 Evidence。

真实新查询可以产生新Observation；禁止仅为改Evidence关系复制已有Observation。
没有新事实时，保留原Evidence，通过其他证据和新Diagnosis反映新的判断。

---

## 31. Evidence 所属 Investigation

Java 创建 Evidence 时必须验证：

```text
Observation.investigation_id
=
Hypothesis.investigation_id
=
Evidence.investigation_id
```

Evidence 不允许引用：

> RecoveryVerification Observation。

恢复观测不是调查证据。

如果恢复失败重新调查：

后续调查阶段重新产生新的 Investigation Observation。

---

## 32. diagnosis

字段：

```text
id

investigation_id

version_no

conclusion_type

primary_hypothesis_id NULL

summary
impact_summary

termination_reason NULL

created_at
```

不可修改。

新增 `run_no INT UNSIGNED NOT NULL`，冻结产生该 Diagnosis 的当前运行周期。
创建时必须匹配 Investigation.current_run_no；历史 Diagnosis 不随 Continue 更新。

---

## 33. Diagnosis 唯一约束

```text
UNIQUE(
    investigation_id,
    version_no
)
```

获取当前诊断：

```text
ORDER BY version_no DESC
LIMIT 1
```

不额外维护：

```text
investigation.current_diagnosis_id
```

避免双重事实来源。

---

## 34. conclusion_type

结论仅 PRIMARY_CAUSE_IDENTIFIED、POSSIBLE_CAUSE、UNDETERMINED。
前两类必须有当前 Investigation 下的 primary_hypothesis_id；
在该 Diagnosis 的 diagnosis_evidence_ref 集合中，至少存在一条属于同 Investigation、
关联该 primary_hypothesis_id 的 SUPPORTS Evidence。
跨表条件在 Java 领域校验与创建事务中保证，不能仅用全 Investigation 存在某条支持证据代替。
UNDETERMINED 可没有主假设；不允许据此创建写操作方案。

---

## 35. termination_reason

只有调查因确定性条件结束时需要。

例如：

```text
AGENT_COMPLETED

USER_STOPPED

CAPABILITY_BUDGET_EXHAUSTED

INVESTIGATION_TIMEOUT

AI_RUNTIME_UNAVAILABLE
```

避免出现：

```text
UNDETERMINED
```

但不知道：

> 为什么没查出来。

---

## 36. diagnosis_evidence_ref

Diagnosis 必须冻结：

> 当时具体用了哪些 Evidence。

字段：

```text
diagnosis_id
evidence_id
created_at
```

唯一：

```text
UNIQUE(
    diagnosis_id,
    evidence_id
)
```

唯一关系约束要求：

> 同一个 Diagnosis 引用同一个 Evidence 最多一次。

即使以后同一个 Hypothesis 新增 Evidence：

旧 Diagnosis：

> 不会自动改变依据。

---

## 37. 第三组：处理与恢复

需要：

| 表 | 作用 |
|---|---|
| `remediation_plan` | 为什么这样处理 |
| `remediation_action` | 具体执行什么 |
| `approval_request` | 谁批准 |
| `action_execution` | 实际执行 |
| `recovery_policy` | 什么叫恢复 |
| `recovery_verification` | 是否真的恢复 |

---

## 38. remediation_plan

字段：

```text
id

incident_id
diagnosis_id

title
summary

status

created_at
updated_at
```

状态：

```text
ACTIVE
SUPERSEDED
CANCELLED
EXECUTED
```

说明：

`EXECUTED`

只表示：

> 该 Plan 对应的 Action 已经发生过执行尝试。

不代表：

> ActionExecution = SUCCEEDED。

更不代表：

> Incident = RESOLVED。

---

## 39. 为什么一定需要 SUPERSEDED

例如：

```text
Diagnosis v1
→ Plan P1
```

后来：

```text
Diagnosis v2
```

产生。

P1 不能删除。

也不能继续执行。

事务中将：

```text
P1.status
ACTIVE → SUPERSEDED
```

这样历史完整保留。

---

## 40. Plan 索引

```text
INDEX(
    incident_id,
    status
)
```

```text
INDEX(
    diagnosis_id
)
```

---

## 41. remediation_action

V0.1：

```text
Plan 1:1 Action
```

字段：

```text
id
remediation_plan_id

capability_key
target_resource_id

parameter_schema_name
parameter_schema_version
parameter_payload

summary
expected_impact_summary
risk_level

created_at
```

唯一：

```text
UNIQUE(remediation_plan_id)
```

V0.1 唯一写 Capability：

```text
service.restart
```

---

## 42. Action 为什么不存 shell_command

禁止：

```text
docker restart xxx
```

作为业务数据。

只保存：

```text
capability_key = service.restart

target_resource_id = ...
```

真正执行方式由：

```text
DockerServiceExecutor
```

确定。

这样未来从 Docker 换到其他实现：

> 历史业务含义仍然是“重启服务”。

---

## 43. approval_request

字段：

```text
id

remediation_action_id

status

requested_at

decided_by NULL
decided_at NULL
comment NULL

created_at
updated_at
lock_version
```

状态：

```text
PENDING
APPROVED
REJECTED
CANCELLED
```

唯一：

```text
UNIQUE(remediation_action_id)
```

V0.1 一个 Action 最多一条 Approval。

---

## 44. Approval 决策不可反转

Java 只允许：

```text
PENDING → APPROVED

PENDING → REJECTED

PENDING → CANCELLED
```

不允许：

```text
APPROVED → REJECTED
```

数据库通过：

> 乐观锁 + Domain Service

保证。

---

## 45. action_execution

字段：

```text
id

remediation_action_id
approval_request_id

idempotency_key

status

executor_key

result_schema_name
result_schema_version
result_payload

error_code
error_message

started_at
finished_at

correlation_id

created_at
updated_at
lock_version
```

状态：

```text
PENDING
RUNNING
SUCCEEDED
FAILED
```

以下是本次最终裁决补齐的执行上下文与核对列：
| 字段 | 类型／空值 | 语义 |
|---|---|---|
| recovery_policy_id | BIGINT UNSIGNED NOT NULL FK | 执行前选定策略 |
| recovery_policy_version | INT UNSIGNED NOT NULL | 选定业务版本 |
| recovery_policy_snapshot | JSON NOT NULL | 完整冻结恢复合同，含 schemaName/schemaVersion |
| execution_context_schema_name | VARCHAR(128) NOT NULL | `service.restart.execution-context` |
| execution_context_schema_version | INT UNSIGNED NOT NULL | 1 |
| execution_context_payload | JSON NOT NULL | 受信解析的 Provider／目标容器身份；不含密码或任意命令 |
| reconciliation_attempt_count | INT UNSIGNED NOT NULL DEFAULT 0 | 已登记只读核对次数 |
| max_reconciliation_attempts | INT UNSIGNED NOT NULL | 配置快照，默认 3 |
| last_reconciliation_at | DATETIME(3) NULL | 最近准入核对时间 |
| reconciliation_deadline_at | DATETIME(3) NULL | 首次进入核对时冻结，不因重启刷新 |

execution_context_payload 在 PENDING 创建时记录受信资源绑定标识，在 RUNNING 准入前完成真实目标容器身份解析；
对外部 inspect 的解析不包进 DB 事务。准入落账时再次检查状态／绑定与目标一致。
recovery_policy_snapshot 在创建 PENDING 时已经完整；不能用模型提供的数据替代。
核对结果进入类型化 result_payload 及 Timeline，不建立新的 Invocation Context。

---

## 46. ActionExecution 核心唯一约束

`UNIQUE(remediation_action_id)` 与 `UNIQUE(idempotency_key)` 保证同 Action 只有一个 Execution 身份。
真正限制一次写请求派发还依赖 PENDING -> RUNNING 条件更新、单实例单飞，以及关闭客户端隐式写重试。
一个 Execution 记录不等于远端 exactly-once；结果未知时只读核对，不重新 restart。

---

## 47. 幂等不是靠“先查一下”

公开 API 不提供独立创建或重试 Execution 的接口；Approval 事务创建执行记录。
重复的同一批准动作返回既有决定与 Execution，不能再创建记录或重新派发 CHANGE。
幂等键由 Java 根据已冻结 Action／Execution 身份产生，稳定保存；唯一冲突不能被“换一个随机键重试”绕过。

---

## 48. recovery_policy

字段：

```text
id

managed_resource_id

policy_key
name

version_no

criteria_schema_name
criteria_schema_version
criteria_payload

status

created_at
activated_at
retired_at
```

状态：

```text
ACTIVE
RETIRED
```

唯一：

```text
UNIQUE(
    managed_resource_id,
    policy_key,
    version_no
)
```

---

## 49. 同一资源唯一 ACTIVE 的事务保证

每 ManagedResource 最多一个 ACTIVE，跨 policy_key 也如此。
V0.1 不引入生成列或复杂触发器；激活事务锁定稳定存在的 managed_resource 父行，
在锁内查询当前 ACTIVE、退休旧版本并插入新版本。首次激活也有同一父行锁，不能只锁不存在的旧策略行。
Java 在准入时重复验证唯一性；发现 0 或多条不猜测选择。版本唯一约束仍保留。

---

## 50. recovery_verification

字段：

```text
id

incident_id

action_execution_id NULL

managed_resource_id

recovery_policy_id
recovery_policy_version

policy_snapshot

verification_no

status

result_summary

started_at
finished_at

created_at
updated_at
lock_version
```

补充：
- `deadline_at DATETIME(3) NOT NULL`，按创建时间加快照的 maxDurationSeconds，重启不刷新。
- `UNIQUE(action_execution_id)`；该列可空，外部处理可有多次独立 Verification。
- `policy_snapshot` 内包含 schemaName/schemaVersion、顺序化 Criteria、阈值、采样与时间限制。
- `result_payload JSON NULL` 保存类型化 `recovery.verification.result / 1`（含内嵌 schema 标识）：
  criterionKey、TRUE/FALSE/UNKNOWN、样本 Invocation IDs、缺失／失败原因、overallResult。
  运行中结果可重建，终态内容不得覆盖；result_summary 是同一结果的用户摘要，不是第二套判定算法。

样本不另建表，使用 capability_invocation 的 criterion_key/sample_index。

---

## 51. Verification 为什么必须有 managed_resource_id

因为 RecoveryPolicy V0.1 挂 Resource。

即使未来 Policy 被归档：

历史验证仍然知道：

> 当时验证哪个组件。

---

## 52. Policy Snapshot 的选择时机与历史一致性

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

## 53. Verification 编号

唯一：

```text
UNIQUE(
    incident_id,
    verification_no
)
```

例如：

```text
V1 FAILED
V2 PASSED
```

比单纯按 created_at 排序更清楚。

---

## 54. ActionExecution 可以为空

这是必须的。

因为：

```text
用户在 OpsPilot 外部处理
↓
DIAGNOSED → VERIFYING
```

此时：

```text
action_execution_id = NULL
```

完全合法。

---

## 55. 第四组：事故时间线

### incident_timeline_event

字段：

```text
id

incident_id

event_type

occurred_at

actor_type
actor_id NULL

summary

payload

correlation_id NULL

created_at
```

---

## 56. actor_type

V0.1：

```text
USER
SYSTEM
AI_RUNTIME
```

例如：

```text
AI_RUNTIME
```

表示：

> 这个 Hypothesis 来源于 AI 提议。

但真正写 Timeline 的仍是 Java。

---

## 57. 时间线顺序

Timeline 使用 BIGINT AUTO_INCREMENT ID 与 `INDEX(incident_id, id)` 查询同 Incident 的追加事件。
ID 不是天然的事务提交时钟；所有同 Incident 的 Timeline 追加在其短事务内先获取同一 Incident 行锁，
保持分配／提交顺序，SSE 按数据库游标顺序补读而不按回调到达顺序直接推载荷。
不增加 Event Sourcing、Outbox 或第二套业务状态。

---

## 58. Timeline payload 可以是 JSON

因为不同 event_type 的附加信息不同。

例如：

```text
EVIDENCE_LINKED
```

payload：

```json
{
  "evidenceId": 21,
  "hypothesisId": 7,
  "observationId": 18
}
```

但：

> Incident.status 等核心业务字段绝不能只存在 Timeline JSON。

Timeline 是审计历史，

不是当前业务状态的唯一来源。

---

## 59. AI Runtime 运行记录

需要单独一张：

### agent_step_record

它是：

> 内部技术记录。

不是默认产品领域对象。

字段：

```text
id

incident_id
investigation_id

step_no

intent_type

status

model_provider
model_name

prompt_template_version

context_digest

output_schema_version
output_payload

latency_ms

prompt_tokens
completion_tokens

error_code
error_message

created_at
```

补充 `run_no INT UNSIGNED NOT NULL`、`started_at DATETIME(3) NOT NULL`、
`finished_at DATETIME(3) NULL`、`updated_at DATETIME(3) NOT NULL`。
run_no 为准入周期快照；step_no 保持整个 Investigation 内单调编号，唯一键不改。
状态为 RUNNING/SUCCEEDED/FAILED；终态条件更新，进程中断用 FAILED/PROCESS_INTERRUPTED，
不能因此累计模型自身连续失败。原始输出与被业务接受不是同一个概念；
output_payload 可保存被拒绝的结构化提议及拒绝原因，不能让迟到结果驱动新轮领域写入。

---

## 60. AgentStep 不保存完整 Secret / Prompt

不直接保存：

```text
完整 System Prompt
完整日志原文
数据源 Credential
```

只记录：

```text
prompt_template_version
context_digest
```

以及必要的结构化输出。

避免 Agent 调试表本身成为：

> 敏感信息仓库。

---

## 61. AgentStep 唯一

```text
UNIQUE(
    investigation_id,
    step_no
)
```

便于完整重放：

```text
Step 1
Step 2
Step 3
...
```

---

## 62. 故障实验室辅助表

故障实验室属于 V0.1，但不进入核心 Incident 领域。

故障场景定义：

> 优先由代码 / YAML 配置维护。

例如：

```text
redis-latency
mysql-slow-query
statistics-consumer-stop
```

不为了三个固定场景建复杂：

```text
fault_scenario
fault_action
fault_condition
fault_template
```

数据库只需要记录一次真实演练。

---

## 63. fault_experiment

字段：

```text
id

scenario_key

managed_system_id
target_resource_id

incident_id NULL

status

injected_at
reset_at

ground_truth_schema_name
ground_truth_schema_version
ground_truth_payload

error_message

created_at
updated_at
```

状态：

```text
INJECTING
ACTIVE
RESETTING
RESET
FAILED
```

---

## 64. Ground Truth 隔离

`ground_truth_payload`

保存：

> 实验真正答案。

例如：

```text
cause = REDIS_LATENCY
delay_ms = 600
```

这个字段：

**绝对不能进入 Agent Context。**

Java 构建调查上下文时：

> 不读取 FaultExperiment.ground_truth_payload。

它只供：

```text
Fault Lab
Evaluation
```

使用。

---

## 65. 表数量最终控制

V0.1 核心表：

```text
managed_system
managed_resource
data_source_connection
resource_binding
capability_binding

incident
incident_affected_resource
investigation

capability_invocation
observation
hypothesis
evidence
diagnosis
diagnosis_evidence_ref

remediation_plan
remediation_action
approval_request
action_execution

recovery_policy
recovery_verification

incident_timeline_event

agent_step_record
```

共：

**22 张。**

Fault Lab：

```text
fault_experiment
```

1 张。

总计：

**23 张。**

这个数字看上去不少，但不是因为 CRUD 堆砌。

其中：

- 5 张系统配置；
- 9 张调查与证据；
- 6 张处置恢复；
- 1 张审计；
- 1 张 AI 调试；
- 1 张实验辅助。

没有用户、组织、RBAC、消息中心、知识库之类无关表。

---

## 66. 不应该为了减少表数强行合并

例如不要把：

```text
Observation + Evidence
```

合并。

因为一个是真实事实，

一个是对事实的解释关系。

不要把：

```text
Approval + Execution
```

合并。

因为：

> 批准了不代表执行了。

不要把：

```text
Execution + Verification
```

合并。

因为：

> 执行成功不代表恢复成功。

这些拆分都是业务语义要求，不是“过度范式化”。

---

## 67. JSON 的允许范围

V0.1 JSON 只允许主要出现在：

```text
data_source_connection.config_payload

resource_binding.selector_payload

capability_invocation.request_payload
capability_invocation.response_payload

observation.payload

remediation_action.parameter_payload

action_execution.result_payload

recovery_policy.criteria_payload

recovery_verification.policy_snapshot

incident_timeline_event.payload

agent_step_record.output_payload

fault_experiment.ground_truth_payload
```

这些都有共同特点：

> 不同 Provider / Capability 的结构天然不同。

---

## 68. 不能 JSON 化的字段

这些必须是普通字段：

```text
Incident.status

Hypothesis.status

Diagnosis.conclusion_type

Evidence.relation

ApprovalRequest.status

ActionExecution.status

RecoveryVerification.status

Resource.resource_type

capability_key

时间

外键

version_no
```

否则后面：

> 查询、索引、约束、状态机

都会非常痛苦。

---

## 69. 所有 JSON 必须有 Schema

凡是核心结构化 JSON：

必须同时拥有：

```text
schema_name
schema_version
```

例如：

```text
schema_name =
cache.inspect.result

schema_version =
1
```

以后结构变化：

```text
version = 2
```

不会让历史数据失去解释方式。

---

## 70. 第一条重要事务边界：开始调查

用户点击：

> 开始调查

一个数据库事务完成：

```text
检查 Incident = CREATED

创建 Investigation

Incident:
CREATED → INVESTIGATING

写 TimelineEvent
```

全部成功：

> COMMIT。

任何一步失败：

> ROLLBACK。

不能出现：

```text
Incident = INVESTIGATING

但是 Investigation 根本不存在
```

---

## 71. 外部调用绝不能包在数据库长事务里

例如：

```text
Prometheus
LLM
Docker
```

都可能等待几秒甚至几十秒。

禁止：

```text
BEGIN TRANSACTION

SELECT ...

调用 LLM 等 40 秒

UPDATE ...

COMMIT
```

会长期占据数据库连接和锁。

必须采用：

```text
短事务
↓
外部调用
↓
短事务
```

---

## 72. CapabilityInvocation 标准事务流程

事务一：按 Incident -> Investigation 锁序完成当前run状态、Stop、deadline、预算及全部Capability Guard；
创建 RUNNING Invocation（带run_no）、当前轮计数+1、累计计数+1、Timeline，然后 COMMIT。
事务外调用 Provider；不得隐式重试。
事务二：条件更新 Invocation 终态，成功写脱敏结果与真实 Observation，失败只写错误及 Timeline。
**结果事务不再次扣预算。** 准入已提交但未能确认外部调用的崩溃仍保留已用额度，标记中断，不假称获得事实。
Recovery 调用使用另一上下文，不扣调查计数；执行核对不创建 Invocation。

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

## 73. AI Step 同样如此

事务一：

```text
创建 AgentStepRecord
```

然后调用 Python。

不持有数据库事务。

返回后事务二：

```text
记录 Step Result

校验 Intent

执行领域变化

写 Timeline
```

准入记录包括 run_no；结果事务校验原轮、Stop、Incident当前状态和记录终态，防止迟到结果跨轮写入。
Stop 与准入遵循本文件 §72 的共同锁序。COMPLETE 只接受本轮合法草稿，不拿上一轮结论冒充新结果。

---

## 74. 创建 Evidence 的事务

同一个事务：

```text
读取 Observation

读取 Hypothesis

验证：
属于同一个 Investigation

检查唯一约束

INSERT Evidence

必要时：
UPDATE Hypothesis.status

INSERT Timeline
```

统一提交。

不能出现：

> Evidence 已创建，但 Hypothesis 状态没有更新一半失败。

---

## 75. COMPLETE_INVESTIGATION 事务

这是非常关键的一次事务。

需要：

```text
锁定 Incident
锁定 Investigation

验证 Incident = INVESTIGATING

验证 Diagnosis Draft

INSERT Diagnosis

INSERT DiagnosisEvidenceReference

SUPERSEDE 旧的 ACTIVE Plan（如果存在）

Incident:
INVESTIGATING → DIAGNOSED

INSERT Timeline
```

统一提交。

该事务还检查 Draft 的 run_no 与 current_run_no 一致，冻结 Diagnosis.run_no，
并把旧未执行 Plan／待审批状态按统一生命周期处理。所有写入与 Incident 迁移一起提交。

---

## 76. 为什么新 Diagnosis 要在同事务过期旧 Plan

否则可能：

```text
Diagnosis v2 已产生
```

但是：

```text
Plan for Diagnosis v1
```

仍然 ACTIVE。

此时另一个线程可能批准并执行旧方案。

所以必须原子完成。

---

## 77. 创建审批事务

Java 已经获得合法 Remediation Proposal 后：

```text
验证 Incident = DIAGNOSED

验证 Plan 基于当前 Diagnosis

INSERT RemediationPlan

INSERT RemediationAction

INSERT ApprovalRequest(PENDING)

Incident:
DIAGNOSED → AWAITING_APPROVAL

Timeline
```

同事务。

---

## 78. 用户批准事务

用户点击：

> 批准并执行

事务中：

```text
锁 Incident

锁 ApprovalRequest

校验：
Incident = AWAITING_APPROVAL

Approval = PENDING

Plan = ACTIVE

Plan 基于当前 Diagnosis

Action Resource / Capability 仍有效

Approval:
PENDING → APPROVED

创建 ActionExecution(PENDING)

Incident:
AWAITING_APPROVAL → EXECUTING

Timeline
```

提交。

然后：

> 才调用 Docker。

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

## 79. Docker 执行不在事务里面

批准事务只创建 PENDING；Worker 条件更新 PENDING -> RUNNING 并保存已验证执行上下文后提交，才在事务外发出一次 restart。

明确失败：Execution FAILED，Plan EXECUTED，Incident DIAGNOSED，Timeline 同事务。
结果不确定：保持 RUNNING，进入有界只读 reconciliation，不重发 CHANGE。
确定成功：Execution SUCCEEDED，Plan EXECUTED，从已有 recovery_policy_snapshot 创建唯一 Verification PENDING，
计算其deadline并将 Incident EXECUTING -> VERIFYING，同事务写 Timeline；提交后派发。

成功事务不得重新查询 ACTIVE Policy 来决定是否允许保存真实执行结果。
数据库提交失败本身按既有 RUNNING 恢复路径核对，不再次发送写操作。

---

## 80. 恢复验证的采样事务与最终事务

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

## 81. 为什么事务边界是这个项目真正重要的 Java 面试点

因为这不是简单：

> Controller → Service → Mapper。

而是：

```text
数据库事务
+
远程 AI 调用
+
Prometheus
+
Redis
+
Docker
+
状态机
```

如果处理错误，很容易出现：

> 数据库说正在执行，但 Docker 根本没收到。

或者：

> Docker 已经执行成功，但 Java 请求超时，以为失败又执行一次。

所以：

```text
短事务
幂等键
状态持久化
恢复查询
```

才是真正的工程问题。

---

## 82. 执行结果不确定与有界只读核对

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

## 83. 不使用 XA / 2PC

OpsPilot 不尝试做：

```text
MySQL Transaction
+
Docker Transaction
+
Prometheus Transaction
```

这种不存在实际意义的全局事务。

采用：

> 本地事务 + 状态记录 + 幂等执行 + 事后验证。

这更加符合实际分布式系统工程。

---

## 84. 乐观锁主要使用位置

强烈建议：

```text
incident
investigation
approval_request
action_execution
recovery_verification
```

使用：

```text
lock_version
```

对于 Immutable 表：

```text
observation
evidence
diagnosis
timeline_event
```

不需要乐观锁。

因为：

> 根本不更新。

---

## 85. 数据库约束和 Java 不变量怎么分工

数据库负责容易表达的：

```text
NOT NULL

FOREIGN KEY

UNIQUE

XOR CHECK

数据类型
```

Java负责跨对象业务语义：

```text
PRIMARY Diagnosis 至少一个 SUPPORTS Evidence

Evidence 两侧属于同一 Investigation

RESOLVED 必须存在 PASSED Verification

Plan 必须基于当前 Diagnosis

未批准 Action 不能执行

UNDETERMINED 不能生成写操作 Plan
```

不要尝试把整个业务状态机写成：

> 一百条数据库 Trigger。

---

## 86. 不使用数据库 Trigger

V0.1 禁止依赖 Trigger 实现核心业务流程。

例如不要：

```text
UPDATE verification
↓ trigger
自动 UPDATE incident
```

否则：

> Java 看不到真正状态转移发生在哪里。

业务规则应该集中在：

```text
Domain / Application Service
```

数据库负责最后一道完整性保护。

---

## 87. Repository 层不能自己改变业务状态

例如禁止：

```java
incidentRepository.updateStatus(
    id,
    "RESOLVED"
);
```

被任意 Service 随便调用。

状态变化必须通过：

```text
IncidentStateTransitionService
```

或者对应 Application Service。

数据库模型虽然允许：

```text
UPDATE incident
```

业务架构必须限制调用入口。

---

## 88. 数据模型的分层归属

未来代码中可以理解为：

```text
核心业务数据：

incident
investigation
hypothesis
evidence
diagnosis
remediation...
```

---

```text
系统集成配置：

managed_system
managed_resource
data_source_connection
resource_binding
capability_binding
recovery_policy
```

---

```text
运行审计：

capability_invocation
incident_timeline_event
agent_step_record
```

---

```text
实验支持：

fault_experiment
```

这四类数据职责不同。

不要全部放：

```text
entity/
```

然后没有边界。

---

## 89. 关键索引汇总

高频故障列表：

```text
incident(status, created_at)
```

系统故障：

```text
incident(managed_system_id, status, created_at)
```

事故时间线：

```text
incident_timeline_event(incident_id, id)
```

调查能力调用：

```text
capability_invocation(investigation_id, id)
```

事故全部能力调用：

```text
capability_invocation(incident_id, id)
```

调查 Hypothesis：

```text
hypothesis(investigation_id, status)
```

Plan：

```text
remediation_plan(incident_id, status)
```

Observation：

```text
observation(investigation_id, id)

observation(recovery_verification_id, id)
```

Diagnosis：

唯一索引本身：

```text
(investigation_id, version_no)
```

已经支持当前版本查询。

---

## 90. 暂时不要给 JSON 建索引

V0.1 不需要：

```text
JSON_EXTRACT(payload...)
```

做核心业务查询。

Provider 数据主要用于：

> 展示、Evidence、AI Context。

所以不要过早增加：

```text
Generated Column
Functional JSON Index
```

真正发现性能需求再加。

---

## 91. 数据增长最大的三张表

未来增长最快：

```text
incident_timeline_event
capability_invocation
observation
```

V0.1 数据量非常小。

不做：

```text
分表
冷热分层
归档库
ClickHouse
Elasticsearch
```

但所有查询必须通过：

```text
incident_id
investigation_id
```

有明确索引。

为未来迁移保留可能。

---

## 92. 原始日志大结果不要直接塞 MySQL

大型原文不直接塞入MySQL。例如Loki日志先由Sanitizer脱敏，结构化摘要进入response_payload / Observation，
较大脱敏结果通过raw_result_ref引用受控本地目录，V0.1采用file://引用。
不引入MinIO、S3、OSS或COS；RawResultStore接口允许未来替换实现，不改变当前业务合同。
小型结果直接JSON保存。原始引用不能成为绕过脱敏的数据出口。

---

## 93. 数据完整性最高优先级约束

最终数据库层至少必须真正保证：

| 约束 | 方式 |
|---|---|
| 一事故一调查 | `UNIQUE(investigation.incident_id)` |
| Diagnosis 版本唯一 | `UNIQUE(investigation_id, version_no)` |
| Diagnosis Evidence 不重复 | `UNIQUE(diagnosis_id, evidence_id)` |
| 一个 Plan 一个 Action | `UNIQUE(remediation_plan_id)` |
| 一个 Action 一个 Approval | `UNIQUE(remediation_action_id)` |
| 一个 Action 最多一次 Execution | `UNIQUE(remediation_action_id)` |
| Execution 幂等 | `UNIQUE(idempotency_key)` |
| Verification 编号唯一 | `UNIQUE(incident_id, verification_no)` |
| Observation-Hypothesis 关系唯一 | `UNIQUE(observation_id, hypothesis_id)` |
| Invocation 上下文 XOR | `CHECK` |
| Resource key 系统内唯一 | `UNIQUE(system_id, resource_key)` |

---

## 94. 当前数据模型中刻意没有的表

没有：

```text
user
role
permission
organization
team
notification
knowledge_base
document
embedding
agent
agent_memory
prompt
model
mcp_server
plugin
workflow
```

原因不是：

> 以后永远不要。

而是：

> V0.1 三个场景根本不需要。

---

## 95. 数据库实施顺序

API 已在 [05-api.md](05-api.md) 定义；不要按 23 张表生成 23 套任意 CRUD。
Flyway 分批落地，先创建列、后按依赖增加 FK 的计划见 [08-implementation-plan.md](08-implementation-plan.md)。
本文件列出的新增字段全部是本次最终运行语义的物理落点，不新增核心表。

---

## 96. 本阶段最重要的结论

数据库不是 OpsPilot 产品设计的起点。

它只是前面已经冻结的：

```text
产品需求
↓
生命周期
↓
职责边界
↓
领域关系
```

的物理投影。

如果数据库中出现一个字段，却回答不了：

> “哪条业务规则需要它？”

那这个字段大概率不应该存在。

反过来，如果已经冻结的业务不变量：

> 无法通过数据库约束或 Java 事务可靠实现，

说明物理设计还没有完成。

这才是进入编码前做数据库设计的意义。

---

## 97. 数据库补充不变量

| ID | 冻结规则 |
|---|---|
| DB-INV-001 | CapabilityInvocation 成功／失败终态只从 RUNNING 条件更新 |
| DB-INV-002 | Incident 全部迁移验证 status + lock_version |
| DB-INV-003 | 同一 ActionExecution 最多一个关联 RecoveryVerification |
| DB-INV-004 | Evidence 创建后不可修改、不可删除 |
| DB-INV-005 | 同 Observation × Hypothesis 最多一条 Evidence |
| DB-INV-006 | Diagnosis 冻结具体 Evidence ID，后续新增不改历史依据 |
| DB-INV-007 | Ground Truth 不进入调查上下文 |

---

## 98. 运行控制字段与约束完整性检查

所有 actor 标识统一使用 VARCHAR(128)，不建立 user／role 表。每个 JSON 使用伴随 schema 字段或载荷内的 schemaName/schemaVersion；
核心业务状态、父外键、运行周期、核对计数和采样身份不能只放不可查询的无类型 JSON。

必须验证：current_run_no>=1；current_run_capability_count不超过单轮上限；
Stop时间与身份同时空或同时非空；核对次数不超过已快照上限；恢复sample_index>=1。
依赖跨表关系由Java同事务检查，简单非空／范围／XOR由MySQL再保护。不使用 Trigger。

物理 Migration 以对应 Task 为验证单位；没有实际运行 MySQL 不能将本规格称为“迁移已通过”。

---
