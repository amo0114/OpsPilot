# 当前工作

更新时间：2026-09-28（本地）
仓库/分支：/root/projects/OpsPilot-V0.1-IMPLEMENTATION，main；无 remote
当前批次/状态：B12 REVIEW（B12-R2 PASS，提交中）
成员 Task 及顺序：B12 = TASK-042 → TASK-043
固定 Base SHA：c204b1df60399f5e699bd094a1ff7f2f27fb4b78
批外前置核实：TASK-041 DONE（9f60002，B11-R1 PASS）；开工时工作树干净
允许目录 / 明确不做 / 关键不变量：见 PROGRESS「B12」（确定性收束、启动中断记录与恢复派发；不做 Invocation 准入、COMPLETE 全链路验收、Execution/Verification 恢复）
本批规格章节及 PROGRESS 记录：PROGRESS「B12」
当前成员及位置：TASK-042、TASK-043 均 REVIEW（B12-R2 PASS），提交中
已实现并针对性验证的成员：TASK-042、TASK-043（B12-V1、B12-V2 verify exit 0；真实进程 kill -9 重启冒烟为 B12-V1）
未完成 / 未执行验证：NOT RUN 项见 PROGRESS「B12」；准入 step_no 并发死锁在 B12 提交后以 TASK-039 单独修复
未提交文件（含既有无关修改）：见 PROGRESS「B12」本批修改文件（17 个修改＋8 个未跟踪 backend 文件）、docs/dev/CURRENT.md、docs/dev/PROGRESS.md；无既有无关修改
共同验证及独立 Review 证据编号：B12-V1、B12-V2；B12-R1（1 个 P2，已修复）、B12-R2 PASS
下一步具体动作：
1. 提交代码（`feat(investigation): deterministic termination and startup recovery (TASK-042–043)`），回填 SHA 后批次与成员标 DONE
2. TASK-039 死锁独立修复，完成后送审；不推送；不开始 B13
本地启动 Demo 配置：在 OPSPILOT_DB_* 环境变量基础上加 --spring.profiles.active=demo

后续 UI 约定（TASK-096/099 实施）：
- 底座 React＋TypeScript＋Vite＋Tailwind CSS＋shadcn/ui；Motion 仅在需要布局动画时引入；单一图标库；单一锁文件（npm）
- beautifului/beui/transitions 只借组织方式与过渡，不整套复制；Rare UI 第一版不用其源码；复制 MIT 代码保留声明
- 故障详情主体：当前影响→当前状态→当前判断→判断依据→处理建议→恢复情况；宽屏时间线置侧，技术信息折叠或进抽屉
- 浅色、中性灰文本、小面积语义色；不用巨型标题、满屏渐变、大片玻璃；动效 120～180ms、面板 180～240ms，尊重 reduced-motion
- 状态只认 Java 真实结果：不照搬 setTimeout 演示，不展示思维链，只展示 availableActions，409 刷新事实，无 AI 置信度，预算≠进度，SSE 断开≠FAILED，API 不支持的操作不展示
- 先打磨调查中/等待审批/恢复验证三态（共用组件），先做故障详情再复用
