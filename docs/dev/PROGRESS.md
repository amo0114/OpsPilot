# 实施进度

> 编号与名称取自 docs/specs/08-implementation-plan.md 的 TASK 标题；范围、前置依赖与完成标准只以 08 为准，本表不复制任务正文、不维护第二份依赖图。
> 状态：TODO / READY / IN_PROGRESS / REVIEW / BLOCKED / DONE（08 §31 开发任务状态，与 IncidentStatus 无关）。依赖全部 DONE 才 READY；FROZEN 只表示规格定稿，不表示任务完成。
> 交付定位：已提交写真实 commit；未提交写“未提交＋变更文件”。验证摘要只写实际执行过的检查，未执行写 NOT RUN。
> 最近更新：2026-09-26（TASK-006 提交）

| Task | 名称 | 状态 | 交付定位 | 验证摘要 |
|---|---|---|---|---|
| TASK-001 | 导入已合并 Frozen Spec 并验证仓库落位 | DONE | commit 758d127（交付包原样落位＋授权修改 AGENTS.md、CLAUDE.md＋docs/dev 两份进度文件） | 2026-09-25 实测：SHA256SUMS 16/16 字节与哈希一致（改 AGENTS/CLAUDE 前，`sha256sum -c` exit 0；此后这两份按授权修改，与清单不同属预期）；链接 506 个 0 缺失、Manifest § 引用 95 个 0 未解析；TASK 001～109 唯一有序，前置依赖按 068→074～076→067→069 顺序 0 违例；Manifest 与 00～09 均 FROZEN/0.1；Incident 8 状态与 7 能力跨文件一致；4 类旧规则只以禁止语句出现；无 archive/旧补丁/业务代码 |
| TASK-002 | 创建 Monorepo 工程骨架 | DONE | TASK-002 commit（紧随 758d127）：.gitignore；backend/（父 POM＋5 Module＋mvnw 3.9.16）；ai-runtime/（uv＋FastAPI health，uv.lock 按 pypi.org 生成）；web/（npm＋Vite React TS 壳）；contracts/ai-runtime/v1、deploy、scripts 占位 | 独立 Review：PASS AFTER PATCH → 复核 PASS（P1-01 uv.lock 规范索引、P1-02 .env 忽略均关闭；mvnw.cmd NOT VERIFIED）。2026-09-25 修复后实测：backend `./mvnw -B clean verify` exit 0，boot jar `/actuator/health` 200 UP；ai-runtime 清除 UV_/PIP_ 索引变量后 `uv sync --locked`、`ruff format --check`、`ruff check`、`pytest`（1 passed）exit 0，uvicorn `/internal/v1/health` 200；web `npm ci`、`typecheck`、`build` exit 0；`git check-ignore` 根及 ai-runtime/backend/web 的 .env 均忽略 |
| TASK-003 | 建立工程约束 | DONE | TASK-003 commit（紧随 d68c953；同提交含用户的 .gitignore `.claude` 规则）：backend/pom.xml（Enforcer＋Spotless＋版本锁定）；backend/opspilot-web/pom.xml（springdoc）；AGENTS.md（补 07 §120 红线） | 独立 Review PASS（mvnw.cmd、真实 MySQL/Flyway NOT VERIFIED）。2026-09-26 实测：`./mvnw -B clean verify` exit 0（6 条 Enforcer 规则＋6 模块 spotless:check 通过）；负向：JDK 11 → RequireJavaVersion BUILD FAILURE exit 1；POM java.version=17 → RequireProperty(maven.compiler.release) BUILD FAILURE exit 1（已还原）；未格式化 Java → spotless:check BUILD FAILURE exit 1（已删除）；仓库外临时探针：lombok → BannedDependencies 失败，[3.0,) → BanDynamicVersions 失败；MyBatis 3.5.19/MyBatis-Spring 4.1.0/starter 4.1.0、Flyway 12.4.0＋flyway-mysql、mysql-connector-j 9.7.0 与 springdoc 同时解析且收敛通过；boot jar `/actuator/health` 200 UP，`/v3/api-docs` 200 openapi 3.1.0 |
| TASK-004 | 建立配置与错误模型基础 | DONE | TASK-004 commit（紧随 04560f9）：domain/error（ErrorCode、ErrorCategory、OpsPilotException、DomainException）；application/error/ApplicationException、application/correlation/Correlation；web/error（ApiExceptionHandler、ErrorResponse）、web/request/RequestIdFilter；boot @ConfigurationPropertiesScan＋日志 correlation 格式；application/web POM；web 测试 2 个文件 | 独立 Review：PASS AFTER PATCH（P1：异常 message/cause 原样写入日志）→ 复核 PASS（具体配置绑定、mvnw.cmd NOT VERIFIED）：三处日志只记 code/status、requestId 与异常链类型＋首个栈帧，不含 message。2026-09-26 修复后实测：`./mvnw -B clean verify` exit 0，ApiErrorContractTest 10/10（原 8 项＋3 个日志点用 OutputCapture 断言 message 与 cause 中的敏感串不进日志，其中 1 项替换原 500 测试）；整段构建输出敏感串 0 命中；变异检查：恢复 `log.error(..., ex)` 后该测试失败（报 hunter2），还原后通过；boot jar health 200、未知路由 404 统一包络；具体配置类绑定 NOT RUN |
| TASK-005 | 创建系统接入数据库结构 | DONE | TASK-005 commit（紧随 384589a）：infrastructure db/migration/V001__create_system_integration_tables.sql；infrastructure SystemIntegrationSchemaTest；infrastructure/boot POM；boot application.yml（datasource＋flyway） | 独立 Review 两轮 PASS AFTER PATCH：第 1 轮 P1 枚举 CHECK 在 ai_ci 下放行大小写/重音、正则 $ 放行末尾换行 → 正则改 `\z`（已关闭）；第 2 轮 P1 `utf8mb4_bin` 为 PAD SPACE 放行末尾空格 → 5 个枚举 CHECK 改为 `CAST(col AS BINARY) IN`（utf8mb4_0900_bin 需 8.0.17+，不选）。第 3 轮复核 PASS（MySQL 8.0.16 实机、mvnw.cmd NOT VERIFIED）。V001 提交前未应用于持久库，直接修改。2026-09-26 第 2 轮修复后实测（mysql:8.4.11）：`./mvnw -B clean verify` exit 0；SystemIntegrationSchemaTest 29/29（原 13＋5 大小写/重音＋5 末尾换行＋5 末尾空格，均断言 3819 与约束名）；变异检查：换回 utf8mb4_bin 后恰好 5 个末尾空格用例失败，上一轮恢复旧约束时恰好 11 例失败，还原后均通过；boot jar 连独立容器首次应用 v001、再次 up to date，health 200；库内 CHECK 22、FK 4、UNIQUE 5；直连 SQL：ACTIVE␠、active、ÁCTIVE、SERVICE␠、MYSQL␠ 均 3819，合法 ACTIVE（HEX 414354495645）写入；容器已删除 |
| TASK-006 | 实现 ManagedSystem / ManagedResource 领域模型 | DONE | TASK-006 commit（紧随 c804769）：domain/system（ManagedSystem、ManagedResource、SystemStatus、ResourceStatus、ResourceType）；application/system（ManagedSystemRepository、ManagedResourceRepository 两个 Port）；infrastructure persistence/mybatis/system（2 Mapper＋XML、2 Row、2 MyBatis 仓储）；infrastructure POM（mybatis-spring-boot-starter；测试改用 spring-boot-starter-test＋starter-flyway）；infrastructure 测试 2 个文件 | 独立 Review PASS（适配器范围、只读 Port、environment String 均接受；MySQL 8.0.16、mvnw.cmd NOT VERIFIED）。2026-09-26 实测（mysql:8.4.11）：`./mvnw -B clean verify` exit 0（Enforcer 收敛通过）；MyBatisSystemRepositoryTest 8/8（按 key 查系统含全部字段与 null description、ARCHIVED；资源按系统限定查找、跨系统同名 key 不串、findById、按 key 排序列出、6 类型×3 状态逐一往返；3 个 Java 枚举与 information_schema CHECK 取值完全一致）＋SystemIntegrationSchemaTest 29/29＋web 10/10；变异检查：ResourceType 加 CONTAINER 后一致性测试失败、往返测试报错，还原后通过；boot jar 连真实 MySQL：v001 应用、health 200、/actuator/beans 含 2 Mapper 与 2 仓储（beans 端点仅本次命令行临时开放）；容器已删除 |
| TASK-007 | 实现数据源连接与资源绑定 | READY | — | NOT RUN |
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
| springdoc 默认开放 /v3/api-docs，启动日志 WARN 提示生产应关闭 | opspilot-web | 单用户 Demo 可接受；是否按 profile 关闭由 TASK-004 配置或 TASK-105 部署决定 | TASK-004/105 |
| banDynamicVersions 豁免整个 `io.github.ismoyuan.opspilot:*`，范围宽于“仅本工程 SNAPSHOT”（Review 非阻塞意见） | backend/pom.xml | 当前内部依赖版本明确；后续改动父 POM 的 Task 可收窄为仅豁免 SNAPSHOT | 后续触及父 POM 的 Task |
| 过滤器链中、DispatcherServlet 之外抛出的异常仍走 Boot 默认 /error 响应体，不是 05 §9 包络 | opspilot-web | 当前唯一过滤器为 RequestIdFilter；出现其他过滤器（如内部认证 05 §90）时再补 ErrorController | 引入新过滤器的 Task |
| 具体 @ConfigurationProperties 类（Investigation/AiRuntime/Worker/Dispatcher/Execution 等 07 §88 默认值）尚未创建；application-local/test.yml 未建空文件 | opspilot-boot | 由各配置所属 Task（TASK-034/035/071/072 等）强类型加入 | 对应 Task |
| 测试时 Mockito 动态加载 byte-buddy agent 输出 JDK WARNING，不影响结果 | opspilot-web 测试 | 未来 JDK 默认禁止时再在 surefire argLine 显式配置 agent | 后续触及测试构建的 Task |
| 错误日志不再输出完整堆栈，只有每层异常类型与首个栈帧，深层定位信息减少 | web/error/ApiExceptionHandler | 07 §99 优先；如排障不足，后续引入经过脱敏的 message 白名单，不恢复原始 message | 后续排障需要时 |
| 自 TASK-005 起 boot 启动需要可用 MySQL（OPSPILOT_DB_URL/USERNAME/PASSWORD），`./mvnw verify` 需要 Docker（Testcontainers） | opspilot-boot；opspilot-infrastructure 测试 | 无 Docker 时 verify 失败，不跳过；本地启动方式写在 CURRENT；Compose 由 TASK-105 | TASK-105 |
| 未设置 OPSPILOT_DB_PASSWORD 时 Spring 保留占位符原文，启动要到数据库认证才失败（Review 已实测认证失败退出），而不是在配置绑定时报出缺失变量 | opspilot-boot application.yml | 不泄露也不会连上；若需要明确报错，可在数据源配置/SecretResolver（TASK-009）处显式校验 | TASK-009 或后续配置 Task |
| environment 未在规格中给出枚举，V001 只要求非空（VARCHAR(32)）；config/selector 的 schema 与 payload 均为 NOT NULL，无配置的 Provider 用空对象＋schema | V001 | TASK-008 SchemaCodecRegistry、TASK-010 Seed 按此落地；如需枚举环境再加迁移 | TASK-008/010 |
| capability_key 只做 `domain.action` 格式 CHECK，不在库里枚举 7 个能力；存在性由 Java CapabilityRegistry 判定（04 §11） | V001 | TASK-044 Registry 与 TASK-010 Seed 校验绑定键 | TASK-044 |
| 领域 environment 为 String；05 §15 示例值为 "DEMO"，库内未枚举 | domain/system/ManagedSystem | 与 TASK-005 已接受的自由文本一致；TASK-010 Seed 使用 DEMO | TASK-010 |
