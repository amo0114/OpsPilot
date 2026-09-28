# 当前工作

更新时间：2026-09-28（本地）
仓库/分支：/root/projects/OpsPilot-V0.1-IMPLEMENTATION，main；无 remote
当前批次/状态：TASK-040/043 结果保存失败修复 Review-3 PASS，提交中；B13 未开始
成员 Task 及顺序：B12 = TASK-042 → TASK-043（均 DONE）；B13 前独立修复 TASK-039、TASK-016、TASK-026 已 DONE，剩 TASK-040/043 结果保存失败修复，之后 B13 = TASK-044 → 046
固定 Base SHA：a4db1b5c22ec51d62ea38c45e9693b4a5eb0d6a7（TASK-040/043 修复开工时 HEAD）
批外前置核实：B13 开工时按 08 核对其成员的批外前置
允许目录 / 明确不做 / 关键不变量：TASK-040/043 修复按 TASK-016 修复 Review 约定（原结果事务退出后以独立短事务处理指定 Step：只终结仍为 RUNNING 的记录、保护已提交终态、不计 AI 失败、不重放 Intent 或网络调用，写清失败后的恢复路径）；B13 开工时按 BATCH-PLAN 与 08 固定
本批规格章节及 PROGRESS 记录：PROGRESS「TASK-040/043 修复」
当前成员及位置：TASK-040/043 修复 Review-2 P2（取消后孤立 Step 不可达）已修复，待复审
已实现并针对性验证的成员：B01～B12 全部
未完成 / 未执行验证：TASK-040/043 修复复审；该修复真实进程冒烟 NOT RUN（原因见 PROGRESS）；Fake runtime 偶发 AI_RUNTIME_UNAVAILABLE 保持观察；其余见 PROGRESS 待处理问题；MySQL 8.0.16、Windows mvnw.cmd NOT VERIFIED
未提交文件（含既有无关修改）：InvestigationOrchestrator.java、AgentStepRecorder.java、AgentStepRepository.java、AgentStepMapper.java、MyBatisAgentStepRepository.java、AgentStepMapper.xml、InvestigationWorkMapper.xml、InvestigationOrchestrationIntegrationTest.java、docs/dev/CURRENT.md、docs/dev/PROGRESS.md；无既有无关修改
共同验证及独立 Review 证据编号：见 PROGRESS「TASK-040/043 修复」（Review-1、Review-2 各 1 个 P2 已修复；修复后 verify exit 0＋回归＋变异确认）；Review-3 NOT RUN
下一步具体动作：
1. 独立 Reviewer 复审 Base a4db1b5 到当前工作树（重点 InvestigationWorkMapper 查询扩展与取消后孤立 Step 回归）
2. PASS 后按用户授权提交（建议 `fix(investigation): close steps whose outcome could not be recorded (TASK-040/043)`）并回填记录
3. 不推送；未获指示不开始 B13
本地启动 Demo 配置：在 OPSPILOT_DB_* 环境变量基础上加 --spring.profiles.active=demo

后续 UI 约定（TASK-096/099 实施）：
- 底座 React＋TypeScript＋Vite＋Tailwind CSS＋shadcn/ui；Motion 仅在需要布局动画时引入；单一图标库；单一锁文件（npm）
- beautifului/beui/transitions 只借组织方式与过渡，不整套复制；Rare UI 第一版不用其源码；复制 MIT 代码保留声明
- 故障详情主体：当前影响→当前状态→当前判断→判断依据→处理建议→恢复情况；宽屏时间线置侧，技术信息折叠或进抽屉
- 浅色、中性灰文本、小面积语义色；不用巨型标题、满屏渐变、大片玻璃；动效 120～180ms、面板 180～240ms，尊重 reduced-motion
- 状态只认 Java 真实结果：不照搬 setTimeout 演示，不展示思维链，只展示 availableActions，409 刷新事实，无 AI 置信度，预算≠进度，SSE 断开≠FAILED，API 不支持的操作不展示
- 先打磨调查中/等待审批/恢复验证三态（共用组件），先做故障详情再复用
