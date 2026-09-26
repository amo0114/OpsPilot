# 实施进度

> 编号与名称取自 docs/specs/08-implementation-plan.md 的 TASK 标题；范围、前置依赖与完成标准只以 08 为准，本表不复制任务正文、不维护第二份依赖图。
> 状态：TODO / READY / IN_PROGRESS / REVIEW / BLOCKED / DONE（08 §31 开发任务状态，与 IncidentStatus 无关）。依赖全部 DONE 才 READY；FROZEN 只表示规格定稿，不表示任务完成。
> 交付定位：已提交写真实 commit；未提交写“未提交＋变更文件”。验证摘要只写实际执行过的检查，未执行写 NOT RUN。
> 最近更新：2026-09-26（TASK-010 提交）

| Task | 名称 | 状态 | 交付定位 | 验证摘要 |
|---|---|---|---|---|
| TASK-001 | 导入已合并 Frozen Spec 并验证仓库落位 | DONE | commit 758d127（交付包原样落位＋授权修改 AGENTS.md、CLAUDE.md＋docs/dev 两份进度文件） | 2026-09-25 实测：SHA256SUMS 16/16 字节与哈希一致（改 AGENTS/CLAUDE 前，`sha256sum -c` exit 0；此后这两份按授权修改，与清单不同属预期）；链接 506 个 0 缺失、Manifest § 引用 95 个 0 未解析；TASK 001～109 唯一有序，前置依赖按 068→074～076→067→069 顺序 0 违例；Manifest 与 00～09 均 FROZEN/0.1；Incident 8 状态与 7 能力跨文件一致；4 类旧规则只以禁止语句出现；无 archive/旧补丁/业务代码 |
| TASK-002 | 创建 Monorepo 工程骨架 | DONE | TASK-002 commit（紧随 758d127）：.gitignore；backend/（父 POM＋5 Module＋mvnw 3.9.16）；ai-runtime/（uv＋FastAPI health，uv.lock 按 pypi.org 生成）；web/（npm＋Vite React TS 壳）；contracts/ai-runtime/v1、deploy、scripts 占位 | 独立 Review：PASS AFTER PATCH → 复核 PASS（P1-01 uv.lock 规范索引、P1-02 .env 忽略均关闭；mvnw.cmd NOT VERIFIED）。2026-09-25 修复后实测：backend `./mvnw -B clean verify` exit 0，boot jar `/actuator/health` 200 UP；ai-runtime 清除 UV_/PIP_ 索引变量后 `uv sync --locked`、`ruff format --check`、`ruff check`、`pytest`（1 passed）exit 0，uvicorn `/internal/v1/health` 200；web `npm ci`、`typecheck`、`build` exit 0；`git check-ignore` 根及 ai-runtime/backend/web 的 .env 均忽略 |
| TASK-003 | 建立工程约束 | DONE | TASK-003 commit（紧随 d68c953；同提交含用户的 .gitignore `.claude` 规则）：backend/pom.xml（Enforcer＋Spotless＋版本锁定）；backend/opspilot-web/pom.xml（springdoc）；AGENTS.md（补 07 §120 红线） | 独立 Review PASS（mvnw.cmd、真实 MySQL/Flyway NOT VERIFIED）。2026-09-26 实测：`./mvnw -B clean verify` exit 0（6 条 Enforcer 规则＋6 模块 spotless:check 通过）；负向：JDK 11 → RequireJavaVersion BUILD FAILURE exit 1；POM java.version=17 → RequireProperty(maven.compiler.release) BUILD FAILURE exit 1（已还原）；未格式化 Java → spotless:check BUILD FAILURE exit 1（已删除）；仓库外临时探针：lombok → BannedDependencies 失败，[3.0,) → BanDynamicVersions 失败；MyBatis 3.5.19/MyBatis-Spring 4.1.0/starter 4.1.0、Flyway 12.4.0＋flyway-mysql、mysql-connector-j 9.7.0 与 springdoc 同时解析且收敛通过；boot jar `/actuator/health` 200 UP，`/v3/api-docs` 200 openapi 3.1.0 |
| TASK-004 | 建立配置与错误模型基础 | DONE | TASK-004 commit（紧随 04560f9）：domain/error（ErrorCode、ErrorCategory、OpsPilotException、DomainException）；application/error/ApplicationException、application/correlation/Correlation；web/error（ApiExceptionHandler、ErrorResponse）、web/request/RequestIdFilter；boot @ConfigurationPropertiesScan＋日志 correlation 格式；application/web POM；web 测试 2 个文件 | 独立 Review：PASS AFTER PATCH（P1：异常 message/cause 原样写入日志）→ 复核 PASS（具体配置绑定、mvnw.cmd NOT VERIFIED）：三处日志只记 code/status、requestId 与异常链类型＋首个栈帧，不含 message。2026-09-26 修复后实测：`./mvnw -B clean verify` exit 0，ApiErrorContractTest 10/10（原 8 项＋3 个日志点用 OutputCapture 断言 message 与 cause 中的敏感串不进日志，其中 1 项替换原 500 测试）；整段构建输出敏感串 0 命中；变异检查：恢复 `log.error(..., ex)` 后该测试失败（报 hunter2），还原后通过；boot jar health 200、未知路由 404 统一包络；具体配置类绑定 NOT RUN |
| TASK-005 | 创建系统接入数据库结构 | DONE | TASK-005 commit（紧随 384589a）：infrastructure db/migration/V001__create_system_integration_tables.sql；infrastructure SystemIntegrationSchemaTest；infrastructure/boot POM；boot application.yml（datasource＋flyway） | 独立 Review 两轮 PASS AFTER PATCH：第 1 轮 P1 枚举 CHECK 在 ai_ci 下放行大小写/重音、正则 $ 放行末尾换行 → 正则改 `\z`（已关闭）；第 2 轮 P1 `utf8mb4_bin` 为 PAD SPACE 放行末尾空格 → 5 个枚举 CHECK 改为 `CAST(col AS BINARY) IN`（utf8mb4_0900_bin 需 8.0.17+，不选）。第 3 轮复核 PASS（MySQL 8.0.16 实机、mvnw.cmd NOT VERIFIED）。V001 提交前未应用于持久库，直接修改。2026-09-26 第 2 轮修复后实测（mysql:8.4.11）：`./mvnw -B clean verify` exit 0；SystemIntegrationSchemaTest 29/29（原 13＋5 大小写/重音＋5 末尾换行＋5 末尾空格，均断言 3819 与约束名）；变异检查：换回 utf8mb4_bin 后恰好 5 个末尾空格用例失败，上一轮恢复旧约束时恰好 11 例失败，还原后均通过；boot jar 连独立容器首次应用 v001、再次 up to date，health 200；库内 CHECK 22、FK 4、UNIQUE 5；直连 SQL：ACTIVE␠、active、ÁCTIVE、SERVICE␠、MYSQL␠ 均 3819，合法 ACTIVE（HEX 414354495645）写入；容器已删除 |
| TASK-006 | 实现 ManagedSystem / ManagedResource 领域模型 | DONE | TASK-006 commit（紧随 c804769）：domain/system（ManagedSystem、ManagedResource、SystemStatus、ResourceStatus、ResourceType）；application/system（ManagedSystemRepository、ManagedResourceRepository 两个 Port）；infrastructure persistence/mybatis/system（2 Mapper＋XML、2 Row、2 MyBatis 仓储）；infrastructure POM（mybatis-spring-boot-starter；测试改用 spring-boot-starter-test＋starter-flyway）；infrastructure 测试 2 个文件 | 独立 Review PASS（适配器范围、只读 Port、environment String 均接受；MySQL 8.0.16、mvnw.cmd NOT VERIFIED）。2026-09-26 实测（mysql:8.4.11）：`./mvnw -B clean verify` exit 0（Enforcer 收敛通过）；MyBatisSystemRepositoryTest 8/8（按 key 查系统含全部字段与 null description、ARCHIVED；资源按系统限定查找、跨系统同名 key 不串、findById、按 key 排序列出、6 类型×3 状态逐一往返；3 个 Java 枚举与 information_schema CHECK 取值完全一致）＋SystemIntegrationSchemaTest 29/29＋web 10/10；变异检查：ResourceType 加 CONTAINER 后一致性测试失败、往返测试报错，还原后通过；boot jar 连真实 MySQL：v001 应用、health 200、/actuator/beans 含 2 Mapper 与 2 仓储（beans 端点仅本次命令行临时开放）；容器已删除 |
| TASK-007 | 实现数据源连接与资源绑定 | DONE | TASK-007 commit（紧随 0a0818b）：domain/system（DataSourceConnection、ResourceBinding、CapabilityBinding、ProviderType、ConnectionStatus、SelectorSchema、ConfigSchema）；application/system（DataSourceConnectionRepository、ResourceBindingRepository、CapabilityBindingRepository 三个只读 Port）；infrastructure persistence/mybatis/system（3 Mapper＋XML、3 Row、3 MyBatis 仓储）；infrastructure 测试 MyBatisBindingRepositoryTest | 2026-09-26 实测（mysql:8.4.11）：`./mvnw -B clean verify` exit 0；MyBatisBindingRepositoryTest 13/13（连接按 key/id 全字段含 credentialRef、config schema 与 JSON 载荷；无凭据＋DISABLED；5 Provider×3 状态往返；资源绑定按资源限定、按连接 id 排序、SelectorSchema 与载荷；能力绑定按资源限定、enabled=false 保留、按 key 排序；connection_key 与 capability_key 的大小写/重音变体 6 例不命中；ProviderType、ConnectionStatus 与 CHECK 取值一致）＋SystemIntegrationSchemaTest 29/29＋MyBatisSystemRepositoryTest 8/8＋web 10/10；变异检查：删除两处按字节比较后恰好 6 个变体用例失败，还原后通过；独立 Review PASS（无 P0/P1；复核实测 `./mvnw -B clean verify` exit 0、infrastructure 50/50、web 10/10；正式 boot jar 连 MySQL 8.4.11 迁移成功、health 200 UP、3 Mapper 与 3 仓储装配；`git diff --check` 通过）；MySQL 8.0.16、mvnw.cmd NOT VERIFIED |
| TASK-008 | 建立 SchemaCodecRegistry 基础 | DONE | TASK-008 commit（紧随 3740c16）：domain/system/binding（PrometheusResourceBindingV1＋PrometheusMetricBindingV1、LokiResourceBindingV1、RedisResourceBindingV1、MySqlResourceBindingV1、DockerResourceBindingV1、包内 BindingLabels）；application/schema（SchemaCodecRegistry Port、SchemaPayloadException）；infrastructure/schema/JacksonSchemaCodecRegistry；infrastructure POM（tools.jackson.core:jackson-databind，Boot 管理 3.1.5）；infrastructure 测试 JacksonSchemaCodecRegistryTest | 2026-09-26 实测：`./mvnw -B clean verify` exit 0（infrastructure 95/95、web 10/10，Enforcer/Spotless 通过）；JacksonSchemaCodecRegistryTest 45/45（5 种 V1 按规格示例解码、Redis 缓存/Stream 两形态、decodeSelector；未注册 name/version 5 例 UNKNOWN_SCHEMA；类型错配 TYPE_MISMATCH；非法载荷 33 例 INVALID_PAYLOAD：畸形/顶层 null/数组/字符串/尾随内容/未知字段/重复键/数字与布尔冒充字符串/缺失/null/非法名与标签/空 metrics/Redis 半配置与 glob/MySQL 注入与超长等；拒绝信息不含载荷值、无 cause、details 为空）；变异检查：去掉字符串强转禁止→4 例失败、去掉重复键检测→1 例失败、去掉 FAIL_ON_UNKNOWN_PROPERTIES 等→3 例失败（Jackson 3 默认不拒绝未知字段），均已还原；boot jar 连 MySQL 8.4.11：v001 应用、health 200 UP、beans 含 jacksonSchemaCodecRegistry（beans 端点仅本次命令行临时开放），容器已删除；独立 Review PASS（无 P0/P1；复核 `./mvnw -B clean verify` exit 0、Codec 45/45、infrastructure 95/95、web 10/10；boot jar 连 MySQL 8.4.11 health 200 UP、Registry Bean 装配；确认 Jackson 3.1.5；`git diff --check` 通过）；MySQL 8.0.16、mvnw.cmd NOT VERIFIED |
| TASK-009 | 实现 SecretResolver | DONE | TASK-009 commit（紧随 542d570）：domain/error/ErrorCode（新增 SECRET_NOT_FOUND，INTERNAL）；application/secret（SecretResolver Port、SecretValue、SecretNotFoundException）；infrastructure/secret/EnvironmentSecretResolver；infrastructure 测试 EnvironmentSecretResolverTest | 2026-09-26 实测：`./mvnw -B clean verify` exit 0（infrastructure 108/108、web 10/10，门禁通过）；EnvironmentSecretResolverTest 13/13（env://KEY 解析且 SecretValue.toString 不含明文；缺失与空值 → SECRET_NOT_FOUND/NOT_FOUND，消息只含引用名；null、空串、误填明文、vault://、大写 scheme、小写键、env://、末尾换行、前导空格 9 例 → SECRET_NOT_FOUND/UNSUPPORTED_REF 且不回显引用；details 为空、无 cause；默认构造读取真实进程环境）；变异检查：不合法引用回显原文 → 9 例失败，空值视为存在 → 1 例失败，均已还原；boot jar 连 MySQL 8.4.11：health 200 UP、beans 含 environmentSecretResolver，容器已删除；独立 Review PASS（无 P0/P1；复核 `./mvnw -B clean verify` exit 0、infrastructure 108/108、web 10/10，构建日志无测试凭据值；boot jar health 200 UP、environmentSecretResolver 装配；`git diff --check` 通过）；MySQL 8.0.16、mvnw.cmd NOT VERIFIED |
| TASK-010 | ShortLink Demo 系统 Seed | DONE | TASK-010 commit（紧随 509ccc5）：infrastructure db/demo/R__shortlink_demo_seed.sql（可重复迁移，按唯一键 upsert）；boot application-demo.yml（demo profile 追加 classpath:db/demo）；infrastructure 测试 ShortLinkDemoSeedTest | 2026-09-26 实测（mysql:8.4.11）：`./mvnw -B clean verify` exit 0（infrastructure 114/114、web 10/10，门禁通过）；ShortLinkDemoSeedTest 6/6（系统与 5 资源类型；5 连接 providerType、仅 env:// 引用；7 条资源绑定与 06 §131 一致且全部经 SchemaCodecRegistry 解码为对应 V1 类型，含 8 个 09 §7 语义指标；8 条能力绑定与 06 §131 一致、均 enabled、均属 7 个 V0.1 能力；篡改名称/状态/enabled/选择器后重跑脚本行数不变且收敛；flyway_schema_history 记为 repeatable）；变异检查：绑定 join 键拼错 → 2 例失败，已还原；boot jar：默认 profile 仅应用 V001、managed_system 0 行，demo profile 应用 R__ 后 1/5/5/7/8 行，再次启动 up to date，health 均 200；容器已删除；独立 Review PASS（无 P0/P1；复核 `./mvnw -B clean verify` exit 0、Seed 6/6、infrastructure 114/114、web 10/10；正式 jar 默认及 production profile 仅 V001 且配置表为空，demo 1/5/5/7/8 行，二次启动行数与迁移记录不变，四次 health 200；`git diff --check` 通过）；MySQL 8.0.16、mvnw.cmd NOT VERIFIED |
| TASK-011 | Systems Read API | READY | — | NOT RUN |
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
| 未设置 OPSPILOT_DB_PASSWORD 时 Spring 保留占位符原文，启动要到数据库认证才失败（Review 已实测认证失败退出），而不是在配置绑定时报出缺失变量 | opspilot-boot application.yml | 不泄露也不会连上；若需要明确报错，可在数据源配置/SecretResolver（TASK-009）处显式校验 | 后续配置 Task（TASK-009 的 SecretResolver 只解析 credential_ref，不接管 Spring 数据源配置） |
| environment 未在规格中给出枚举，V001 只要求非空（VARCHAR(32)）；config/selector 的 schema 与 payload 均为 NOT NULL，无配置的 Provider 用空对象＋schema | V001 | TASK-008 SchemaCodecRegistry、TASK-010 Seed 按此落地；如需枚举环境再加迁移 | TASK-008/010 |
| capability_key 只做 `domain.action` 格式 CHECK，不在库里枚举 7 个能力；存在性由 Java CapabilityRegistry 判定（04 §11） | V001 | TASK-044 Registry 与 TASK-010 Seed 校验绑定键 | TASK-044 |
| 领域 environment 为 String；05 §15 示例值为 "DEMO"，库内未枚举 | domain/system/ManagedSystem | 与 TASK-005 已接受的自由文本一致；TASK-010 Seed 使用 DEMO | TASK-010 |
| TASK-006 的 system_key / resource_key 查找沿用列默认 ai_ci，大小写或重音变体会命中（TASK-007 的 connection_key、capability_key 查找已改为按字节比较） | ManagedSystemMapper.xml、ManagedResourceMapper.xml | 键由 Seed/配置给出且库内 CHECK 只允许小写，库内只存小写不能阻止查询输入的变体命中；接入外部输入（对外 API TASK-011、按资源键解析 AI 目标 TASK-037/040）前必须改为按字节精确匹配 | TASK-011/040 |
| 选择器与连接配置载荷在领域中是未解码 JSON 文本（配 SelectorSchema/ConfigSchema），尚无 Codec；两种 Schema 记录结构相同，Registry 可统一为 name/version 键 | domain/system | TASK-008 SchemaCodecRegistry 负责解码，禁止以 Map 传递 | TASK-008 |
| capabilityKey 在 CapabilityBinding 中为 String；存在性与格式外的校验由 Registry 负责 | domain/system/CapabilityBinding | TASK-044 如引入 CapabilityKey 值对象再替换 | TASK-044 |
| 绑定 schema 名定为 `<provider>.resource.binding` / 1（prometheus、loki、redis、mysql、docker） | domain/system/binding 各 SCHEMA_NAME | TASK-010 Seed 必须使用这些名称；TASK-007 测试中的 *.resource.selector 仅为测试数据 | TASK-010 |
| 注册表不校验绑定 schema 与连接 providerType 是否一致（如 Prometheus 选择器挂在 Loki 连接上） | JacksonSchemaCodecRegistry | Provider 解析时按 providerType 选定期望类型并校验，错配按未配置处理 | TASK-046 |
| PrometheusMetricBindingV1 只有 queryTemplate、unit；模板占位符与秒→毫秒换算尚未定义 | domain/system/binding | TASK-052 固定模板与换算；若需新增字段，在 Seed 数据依赖前修改 V1 或新增 V2，不做兼容猜测 | TASK-052 |
| 注册表只有 decode；尚无写入 JSON 的调用方 | application/schema | 首个写入载荷的 Task（如 CapabilityInvocation/Observation 持久化）再加 encode，并与 CanonicalJsonWriter（07 §58）划清职责 | TASK-047/048 |
| Jackson 3 默认 FAIL_ON_UNKNOWN_PROPERTIES=false（变异检查实测）；Web 请求 DTO 与 AI 协议默认会静默忽略未知字段 | Spring 自动配置的 JsonMapper | 05 §93 要求 AI 协议未知字段返回 AI_OUTPUT_INVALID；协议与公开 API 实现时显式开启严格反序列化 | TASK-030/034 与公开 API Task |
| SchemaPayloadException 的字段路径可能含载荷中的动态键（Review 实测 labels 下的键名、未知字段名会进入 message） | infrastructure/schema/JacksonSchemaCodecRegistry#describe | 当前响应与日志均不输出该 message；任何 Task 若要把它写入日志、Timeline 或返回给调用方，须先隐藏 Map 键与未知字段名，不能把路径视为已脱敏 | 首个输出 Schema 诊断的 Task |
| Redis 名称禁止 glob、MySQL databaseName 标识符校验只是配置约束 | domain/system/binding | Provider 仍须使用精确键命令（XINFO 等，不用 SCAN/KEYS 模式），MySQL 仍须用绑定参数与固定 SQL | TASK-054/055/056 |
| SECRET_NOT_FOUND 不在 05 §93 公开目录，按 08 TASK-009 加入 ErrorCode，类别 INTERNAL（同步 API 若直接遇到返回 500 与固定文案）；格式不合法的引用也归为 SECRET_NOT_FOUND，以 Reason.UNSUPPORTED_REF 区分 | domain/error/ErrorCode；application/secret | Provider 调用中遇到时按 05 §95 记 CapabilityInvocation FAILED，不让调查整体 500 | TASK-048/058 |
| env:// 可引用任意进程环境变量（未限定 OPSPILOT_ 前缀）；引用来自受信 Seed 配置 | infrastructure/secret/EnvironmentSecretResolver | 规格未要求前缀；如需收紧，在 Seed/部署 Task 中统一命名约束 | TASK-010/105 |
| Demo 约定值尚未经真实靶场校准：端点主机名（prometheus/loki/redis-proxy/mysql、docker.sock）、Prometheus/Loki 标签、容器名 shortlink-statistics-consumer、Stream shortlink:stats / stats-consumer-group、databaseName shortlink、只读账号名 | db/demo/R__shortlink_demo_seed.sql | 修改 R__ 后 Flyway 自动重跑并收敛；靶场与模板在 TASK-052/105/106 按真实导出数据校准 | TASK-052/105/106 |
| PromQL 模板为完整表达式（标签内联、rate 窗口 1m、秒→毫秒 *1000），与 labels 字段重复；未定义占位符 | R__ 中 prometheus.resource.binding | TASK-052 决定模板是否引用 labels、窗口与换算方式，并以真实数据验证 | TASK-052 |
| 连接 config_payload 使用 <provider>.connection.config / 1（MySQL/Redis 仅含 username，其余为空对象），尚无对应 Codec | R__ 中 data_source_connection | 各 Provider Task 为实际需要的配置注册 Codec 并按需调整字段 | TASK-052～057 |
| Seed 只 upsert 不删除；重跑不递增 lock_version（V0.1 无配置写入方） | R__shortlink_demo_seed.sql | 需要移除配置时显式写删除语句；出现配置写入方时再定义版本递增。文件未变化时 R__ 不重跑，普通重启不会修复库内人工改动 | 后续修改 Seed 的 Task |
| RecoveryPolicy Seed 未包含 | db/demo | 06 §131 最后一步，按 TASK-075/076 加入同一 demo 位置 | TASK-075/076 |
