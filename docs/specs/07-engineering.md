# OpsPilot V0.1 工程结构与编码规范

> Status: FROZEN  
> Version: 0.1  
> Freeze revision: FINAL-FREEZE-20260925  
> 冻结日期：2026-09-25  
> 范围：单实例模块化单体、模块依赖、代码规范、派发恢复、构建与测试。  
> 本文是已合并的正式实现规格。权威清单见 [SPEC-MANIFEST.md](SPEC-MANIFEST.md)。FROZEN 表示规格定稿，不表示代码、迁移或故障实验已经通过。

## 1. 唯一权威规格基线

本目录已经完成规格合并。正式实现只读取 docs/specs/00～09 与 SPEC-MANIFEST，
不再让编码Agent解释“旧正文+补丁”的优先级。历史原稿与最终裁决存入 docs/archive，
仅用于审计，不作为并行实现合同。

导入本包后，TASK-001只完成仓库内落位与一致性检查；不要再次把旧正文覆盖回已合并文件。

---

## 2. 建立规格权威清单

`docs/specs/SPEC-MANIFEST.md` 列出00～09，全部Status:FROZEN、Version:0.1、FreezeRevision:FINAL-FREEZE-20260925。
Manifest定义阅读范围与文件职责；08明确任务，09明确真实验收，二者同样是权威规格。

代码与规格冲突时先按已冻结规则修代码。普通实现细节在当前Task解决；
只有改变业务语义、访问授权或核心状态机的真实缺陷需要显式记录决策，不能顺手扩大范围。

---

## 3. 总体工程形态

OpsPilot V0.1 使用：

### Monorepo + Modular Monolith + 独立 AI Runtime

中文：

> 单仓库、模块化 Java 单体、独立 Python AI 服务。

整体：

```text
                     Browser
                        │
                   REST / SSE
                        │
                        ▼
              ┌──────────────────┐
              │ OpsPilot Server  │
              │ Java/Spring Boot │
              └─────────┬────────┘
                        │
             ┌──────────┼─────────────┐
             │          │             │
             ▼          ▼             ▼
          MySQL    Infrastructure   AI Runtime
                    Providers        Python
                        │              │
          ┌─────────────┼───────┐      ▼
          ▼             ▼       ▼     LLM
     Prometheus       Redis    Docker
     Loki             MySQL
```

注意：

Java 虽然拆 Maven Module，

最终：

> 仍然打成一个 Spring Boot 应用。

不是：

```text
Incident Service
Evidence Service
Approval Service
Recovery Service
```

五六个微服务。

---

## 4. 为什么 V0.1 必须是模块化单体

因为当前真正复杂的是：

```text
业务一致性
状态机
事务
AI边界
外部操作
恢复验证
```

不是：

> 服务之间的网络通信。

如果现在拆微服务：

```text
IncidentService
↓ HTTP
InvestigationService
↓ Kafka
EvidenceService
↓ RPC
ExecutionService
```

只会把一个可以用本地事务保证的问题，

变成：

> 分布式一致性问题。

因此：

> **模块用于约束代码边界，不用于制造部署边界。**

---

## 5. 技术基线

| 层 | 冻结基线 |
|---|---|
| Java | Java 21；Maven 3.9+；Spring Boot 4.1.x；Spring MVC；RestClient；SSE |
| 数据访问 | MyBatis Core 3.x；MyBatis-Spring 4.x；mybatis-spring-boot-starter 4.x；MySQL >=8.0.16；Flyway |
| 序列化与API | Jackson；Bean Validation；springdoc-openapi 3.x；OpenAPI |
| Python | Python 3.13；FastAPI；Pydantic v2；OpenAI-compatible LLM Client；内部HTTP/JSON |
| Web | React；TypeScript；Vite；公开类型由Java/OpenAPI派生 |

这是项目选定的主版本基线，不是“最新版本已验证”的声明。
TASK-003锁定兼容的具体patch版本和构建依赖，禁止把x、LATEST或动态版本放入正式构建文件。
MyBatis三项是不同组件，不能写成Core 4.x。Python不引入LangGraph、LangChain Agent、Celery、Redis Queue或自己的数据库。

---

## 6. 明确排除的技术

V0.1 不引入：

```text
Spring Cloud

Nacos

Feign

Kafka

RabbitMQ

Quartz

Spring Batch

Spring StateMachine

Spring Modulith

JPA / Hibernate

MyBatis-Plus

Redis 作为 OpsPilot 状态库

Elasticsearch 作为 OpsPilot 数据库

LangGraph

MCP

GraphRAG

Vector DB
```

这不是说这些技术不好。

而是：

> 当前没有被冻结的业务需求需要它们。

---

## 7. 为什么选择原生 MyBatis

OpsPilot 大量关键操作类似：

```text
WHERE status = ?
AND lock_version = ?
```

或者：

```text
INSERT immutable record
```

以及：

```text
明确 JOIN
明确 FOR UPDATE
明确 UNIQUE constraint
```

这类系统更适合：

> 显式 SQL。

因此使用：

```text
MyBatis Mapper Interface
+
XML SQL
```

而不是：

```text
BaseMapper<T>
ServiceImpl<T>
lambdaUpdate()
```

---

## 8. 为什么不用 MyBatis-Plus

不是能力问题。

而是它非常容易诱导编码 Agent 写出：

```text
incidentService.updateById(...)
```

然后绕开：

```text
StateTransitionService

expectedVersion

expectedStatus
```

而 OpsPilot 最不能允许的：

恰恰就是：

> 任意 CRUD 修改业务状态。

所以 V0.1：

### 禁止 MyBatis-Plus。

---

## 9. 仓库最终结构

```text
opspilot/
├── README.md
├── AGENTS.md
├── CLAUDE.md
├── docs/
│   ├── specs/
│   │   ├── SPEC-MANIFEST.md
│   │   ├── 00-product.md
│   │   ├── 01-lifecycle.md
│   │   ├── 02-java-ai-boundary.md
│   │   ├── 03-domain-model.md
│   │   ├── 04-database.md
│   │   ├── 05-api.md
│   │   ├── 06-capability.md
│   │   ├── 07-engineering.md
│   │   ├── 08-implementation-plan.md
│   │   └── 09-acceptance.md
│   └── archive/
├── backend/
│   ├── opspilot-domain/
│   ├── opspilot-application/
│   ├── opspilot-infrastructure/
│   ├── opspilot-web/
│   └── opspilot-boot/
├── ai-runtime/
├── web/
├── contracts/
│   └── ai-runtime/v1/
├── deploy/
│   ├── docker-compose.yml
│   └── demo/docker-compose.yml
└── scripts/
```
基础core Compose和完整Demo Compose职责分开；具体启动脚本在工程任务中创建，不冒充本次文档交付已有业务工程。

---

## 10. Java 使用 Maven 多模块，但只有一个应用

正式模块：

```text
opspilot-domain

opspilot-application

opspilot-infrastructure

opspilot-web

opspilot-boot
```

共：

### 5 个。

不继续拆成十几个。

---

## 11. 模块依赖方向

必须固定：

```text
              opspilot-domain
                     ▲
                     │
             opspilot-application
                ▲             ▲
                │             │
        opspilot-web   opspilot-infrastructure
                ▲             ▲
                └──────┬──────┘
                       │
                 opspilot-boot
```

也就是：

```text
domain
↑
application
↑        ↑
web   infrastructure
 \      /
   boot
```

---

## 12. 绝对禁止反向依赖

#### domain

不能依赖：

```text
Spring
MyBatis
HTTP
Docker
Redis Client
Jackson Web DTO
```

---

#### application

不能依赖：

```text
Controller
MyBatis Mapper
Prometheus Client
Docker Client
FastAPI DTO
```

---

#### web

不能依赖：

```text
MyBatis Mapper
Docker Provider
Redis Provider
```

---

#### infrastructure

不能依赖：

```text
Controller
HTTP Response DTO
```

---

#### boot

是：

> Composition Root。

也就是：

> 最终把所有实现装配起来的地方。

它可以同时依赖：

```text
web
infrastructure
```

---

## 13. domain 模块到底放什么

`opspilot-domain`

只放：

> 与 Spring、数据库、HTTP 无关的业务语言和纯规则。

例如：

```text
incident/
investigation/
capability/
remediation/
recovery/
system/
timeline/
```

---

## 14. domain 中的典型代码

例如：

```text
IncidentStatus

DiagnosisConclusionType

EvidenceRelation

HypothesisStatus

ApprovalStatus

ExecutionStatus

VerificationStatus
```

---

纯规则：

```text
IncidentTransitionPolicy

DiagnosisInvariantPolicy

ApprovalDecisionPolicy

RecoveryPredicateEvaluator

CapabilityPolicy
```

---

值对象：

```text
IncidentKey

CapabilityKey

TimeWindow

RecoverySampling

RecoveryPredicate
```

---

## 15. domain 不做“DDD 表演”

不要求：

```text
AggregateRoot<T>

DomainEntity<T>

DomainEventBase

ValueObjectBase
```

这种抽象。

也不强制：

> 每张表变成一个“富领域实体”。

真正原则是：

> 业务规则必须有明确位置。

而不是：

> 类名看起来像 DDD。

---

## 16. application 模块职责

`opspilot-application`

负责：

> 用例编排。

例如：

```text
创建 Incident

开始调查

停止调查

处理 Agent Intent

完成 Diagnosis

请求 Remediation

批准 Action

执行 Action

运行 Recovery Verification
```

---

## 17. Application Service 示例

例如：

```text
IncidentApplicationService

InvestigationApplicationService

RemediationApplicationService

ApprovalApplicationService

RecoveryApplicationService

FaultLabApplicationService
```

---

不是：

```text
IncidentManager

CommonManager

BizHelper

DataService
```

这种含义模糊的名字。

---

## 18. Application 层拥有端口

它定义自己需要什么。

例如：

```text
IncidentRepository

InvestigationRepository

DiagnosisRepository

TimelineRepository
```

以及外部能力：

```text
AiDecisionPort

CapabilityProviderPort

ActionExecutorPort

RawResultStore

WorkDispatcher

SecretResolver
```

真正实现：

> 在 infrastructure。

这就是依赖倒置。

---

## 19. application 可以使用 Spring 吗

可以。

V0.1 不追求：

> “Application 层必须 100% 无框架”。

允许：

```text
@Service

@Transactional
```

因为这是一个 Spring Boot 项目。

但是不能因为用了 Spring：

> 就让 Application 层直接知道 Docker / MyBatis / HTTP。

---

## 20. infrastructure 模块职责

`opspilot-infrastructure`

负责：

```text
MyBatis

MySQL Persistence

AI Runtime HTTP Client

Prometheus

Loki

Redis

业务 MySQL Inspect

Docker

本地 Raw Result Store

Secret Resolver
```

---

建议内部：

```text
persistence/
ai/
provider/
execution/
storage/
secret/
config/
```

---

## 21. persistence 结构

例如：

```text
infrastructure/
└── persistence/
    └── mybatis/
        ├── incident/
        ├── investigation/
        ├── remediation/
        ├── recovery/
        ├── system/
        └── timeline/
```

每组内部：

```text
Mapper
Row / PO
RepositoryImpl
```

---

## 22. Mapper 不等于 Repository

例如：

```text
IncidentMapper
```

只负责 SQL。

而：

```text
MyBatisIncidentRepository
```

实现：

```text
IncidentRepository
```

Application 层永远依赖：

```text
IncidentRepository
```

而不是：

```text
IncidentMapper
```

---

## 23. 为什么需要 Repository 这一层

因为：

```text
SELECT
UPDATE
INSERT
```

只是持久化细节。

应用层真正关心的是：

```text
findIncident(...)

compareAndSetStatus(...)

findLatestDiagnosis(...)

saveEvidence(...)
```

而不是：

```text
mapper.selectById(...)
```

---

## 24. 查询页面允许专门 Query Model

不要为了展示 Incident 列表：

```text
加载 12 个领域对象
↓
组装
↓
再查询 8 次数据库
```

读取页面允许设计：

```text
IncidentQueryRepository

TimelineQueryRepository

InvestigationDetailQueryRepository
```

直接执行：

> 面向页面的 SQL Projection。

但是：

### Query 路径永远不能修改业务状态。

---

## 25. 这不是 CQRS 系统

只是：

```text
写路径
强调业务不变量

读路径
强调查询效率
```

不引入：

```text
独立 Read Database

Kafka Projection

Event Store
```

---

## 26. web 模块职责

`opspilot-web`

只负责：

```text
Controller

Request DTO

Response DTO

Validation

Exception Mapping

SSE Endpoint

OpenAPI
```

---

Controller：

```text
IncidentController
IncidentActionController
ApprovalController
RecoveryController
TimelineController
SystemController
FaultLabController
```

---

## 27. Controller 禁止做什么

禁止：

```text
@Autowired Mapper

@Autowired DockerClient

@Autowired AiRuntimeClient
```

禁止：

```java
if (incident.getStatus() == ...) {
    mapper.updateStatus(...);
}
```

Controller 只做：

```text
HTTP
↓
Command
↓
Application Service
↓
Response
```

---

## 28. DTO 和领域对象分开

例如：

```text
StartInvestigationRequest
```

不是：

```text
Investigation
```

例如：

```text
IncidentDetailResponse
```

不是：

```text
IncidentEntity
```

禁止直接序列化数据库 Row。

---

## 29. Java DTO 优先使用 record

例如：

```java
public record StartInvestigationRequest(
    long expectedVersion
) {}
```

以及：

```text
Value Object
Internal immutable result
```

都优先使用：

```text
record
```

真正存在状态变化的对象：

> 再使用普通 class。

---

## 30. 不使用 Lombok

V0.1 建议：

### 项目级不引入 Lombok。

原因：

Java 21 已经有：

```text
record
```

对于真正领域对象：

显式代码可读性更高。

对于 AI Coding：

> 少一层生成代码，也更容易 Review。

---

## 31. boot 模块

`opspilot-boot`

只负责：

```text
SpringBootApplication

Application Configuration

Bean Composition

application.yml

Flyway Bootstrap

Runtime Startup Recovery
```

---

不会在：

```text
OpsPilotApplication.java
```

里面写业务逻辑。

---

## 32. 包命名原则

基础包建议：

```text
io.github.ismoyuan.opspilot
```

不要采用：

```text
controller/
service/
serviceImpl/
mapper/
entity/
utils/
```

全部混在根目录的传统后台项目结构。

---

应该围绕领域语言。

例如 domain：

```text
opspilot.domain.incident
opspilot.domain.investigation
opspilot.domain.capability
opspilot.domain.remediation
opspilot.domain.recovery
opspilot.domain.system
```

---

application：

```text
opspilot.application.incident
opspilot.application.investigation
opspilot.application.remediation
opspilot.application.recovery
```

---

## 33. 禁止 common / util 垃圾桶

禁止一遇到不知道放哪就写：

```text
common/
utils/
helpers/
base/
```

工具类只有在：

> 真的与领域无关且被多处稳定复用

时才存在。

例如：

```text
CanonicalJsonWriter
```

可以存在。

而：

```text
IncidentUtils
```

通常说明设计有问题。

---

## 34. 状态机不引入 Spring StateMachine

已经只有：

```text
8 个状态
```

完全没必要增加框架。

使用：

```text
IncidentTransitionPolicy
```

定义：

```text
from
trigger
to
```

纯规则。

Application Service：

负责加载现实数据并调用 Guard。

---

## 35. 绝对禁止通用 updateStatus

不存在：

```java
incidentRepository.updateStatus(id, status);
```

这种公共接口。

合法方式必须类似：

```text
transition(
    incidentId,
    expectedStatus,
    expectedVersion,
    targetStatus
)
```

数据库：

```text
WHERE id = ?
AND status = ?
AND lock_version = ?
```

---

## 36. 事务到底放在哪里

冻结：

### Application Use Case 边界。

例如：

```text
startInvestigation()

completeInvestigation()

approveAction()

finishExecution()

finishVerification()
```

这些方法：

> 可以开启事务。

---

不在：

```text
Controller
Mapper
Provider
AI Client
```

开启业务事务。

---

## 37. 最大事务原则

一个事务：

> 只包数据库一致性操作。

绝不包含：

```text
LLM 请求

Prometheus

Redis

业务 MySQL

Docker

Loki

sleep(10s)
```

---

## 38. 外部调用统一模式

所有外部调用：

```text
短事务 A
↓
保存当前执行状态
↓
COMMIT

外部调用

短事务 B
↓
保存结果
↓
推进状态
↓
COMMIT
```

这是整个 Java 工程最重要的模式之一。

---

## 39. 不要使用 private @Transactional

禁止：

```java
public void foo() {
    bar();
}

@Transactional
private void bar() {}
```

这种依赖 Spring Proxy 却实际不生效的写法。

跨事务阶段：

> 拆成明确 Application Bean。

---

## 40. Orchestrator 不开启长事务

`InvestigationOrchestrator`

本身：

```text
不加 @Transactional
```

它负责：

```text
调用短事务 Service

调用 AI

调用 Capability

再次调用短事务 Service
```

它是：

> 流程编排器。

不是：

> 巨型事务 Service。

---

## 41. InvestigationOrchestrator 主循环

```text
捕获 investigationId + current_run_no
-> 准备本轮上下文
-> 短事务：锁Incident/Investigation，执行最后Guard，登记RUNNING AgentStep
-> 提交后调用Python，等待不超过step timeout与run剩余时间
-> 结果短事务：校验Step/run/状态/Stop，保存审计并处理一个主Intent
-> 若REQUEST_CAPABILITY：另一次受控准入短事务+外部Provider+结果短事务
-> 下一次Step或确定性Diagnosis收束
```
上下文可以在事务外准备，但不得以准备时的旧状态代替最后准入校验。
本轮退出、Stop或run变更后不准入新工作；进程重启恢复原run，不调用resumeInvestigation刷新预算。

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

## 42. run Guard 与原子准入

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

## 43. 结果回传的轮号与停止校验

结果处理仍须校验原run和当前状态。已经Stop：
同轮在途合法COMPLETE可提交；其他Intent只记录，不展开新的自动链。
已切换run或取消：旧AI提议不能修改Hypothesis/Evidence/Diagnosis或新轮计数。
旧Provider真实返回保留在原Invocation/Observation中，不能在新轮自动触发解释或结束状态。
结果拒绝必须可审计；不需要保存模型完整思维链。

---

## 44. 不用 @Async 隐藏业务流程

V0.1 不建议在业务代码到处：

```java
@Async
```

因为它会让：

```text
线程
异常
生命周期
调用链
```

变得不透明。

使用明确：

```text
WorkDispatcher
```

接口。

---

## 45. WorkDispatcher

Application 定义：

```text
dispatchInvestigation(incidentId)

dispatchActionExecution(executionId)

dispatchRecoveryVerification(verificationId)
```

Infrastructure 实现：

```text
InProcessWorkDispatcher
```

使用一个受控：

```text
TaskExecutor / ExecutorService
```

调查派发携带期望runNo；Execution与Verification派发携带持久化身份。
所有触发器、afterCommit和补派发最终进入同一个受控Worker入口，不能绕过条件更新。

---

## 46. V0.1 不需要 MQ

因为当前：

```text
单 Java 实例

演示级并发

工作事实都已经持久化到 MySQL
```

所以没必要为了：

```text
“异步”
```

就引入 Kafka/RabbitMQ。

---

## 47. 后台执行器必须限制并发

不能：

```text
newCachedThreadPool()
```

无限开线程。

配置：

```text
worker.maxConcurrency
```

例如 Demo：

```text
8
```

具体值配置化。

不写成魔法数字。

---

## 48. 一 Incident 单飞

同一个 Incident：

> 同一 JVM 内只能存在一个 Investigation Worker。

设计：

```text
SingleFlightRegistry<IncidentId>
```

用于阻止重复调度。

数据库：

```text
status
lock_version
```

仍然是最终保护。

单飞保护覆盖Investigation、Execution及Verification各自的工作入口。
Registry的释放必须验证当前拥有者token，不能仅用remove(incidentId)误删后来Worker。
旧runWorker结束时，不自动吃掉新run的补派发意图；数据库扫描可再次唤醒。

---

## 49. SingleFlight 不是业务事实

`SingleFlightRegistry`

只是：

> JVM 运行优化。

它丢了没有关系。

不能因为：

```text
Map 中没有 Incident
```

就认为：

> Incident 不在调查。

事实仍然来自 MySQL。

---

## 50. V0.1 明确为单 Java 实例

这一点必须写清楚。

当前不支持：

```text
Java Instance A
Java Instance B
```

同时抢同一个调查任务。

因此不建设：

```text
Redis Distributed Lock

DB Lease Scheduler

Leader Election
```

未来真正横向扩展：

> 再引入数据库 Lease / Queue。

---

## 51. 启动恢复与存活期间补派发

#### 数据库工作事实与单实例补派发

`WorkDispatcher` 只负责进程内唤醒，不是 durable queue。提交后的事件尽快派发；
同一数据库工作也必须能由启动扫描及轻量补派发恢复。V0.1 不新增MQ、Outbox、Lease或分布式调度表。
本包补齐的默认补派发间隔为5秒，可配置；线程池满时记录拒绝，不丢弃已提交业务事实。

| 数据库事实 | 启动恢复 | 存活期间补派发 |
|---|---|---|
| INVESTIGATING，未Stop | 保留run/预算/deadline，处理旧进程残留后调度 | 没有相应JVM Worker时唤醒同run；到期则收束 |
| INVESTIGATING，已Stop | 不调用AI，按本轮事实收束 | 确保停止收束工作可达 |
| ActionExecution PENDING | 重新派发，只有条件更新获胜者取得CHANGE准入 | 未有Worker则补派发 |
| ActionExecution RUNNING | 不重发CHANGE，进入有界只读reconciliation | 无Worker且结果不明时只恢复核对分支 |
| Verification PENDING | 原快照、原deadline，派发或按到期结果收束 | 未有Worker则补派发 |
| Verification RUNNING | 按criterion/sample身份恢复；不刷新时间 | 无Worker则恢复剩余未准入工作 |
| 终态／稳定状态 | 不派发 | 不派发 |

启动扫描能认定单实例旧进程记录已中断；周期扫描不能把仍由当前JVM Worker拥有的RUNNING请求标记成中断。
Worker异常退出应在失败事务及finally释放路径中记录事实；finally失败后仍由扫描发现无归属工作并安全收束。
应用部署严格单实例，不允许滚动发布期间两个Java实例同时拥有控制权。

SingleFlight按工作类型及业务ID识别拥有者，Worker捕获runNo/执行身份；
释放用拥有者token条件删除，旧Worker不能移除新Worker的登记。
Dispatch去重只合并唤醒，不删除数据库PENDING意图。拒绝、合并与重启不刷新run或核对预算。

---

## 52. 恢复 INVESTIGATING

恢复INVESTIGATING时保留current_run_no、current_run_started_at、Stop和所有计数。
先终结旧进程RUNNING Step/只读Invocation的中断记录，之后按deadline、Stop和预算判断：
可继续则派发原run；否则确定性创建本轮Diagnosis并迁移DIAGNOSED。
不调用resumeInvestigation，不把服务重启当用户重新授权。

---

## 53. Stop Requested 后 Java 恰好崩溃

启动时看到INVESTIGATING且stop_requested_at非空，不再准入AI或Capability。
使用该run已取得的合法草稿收束；没有则新增UNDETERMINED/USER_STOPPED。
历史Diagnosis不是本轮草稿，不能仅复用旧结论完成一次新的调查。

---

## 54. 残留 RUNNING AgentStepRecord

如果 Java 在调用 AI 时崩溃：

数据库可能留下：

```text
AgentStepRecord = RUNNING
```

启动恢复时：

标记：

```text
FAILED

errorCode =
PROCESS_INTERRUPTED
```

然后可以继续下一轮。

该失败：

> 不应被当成 LLM 本身连续失败。

否则 Java 自己重启一次可能直接把：

```text
consecutive_ai_failure_count
```

烧满。

---

## 55. 残留 RUNNING CapabilityInvocation

只读 Capability：

如果进程崩溃时仍为：

```text
RUNNING
```

恢复时：

```text
FAILED

PROCESS_INTERRUPTED
```

不制造 Observation。

后续 Agent 如仍有价值：

> 可以再次请求。

---

## 56. Capability Budget 扣减点

全部Guard在同一准入短事务内完成，创建带run_no的RUNNING Invocation，
current_run_capability_count与capability_call_count同时+1，COMMIT后才调用Provider。
失败与中断不退还已准入预算；结果事务不再加计数。Duplicate/非法参数/无Provider在准入前拒绝，不建记录、不扣预算。
安全预算是准入记账，不等于远端接收证明。

---

## 57. Duplicate 指纹与查询范围

指纹为 investigationId + capabilityKey + resourceId + schemaName + schemaVersion + canonical(arguments)。
**run_no 不加入指纹**：Continue不会让刚完成的同一请求立即绕过短时保护，所有在途调用也仍受保护。
检查范围为该 Investigation 同指纹全部PENDING/RUNNING，以及finished_at距今小于30秒的终态调用；
不以created_at代替finished_at，不因历史总数可超过12而无限制加载全表。

Duplicate Guard 与最后一次状态／预算检查、Invocation登记在同一准入事务内完成。
拒绝CAPABILITY_DUPLICATE_REQUEST，不建Invocation、不扣预算；返回给当前run的结构化反馈促使改选或正常收束。
30秒是可配置默认保护窗口，范围仅调查上下文；Recovery按样本唯一身份调度。

---

## 58. Canonical JSON

必须只有一个专门实例：

```text
CanonicalJsonWriter
```

保证：

```text
字段稳定排序
Map Key 稳定排序
统一 null 语义
```

禁止各模块：

```text
new ObjectMapper()
```

自己算一套指纹。

schemaName/schemaVersion参与指纹。字段顺序、空对象、默认值和null规范由同一个CanonicalJsonWriter统一；
不要在Python和各Java模块各算一套。run_no不是指纹组成，防止Continue绕过刚完成的请求保护。

---

## 59. Capability 执行内部结构

建议：

```text
CapabilityExecutionService
        │
        ├── CapabilityRegistry
        ├── CapabilityBindingRepository
        ├── ProviderResolver
        ├── DuplicateGuard
        ├── Sanitizer
        ├── ObservationExtractor
        └── RawResultStore
```

---

## 60. Capability Registry 放 domain/application

Registry 表达的是：

```text
哪些 Capability 存在
支持什么 ResourceType
OBSERVE / CHANGE
Risk
Approval
Schema
Timeout
```

这是：

> OpsPilot 程序能力定义。

不是基础设施实现。

因此不能放：

```text
provider/prometheus/
```

里面。

---

## 61. Provider SPI

Infrastructure 内部统一语义：

```text
Capability
↓
ProviderResolver
↓
Provider
```

例如：

```text
metrics.query
↓
PrometheusMetricsProvider
```

---

```text
logs.search
↓
LokiLogsProvider
```

---

```text
service.inspect
↓
DockerServiceInspectProvider
```

---

## 62. Provider 不暴露原生命令

Provider 接收：

> 已解析完成的强类型请求。

例如：

```text
MetricsQueryRequestV1
```

而不是：

```text
String query
```

Docker：

不是：

```text
String command
```

---

## 63. SecretResolver

Credential 解析通过：

```text
SecretResolver
```

Application / Domain：

只认识：

```text
credentialRef
```

Infrastructure V0.1 实现：

```text
EnvironmentSecretResolver
```

支持：

```text
env://XXX
```

V0.1 不开发：

```text
Vault Client
AWS Secrets Manager
```

---

## 64. ActionExecution Worker

Worker读取已提交PENDING Execution及恢复快照，完成受信目标解析／授权检查；
外部只读解析在事务外，最终准入事务再次核对身份和绑定并条件更新PENDING -> RUNNING。
唯一获胜Worker在事务外派发一次restart；明确失败、成功、不确定走各自完成路径。
创建时已缺Policy的请求根本不能产生可执行PENDING；不能等restart后才首次选Policy。

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

## 65. Docker 操作不能通过 shell

正式禁止：

```java
Runtime.exec("docker restart ...")
```

以及：

```text
ProcessBuilder
docker CLI
bash
sh
```

使用：

> Docker Engine API Java Client。

这样 AI 永远接触不到 Shell。

---

## 66. Execution 中断与有界只读核对

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

## 67. Execution Reconciliation 位置

放在：

```text
ActionExecutionRecoveryService
```

而不是：

```text
InvestigationOrchestrator
```

因为这是：

> 执行一致性问题。

不是：

> AI 调查问题。

ActionExecutionRecoveryService拥有显式核对预算，不通过Investigation CapabilityExecutionService假造第三种上下文。
同executionId单飞，每次尝试先登记计数再inspect；重启只恢复剩余次数，不重新发送CHANGE。

---

## 68. 恢复验证执行合同

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

## 69. Recovery 的 sleep 不在事务中

例如：

```text
queue.inspect
↓
sleep 10s
↓
queue.inspect
```

整个 10 秒期间：

> 没有数据库事务。

每一次 Capability Invocation：

单独持久化。

---

## 70. Verification 中断恢复

恢复只依据policy_snapshot、deadline_at以及带criterion_key/sample_index的Invocation。
PENDING可以补派发；RUNNING中断样本登记FAILED/PROCESS_INTERRUPTED，不覆盖成新采样。
成功且仍有效的样本复用；未准入样本在原deadline及间隔约束允许时继续。
UNKNOWN不立即短路，仍可收集后续明确FALSE；最后以FAILED优先矩阵终结。
过期样本不得用于TRUE或FALSE，不通过重新填时间刷新有效期。

---

## 71. SSE 工程实现

使用：

```text
Spring MVC SSE
```

不为了 SSE：

> 把整个后端改成 WebFlux。

因为数据库和 MyBatis 本身：

```text
Blocking
```

混合两种编程模型没有收益。

---

## 72. SSE 只发送 COMMIT 后的数据

SSE只表达已经COMMIT的数据库事实。所有同Incident Timeline追加先获取该Incident行锁，
避免自动增长ID先分配后提交的乱序导致游标漏读。afterCommit只通知需要补读，
SSE发送端从数据库按id顺序发送，不按回调顺序直接发送可能过时的载荷。
前端先GET一致性Snapshot及lastTimelineEventId，再追赶其后的事件，event id去重。

---

## 73. 不需要 Transactional Outbox

V0.1不建设Transactional Outbox。Timeline为持久审计与断线补读来源，JVM Hub只持有连接。
需要防止“读完历史才订阅实时”的窗口；按05-api的注册/补读合同实现。
连接重连、进程重启和afterCommit通知丢失不丢业务事实；不要把SSE投递当作数据库提交成功的条件。

---

## 74. SSE Hub 只是内存连接管理器

类似：

```text
IncidentSseHub
```

保存：

```text
incidentKey
→ emitters
```

Java重启：

> 连接全部断掉没有关系。

浏览器：

> 自动重连。

事实不会丢，

因为 Timeline 在 MySQL。

---

## 75. AI Runtime 工程结构

建议：

```text
ai-runtime/
├── pyproject.toml
│
├── src/
│   └── opspilot_ai/
│       ├── main.py
│       ├── config.py
│       │
│       ├── api/
│       │   ├── health.py
│       │   ├── investigation.py
│       │   └── remediation.py
│       │
│       ├── protocol/
│       │   └── v1/
│       │
│       ├── investigation/
│       │   ├── service.py
│       │   ├── context.py
│       │   └── prompts/
│       │
│       ├── remediation/
│       │   ├── service.py
│       │   └── prompts/
│       │
│       └── llm/
│           ├── client.py
│           └── openai_compatible.py
│
└── tests/
```

---

## 76. Python 禁止出现的目录

不要出现：

```text
repositories/

database/

redis/

prometheus/

docker/

tools/
```

因为 Python：

> 没有这些权力。

---

## 77. Python 的最重要接口

逻辑上只有：

```text
InvestigationDecisionService

RemediationDraftService

LlmClient
```

足够。

不要：

```text
AgentManager
AgentExecutor
ToolRegistry
MemoryManager
WorkflowEngine
```

第一版没有必要。

---

## 78. Python Prompt 必须版本化

例如：

```text
investigation-v1
remediation-v1
```

AgentStepRecord：

保存：

```text
promptTemplateVersion
```

但不保存：

> 完整 Chain of Thought。

---

## 79. 不持久化模型思维链

数据库只保存：

```text
结构化 Intent
purpose
reason
Diagnosis Draft
Remediation Draft
```

不保存：

```text
隐藏推理过程
```

也不在 UI：

> 展示所谓完整模型思维链。

---

## 80. Java / Python 契约谁说了算

Internal Protocol 不能是：

```text
Java 有一份 DTO

Python 又手写一份

然后希望两边永远一致
```

必须存在语言中立契约：

```text
contracts/ai-runtime/v1/
```

---

## 81. Internal Contract 内容

至少：

```text
investigation-step-request.schema.json

investigation-step-response.schema.json

remediation-draft-request.schema.json

remediation-draft-response.schema.json
```

以及：

```text
fixtures/
```

包含合法样例。

---

## 82. 两边依然保留强类型

Java：

```text
record / sealed interface
```

Python：

```text
Pydantic Model / discriminated union
```

JSON Schema：

> 是协议事实。

不是为了重新使用：

```text
Map<String, Object>
```

---

## 83. 不急着做跨语言代码生成

V0.1 不增加复杂：

```text
JSON Schema
→ Java codegen
→ Python codegen
```

链。

采用：

```text
Canonical Schema
+
Java typed model
+
Python typed model
+
Contract Fixture Tests
```

足够可靠。

---

## 84. Contract Test

同一份：

```text
fixture
```

必须：

#### Java

能够：

```text
deserialize
validate
serialize
```

---

#### Python

能够：

```text
Pydantic validate
serialize
```

如果一边通过、一边失败：

> CI 失败。

---

## 85. Public API 契约

公开：

```text
/api/v1/**
```

仍然由 Java 主服务拥有。

使用：

```text
springdoc OpenAPI
```

产生 OpenAPI。

前端：

> 从 OpenAPI 生成 TypeScript 类型。

不把 Python 协议和浏览器 API 混到一起。

---

## 86. AI HTTP Client 不做透明自动重试

禁止：

```text
HTTP client 自动 retry 3 次
```

导致数据库：

> 看不出模型究竟请求了几次。

每一次 AI 调用：

都应该对应：

```text
AgentStepRecord
```

重试：

> 由 InvestigationOrchestrator 显式决定。

---

## 87. Provider 同样不做隐藏 Retry

冻结：

```text
一次 CapabilityInvocation
=
一次真实 Provider Attempt。
```

如果需要重新查：

> 新建 Invocation。

这样审计完全可见。

明确例外只有ActionExecution的有界只读reconciliation：它有自己的持久化计数、时间限制和审计，
不是普通Provider对同Invocation进行隐藏重试。CHANGE仍然禁止重放。

---

## 88. 配置必须强类型

Java：

```text
@ConfigurationProperties
```

例如：

```text
InvestigationProperties

AiRuntimeProperties

WorkerProperties

CapabilityTimeoutProperties

RawStorageProperties
```

---

禁止：

```java
@Value("${xxx}")
```

散落几十个类。

本次合并新增配置必须强类型绑定：dispatcher.recoveryScanIntervalSeconds=5；
execution.maxReconciliationAttempts=3、reconciliationIntervalSeconds=5、reconciliationTimeoutSeconds=5、
reconciliationMaxDurationSeconds=60；Recovery的maxDuration/maxSampleAge/maxGap来自Policy快照。
这些是本次实施默认值，调整需记录依据，不更改已创建执行的快照或期限。

---

## 89. application.yml 不保存 Secret

仓库允许：

```text
timeout
port
default budget
directory
```

禁止：

```text
LLM_API_KEY

MYSQL_PASSWORD

REDIS_PASSWORD

Docker credential
```

提交进 Git。

---

## 90. 配置层级

建议：

```text
application.yml

application-local.yml

application-test.yml
```

Secret：

```text
Environment Variable
```

Docker Compose：

> 从 `.env` 注入。

`.env`：

必须：

```text
.gitignore
```

---

## 91. Flyway 是唯一 Schema 管理入口

禁止：

```text
应用启动时自动 create table

MyBatis 自动建表

手工进 MySQL 改结构但不写 migration
```

任何 Schema 修改：

> 必须 Flyway。

---

## 92. Migration 按业务批次

不要：

```text
23 张表
=
23 个毫无意义 migration
```

可以：

```text
V001__create_system_integration_tables.sql

V002__create_incident_investigation_tables.sql

V003__create_remediation_recovery_tables.sql

V004__create_runtime_audit_tables.sql

V005__create_fault_lab_table.sql

V006__add_constraints_and_indexes.sql
```

具体拆分在 Task 阶段决定。

TASK-068先创建ActionExecution，TASK-074创建Policy/Verification并回填Execution->Policy等必要FK；
TASK-075/076完成Codec与合法Seed后才让TASK-067/069的真实批准路径可用。
TASK-021的恢复外键可先建列，在TASK-074增加最终FK；终版不得遗留未约束字段。

---

## 93. Demo Seed 与 Schema 分开

ShortLink Demo 配置：

不混进核心 Schema migration。

例如：

```text
db/migration/
```

核心结构。

```text
db/demo/
```

Demo Seed。

Production profile：

> 不加载 Demo Seed。

---

## 94. Raw Result Store

定义：

```text
RawResultStore
```

Application Port。

V0.1 Infrastructure：

```text
LocalFileRawResultStore
```

保存：

> 已脱敏结果。

---

## 95. Raw Result 路径

例如：

```text
/data/raw/
  INC-001/
    invocation-81.json.gz
```

数据库：

```text
raw_result_ref =
file://...
```

不引入 MinIO。

---

## 96. JSON Payload 仍然必须有强类型 Codec

数据库允许 JSON。

Java 不能因此使用：

```text
Map<String,Object>
```

到处传。

设计：

```text
SchemaCodecRegistry
```

通过：

```text
schemaName
schemaVersion
```

关联具体：

```text
Class / Codec
```

---

例如：

```text
queue.inspect.result / 1
↓
QueueInspectResultV1
```

---

## 97. Recovery Policy Codec 独立

```text
recovery.policy.criteria / 1
```

必须解析成：

```text
RecoveryPolicyCriteriaV1
```

ACTIVE 之前完整校验。

禁止：

```text
运行时临时拿 JsonNode 猜字段。
```

Codec覆盖criterionKey、受控字段投影、采样身份、TRUE/FALSE/UNKNOWN与Snapshot的时间字段；
V0.1只支持明确的schemaVersion=1。未知版本拒绝，不用兼容性猜测解释历史数据。
执行快照与验证快照采用同一类型，不维护第二套谓词算法。

---

## 98. 日志规范

每一条重要工程日志至少尽可能带：

```text
requestId

correlationId

incidentKey

operation
```

后台 Worker：

没有 HTTP requestId 时：

> 自己生成 correlationId。

---

## 99. 禁止日志输出

```text
Credential

Authorization

Cookie

LLM API Key

完整 Prompt

完整原始日志

数据库密码
```

---

## 100. Provider 日志

记录：

```text
Capability Key

Resource Key

Provider Type

Duration

Status

Error Code
```

不默认记录：

> 整个 response payload。

---

## 101. OpsPilot 自身也必须可观察

Java 开启：

```text
Actuator Health
Micrometer Metrics
```

V0.1 推荐记录：

```text
investigation duration

agent step latency

agent step failure

capability latency

capability failure

action execution result

recovery verification result

active SSE connections
```

---

## 102. 不引入完整 Tracing 平台

V0.1 有：

```text
requestId
correlationId
structured log
metrics
```

已经足够。

不为了项目“看起来高级”再搭：

```text
Jaeger
Tempo
OpenTelemetry Collector
```

除非后续确实需要。

---

## 103. Java 错误模型

业务错误：

```text
ErrorCode
```

必须与 Frozen API 对齐。

例如：

```text
INCIDENT_STATE_CONFLICT

DIAGNOSIS_NOT_ACTIONABLE

CAPABILITY_NOT_BOUND

AI_RUNTIME_TIMEOUT
```

---

## 104. 不允许随意字符串异常

禁止：

```java
throw new RuntimeException("当前状态不行");
```

然后 Controller：

```text
500
```

应产生：

```text
明确 ErrorCode
+
结构化上下文
```

---

## 105. 基础设施错误必须翻译

例如 Docker SDK 抛：

```text
SocketTimeoutException
```

不能直接一路冒到 Browser。

转换成：

```text
EXECUTION_RESULT_UNCERTAIN
```

或其他冻结 Error Code。

---

## 106. 测试策略：不追求测试数量

V0.1 不设置：

```text
80% coverage

90% coverage

100% coverage
```

这种指标。

测试只覆盖：

> 如果错了会破坏产品核心可信度的部分。

---

## 107. 第一层：纯规则单元测试

必须重点测试：

```text
Incident 状态转移

Diagnosis 不变量

Evidence 引用规则

Approval 决策

Recovery Predicate

Capability Policy

Duplicate Fingerprint
```

这些：

> 快、稳定、收益最高。

增加针对性规则用例：run重入与进程恢复区别、Stop/准入顺序、旧run结果拒绝、
reconciliation登记前后崩溃、FALSE+UNKNOWN优先级、lag已归零与pending累积反例。
测试只针对冻结不变量，不铺设与风险无关的重复场景。

---

## 108. 第二层：MySQL Integration Test

使用：

> 真 MySQL 测试容器。

重点验证：

```text
UNIQUE

CHECK

FK

optimistic lock

SQL

transaction
```

尤其：

```text
Incident version conflict

Evidence duplicate

ActionExecution idempotency

Invocation XOR
```

---

## 109. 不用 H2 模拟 MySQL

因为项目已经依赖：

```text
MySQL CHECK
JSON
DATETIME(3)
```

H2 的行为可能不同。

所以 Persistence Test：

> 直接 MySQL。

---

## 110. 第三层：Java/Python Contract Test

测试：

```text
JSON Schema

Java DTO

Pydantic Model
```

三者一致。

这是：

> AI Runtime 边界最值得写的测试之一。

---

## 111. 第四层：Provider Test

只针对关键 Provider。

例如：

```text
Redis
MySQL
```

可以用真实 Testcontainer。

Prometheus/Loki：

可以使用：

> 可控 HTTP Stub + Demo Smoke Test。

Docker：

> 重点测试绑定、参数和安全 Guard。

不要求 CI 每次真的把自己 Docker Container 重启。

---

## 112. AI Runtime 默认测试不用真实模型

CI 不依赖：

```text
DeepSeek API

OpenAI API
```

否则：

```text
不稳定
慢
花钱
```

使用：

```text
FakeLlmClient
```

喂固定 Structured Output。

---

## 113. Live AI Test 单独 Profile

允许：

```text
ai-live
```

手工运行。

用于：

> 看真实模型是否遵守协议。

但它不是：

> 每次 PR 必跑测试。

---

## 114. E2E 不铺满整个项目

完整S1 / S2 / S3是系统验收，不放进每次PR的默认流水线。
唯一标准见[09-acceptance.md](09-acceptance.md)；普通CI保护Unit / Integration / Contract / Build。
发布或演示前通过独立workflow_dispatch或手动验收命令运行真实靶场。

---

## 115. 构建规范

Backend：

```text
mvn verify
```

必须至少完成：

```text
compile

unit tests

persistence critical tests

contract tests
```

---

Python：

```text
format / lint
unit tests
contract tests
```

---

Frontend：

```text
typecheck
build
```

---

## 116. Java Formatter

项目统一一个自动 Formatter。

不允许：

```text
不同 AI
↓
不同格式
```

推荐 Maven：

```text
Spotless
```

CI：

> 检查格式。

---

## 117. 不铺静态检查全家桶

V0.1 不同时加：

```text
Checkstyle
PMD
SpotBugs
Sonar
ErrorProne
```

五套系统。

当前：

```text
Compiler
Formatter
Tests
Maven Enforcer
```

足够。

真正遇到问题再补。

---

## 118. Maven Enforcer

至少限制：

```text
Java Version

Maven Version

Dependency Convergence
```

防止某个编码 Agent：

> 偷偷把 Java 改成 17 或引入冲突依赖。

---

## 119. 禁止动态版本

不允许：

```text
LATEST
RELEASE
+
*
```

Python / JS：

> lock file 提交。

Maven：

> Parent / DependencyManagement 统一版本。

---

## 120. AI Coding 专用规则

根目录：

```text
AGENTS.md
```

必须把关键红线写进去。

至少包括：

```text
1. Frozen Specs 优先于代码。

2. 不修改 Incident 8 状态。

3. 不增加数据库核心表，除非任务明确要求。

4. 不引入任意 SQL / Shell / PromQL / LogQL 给 AI。

5. Controller 不访问 Mapper。

6. Python 不连接业务基础设施。

7. 所有 Incident 状态变化走统一 Transition。

8. 外部调用不放在数据库事务中。

9. 不使用 Map<String,Object> 作为 Capability 协议。

10. 不引入新框架/依赖解决局部问题，除非任务明确要求。

11. 不重构任务范围之外代码。

12. 每个任务完成其对应针对性验证；TASK-013起批尾完整验证覆盖所有成员DoD，同一最终代码树可共享构建证据，不省略专项验证。
```

---

## 121. Claude / Cursor 等工具

因为不同 AI IDE 读取不同规则文件，

可以存在：

```text
AGENTS.md
CLAUDE.md
.cursor/rules/opspilot.mdc
```

但：

> 三份都只保留高优先级规则。

不要分别维护三套不同架构。

核心内容来自：

```text
docs/specs
```

---

## 122. 编码 Agent 每次任务必须拿到什么

2026-09-27工作流修订：TASK-013起按08 §1及BATCH-PLAN组织交付；以下输入须覆盖批次内各Task，另固定批次ID、base SHA和批外前置。业务边界与测试要求不变。

禁止 Prompt：

> “实现 OpsPilot 后端。”

每次任务必须给：

```text
TASK ID

背景

目标

允许修改目录

禁止修改目录

关联 Frozen Spec

输入

输出

关键不变量

验收标准

验证命令
```

---

## 123. 每个任务尽量限制模块

例如：

```text
TASK-013

只允许修改：

opspilot-domain
opspilot-application

禁止：

infrastructure
web
ai-runtime
```

这样能力较弱的模型：

> 更不容易顺手改崩其他层。

---

## 124. 禁止“顺手重构”

编码 Agent 不得因为：

> “这样更优雅”

就在一个 Capability Task 中：

```text
改数据库

改 API

改状态机

换 ORM

抽通用框架
```

范围外问题：

> 记录到 Review Notes。

不在当前 Task 修。

---

## 125. 依赖新增规则

增加 Maven / Python / NPM 依赖前必须回答：

```text
当前标准库 / 已有依赖为什么不能完成？

这个依赖解决什么冻结需求？

能不能被 50 行以内普通代码替代？
```

例如：

> 为了一个状态机引入大型 Workflow Framework

不允许。

---

## 126. 代码命名规范

优先使用业务语言：

```text
completeInvestigation

approveAction

verifyRecovery

createEvidenceLink
```

而不是：

```text
processData

handleBiz

doAction

executeLogic
```

---

## 127. Command / Result 命名

写操作输入：

```text
CreateIncidentCommand

StartInvestigationCommand

ApproveActionCommand
```

结果：

```text
StartInvestigationResult
```

查询：

```text
IncidentDetailView
```

这样代码意图非常清楚。

---

## 128. Repository 命名

Application Port：

```text
IncidentRepository
```

Infrastructure：

```text
MyBatisIncidentRepository
```

底层 Mapper：

```text
IncidentMapper
```

三者不要混名。

---

## 129. Provider 命名

统一：

```text
PrometheusMetricsQueryProvider

LokiLogsSearchProvider

RedisCacheInspectProvider

RedisQueueInspectProvider

MySqlDatabaseInspectProvider

DockerServiceInspectProvider

DockerServiceRestartExecutor
```

看到类名就知道：

> 基础设施 + 语义能力。

---

## 130. 不创建 God Service

例如禁止一个：

```text
OpsPilotService.java
```

3000 行：

```text
createIncident
runAgent
queryRedis
approve
dockerRestart
verify
```

必须按被冻结的职责边界拆分。

---

## 131. 但也禁止一方法一类

不要为了“Clean Architecture”产生：

```text
StartInvestigationUseCase
StopInvestigationUseCase
ContinueInvestigationUseCase
CancelInvestigationUseCase
```

几十个只有 10 行的类。

V0.1 使用：

```text
InvestigationApplicationService
```

承载同一领域的一组相关用例即可。

保持：

> 可理解。

而不是：

> 教科书纯度。

---

## 132. Docker Compose 角色

最终 Demo 使用：

```text
deploy/docker-compose.yml
```

至少可以启动：

```text
opspilot-server

ai-runtime

opspilot-mysql

web
```

以及 Demo 所需：

```text
Prometheus

Loki

ShortLink Target Environment
```

---

## 133. Compose 分 Profile

建议：

```text
core

demo
```

例如：

```text
docker compose --profile demo up
```

用于完整 S1/S2/S3。

开发后端本身时：

> 不一定每次启动整个演示环境。

---

## 134. 健康检查

Java：

```text
/actuator/health
```

Python：

```text
/internal/v1/health
```

Docker Compose：

> 根据 Health Check 决定依赖启动顺序。

不要用：

```text
sleep 20
```

猜服务启动完成。

---

## 135. CI 第一版保持简单

流水线：

```text
backend
↓
mvn verify

ai-runtime
↓
lint + pytest

web
↓
typecheck + build
```

可并行。

---

## 136. 默认 CI 不启动完整 S1/S2/S3

原因：

> 场景验收比普通 PR CI 重得多。

完整 Demo Acceptance：

单独：

```text
workflow_dispatch
```

或者：

> 发布前手动执行。

---

## 137. 代码 Review 的优先级

Review 不是先看：

> 命名够不够漂亮。

顺序应该是：

#### P0

有没有破坏安全和业务不变量。

#### P1

事务 / 并发 / 幂等是否正确。

#### P2

模块依赖是否正确。

#### P3

错误处理与审计是否完整。

#### P4

代码可读性。

#### P5

风格。

---

## 138. Engineering Invariants

本阶段正式建议冻结以下规则。

---

### ENG-INV-001

OpsPilot Java 后端是一个 Modular Monolith，不拆业务微服务。

---

### ENG-INV-002

Java 模块依赖只能：

```text
domain
← application
← web / infrastructure
← boot
```

不得反向依赖。

---

### ENG-INV-003

Controller 不得直接访问 Mapper、Provider 或 AI Runtime Client。

---

### ENG-INV-004

Application Service 是业务事务边界。

---

### ENG-INV-005

任何 LLM、Provider、Docker、Sleep 均不得位于数据库事务中。

---

### ENG-INV-006

Incident 状态变化只能通过统一状态转换入口，并使用：

```text
expectedStatus + lockVersion
```

---

### ENG-INV-007

InvestigationOrchestrator 不拥有数据库事务，只编排短事务与外部调用。

---

### ENG-INV-008

V0.1 后台异步工作使用 JVM 内 WorkDispatcher，不引入消息队列。

---

### ENG-INV-009

V0.1 只支持单 Java 应用实例；SingleFlight 只作为 JVM 运行保护。

---

### ENG-INV-010

Java 重启以后，必须能够根据 MySQL 事实恢复：

```text
INVESTIGATING
EXECUTING
VERIFYING
```

等后台流程。

---

### ENG-INV-011

Python AI Runtime 不拥有数据库，也不拥有任何业务基础设施 Client。

---

### ENG-INV-012

Java / Python Internal Protocol 必须拥有 V1 Canonical Schema 和双方 Contract Test。

---

### ENG-INV-013

Provider 不允许使用 Shell 执行基础设施操作。

---

### ENG-INV-014

JSON 持久化允许存在，但 Java 运行时必须有 schema + typed model。

---

### ENG-INV-015

公开 API 类型由 Java/OpenAPI 提供给前端，不手工维护第二套事实。

---

### ENG-INV-016

SSE 只能发布数据库 COMMIT 后的事实。

---

### ENG-INV-017

SSE 不建设 Outbox；断线恢复依赖 TimelineEvent。

---

### ENG-INV-018

Flyway 是 MySQL Schema 唯一变更入口。

---

### ENG-INV-019

V0.1 使用原生 MyBatis，不使用 MyBatis-Plus。

---

### ENG-INV-020

测试优先保护业务不变量，不设置代码覆盖率 KPI。

#### ENG-INV-010 的具体范围
启动及存活期间补派发均覆盖PENDING和无JVM Worker归属的后台工作；不能只扫描RUNNING。
应用重启不刷新run预算、Verification deadline或reconciliation尝试上限。

#### ENG-INV-014 的具体范围
Recovery Criteria、Execution恢复快照、结果与样本身份必须有正式类型与版本；
不能只保存在没有Schema的Map或靠Observation条数猜采样位置。

---

## 139. Coding Agent 红线

任何 Codex / Claude Code 输出只要出现以下情况：

默认判定：

> Review Failed。

```text
Controller → Mapper

Python → Redis

Python → MySQL

Python → Docker

AI 自己 update Incident

任意 updateStatus

数据库事务包住 LLM

数据库事务包住 Docker

Agent 自己执行 service.restart

使用 Map<String,Object> 做 Capability 参数

使用 Shell 执行 Docker

新增万能 Tool

新增隐藏 Retry

Execution 成功直接 RESOLVED

Recovery 让 LLM 判断

Ground Truth 进入 Agent Context

硬编码 Secret

未经任务允许新增依赖

未经任务允许新增表
```

---

## 140. Engineering 阶段完成标准

这篇冻结后，编码 Agent 应该能够明确回答：

#### Java 是单模块还是多模块？

Maven 5 模块，单个部署应用。

#### 是微服务吗？

不是。

#### Domain 能依赖 Spring 吗？

不依赖。

#### Transaction 放哪？

Application Use Case。

#### Orchestrator 有没有长事务？

没有。

#### AI 调用在哪？

Infrastructure 实现 `AiDecisionPort`。

#### Python 保存 Incident 吗？

不保存。

#### Provider 在哪？

Java Infrastructure。

#### Controller 能调用 Mapper 吗？

不能。

#### 状态怎么改？

统一 Transition + Optimistic Lock。

#### 调查怎么异步？

In-process WorkDispatcher。

#### 为什么不用 MQ？

V0.1 单实例、任务状态已经持久化。

#### Java 重启会丢调查吗？

不会，StartupRecoveryCoordinator 从 DB 恢复。

#### SSE 会不会成为事实来源？

不会。

#### MyBatis-Plus 用不用？

不用。

#### LangGraph 用不用？

V0.1 不用。

#### Java/Python JSON 怎么避免漂移？

Canonical Schema + Contract Fixture Tests。

#### 数据库结构谁管理？

Flyway。

如果这些答案不会因不同编码 Agent 而改变：

> 工程结构阶段才真正完成。

---

## 141. 任务计划入口

执行 [08-implementation-plan.md](08-implementation-plan.md)。
保留109个任务编号和里程碑；Policy基础任务前置到真实审批执行之前，具体依赖以08为准。
不要新增整套Task、重新拆微服务或把全部任务一次性交给实施模型。

---

## 142. 工程基线状态

00～09设计基线已合并并标记FROZEN。下一步是TASK-001仓库落位，然后TASK-002开始工程骨架。
规格静态一致性检查与真实编译、迁移、Provider、S1/S2/S3验收是不同证据，本包只交付前者。

---
