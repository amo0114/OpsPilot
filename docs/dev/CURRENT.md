# 当前工作

更新时间：2026-09-27（本地）
仓库/分支：/root/projects/OpsPilot-V0.1-IMPLEMENTATION，main；无 remote
当前批次/状态：B05 REVIEW（B05-R1 PASS，提交中）
成员 Task 及顺序：B05 = TASK-023 → TASK-024（均 REVIEW）
固定 Base SHA：c3bdd7d74d6578c460ad97b9ef6697b4929d1583
批外前置核实：TASK-022 DONE（b927804，B04-R1 PASS）；开工时工作树干净
允许目录 / 明确不做 / 关键不变量：见 PROGRESS「B05」范围与关键不变量（沿用 V003，不新增迁移；不做 Diagnosis、查询 API、AI 协议/Intent 分派、run/Stop 准入）
本批规格章节及 PROGRESS 记录：PROGRESS「B05」
当前成员及位置：整批送审，无进行中编码
已实现并针对性验证的成员：TASK-023（HypothesisRulesTest 3/3、HypothesisIntegrationTest 5/5）、TASK-024（EvidenceIntegrationTest 6/6），均真实 MySQL 8.4.11
未完成 / 未执行验证：B05-R1 独立 Review；并发重复关系 NOT RUN；ccg 质量关卡 NOT RUN（本机未安装）；recovery_verification 外键（TASK-074）；MySQL 8.0.16、Windows mvnw.cmd NOT VERIFIED
未提交文件（含既有无关修改）：无既有无关修改；本批修改 6 个代码文件＋docs/dev/CURRENT.md、PROGRESS.md，新增文件（未跟踪）见 PROGRESS「B05」本批修改文件（`git ls-files --others --exclude-standard` 共 33 个）
共同验证及独立 Review 证据编号：B05-V1（`./mvnw -B clean verify` exit 0）；B05-R1 PASS
下一步具体动作：
1. 独立 Reviewer 审查 c3bdd7d → 当前工作树（含未跟踪文件），重点：Hypothesis 状态矩阵解释、锁序与同事务回滚、Evidence 归属/重复处理、拒绝错误码约定
2. Review PASS 且获用户授权后提交（建议 `feat(investigation): hypothesis and immutable evidence links (TASK-023–024)`），回填 SHA 后批次与成员标 DONE；不得提前开始 B06
本地启动 Demo 配置：在 OPSPILOT_DB_* 环境变量基础上加 --spring.profiles.active=demo

后续 UI 约定（TASK-096/099 实施）：
- 底座 React＋TypeScript＋Vite＋Tailwind CSS＋shadcn/ui；Motion 仅在需要布局动画时引入；单一图标库；单一锁文件（npm）
- beautifului/beui/transitions 只借组织方式与过渡，不整套复制；Rare UI 第一版不用其源码；复制 MIT 代码保留声明
- 故障详情主体：当前影响→当前状态→当前判断→判断依据→处理建议→恢复情况；宽屏时间线置侧，技术信息折叠或进抽屉
- 浅色、中性灰文本、小面积语义色；不用巨型标题、满屏渐变、大片玻璃；动效 120～180ms、面板 180～240ms，尊重 reduced-motion
- 状态只认 Java 真实结果：不照搬 setTimeout 演示，不展示思维链，只展示 availableActions，409 刷新事实，无 AI 置信度，预算≠进度，SSE 断开≠FAILED，API 不支持的操作不展示
- 先打磨调查中/等待审批/恢复验证三态（共用组件），先做故障详情再复用
