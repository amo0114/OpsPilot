# OpsPilot V0.1 规格合并交付报告

> 日期：2026-09-25  
> 基线：FINAL-FREEZE-20260925 / 0.1  
> 对象：Markdown规格包，不是Git仓库或运行软件的验收报告。

## 1. 本次实际完成

取得并保存14份原始正文/补丁，共438,857字节；没有根据截断片段补写原始文件。
采用按章节直接替换、补齐和交叉对齐的方式生成10份正式规格正文，保留原主体结构与未变更章节，
并保留TASK-001～TASK-109全部编号。不是仅把最新补丁贴到旧正文末尾。

原14份文件在完整包originals中保持原始字节，含其中已经作废的历史语义。
正式编码只读取SPEC-MANIFEST列出的正文。
上一轮长篇会话终审在归档中提供摘要，不冒充完整逐字报告；原审查指令另有原文件副本。

## 2. 输出范围

10份00～09正文＋SPEC-MANIFEST；
FINAL-FREEZE最终裁决归档；
README、AGENTS、CLAUDE实施入口；
合并报告、静态校验报告与SHA256校验清单；
原始正文/补丁、用户裁决记录及审查材料归档。

全量包包含全部内容。实施精简包去掉archive，规范正文与全量包完全相同。
新增文档是工作容器中的交付文件；没有替你改写Library、Dropbox或Git仓库。

## 3. 源正文到最终文件

“未改章节”指分节后的正文/标题数据保持原样；最终文件统一了Markdown标题层级与文档元信息。
“新增章节”是把必要约束整理到正文，不是新增产品能力。表中未统计源文件前置状态说明的重排。

| 正式文件 | 主体源文件 | 原章节数 | 内容未改章节数 | 新增章节数 |
|---|---|---:|---:|---:|
| [00-product.md](../specs/00-product.md) | OpsPilot V0.1 产品定义与核心领域设计.md | 35 | 25 | 0 |
| [01-lifecycle.md](../specs/01-lifecycle.md) | OpsPilot V0.1 核心生命周期与状态机设计.md | 42 | 29 | 0 |
| [02-java-ai-boundary.md](../specs/02-java-ai-boundary.md) | OpsPilot V0.1 Java 主服务与 AI Runtime 职责边界设计.md | 46 | 39 | 0 |
| [03-domain-model.md](../specs/03-domain-model.md) | OpsPilot V0.1 核心领域关系与持久化边界设计.md | 83 | 71 | 0 |
| [04-database.md](../specs/04-database.md) | OpsPilot V0.1 MySQL 物理数据模型设计.md | 96 | 69 | 2 |
| [05-api.md](../specs/05-api.md) | OpsPilot V0.1 API 契约设计.md | 106 | 70 | 1 |
| [06-capability.md](../specs/06-capability.md) | OpsPilot V0.1 Capability 详细契约设计.md | 140 | 117 | 0 |
| [07-engineering.md](../specs/07-engineering.md) | OpsPilot V0.1 工程结构与编码规范设计.md | 142 | 109 | 0 |
| [08-implementation-plan.md](../specs/08-implementation-plan.md) | OpsPilot V0.1 开发任务拆分与实施顺序.md | 44 | 18 | 0 |
| [09-acceptance.md](../specs/09-acceptance.md) | OpsPilot V0.1 S1 - S2 - S3 系统验收与故障实验设计.md | 107 | 79 | 1 |

其余4份冻结补丁按适用规则合入对应正文；其中MySQL旧DB-PATCH-03的Evidence版本化明确不应用，
以随后API对齐补丁撤销的最终规则为准。最终用户裁决覆盖单次核对和UNKNOWN优先等已变更规则。

## 4. 六项裁决的落位结果

| 项目 | 合并结果 | 主要检查 |
|---|---|---|
| FINAL-PATCH-01 | current_run_no及本轮计数/起点，12/480明确按run；重启不重置 | 不新增InvestigationRun表，不删历史 |
| FINAL-PATCH-02 | Stop与准入统一事务/锁序，COMMIT分界，迟到结果run校验 | 不把外部调用放入事务 |
| FINAL-PATCH-03 | PENDING可补派发，RUNNING不重放CHANGE，只读核对有界 | 次数在读取前登记；中断可用剩余额度；失败用既有错误码 |
| FINAL-PATCH-04 | 持久样本身份、顺序、期限和有效性 | FALSE优先，UNKNOWN不提前掩盖后续FALSE |
| FINAL-PATCH-05 | lag/pending/服务状态共同验证，允许健康零积压 | 默认B/C/D/A顺序，9个恢复采样Invocation |
| FINAL-PATCH-06 | 创建Execution前选择并快照合法Policy | 成功后复制快照，不重新查ACTIVE |

## 5. 明确新增的实施细化，不伪装成源文档原话

用户裁决没有逐列指定SQL类型、核对次数、采样超时或pending阈值。
为了可直接实施，本文将这些细节落入既有表/Schema，并标明为本次定稿的实施细化。

| 参数 | 本次定稿默认值 | 来源与使用边界 |
|---|---:|---|
| 单个 active run 的能力预算 | 12 次 | 用户明确裁决预算作用域；原默认数值保留 |
| 单个 active run 的墙钟期限 | 480 秒 | 用户明确裁决预算作用域；重启不刷新 |
| AI 单步超时／连续失败上限 | 60 秒／3 次 | 原规格默认值保留 |
| 重复调用保护窗口 | 30 秒 | 原 Capability 补丁默认值保留；按完成时间判断近期终态 |
| 只读 reconciliation 最大次数 | 3 次 | 本次为“有界重试”补齐的可配置实施默认值，并非用户指定或实测值 |
| reconciliation 间隔／单次超时／总期限 | 5 秒／5 秒／60 秒 | 本次实施默认值；持久化次数和截止时间，不因重启重新获得额度 |
| 单实例补派发扫描间隔 | 5 秒 | 本次实施默认值；不引入持久队列、租约或多实例调度 |
| Recovery 总期限／样本最长有效期 | 120 秒／120 秒 | 本次实施默认值；保存在 Policy 快照，实际从 Verification 创建计时 |
| 多次采样允许最大间隔 | 2 × intervalSeconds | 本次实施默认值；单样本 maxGapSeconds=null |
| S3 lag 健康阈值 | ≤20 | 原验收默认值保留，只是 Demo 阈值 |
| S3 pendingCount 健康阈值 | ≤20 | 本次为新增 pending 门禁补齐的 Demo 默认值，必须验证健康基线与 ACK 语义 |
| S1/S2 错误率独立故障 Gate | ≥max(基线错误率+0.05, 0.05) | 本次为原“错误率明显升高”补齐的 Demo 默认值；错误率使用0～1，0.05为5个百分点 |


此外统一了协议字段：current_run_no为持久化名、runNo为API名、run_no为运行记录关联；
consecutive_ai_failure_count为计数字段，max_consecutive_ai_failures仍为限制字段。
恢复样本复用CapabilityInvocation，没有新建表；Execution执行上下文与AI参数分开。
Stop首次登记推进并发版本、重复审批的自然幂等判定、Internal版本位于JSON顶层，也已写入API。

S3在保留原服务/lag检查基础上增加pending检查，阈值不是“已测得的健康值”。
正常负载与ACK时点仍需TASK-093/106确认。
错误率Gate的具体数值是对原“明显升高”的实施默认补齐；P99分支和错误率分支的断言保持一致。
S2固定7/8连接和5RPS不能当作已证明的故障复现，实际Gate由对应Task校准。

## 6. Task依赖与迁移顺序

```text
001～066 → 068 → 074 → 075 → 076 → 067 → 069～073 → 077～109
```

068先有ActionExecution，074建立恢复表并补完整外键，075/076准备合法恢复合同，
067/069才允许真实批准。TASK-066此前完成的是接口合同，不是可运行批准闭环。

TASK-021先创建恢复关联列，TASK-074补最终FK及采样唯一约束。
Task编号、原里程碑分组不改；每个Task追加了权威阅读文件、前置任务、范围与验证要求。
同一核心状态机任务默认串行，不以并行Agent扩大修改权。

## 7. 合并动作记录

以下记录本次按章节执行的替换/补齐；同一章节可能同时吸收多项裁决。
未列出的主体章节保留，仅做标题层级与元信息规范化。

| 正式文件/章节 | 动作 | 原因 |
|---|---|---|
| 00-product.md §5 | REPLACE | 验收阶段修正与 FINAL-FREEZE-05 |
| 00-product.md §11 | REPLACE | FINAL-FREEZE-01 |
| 00-product.md §16 | REPLACE | 定稿合并 |
| 00-product.md §20 | REPLACE | 定稿合并 |
| 00-product.md §21 | MERGE | 定稿补足 |
| 00-product.md §29 | REPLACE | 定稿合并 |
| 00-product.md §32 | REPLACE | 定稿合并 |
| 00-product.md §34 | REPLACE | 定稿合并 |
| 00-product.md §8 | REPLACE_TEXT | 消除旧语义 |
| 00-product.md §23 | REPLACE_TEXT | 消除旧语义 |
| 01-lifecycle.md §4 | REPLACE | 定稿合并 |
| 01-lifecycle.md §9 | REPLACE | FINAL-FREEZE-01 |
| 01-lifecycle.md §10 | REPLACE | 定稿合并 |
| 01-lifecycle.md §11 | REPLACE | 定稿合并 |
| 01-lifecycle.md §20 | REPLACE | 定稿合并 |
| 01-lifecycle.md §24 | MERGE | 定稿补足 |
| 01-lifecycle.md §26 | MERGE | 定稿补足 |
| 01-lifecycle.md §30 | REPLACE | FINAL-FREEZE-04 |
| 01-lifecycle.md §37 | REPLACE | 定稿合并 |
| 01-lifecycle.md §38 | REPLACE | 定稿合并 |
| 01-lifecycle.md §39 | MERGE | 定稿补足 |
| 01-lifecycle.md §41 | REPLACE | 定稿合并 |
| 01-lifecycle.md §42 | REPLACE | 定稿合并 |
| 02-java-ai-boundary.md §26 | REPLACE | 定稿合并 |
| 02-java-ai-boundary.md §28 | REPLACE | 定稿合并 |
| 02-java-ai-boundary.md §29 | REPLACE | 定稿合并 |
| 02-java-ai-boundary.md §36 | REPLACE | 定稿合并 |
| 02-java-ai-boundary.md §43 | MERGE | 定稿补足 |
| 02-java-ai-boundary.md §46 | REPLACE | 定稿合并 |
| 02-java-ai-boundary.md §5 | MERGE | 定稿补足 |
| 03-domain-model.md §4 | REPLACE | FINAL-FREEZE-01 |
| 03-domain-model.md §20 | REPLACE | 定稿合并 |
| 03-domain-model.md §38 | REPLACE | 定稿合并 |
| 03-domain-model.md §50 | REPLACE | 定稿合并 |
| 03-domain-model.md §48 | MERGE | 定稿补足 |
| 03-domain-model.md §53 | REPLACE | 定稿合并 |
| 03-domain-model.md §54 | REPLACE | 定稿合并 |
| 03-domain-model.md §56 | REPLACE | 定稿合并 |
| 03-domain-model.md §74 | REPLACE | 定稿合并 |
| 03-domain-model.md §81 | REPLACE | 定稿合并 |
| 03-domain-model.md §82 | REPLACE | 定稿合并 |
| 03-domain-model.md §83 | REPLACE | 定稿合并 |
| 04-database.md §16 | REPLACE | FINAL-FREEZE-01 |
| 04-database.md §18 | MERGE | 定稿补足 |
| 04-database.md §19 | REPLACE | 定稿合并 |
| 04-database.md §21 | MERGE | 定稿补足 |
| 04-database.md §22 | MERGE | 定稿补足 |
| 04-database.md §29 | MERGE | 定稿补足 |
| 04-database.md §32 | MERGE | 定稿补足 |
| 04-database.md §34 | REPLACE | 定稿合并 |
| 04-database.md §45 | MERGE | FINAL-FREEZE-03/06 |
| 04-database.md §46 | REPLACE | 定稿合并 |
| 04-database.md §47 | REPLACE | 定稿合并 |
| 04-database.md §49 | REPLACE | 定稿合并 |
| 04-database.md §50 | MERGE | FINAL-FREEZE-04 |
| 04-database.md §52 | REPLACE | 定稿合并 |
| 04-database.md §57 | REPLACE | 定稿合并 |
| 04-database.md §59 | MERGE | 定稿补足 |
| 04-database.md §72 | REPLACE | 定稿合并 |
| 04-database.md §73 | MERGE | 定稿补足 |
| 04-database.md §75 | MERGE | 定稿补足 |
| 04-database.md §78 | MERGE | 定稿补足 |
| 04-database.md §79 | REPLACE | 定稿合并 |
| 04-database.md §80 | REPLACE | 定稿合并 |
| 04-database.md §82 | REPLACE | 定稿合并 |
| 04-database.md §95 | REPLACE | 定稿合并 |
| 04-database.md §97 | ADD | 最终裁决落位 |
| 04-database.md §98 | ADD | 最终裁决落位 |
| 06-capability.md §13 | REPLACE | 定稿合并 |
| 06-capability.md §22 | REPLACE | 定稿合并 |
| 06-capability.md §27 | MERGE | 定稿补足 |
| 06-capability.md §33 | REPLACE | 定稿合并 |
| 06-capability.md §34 | MERGE | 定稿补足 |
| 06-capability.md §42 | REPLACE | 定稿合并 |
| 06-capability.md §40 | MERGE | 定稿补足 |
| 06-capability.md §79 | REPLACE | 定稿合并 |
| 06-capability.md §86 | REPLACE | 定稿合并 |
| 06-capability.md §88 | REPLACE | 定稿合并 |
| 06-capability.md §89 | REPLACE | 定稿合并 |
| 06-capability.md §106 | MERGE | 定稿补足 |
| 06-capability.md §110 | REPLACE | FINAL-FREEZE-03 |
| 06-capability.md §113 | REPLACE | FINAL-FREEZE-04/05 |
| 06-capability.md §116 | REPLACE | 定稿合并 |
| 06-capability.md §123 | REPLACE | FINAL-FREEZE-01/02 |
| 06-capability.md §124 | REPLACE | CAP-PATCH-01与最终准入裁决 |
| 06-capability.md §128 | REPLACE | 定稿合并 |
| 06-capability.md §130 | REPLACE | 定稿合并 |
| 06-capability.md §137 | REPLACE | 定稿合并 |
| 06-capability.md §139 | REPLACE | 定稿合并 |
| 06-capability.md §140 | REPLACE | 定稿合并 |
| 05-api.md §18 | REPLACE | 定稿合并 |
| 05-api.md §24 | MERGE | 定稿补足 |
| 05-api.md §27 | REPLACE | API-PATCH-02与FINAL-FREEZE-02 |
| 05-api.md §28 | REPLACE | 定稿合并 |
| 05-api.md §30 | REPLACE | 定稿合并 |
| 05-api.md §33 | MERGE | 定稿补足 |
| 05-api.md §34 | MERGE | 定稿补足 |
| 05-api.md §38 | MERGE | 定稿补足 |
| 05-api.md §43 | REPLACE | 定稿合并 |
| 05-api.md §48 | REPLACE | 定稿合并 |
| 05-api.md §50 | REPLACE | 定稿合并 |
| 05-api.md §54 | REPLACE | 定稿合并 |
| 05-api.md §56 | REPLACE | 定稿合并 |
| 05-api.md §57 | MERGE | 定稿补足 |
| 05-api.md §65 | REPLACE | 定稿合并 |
| 05-api.md §67 | MERGE | 定稿补足 |
| 05-api.md §77 | REPLACE | 定稿合并 |
| 05-api.md §78 | REPLACE | 定稿合并 |
| 05-api.md §80 | REPLACE | 定稿合并 |
| 05-api.md §83 | REPLACE | 定稿合并 |
| 05-api.md §85 | REPLACE | 定稿合并 |
| 05-api.md §88 | REPLACE_TEXT | 消除旧语义 |
| 05-api.md §88 | MERGE | 定稿补足 |
| 05-api.md §89 | REPLACE | 定稿合并 |
| 05-api.md §91 | REPLACE | 定稿合并 |
| 05-api.md §93 | REPLACE_TEXT | 消除旧语义 |
| 05-api.md §93 | MERGE | 定稿补足 |
| 05-api.md §98 | REPLACE | 定稿合并 |
| 05-api.md §105 | REPLACE | 定稿合并 |
| 05-api.md §106 | REPLACE | 定稿合并 |
| 05-api.md §107 | ADD | 最终裁决落位 |
| 07-engineering.md §1 | REPLACE | 定稿合并 |
| 07-engineering.md §2 | REPLACE | 定稿合并 |
| 07-engineering.md §5 | REPLACE | 定稿合并 |
| 07-engineering.md §9 | REPLACE | 定稿合并 |
| 07-engineering.md §41 | REPLACE | 定稿合并 |
| 07-engineering.md §42 | REPLACE | 定稿合并 |
| 07-engineering.md §43 | REPLACE | 定稿合并 |
| 07-engineering.md §45 | MERGE | 定稿补足 |
| 07-engineering.md §48 | MERGE | 定稿补足 |
| 07-engineering.md §51 | REPLACE | FINAL-FREEZE-03 |
| 07-engineering.md §52 | REPLACE | 定稿合并 |
| 07-engineering.md §53 | REPLACE | 定稿合并 |
| 07-engineering.md §56 | REPLACE | 定稿合并 |
| 07-engineering.md §57 | REPLACE | 定稿合并 |
| 07-engineering.md §58 | MERGE | 定稿补足 |
| 07-engineering.md §64 | REPLACE | 定稿合并 |
| 07-engineering.md §66 | REPLACE | 定稿合并 |
| 07-engineering.md §67 | MERGE | 定稿补足 |
| 07-engineering.md §68 | REPLACE | 定稿合并 |
| 07-engineering.md §70 | REPLACE | 定稿合并 |
| 07-engineering.md §72 | REPLACE | 定稿合并 |
| 07-engineering.md §73 | REPLACE | 定稿合并 |
| 07-engineering.md §87 | MERGE | 定稿补足 |
| 07-engineering.md §88 | MERGE | 定稿补足 |
| 07-engineering.md §92 | MERGE | 定稿补足 |
| 07-engineering.md §97 | MERGE | 定稿补足 |
| 07-engineering.md §107 | MERGE | 定稿补足 |
| 07-engineering.md §138 | MERGE | 定稿补足 |
| 07-engineering.md §141 | REPLACE | 定稿合并 |
| 07-engineering.md §142 | REPLACE | 定稿合并 |
| 09-acceptance.md §3 | REPLACE | 定稿合并 |
| 09-acceptance.md §7 | MERGE | 定稿补足 |
| 09-acceptance.md §10 | REPLACE | 定稿合并 |
| 09-acceptance.md §11 | REPLACE | 定稿合并 |
| 09-acceptance.md §16 | MERGE | 定稿补足 |
| 09-acceptance.md §25 | REPLACE | 定稿合并 |
| 09-acceptance.md §33 | REPLACE | Gate/断言对齐；显式补齐Demo错误率默认值 |
| 09-acceptance.md §39 | REPLACE | 定稿合并 |
| 09-acceptance.md §48 | REPLACE | 定稿合并 |
| 09-acceptance.md §50 | REPLACE | 定稿合并 |
| 09-acceptance.md §51 | REPLACE | 定稿合并 |
| 09-acceptance.md §55 | REPLACE | 定稿合并 |
| 09-acceptance.md §57 | REPLACE | 定稿合并 |
| 09-acceptance.md §61 | MERGE | 定稿补足 |
| 09-acceptance.md §75 | REPLACE | FINAL-FREEZE-05 |
| 09-acceptance.md §76 | REPLACE | 定稿合并 |
| 09-acceptance.md §77 | REPLACE | 定稿合并 |
| 09-acceptance.md §78 | REPLACE | FINAL-FREEZE-04 |
| 09-acceptance.md §79 | REPLACE | 定稿合并 |
| 09-acceptance.md §80 | REPLACE | 定稿合并 |
| 09-acceptance.md §81 | REPLACE | 定稿合并 |
| 09-acceptance.md §89 | REPLACE | 定稿合并 |
| 09-acceptance.md §99 | REPLACE | 定稿合并 |
| 09-acceptance.md §100 | REPLACE | 定稿合并 |
| 09-acceptance.md §101 | REPLACE | 定稿合并 |
| 09-acceptance.md §104 | REPLACE | 定稿合并 |
| 09-acceptance.md §105 | REPLACE | 定稿合并 |
| 09-acceptance.md §107 | REPLACE | 定稿合并 |
| 09-acceptance.md §108 | ADD | 最终裁决落位 |
| 08-implementation-plan.md §2 | REPLACE | 定稿合并 |
| 08-implementation-plan.md §3 | REPLACE | 定稿合并 |
| 08-implementation-plan.md §4 | REPLACE | 定稿合并 |
| 08-implementation-plan.md §5 | REPLACE | 定稿合并 |
| 08-implementation-plan.md §6 | REPLACE_TEXT | 消除旧语义 |
| 08-implementation-plan.md §7 | REPLACE | 定稿合并 |
| 08-implementation-plan.md §8 | REPLACE | 保留任务编号，应用最终裁决与显式依赖 |
| 08-implementation-plan.md §9 | REPLACE | 保留任务编号，应用最终裁决与显式依赖 |
| 08-implementation-plan.md §10 | REPLACE | 保留任务编号，应用最终裁决与显式依赖 |
| 08-implementation-plan.md §11 | REPLACE | 保留任务编号，应用最终裁决与显式依赖 |
| 08-implementation-plan.md §12 | REPLACE | 保留任务编号，应用最终裁决与显式依赖 |
| 08-implementation-plan.md §13 | REPLACE | 保留任务编号，应用最终裁决与显式依赖 |
| 08-implementation-plan.md §14 | REPLACE | 保留任务编号，应用最终裁决与显式依赖 |
| 08-implementation-plan.md §15 | REPLACE | 保留任务编号，应用最终裁决与显式依赖 |
| 08-implementation-plan.md §16 | REPLACE | 保留任务编号，应用最终裁决与显式依赖 |
| 08-implementation-plan.md §17 | REPLACE | 保留任务编号，应用最终裁决与显式依赖 |
| 08-implementation-plan.md §18 | REPLACE | 保留任务编号，应用最终裁决与显式依赖 |
| 08-implementation-plan.md §19 | REPLACE | 保留任务编号，应用最终裁决与显式依赖 |
| 08-implementation-plan.md §20 | REPLACE | 保留任务编号，应用最终裁决与显式依赖 |
| 08-implementation-plan.md §21 | REPLACE | 保留任务编号，应用最终裁决与显式依赖 |
| 08-implementation-plan.md §22 | REPLACE | 保留任务编号，应用最终裁决与显式依赖 |
| 08-implementation-plan.md §34 | REPLACE | 定稿合并 |
| 08-implementation-plan.md §37 | REPLACE | 定稿合并 |
| 08-implementation-plan.md §42 | REPLACE | 定稿合并 |
| 08-implementation-plan.md §43 | REPLACE | 定稿合并 |
| 08-implementation-plan.md §44 | REPLACE | 定稿合并 |
| 07-engineering.md §54 | REPLACE_TEXT | 消除旧语义 |
| 04-database.md §92 | REPLACE | 应用DB-PATCH-08已冻结的本地RawResultStore |
| 05-api.md §104 | REPLACE_TEXT | 消除旧语义 |
| 06-capability.md §112 | REPLACE_TEXT | 消除旧语义 |
| 07-engineering.md §114 | REPLACE | 系统验收已完成定稿，不保留下一阶段占位 |
| 05-api.md §103 | REPLACE | 统一正式Internal协议版本位置，去掉二选一残留 |
| 04-database.md §30 | MERGE | 应用API对齐补丁禁止复制事实绕过唯一约束 |
| 05-api.md §23 | REPLACE_TEXT | 消除旧语义 |
| 05-api.md §82 | REPLACE_TEXT | 消除旧语义 |
| 05-api.md §81 | MERGE | 区分消息时间与ACK时间，示例不暗示未实现测量 |
| 07-engineering.md §84 | REPLACE_TEXT | 修正文档代码块语言，fixture名称不是JSON正文 |
| 05-api.md §85 | MERGE | 保留完整最终Diagnosis草稿协议示例 |
| 04-database.md §36 | REPLACE_TEXT | 消除旧语义 |
| 03-domain-model.md §48 | REPLACE | 消除幂等键本身保证远端只执行一次的过强表述 |
| 05-api.md §90 | MERGE | 保留单用户范围并明确部署访问前提 |

## 8. 原始输入字节校验

完整包originals是下列输入的原样副本，未“修复”历史内容。SHA256用于文件完整性核对，不表示规格正确性。

| 原始文件 | 字节数 | SHA256 |
|---|---:|---|
| OpsPilot V0.1 API 契约与物理模型最终对齐补丁.md | 13,818 | `24c03c2e9db6ff89667a95e826e5efd5e7b0ec68439bd1b3ad51cc66a1c00af8` |
| OpsPilot V0.1 API 契约设计.md | 50,514 | `8f46007080d4620a5e6d6cfc17019d7bb28cf0bb7edda4dc257e643c5d6c654b` |
| OpsPilot V0.1 Capability 契约最终冻结补丁.md | 15,075 | `d5aa59d4ba1578e508ce0cb4450473d2f3734c1ed5b8244325d4c573d5acceba` |
| OpsPilot V0.1 Capability 详细契约设计.md | 52,138 | `090bd84e6eb9388dd2e4bb8ad4b9c2ea0469984cdab7670b167f3f58ceebc2a0` |
| OpsPilot V0.1 Java 主服务与 AI Runtime 职责边界设计.md | 29,046 | `814e8c25992f8fa909ea4b82cdac355336409ef85a9f29270969e616e7ffaa1f` |
| OpsPilot V0.1 MySQL 物理数据模型设计.md | 38,278 | `fb96bf3837c294e92ea086afff98cc29f03070427fbd7ce8d2ff168e5d0f32b5` |
| OpsPilot V0.1 MySQL 物理模型最终冻结补丁.md | 7,361 | `66c13ccff0cea6154591a656778248a57fb73c5a50d157cf2254bcbd58cab4f0` |
| OpsPilot V0.1 S1 - S2 - S3 系统验收与故障实验设计.md | 46,902 | `0e2b871edb68d81c7e559268f620347a909b02031ba78bebedd10ec22bdf5712` |
| OpsPilot V0.1 产品定义与核心领域设计.md | 21,928 | `0f00a4fe5526c146a4f9580d4818a3dff8d228a7fc46a76ee7eb52759da370f5` |
| OpsPilot V0.1 工程结构与编码规范设计.md | 49,874 | `cfc7c21919a8bbe244bb2dd620004804072569b19951c0f59b3d4ed9036cd890` |
| OpsPilot V0.1 开发任务拆分与实施顺序.md | 42,055 | `567c0ced23a3aee99bc8464999603493741492d9bbe9cbc198e767bb49261c52` |
| OpsPilot V0.1 核心生命周期与状态机设计.md | 28,493 | `187a5c97ca91ad866b562df2882d72deb591a5da5e2d68b1b7e1c70e658c1e75` |
| OpsPilot V0.1 核心领域关系与持久化边界设计.md | 34,167 | `b196b08703b81dc73ceb585898f0774e1170c2af9bacc4207b0920f9ac8ed3fa` |
| OpsPilot V0.1 生命周期与状态机最终冻结补丁.md | 9,208 | `1fa0bf4790d37382209a98d80733c03274759375b5963d0a290d8f966d9737df` |

## 9. 未执行与交付边界

本次没有创建或运行Java/Python/React业务工程，没有执行Flyway Migration，
没有调用真实LLM或基础设施Provider，没有启动Docker/ShortLink，没有完成S1/S2/S3故障验收。
没有访问用户Git仓库，未导入、提交、覆盖或移动仓库文件。

FROZEN表示采用本轮裁决的规格基线已定稿。
当前已完成TASK-001的文本产物；目标仓库导入核对仍要执行。
后续按Task推进，不需要为了普通实现细节再开架构设计阶段。
