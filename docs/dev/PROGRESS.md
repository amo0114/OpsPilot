# 实施进度

> 编号与名称取自 docs/specs/08-implementation-plan.md 的 TASK 标题；范围、前置依赖与完成标准只以 08 为准，本表不复制任务正文、不维护第二份依赖图。
> 状态：TODO / READY / IN_PROGRESS / REVIEW / BLOCKED / DONE（08 §31 开发任务状态，与 IncidentStatus 无关）。依赖全部 DONE 才 READY；FROZEN 只表示规格定稿，不表示任务完成。
> 交付定位：已提交写真实 commit；未提交写“未提交＋变更文件”。验证摘要只写实际执行过的检查，未执行写 NOT RUN。
> 最近更新：2026-09-26（TASK-003 提交）

| Task | 名称 | 状态 | 交付定位 | 验证摘要 |
|---|---|---|---|---|
| TASK-001 | 导入已合并 Frozen Spec 并验证仓库落位 | DONE | commit 758d127（交付包原样落位＋授权修改 AGENTS.md、CLAUDE.md＋docs/dev 两份进度文件） | 2026-09-25 实测：SHA256SUMS 16/16 字节与哈希一致（改 AGENTS/CLAUDE 前，`sha256sum -c` exit 0；此后这两份按授权修改，与清单不同属预期）；链接 506 个 0 缺失、Manifest § 引用 95 个 0 未解析；TASK 001～109 唯一有序，前置依赖按 068→074～076→067→069 顺序 0 违例；Manifest 与 00～09 均 FROZEN/0.1；Incident 8 状态与 7 能力跨文件一致；4 类旧规则只以禁止语句出现；无 archive/旧补丁/业务代码 |
| TASK-002 | 创建 Monorepo 工程骨架 | DONE | TASK-002 commit（紧随 758d127）：.gitignore；backend/（父 POM＋5 Module＋mvnw 3.9.16）；ai-runtime/（uv＋FastAPI health，uv.lock 按 pypi.org 生成）；web/（npm＋Vite React TS 壳）；contracts/ai-runtime/v1、deploy、scripts 占位 | 独立 Review：PASS AFTER PATCH → 复核 PASS（P1-01 uv.lock 规范索引、P1-02 .env 忽略均关闭；mvnw.cmd NOT VERIFIED）。2026-09-25 修复后实测：backend `./mvnw -B clean verify` exit 0，boot jar `/actuator/health` 200 UP；ai-runtime 清除 UV_/PIP_ 索引变量后 `uv sync --locked`、`ruff format --check`、`ruff check`、`pytest`（1 passed）exit 0，uvicorn `/internal/v1/health` 200；web `npm ci`、`typecheck`、`build` exit 0；`git check-ignore` 根及 ai-runtime/backend/web 的 .env 均忽略 |
| TASK-003 | 建立工程约束 | DONE | TASK-003 commit（紧随 d68c953；同提交含用户的 .gitignore `.claude` 规则）：backend/pom.xml（Enforcer＋Spotless＋版本锁定）；backend/opspilot-web/pom.xml（springdoc）；AGENTS.md（补 07 §120 红线） | 独立 Review PASS（mvnw.cmd、真实 MySQL/Flyway NOT VERIFIED）。2026-09-26 实测：`./mvnw -B clean verify` exit 0（6 条 Enforcer 规则＋6 模块 spotless:check 通过）；负向：JDK 11 → RequireJavaVersion BUILD FAILURE exit 1；POM java.version=17 → RequireProperty(maven.compiler.release) BUILD FAILURE exit 1（已还原）；未格式化 Java → spotless:check BUILD FAILURE exit 1（已删除）；仓库外临时探针：lombok → BannedDependencies 失败，[3.0,) → BanDynamicVersions 失败；MyBatis 3.5.19/MyBatis-Spring 4.1.0/starter 4.1.0、Flyway 12.4.0＋flyway-mysql、mysql-connector-j 9.7.0 与 springdoc 同时解析且收敛通过；boot jar `/actuator/health` 200 UP，`/v3/api-docs` 200 openapi 3.1.0 |
| TASK-004 | 建立配置与错误模型基础 | READY | — | NOT RUN |
| TASK-005 | 创建系统接入数据库结构 | TODO | — | NOT RUN |
| TASK-006 | 实现 ManagedSystem / ManagedResource 领域模型 | TODO | — | NOT RUN |
| TASK-007 | 实现数据源连接与资源绑定 | TODO | — | NOT RUN |
| TASK-008 | 建立 SchemaCodecRegistry 基础 | TODO | — | NOT RUN |
| TASK-009 | 实现 SecretResolver | TODO | — | NOT RUN |
| TASK-010 | ShortLink Demo 系统 Seed | TODO | — | NOT RUN |
| TASK-011 | Systems Read API | TODO | — | NOT RUN |
| TASK-012 | Incident / Investigation 基础表 | TODO | — | NOT RUN |
| TASK-013 | Incident Domain Model | TODO | — | NOT RUN |
| TASK-014 | Incident 状态转换 Repository | TODO | — | NOT RUN |
| TASK-015 | 创建 Incident | TODO | — | NOT RUN |
| TASK-016 | 开始调查 | TODO | — | NOT RUN |
| TASK-017 | Continue Investigation | TODO | — | NOT RUN |
| TASK-018 | Stop Investigation Request | TODO | — | NOT RUN |
| TASK-019 | Cancel Incident | TODO | — | NOT RUN |
| TASK-020 | Incident 基础 API | TODO | — | NOT RUN |
| TASK-021 | 调查事实数据库结构 | TODO | — | NOT RUN |
| TASK-022 | Observation Domain / Persistence | TODO | — | NOT RUN |
| TASK-023 | Hypothesis Domain | TODO | — | NOT RUN |
| TASK-024 | Evidence Domain | TODO | — | NOT RUN |
| TASK-025 | Diagnosis Domain | TODO | — | NOT RUN |
| TASK-026 | Diagnosis 创建事务 | TODO | — | NOT RUN |
| TASK-027 | Investigation 技术详情 Query | TODO | — | NOT RUN |
| TASK-028 | Canonical AI Protocol Schema | TODO | — | NOT RUN |
| TASK-029 | Capability Arguments Protocol | TODO | — | NOT RUN |
| TASK-030 | Java Protocol Model | TODO | — | NOT RUN |
| TASK-031 | Python Pydantic Protocol | TODO | — | NOT RUN |
| TASK-032 | Java / Python Contract Test | TODO | — | NOT RUN |
| TASK-033 | AI Runtime 最小推理服务 | TODO | — | NOT RUN |
| TASK-034 | Java AiRuntimeClient | TODO | — | NOT RUN |
| TASK-035 | WorkDispatcher | TODO | — | NOT RUN |
| TASK-036 | SingleFlightRegistry | TODO | — | NOT RUN |
| TASK-037 | Investigation Context Builder | TODO | — | NOT RUN |
| TASK-038 | AgentStep 生命周期 | TODO | — | NOT RUN |
| TASK-039 | Investigation Guard | TODO | — | NOT RUN |
| TASK-040 | Intent Dispatcher | TODO | — | NOT RUN |
| TASK-041 | Stop Race Handling | TODO | — | NOT RUN |
| TASK-042 | Deterministic Termination | TODO | — | NOT RUN |
| TASK-043 | Investigation Startup Recovery | TODO | — | NOT RUN |
| TASK-044 | CapabilityRegistry | TODO | — | NOT RUN |
| TASK-045 | Capability Descriptor Builder | TODO | — | NOT RUN |
| TASK-046 | Provider Resolver | TODO | — | NOT RUN |
| TASK-047 | Canonical JSON + Duplicate Guard | TODO | — | NOT RUN |
| TASK-048 | CapabilityInvocation 执行骨架 | TODO | — | NOT RUN |
| TASK-049 | Sanitizer Framework | TODO | — | NOT RUN |
| TASK-050 | RawResultStore | TODO | — | NOT RUN |
| TASK-051 | ObservationExtractor | TODO | — | NOT RUN |
| TASK-052 | metrics.query | TODO | — | NOT RUN |
| TASK-053 | logs.search | TODO | — | NOT RUN |
| TASK-054 | cache.inspect | TODO | — | NOT RUN |
| TASK-055 | database.inspect | TODO | — | NOT RUN |
| TASK-056 | queue.inspect | TODO | — | NOT RUN |
| TASK-057 | service.inspect | TODO | — | NOT RUN |
| TASK-058 | Capability Execution Integration | TODO | — | NOT RUN |
| TASK-059 | COMPLETE_INVESTIGATION 全链路 | TODO | — | NOT RUN |
| TASK-060 | Diagnosis Version Evolution | TODO | — | NOT RUN |
| TASK-061 | Undetermined Outcomes | TODO | — | NOT RUN |
| TASK-062 | Remediation 数据结构 | TODO | — | NOT RUN |
| TASK-063 | Remediation Draft Context | TODO | — | NOT RUN |
| TASK-064 | Remediation Proposal 校验 | TODO | — | NOT RUN |
| TASK-065 | request-remediation | TODO | — | NOT RUN |
| TASK-066 | Approval API | TODO | — | NOT RUN |
| TASK-067 | Approval 并发与历史方案保护 | TODO | — | NOT RUN |
| TASK-068 | ActionExecution 数据结构 | TODO | — | NOT RUN |
| TASK-069 | Approve → Execution | TODO | — | NOT RUN |
| TASK-070 | Docker service.restart Executor | TODO | — | NOT RUN |
| TASK-071 | Execution Worker | TODO | — | NOT RUN |
| TASK-072 | Execution Reconciliation | TODO | — | NOT RUN |
| TASK-073 | Execution Startup Recovery | TODO | — | NOT RUN |
| TASK-074 | RecoveryPolicy / Verification 数据结构 | TODO | — | NOT RUN |
| TASK-075 | RecoveryPolicy Criteria V1 | TODO | — | NOT RUN |
| TASK-076 | RecoveryPolicy Activation | TODO | — | NOT RUN |
| TASK-077 | Recovery Predicate Evaluator | TODO | — | NOT RUN |
| TASK-078 | Recovery Sampling Runner | TODO | — | NOT RUN |
| TASK-079 | Recovery Verification Runner | TODO | — | NOT RUN |
| TASK-080 | Execution Success → Verification | TODO | — | NOT RUN |
| TASK-081 | Manual Verify Recovery | TODO | — | NOT RUN |
| TASK-082 | Verification Outcome Transition | TODO | — | NOT RUN |
| TASK-083 | Verification Startup Recovery | TODO | — | NOT RUN |
| TASK-084 | Timeline Query | TODO | — | NOT RUN |
| TASK-085 | IncidentDetailView | TODO | — | NOT RUN |
| TASK-086 | availableActions | TODO | — | NOT RUN |
| TASK-087 | SSE Hub | TODO | — | NOT RUN |
| TASK-088 | After Commit Event | TODO | — | NOT RUN |
| TASK-089 | SSE Reconnect | TODO | — | NOT RUN |
| TASK-090 | Fault Lab 基础模型 | TODO | — | NOT RUN |
| TASK-091 | Ground Truth Isolation | TODO | — | NOT RUN |
| TASK-092 | Fault Inject / Reset API | TODO | — | NOT RUN |
| TASK-093 | Statistics Consumer Stop Injector | TODO | — | NOT RUN |
| TASK-094 | Redis Latency Injector | TODO | — | NOT RUN |
| TASK-095 | MySQL Slow Query Injector | TODO | — | NOT RUN |
| TASK-096 | Web 基础壳 | TODO | — | NOT RUN |
| TASK-097 | Systems 页面 | TODO | — | NOT RUN |
| TASK-098 | Incident List | TODO | — | NOT RUN |
| TASK-099 | Incident Detail 核心页 | TODO | — | NOT RUN |
| TASK-100 | Investigation Timeline UI | TODO | — | NOT RUN |
| TASK-101 | Approval UI | TODO | — | NOT RUN |
| TASK-102 | Recovery UI | TODO | — | NOT RUN |
| TASK-103 | Technical Detail UI | TODO | — | NOT RUN |
| TASK-104 | Fault Lab UI | TODO | — | NOT RUN |
| TASK-105 | Demo Docker Compose | TODO | — | NOT RUN |
| TASK-106 | Demo Seed / Health Check | TODO | — | NOT RUN |
| TASK-107 | S1 Acceptance | TODO | — | NOT RUN |
| TASK-108 | S2 Acceptance | TODO | — | NOT RUN |
| TASK-109 | S3 Acceptance | TODO | — | NOT RUN |

## 待处理问题

| 发现 | 位置 | 影响与建议 | 所属 |
|---|---|---|---|
| 用户放入的 Windows 下载元数据文件 | OpsPilot-START-HERE.md:Zone.Identifier | TASK-002 的 .gitignore 已忽略 `*:Zone.Identifier`；文件本身未删，是否删除由用户决定 | 用户 |
| MyBatis starter/Flyway/MySQL 驱动只在父 POM 锁定版本（Flyway、驱动沿用 Boot BOM），尚未进入任何模块；进 classpath 即要求 DataSource | backend/pom.xml | 按用户确认的方案，TASK-005/007 接入数据库时再加入 infrastructure/boot，届时 Boot 4 需 spring-boot-starter-flyway＋flyway-mysql | TASK-005/007 |
| springdoc 默认开放 /v3/api-docs，启动日志 WARN 提示生产应关闭 | opspilot-web | 单用户 Demo 可接受；是否按 profile 关闭由 TASK-004 配置或 TASK-105 部署决定 | TASK-004/105 |
| banDynamicVersions 豁免整个 `io.github.ismoyuan.opspilot:*`，范围宽于“仅本工程 SNAPSHOT”（Review 非阻塞意见） | backend/pom.xml | 当前内部依赖版本明确；后续改动父 POM 的 Task 可收窄为仅豁免 SNAPSHOT | 后续触及父 POM 的 Task |
