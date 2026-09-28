# 当前工作

更新时间：2026-09-27（本地）
仓库/分支：/root/projects/OpsPilot-V0.1-IMPLEMENTATION，main；无 remote
当前批次/状态：B10 REVIEW（B10-R2 PASS，提交中）
成员 Task 及顺序：B10 = TASK-037 → TASK-038 → TASK-039（均 REVIEW）
固定 Base SHA：d87037f02713f47d65722a5117428f021d882b1e
批外前置核实：TASK-036 DONE（8eaa169，B09-R1 PASS）；开工时工作树干净
允许目录 / 明确不做 / 关键不变量：见 PROGRESS「B10」（上下文构造、AgentStep 生命周期、单步准入；ai-runtime 仅以响应头回传调用元数据；不做 Intent 分派、Stop 结果处置、收束、启动中断、Registry、Capability 准入）
本批规格章节及 PROGRESS 记录：PROGRESS「B10」
当前成员及位置：B10-R1 修复完成，等待 Reviewer 复核
已实现并针对性验证的成员：TASK-037（上下文隔离 3 例）、TASK-038（Step 记录与元数据）、TASK-039（准入规则 5 例＋锁内准入），真实 MySQL 与跨语言冒烟
未完成 / 未执行验证：B10-R1 独立 Review；TASK-040～043 编排/收束/恢复；context_digest；真实 LLM 接入归属（TASK-058 前）；ccg 质量关卡 NOT RUN；其余见 PROGRESS 待处理问题
未提交文件（含既有无关修改）：无既有无关修改；修改 8 个 ai-runtime 文件、9 个 backend 文件、contracts README 与 docs/dev 两份，新增 28 个未跟踪文件（`git ls-files --others --exclude-standard`，清单见 PROGRESS「B10」本批修改文件）
共同验证及独立 Review 证据编号：B10-V1（修复前）、B10-V2（修复后：backend verify exit 0，infrastructure 414；ai-runtime 145 passed）；B10-R1（1 个 P1，已修复）、B10-R2 PASS
下一步具体动作：
1. Reviewer 复核 StepAdmissionService 持锁后取时与回归 deadlineIsJudgedAfterTheLocksAreAcquired，并重跑必要门禁
2. Review PASS 且获用户授权后提交（建议 `feat(investigation): step context, agent step lifecycle and atomic step admission (TASK-037–039)`），回填 SHA 后批次与成员标 DONE；不得提前开始 B11
本地启动 Demo 配置：在 OPSPILOT_DB_* 环境变量基础上加 --spring.profiles.active=demo

后续 UI 约定（TASK-096/099 实施）：
- 底座 React＋TypeScript＋Vite＋Tailwind CSS＋shadcn/ui；Motion 仅在需要布局动画时引入；单一图标库；单一锁文件（npm）
- beautifului/beui/transitions 只借组织方式与过渡，不整套复制；Rare UI 第一版不用其源码；复制 MIT 代码保留声明
- 故障详情主体：当前影响→当前状态→当前判断→判断依据→处理建议→恢复情况；宽屏时间线置侧，技术信息折叠或进抽屉
- 浅色、中性灰文本、小面积语义色；不用巨型标题、满屏渐变、大片玻璃；动效 120～180ms、面板 180～240ms，尊重 reduced-motion
- 状态只认 Java 真实结果：不照搬 setTimeout 演示，不展示思维链，只展示 availableActions，409 刷新事实，无 AI 置信度，预算≠进度，SSE 断开≠FAILED，API 不支持的操作不展示
- 先打磨调查中/等待审批/恢复验证三态（共用组件），先做故障详情再复用
