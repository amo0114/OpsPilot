# 当前工作

更新时间：2026-09-28（本地）
仓库/分支：/root/projects/OpsPilot-V0.1-IMPLEMENTATION，main；无 remote
当前批次/状态：TASK-039 准入死锁修复 DONE（代码 30aea1f）；下一步 TASK-016 首次 Start 死锁独立修复；B13 未开始
成员 Task 及顺序：B12 = TASK-042 → TASK-043（均 DONE）；下一步为 TASK-039 死锁独立修复，之后 B13 = TASK-044 → 046
固定 Base SHA：TASK-016 修复开工时读取当时 HEAD
批外前置核实：B13 开工时按 08 核对其成员的批外前置
允许目录 / 明确不做 / 关键不变量：TASK-039 修复按 B12-R2 约定（只改 Step 编号查询/插入＋跨调查并发回归）；B13 开工时按 BATCH-PLAN 与 08 固定
本批规格章节及 PROGRESS 记录：PROGRESS「TASK-039 修复」
当前成员及位置：无进行中修复
已实现并针对性验证的成员：B01～B12 全部
未完成 / 未执行验证：首次 Start 并发死锁（TASK-016 独立修复，下一步）；其余见 PROGRESS 待处理问题；MySQL 8.0.16、Windows mvnw.cmd NOT VERIFIED
未提交文件（含既有无关修改）：无（本交接卡与 PROGRESS 回填随 docs(progress) 提交）
共同验证及独立 Review 证据编号：见 PROGRESS「TASK-039 修复」（Review PASS）
下一步具体动作：
1. TASK-016 首次 Start 死锁独立修复：首次 Start 在 Incident 行锁保护下不对不存在的 Investigation 加锁读取，保留 Continue/Stop/Step 既有行的锁，不全局修改 findByIncidentIdForUpdate，加跨 Incident 并发首次 Start 回归，不加通用重试；完成后送审
2. 不推送；未获指示不开始 B13
本地启动 Demo 配置：在 OPSPILOT_DB_* 环境变量基础上加 --spring.profiles.active=demo

后续 UI 约定（TASK-096/099 实施）：
- 底座 React＋TypeScript＋Vite＋Tailwind CSS＋shadcn/ui；Motion 仅在需要布局动画时引入；单一图标库；单一锁文件（npm）
- beautifului/beui/transitions 只借组织方式与过渡，不整套复制；Rare UI 第一版不用其源码；复制 MIT 代码保留声明
- 故障详情主体：当前影响→当前状态→当前判断→判断依据→处理建议→恢复情况；宽屏时间线置侧，技术信息折叠或进抽屉
- 浅色、中性灰文本、小面积语义色；不用巨型标题、满屏渐变、大片玻璃；动效 120～180ms、面板 180～240ms，尊重 reduced-motion
- 状态只认 Java 真实结果：不照搬 setTimeout 演示，不展示思维链，只展示 availableActions，409 刷新事实，无 AI 置信度，预算≠进度，SSE 断开≠FAILED，API 不支持的操作不展示
- 先打磨调查中/等待审批/恢复验证三态（共用组件），先做故障详情再复用
