# 当前工作

更新时间：2026-09-27（本地）
仓库/分支：/root/projects/OpsPilot-V0.1-IMPLEMENTATION，main；无 remote
当前批次/状态：B09 REVIEW（B09-R1 PASS，提交中）
成员 Task 及顺序：B09 = TASK-035 → TASK-036（均 REVIEW）
固定 Base SHA：d922a3ffff08bdd0ce1a7ea083fd7de0c273510a
批外前置核实：TASK-034 DONE（5e81bb8，B08-R2 PASS）；开工时工作树干净
允许目录 / 明确不做 / 关键不变量：见 PROGRESS「B09」（application/dispatch、infrastructure/dispatch、调查可派发查询；不做 MQ/Outbox/Lease、调查循环、启动中断标记与收束、Execution/Verification 真实 Worker）
本批规格章节及 PROGRESS 记录：PROGRESS「B09」
当前成员及位置：整批送审，无进行中编码
已实现并针对性验证的成员：TASK-035（InProcessWorkDispatcherTest 4、DispatchRecoveryIntegrationTest 3、DispatchRecoverySchedulerIntegrationTest 1）、TASK-036（SingleFlightRegistryTest 5）
未完成 / 未执行验证：B09-R1 独立 Review；调查循环与启动中断标记（TASK-037～043）；Execution/Verification 数据来源与 Worker（TASK-071/073/079/083）；真实 LLM 接入归属（TASK-058 前）；AgentStep 元数据（TASK-038）；ccg 质量关卡 NOT RUN；InvestigationRunIntegrationTest "Connection is closed" 观察项；Invocation 技术 API 后端归属（TASK-103 前）；Plan supersede 占位（TASK-062/067）；recovery_verification 外键（TASK-074）；MySQL 8.0.16、Windows mvnw.cmd NOT VERIFIED
未提交文件（含既有无关修改）：无既有无关修改；修改 WorkDispatcher 与 docs/dev 两份，删除 DeferredWorkDispatcher，新增 26 个未跟踪文件（`git ls-files --others --exclude-standard`，清单见 PROGRESS「B09」本批修改文件）
共同验证及独立 Review 证据编号：B09-V1（backend verify exit 0）；B09-R1 PASS
下一步具体动作：
1. 独立 Reviewer 审查 d922a3f → 当前工作树（含未跟踪文件），重点：拥有者 token 与延后唤醒的原子性、拒绝后可再唤醒、补派发不刷新 run 且不碰运行中工作、占位 Worker 与测试配置
2. Review PASS 且获用户授权后提交（建议 `feat(dispatch): in-process work dispatcher, single-flight registry and recovery rescan (TASK-035–036)`），回填 SHA 后批次与成员标 DONE；不得提前开始 B10
本地启动 Demo 配置：在 OPSPILOT_DB_* 环境变量基础上加 --spring.profiles.active=demo

后续 UI 约定（TASK-096/099 实施）：
- 底座 React＋TypeScript＋Vite＋Tailwind CSS＋shadcn/ui；Motion 仅在需要布局动画时引入；单一图标库；单一锁文件（npm）
- beautifului/beui/transitions 只借组织方式与过渡，不整套复制；Rare UI 第一版不用其源码；复制 MIT 代码保留声明
- 故障详情主体：当前影响→当前状态→当前判断→判断依据→处理建议→恢复情况；宽屏时间线置侧，技术信息折叠或进抽屉
- 浅色、中性灰文本、小面积语义色；不用巨型标题、满屏渐变、大片玻璃；动效 120～180ms、面板 180～240ms，尊重 reduced-motion
- 状态只认 Java 真实结果：不照搬 setTimeout 演示，不展示思维链，只展示 availableActions，409 刷新事实，无 AI 置信度，预算≠进度，SSE 断开≠FAILED，API 不支持的操作不展示
- 先打磨调查中/等待审批/恢复验证三态（共用组件），先做故障详情再复用
