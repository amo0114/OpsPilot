# 当前工作

更新时间：2026-09-30（本地，B22 REVIEW）
仓库/分支：/root/projects/OpsPilot-V0.1-IMPLEMENTATION，main；无 remote
当前批次/状态：B22 REVIEW（实现与针对性验证完成，B22-V1 exit 0；B22-R1 PASS，未提交）；B21 DONE（ef115c8）
成员 Task 及顺序：TASK-068（REVIEW）→ TASK-074（REVIEW）
固定 Base SHA：423baf741b5645be4f5f7940563890fbd8dc5623
批外前置核实：TASK-066 DONE（ef115c8，B21-R1 PASS）；TASK-021 DONE（b927804）
允许目录 / 明确不做 / 关键不变量：见 PROGRESS「B22」——V005/V006 迁移、Schema 测试与受影响夹具、docs/dev；不做批准成功路径（069）、审批并发（067）、Criteria/激活/Seed（075/076）、Worker/Docker/核对（070～073）、恢复求值/采样（077～083）、API/UI、新依赖；生产 Java 代码未修改
本批规格章节及 PROGRESS 记录：08 TASK-068/074；04 §18～§23、§45～§54、§78～§80、§82、§97～§98；01 §26、§28～§30；07 §92；PROGRESS「B22」
当前成员及位置：两成员实现完成，停在 REVIEW
已实现并针对性验证的成员：TASK-068（ActionExecutionSchemaTest 33/33）、TASK-074（RecoverySchemaTest 43/43＋受影响 4 个夹具）；变异 5 项均被测试发现
未完成 / 未执行验证：B22-R1 PASS，待提交；CCG verify-change/verify-quality NOT RUN（本机 /root/.claude/skills/ccg/ 不存在）；ai-runtime、真实 LLM、S1～S3 NOT RUN（本批不要求）；幂等键生成与使用、批准成功路径属 TASK-069；既有遗留见 PROGRESS 待处理问题；Redis < 7.2、MySQL 8.0.16、Windows mvnw.cmd NOT VERIFIED
未提交文件（含既有无关修改）：新增 V005__create_action_execution_table.sql、V006__create_recovery_tables.sql、ActionExecutionSchemaTest.java、RecoverySchemaTest.java（均未跟踪）；修改 InvestigationFactSchemaTest、MyBatisObservationRepositoryTest、InvestigationFixture、boot InvestigationApiContractTest、docs/dev/CURRENT.md、PROGRESS.md；无既有无关修改
共同验证及独立 Review 证据编号：B22-V1（backend clean verify exit 0，2026-09-30 06:16～06:29 UTC）；B22-R1 PASS（本轮 124 项针对性测试通过）
下一步具体动作：
1. B22-R1 已 PASS，范围为 Base 423baf7 至工作树全部变化（含 4 个未跟踪文件）；审查证据见 PROGRESS
2. Review PASS 且用户授权后提交代码（建议标题 `feat(recovery): action execution and recovery policy/verification schema (TASK-068, TASK-074)`），回填 SHA，B22 与成员一起标 DONE；不推送
3. 之后才开始 B23（TASK-075～076）

本地启动 Demo 配置：在 OPSPILOT_DB_* 环境变量基础上加 --spring.profiles.active=demo

后续 UI 约定（TASK-096/099 实施）：
- 底座 React＋TypeScript＋Vite＋Tailwind CSS＋shadcn/ui；Motion 仅在需要布局动画时引入；单一图标库；单一锁文件（npm）
- beautifului/beui/transitions 只借组织方式与过渡，不整套复制；Rare UI 第一版不用其源码；复制 MIT 代码保留声明
- 故障详情主体：当前影响→当前状态→当前判断→判断依据→处理建议→恢复情况；宽屏时间线置侧，技术信息折叠或进抽屉
- 浅色、中性灰文本、小面积语义色；不用巨型标题、满屏渐变、大片玻璃；动效 120～180ms、面板 180～240ms，尊重 reduced-motion
- 状态只认 Java 真实结果：不照搬 setTimeout 演示，不展示思维链，只展示 availableActions，409 刷新事实，无 AI 置信度，预算≠进度，SSE 断开≠FAILED，API 不支持的操作不展示
- 先打磨调查中/等待审批/恢复验证三态（共用组件），先做故障详情再复用
