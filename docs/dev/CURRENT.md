# 当前工作

更新时间：2026-09-26（本地）
仓库/分支：/root/projects/OpsPilot-V0.1-IMPLEMENTATION，main；HEAD d68c953（TASK-002）；无 remote
当前任务：TASK-003 DONE（独立 Review PASS，已提交）；TASK-004 READY，未开始
任务内位置：等待用户指示开始 TASK-004
本轮允许修改：backend/pom.xml、backend/opspilot-web/pom.xml、AGENTS.md、docs/dev/
本轮明确不做：MyBatis/Flyway/MySQL 进入模块（TASK-005/007，按用户确认方案）；ErrorCode/配置（TASK-004）；业务代码与表

已完成：
- 父 POM Enforcer 3.6.3（validate）：JDK [21,22)；maven.compiler.release=21（java.version 会被 JVM 系统属性遮蔽，故检查实际编译目标）；Maven [3.9,)；dependencyConvergence；banDynamicVersions（忽略本工程 SNAPSHOT）；bannedDependencies（07 §6：MyBatis-Plus、Hibernate ORM/JPA、Lombok、Spring Cloud/Nacos、Kafka/RabbitMQ、Quartz、Batch、StateMachine、Modulith、Spring Data Elasticsearch、MCP SDK；保留 hibernate-validator）
- Spotless 3.10.2＋palantir-java-format 2.99.0，verify 阶段 check
- dependencyManagement：mybatis 3.5.19、mybatis-spring 4.1.0、mybatis-spring-boot-starter 4.1.0、springdoc-openapi-starter-webmvc-api 3.1.1；Flyway 12.4.0、mysql-connector-j 9.7.0 沿用 Boot 4.1.1 BOM
- opspilot-web 加 springdoc-openapi-starter-webmvc-api（无 UI）
- AGENTS.md 硬边界补 5 行：Specs 优先、不改 8 状态/无通用 updateStatus、不加核心表、不为局部问题加依赖、完成即验证且不跳过门禁

已执行验证（2026-09-26，WSL，JDK 21.0.10，mvnw 3.9.16，基线 d68c953＋未提交工作树）：见 PROGRESS TASK-003 行（clean verify exit 0；JDK 11、java.version=17、未格式化文件三项负向均 BUILD FAILURE；探针验证 banned/dynamic 规则与数据访问依赖解析收敛；health 200 UP；/v3/api-docs 200）
- 探针 `mvnw install` 把本工程 0.1.0-SNAPSHOT 装入本机 ~/.m2（仅本地缓存）；探针目录已删除

未执行验证：Windows 原生 mvnw.cmd：NOT RUN；真实 MySQL 连接/Flyway 迁移：NOT RUN（TASK-005 范围）

未提交修改：无（TASK-003 已提交；按用户要求 .gitignore 的 `.claude` 规则并入同一提交）
当前阻塞：无

下一步具体动作：
1. TASK-004：ErrorCode、DomainException/ApplicationException、API Error Mapping、@ConfigurationProperties、RequestId/CorrelationId

本任务需要读取的规格章节（TASK-004）：08 TASK-004；07 §88～§90、§98～§99、§103～§105；05 错误响应章节

后续 UI 约定（TASK-096/099 实施）：
- 底座 React＋TypeScript＋Vite＋Tailwind CSS＋shadcn/ui；Motion 仅在需要布局动画时引入；单一图标库；单一锁文件（npm）
- beautifului/beui/transitions 只借组织方式与过渡，不整套复制；Rare UI 第一版不用其源码；复制 MIT 代码保留声明
- 故障详情主体：当前影响→当前状态→当前判断→判断依据→处理建议→恢复情况；宽屏时间线置侧，技术信息折叠或进抽屉
- 浅色、中性灰文本、小面积语义色；不用巨型标题、满屏渐变、大片玻璃；动效 120～180ms、面板 180～240ms，尊重 reduced-motion
- 状态只认 Java 真实结果：不照搬 setTimeout 演示，不展示思维链，只展示 availableActions，409 刷新事实，无 AI 置信度，预算≠进度，SSE 断开≠FAILED，API 不支持的操作不展示
- 先打磨调查中/等待审批/恢复验证三态（共用组件），先做故障详情再复用
