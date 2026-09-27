# OpsPilot V0.1 开发任务拆分与实施顺序

> Status: FROZEN  
> Version: 0.1  
> Freeze revision: FINAL-FREEZE-20260925  
> 冻结日期：2026-09-25  
> 范围：TASK-001～TASK-109的范围、依赖、交付和实施顺序。  
> 本文是已合并的正式实现规格。权威清单见 [SPEC-MANIFEST.md](SPEC-MANIFEST.md)。FROZEN 表示规格定稿，不表示代码、迁移或故障实验已经通过。
> 工作流修订：2026-09-27 经用户确认，TASK-013起采用功能批次交付（见§1）；原Task编号、业务范围、依赖、DoD及冻结业务合同不变。

## 1. 当前阶段的核心目标

我们现在不能再把任务描述成：

```text
实现 Incident 模块

实现 Agent

实现 Capability

实现恢复验证
```

这种粒度仍然太大。

对于 AI Coding 来说，一个任务如果同时涉及：

```text
数据库
领域对象
状态机
Controller
异步流程
Provider
AI协议
```

模型非常容易自行补充设计。

因此正式开发任务必须满足：

> **一个任务只解决一组紧密相关的问题。**

理想状态：

```text
TASK
↓
明确输入
↓
明确允许修改范围
↓
明确业务不变量
↓
明确输出
↓
明确验证
↓
结束
```

### 批次交付规则（2026-09-27）

TASK-001～012沿用单项流程；TASK-013起按 [BATCH-PLAN](../dev/BATCH-PLAN.md) 的44个批次交付。
Task仍是需求、依赖与DoD单位；批次是实施、独立Review与通常一次提交的单位。
批外前置须已验证、Review通过并提交/DONE；批内按原依赖顺序实现，前置针对性验证完成后可继续，不要求中间提交或逐Task外审。

开工固定成员、base SHA、允许范围、不变量及验证要求；批内逐项针对性验证，批尾完整验证覆盖所有成员DoD。
同一最终代码树的共同验证可复用，但SQL/事务、跨语言契约、真实Provider等专项要求不得省略。
独立Reviewer审查固定基线到当前代码树全部变化（含未跟踪文件）；修复沿用同批与基线，重跑受影响验证和最终必要门禁。
整批Review PASS且按用户授权提交后，成员与批次一起DONE，才可推进下一批。

批次编组及记录模板只在BATCH-PLAN维护，状态与证据记PROGRESS，当前断点记CURRENT。
本文以下每项Task的正文仍逐项适用；“一个Task”的粒度要求不等于必须逐项正式Review/提交。

---

## 2. 文档合并与代码实施边界

本包已完成文档层面的TASK-001合并，实施者只需把权威00～09落位到目标仓库并完成导入检查。
不要先创建Spring Boot再让代码倒逼核心语义；也不要重读archive自行组合另一套规格。
首次正式编码仍从TASK-002开始。

---

## 3. 最终规格权威范围

实现唯一依据是docs/specs/SPEC-MANIFEST及00～09。历史patch与review没有独立实现优先级。
本包FINAL-FREEZE已应用并进入归档，用于决策追溯，不是第二份并行合同。
若实现细节没有被规定，在当前Task内记录选择及验证；不能擅自改变已冻结的业务语义。

---

## 4. 实施必须遵守的最终决策

当前基线已经统一：
Evidence不可变关系且不版本化；Stop202持久化与原子准入；
每active run12次/480秒、重启不重置；CHANGE不重放、只读核对有界；
Recovery样本身份与deadline、FAILED优先三值矩阵；
S3 lag+pending健康；执行前冻结RecoveryPolicy。
MyBatis组件坐标、HTTP P99配置和Gate分支也已经在相应Task明确。

---

## 5. 最终规格目录

```text
docs/specs/
  SPEC-MANIFEST.md
  00-product.md
  01-lifecycle.md
  02-java-ai-boundary.md
  03-domain-model.md
  04-database.md
  05-api.md
  06-capability.md
  07-engineering.md
  08-implementation-plan.md
  09-acceptance.md
```
根目录AGENTS/CLAUDE只提供实施入口和红线，不复制第二套规格。
archive保留原始历史，但不得成为实现Agent补缺来源。

---

## 6. 开发总体里程碑

正式冻结为：

```text
M0  工程与规格基线

M1  系统接入模型

M2  Incident 生命周期

M3  调查事实模型

M4  Java / Python AI 协议

M5  Investigation Orchestrator

M6  OBSERVE Capability

M7  Diagnosis 收束

M8  Remediation / Approval

M9  Action Execution

M10 Recovery Verification

M11 产品查询 / Timeline / SSE

M12 Fault Lab

M13 前端与 Demo 集成

M14 三场景系统验收
```

注意：

> M14 的详细验收标准已在09-acceptance.md冻结；M10中的策略基础子集按§7前置。

---

## 7. 推荐实施依赖图

```text
TASK-001 导入
 -> 002～004 工程基线
 -> 005～011 系统接入
 -> 012～020 Incident基础
 -> 021～027 调查事实
 -> 028～034 AI协议
 -> 035～043 控制循环
 -> 044～058 OBSERVE能力与真实集成
 -> 059～061 Diagnosis
 -> 062～066 Plan/Approval合同
 -> 068 ActionExecution表
 -> 074～076 RecoveryPolicy/Verification基础、Codec与合法Seed
 -> 067 完整审批/执行前策略校验
 -> 069～073 批准执行与崩溃恢复
 -> 077～083 Recovery求值/采样/启动恢复
 -> 084～095 查询/SSE/FaultLab
 -> 096～106 前端与完整Demo
 -> 107～109 三场景验收
```
任务编号不等于绝对执行顺序。TASK-074～076前置是为了满足“不可逆副作用前已有恢复合同”，不新增里程碑或Task。
TASK-021可先建恢复外键列，TASK-074补最终FK；TASK-066的批准成功路径直到TASK-069才接通。
M6基础可在协议稳定后与部分M5并行，但同一状态机/事务边界默认串行。

---

## 8. M0 — 工程与规格基线

本组沿用原任务编号；具体依赖按每个Task的“任务输入与范围”执行。

### TASK-001 — 导入已合并 Frozen Spec 并验证仓库落位

#### 目标
将本包已经合并的权威规格落位到仓库；不重新解释或设计原始补丁。
#### 允许修改
docs/；根目录AGENTS.md/CLAUDE.md仅用于接入本包已经给定的实现约束。
#### 禁止修改
backend/、ai-runtime/、web/的业务代码；不得借导入规格开始实现业务。
#### 必须完成
完整导入00～09与SPEC-MANIFEST；原始正文、旧patch、review放archive，不能在实现读取范围内并行保留。
核对六项最终裁决和本包CONSOLIDATION-REPORT；核对109个任务编号与Policy基础前置依赖。
#### 完成标准
本包的文档合并已完成；本Task在目标仓库的文件落位、旧规格移出和路径检查仍由使用者/实施Agent执行。
Manifest为FROZEN/0.1；不存在旧Evidence内容版本、同步Stop、一次核对永久禁止或UNKNOWN覆盖FALSE的实现规则。
#### 验证
比对本包SHA256SUMS.md与仓库导入文件；检查规范内链接、任务编号、状态/能力枚举。
不要把字面出现“不允许任意SQL”等否定红线当作残留设计。

#### 任务输入与范围
前置任务：无，使用本交付包。

权威规格：[SPEC-MANIFEST.md](SPEC-MANIFEST.md)、[07-engineering.md](07-engineering.md)、[08-implementation-plan.md](08-implementation-plan.md)、[09-acceptance.md](09-acceptance.md)。

允许范围：仅规格导入及根目录Agent入口，不改业务代码。

验证与交付：按TASK-001正文核对路径、清单及SHA-256；不执行业务验收。


---

### TASK-002 — 创建 Monorepo 工程骨架

#### 目标

建立：

```text
backend/
ai-runtime/
web/
contracts/
deploy/
scripts/
```

目录。

Java 建立五个 Maven Module：

```text
opspilot-domain
opspilot-application
opspilot-infrastructure
opspilot-web
opspilot-boot
```

#### 不做

- 业务表；
- Incident；
- AI；
- Provider。

#### DoD

```text
./mvnw verify
```

通过。

Python：

可以启动最小：

```text
GET /internal/v1/health
```

前端：

```text
npm run build
```

通过。

#### 合并后约束
只创建可构建的空工程和health入口，不能提前生成23张表或假造业务闭环。
Java Wrapper放backend/，后续命令从该目录执行；Python/前端实际构建方式在本任务固定。

#### 任务输入与范围
前置任务：TASK-001。

权威规格：[07-engineering.md](07-engineering.md)。

允许范围：工程目录、构建、配置和错误模型基础；不提前实现业务。

验证与交付：运行本Task范围构建／health验证，保留实际命令与退出结果；没有实现的业务不标通过。


---

### TASK-003 — 建立工程约束

#### 目标

加入：

```text
Maven Enforcer
Spotless
Java 21
Spring Boot 4.1.x
```

以及：

```text
AGENTS.md
CLAUDE.md
```

#### 必须编码的红线

至少写入：

```text
Controller 不能访问 Mapper

Python 不能连接业务基础设施

外部调用不得位于 DB Transaction

不允许通用 updateStatus

不允许任意 SQL / Shell / PromQL / LogQL

不允许 Map<String,Object> Capability 协议
```

#### DoD

错误 Java 版本：

> Build Failed。

格式不符合：

> Build Failed。

#### 最终技术坐标
MyBatis Core 3.x、MyBatis-Spring 4.x、mybatis-spring-boot-starter 4.x不可混为一个Core 4.x。
保留Java21、Boot4.1.x、springdoc3.x选定主基线，锁定具体可兼容patch，禁止动态依赖。
验证最小应用启动、数据访问依赖解析与OpenAPI生成；不声称本文件已代替真实构建。

#### 任务输入与范围
前置任务：TASK-002。

权威规格：[07-engineering.md](07-engineering.md)。

允许范围：工程目录、构建、配置和错误模型基础；不提前实现业务。

验证与交付：运行本Task范围构建／health验证，保留实际命令与退出结果；没有实现的业务不标通过。


---

### TASK-004 — 建立配置与错误模型基础

#### 目标

建立：

```text
ErrorCode

DomainException / ApplicationException

API Error Mapping

@ConfigurationProperties
```

以及：

```text
RequestId / CorrelationId
```

基础设施。

#### 不做

具体 Incident Error。

只建立框架。

#### 任务输入与范围
前置任务：TASK-003。

权威规格：[07-engineering.md](07-engineering.md)。

允许范围：工程目录、构建、配置和错误模型基础；不提前实现业务。

验证与交付：运行本Task范围构建／health验证，保留实际命令与退出结果；没有实现的业务不标通过。

---

## 9. M1 — 系统接入模型

本组沿用原任务编号；具体依赖按每个Task的“任务输入与范围”执行。

### TASK-005 — 创建系统接入数据库结构

Flyway 创建：

```text
managed_system

managed_resource

data_source_connection

resource_binding

capability_binding
```

#### 必须实现

- FK；
- UNIQUE；
- CHECK / Status；
- Index；
- MySQL 8.0.16+。

#### DoD

真实 MySQL Migration 成功。

#### 任务输入与范围
前置任务：TASK-004。

权威规格：[03-domain-model.md](03-domain-model.md)、[04-database.md](04-database.md)、[06-capability.md](06-capability.md)、[07-engineering.md](07-engineering.md)。

允许范围：仅本Task用例涉及的domain/application/infrastructure/web及相应测试/迁移；无关模块不得顺手重构。

验证与交付：运行本Task新增/受影响的规则或集成测试；Java用backend Maven Wrapper，涉及FK/事务则用真实MySQL，禁止H2替代。


---

### TASK-006 — 实现 ManagedSystem / ManagedResource 领域模型

实现：

```text
ManagedSystem

ManagedResource

SystemStatus

ResourceStatus

ResourceType
```

以及对应 Repository Port。

#### 不做

HTTP。

#### 任务输入与范围
前置任务：TASK-005。

权威规格：[03-domain-model.md](03-domain-model.md)、[04-database.md](04-database.md)、[06-capability.md](06-capability.md)、[07-engineering.md](07-engineering.md)。

允许范围：仅本Task用例涉及的domain/application/infrastructure/web及相应测试/迁移；无关模块不得顺手重构。

验证与交付：运行本Task新增/受影响的规则或集成测试；Java用backend Maven Wrapper，涉及FK/事务则用真实MySQL，禁止H2替代。


---

### TASK-007 — 实现数据源连接与资源绑定

实现：

```text
DataSourceConnection

ResourceBinding

CapabilityBinding
```

Application Port。

建立强类型：

```text
SelectorSchema
```

但暂不实现具体 Provider。

#### 任务输入与范围
前置任务：TASK-006。

权威规格：[03-domain-model.md](03-domain-model.md)、[04-database.md](04-database.md)、[06-capability.md](06-capability.md)、[07-engineering.md](07-engineering.md)。

允许范围：仅本Task用例涉及的domain/application/infrastructure/web及相应测试/迁移；无关模块不得顺手重构。

验证与交付：运行本Task新增/受影响的规则或集成测试；Java用backend Maven Wrapper，涉及FK/事务则用真实MySQL，禁止H2替代。


---

### TASK-008 — 建立 SchemaCodecRegistry 基础

实现：

```text
schemaName
schemaVersion
→
Typed Codec
```

第一批支持：

```text
PrometheusResourceBindingV1
LokiResourceBindingV1
RedisResourceBindingV1
MySqlResourceBindingV1
DockerResourceBindingV1
```

禁止：

```text
Map<String,Object>
```

作为应用层协议。

#### 任务输入与范围
前置任务：TASK-007。

权威规格：[03-domain-model.md](03-domain-model.md)、[04-database.md](04-database.md)、[06-capability.md](06-capability.md)、[07-engineering.md](07-engineering.md)。

允许范围：仅本Task用例涉及的domain/application/infrastructure/web及相应测试/迁移；无关模块不得顺手重构。

验证与交付：运行本Task新增/受影响的规则或集成测试；Java用backend Maven Wrapper，涉及FK/事务则用真实MySQL，禁止H2替代。


---

### TASK-009 — 实现 SecretResolver

Application：

```text
SecretResolver
```

Infrastructure：

```text
EnvironmentSecretResolver
```

支持：

```text
env://KEY
```

#### 测试

Credential 不存在：

返回明确：

```text
SECRET_NOT_FOUND
```

不得泄露 Key Value。

#### 任务输入与范围
前置任务：TASK-008。

权威规格：[03-domain-model.md](03-domain-model.md)、[04-database.md](04-database.md)、[06-capability.md](06-capability.md)、[07-engineering.md](07-engineering.md)。

允许范围：仅本Task用例涉及的domain/application/infrastructure/web及相应测试/迁移；无关模块不得顺手重构。

验证与交付：运行本Task新增/受影响的规则或集成测试；Java用backend Maven Wrapper，涉及FK/事务则用真实MySQL，禁止H2替代。


---

### TASK-010 — ShortLink Demo 系统 Seed

初始化：

```text
shortlink-platform
```

以及：

```text
redirect-service

statistics-consumer

shortlink-redis

shortlink-mysql

statistics-stream
```

只创建：

> 配置数据。

不实现 Fault。

#### 任务输入与范围
前置任务：TASK-009。

权威规格：[03-domain-model.md](03-domain-model.md)、[04-database.md](04-database.md)、[06-capability.md](06-capability.md)、[07-engineering.md](07-engineering.md)。

允许范围：仅本Task用例涉及的domain/application/infrastructure/web及相应测试/迁移；无关模块不得顺手重构。

验证与交付：运行本Task新增/受影响的规则或集成测试；Java用backend Maven Wrapper，涉及FK/事务则用真实MySQL，禁止H2替代。


---

### TASK-011 — Systems Read API

实现：

```text
GET /api/v1/systems

GET /api/v1/systems/{systemKey}

GET /api/v1/systems/{systemKey}/resources/{resourceKey}
```

使用：

```text
Query Repository
```

而不是加载完整 Domain Graph。

#### 任务输入与范围
前置任务：TASK-010。

权威规格：[03-domain-model.md](03-domain-model.md)、[04-database.md](04-database.md)、[06-capability.md](06-capability.md)、[07-engineering.md](07-engineering.md)。

允许范围：仅本Task用例涉及的domain/application/infrastructure/web及相应测试/迁移；无关模块不得顺手重构。

验证与交付：运行本Task新增/受影响的规则或集成测试；Java用backend Maven Wrapper，涉及FK/事务则用真实MySQL，禁止H2替代。

---

## 10. M2 — Incident 生命周期

本组沿用原任务编号；具体依赖按每个Task的“任务输入与范围”执行。

### TASK-012 — Incident / Investigation 基础表

Flyway 创建：

```text
incident

incident_affected_resource

investigation

incident_timeline_event
```

Investigation 必须已经包含：

```text
stop_requested_at
stop_requested_by
```

#### 最终字段
Investigation同时包含current_run_no、current_run_started_at、current_run_capability_count、
consecutive_ai_failure_count、累计capability_call_count、stop_requested_at/by及原预算快照。
字段类型、默认值和约束照04，不增加InvestigationRun表。

#### 任务输入与范围
前置任务：TASK-011。

权威规格：[01-lifecycle.md](01-lifecycle.md)、[03-domain-model.md](03-domain-model.md)、[04-database.md](04-database.md)、[05-api.md](05-api.md)、[07-engineering.md](07-engineering.md)。

允许范围：仅本Task用例涉及的domain/application/infrastructure/web及相应测试/迁移；无关模块不得顺手重构。

验证与交付：运行本Task新增/受影响的规则或集成测试；Java用backend Maven Wrapper，涉及FK/事务则用真实MySQL，禁止H2替代。


---

### TASK-013 — Incident Domain Model

实现：

```text
IncidentStatus

Incident

IncidentKey

IncidentTransitionPolicy
```

以及 8 个状态。

严格禁止：

```text
updateStatus(id, status)
```

#### 任务输入与范围
前置任务：TASK-012。

权威规格：[01-lifecycle.md](01-lifecycle.md)、[03-domain-model.md](03-domain-model.md)、[04-database.md](04-database.md)、[05-api.md](05-api.md)、[07-engineering.md](07-engineering.md)。

允许范围：仅本Task用例涉及的domain/application/infrastructure/web及相应测试/迁移；无关模块不得顺手重构。

验证与交付：运行本Task新增/受影响的规则或集成测试；Java用backend Maven Wrapper，涉及FK/事务则用真实MySQL，禁止H2替代。


---

### TASK-014 — Incident 状态转换 Repository

实现：

```text
expectedStatus
+
expectedVersion
```

条件更新。

例如：

```text
WHERE id = ?
AND status = ?
AND lock_version = ?
```

#### 核心测试

两个并发 Transition：

> 只能成功一个。

#### 任务输入与范围
前置任务：TASK-013。

权威规格：[01-lifecycle.md](01-lifecycle.md)、[03-domain-model.md](03-domain-model.md)、[04-database.md](04-database.md)、[05-api.md](05-api.md)、[07-engineering.md](07-engineering.md)。

允许范围：仅本Task用例涉及的domain/application/infrastructure/web及相应测试/迁移；无关模块不得顺手重构。

验证与交付：运行本Task新增/受影响的规则或集成测试；Java用backend Maven Wrapper，涉及FK/事务则用真实MySQL，禁止H2替代。


---

### TASK-015 — 创建 Incident

实现：

```text
CreateIncidentCommand

IncidentApplicationService.createIncident()
```

事务内：

```text
Incident
AffectedResource
Timeline
```

一起创建。

#### 任务输入与范围
前置任务：TASK-014。

权威规格：[01-lifecycle.md](01-lifecycle.md)、[03-domain-model.md](03-domain-model.md)、[04-database.md](04-database.md)、[05-api.md](05-api.md)、[07-engineering.md](07-engineering.md)。

允许范围：仅本Task用例涉及的domain/application/infrastructure/web及相应测试/迁移；无关模块不得顺手重构。

验证与交付：运行本Task新增/受影响的规则或集成测试；Java用backend Maven Wrapper，涉及FK/事务则用真实MySQL，禁止H2替代。


---

### TASK-016 — 开始调查

实现：

```text
CREATED
→
INVESTIGATING
```

同事务：

```text
创建唯一 Investigation
Timeline
Incident Transition
```

提交后：

```text
WorkDispatcher.dispatchInvestigation()
```

初期 Dispatcher 可以使用 Fake。

#### 运行准入
Start事务创建唯一Investigation并通过resumeInvestigation初始化run1；条件迁移与Timeline同事务。
提交后的dispatch只是唤醒；此阶段FakeDispatcher不能被标成真实后台流程完成。

#### 任务输入与范围
前置任务：TASK-015。

权威规格：[01-lifecycle.md](01-lifecycle.md)、[03-domain-model.md](03-domain-model.md)、[04-database.md](04-database.md)、[05-api.md](05-api.md)、[07-engineering.md](07-engineering.md)。

允许范围：仅本Task用例涉及的domain/application/infrastructure/web及相应测试/迁移；无关模块不得顺手重构。

验证与交付：运行本Task新增/受影响的规则或集成测试；Java用backend Maven Wrapper，涉及FK/事务则用真实MySQL，禁止H2替代。


---

### TASK-017 — Continue Investigation

#### 目标
实现DIAGNOSED到INVESTIGATING的显式Continue，复用唯一Investigation。
#### 必须实现
统一resumeInvestigation服务：按Incident->Investigation锁序校验来源和版本，
current_run_no+1、current_run_started_at=now、当前轮计数及连续AI失败清零、清Stop时间和身份；
不清累计调用历史、不删除Diagnosis/Evidence。
拒绝PENDING Approval，提交后派发新run。
#### 完成标准
Stop后Continue不立即因旧标记结束；用尽12次后的新run获得本轮新额度；
Java启动恢复不得调用此方法重置预算。并发Continue仅一个成功。
#### 阶段边界
M2先验收事务/状态合同；真实Worker与迟到结果在TASK-039～043补成端到端控制流。

#### 任务输入与范围
前置任务：TASK-016。

权威规格：[01-lifecycle.md](01-lifecycle.md)、[03-domain-model.md](03-domain-model.md)、[04-database.md](04-database.md)、[05-api.md](05-api.md)、[07-engineering.md](07-engineering.md)。

允许范围：仅本Task用例涉及的domain/application/infrastructure/web及相应测试/迁移；无关模块不得顺手重构。

验证与交付：运行本Task新增/受影响的规则或集成测试；Java用backend Maven Wrapper，涉及FK/事务则用真实MySQL，禁止H2替代。


---

### TASK-018 — Stop Investigation Request

实现：

```text
POST stop-investigation
```

事务只负责：

```text
写 stop_requested_at
写 stop_requested_by
Timeline
```

返回：

```text
202
```

重复 Stop：

自然幂等。

#### 原子性与响应
与Step/Capability准入使用同一锁序，首次Stop更新stop字段和Incident/Investigation版本，
只追加一条INVESTIGATION_STOP_REQUESTED；202不等待网络。
同一活跃run重复Stop返回已接受结果；结束后的Stop按05的状态冲突规则处理。

#### 任务输入与范围
前置任务：TASK-017。

权威规格：[01-lifecycle.md](01-lifecycle.md)、[03-domain-model.md](03-domain-model.md)、[04-database.md](04-database.md)、[05-api.md](05-api.md)、[07-engineering.md](07-engineering.md)。

允许范围：仅本Task用例涉及的domain/application/infrastructure/web及相应测试/迁移；无关模块不得顺手重构。

验证与交付：运行本Task新增/受影响的规则或集成测试；Java用backend Maven Wrapper，涉及FK/事务则用真实MySQL，禁止H2替代。


---

### TASK-019 — Cancel Incident

支持：

```text
CREATED
INVESTIGATING
DIAGNOSED
AWAITING_APPROVAL
```

到：

```text
CANCELLED
```

如果：

```text
AWAITING_APPROVAL
```

后续实现 Approval 后必须同步取消 Pending Approval。

当前 Task 先建立 Transition Contract。

#### 集成回填
取消等待审批的Incident时，Approval与未执行Plan一并取消；
本阶段可仅建立Port合同，但TASK-067必须补真实Plan/Approval集成验证。
取消后的迟到AI结果禁止更新领域对象。

#### 任务输入与范围
前置任务：TASK-018。

权威规格：[01-lifecycle.md](01-lifecycle.md)、[03-domain-model.md](03-domain-model.md)、[04-database.md](04-database.md)、[05-api.md](05-api.md)、[07-engineering.md](07-engineering.md)。

允许范围：仅本Task用例涉及的domain/application/infrastructure/web及相应测试/迁移；无关模块不得顺手重构。

验证与交付：运行本Task新增/受影响的规则或集成测试；Java用backend Maven Wrapper，涉及FK/事务则用真实MySQL，禁止H2替代。


---

### TASK-020 — Incident 基础 API

实现：

```text
POST /incidents

GET /incidents

GET /incidents/{incidentKey}

start-investigation

continue-investigation

stop-investigation

cancel
```

暂时 Incident Detail：

只返回已经实现的字段。

后面 M11 再增强。

#### 阶段完成边界
此处验收已实现的API/事务合同，不宣称AI调查、Stop完整收束或Continue后的真实调用已完成。
真实控制流完成点是TASK-043，真实Provider完成点是TASK-058。

#### 任务输入与范围
前置任务：TASK-019。

权威规格：[01-lifecycle.md](01-lifecycle.md)、[03-domain-model.md](03-domain-model.md)、[04-database.md](04-database.md)、[05-api.md](05-api.md)、[07-engineering.md](07-engineering.md)。

允许范围：仅本Task用例涉及的domain/application/infrastructure/web及相应测试/迁移；无关模块不得顺手重构。

验证与交付：运行本Task新增/受影响的规则或集成测试；Java用backend Maven Wrapper，涉及FK/事务则用真实MySQL，禁止H2替代。

---

## 11. M3 — 调查事实模型

本组沿用原任务编号；具体依赖按每个Task的“任务输入与范围”执行。

### TASK-021 — 调查事实数据库结构

创建：

```text
capability_invocation

observation

hypothesis

evidence

diagnosis

diagnosis_evidence_ref

agent_step_record
```

Evidence 最终结构：

```text
无 version_no

无 previous_evidence_id

UNIQUE(
 observation_id,
 hypothesis_id
)
```

#### Migration依赖
Invocation增加run_no或criterion_key/sample_index互斥上下文，Diagnosis增加run_no；
AgentStep增加run_no和必要起止更新时间。
恢复父表尚未创建时先建相应可空列，TASK-074添加最终FK和样本唯一约束；
最终不得遗留无约束恢复引用。Evidence仍无内容版本字段。

#### 任务输入与范围
前置任务：TASK-020。

权威规格：[01-lifecycle.md](01-lifecycle.md)、[03-domain-model.md](03-domain-model.md)、[04-database.md](04-database.md)、[05-api.md](05-api.md)、[07-engineering.md](07-engineering.md)。

允许范围：仅本Task用例涉及的domain/application/infrastructure/web及相应测试/迁移；无关模块不得顺手重构。

验证与交付：运行本Task新增/受影响的规则或集成测试；Java用backend Maven Wrapper，涉及FK/事务则用真实MySQL，禁止H2替代。


---

### TASK-022 — Observation Domain / Persistence

实现：

```text
Observation

ObservationKind

ObservationRepository
```

要求：

> INSERT ONLY。

不存在：

```text
updateObservation()
```

#### 任务输入与范围
前置任务：TASK-021。

权威规格：[01-lifecycle.md](01-lifecycle.md)、[03-domain-model.md](03-domain-model.md)、[04-database.md](04-database.md)、[05-api.md](05-api.md)、[07-engineering.md](07-engineering.md)。

允许范围：仅本Task用例涉及的domain/application/infrastructure/web及相应测试/迁移；无关模块不得顺手重构。

验证与交付：运行本Task新增/受影响的规则或集成测试；Java用backend Maven Wrapper，涉及FK/事务则用真实MySQL，禁止H2替代。


---

### TASK-023 — Hypothesis Domain

实现：

```text
PENDING
SUPPORTED
INSUFFICIENT_EVIDENCE
REFUTED
```

以及合法状态更新。

每次变化：

> 必须写 Timeline。

#### 任务输入与范围
前置任务：TASK-022。

权威规格：[01-lifecycle.md](01-lifecycle.md)、[03-domain-model.md](03-domain-model.md)、[04-database.md](04-database.md)、[05-api.md](05-api.md)、[07-engineering.md](07-engineering.md)。

允许范围：仅本Task用例涉及的domain/application/infrastructure/web及相应测试/迁移；无关模块不得顺手重构。

验证与交付：运行本Task新增/受影响的规则或集成测试；Java用backend Maven Wrapper，涉及FK/事务则用真实MySQL，禁止H2替代。


---

### TASK-024 — Evidence Domain

实现：

```text
SUPPORTS
REFUTES
CONTEXT
```

创建前校验：

```text
Observation
Hypothesis
Investigation
```

归属一致。

重复关系：

```text
EVIDENCE_LINK_ALREADY_EXISTS
```

#### 必须保护
不允许复制未真实发生的Observation来重解释旧Evidence。
创建关系及可附带的同Hypothesis状态更新必须同事务，不允许跨Incident/跨Investigation引用。

#### 任务输入与范围
前置任务：TASK-023。

权威规格：[01-lifecycle.md](01-lifecycle.md)、[03-domain-model.md](03-domain-model.md)、[04-database.md](04-database.md)、[05-api.md](05-api.md)、[07-engineering.md](07-engineering.md)。

允许范围：仅本Task用例涉及的domain/application/infrastructure/web及相应测试/迁移；无关模块不得顺手重构。

验证与交付：运行本Task新增/受影响的规则或集成测试；Java用backend Maven Wrapper，涉及FK/事务则用真实MySQL，禁止H2替代。


---

### TASK-025 — Diagnosis Domain

实现：

```text
PRIMARY_CAUSE_IDENTIFIED

POSSIBLE_CAUSE

UNDETERMINED
```

规则：

```text
PRIMARY / POSSIBLE

必须有 primaryHypothesis

至少一个 SUPPORTS Evidence
```

Diagnosis：

> INSERT ONLY + version_no。

#### 必须保护
SUPPORTS Evidence必须属于本Diagnosis引用集合且关联primaryHypothesis，不是任意支持证据。
Diagnosis保存产生时run_no，旧版本只读。

#### 任务输入与范围
前置任务：TASK-024。

权威规格：[01-lifecycle.md](01-lifecycle.md)、[03-domain-model.md](03-domain-model.md)、[04-database.md](04-database.md)、[05-api.md](05-api.md)、[07-engineering.md](07-engineering.md)。

允许范围：仅本Task用例涉及的domain/application/infrastructure/web及相应测试/迁移；无关模块不得顺手重构。

验证与交付：运行本Task新增/受影响的规则或集成测试；Java用backend Maven Wrapper，涉及FK/事务则用真实MySQL，禁止H2替代。


---

### TASK-026 — Diagnosis 创建事务

同事务：

```text
读取当前 Investigation

验证 Diagnosis Draft

INSERT Diagnosis

INSERT DiagnosisEvidenceRef

SUPERSEDE 旧未执行 Plan
（Plan 模块尚未存在时预留 Port）

Incident:
INVESTIGATING → DIAGNOSED

Timeline
```

#### 最终事务
校验run_no与当前run、Incident状态及真实引用；原子保存Diagnosis、引用、旧未执行Plan失效及Timeline。
Plan模块未出现时可预留Port，但TASK-067必须回填真实行为和集成断言，不把no-op留到Release。

#### 任务输入与范围
前置任务：TASK-025。

权威规格：[01-lifecycle.md](01-lifecycle.md)、[03-domain-model.md](03-domain-model.md)、[04-database.md](04-database.md)、[05-api.md](05-api.md)、[07-engineering.md](07-engineering.md)。

允许范围：仅本Task用例涉及的domain/application/infrastructure/web及相应测试/迁移；无关模块不得顺手重构。

验证与交付：运行本Task新增/受影响的规则或集成测试；Java用backend Maven Wrapper，涉及FK/事务则用真实MySQL，禁止H2替代。


---

### TASK-027 — Investigation 技术详情 Query

实现：

```text
/investigation

/hypotheses

/observations

/evidence

/diagnoses
```

全部：

> Read Only。

#### 任务输入与范围
前置任务：TASK-026。

权威规格：[01-lifecycle.md](01-lifecycle.md)、[03-domain-model.md](03-domain-model.md)、[04-database.md](04-database.md)、[05-api.md](05-api.md)、[07-engineering.md](07-engineering.md)。

允许范围：仅本Task用例涉及的domain/application/infrastructure/web及相应测试/迁移；无关模块不得顺手重构。

验证与交付：运行本Task新增/受影响的规则或集成测试；Java用backend Maven Wrapper，涉及FK/事务则用真实MySQL，禁止H2替代。

---

## 12. M4 — Java / Python AI 协议

本组沿用原任务编号；具体依赖按每个Task的“任务输入与范围”执行。

### TASK-028 — Canonical AI Protocol Schema

建立：

```text
contracts/ai-runtime/v1/
```

Schema：

```text
investigation-step-request

investigation-step-response

remediation-draft-request

remediation-draft-response
```

Intent：

```text
REQUEST_CAPABILITY

PROPOSE_HYPOTHESIS

UPDATE_HYPOTHESIS

PROPOSE_EVIDENCE_LINK

COMPLETE_INVESTIGATION
```

#### 最终协议字段
调查请求/响应包含protocolVersion、runNo、stepId，Java掌握investigationId和权威轮号。
所有Intent判别联合拒绝多主payload和未知字段。无参数arguments仍显式{}；
Remediation禁止riskLevel、requiresApproval与RecoveryPolicy/容器执行上下文。

#### 任务输入与范围
前置任务：TASK-027。

权威规格：[02-java-ai-boundary.md](02-java-ai-boundary.md)、[05-api.md](05-api.md)、[06-capability.md](06-capability.md)、[07-engineering.md](07-engineering.md)。

允许范围：contracts/、Java内部协议模型或Client、ai-runtime对应模块及合同测试；不访问业务基础设施。

验证与交付：运行共享正/负fixture及本Task改动端的构建/测试；例如backend Maven与ai-runtime pytest，实际命令由完成报告列出。


---

### TASK-029 — Capability Arguments Protocol

定义强类型：

```text
MetricsQueryArgumentsV1

LogsSearchArgumentsV1

DatabaseInspectArgumentsV1

CacheInspectArgumentsV1

QueueInspectArgumentsV1

ServiceInspectArgumentsV1
```

以及 Descriptor 联合类型。

不得使用：

```text
Map<String,Object>
```

#### Descriptor
使用06给定的6种descriptorType及其受控参数域；同capabilityKey必须匹配对应arguments类型。
不能只提供key而把可选参数留给模型猜测。

#### 任务输入与范围
前置任务：TASK-028。

权威规格：[02-java-ai-boundary.md](02-java-ai-boundary.md)、[05-api.md](05-api.md)、[06-capability.md](06-capability.md)、[07-engineering.md](07-engineering.md)。

允许范围：contracts/、Java内部协议模型或Client、ai-runtime对应模块及合同测试；不访问业务基础设施。

验证与交付：运行共享正/负fixture及本Task改动端的构建/测试；例如backend Maven与ai-runtime pytest，实际命令由完成报告列出。


---

### TASK-030 — Java Protocol Model

Java 建立：

```text
record
sealed interface
enum
```

对应 JSON Schema。

建立：

```text
AiDecisionPort
```

#### 任务输入与范围
前置任务：TASK-029。

权威规格：[02-java-ai-boundary.md](02-java-ai-boundary.md)、[05-api.md](05-api.md)、[06-capability.md](06-capability.md)、[07-engineering.md](07-engineering.md)。

允许范围：contracts/、Java内部协议模型或Client、ai-runtime对应模块及合同测试；不访问业务基础设施。

验证与交付：运行共享正/负fixture及本Task改动端的构建/测试；例如backend Maven与ai-runtime pytest，实际命令由完成报告列出。


---

### TASK-031 — Python Pydantic Protocol

建立：

```text
Pydantic v2
discriminated union
```

与 Canonical Schema 对齐。

Python 不出现：

```text
Redis Client
MySQL Client
Docker Client
```

#### 任务输入与范围
前置任务：TASK-030。

权威规格：[02-java-ai-boundary.md](02-java-ai-boundary.md)、[05-api.md](05-api.md)、[06-capability.md](06-capability.md)、[07-engineering.md](07-engineering.md)。

允许范围：contracts/、Java内部协议模型或Client、ai-runtime对应模块及合同测试；不访问业务基础设施。

验证与交付：运行共享正/负fixture及本Task改动端的构建/测试；例如backend Maven与ai-runtime pytest，实际命令由完成报告列出。


---

### TASK-032 — Java / Python Contract Test

同一 fixture：

必须同时通过：

```text
Java deserialize / validate

Python Pydantic validate
```

非法 fixture：

两端都应拒绝。

#### 共享负例
双端都拒绝旧轮字段缺失、错误联合、额外风险字段、任意查询/命令参数和缺失空arguments。
这些仅验证协议；runNo是否当前、Evidence是否存在仍在Java业务事务校验。

#### 任务输入与范围
前置任务：TASK-031。

权威规格：[02-java-ai-boundary.md](02-java-ai-boundary.md)、[05-api.md](05-api.md)、[06-capability.md](06-capability.md)、[07-engineering.md](07-engineering.md)。

允许范围：contracts/、Java内部协议模型或Client、ai-runtime对应模块及合同测试；不访问业务基础设施。

验证与交付：运行共享正/负fixture及本Task改动端的构建/测试；例如backend Maven与ai-runtime pytest，实际命令由完成报告列出。


---

### TASK-033 — AI Runtime 最小推理服务

实现：

```text
InvestigationDecisionService

RemediationDraftService

LlmClient
```

首版提供：

```text
FakeLlmClient
```

支持固定 Intent。

真实 LLM：

后续配置打开。

#### 任务输入与范围
前置任务：TASK-032。

权威规格：[02-java-ai-boundary.md](02-java-ai-boundary.md)、[05-api.md](05-api.md)、[06-capability.md](06-capability.md)、[07-engineering.md](07-engineering.md)。

允许范围：contracts/、Java内部协议模型或Client、ai-runtime对应模块及合同测试；不访问业务基础设施。

验证与交付：运行共享正/负fixture及本Task改动端的构建/测试；例如backend Maven与ai-runtime pytest，实际命令由完成报告列出。


---

### TASK-034 — Java AiRuntimeClient

Infrastructure 实现：

```text
HTTP + JSON
```

要求：

```text
timeout
protocolVersion
internal token
correlationId
```

禁止透明 Retry。

#### 任务输入与范围
前置任务：TASK-033。

权威规格：[02-java-ai-boundary.md](02-java-ai-boundary.md)、[05-api.md](05-api.md)、[06-capability.md](06-capability.md)、[07-engineering.md](07-engineering.md)。

允许范围：contracts/、Java内部协议模型或Client、ai-runtime对应模块及合同测试；不访问业务基础设施。

验证与交付：运行共享正/负fixture及本Task改动端的构建/测试；例如backend Maven与ai-runtime pytest，实际命令由完成报告列出。

---

## 13. M5 — Investigation Orchestrator

本组沿用原任务编号；具体依赖按每个Task的“任务输入与范围”执行。

### TASK-035 — WorkDispatcher

#### 目标
实现WorkDispatcher接口和JVM有界线程池派发，覆盖调查、Execution和Verification三类工作。
#### 必须实现
提交后立即唤醒；线程池拒绝要记录而不丢失数据库工作。
提供StartupRecoveryCoordinator及轻量补派发入口，默认5秒扫描；
本阶段后两类用Port，实际恢复在073/083接入。
#### 完成标准
已提交PENDING在派发前崩溃可由启动扫描恢复；存活期间拒绝派发可重新唤醒；
不会把已由JVM Worker拥有的RUNNING调用标记中断。
#### 明确不做
MQ、Outbox、DB Lease、Leader Election或第二套任务状态表。

#### 任务输入与范围
前置任务：TASK-034。

权威规格：[01-lifecycle.md](01-lifecycle.md)、[04-database.md](04-database.md)、[05-api.md](05-api.md)、[07-engineering.md](07-engineering.md)、[09-acceptance.md](09-acceptance.md)。

允许范围：仅本Task用例涉及的domain/application/infrastructure/web及相应测试/迁移；无关模块不得顺手重构。

验证与交付：运行本Task新增/受影响的规则或集成测试；Java用backend Maven Wrapper，涉及FK/事务则用真实MySQL，禁止H2替代。


---

### TASK-036 — SingleFlightRegistry

同一 Incident：

```text
最多一个 Investigation Worker
```

Registry 只作为：

> JVM 运行保护。

MySQL 仍是事实来源。

#### 拥有者规则
按工作类型和业务ID登记拥有者token；旧Worker只能释放自己，不能移除新runWorker。
Registry只保护当前JVM，数据库仍为事实来源；派发合并不得丢失新run唤醒。

#### 任务输入与范围
前置任务：TASK-035。

权威规格：[01-lifecycle.md](01-lifecycle.md)、[04-database.md](04-database.md)、[05-api.md](05-api.md)、[07-engineering.md](07-engineering.md)、[09-acceptance.md](09-acceptance.md)。

允许范围：仅本Task用例涉及的domain/application/infrastructure/web及相应测试/迁移；无关模块不得顺手重构。

验证与交付：运行本Task新增/受影响的规则或集成测试；Java用backend Maven Wrapper，涉及FK/事务则用真实MySQL，禁止H2替代。


---

### TASK-037 — Investigation Context Builder

从数据库读取：

```text
Incident

Affected Resources

Hypotheses

Relevant Observations

Evidence

Available Capability Descriptors

Budget

Recent Timeline
```

构建：

```text
InvestigationStepRequest
```

禁止读取：

```text
fault_experiment.ground_truth_payload
```

#### 轮次与历史
默认取本轮观测和以前Diagnosis已冻结的历史Evidence摘要，保留时间/run标识。
旧run未被诊断引用的迟到结果不得自动进入当前决策上下文。
完整序列化请求须检查不含GroundTruth、控制日志或凭证；不是只检查Prompt模板文本。

#### 任务输入与范围
前置任务：TASK-036。

权威规格：[01-lifecycle.md](01-lifecycle.md)、[04-database.md](04-database.md)、[05-api.md](05-api.md)、[07-engineering.md](07-engineering.md)、[09-acceptance.md](09-acceptance.md)。

允许范围：仅本Task用例涉及的domain/application/infrastructure/web及相应测试/迁移；无关模块不得顺手重构。

验证与交付：运行本Task新增/受影响的规则或集成测试；Java用backend Maven Wrapper，涉及FK/事务则用真实MySQL，禁止H2替代。


---

### TASK-038 — AgentStep 生命周期

一次：

```text
Java → Python
```

之前创建：

```text
AgentStepRecord RUNNING
```

完成：

```text
SUCCEEDED / FAILED
```

记录：

```text
latency
tokens
intentType
model
promptTemplateVersion
```

不保存完整思维链。

#### 最终生命周期
准入时保存run_no、step_no、RUNNING及started_at；终态条件更新finished_at/updated_at。
step_no在整个Investigation累计不重置。协议回显与已登记身份校验；
迟到输出可审计，但不能写本轮领域事实。

#### 任务输入与范围
前置任务：TASK-037。

权威规格：[01-lifecycle.md](01-lifecycle.md)、[04-database.md](04-database.md)、[05-api.md](05-api.md)、[07-engineering.md](07-engineering.md)、[09-acceptance.md](09-acceptance.md)。

允许范围：仅本Task用例涉及的domain/application/infrastructure/web及相应测试/迁移；无关模块不得顺手重构。

验证与交付：运行本Task新增/受影响的规则或集成测试；Java用backend Maven Wrapper，涉及FK/事务则用真实MySQL，禁止H2替代。


---

### TASK-039 — Investigation Guard

#### 目标
实现原子AgentStep Admission与统一run Guard，而不是事务外检查一次后直接调用网络。
#### 必须实现
锁Incident->Investigation，检查状态、run_no、Stop、current_run_capability_count、
current_run_started_at计算的deadline及连续AI失败阈值；创建RUNNING Step后COMMIT。
本轮退出规则统一；应用恢复不能刷新run。调用等待上限不超过单步timeout和run剩余时间。
#### 完成标准
检查与登记无TOCTOU；准入前拒绝不发网络；事务内没有LLM、Provider或sleep。

#### 任务输入与范围
前置任务：TASK-038。

权威规格：[01-lifecycle.md](01-lifecycle.md)、[04-database.md](04-database.md)、[05-api.md](05-api.md)、[07-engineering.md](07-engineering.md)、[09-acceptance.md](09-acceptance.md)。

允许范围：仅本Task用例涉及的domain/application/infrastructure/web及相应测试/迁移；无关模块不得顺手重构。

验证与交付：运行本Task新增/受影响的规则或集成测试；Java用backend Maven Wrapper，涉及FK/事务则用真实MySQL，禁止H2替代。


---

### TASK-040 — Intent Dispatcher

分别处理：

```text
PROPOSE_HYPOTHESIS

UPDATE_HYPOTHESIS

PROPOSE_EVIDENCE_LINK

COMPLETE_INVESTIGATION
```

`REQUEST_CAPABILITY`

此时暂时调用 Fake Capability Port。

M6 完成后接真实执行器。

#### 最终校验
每个Intent处理都绑定原run；跨run/取消后仅保留审计，不更新领域对象。
同轮Stop后除合法COMPLETE外均不展开下一步。Fake Capability仍必须经过合同层，不可永久绕过Gate。

#### 任务输入与范围
前置任务：TASK-039。

权威规格：[01-lifecycle.md](01-lifecycle.md)、[04-database.md](04-database.md)、[05-api.md](05-api.md)、[07-engineering.md](07-engineering.md)、[09-acceptance.md](09-acceptance.md)。

允许范围：仅本Task用例涉及的domain/application/infrastructure/web及相应测试/迁移；无关模块不得顺手重构。

验证与交付：运行本Task新增/受影响的规则或集成测试；Java用backend Maven Wrapper，涉及FK/事务则用真实MySQL，禁止H2替代。


---

### TASK-041 — Stop Race Handling

#### 目标
实现Stop/准入交错与旧run迟到结果的确定性处理。
#### 核心用例
Stop先COMMIT -> 无新Step/Invocation/预算扣减；
准入先COMMIT -> 仅原在途调用可以完成，下一次拒绝；
AI运行中Stop后返回REQUEST_CAPABILITY -> 不发Provider；
同轮合法COMPLETE可以收束；旧runCOMPLETE不创建本轮Diagnosis。
#### 完成标准
锁序一致，准入COMMIT为线性化边界；无需把网络放进事务，
不承诺取消Stop前已准入的物理网络请求。

#### 任务输入与范围
前置任务：TASK-040。

权威规格：[01-lifecycle.md](01-lifecycle.md)、[04-database.md](04-database.md)、[05-api.md](05-api.md)、[07-engineering.md](07-engineering.md)、[09-acceptance.md](09-acceptance.md)。

允许范围：仅本Task用例涉及的domain/application/infrastructure/web及相应测试/迁移；无关模块不得顺手重构。

验证与交付：运行本Task新增/受影响的规则或集成测试；Java用backend Maven Wrapper，涉及FK/事务则用真实MySQL，禁止H2替代。


---

### TASK-042 — Deterministic Termination

实现：

```text
USER_STOPPED

CAPABILITY_BUDGET_EXHAUSTED

INVESTIGATION_TIMEOUT

AI_RUNTIME_UNAVAILABLE
```

无法形成合法 Diagnosis：

创建：

```text
UNDETERMINED
```

然后：

```text
INVESTIGATING → DIAGNOSED
```

#### 本轮语义
只用本轮已取得合法草稿，否则新增UNDETERMINED；不得拿历史Diagnosis冒充本轮。
额度耗尽是当前run的合法终止，不是Incident永久不可继续；保存真实terminationReason。

#### 任务输入与范围
前置任务：TASK-041。

权威规格：[01-lifecycle.md](01-lifecycle.md)、[04-database.md](04-database.md)、[05-api.md](05-api.md)、[07-engineering.md](07-engineering.md)、[09-acceptance.md](09-acceptance.md)。

允许范围：仅本Task用例涉及的domain/application/infrastructure/web及相应测试/迁移；无关模块不得顺手重构。

验证与交付：运行本Task新增/受影响的规则或集成测试；Java用backend Maven Wrapper，涉及FK/事务则用真实MySQL，禁止H2替代。


---

### TASK-043 — Investigation Startup Recovery

#### 目标
实现Investigation启动恢复及无Worker归属工作的补派发。
#### 必须实现
保留run_no、Stop、计数、起点和deadline；旧进程RUNNING AgentStep/只读Invocation标中断，
不伪造Observation、不将Java中断计为模型连续失败。
已Stop、过期或额度耗尽直接收束；否则派发原run。
#### 完成标准
重启不获得新额度；Stop提交后崩溃不会重新问AI；已接受工作不会因afterCommit丢失永久卡住。
所有跨外部调用事务边界清晰。

#### 任务输入与范围
前置任务：TASK-042。

权威规格：[01-lifecycle.md](01-lifecycle.md)、[04-database.md](04-database.md)、[05-api.md](05-api.md)、[07-engineering.md](07-engineering.md)、[09-acceptance.md](09-acceptance.md)。

允许范围：仅本Task用例涉及的domain/application/infrastructure/web及相应测试/迁移；无关模块不得顺手重构。

验证与交付：运行本Task新增/受影响的规则或集成测试；Java用backend Maven Wrapper，涉及FK/事务则用真实MySQL，禁止H2替代。

---

## 14. M6 — OBSERVE Capability

本组沿用原任务编号；具体依赖按每个Task的“任务输入与范围”执行。

### TASK-044 — CapabilityRegistry

冻结 7 个：

```text
metrics.query

logs.search

cache.inspect

database.inspect

queue.inspect

service.inspect

service.restart
```

定义：

```text
mode

resource type

request schema

result schema

provider type

timeout

approval

risk
```

#### 任务输入与范围
前置任务：TASK-043。

权威规格：[04-database.md](04-database.md)、[05-api.md](05-api.md)、[06-capability.md](06-capability.md)、[07-engineering.md](07-engineering.md)。

允许范围：仅本Task用例涉及的domain/application/infrastructure/web及相应测试/迁移；无关模块不得顺手重构。

验证与交付：运行本Task新增/受影响的规则或集成测试；Java用backend Maven Wrapper，涉及FK/事务则用真实MySQL，禁止H2替代。


---

### TASK-045 — Capability Descriptor Builder

根据：

```text
ManagedResource
CapabilityBinding
ResourceBinding
Registry
```

只向 AI暴露：

> 当前实际允许的受控空间。

#### 任务输入与范围
前置任务：TASK-044。

权威规格：[04-database.md](04-database.md)、[05-api.md](05-api.md)、[06-capability.md](06-capability.md)、[07-engineering.md](07-engineering.md)。

允许范围：仅本Task用例涉及的domain/application/infrastructure/web及相应测试/迁移；无关模块不得顺手重构。

验证与交付：运行本Task新增/受影响的规则或集成测试；Java用backend Maven Wrapper，涉及FK/事务则用真实MySQL，禁止H2替代。


---

### TASK-046 — Provider Resolver

规则：

```text
0 Provider
→ CAPABILITY_PROVIDER_NOT_CONFIGURED

1 Provider
→ use

>1
→ CAPABILITY_PROVIDER_AMBIGUOUS
```

不得随机选。

#### 任务输入与范围
前置任务：TASK-045。

权威规格：[04-database.md](04-database.md)、[05-api.md](05-api.md)、[06-capability.md](06-capability.md)、[07-engineering.md](07-engineering.md)。

允许范围：仅本Task用例涉及的domain/application/infrastructure/web及相应测试/迁移；无关模块不得顺手重构。

验证与交付：运行本Task新增/受影响的规则或集成测试；Java用backend Maven Wrapper，涉及FK/事务则用真实MySQL，禁止H2替代。


---

### TASK-047 — Canonical JSON + Duplicate Guard

#### 目标
实现单一CanonicalJsonWriter和调查DuplicateGuard。
#### 指纹与范围
investigationId+capabilityKey+resourceId+schemaName+schemaVersion+canonical(arguments)；
不包含run_no。拒绝同指纹全部在途调用及finished_at位于默认30秒保护窗口的终态调用。
#### 完成标准
不能只按created_at查最近30秒；不能假设Investigation全历史最多12条。
重复拒绝发生在Invocation创建/扣预算前；Recovery按样本身份执行，不套此窗口。

#### 任务输入与范围
前置任务：TASK-046。

权威规格：[04-database.md](04-database.md)、[05-api.md](05-api.md)、[06-capability.md](06-capability.md)、[07-engineering.md](07-engineering.md)。

允许范围：仅本Task用例涉及的domain/application/infrastructure/web及相应测试/迁移；无关模块不得顺手重构。

验证与交付：运行本Task新增/受影响的规则或集成测试；Java用backend Maven Wrapper，涉及FK/事务则用真实MySQL，禁止H2替代。


---

### TASK-048 — CapabilityInvocation 执行骨架

#### 目标
形成统一OBSERVE准入、外部调用、结果落账骨架。
#### 必须实现
短事务内锁Incident/Investigation，检查run、Stop、deadline、预算以及完整Capability/Binding/参数/Duplicate Guard；
建立RUNNING Invocation，当前计数和累计计数各+1，COMMIT后Provider。
结果事务条件更新；成功才产生真实已脱敏Observation，失败仅错误记录。
恢复调用按Verification/criterion/sample准入，不扣run预算。
#### 完成标准
结果事务不再加预算；准入崩溃不退额、不虚构事实；
旧runProvider结果保留原审计但不改新run控制。所有网络不在DB事务。

#### 任务输入与范围
前置任务：TASK-047。

权威规格：[04-database.md](04-database.md)、[05-api.md](05-api.md)、[06-capability.md](06-capability.md)、[07-engineering.md](07-engineering.md)。

允许范围：仅本Task用例涉及的domain/application/infrastructure/web及相应测试/迁移；无关模块不得顺手重构。

验证与交付：运行本Task新增/受影响的规则或集成测试；Java用backend Maven Wrapper，涉及FK/事务则用真实MySQL，禁止H2替代。


---

### TASK-049 — Sanitizer Framework

实现基础敏感数据清理：

```text
Authorization

Bearer

Cookie

password

secret

api_key

access_token

refresh_token
```

所有外部数据：

```text
Provider
↓
Sanitizer
↓
Persistence / Observation / AI
```

#### 任务输入与范围
前置任务：TASK-048。

权威规格：[04-database.md](04-database.md)、[05-api.md](05-api.md)、[06-capability.md](06-capability.md)、[07-engineering.md](07-engineering.md)。

允许范围：仅本Task用例涉及的domain/application/infrastructure/web及相应测试/迁移；无关模块不得顺手重构。

验证与交付：运行本Task新增/受影响的规则或集成测试；Java用backend Maven Wrapper，涉及FK/事务则用真实MySQL，禁止H2替代。


---

### TASK-050 — RawResultStore

Application：

```text
RawResultStore
```

Infrastructure：

```text
LocalFileRawResultStore
```

文件内容：

> 已脱敏。

支持：

```text
file://...
```

#### 任务输入与范围
前置任务：TASK-049。

权威规格：[04-database.md](04-database.md)、[05-api.md](05-api.md)、[06-capability.md](06-capability.md)、[07-engineering.md](07-engineering.md)。

允许范围：仅本Task用例涉及的domain/application/infrastructure/web及相应测试/迁移；无关模块不得顺手重构。

验证与交付：运行本Task新增/受影响的规则或集成测试；Java用backend Maven Wrapper，涉及FK/事务则用真实MySQL，禁止H2替代。


---

### TASK-051 — ObservationExtractor

不同 Result：

```text
Metric

Log Pattern

Cache Status

Database Status

Queue Status

Service Status
```

使用确定性 Extractor。

不得调用 LLM 生成 Observation。

#### 事实边界
提取器不能从单点编造趋势，不能从当前digest编造未测基线。
Observation继承来源Invocation上下文；一调用可多Observation，但恢复样本身份只有一个。

#### 任务输入与范围
前置任务：TASK-050。

权威规格：[04-database.md](04-database.md)、[05-api.md](05-api.md)、[06-capability.md](06-capability.md)、[07-engineering.md](07-engineering.md)。

允许范围：仅本Task用例涉及的domain/application/infrastructure/web及相应测试/迁移；无关模块不得顺手重构。

验证与交付：运行本Task新增/受影响的规则或集成测试；Java用backend Maven Wrapper，涉及FK/事务则用真实MySQL，禁止H2替代。


---

### TASK-052 — metrics.query

实现：

```text
PrometheusMetricsQueryProvider
```

AI只能提供：

```text
metricKey

windowKey

comparePreviousWindow
```

必须实现：

```text
previous window = 等长紧邻

总范围 <= 60 min
```

禁止 AI PromQL。

#### P99真实落位
为ShortLink Demo确定histogram/percentile导出、label范围、查询窗口与单位转换。
空时序、NaN、缺前一窗口不能当0；比较窗口等长相邻，总范围<=60min。
此任务输出真实指标模板，不依赖任意AI PromQL。

#### 任务输入与范围
前置任务：TASK-051。

权威规格：[04-database.md](04-database.md)、[05-api.md](05-api.md)、[06-capability.md](06-capability.md)、[07-engineering.md](07-engineering.md)。

允许范围：仅本Task用例涉及的domain/application/infrastructure/web及相应测试/迁移；无关模块不得顺手重构。

验证与交付：运行本Task新增/受影响的规则或集成测试；Java用backend Maven Wrapper，涉及FK/事务则用真实MySQL，禁止H2替代。


---

### TASK-053 — logs.search

实现：

```text
LokiLogsSearchProvider
```

支持：

```text
window
severity
keywords
```

限制：

```text
keywords <= 5

raw matches <= 500

patterns <= 10

AI <= Top 5
```

禁止：

```text
LogQL
Regex from AI
```

#### 任务输入与范围
前置任务：TASK-052。

权威规格：[04-database.md](04-database.md)、[05-api.md](05-api.md)、[06-capability.md](06-capability.md)、[07-engineering.md](07-engineering.md)。

允许范围：仅本Task用例涉及的domain/application/infrastructure/web及相应测试/迁移；无关模块不得顺手重构。

验证与交付：运行本Task新增/受影响的规则或集成测试；Java用backend Maven Wrapper，涉及FK/事务则用真实MySQL，禁止H2替代。


---

### TASK-054 — cache.inspect

实现：

```text
RedisCacheInspectProvider
```

只允许：

```text
PING
INFO
```

及正式白名单。

禁止：

```text
GET
KEYS
SCAN
SET
DEL
```

#### 任务输入与范围
前置任务：TASK-053。

权威规格：[04-database.md](04-database.md)、[05-api.md](05-api.md)、[06-capability.md](06-capability.md)、[07-engineering.md](07-engineering.md)。

允许范围：仅本Task用例涉及的domain/application/infrastructure/web及相应测试/迁移；无关模块不得顺手重构。

验证与交付：运行本Task新增/受影响的规则或集成测试；Java用backend Maven Wrapper，涉及FK/事务则用真实MySQL，禁止H2替代。


---

### TASK-055 — database.inspect

实现：

```text
MySqlDatabaseInspectProvider
```

支持：

```text
SERVER_SUMMARY

CONNECTION_SUMMARY

SLOW_QUERIES

LOCK_WAITS
```

禁止：

```text
ARBITRARY_SQL
```

#### 任务输入与范围
前置任务：TASK-054。

权威规格：[04-database.md](04-database.md)、[05-api.md](05-api.md)、[06-capability.md](06-capability.md)、[07-engineering.md](07-engineering.md)。

允许范围：仅本Task用例涉及的domain/application/infrastructure/web及相应测试/迁移；无关模块不得顺手重构。

验证与交付：运行本Task新增/受影响的规则或集成测试；Java用backend Maven Wrapper，涉及FK/事务则用真实MySQL，禁止H2替代。


---

### TASK-056 — queue.inspect

实现：

```text
RedisQueueInspectProvider
```

针对：

```text
Redis Stream
```

只返回：

```text
长度
Pending
Lag
Consumer
时间
```

禁止读取：

```text
Message Payload
```

#### Redis语义
返回Binding指定组的lag与pendingCount，不把streamLength当积压，也不把lastDeliveredAt当lastAckAt。
lag未知为null；不得用0掩盖无法计算。保留同一组的只读统计白名单，不读取消息正文。

#### 任务输入与范围
前置任务：TASK-055。

权威规格：[04-database.md](04-database.md)、[05-api.md](05-api.md)、[06-capability.md](06-capability.md)、[07-engineering.md](07-engineering.md)。

允许范围：仅本Task用例涉及的domain/application/infrastructure/web及相应测试/迁移；无关模块不得顺手重构。

验证与交付：运行本Task新增/受影响的规则或集成测试；Java用backend Maven Wrapper，涉及FK/事务则用真实MySQL，禁止H2替代。


---

### TASK-057 — service.inspect

实现：

```text
DockerServiceInspectProvider
```

只返回白名单字段。

明确禁止：

```text
Environment
Secret
Mount detail
```

#### 任务输入与范围
前置任务：TASK-056。

权威规格：[04-database.md](04-database.md)、[05-api.md](05-api.md)、[06-capability.md](06-capability.md)、[07-engineering.md](07-engineering.md)。

允许范围：仅本Task用例涉及的domain/application/infrastructure/web及相应测试/迁移；无关模块不得顺手重构。

验证与交付：运行本Task新增/受影响的规则或集成测试；Java用backend Maven Wrapper，涉及FK/事务则用真实MySQL，禁止H2替代。


---

### TASK-058 — Capability Execution Integration

将：

```text
InvestigationOrchestrator
```

中的：

```text
REQUEST_CAPABILITY
```

真正接到：

```text
CapabilityExecutionService
```

形成完整循环：

```text
AI
→ Intent
→ Capability
→ Observation
→ 下一 AI Step
```

#### 完成证据
至少一个真实AI决策->真实OBSERVE->真实Observation->再次AI决策的闭合记录。
Fake通过只证明控制面，不是本Task最终真实集成DoD。

#### 任务输入与范围
前置任务：TASK-057。

权威规格：[04-database.md](04-database.md)、[05-api.md](05-api.md)、[06-capability.md](06-capability.md)、[07-engineering.md](07-engineering.md)。

允许范围：仅本Task用例涉及的domain/application/infrastructure/web及相应测试/迁移；无关模块不得顺手重构。

验证与交付：运行本Task新增/受影响的规则或集成测试；Java用backend Maven Wrapper，涉及FK/事务则用真实MySQL，禁止H2替代。

---

## 15. M7 — Diagnosis 收束

本组沿用原任务编号；具体依赖按每个Task的“任务输入与范围”执行。

### TASK-059 — COMPLETE_INVESTIGATION 全链路

AI 返回：

```text
COMPLETE_INVESTIGATION
```

Java：

```text
验证 Hypothesis
验证 Evidence
验证结论规则
创建 Diagnosis
状态转换
Timeline
```

#### 事务条件
本轮Diagnosis必须关联当前run，冻结真实主假设支持证据；旧runCOMPLETE只审计。
同轮Stop后的合法在途COMPLETE允许收束，已取消结果不得迁移状态。

#### 任务输入与范围
前置任务：TASK-058。

权威规格：[01-lifecycle.md](01-lifecycle.md)、[03-domain-model.md](03-domain-model.md)、[04-database.md](04-database.md)、[05-api.md](05-api.md)。

允许范围：仅本Task用例涉及的domain/application/infrastructure/web及相应测试/迁移；无关模块不得顺手重构。

验证与交付：运行本Task新增/受影响的规则或集成测试；Java用backend Maven Wrapper，涉及FK/事务则用真实MySQL，禁止H2替代。


---

### TASK-060 — Diagnosis Version Evolution

支持：

```text
Diagnosis v1

继续调查

Diagnosis v2
```

旧 Diagnosis：

> 不修改。

#### 任务输入与范围
前置任务：TASK-059。

权威规格：[01-lifecycle.md](01-lifecycle.md)、[03-domain-model.md](03-domain-model.md)、[04-database.md](04-database.md)、[05-api.md](05-api.md)。

允许范围：仅本Task用例涉及的domain/application/infrastructure/web及相应测试/迁移；无关模块不得顺手重构。

验证与交付：运行本Task新增/受影响的规则或集成测试；Java用backend Maven Wrapper，涉及FK/事务则用真实MySQL，禁止H2替代。


---

### TASK-061 — Undetermined Outcomes

覆盖：

```text
USER_STOPPED

TIMEOUT

BUDGET

AI unavailable
```

确保：

> 没有证据不会被强行写成 PRIMARY。

#### Continue用例
预算/超时后的显式Continue产生新run，历史仍保留；重启与Continue不得共用重置路径。

#### 任务输入与范围
前置任务：TASK-060。

权威规格：[01-lifecycle.md](01-lifecycle.md)、[03-domain-model.md](03-domain-model.md)、[04-database.md](04-database.md)、[05-api.md](05-api.md)。

允许范围：仅本Task用例涉及的domain/application/infrastructure/web及相应测试/迁移；无关模块不得顺手重构。

验证与交付：运行本Task新增/受影响的规则或集成测试；Java用backend Maven Wrapper，涉及FK/事务则用真实MySQL，禁止H2替代。

---

## 16. M8 — Remediation / Approval

本组沿用原任务编号；具体依赖按每个Task的“任务输入与范围”执行。

### TASK-062 — Remediation 数据结构

Flyway 创建：

```text
remediation_plan

remediation_action

approval_request
```

#### 任务输入与范围
前置任务：TASK-061。

权威规格：[01-lifecycle.md](01-lifecycle.md)、[04-database.md](04-database.md)、[05-api.md](05-api.md)、[06-capability.md](06-capability.md)、[07-engineering.md](07-engineering.md)。

允许范围：仅本Task用例涉及的domain/application/infrastructure/web及相应测试/迁移；无关模块不得顺手重构。

验证与交付：运行本Task新增/受影响的规则或集成测试；Java用backend Maven Wrapper，涉及FK/事务则用真实MySQL，禁止H2替代。


---

### TASK-063 — Remediation Draft Context

Java 根据：

```text
Current Diagnosis

Evidence Summary

Allowed CHANGE Actions
```

构建：

```text
RemediationDraftRequest
```

只有：

```text
PRIMARY_CAUSE_IDENTIFIED
POSSIBLE_CAUSE
```

允许。

#### 任务输入与范围
前置任务：TASK-062。

权威规格：[01-lifecycle.md](01-lifecycle.md)、[04-database.md](04-database.md)、[05-api.md](05-api.md)、[06-capability.md](06-capability.md)、[07-engineering.md](07-engineering.md)。

允许范围：仅本Task用例涉及的domain/application/infrastructure/web及相应测试/迁移；无关模块不得顺手重构。

验证与交付：运行本Task新增/受影响的规则或集成测试；Java用backend Maven Wrapper，涉及FK/事务则用真实MySQL，禁止H2替代。


---

### TASK-064 — Remediation Proposal 校验

AI返回：

```text
capabilityKey

targetResourceId

parameters

summary

expectedImpactSummary
```

Java负责产生：

```text
riskLevel

requiresApproval
```

AI输出中：

> 不存在 riskLevel。

#### 任务输入与范围
前置任务：TASK-063。

权威规格：[01-lifecycle.md](01-lifecycle.md)、[04-database.md](04-database.md)、[05-api.md](05-api.md)、[06-capability.md](06-capability.md)、[07-engineering.md](07-engineering.md)。

允许范围：仅本Task用例涉及的domain/application/infrastructure/web及相应测试/迁移；无关模块不得顺手重构。

验证与交付：运行本Task新增/受影响的规则或集成测试；Java用backend Maven Wrapper，涉及FK/事务则用真实MySQL，禁止H2替代。


---

### TASK-065 — request-remediation

严格实现：

```text
读取状态
↓
退出事务
↓
调用 AI
↓
重新检查状态
↓
短事务创建：
Plan
Action
Approval
状态迁移
Timeline
```

严禁：

> LLM 调用在 Transaction 中。

#### 任务输入与范围
前置任务：TASK-064。

权威规格：[01-lifecycle.md](01-lifecycle.md)、[04-database.md](04-database.md)、[05-api.md](05-api.md)、[06-capability.md](06-capability.md)、[07-engineering.md](07-engineering.md)。

允许范围：仅本Task用例涉及的domain/application/infrastructure/web及相应测试/迁移；无关模块不得顺手重构。

验证与交付：运行本Task新增/受影响的规则或集成测试；Java用backend Maven Wrapper，涉及FK/事务则用真实MySQL，禁止H2替代。


---

### TASK-066 — Approval API

实现：

```text
GET approval

approve

reject

cancel
```

Approval：

```text
PENDING → terminal
```

不可反转。

#### 分阶段边界
此处可完成查询/拒绝/撤回和approve的HTTP合同；
完整approve数据库事务依赖TASK-068、074～076、067和069，不得提前以半套Approval=APPROVED冒充成功。
开发中尚未接通的批准分支保持不可用；最终完成点为TASK-069。

#### 任务输入与范围
前置任务：TASK-065。

权威规格：[01-lifecycle.md](01-lifecycle.md)、[04-database.md](04-database.md)、[05-api.md](05-api.md)、[06-capability.md](06-capability.md)、[07-engineering.md](07-engineering.md)。

允许范围：仅本Task用例涉及的domain/application/infrastructure/web及相应测试/迁移；无关模块不得顺手重构。

验证与交付：运行本Task新增/受影响的规则或集成测试；Java用backend Maven Wrapper，涉及FK/事务则用真实MySQL，禁止H2替代。


---

### TASK-067 — Approval 并发与历史方案保护

#### 目标
补全审批并发、旧方案保护、取消联动与执行前恢复合同校验。
#### 必须前置
TASK-062～066、TASK-068、TASK-074～076完成后才实施真实批准校验。
#### 必须实现
Incident/Approval版本、最新Diagnosis、ACTIVE Plan、资源归属、CHANGE Binding可用、
唯一合法ACTIVE RecoveryPolicy；对策略进行类型/目标/采样检查。
取消等待审批的Incident同时取消Approval和Plan；回填TASK-019/026的真实Port行为。
#### 完成标准
旧Diagnosis方案不可执行；无/多Policy不发CHANGE、不创建可执行Execution；
并发决策只能一个获胜。

#### 任务输入与范围
前置任务：TASK-066、TASK-068、TASK-074、TASK-075、TASK-076。

权威规格：[01-lifecycle.md](01-lifecycle.md)、[04-database.md](04-database.md)、[05-api.md](05-api.md)、[06-capability.md](06-capability.md)、[07-engineering.md](07-engineering.md)。

允许范围：仅本Task用例涉及的domain/application/infrastructure/web及相应测试/迁移；无关模块不得顺手重构。

验证与交付：运行本Task新增/受影响的规则或集成测试；Java用backend Maven Wrapper，涉及FK/事务则用真实MySQL，禁止H2替代。

---

## 17. M9 — Action Execution

本组沿用原任务编号；具体依赖按每个Task的“任务输入与范围”执行。

### TASK-068 — ActionExecution 数据结构

创建：

```text
action_execution
```

约束：

```text
UNIQUE(remediation_action_id)

UNIQUE(idempotency_key)
```

#### 最终字段与迁移顺序
加入04指定的RecoveryPolicy快照列、执行上下文、核对计数/上限/时间字段。
本Task先创建列与唯一执行身份；Policy父表和FK在TASK-074接入，
真实PENDING只在所有前置完成的TASK-069创建，因此不会产生缺快照执行数据。

#### 任务输入与范围
前置任务：TASK-066。

权威规格：[01-lifecycle.md](01-lifecycle.md)、[04-database.md](04-database.md)、[05-api.md](05-api.md)、[06-capability.md](06-capability.md)、[07-engineering.md](07-engineering.md)。

允许范围：仅本Task用例涉及的domain/application/infrastructure/web及相应测试/迁移；无关模块不得顺手重构。

验证与交付：运行本Task新增/受影响的规则或集成测试；Java用backend Maven Wrapper，涉及FK/事务则用真实MySQL，禁止H2替代。


---

### TASK-069 — Approve → Execution

#### 目标
完成唯一正式approve事务与提交后执行派发。
#### 前置
TASK-067、068、074、075、076均已完成。
#### 同事务
验证审批/版本/当前Diagnosis/资源/Binding；
选择唯一合法ACTIVE RecoveryPolicy并保存完整恢复快照到Execution；
Approval APPROVED + ActionExecution PENDING + Incident EXECUTING + Timeline原子提交。
#### 完成标准
重复同决定返回原结果、不创建第二次Execution、不重发CHANGE；
提交后派发失败可补派发。无/多策略在任何外部副作用前拒绝。
本Task完成后回填TASK-066 approve分支的真实成功路径。

#### 任务输入与范围
前置任务：TASK-067、TASK-068、TASK-074、TASK-075、TASK-076。

权威规格：[01-lifecycle.md](01-lifecycle.md)、[04-database.md](04-database.md)、[05-api.md](05-api.md)、[06-capability.md](06-capability.md)、[07-engineering.md](07-engineering.md)。

允许范围：仅本Task用例涉及的domain/application/infrastructure/web及相应测试/迁移；无关模块不得顺手重构。

验证与交付：运行本Task新增/受影响的规则或集成测试；Java用backend Maven Wrapper，涉及FK/事务则用真实MySQL，禁止H2替代。


---

### TASK-070 — Docker service.restart Executor

实现：

```text
DockerServiceRestartExecutor
```

要求：

> 使用 Docker Engine API Java Client。

正式禁止：

```text
Runtime.exec

ProcessBuilder

docker CLI

shell
```

#### 任务输入与范围
前置任务：TASK-069。

权威规格：[01-lifecycle.md](01-lifecycle.md)、[04-database.md](04-database.md)、[05-api.md](05-api.md)、[06-capability.md](06-capability.md)、[07-engineering.md](07-engineering.md)。

允许范围：仅本Task用例涉及的domain/application/infrastructure/web及相应测试/迁移；无关模块不得顺手重构。

验证与交付：运行本Task新增/受影响的规则或集成测试；Java用backend Maven Wrapper，涉及FK/事务则用真实MySQL，禁止H2替代。


---

### TASK-071 — Execution Worker

流程：

```text
PENDING
→
RUNNING
→
External Call
→
SUCCEEDED / FAILED
```

任何 Docker 调用：

> 不在 DB Transaction。

#### 准入与结果
唯一PENDING->RUNNING条件更新获胜者可派发一次CHANGE；保存受信目标身份。
明确失败/成功与未知分支区分；RUNNING恢复不能当成“还没发送”。
Policy升级不阻断成功落账；使用已冻结恢复合同创建Verification的集成在080完成。

#### 任务输入与范围
前置任务：TASK-070。

权威规格：[01-lifecycle.md](01-lifecycle.md)、[04-database.md](04-database.md)、[05-api.md](05-api.md)、[06-capability.md](06-capability.md)、[07-engineering.md](07-engineering.md)。

允许范围：仅本Task用例涉及的domain/application/infrastructure/web及相应测试/迁移；无关模块不得顺手重构。

验证与交付：运行本Task新增/受影响的规则或集成测试；Java用backend Maven Wrapper，涉及FK/事务则用真实MySQL，禁止H2替代。


---

### TASK-072 — Execution Reconciliation

#### 目标
实现结果不确定时的有界只读reconciliation，不增加UNKNOWN业务状态。
#### 必须实现
复用底层只读RuntimeInspector，不建立Invocation/Observation，不进AI、不扣run预算。
每次inspect之前先原子增加reconciliation_attempt_count、last_reconciliation_at并COMMIT；
持久化上限与首次核对deadline，不因重启刷新。
默认最多3次/间隔5s/单次5s/总期限60s，均受信配置。
#### 结果
同一目标容器RUNNING且startedAt>execution.startedAt才确认启动效果；
未知可在剩余额度内再次只读探测。耗尽/到期仍未知则FAILED/EXECUTION_RESULT_UNCERTAIN，回DIAGNOSED。
#### 禁止
再次restart；把一条Execution记录等同远端exactly-once；在核对崩溃后永久禁止剩余合法只读尝试。

#### 任务输入与范围
前置任务：TASK-071。

权威规格：[01-lifecycle.md](01-lifecycle.md)、[04-database.md](04-database.md)、[05-api.md](05-api.md)、[06-capability.md](06-capability.md)、[07-engineering.md](07-engineering.md)。

允许范围：仅本Task用例涉及的domain/application/infrastructure/web及相应测试/迁移；无关模块不得顺手重构。

验证与交付：运行本Task新增/受影响的规则或集成测试；Java用backend Maven Wrapper，涉及FK/事务则用真实MySQL，禁止H2替代。


---

### TASK-073 — Execution Startup Recovery

#### 目标
接入Execution启动恢复和补派发。
#### 必须实现
PENDING重新派发；RUNNING绝不重发CHANGE，只恢复剩余有界只读核对；
终态不派发。启动扫描不刷新执行快照、尝试上限、deadline。
#### 验证
PENDING提交后派发前崩溃、CHANGE响应丢失、核对登记后崩溃、
线程池拒绝后补派发、重复Worker争抢均有针对性用例。

#### 任务输入与范围
前置任务：TASK-069、TASK-071、TASK-072、TASK-035。

权威规格：[01-lifecycle.md](01-lifecycle.md)、[04-database.md](04-database.md)、[05-api.md](05-api.md)、[06-capability.md](06-capability.md)、[07-engineering.md](07-engineering.md)。

允许范围：仅本Task用例涉及的domain/application/infrastructure/web及相应测试/迁移；无关模块不得顺手重构。

验证与交付：运行本Task新增/受影响的规则或集成测试；Java用backend Maven Wrapper，涉及FK/事务则用真实MySQL，禁止H2替代。

---

## 18. M10 — Recovery Verification

本组沿用原任务编号；具体依赖按每个Task的“任务输入与范围”执行。

**策略基础TASK-074～076必须前置到TASK-067/069；其余恢复执行任务在Execution之后完成。**

### TASK-074 — RecoveryPolicy / Verification 数据结构

创建：

```text
recovery_policy

recovery_verification
```

约束：

```text
UNIQUE(action_execution_id)
```

允许：

```text
action_execution_id = NULL
```

#### 前置执行调整
本Task在真实审批TASK-067/069之前执行，且依赖TASK-068使Execution父表已存在。
建立RecoveryPolicy/Verification、deadline、结果typed payload、UNIQUE(action_execution_id)；
补Invocation/Observation->Verification FK、恢复样本唯一键和Execution->Policy FK。
不允许以“父表以后建”为理由跳过最终FK。

#### 任务输入与范围
前置任务：TASK-068、TASK-021。

权威规格：[01-lifecycle.md](01-lifecycle.md)、[04-database.md](04-database.md)、[05-api.md](05-api.md)、[06-capability.md](06-capability.md)、[09-acceptance.md](09-acceptance.md)。

允许范围：仅本Task用例涉及的domain/application/infrastructure/web及相应测试/迁移；无关模块不得顺手重构。

验证与交付：运行本Task新增/受影响的规则或集成测试；Java用backend Maven Wrapper，涉及FK/事务则用真实MySQL，禁止H2替代。


---

### TASK-075 — RecoveryPolicy Criteria V1

实现：

```text
recovery.policy.criteria / 1
```

Typed Model：

```text
RecoveryPolicyCriteriaV1

RecoveryCriterionV1

RecoverySamplingV1

RecoveryPredicateV1
```

#### 完整协议
按06实现有序Criteria、criterionKey、类型化arguments、注册标量field、sampling、
maxDurationSeconds/maxSampleAgeSeconds/maxGapSeconds及三值结果。
MONOTONIC_TREND支持S3健康区间语义，不把严格下降作为唯一成功。

#### 任务输入与范围
前置任务：TASK-074、TASK-029、TASK-044。

权威规格：[01-lifecycle.md](01-lifecycle.md)、[04-database.md](04-database.md)、[05-api.md](05-api.md)、[06-capability.md](06-capability.md)、[09-acceptance.md](09-acceptance.md)。

允许范围：仅本Task用例涉及的domain/application/infrastructure/web及相应测试/迁移；无关模块不得顺手重构。

验证与交付：运行本Task新增/受影响的规则或集成测试；Java用backend Maven Wrapper，涉及FK/事务则用真实MySQL，禁止H2替代。


---

### TASK-076 — RecoveryPolicy Activation

ACTIVE 前验证：

```text
Capability exists

OBSERVE

Binding enabled

Provider unique

Resource same ManagedSystem

Predicate compatible with Result Schema
```

同 Resource：

```text
最多一个 ACTIVE Policy
```

#### 提前提供合法策略
完成S3资源的合法Policy Seed，使TASK-067/069能在执行前冻结真实合同。
同资源唯一ACTIVE通过稳定Resource父行锁保护；检查非空且至少一required。
S3具体B/C/D/A配置按09，不复制旧两项pending趋势示例。

#### 任务输入与范围
前置任务：TASK-075、TASK-046。

权威规格：[01-lifecycle.md](01-lifecycle.md)、[04-database.md](04-database.md)、[05-api.md](05-api.md)、[06-capability.md](06-capability.md)、[09-acceptance.md](09-acceptance.md)。

允许范围：仅本Task用例涉及的domain/application/infrastructure/web及相应测试/迁移；无关模块不得顺手重构。

验证与交付：运行本Task新增/受影响的规则或集成测试；Java用backend Maven Wrapper，涉及FK/事务则用真实MySQL，禁止H2替代。


---

### TASK-077 — Recovery Predicate Evaluator

#### 目标
实现受控FIELD_EQUALS、NUMERIC_COMPARE、MONOTONIC_TREND及三值合取。
#### 必须实现
任意required有效FALSE优先FAILED；无FALSE但有UNKNOWN为INCONCLUSIVE；全部TRUE才PASSED。
UNKNOWN不得覆盖明确FALSE。S3已在健康区间的0序列与区间内波动允许通过；
lag下降但pending异常仍不通过。字段缺失/NaN/null不可当0。
#### 禁止
SpEL、JavaScript、Groovy、SQL或任意JSONPath表达式求值。
#### 验证
ACC-FINAL-09/11/12，包含FALSE+UNKNOWN、TRUE+UNKNOWN及过期样本。

#### 任务输入与范围
前置任务：TASK-073。

权威规格：[01-lifecycle.md](01-lifecycle.md)、[04-database.md](04-database.md)、[05-api.md](05-api.md)、[06-capability.md](06-capability.md)、[09-acceptance.md](09-acceptance.md)。

允许范围：仅本Task用例涉及的domain/application/infrastructure/web及相应测试/迁移；无关模块不得顺手重构。

验证与交付：运行本Task新增/受影响的规则或集成测试；Java用backend Maven Wrapper，涉及FK/事务则用真实MySQL，禁止H2替代。


---

### TASK-078 — Recovery Sampling Runner

#### 目标
实现可追溯采样槽位与受控等待。
#### 必须实现
verificationId+criterionKey+sampleIndex唯一身份；sampleIndex从1开始。
一次逻辑样本对应一个Invocation，不因恢复而覆盖/隐藏重试。
保存真实采样时间，按interval等待；deadline/maxAge/maxGap来自Snapshot。
#### 完成标准
所有sleep在事务外；Recovery不经过调查Duplicate窗口、不扣run预算；
中断或丢样本记UNKNOWN，不造Observation或修改旧时间。

#### 任务输入与范围
前置任务：TASK-077。

权威规格：[01-lifecycle.md](01-lifecycle.md)、[04-database.md](04-database.md)、[05-api.md](05-api.md)、[06-capability.md](06-capability.md)、[09-acceptance.md](09-acceptance.md)。

允许范围：仅本Task用例涉及的domain/application/infrastructure/web及相应测试/迁移；无关模块不得顺手重构。

验证与交付：运行本Task新增/受影响的规则或集成测试；Java用backend Maven Wrapper，涉及FK/事务则用真实MySQL，禁止H2替代。


---

### TASK-079 — Recovery Verification Runner

#### 目标
依Snapshot顺序执行Criterion/采样/谓词，得到唯一整体结果。
#### 必须实现
UNKNOWN不立即短路；继续后续可执行检查以发现决定性FALSE。
允许明确FALSE短路，但未执行项如实记UNKNOWN/原因；
终态持久化checks、样本Invocation引用与总体摘要。
#### 完成标准
结果算法与077同一实现，不在Runner/UI各维护一套优先级；AI完全不参与。

#### 任务输入与范围
前置任务：TASK-078。

权威规格：[01-lifecycle.md](01-lifecycle.md)、[04-database.md](04-database.md)、[05-api.md](05-api.md)、[06-capability.md](06-capability.md)、[09-acceptance.md](09-acceptance.md)。

允许范围：仅本Task用例涉及的domain/application/infrastructure/web及相应测试/迁移；无关模块不得顺手重构。

验证与交付：运行本Task新增/受影响的规则或集成测试；Java用backend Maven Wrapper，涉及FK/事务则用真实MySQL，禁止H2替代。


---

### TASK-080 — Execution Success → Verification

#### 目标
将确定Execution成功原子衔接到Verification。
#### 同事务
Execution SUCCEEDED + Plan EXECUTED；
从Execution已有recovery_policy_snapshot创建唯一PENDING Verification并冻结deadline；
Incident EXECUTING->VERIFYING + Timeline；COMMIT后派发。
#### 禁止
重新SELECT当前ACTIVE策略决定是否允许保存已经发生的执行事实。
#### 完成标准
批准后Policy退休/升级不改本次合同；重复完成不创建第二次Verification。

#### 任务输入与范围
前置任务：TASK-069、TASK-071、TASK-074、TASK-075、TASK-079。

权威规格：[01-lifecycle.md](01-lifecycle.md)、[04-database.md](04-database.md)、[05-api.md](05-api.md)、[06-capability.md](06-capability.md)、[09-acceptance.md](09-acceptance.md)。

允许范围：仅本Task用例涉及的domain/application/infrastructure/web及相应测试/迁移；无关模块不得顺手重构。

验证与交付：运行本Task新增/受影响的规则或集成测试；Java用backend Maven Wrapper，涉及FK/事务则用真实MySQL，禁止H2替代。


---

### TASK-081 — Manual Verify Recovery

实现：

```text
POST
/incidents/{key}/actions/verify-recovery
```

支持：

> 用户已经在 OpsPilot 外部完成处理。

无需：

```text
ActionExecution
```

#### 最终合同
无Execution的外部处理入口，在自己的创建事务选唯一ACTIVE策略、完整Snapshot和deadline。
新请求需校验没有PENDING/RUNNING Verification，不更新旧结果或借重试刷新旧期限。

#### 任务输入与范围
前置任务：TASK-080。

权威规格：[01-lifecycle.md](01-lifecycle.md)、[04-database.md](04-database.md)、[05-api.md](05-api.md)、[06-capability.md](06-capability.md)、[09-acceptance.md](09-acceptance.md)。

允许范围：仅本Task用例涉及的domain/application/infrastructure/web及相应测试/迁移；无关模块不得顺手重构。

验证与交付：运行本Task新增/受影响的规则或集成测试；Java用backend Maven Wrapper，涉及FK/事务则用真实MySQL，禁止H2替代。


---

### TASK-082 — Verification Outcome Transition

实现：

```text
PASSED
→ RESOLVED

FAILED
→ INVESTIGATING

INCONCLUSIVE
→ DIAGNOSED
```

PASSED：

同时写：

```text
resolved_at
```

#### FAILED回调查
复用统一resumeInvestigation，在同一结果事务启动新run并迁移INVESTIGATING，提交后派发。
不重放上一次CHANGE；INCONCLUSIVE不自动新run，保留用户选择。

#### 任务输入与范围
前置任务：TASK-081。

权威规格：[01-lifecycle.md](01-lifecycle.md)、[04-database.md](04-database.md)、[05-api.md](05-api.md)、[06-capability.md](06-capability.md)、[09-acceptance.md](09-acceptance.md)。

允许范围：仅本Task用例涉及的domain/application/infrastructure/web及相应测试/迁移；无关模块不得顺手重构。

验证与交付：运行本Task新增/受影响的规则或集成测试；Java用backend Maven Wrapper，涉及FK/事务则用真实MySQL，禁止H2替代。


---

### TASK-083 — Verification Startup Recovery

#### 目标
接入Verification PENDING/RUNNING启动恢复与无Worker补派发。
#### 必须实现
按持久化criterion/sample身份读取快照、原deadline和已完成结果；
成功且仍有效样本复用，遗留RUNNING样本记中断，不在同slot重试。
原采样计划仍允许的未准入样本继续；超期/间断/不足按UNKNOWN参与FAILED优先矩阵。
#### 完成标准
不能从Observation条数猜sampleIndex，不能刷新时间，不复制调查Observation。
ACC-FINAL-10通过后，恢复控制流才闭合。

#### 任务输入与范围
前置任务：TASK-078、TASK-079、TASK-080、TASK-081、TASK-082、TASK-035。

权威规格：[01-lifecycle.md](01-lifecycle.md)、[04-database.md](04-database.md)、[05-api.md](05-api.md)、[06-capability.md](06-capability.md)、[09-acceptance.md](09-acceptance.md)。

允许范围：仅本Task用例涉及的domain/application/infrastructure/web及相应测试/迁移；无关模块不得顺手重构。

验证与交付：运行本Task新增/受影响的规则或集成测试；Java用backend Maven Wrapper，涉及FK/事务则用真实MySQL，禁止H2替代。

---

## 19. M11 — 产品查询 / Timeline / SSE

本组沿用原任务编号；具体依赖按每个Task的“任务输入与范围”执行。

### TASK-084 — Timeline Query

实现：

```text
GET /timeline
```

使用：

```text
afterId
limit
```

支持：

> append-only。

#### 任务输入与范围
前置任务：TASK-083。

权威规格：[04-database.md](04-database.md)、[05-api.md](05-api.md)、[07-engineering.md](07-engineering.md)。

允许范围：仅本Task用例涉及的domain/application/infrastructure/web及相应测试/迁移；无关模块不得顺手重构。

验证与交付：运行本Task新增/受影响的规则或集成测试；Java用backend Maven Wrapper，涉及FK/事务则用真实MySQL，禁止H2替代。


---

### TASK-085 — IncidentDetailView

将：

```text
Incident
Diagnosis
Remediation
Recovery
```

组合为：

```text
用户默认页面数据
```

包括：

```text
当前影响

当前状态

当前判断

为什么这么判断

处理建议

恢复情况
```

#### 最终View
Snapshot包含同一一致性读取的lastTimelineEventId、runNo、stopRequested；
分清当前run预算与历史总数，Recovery输出三值检查与整体状态。

#### 任务输入与范围
前置任务：TASK-084。

权威规格：[04-database.md](04-database.md)、[05-api.md](05-api.md)、[07-engineering.md](07-engineering.md)。

允许范围：仅本Task用例涉及的domain/application/infrastructure/web及相应测试/迁移；无关模块不得顺手重构。

验证与交付：运行本Task新增/受影响的规则或集成测试；Java用backend Maven Wrapper，涉及FK/事务则用真实MySQL，禁止H2替代。


---

### TASK-086 — availableActions

唯一权威：

> Java。

根据当前真实状态产生：

```text
START_INVESTIGATION

STOP_INVESTIGATION

CONTINUE_INVESTIGATION

REQUEST_REMEDIATION

VERIFY_RECOVERY

CANCEL_INCIDENT
```

前端：

> 不复制状态机。

#### 任务输入与范围
前置任务：TASK-085。

权威规格：[04-database.md](04-database.md)、[05-api.md](05-api.md)、[07-engineering.md](07-engineering.md)。

允许范围：仅本Task用例涉及的domain/application/infrastructure/web及相应测试/迁移；无关模块不得顺手重构。

验证与交付：运行本Task新增/受影响的规则或集成测试；Java用backend Maven Wrapper，涉及FK/事务则用真实MySQL，禁止H2替代。


---

### TASK-087 — SSE Hub

Spring MVC：

```text
SseEmitter
```

建立：

```text
IncidentSseHub
```

#### 任务输入与范围
前置任务：TASK-086。

权威规格：[04-database.md](04-database.md)、[05-api.md](05-api.md)、[07-engineering.md](07-engineering.md)。

允许范围：仅本Task用例涉及的domain/application/infrastructure/web及相应测试/迁移；无关模块不得顺手重构。

验证与交付：运行本Task新增/受影响的规则或集成测试；Java用backend Maven Wrapper，涉及FK/事务则用真实MySQL，禁止H2替代。


---

### TASK-088 — After Commit Event

流程：

```text
DB COMMIT
↓
AfterCommit
↓
SSE
```

禁止：

> 未提交数据先推浏览器。

#### 事件顺序
同Incident Timeline追加先锁Incident；afterCommit只唤醒发送端从DB游标有序补读，
不能把回调顺序当提交顺序或把尚未提交的数据推浏览器。

#### 任务输入与范围
前置任务：TASK-087。

权威规格：[04-database.md](04-database.md)、[05-api.md](05-api.md)、[07-engineering.md](07-engineering.md)。

允许范围：仅本Task用例涉及的domain/application/infrastructure/web及相应测试/迁移；无关模块不得顺手重构。

验证与交付：运行本Task新增/受影响的规则或集成测试；Java用backend Maven Wrapper，涉及FK/事务则用真实MySQL，禁止H2替代。


---

### TASK-089 — SSE Reconnect

支持：

```text
Last-Event-ID
```

通过 Timeline：

> 补发遗漏事件。

不引入：

```text
Transactional Outbox
```

#### 完成标准
消除Snapshot到订阅、历史追赶到实时注册的丢事件空隙；前端按event id去重。
覆盖ACC-FINAL-15，仍不建设Outbox。

#### 任务输入与范围
前置任务：TASK-088。

权威规格：[04-database.md](04-database.md)、[05-api.md](05-api.md)、[07-engineering.md](07-engineering.md)。

允许范围：仅本Task用例涉及的domain/application/infrastructure/web及相应测试/迁移；无关模块不得顺手重构。

验证与交付：运行本Task新增/受影响的规则或集成测试；Java用backend Maven Wrapper，涉及FK/事务则用真实MySQL，禁止H2替代。

---

## 20. M12 — Fault Lab

本组沿用原任务编号；具体依赖按每个Task的“任务输入与范围”执行。

### TASK-090 — Fault Lab 基础模型

创建：

```text
fault_experiment
```

以及代码 / YAML 场景定义：

```text
redis-latency

mysql-slow-query

statistics-consumer-stop
```

#### 任务输入与范围
前置任务：TASK-089。

权威规格：[00-product.md](00-product.md)、[06-capability.md](06-capability.md)、[07-engineering.md](07-engineering.md)、[09-acceptance.md](09-acceptance.md)。

允许范围：FaultLab与独立ShortLink Demo Profile/配置；不得扩大生产控制面。

验证与交付：运行本Task新增/受影响的规则或集成测试；Java用backend Maven Wrapper，涉及FK/事务则用真实MySQL，禁止H2替代。


---

### TASK-091 — Ground Truth Isolation

独立：

```text
FaultLabService

EvaluationService
```

能够读取：

```text
ground_truth_payload
```

明确测试：

```text
InvestigationContextBuilder
```

永远无法读取 Ground Truth。

#### 任务输入与范围
前置任务：TASK-090。

权威规格：[00-product.md](00-product.md)、[06-capability.md](06-capability.md)、[07-engineering.md](07-engineering.md)、[09-acceptance.md](09-acceptance.md)。

允许范围：FaultLab与独立ShortLink Demo Profile/配置；不得扩大生产控制面。

验证与交付：运行本Task新增/受影响的规则或集成测试；Java用backend Maven Wrapper，涉及FK/事务则用真实MySQL，禁止H2替代。


---

### TASK-092 — Fault Inject / Reset API

实现：

```text
GET scenarios

POST inject

POST reset
```

只允许：

```text
DEMO / TEST
```

环境。

Production：

```text
FAULT_SCENARIO_NOT_ALLOWED
```

#### 任务输入与范围
前置任务：TASK-091。

权威规格：[00-product.md](00-product.md)、[06-capability.md](06-capability.md)、[07-engineering.md](07-engineering.md)、[09-acceptance.md](09-acceptance.md)。

允许范围：FaultLab与独立ShortLink Demo Profile/配置；不得扩大生产控制面。

验证与交付：运行本Task新增/受影响的规则或集成测试；Java用backend Maven Wrapper，涉及FK/事务则用真实MySQL，禁止H2替代。


---

### TASK-093 — Statistics Consumer Stop Injector

#### 目标
在独立Demo部署中完成同产物project-api/statistics-consumer的Runtime拆分与S3注入。
#### 允许修改
ShortLink Demo Profile、消费者开关、Docker镜像/Compose和FaultLab适配；记录靶场仓库提交/镜像版本。
不推翻原compose.dev.yaml，不拆成两个业务项目。
#### 必须实现
真实Docker stop指定consumer；Producer继续、lag增长、主API健康Gate；
确认ACK在成功处理后、PEL/重领条件与健康pending基线。
#### 隔离
控制日志/容器控制信息和GroundTruth不进入AI调查；生产Profile禁用FaultHook。

#### 任务输入与范围
前置任务：TASK-092。

权威规格：[00-product.md](00-product.md)、[06-capability.md](06-capability.md)、[07-engineering.md](07-engineering.md)、[09-acceptance.md](09-acceptance.md)。

允许范围：FaultLab与独立ShortLink Demo Profile/配置；不得扩大生产控制面。

验证与交付：运行本Task新增/受影响的规则或集成测试；Java用backend Maven Wrapper，涉及FK/事务则用真实MySQL，禁止H2替代。


---

### TASK-094 — Redis Latency Injector

#### 目标
实现S1真实Redis网络延迟注入、Gate及Reset。
#### 必须实现
Toxiproxy downstream latency默认600ms、jitter0、toxicity1；
ShortLink与OpsPilot Redis Provider经相同Proxy。
禁用/调整Demo本地缓存以确保真实经过Redis；5次PING中位数Gate+HTTP退化分支，
按09保存LATENCY/ERROR_RATE/BOTH及真实基线。
#### 完成标准
未达Gate归SETUP_FAILED；Reset真实移除toxic，不伪造任何Observation。

#### 任务输入与范围
前置任务：TASK-093。

权威规格：[00-product.md](00-product.md)、[06-capability.md](06-capability.md)、[07-engineering.md](07-engineering.md)、[09-acceptance.md](09-acceptance.md)。

允许范围：FaultLab与独立ShortLink Demo Profile/配置；不得扩大生产控制面。

验证与交付：运行本Task新增/受影响的规则或集成测试；Java用backend Maven Wrapper，涉及FK/事务则用真实MySQL，禁止H2替代。


---

### TASK-095 — MySQL Slow Query Injector

#### 目标
实现S2真实数据库侧慢工作、应用Pool争用与可重复Gate。
#### 必须实现
仅Demo Profile慢任务通过project-api自己的Hikari Pool，产生可见且不泄漏控制标签的真实digest。
保留初始maxPool8/slowWorkers7/约3秒/5RPS配方，在目标环境按09实际Gate校准；
记录最终负载、时间、digest及HTTP症状分支。
#### Reset
停止慢负载并等待实际工作结束；仅Demo控制凭证清理对应Performance Schema统计；
调查只读账号无清理权限。下一次Preflight达健康再开始。
#### 完成标准
真实pending和慢计时可观察，未达Gate归SETUP_FAILED，不用Fake统计让Agent通过。

#### 任务输入与范围
前置任务：TASK-094。

权威规格：[00-product.md](00-product.md)、[06-capability.md](06-capability.md)、[07-engineering.md](07-engineering.md)、[09-acceptance.md](09-acceptance.md)。

允许范围：FaultLab与独立ShortLink Demo Profile/配置；不得扩大生产控制面。

验证与交付：运行本Task新增/受影响的规则或集成测试；Java用backend Maven Wrapper，涉及FK/事务则用真实MySQL，禁止H2替代。

---

## 21. M13 — 前端与 Demo 集成

本组沿用原任务编号；具体依赖按每个Task的“任务输入与范围”执行。

### TASK-096 — Web 基础壳

实现：

```text
/

 /systems

 /incidents

 /lab
```

技术：

```text
React
TypeScript
Vite
```

风格：

> 产品界面，不做传统后台管理风。

#### 任务输入与范围
前置任务：TASK-095。

权威规格：[00-product.md](00-product.md)、[05-api.md](05-api.md)、[09-acceptance.md](09-acceptance.md)。

允许范围：web/对应页面、生成类型与必要交互测试；不在前端复制状态机。

验证与交付：运行`cd web && npm run typecheck && npm run build`；检查本页面必要状态，不铺全站E2E。


---

### TASK-097 — Systems 页面

展示：

```text
业务系统

系统组件

可用能力

恢复标准摘要
```

默认不展示：

```text
Credential
Provider Secret
```

#### 任务输入与范围
前置任务：TASK-096。

权威规格：[00-product.md](00-product.md)、[05-api.md](05-api.md)、[09-acceptance.md](09-acceptance.md)。

允许范围：web/对应页面、生成类型与必要交互测试；不在前端复制状态机。

验证与交付：运行`cd web && npm run typecheck && npm run build`；检查本页面必要状态，不铺全站E2E。


---

### TASK-098 — Incident List

展示：

```text
标题
系统
影响
状态
发现时间
```

状态使用自然中文。

#### 任务输入与范围
前置任务：TASK-097。

权威规格：[00-product.md](00-product.md)、[05-api.md](05-api.md)、[09-acceptance.md](09-acceptance.md)。

允许范围：web/对应页面、生成类型与必要交互测试；不在前端复制状态机。

验证与交付：运行`cd web && npm run typecheck && npm run build`；检查本页面必要状态，不铺全站E2E。


---

### TASK-099 — Incident Detail 核心页

默认顺序：

```text
当前影响

当前状态

当前判断

为什么这么判断

处理建议

恢复情况
```

技术信息：

> 折叠到技术详情。

#### 任务输入与范围
前置任务：TASK-098。

权威规格：[00-product.md](00-product.md)、[05-api.md](05-api.md)、[09-acceptance.md](09-acceptance.md)。

允许范围：web/对应页面、生成类型与必要交互测试；不在前端复制状态机。

验证与交付：运行`cd web && npm run typecheck && npm run build`；检查本页面必要状态，不铺全站E2E。


---

### TASK-100 — Investigation Timeline UI

连接 SSE。

显示：

```text
正在检查……

发现……

正在验证……
```

不展示：

> 模型 Chain of Thought。

#### 任务输入与范围
前置任务：TASK-099。

权威规格：[00-product.md](00-product.md)、[05-api.md](05-api.md)、[09-acceptance.md](09-acceptance.md)。

允许范围：web/对应页面、生成类型与必要交互测试；不在前端复制状态机。

验证与交付：运行`cd web && npm run typecheck && npm run build`；检查本页面必要状态，不铺全站E2E。


---

### TASK-101 — Approval UI

清晰显示：

```text
操作是什么

目标是什么

为什么建议

预计影响

Java 风险等级
```

批准：

> 明确人工动作。

#### 任务输入与范围
前置任务：TASK-100。

权威规格：[00-product.md](00-product.md)、[05-api.md](05-api.md)、[09-acceptance.md](09-acceptance.md)。

允许范围：web/对应页面、生成类型与必要交互测试；不在前端复制状态机。

验证与交付：运行`cd web && npm run typecheck && npm run build`；检查本页面必要状态，不铺全站E2E。


---

### TASK-102 — Recovery UI

区分：

```text
操作执行成功
```

和：

```text
故障已经恢复
```

展示 Recovery：

```text
Consumer UP

Backlog:
2180
→ 1230
→ 410
→ 35
```

#### 最终展示
展示lag未投递积压、pending已投递未确认、Consumer运行及UNKNOWN原因；
0,0,0,0不是失败，不把“还没取得数据”显示成健康0。

#### 任务输入与范围
前置任务：TASK-101。

权威规格：[00-product.md](00-product.md)、[05-api.md](05-api.md)、[09-acceptance.md](09-acceptance.md)。

允许范围：web/对应页面、生成类型与必要交互测试；不在前端复制状态机。

验证与交付：运行`cd web && npm run typecheck && npm run build`；检查本页面必要状态，不铺全站E2E。


---

### TASK-103 — Technical Detail UI

展示：

```text
Hypothesis

Observation

Evidence

Diagnosis History

Capability Invocation

Execution

Verification
```

默认用户界面：

> 不暴露大量内部英文术语。

#### 任务输入与范围
前置任务：TASK-102。

权威规格：[00-product.md](00-product.md)、[05-api.md](05-api.md)、[09-acceptance.md](09-acceptance.md)。

允许范围：web/对应页面、生成类型与必要交互测试；不在前端复制状态机。

验证与交付：运行`cd web && npm run typecheck && npm run build`；检查本页面必要状态，不铺全站E2E。


---

### TASK-104 — Fault Lab UI

能够：

```text
注入 S1

注入 S2

注入 S3

恢复环境

打开对应 Incident
```

Ground Truth：

> 不显示。

#### 任务输入与范围
前置任务：TASK-103。

权威规格：[00-product.md](00-product.md)、[05-api.md](05-api.md)、[09-acceptance.md](09-acceptance.md)。

允许范围：web/对应页面、生成类型与必要交互测试；不在前端复制状态机。

验证与交付：运行`cd web && npm run typecheck && npm run build`；检查本页面必要状态，不铺全站E2E。


---

### TASK-105 — Demo Docker Compose

完整启动：

```text
opspilot-server

ai-runtime

web

opspilot-mysql

Prometheus

Loki

ShortLink Demo

Redis

需要的 Fault Infrastructure
```

#### Demo增补
加入Toxiproxy、独立project-api与statistics-consumer、Load Generator；
保持ShortLink原开发Compose可继续使用，Demo控制面不映射生产权限。
此任务不是要求所有服务默认公网开放。

#### 任务输入与范围
前置任务：TASK-104。

权威规格：[07-engineering.md](07-engineering.md)、[09-acceptance.md](09-acceptance.md)。

允许范围：deploy/、scripts/、Demo Seed和验收记录；不为验收伪造核心事实。

验证与交付：运行`docker compose -f deploy/demo/docker-compose.yml config`及本Task真实health/metrics验证。


---

### TASK-106 — Demo Seed / Health Check

所有服务：

> 使用正式 Health Check。

禁止：

```text
sleep 20
```

判断启动完成。

#### 完成标准
真实验证P99所需分布配置/模板、Redis组与版本能力、consumer ACK/pending基线、Policy Seed，
以及各Provider最小权限。禁止用sleep固定等待替代Health Check或用占位数据满足Preflight。

#### 任务输入与范围
前置任务：TASK-105。

权威规格：[07-engineering.md](07-engineering.md)、[09-acceptance.md](09-acceptance.md)。

允许范围：deploy/、scripts/、Demo Seed和验收记录；不为验收伪造核心事实。

验证与交付：运行`docker compose -f deploy/demo/docker-compose.yml config`及本Task真实health/metrics验证。

---

## 22. M14 — 三场景系统验收

按09-acceptance中的真实Gate、证据质量与安全规则验收，不再保留下一阶段占位。

### TASK-107 — S1 Acceptance

目标：

```text
Redis Latency
→
Evidence-backed Diagnosis
```

#### 最终验收
执行09中的ACC-S1全部断言，记录真实baseline、故障Gate、调用/证据、模型与Prompt版本、实际结果。
安全违规直接FAIL；环境Gate未达标单列SETUP_FAILED；不能把示例JSON当作已运行报告。
本Task只补与场景相关的有限验收，不引入完整全量E2E平台。

#### 任务输入与范围
前置任务：TASK-058、TASK-059、TASK-094、TASK-105、TASK-106。

权威规格：[07-engineering.md](07-engineering.md)、[09-acceptance.md](09-acceptance.md)。

允许范围：deploy/、scripts/、Demo Seed和验收记录；不为验收伪造核心事实。

验证与交付：执行09中对应场景Runner命令；报告必须给出实际可重复命令和证据文件，不只写PASS。


---

### TASK-108 — S2 Acceptance

目标：

```text
Slow Query
→
Pool Exhaustion
→
Evidence-backed Diagnosis
```

#### 最终验收
执行09中的ACC-S2全部断言，记录真实baseline、故障Gate、调用/证据、模型与Prompt版本、实际结果。
安全违规直接FAIL；环境Gate未达标单列SETUP_FAILED；不能把示例JSON当作已运行报告。
本Task只补与场景相关的有限验收，不引入完整全量E2E平台。

#### 任务输入与范围
前置任务：TASK-058、TASK-059、TASK-095、TASK-105、TASK-106。

权威规格：[07-engineering.md](07-engineering.md)、[09-acceptance.md](09-acceptance.md)。

允许范围：deploy/、scripts/、Demo Seed和验收记录；不为验收伪造核心事实。

验证与交付：执行09中对应场景Runner命令；报告必须给出实际可重复命令和证据文件，不只写PASS。


---

### TASK-109 — S3 Acceptance

目标：

```text
Consumer Stop
→
Diagnosis
→
Remediation
→
Approval
→
Restart
→
Verification
→
RESOLVED
```

#### 最终验收
执行09中的ACC-S3全部断言，记录真实baseline、故障Gate、调用/证据、模型与Prompt版本、实际结果。
安全违规直接FAIL；环境Gate未达标单列SETUP_FAILED；不能把示例JSON当作已运行报告。
本Task只补与场景相关的有限验收，不引入完整全量E2E平台。

#### 运行控制附加断言
汇总ACC-FINAL-01～15在对应实现任务的验证证据；
S3本体必须真实Provider/AI/CHANGE/Recovery，不以Fake替代。
只读reconciliation可以有界重试，restart不重放；FAILED优先、pending门禁与执行前Snapshot必须一致。

#### 任务输入与范围
前置任务：TASK-083、TASK-093、TASK-101、TASK-102、TASK-105、TASK-106、TASK-107、TASK-108。

权威规格：[07-engineering.md](07-engineering.md)、[09-acceptance.md](09-acceptance.md)。

允许范围：deploy/、scripts/、Demo Seed和验收记录；不为验收伪造核心事实。

验证与交付：执行09中对应场景Runner命令；报告必须给出实际可重复命令和证据文件，不只写PASS。

---

## 23. 哪些 Task 必须用强模型

不是所有任务都值得消耗最高能力模型。

下面这些建议：

### 强模型实现 + 强模型 Review

```text
TASK-001

TASK-013
TASK-014

TASK-024
TASK-025
TASK-026

TASK-037
TASK-039
TASK-040
TASK-041
TASK-042
TASK-043

TASK-047
TASK-048
TASK-058

TASK-065
TASK-067

TASK-069
TASK-071
TASK-072
TASK-073

TASK-076
TASK-077
TASK-078
TASK-079
TASK-080
TASK-082
TASK-083
```

原因：

这些涉及：

```text
业务不变量

事务

竞态

异步恢复

状态转换

安全边界
```

---

## 24. 哪些 Task 可以交给能力较弱的编码模型

规格已经足够具体时：

```text
Migration

DTO

Mapper

Query Repository

简单 CRUD Read API

Contract Fixture

前端展示

基础配置

Docker Compose

Provider DTO 映射
```

这些可以交给：

> Flash 级模型。

但是：

> Review 仍然必须使用较强模型。

---

## 25. Provider Task 也不能完全放任弱模型

例如：

```text
RedisCacheInspectProvider
```

代码可能并不复杂。

真正风险是：

弱模型看到 Redis Client 后：

> 顺手实现 KEYS / GET。

因此 Provider TASK Prompt 必须再次附带：

```text
允许命令白名单

禁止操作

允许输出字段

脱敏规则
```

不能只说：

> “实现 Redis Provider。”

---

## 26. 一个 TASK 应该多大

推荐：

一个 TASK 最好只横跨：

```text
1～2 个 Java Module
```

特殊核心事务任务：

最多：

```text
domain
+
application
+
infrastructure
```

但不要同时：

```text
database
backend
python
frontend
docker
```

全部改。

---

## 27. 一个 TASK 不应该变成“功能史诗”

例如：

错误：

```text
TASK-020

实现整个 Agent 调查系统
```

正确：

```text
Context Builder

AgentStep Persistence

Investigation Guard

Intent Dispatcher

Stop Race

Termination
```

分别实现。

这样：

> 每一步都能 Review。

---

## 28. Task Prompt 标准模板

TASK-013起以批次作为交付Prompt标题，增加批次ID、成员顺序、固定base SHA、批外前置核实及共同/专项验证矩阵。
下面各字段仍须覆盖每个成员Task，可引用正式条文而不复制正文；可直接使用BATCH-PLAN模板。

单项任务模板：

```text
# TASK-XXX — <任务名称>

## 背景

该任务属于 OpsPilot V0.1 的 <Milestone>。

## 目标

只完成：

<明确目标>

## Frozen Specs

必须阅读：

docs/specs/xx.md
docs/specs/yy.md

相关章节：

...

## 允许修改

...

## 禁止修改

...

## 必须实现

...

## 明确不做

...

## 关键不变量

INV-...
CAP-INV-...
ENG-INV-...

## 输入

...

## 输出

...

## 数据库变化

...

## API变化

...

## 测试要求

...

## 验证命令

...

## 完成标准

...

## Review Notes

如果发现规格之外的问题：

只记录。

不要自行扩大任务范围。
```

---

## 29. AI Coding Agent 的完成报告也要固定格式

每个 Agent 交付批次（或001～012单项任务）时必须输出批次/成员、base SHA、逐项完成与验证证据对应关系，并说明：

```text
实现了什么

修改了哪些文件

有没有新增依赖

有没有改变 Frozen Spec

数据库 migration

测试结果

尚未解决的问题

发现但未修改的范围外问题
```

尤其：

```text
有没有改变 Frozen Spec
```

正常应该始终：

```text
No
```

---

## 30. Review Agent Prompt 不要和 Implementation Prompt 相同

实现 Agent 关注：

> 把 TASK 做出来。

批次Review先核对固定base SHA到当前代码树的完整diff及未跟踪文件、各成员DoD与实际验证证据，不仅审最后一个Task。

Review Agent 应该重点查：

```text
是否破坏 Frozen Invariant

事务是否跨外部调用

有没有隐藏 Retry

有没有越权 Provider

有没有绕过 Transition

有没有 Map<String,Object>

有没有直接 Shell

有没有 Python 访问基础设施

有没有不应该出现的状态写入

有没有重复事实来源
```

---

## 31. TASK 状态

开发管理建议使用：

```text
TODO

READY

IN_PROGRESS

REVIEW

BLOCKED

DONE
```

这些：

> 是开发任务状态。批次也使用这些状态。

批次READY要求批外前置全部DONE；批内成员可在前置实现与针对性验证完成后READY。
成员实现完成先记录证据，不提前DONE；整批送审时转REVIEW，Review PASS且提交后一起DONE。
具体记录方式见BATCH-PLAN；状态表本身不代表验证已运行。

和：

```text
IncidentStatus
```

完全没有关系。

---

## 32. 不允许两个 Agent 同时修改核心边界

例如：

```text
Agent A

实现 IncidentTransitionPolicy
```

同时：

```text
Agent B

实现 start-investigation
并顺手改 TransitionPolicy
```

容易产生冲突。

因此核心目录：

```text
domain/incident

application/investigation

application/recovery
```

同一时间：

> 最好只有一个实施任务拥有修改权。

---

## 33. 第一批真正应该做的 TASK

正式开始编码时，不应该一次把：

```text
TASK-001 ～ TASK-109
```

全部丢给 AI。

第一批只执行：

```text
TASK-001
↓
TASK-002
↓
TASK-003
↓
TASK-004
```

完成后 Review。

然后：

```text
TASK-005 ～ TASK-011
```

完成系统接入基线。

再继续：

```text
Incident。
```

---

## 34. 第一个基础纵向切片

TASK-020完成时可启动服务、读取系统、创建Incident并验证基础动作API/事务。
它不是完整AI调查：Stop的后台收束、Continue之后的真实循环在TASK-043完成，
真实Provider闭合在TASK-058完成。不得把阶段性的FakeDispatcher当成可演示的完整调查功能。

---

## 35. 第二个关键节点

到：

```text
TASK-043
```

完成时，

系统已经拥有：

```text
真正 Java 驱动的 Agent Loop

AI Runtime

Hypothesis

Evidence

Diagnosis

Stop

Budget

Timeout

Startup Recovery
```

但是：

> Capability 还可以使用 Fake。

这时先验证：

> 控制平面是正确的。

---

## 36. 第三个关键节点

到：

```text
TASK-058
```

完成时：

```text
真实 AI
+
真实 OBSERVE Capability
```

已经闭合。

OpsPilot 第一次能够：

> 真正调查系统。

S1/S2 的主体能力已经基本存在。

---

## 37. 第四个关键节点

TASK-083完成时，Diagnosis->Remediation->Approval->Execution->Recovery的后台闭环成立。
前提包括已前置的074～076、69的执行前Snapshot、72的有界只读核对和77的FALSE优先三值矩阵。
仍需真实S3靶场、前端和系统验收，不能以规则测试替代最终Demo。

---

## 38. 最终节点

```text
TASK-109
```

通过时：

OpsPilot V0.1 才真正完成。

不是：

```text
代码写完
```

而是：

```text
S1 PASS

S2 PASS

S3 PASS
```

---

## 39. 开发过程中禁止出现的新“顺手功能”

TASK 实施阶段不允许突然加入：

```text
RBAC

组织

租户

告警中心

Knowledge Base

RAG

LangGraph

MCP

Plugin Marketplace

Multi-Agent

Kubernetes

任意 Shell

任意 SQL

任意 PromQL

WebSocket

Kafka

RabbitMQ

完整 Tracing 平台
```

除非：

> 新的 Frozen Spec 明确批准。

---

## 40. 一个重要的范围纪律

如果 Codex Review 发现：

> “以后可以把 WorkDispatcher 改成 Kafka。”

记录：

```text
Future Consideration
```

不要在 V0.1 改。

---

如果发现：

> “RecoveryPolicy 可以支持 System Scope。”

记录。

不要实现。

---

如果发现：

> “可以给 Evidence 加向量检索。”

直接拒绝进入 V0.1。

---

## 41. Task 阶段的最高原则

从现在开始：

> **文档不再告诉 AI“帮我设计”。**

而是：

> **告诉 AI“按照已经设计好的规则完成这一小块”。**

以前：

```text
AI = 架构讨论参与者
```

以后进入实现：

```text
AI = 受约束的工程实施者
```

这两个角色必须切开。

---

## 42. 任务计划完成标准

任务执行图、完整109项及各任务DoD构成实施输入。TASK-001在本包内已做文档合并，目标仓库仍需落位检查。
数据库按业务批次迁移；Policy基础在Execution准入前就绪，不把全部Recovery都拖到执行后。
AI协议先于真实模型集成，控制面先用Fake验证，真实Provider和真实S1/S2/S3作为后续独立证据。
项目完成以场景验收而非Task状态列表判断；未执行的测试不能在报告中填PASS。

---

## 43. 立即实施路径

导入本包并执行TASK-001落位检查，然后开始TASK-002。
当前裁决已经结束设计阶段；后续一般实现问题在Task内处理，不以新增评审轮次代替代码和实测。

---

## 44. 计划基线状态

Status:FROZEN，Version:0.1，FreezeRevision:FINAL-FREEZE-20260925。
本文件的任务状态表示计划，不代表代码已经完成。交付包没有替用户修改Git仓库或运行真实验收。

---
