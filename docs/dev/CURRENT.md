# 当前工作

更新时间：2026-09-28（本地）
仓库/分支：/root/projects/OpsPilot-V0.1-IMPLEMENTATION，main；无 remote
当前批次/状态：TASK-040/043 修复 DONE（代码 5589fed）；B13 前约定的独立修复（TASK-039、016、026、040/043）全部完成；B13 未开始
成员 Task 及顺序：B12 = TASK-042 → TASK-043（均 DONE）；下一批 B13 = TASK-044 → 046（待用户指示）
固定 Base SHA：B13 开工时读取当时 HEAD
批外前置核实：B13 开工时按 08 核对其成员的批外前置
允许目录 / 明确不做 / 关键不变量：B13 开工时按 BATCH-PLAN 与 08 固定
本批规格章节及 PROGRESS 记录：PROGRESS「TASK-040/043 修复」
当前成员及位置：无进行中批次或修复
已实现并针对性验证的成员：B01～B12 全部，及 TASK-039、TASK-016、TASK-026、TASK-040/043 独立修复
未完成 / 未执行验证：调查调用中断在真实链路复核（TASK-048）；AgentStep 输出信封读取方（TASK-100 等）；Fake Capability 无步数上限（TASK-047/048）；真实 LLM 接入归属（TASK-058 前）；观察项：surefire 退出等待、Hikari 连接已关闭告警、Fake runtime 偶发 AI_RUNTIME_UNAVAILABLE；其余见 PROGRESS 待处理问题；MySQL 8.0.16、Windows mvnw.cmd NOT VERIFIED
未提交文件（含既有无关修改）：无（本交接卡与 PROGRESS 回填随 docs(progress) 提交）
共同验证及独立 Review 证据编号：见 PROGRESS「TASK-040/043 修复」（Review-3 PASS）
下一步具体动作：
1. 等待用户指示开始 B13（TASK-044 → 046，Capability 注册、允许能力描述与 Provider 解析）
2. 不推送
本地启动 Demo 配置：在 OPSPILOT_DB_* 环境变量基础上加 --spring.profiles.active=demo

后续 UI 约定（TASK-096/099 实施）：
- 底座 React＋TypeScript＋Vite＋Tailwind CSS＋shadcn/ui；Motion 仅在需要布局动画时引入；单一图标库；单一锁文件（npm）
- beautifului/beui/transitions 只借组织方式与过渡，不整套复制；Rare UI 第一版不用其源码；复制 MIT 代码保留声明
- 故障详情主体：当前影响→当前状态→当前判断→判断依据→处理建议→恢复情况；宽屏时间线置侧，技术信息折叠或进抽屉
- 浅色、中性灰文本、小面积语义色；不用巨型标题、满屏渐变、大片玻璃；动效 120～180ms、面板 180～240ms，尊重 reduced-motion
- 状态只认 Java 真实结果：不照搬 setTimeout 演示，不展示思维链，只展示 availableActions，409 刷新事实，无 AI 置信度，预算≠进度，SSE 断开≠FAILED，API 不支持的操作不展示
- 先打磨调查中/等待审批/恢复验证三态（共用组件），先做故障详情再复用
