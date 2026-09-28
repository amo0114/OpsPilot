# 当前工作

更新时间：2026-09-28（本地）
仓库/分支：/root/projects/OpsPilot-V0.1-IMPLEMENTATION，main；无 remote
当前批次/状态：TASK-026 Diagnosis 版本号死锁修复 Review PASS，提交中；B13 未开始
成员 Task 及顺序：B12 = TASK-042 → TASK-043（均 DONE）；B13 前依次独立修复 TASK-026 → TASK-040/043（TASK-039、TASK-016 修复已 DONE），之后 B13 = TASK-044 → 046
固定 Base SHA：2e202b920480d84836fd6ee48e83b88eb2111da5（TASK-026 修复开工时 HEAD）
批外前置核实：B13 开工时按 08 核对其成员的批外前置
允许目录 / 明确不做 / 关键不变量：TASK-026 修复按 TASK-016 修复 Review 约定（保留业务锁、版本唯一约束与原子状态迁移，消除 INSERT … SELECT MAX 的跨调查锁冲突，按实际调用链验证版本分配安全）；B13 开工时按 BATCH-PLAN 与 08 固定
本批规格章节及 PROGRESS 记录：PROGRESS「TASK-026 修复」
当前成员及位置：TASK-026 修复实现与验证完成，待独立 Review
已实现并针对性验证的成员：B01～B12 全部
未完成 / 未执行验证：TASK-026 修复 Review；之后 TASK-040/043 结果保存失败修复；Fake runtime 偶发 AI_RUNTIME_UNAVAILABLE 保持观察；其余见 PROGRESS 待处理问题；MySQL 8.0.16、Windows mvnw.cmd NOT VERIFIED
未提交文件（含既有无关修改）：DiagnosisRepository.java、AgentStepRecorder.java、DiagnosisMapper.java、DiagnosisInsert.java、MyBatisDiagnosisRepository.java、DiagnosisMapper.xml、DiagnosisIntegrationTest.java、InvestigationOrchestrationIntegrationTest.java、docs/dev/CURRENT.md、docs/dev/PROGRESS.md；无既有无关修改
共同验证及独立 Review 证据编号：见 PROGRESS「TASK-026 修复」（verify exit 0＋两项回归的失败/变异确认＋真实进程并发创建与跨 run）；Review NOT RUN
下一步具体动作：
1. 独立 Reviewer 审查 Base 2e202b9 到当前工作树（版本号分配、结果事务快照前提与两项回归）
2. PASS 后按用户授权提交（建议 `fix(diagnosis): allocate diagnosis versions without gap locks (TASK-026)`）并回填记录
3. 之后 TASK-040/043 结果保存失败修复并送审；不推送；不开始 B13
本地启动 Demo 配置：在 OPSPILOT_DB_* 环境变量基础上加 --spring.profiles.active=demo

后续 UI 约定（TASK-096/099 实施）：
- 底座 React＋TypeScript＋Vite＋Tailwind CSS＋shadcn/ui；Motion 仅在需要布局动画时引入；单一图标库；单一锁文件（npm）
- beautifului/beui/transitions 只借组织方式与过渡，不整套复制；Rare UI 第一版不用其源码；复制 MIT 代码保留声明
- 故障详情主体：当前影响→当前状态→当前判断→判断依据→处理建议→恢复情况；宽屏时间线置侧，技术信息折叠或进抽屉
- 浅色、中性灰文本、小面积语义色；不用巨型标题、满屏渐变、大片玻璃；动效 120～180ms、面板 180～240ms，尊重 reduced-motion
- 状态只认 Java 真实结果：不照搬 setTimeout 演示，不展示思维链，只展示 availableActions，409 刷新事实，无 AI 置信度，预算≠进度，SSE 断开≠FAILED，API 不支持的操作不展示
- 先打磨调查中/等待审批/恢复验证三态（共用组件），先做故障详情再复用
