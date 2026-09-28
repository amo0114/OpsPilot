# 当前工作

更新时间：2026-09-28（本地）
仓库/分支：/root/projects/OpsPilot-V0.1-IMPLEMENTATION，main；无 remote
当前批次/状态：B11 REVIEW（B11-R1 PASS，提交中）
成员 Task 及顺序：B11 = TASK-040 → TASK-041
固定 Base SHA：57371cd5e3d217c5a60b3a4f112307b361bc9617
批外前置核实：TASK-039 DONE（c3ec168，B10-R2 PASS）；开工时工作树干净
允许目录 / 明确不做 / 关键不变量：见 PROGRESS「B11」（编排循环、Intent 分派与引用范围、run/Stop 处置、Fake Capability Gate；不做收束、启动恢复、Registry/Invocation/Provider）
本批规格章节及 PROGRESS 记录：PROGRESS「B11」
当前成员及位置：TASK-040、TASK-041 均 REVIEW（B11-R1 PASS），提交中
已实现并针对性验证的成员：TASK-040、TASK-041（B11-V1 verify exit 0；真实进程端到端到 DIAGNOSED）
未完成 / 未执行验证：NOT RUN 项见 PROGRESS「B11」（收束 TASK-042、启动恢复 TASK-043、真实 Capability/LLM 等）
未提交文件（含既有无关修改）：见 PROGRESS「B11」本批修改文件（含 PlaceholderInvestigationWorker → UnwiredInvestigationWorker 已暂存的更名）、docs/dev/CURRENT.md、docs/dev/PROGRESS.md；无既有无关修改
共同验证及独立 Review 证据编号：B11-V1、B11-R1 PASS
下一步具体动作：
1. 提交代码（`feat(investigation): orchestration loop, intent dispatch and stop race handling (TASK-040–041)`），回填 SHA 后批次与成员标 DONE
2. 不推送；未获指示不开始 B12
本地启动 Demo 配置：在 OPSPILOT_DB_* 环境变量基础上加 --spring.profiles.active=demo

后续 UI 约定（TASK-096/099 实施）：
- 底座 React＋TypeScript＋Vite＋Tailwind CSS＋shadcn/ui；Motion 仅在需要布局动画时引入；单一图标库；单一锁文件（npm）
- beautifului/beui/transitions 只借组织方式与过渡，不整套复制；Rare UI 第一版不用其源码；复制 MIT 代码保留声明
- 故障详情主体：当前影响→当前状态→当前判断→判断依据→处理建议→恢复情况；宽屏时间线置侧，技术信息折叠或进抽屉
- 浅色、中性灰文本、小面积语义色；不用巨型标题、满屏渐变、大片玻璃；动效 120～180ms、面板 180～240ms，尊重 reduced-motion
- 状态只认 Java 真实结果：不照搬 setTimeout 演示，不展示思维链，只展示 availableActions，409 刷新事实，无 AI 置信度，预算≠进度，SSE 断开≠FAILED，API 不支持的操作不展示
- 先打磨调查中/等待审批/恢复验证三态（共用组件），先做故障详情再复用
