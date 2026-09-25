# OpsPilot V0.1 Java 主服务与 AI Runtime 职责边界

> Status: FROZEN  
> Version: 0.1  
> Freeze revision: FINAL-FREEZE-20260925  
> 冻结日期：2026-09-25  
> 范围：Java控制与事实所有权、Python单步提议、协议和安全职责。  
> 本文是已合并的正式实现规格。权威清单见 [SPEC-MANIFEST.md](SPEC-MANIFEST.md)。FROZEN 表示规格定稿，不表示代码、迁移或故障实验已经通过。

## 1. 最重要的架构决定

OpsPilot V0.1 采用：

> **Java 主服务 = 业务控制中心和事实权威**
> **Python AI Runtime = AI 推理与决策建议引擎**

即：

```text
用户 / 前端
     │
     ▼
┌──────────────────────────────┐
│       Java 主服务            │
│                              │
│ Incident                     │
│ 状态机                       │
│ Investigation                │
│ Evidence                     │
│ Capability                   │
│ Approval                     │
│ Execution                    │
│ Recovery Verification        │
│ Timeline                     │
└──────────────┬───────────────┘
               │
               │ 请求“下一步应该做什么”
               ▼
┌──────────────────────────────┐
│       AI Runtime             │
│          Python              │
│                              │
│ 调用大模型                   │
│ 分析当前上下文               │
│ 提出待验证原因               │
│ 判断下一步值得查什么         │
│ 提议证据关系                 │
│ 生成诊断草稿                 │
│ 生成处理建议草稿             │
└──────────────────────────────┘
```

核心原则：

> **AI Runtime 可以提出建议，但不能直接改变 OpsPilot 的业务事实。**

---

## 2. 谁才是系统的“事实来源”

答案：

### Java 主服务。

例如当前 Incident：

```text
status = INVESTIGATING
```

真正有效的是：

> Java 数据库里的状态。

不是：

> Python 内存里认为它正在调查。

---

再比如：

AI 说：

> O-003 支持 H-001。

真正形成 Evidence 的前提是：

```text
Java 校验
O-003 存在
H-001 存在
属于同一 Investigation
关系类型合法
```

然后 Java 才落账。

---

因此：

```text
AI 的输出
=
Proposal / Intent

Java 持久化后的内容
=
Fact
```

这条边界必须贯穿整个项目。

---

## 3. 为什么不让 Python Agent 成为系统核心

一种很常见的实现方式是：

```text
Python Agent
   │
   ├── 查 Redis
   ├── 查 MySQL
   ├── 查日志
   ├── 自己维护状态
   ├── 自己写数据库
   ├── 自己执行操作
   └── Java 只负责给前端 API
```

这种架构的问题是：

> Java 变成了一个没有实际业务价值的外壳。

对于 OpsPilot 也不安全。

如果 Python Agent 崩溃：

```text
Incident 当前是什么状态？
查过什么？
Evidence 有哪些？
用户批过什么？
操作到底执行没执行？
```

都容易变得混乱。

因此 V0.1 明确禁止这种设计。

---

## 4. Java 主服务拥有的职责

Java 主服务正式拥有以下业务职责。

---

### 4.1 Incident 生命周期

Java 负责：

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

所有合法状态转移。

AI Runtime：

> 无权直接修改状态。

---

### 4.2 Investigation 控制

Java 负责：

- 开始调查；
- 判断调查是否仍允许继续；
- 调用次数预算；
- 调查总时长；
- 用户停止；
- 防止两个 Agent 同时调查一个 Incident；
- 调查预算耗尽后的确定性退出。

也就是说：

> 调查循环的“控制权”属于 Java。

---

## 5. 一个非常重要的调整：循环应该放在哪里

之前我们一直说：

> Agent Loop。

很容易让人理解成：

```python
while True:
    model()
    tool()
```

全部在 Python。

V0.1 我建议不是这样。

而是：

```text
                Java

        ┌─────────────────┐
        │ 调查还能继续吗？ │
        └────────┬────────┘
                 │ yes
                 ▼
          调用 AI Runtime
                 │
                 ▼
        AI 建议下一步行动
                 │
                 ▼
           Java 校验建议
                 │
                 ▼
         执行允许的能力
                 │
                 ▼
       保存 Observation 等
                 │
                 └──────────────┐
                                │
                         下一轮调查
```

也就是说：

### 外层循环由 Java 驱动。

Python 负责：

> 每一步“现在最值得做什么？”

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

## 6. 为什么这个设计更适合 OpsPilot

因为下面这些都不能依赖模型：

```text
12 次调用预算
8 分钟超时
Incident 当前状态
Capability 是否允许
权限
审批
Evidence 引用完整性
执行幂等
恢复条件
```

既然这些核心规则主要都属于 Java，

那么让 Java 控制外层循环最自然。

---

## 7. AI Runtime 实际负责什么

Python AI Runtime 不是什么都不做。

它负责最需要大模型判断的部分。

---

### 7.1 根据当前信息提出待验证原因

例如当前：

```text
P99 = 1600ms

CPU = 正常

Redis 延迟 = 未知
```

AI 可以提出：

```text
H-001
缓存服务性能异常

H-002
数据库访问异常
```

但只是：

> 提议。

Java 验证结构后创建正式 Hypothesis。

---

### 7.2 判断下一步最值得查询什么

例如：

> 当前最有价值的是检查 Redis。

于是 AI 返回：

```text
REQUEST_CAPABILITY

capability:
cache.inspect

resource:
shortlink.redis
```

不是 AI 自己真的连 Redis。

---

### 7.3 判断 Observation 和 Hypothesis 的关系

例如：

```text
Observation O-003

Redis P95 = 623ms
```

AI提出：

```text
O-003
SUPPORTS
H-001
```

Java检查引用合法以后：

> 创建 Evidence。

---

### 7.4 判断当前证据是否足够形成 Diagnosis

AI 可以提出：

```text
COMPLETE_INVESTIGATION
```

同时附：

```text
Diagnosis Draft
```

Java根据已经冻结的不变量检查。

合法：

```text
INVESTIGATING
→
DIAGNOSED
```

不合法：

> 拒绝。

---

### 7.5 生成处理建议

在已经：

```text
DIAGNOSED
```

以后，

Java 可以单独请求：

> 根据当前 Diagnosis 给出处理建议。

AI Runtime 返回：

```text
RemediationProposal
```

Java再检查：

- Diagnosis 类型；
- Capability 是否存在；
- Resource 是否支持；
- 是否属于写操作；
- 参数是否合法。

通过才创建正式：

```text
RemediationPlan
```

---

## 8. AI Runtime 明确不能做什么

V0.1 正式禁止 Python AI Runtime：

#### 不直接连接 OpsPilot 数据库

---

#### 不直接修改 Incident

---

#### 不直接修改 Investigation

---

#### 不直接创建有效 Approval

---

#### 不直接执行 Docker 命令

---

#### 不直接连接生产 MySQL 执行查询

---

#### 不直接连接 Redis

---

#### 不直接查询 Prometheus

---

#### 不直接查 Loki

---

#### 不直接执行 service.restart

---

#### 不拥有业务系统写凭证

---

它的工作是：

> **判断。**

不是：

> **控制。**

---

## 9. Capability 执行属于谁

全部属于：

> **Java 主服务。**

例如 AI 说：

```text
REQUEST_CAPABILITY

cache.inspect
shortlink.redis
```

Java收到后：

```text
① 当前 Incident 是不是 INVESTIGATING？

② 当前调查预算还有没有？

③ shortlink.redis 是否存在？

④ 它是否绑定 cache.inspect？

⑤ 当前能力是否只读？

⑥ 参数是否合法？

⑦ 当前用户/调查是否允许使用？
```

全部通过以后：

```text
CapabilityExecutor
```

才执行。

---

## 10. Provider 也属于 Java

例如：

```text
cache.inspect
```

真正下面可能使用：

```text
RedisProvider
```

---

```text
metrics.query
```

下面：

```text
PrometheusProvider
```

---

```text
logs.search
```

下面：

```text
LokiProvider
```

---

```text
database.inspect
```

下面：

```text
MySqlProvider
```

这些都在：

> Java 侧。

因此 AI Runtime 甚至不需要知道 Prometheus URL、Redis Password、MySQL Password。

这是非常重要的安全边界。

---

## 11. 凭证应该属于谁

### Java 主服务

持有或者安全引用：

```text
Prometheus Credential
Loki Credential
MySQL Readonly Credential
Redis Monitoring Credential
Docker Execution Credential
```

---

### Python AI Runtime

只需要：

```text
LLM API Key
```

比如：

```text
OpenAI-compatible API
DeepSeek API
```

所以即使 AI Runtime 被攻击：

> 攻击面也不会直接包含业务系统基础设施凭证。

---

## 12. 前端只能访问 Java

正式规定：

```text
Browser
  ↓
Java API
```

禁止：

```text
Browser
  ↓
Python AI Runtime
```

用户：

- 创建 Incident；
- 开始调查；
- 批准操作；
- 查看时间线；
- 查看 Diagnosis；

全部访问 Java。

Python AI Runtime：

> 只作为内部服务。

---

## 13. 调查进度推送也由 Java 提供

例如前端页面看到：

```text
正在检查缓存服务……

发现 Redis 响应明显变慢……

正在检查数据库……
```

由：

```text
Java
→ SSE
→ Browser
```

推送。

不是：

```text
Python
→ Browser
```

为什么？

因为时间线真正的事实来源是 Java。

否则可能出现：

> Python 页面说已经生成 Evidence，

但 Java 落账失败。

UI 和真实系统状态不一致。

---

## 14. 一次 Agent Step 是什么

我们正式定义一个概念：

### AgentStep

中文：

> **一次 AI 决策步骤**

流程：

```text
Java
准备当前调查上下文

      ↓

请求 AI Runtime

      ↓

模型分析

      ↓

AI Runtime 返回一个结构化决策

      ↓

Java 校验

      ↓

执行 / 落账

      ↓

进入下一 Step
```

---

## 15. Java 给 AI 的内容

Java 不应该把整个数据库全部塞过去。

每一步只发送当前需要的信息。

逻辑上包括：

```text
Incident Summary

Affected Resources

Current Hypotheses

Relevant Observations

Evidence

Current Diagnosis（若有）

Available Capabilities

Investigation Budget

Recent Timeline
```

例如：

```text
故障：
短链接跳转明显变慢

当前已知：

O-001
HTTP P99 = 1630ms

O-002
CPU = 42%

已有假设：

H-001
Redis 性能异常
状态：PENDING

当前可用能力：

cache.inspect
database.inspect
logs.search

剩余调用预算：
9
```

AI再决定：

> 下一步检查 Redis。

---

## 16. AI 返回的不能是一段随意文本

内部协议必须使用结构化对象。

不是：

> “我觉得你可以看看 Redis。”

而是类似：

```text
AgentStepResult

next_action:
REQUEST_CAPABILITY

capability:
cache.inspect

target_resource:
shortlink.redis

purpose:
验证 Redis 性能异常假设
```

Java才能可靠处理。

---

## 17. V0.1 的 Agent Intent

`Intent`

你可以理解成：

> **AI 想让系统接下来做什么。**

V0.1 只需要几种。

---

### REQUEST_CAPABILITY

中文：

> 请求获取新的系统信息。

例如：

```text
cache.inspect
```

---

### PROPOSE_HYPOTHESIS

中文：

> 提出一个新的待验证原因。

---

### PROPOSE_EVIDENCE_LINK

中文：

> 提议某个 Observation 支持或者反驳某个 Hypothesis。

---

### UPDATE_HYPOTHESIS

中文：

> 建议改变某个待验证原因当前状态。

---

### COMPLETE_INVESTIGATION

中文：

> AI认为当前调查已经足够，可以形成 Diagnosis。

---

注意：

这些全部是：

> Intent。

不是命令。

---

## 18. Java 怎么处理 Intent

例如：

```text
PROPOSE_HYPOTHESIS
```

Java：

```text
验证格式
验证 Investigation
验证是否处于 INVESTIGATING
检查重复
持久化
写 TimelineEvent
```

---

例如：

```text
PROPOSE_EVIDENCE_LINK
```

Java：

```text
验证 Observation
验证 Hypothesis
验证 Investigation
验证 relation
创建 Evidence
写 Timeline
```

---

例如：

```text
REQUEST_CAPABILITY
```

Java：

```text
状态检查
预算检查
能力检查
资源检查
参数检查
权限检查
执行
创建 CapabilityInvocation
创建 Observation
写 Timeline
```

---

## 19. Remediation 不应该混在 Investigation Step 里

这是一个重要边界。

调查阶段：

```text
INVESTIGATING
```

AI只负责：

> 查什么、怎么理解、什么时候诊断。

形成 Diagnosis 后：

```text
DIAGNOSED
```

才进入另一个逻辑阶段：

```text
Remediation Draft
```

这样不会出现：

> 还没调查完，AI已经准备重启服务器。

因此：

```text
Investigation AI
```

和：

```text
Remediation AI
```

可以使用同一个模型、同一个 Python Runtime，

但：

> 属于不同业务阶段。

---

## 20. Java 与 AI Runtime 的调用方向

V0.1 强烈建议：

### 单向调用。

即：

```text
Java
  ↓
Python
```

而不是：

```text
Java
↔
Python互相回调
```

Python 不主动 Callback Java。

这样整个系统简单很多。

---

## 21. 为什么不让 Python 回调 Java

否则容易出现：

```text
Java 调 Python
↓
Python 调 Java Tool
↓
Java Tool 又调用其他东西
↓
Python继续
↓
Java不知道 Python什么时候结束
```

状态管理非常复杂。

我们改成：

```text
Java
↓
“下一步做什么？”

Python
↓
“请求 cache.inspect”

Java
↓
执行

Java
↓
“现在看到 Redis = 623ms，下一步呢？”

Python
↓
“检查数据库”
```

每一次都由 Java发起。

非常容易控制。

---

## 22. Java 与 Python 使用什么通信

V0.1 推荐：

> **内部 HTTP + JSON**

Python：

```text
FastAPI
```

Java：

```text
Spring Boot WebClient / RestClient
```

没必要第一版：

```text
Kafka
RabbitMQ
gRPC
WebSocket
```

因为：

> Java → AI Runtime

本质就是一次请求 / 返回。

---

## 23. 为什么暂时不用消息队列

消息队列适合：

- 高吞吐异步任务；
- 多消费者；
- 大规模解耦；
- 事件驱动。

V0.1：

```text
Java
→
单个 AI Runtime
```

复杂度很低。

增加 MQ 只会带来：

```text
消息重复
消费幂等
消息顺序
死信
重试
追踪
```

没有价值。

---

## 24. Python AI Runtime 是否保存状态

V0.1 原则：

> **不拥有权威持久状态。**

理想情况下每一个 Step：

```text
Java
→ 提供当前所需上下文
→ Python 推理
→ 返回结果
```

因此 Python Runtime 重启：

> 不应该丢失 Incident。

---

可以存在：

```text
内存缓存
模型客户端连接池
Prompt 模板
短生命周期中间对象
```

但是它们全部：

> 可以丢。

---

## 25. Python 要不要自己的数据库

V0.1：

### 不需要。

不要上来建立：

```text
Java PostgreSQL

+

Python PostgreSQL

+

LangGraph Checkpoint DB
```

然后三个地方都保存调查状态。

非常容易产生：

> 谁才是真的？

---

V0.1 只有：

```text
OpsPilot Database
        ↑
       Java
```

作为业务事实来源。

---

## 26. AI Runtime 实现边界

V0.1 不使用 LangGraph。外层循环、Run、预算、Stop、状态恢复和持久化由 Java 负责；
Python 使用 FastAPI + Pydantic v2 + LLM Client + 版本化 Prompt/Structured Output。
不得在 Python 建立第二份 Investigation 状态或数据库。未来框架变化需要独立需求，不影响本版本合同。

---

## 27. Python Runtime 换模型怎么办

Python 内部可以有简单：

```text
LlmClient
```

接口。

实现：

```text
OpenAiCompatibleClient
```

然后通过配置：

```text
model
base_url
api_key
```

支持不同兼容模型。

V0.1 不需要做：

> 多模型路由平台。

只需要避免业务代码写死具体 SDK 即可。

---

## 28. AI Runtime 挂了怎么办

Java 为每次请求登记带 run_no 的 AgentStepRecord。连接失败、超时或结构化输出非法记 FAILED，
计入当前 run 的 consecutive_ai_failure_count；成功处理本轮合法输出后重置连续失败计数。
客户端不做隐藏重试：需要再次问模型时由 Orchestrator 明确开启下一次 Step，并重新执行准入。
达到阈值、Stop 或本轮 deadline 后，依据本轮已提交事实确定性收束；无合法草稿则 UNDETERMINED。
PROCESS_INTERRUPTED 是 Java 进程中断，不计作模型自身连续失败。

---

## 29. Java 挂了怎么办

从 MySQL 重建状态：恢复原 run 而非重新获得预算；停止标记、计数、截止时间保留。
PENDING 工作可重新派发，RUNNING CHANGE 只允许有界只读 reconciliation，Verification 按采样身份和快照恢复。
完整恢复矩阵见 [07-engineering.md](07-engineering.md)。Python 无权威状态，因此不需要 Python Session 对账。

---

## 30. Capability Provider 挂了怎么办

例如：

```text
Prometheus
```

不可用。

Java创建：

```text
CapabilityInvocation

status = FAILED
```

不会伪造 Observation。

AI下一步收到：

> 指标查询失败。

它可以决定：

> 尝试查日志。

如果所有关键数据源都不可用，

最后可以：

```text
UNDETERMINED
```

结束。

---

## 31. AI Runtime 超时

单次 AI Step 应有独立超时。

例如：

```text
60s
```

具体值以后配置。

AI超时：

> 不能意味着 Incident 失败。

而是：

```text
AgentStep failed
```

根据重试策略继续。

连续失败到阈值：

```text
UNDETERMINED
```

---

## 32. 为什么 Observation 必须 Java 保存

流程：

```text
Capability Provider
      ↓
真实返回
      ↓
Java
      ↓
Observation
      ↓
数据库
```

然后：

```text
Java
      ↓
把 Observation 发送给 AI
```

而不是：

```text
Provider
↓
Python
↓
AI总结以后
↓
Java
```

否则真实原始结果容易被模型加工后丢失。

证据链应该来自：

> 原始系统事实。

不是：

> AI描述过的事实。

---

## 33. 敏感信息清洗也放 Java

比如日志里有：

```text
Authorization: Bearer xxx
password=xxx
email=...
```

Java Provider / Sanitizer 层应该先做：

> 脱敏。

然后才发给 AI Runtime。

因此模型默认看到：

```text
Authorization: [REDACTED]
```

而不是原始 Secret。

---

## 34. 处理方案流程

完整流程：

```text
Diagnosis
    ↓
Java 请求 AI：
“基于这个 Diagnosis，有什么允许范围内的处理建议？”
    ↓
AI Runtime
    ↓
RemediationProposal
    ↓
Java 校验：
Diagnosis 是否允许处理？
Resource 是否支持？
Capability 是否允许？
参数是否合法？
    ↓
RemediationPlan
    ↓
ApprovalRequest
```

Python到此结束。

---

## 35. 执行和恢复验证完全不经过 AI

用户批准以后：

```text
Java
↓
ActionExecution
↓
DockerServiceExecutor
↓
service.restart
```

执行结束：

```text
Java
↓
RecoveryVerification
↓
Provider
↓
检查 RecoveryPolicy
```

AI不参与：

```text
“我觉得恢复了”
```

这非常重要。

---

## 36. S3 完整跨服务流程

一次 S3 调查由 Java 驱动。每次 AI Step 至多一个主 Intent：
REQUEST_CAPABILITY -> Java观察 -> 新Step提出Hypothesis -> 新Step建立Evidence ->
新Step COMPLETE_INVESTIGATION。不能把“提出假设”和“请求外部Capability”当成同一次主动作。

DIAGNOSED 后由用户请求处理建议，Java 单独调用 remediation/draft；AI 只在 allowedActions 内提案。
审批、Policy快照、CHANGE准入、有界只读核对、Verification全部由Java确定性完成，不回调AI。
详见 [05-api.md](05-api.md)、[06-capability.md](06-capability.md)。

---

## 37. 数据所有权表

| 数据 / 能力 | Java | AI Runtime |
|---|---:|---:|
| Incident | **拥有** | 只读上下文 |
| Incident.status | **拥有** | 禁止修改 |
| Investigation | **拥有** | 只读上下文 |
| Hypothesis | **持久化/校验** | 提议 |
| Observation | **拥有** | 读取 |
| Evidence | **持久化/校验** | 提议关系 |
| Diagnosis | **持久化/校验** | 生成草稿 |
| Capability Registry | **拥有** | 获取可用能力 |
| Capability Execution | **执行** | 请求 |
| Provider Credential | **拥有** | 不可见 |
| RemediationPlan | **持久化/校验** | 生成草稿 |
| Approval | **拥有** | 不可控制 |
| ActionExecution | **拥有** | 不可控制 |
| RecoveryPolicy | **拥有** | 可读取摘要 |
| RecoveryVerification | **执行** | 不参与 |
| Timeline | **拥有** | 可读取摘要 |
| LLM 调用 | 不负责 | **拥有** |
| Prompt | 不负责 | **拥有** |
| 模型结构化输出 | 校验 | **生成** |

---

## 38. Java 内部未来大概会出现哪些职责

这里只定义职责，不设计包结构。

例如：

```text
IncidentService

InvestigationOrchestrator

StateTransitionService

CapabilityRegistry

CapabilityExecutionService

ObservationService

EvidenceService

DiagnosisService

RemediationService

ApprovalService

ActionExecutionService

RecoveryVerificationService

TimelineService

AiRuntimeClient
```

这已经能明显看出来：

> Java 不是一个 API Wrapper。

Java实际上承担了整个产品的业务骨架。

---

## 39. Python 内部未来大概有哪些职责

同样先不设计目录。

逻辑职责：

```text
Prompt Assembly

LLM Client

Structured Output

Investigation Decision

Hypothesis Proposal

Evidence Relation Proposal

Diagnosis Draft

Remediation Draft
```

它不需要：

```text
IncidentRepository
ApprovalRepository
DockerExecutor
RedisCredential
```

这些东西。

---

## 40. 最重要的安全边界

整个系统可以用一句话表示：

```text
                  不可信 / 概率性
                       AI
                        │
                        │ Proposal
                        ▼
                 ┌────────────┐
                 │ Java Guard │
                 └─────┬──────┘
                       │
                     校验后
                       │
                       ▼
                   真实世界
```

LLM永远位于：

> Proposal Side。

确定性程序位于：

> Authority Side。

---

## 41. 为什么这个架构特别适合你的求职项目

它同时可以证明两部分能力。

### Java 后端

你可以讲：

- 领域模型；
- 状态机；
- 幂等；
- 状态不变量；
- Provider 抽象；
- 权限边界；
- 审批；
- 调查编排；
- SSE；
- 数据持久化；
- 故障恢复。

---

### AI 应用开发

你可以讲：

- Agent Loop；
- Tool / Capability Calling；
- Structured Output；
- Evidence-based reasoning；
- Prompt Context；
- LLM Failure Handling；
- Hallucination Boundary；
- AI 与确定性系统协作。

而不是：

> “我用 LangChain 调了一下 DeepSeek。”

---

## 42. V0.1 当前架构正式建议

```text
┌──────────────────┐
│      React       │
│       Web        │
└────────┬─────────┘
         │ REST + SSE
         ▼
┌──────────────────────────────────┐
│          Java / Spring Boot      │
│                                  │
│ Domain                           │
│ State Machine                    │
│ Investigation Orchestrator       │
│ Capability Layer                 │
│ Evidence                         │
│ Approval / Execution             │
│ Recovery Verification            │
│ Timeline                         │
└──────┬──────────────────┬────────┘
       │                  │
       │ Internal HTTP    │ Providers
       ▼                  ▼
┌───────────────┐     Prometheus
│ Python        │     Loki
│ AI Runtime    │     MySQL
│               │     Redis
│ FastAPI       │     Docker
│ LLM Client    │
│ Structured AI │
└───────┬───────┘
        │
        ▼
   LLM Provider
```

---

## 43. 本阶段建议冻结的 12 条原则

### BND-001

Java 主服务是 OpsPilot 业务事实的唯一权威来源。

### BND-002

AI Runtime 无权直接修改任何核心领域对象状态。

### BND-003

AI Runtime 只能产生结构化 Proposal / Intent。

### BND-004

所有 Capability 都由 Java 校验和执行。

### BND-005

AI Runtime 不直接持有业务系统数据源凭证。

### BND-006

Observation 必须由 Java 根据真实 Capability 结果创建。

### BND-007

Evidence 由 AI 提议关系，由 Java校验并持久化。

### BND-008

Approval、ActionExecution、RecoveryVerification 全部属于 Java。

### BND-009

前端只与 Java 通信。

### BND-010

V0.1 Java → AI Runtime 采用单向同步 HTTP 调用，不使用 MQ。

### BND-011

AI Runtime 不拥有独立权威数据库；服务重启不得导致 Incident 状态丢失。

### BND-012

V0.1 外层调查循环由 Java 驱动，AI 负责每一步的判断。

### BND-013
一次调查 Step 恰好一个主 Intent；PROPOSE_EVIDENCE_LINK 可附带同一 Hypothesis 的直接相关状态更新，其他跨动作组合拒绝。

### BND-014
PROPOSE_REMEDIATION 只出现在独立 remediation/draft，不出现在 investigation/step。

### BND-015
Java 负责当前 run 的原子准入和迟到结果校验。Python 回传 runNo 不能获得修改权，权威轮号在数据库。

### BND-016
AI 单步超时、连续失败阈值、当前 run 墙钟时间与当前 run Capability 额度分别计算。

---

## 44. 一个非常重要的工程结果

在这种设计下：

如果未来我们发现：

> Python 不合适。

可以把 AI Runtime 换成：

```text
Java + LangChain4j
```

理论上：

> 核心领域模型不需要重写。

如果未来从：

```text
普通 LLM SDK
```

换成：

```text
LangGraph
```

Java 同样不需要知道。

如果未来从：

```text
DeepSeek
```

换成：

```text
OpenAI-compatible model
```

业务层也不应该变化。

这说明：

> AI 框架和模型只是实现细节。

这才是健康的软件架构。

---

## 45. 本阶段结束条件

这篇冻结以后，我们应该能够明确回答：

#### 谁控制 Incident 状态？

Java。

#### 谁控制调查预算？

Java。

#### 谁决定下一步值得查什么？

AI。

#### 谁真正查询 Redis？

Java Provider。

#### 谁创建 Observation？

Java。

#### 谁判断 Observation 能否作为支持证据？

AI提议，Java校验关系合法性并落账。

#### 谁生成 Diagnosis 内容？

AI草拟。

#### 谁判断 Diagnosis 是否满足业务不变量？

Java。

#### 谁批准修改系统？

用户 + Java。

#### 谁真正执行 restart？

Java Executor。

#### 谁判断是否恢复？

Java Recovery Verification。

#### Python 挂了会不会丢 Incident？

不会。

#### Python 能不能绕过审批重启服务？

架构上不能。

如果这些问题答案明确，本阶段就完成。

---

## 46. 后续实施引用

本职责边界已经落入 [03-domain-model.md](03-domain-model.md)、[04-database.md](04-database.md)
以及 [07-engineering.md](07-engineering.md)。实施从任务计划推进，不再重新选择外层控制权。

---
