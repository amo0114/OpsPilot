# 当前工作

更新时间：2026-09-27（本地）
仓库/分支：/root/projects/OpsPilot-V0.1-IMPLEMENTATION，main；无 remote
当前批次/状态：B03 DONE（代码 626a19f，B03-R2 PASS）；B04 未开始
成员 Task 及顺序：B03 = TASK-018 → 019 → 020（均 DONE）；下一批 B04 = TASK-021 → 022
固定 Base SHA：B03 为 4630f27ee3aece7138c79338ee39042c427f3287；B04 开工时读取当时 HEAD
批外前置核实：B04 的批外前置 TASK-020 DONE（626a19f）
允许目录 / 明确不做 / 关键不变量：B04 开工时按 BATCH-PLAN 与 08 TASK-021/022 固定
本批规格章节及 PROGRESS 记录：PROGRESS「B03」
当前成员及位置：无进行中批次
已实现并针对性验证的成员：B01～B03 全部
未完成 / 未执行验证：B03 真实审批取消联动（TASK-067）、Stop 收束与迟到结果（TASK-039～043）；MySQL 8.0.16、Windows mvnw.cmd NOT VERIFIED
未提交文件（含既有无关修改）：无（本交接卡与 PROGRESS 回填随 docs(progress) 提交）
共同验证及独立 Review 证据编号：B03-V1、B03-V2、B03-R1（P1 已修复）、B03-R2 PASS
下一步具体动作：
1. 等待用户指示开始 B04（调查事实表与不可变 Observation，TASK-021 → 022）
2. B04 开工：核对 Git、固定 HEAD 为 Base；TASK-021 的恢复外键可先建列，TASK-074 补最终 FK（07 §92）
本地启动 Demo 配置：在 OPSPILOT_DB_* 环境变量基础上加 --spring.profiles.active=demo

后续 UI 约定（TASK-096/099 实施）：
- 底座 React＋TypeScript＋Vite＋Tailwind CSS＋shadcn/ui；Motion 仅在需要布局动画时引入；单一图标库；单一锁文件（npm）
- beautifului/beui/transitions 只借组织方式与过渡，不整套复制；Rare UI 第一版不用其源码；复制 MIT 代码保留声明
- 故障详情主体：当前影响→当前状态→当前判断→判断依据→处理建议→恢复情况；宽屏时间线置侧，技术信息折叠或进抽屉
- 浅色、中性灰文本、小面积语义色；不用巨型标题、满屏渐变、大片玻璃；动效 120～180ms、面板 180～240ms，尊重 reduced-motion
- 状态只认 Java 真实结果：不照搬 setTimeout 演示，不展示思维链，只展示 availableActions，409 刷新事实，无 AI 置信度，预算≠进度，SSE 断开≠FAILED，API 不支持的操作不展示
- 先打磨调查中/等待审批/恢复验证三态（共用组件），先做故障详情再复用
