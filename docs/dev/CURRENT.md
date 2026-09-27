# 当前工作

更新时间：2026-09-27（本地）
仓库/分支：/root/projects/OpsPilot-V0.1-IMPLEMENTATION，main；无 remote
当前批次/状态：B06 REVIEW（B06-R1 PASS，提交中）
成员 Task 及顺序：B06 = TASK-025 → TASK-026 → TASK-027（均 REVIEW）
固定 Base SHA：c1042ff3fd33b4783c9dcfb348092e54b74e9913
批外前置核实：TASK-024 DONE（01c3395，B05-R1 PASS）；开工时工作树干净
允许目录 / 明确不做 / 关键不变量：见 PROGRESS「B06」（沿用 V003，不新增迁移；不做 AI 协议/Intent 分派/run 准入/确定性收束/Plan 表/Invocation 技术 API）
本批规格章节及 PROGRESS 记录：PROGRESS「B06」
当前成员及位置：整批送审，无进行中编码
已实现并针对性验证的成员：TASK-025（DiagnosisRulesTest 4/4）、TASK-026（DiagnosisIntegrationTest 5/5 真实 MySQL）、TASK-027（InvestigationApiContractTest 2/2 真实 HTTP＋MySQL）
未完成 / 未执行验证：B06-R1 独立 Review；Invocation 技术 API 归属待确认；Plan supersede 占位（TASK-062/067）；ccg 质量关卡 NOT RUN（本机未安装）；recovery_verification 外键（TASK-074）；MySQL 8.0.16、Windows mvnw.cmd NOT VERIFIED
未提交文件（含既有无关修改）：无既有无关修改；本批修改 8 个代码/测试文件＋docs/dev/CURRENT.md、PROGRESS.md，新增 37 个未跟踪文件（`git ls-files --others --exclude-standard`，清单见 PROGRESS「B06」本批修改文件）
共同验证及独立 Review 证据编号：B06-V1（`./mvnw -B clean verify` exit 0）；B06-R1 PASS
下一步具体动作：
1. 独立 Reviewer 审查 c1042ff → 当前工作树（含未跟踪文件），重点：支持证据须在本 Diagnosis 引用集合且关联主假设、旧 run 拒绝、同事务回滚、版本分配、查询只含调查事实与冻结引用、自定错误码/时长语义
2. Review PASS 且获用户授权后提交（建议 `feat(investigation): versioned diagnosis and investigation queries (TASK-025–027)`），回填 SHA 后批次与成员标 DONE；不得提前开始 B07
本地启动 Demo 配置：在 OPSPILOT_DB_* 环境变量基础上加 --spring.profiles.active=demo

后续 UI 约定（TASK-096/099 实施）：
- 底座 React＋TypeScript＋Vite＋Tailwind CSS＋shadcn/ui；Motion 仅在需要布局动画时引入；单一图标库；单一锁文件（npm）
- beautifului/beui/transitions 只借组织方式与过渡，不整套复制；Rare UI 第一版不用其源码；复制 MIT 代码保留声明
- 故障详情主体：当前影响→当前状态→当前判断→判断依据→处理建议→恢复情况；宽屏时间线置侧，技术信息折叠或进抽屉
- 浅色、中性灰文本、小面积语义色；不用巨型标题、满屏渐变、大片玻璃；动效 120～180ms、面板 180～240ms，尊重 reduced-motion
- 状态只认 Java 真实结果：不照搬 setTimeout 演示，不展示思维链，只展示 availableActions，409 刷新事实，无 AI 置信度，预算≠进度，SSE 断开≠FAILED，API 不支持的操作不展示
- 先打磨调查中/等待审批/恢复验证三态（共用组件），先做故障详情再复用
