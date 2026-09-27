# 功能批次实施与 Review 计划

> 生效：2026-09-27，用户确认采用批次工作流。TASK-012 按原流程收尾，从 TASK-013 开始使用。
> 本文只定义交付编组和记录方式。Task 的业务范围、前置依赖、DoD 仍以 [08](../specs/08-implementation-plan.md) 及各 Task 引用的正式规格为准。
> 这是执行计划，不是完成报告；实际状态与证据见 [PROGRESS](PROGRESS.md)，当前断点见 [CURRENT](CURRENT.md)。

## 1. Agent 开工顺序

1. 读取根目录 AGENTS.md、SPEC-MANIFEST.md、CURRENT.md，检查 `git status --short`、相关 diff 和最近提交。
2. 查本文批次表及 PROGRESS 的批次记录、成员 Task 行；只执行当前获准批次，不按聊天摘要或连续编号猜任务。
3. 逐项读取成员 Task 的正式定义、前置任务和引用章节，并检查 PROGRESS 中归属这些 Task 的待处理问题。
4. 批外前置 Task 必须已验证、独立 Review 通过、提交并标记 DONE；批内按原依赖顺序实施。不得仅因同属一批就同时把所有成员标 READY。
5. 开工前在 CURRENT 和 PROGRESS 固定批次 ID、成员顺序、`git rev-parse HEAD` 的完整基线 SHA、允许目录、明确不做、关键不变量及验证要求。基线在本批 Review/修复期间不移动。
6. 已有无关未提交修改逐项列出并保护，不覆盖、不混入本批交付。若使基线或 Review 范围无法区分，先解决范围归属，不继续堆代码。

## 2. 批内实施、验证、Review、提交

- 一次一个批次；批内按依赖实施，每项完成其针对性验证后才推进依赖它的成员。不要求批内中间提交或逐 Task 独立 Review；失败测试未解决不得继续依赖工作。
- 每项 Task 仍保留自身 DoD。批尾在最终代码树上执行覆盖所有成员 DoD 的完整验证，同一次构建可以作为多项 Task 的共同证据，不机械重复。
- 涉及 Java 时执行规定的 Maven Wrapper verify；Enforcer、Spotless 不跳过。涉及 Python、Web、跨语言协议、SQL/事务、Provider 的相应验证不能被 Java BUILD SUCCESS 替代。真实 MySQL、真实 Provider/AI 和场景验收按原 Task 要求执行；缺少环境如实写 NOT RUN/NOT VERIFIED。
- 实施交付后整批进入 REVIEW；独立 Reviewer 审查从固定 base SHA 到当前代码树的整个功能链：`git status --short`、`git diff --check`、`git diff --stat <base>`、`git diff <base>`，并用 `git ls-files --others --exclude-standard` 列出和读取新增文件。`git diff` 不包含未跟踪文件，不能漏审。
- Reviewer 核实实际实现与证据，关注事务、锁序、迟到结果、安全边界和跨 Task 接合，不仅看实施报告。记录自己的实际验证，无法执行的项目标 NOT VERIFIED。
- Review 修复仍在同一批次、同一基线内。修复后重跑受影响验证和最终必要门禁；证据必须覆盖最终代码，旧失败或旧版本成功不能冒充当前结果。PASS AFTER PATCH 表示尚待修复/复核，不是提交许可。
- 整批无提交前阻塞项且必要验证满足，独立 Review PASS 后，按用户提交授权通常提交一次；未获提交授权时停留 REVIEW 并注明“已通过，待提交”，不擅自提交或推进下一批。不得重写既有历史来减少提交数。
- 提交后核实实际 SHA，在 PROGRESS 回填；批次及所有成员一起标 DONE。若提交后仅需回填进度，可在后续文档提交记录代码 SHA，无需 amend、自引用哈希或伪造提交。
- 批内实现完成但整批未通过时，成员保持 IN_PROGRESS，并注明“实现与针对性验证完成，待整批验证/Review”；整批送审时成员转 REVIEW。尚未开工成员保持 TODO/READY。受阻项注明原因，不能提前 DONE。
- 正式状态沿用 TODO / READY / IN_PROGRESS / REVIEW / BLOCKED / DONE，不增加业务状态。批次 READY 要求所有批外前置满足；成员 READY 可接受同批前置已完成针对性验证，批外仍要求 DONE。
- 成员范围的并集是本批范围，不是整个目录的重构授权。同一核心边界同一时间只有一个实施负责人；不因批次较大并发修改状态机/调查/恢复核心。发现过大可提出拆批，调整映射前记录原因并获得用户确认，不私自扩批或改依赖。

## 3. 批次表

共 44 批，覆盖 TASK-013～109 共 97 项。表内顺序就是实施顺序；特别保留 068 → 074～076 → 067 → 069。

| 批次 | 成员 Task（按顺序） | 交付目标 |
|---|---|---|
| B01 | 013–015 | Incident 模型、受控状态更新、创建事务 |
| B02 | 016–017 | 开始/继续调查与 run 初始化/切换 |
| B03 | 018–020 | 停止、取消与基础 Incident API |
| B04 | 021–022 | 调查事实表与不可变 Observation |
| B05 | 023–024 | Hypothesis 与 Evidence 关系 |
| B06 | 025–027 | Diagnosis 规则、持久化与调查查询 |
| B07 | 028–032 | AI 协议、Java/Python 类型与共享契约测试 |
| B08 | 033–034 | Python 决策服务与 Java AI 客户端 |
| B09 | 035–036 | 工作派发与单实例并发控制 |
| B10 | 037–039 | 调查上下文、AgentStep 与原子准入 |
| B11 | 040–041 | Intent 分派与 Stop 竞态处理 |
| B12 | 042–043 | 调查终止与启动恢复 |
| B13 | 044–046 | Capability 注册、允许能力描述与 Provider 解析 |
| B14 | 047–048 | 调用去重、预算与 OBSERVE 执行准入 |
| B15 | 049–051 | 脱敏、原始结果保存与确定性提取 |
| B16 | 052–053 | Metrics / Logs Provider |
| B17 | 054–056 | Redis / MySQL / Redis Stream 只读 Provider |
| B18 | 057–058 | Docker 只读 Provider 与真实调查链路集成 |
| B19 | 059–061 | 调查完成、诊断版本演进与未确定结论 |
| B20 | 062–064 | 修复计划表、上下文与 Proposal 校验 |
| B21 | 065–066 | 请求修复、审批查询、拒绝与取消 |
| B22 | 068, 074 | Execution 与 Recovery 存储合同 |
| B23 | 075–076 | 恢复策略类型、合法性校验与激活 |
| B24 | 067 | 审批并发、过期计划与策略准入 |
| B25 | 069 | 批准事务、执行前快照与派发 |
| B26 | 070–071 | Docker Restart 与执行 Worker |
| B27 | 072–073 | 有界只读核对与执行启动恢复 |
| B28 | 077–079 | 三值判定、持久化采样与 Verification Runner |
| B29 | 080–081 | 执行后验证与手动验证入口 |
| B30 | 082–083 | 恢复结果状态流转与验证启动恢复 |
| B31 | 084–086 | Timeline、完整详情视图与 availableActions |
| B32 | 087–089 | SSE、提交后通知与断线补发 |
| B33 | 090–092 | Fault Lab 模型、Ground Truth 隔离与 API |
| B34 | 093 | S3 Consumer Stop 真实注入 |
| B35 | 094 | S1 Redis Latency 真实注入 |
| B36 | 095 | S2 MySQL Slow Query 真实注入 |
| B37 | 096–098 | Web 基础壳、系统页与 Incident 列表 |
| B38 | 099–100 | Incident 详情与调查实时 Timeline |
| B39 | 101–103 | 审批、恢复与技术详情界面 |
| B40 | 104 | Fault Lab 界面 |
| B41 | 105–106 | Demo Compose、Seed 校准与真实健康检查 |
| B42 | 107 | S1 独立验收 |
| B43 | 108 | S2 独立验收 |
| B44 | 109 | S3 独立验收与最终控制流证据汇总 |

风险检查仍需逐项可追溯：B10 分别证明上下文隔离、Step 生命周期和锁内准入；B28 分别证明三值矩阵、样本身份/时间和总体结果；B33 先证明 Ground Truth 隔离及环境权限，再开放注入/重置 API。

阶段含义不变：020 仅基础 API/事务；043 控制循环可用 Fake Capability；058 必须真实 AI＋OBSERVE；083 为后台恢复闭环；109 才是完整真实场景验收。不得把早期 Fake/临时 Port 当成最终功能，回填要求按原 Task 执行（如 067、073、083）。

## 4. 如何记录，避免重复报告

### PROGRESS：保留总账和批次证据

- 原有 109 个 Task 行保留，增加“批次”列；001～012 为“单项”，013 起关联本表 ID。
- 批次表只在本文维护，不在进度里复制另一套依赖图。PROGRESS 只为已安排/启动批次建立记录：状态、base SHA、成员执行摘要、验证、Review、提交。
- 共同验证用 `Bxx-V1` 等证据编号记一次；各 Task 行引用它，同时指出自己的专项证据。每条证据写工作目录、命令、日期/环境、结果、覆盖 Task 和受测代码定位。未提交时写 base＋当前 diff/文件清单及验证时点，提交后关联真实 SHA。
- Review 用 `Bxx-R1` 等编号记录 Reviewer、审查代码范围、结论、P0/P1、修复复核和验证缺口；不能只保留聊天中的 PASS。
- 仅在有事实时填写结果。日志如需留存只保留必要、脱敏的证据并链接，不新建 109 份报告，不把 Secret 或巨大构建输出塞进进度表。

批次记录模板（复制到 PROGRESS，尖括号必须按实际填写）：

```markdown
### Bxx — <目标>
- 状态：<TODO/READY/IN_PROGRESS/REVIEW/BLOCKED/DONE>
- 成员及顺序：<TASK 列表>；批外前置：<已核实 Task/提交>
- Base SHA：<开工时 git rev-parse HEAD；未开工写未固定>
- 范围：<允许目录、明确不做、关键不变量、规格章节>
- 开工已有修改：<文件及归属；无则写无>
- 成员进度：<逐 Task 实现/针对性验证/未完成项>
- Bxx-V1：<目录；命令；时间/环境；exit code/结果；受测代码；覆盖 Task>
- 专项证据/NOT RUN：<真实 SQL/并发/协议/Provider 等及缺口>
- Bxx-R1：<Reviewer；base 到受审代码；结论；阻塞项及复核>
- 提交：<未提交，或实际代码 SHA>；范围外问题：<PROGRESS 问题行>
```

### CURRENT：只记录当前断点

```markdown
当前批次/状态：
成员 Task 及顺序：
固定 Base SHA：
批外前置核实：
允许目录 / 明确不做 / 关键不变量：
本批规格章节及 PROGRESS 记录：
当前成员及位置：
已实现并针对性验证的成员：
未完成 / 未执行验证：
未提交文件（含既有无关修改）：
共同验证及独立 Review 证据编号：
下一步具体动作：
```

停止、换会话或提交后更新 CURRENT；新会话沿断点继续，不重启整批，不靠聊天推断完成。未开工批次不提前填写基线或验证成功。

## 5. 首批 B01 的落地边界

先完成 TASK-012 的独立 Review 和提交；本文件建立不代表 TASK-012 通过，也不自动启动 B01。

- 顺序：013 模型/八状态转换规则 → 014 expectedStatus＋lock_version 条件更新 → 015 创建事务。
- 允许范围：成员 Task 涉及的 domain/application/infrastructure 与必要测试、进度文档；系统/资源 key 精确查找的已记录问题在接入 TASK-015 外部输入前处理。不提前实现 TASK-020 HTTP API。
- 必须证明：非法转换被拒；并发转换仅一个成功；Incident＋AffectedResource＋Timeline 同事务成功/回滚；系统与资源归属正确；遵守 V002 字段约束。SQL/事务用真实 MySQL。
- 批尾：`cd backend && ./mvnw -B clean verify`，并逐项核对 013～015 DoD；记录实际结果后提交独立 Review。
- 明确不做：Start/Continue/Stop/Cancel、AI 调查、TASK-021 事实表。建议提交标题：`feat(incident): add incident creation and guarded transitions (TASK-013–015)`。

## 6. 可直接使用的续接与 Review 指令

实施：读取 AGENTS、CURRENT、本文及 PROGRESS 当前批次。核对 Git、固定基线和批外依赖；仅按本批成员顺序实施，针对性验证逐项完成。批尾完成所有成员 DoD 的共同/专项验证，记录证据后停在 REVIEW，不自行开始下一批。

Review：只审指定批次。读取其记录和正式规格，检查固定 base 到当前代码树的全部变化（含未跟踪文件），核实整条功能链与每项 DoD。输出批次及 Task 范围、实际验证/NOT VERIFIED、PASS / PASS AFTER PATCH / FAIL、提交前 P0/P1、非阻塞项及 Commit Recommendation。不得把范围外改动或缺失验证静默算作通过。
