# 当前工作

更新时间：2026-09-26（本地）
仓库/分支：/root/projects/OpsPilot-V0.1-IMPLEMENTATION，main；HEAD 384589a（TASK-004）；无 remote
当前任务：TASK-005 DONE（独立 Review 复核 PASS，已提交）；TASK-006 READY，未开始
任务内位置：等待用户指示开始 TASK-006
本轮允许修改：infrastructure db/migration 与持久化测试、infrastructure/boot POM、boot application.yml、docs/dev/
本轮明确不做：领域模型/Repository/Mapper/MyBatis（TASK-006/007）；Codec（TASK-008）；SecretResolver（TASK-009）；Seed（TASK-010）；API（TASK-011）

已完成：
- V001__create_system_integration_tables.sql（opspilot-infrastructure/src/main/resources/db/migration）：managed_system、managed_resource、data_source_connection、resource_binding、capability_binding
  - BIGINT UNSIGNED 自增主键；DATETIME(3) 无库默认值（应用写 UTC）；配置表 lock_version BIGINT UNSIGNED DEFAULT 0；InnoDB utf8mb4_0900_ai_ci
  - UNIQUE：system_key；(system,resource_key)；connection_key；(resource,connection)；(resource,capability_key)
  - FK 全部 ON DELETE/UPDATE RESTRICT；索引 (managed_system_id,status)、resource_binding(data_source_connection_id)
  - CHECK：三类状态 ACTIVE/DISABLED/ARCHIVED；6 种 resource_type；5 种 provider_type（06：PROMETHEUS/LOKI/REDIS/MYSQL/DOCKER），枚举均 `CAST(col AS BINARY) IN` 逐字节比较（拒绝大小写/重音/末尾空格）；key 小写格式（REGEXP_LIKE 'c' 区分大小写、`\z` 锚定整串）；credential_ref 仅 env://；schema_version>=1；JSON 必须为对象；非空名称等
- boot：spring-boot-starter-jdbc、spring-boot-starter-flyway、flyway-mysql、mysql-connector-j(runtime)；datasource url/username 可由环境覆盖，password 只来自 OPSPILOT_DB_PASSWORD
- infrastructure 测试依赖：flyway-mysql、mysql-connector-j、testcontainers-mysql/junit-jupiter、junit-jupiter、assertj（均 test）

本地启动 boot（示例）：先起 MySQL 8.4，再 `OPSPILOT_DB_URL='jdbc:mysql://127.0.0.1:13306/opspilot?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true' OPSPILOT_DB_PASSWORD=… java -jar opspilot-boot/target/opspilot-boot-0.1.0-SNAPSHOT.jar`

已执行验证：见 PROGRESS TASK-005 行（clean verify exit 0；29 个真实 MySQL 测试；变异检查；boot 两次启动迁移/幂等；约束计数；直连 SQL 反例）
未执行验证：Windows mvnw.cmd NOT RUN；MySQL 8.0.16 最低版本上的迁移 NOT RUN（只测 8.4.11）

未提交修改：无（TASK-005 已提交）
当前阻塞：无

下一步具体动作：
1. TASK-006 ManagedSystem/ManagedResource 领域模型与 Repository Port（MyBatis 首次进入 infrastructure 时加入 starter）

本任务需要读取的规格章节（TASK-006）：08 TASK-006；03 §6～§15；04 §7～§8；07 §13～§23

后续 UI 约定（TASK-096/099 实施）：
- 底座 React＋TypeScript＋Vite＋Tailwind CSS＋shadcn/ui；Motion 仅在需要布局动画时引入；单一图标库；单一锁文件（npm）
- beautifului/beui/transitions 只借组织方式与过渡，不整套复制；Rare UI 第一版不用其源码；复制 MIT 代码保留声明
- 故障详情主体：当前影响→当前状态→当前判断→判断依据→处理建议→恢复情况；宽屏时间线置侧，技术信息折叠或进抽屉
- 浅色、中性灰文本、小面积语义色；不用巨型标题、满屏渐变、大片玻璃；动效 120～180ms、面板 180～240ms，尊重 reduced-motion
- 状态只认 Java 真实结果：不照搬 setTimeout 演示，不展示思维链，只展示 availableActions，409 刷新事实，无 AI 置信度，预算≠进度，SSE 断开≠FAILED，API 不支持的操作不展示
- 先打磨调查中/等待审批/恢复验证三态（共用组件），先做故障详情再复用
