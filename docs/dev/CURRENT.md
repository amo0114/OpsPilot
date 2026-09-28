# 当前工作

更新时间：2026-09-27（本地）
仓库/分支：/root/projects/OpsPilot-V0.1-IMPLEMENTATION，main；无 remote
当前批次/状态：B10 DONE（代码 c3ec168，B10-R2 PASS）；B11 未开始
成员 Task 及顺序：B10 = TASK-037 → 039（均 DONE）；下一批 B11 = TASK-040 → 041
固定 Base SHA：B10 为 d87037f02713f47d65722a5117428f021d882b1e；B11 开工时读取当时 HEAD
批外前置核实：B11 开工时按 08 核对 TASK-040/041 的批外前置
允许目录 / 明确不做 / 关键不变量：B11 开工时按 BATCH-PLAN 与 08 TASK-040/041 固定
本批规格章节及 PROGRESS 记录：PROGRESS「B10」
当前成员及位置：无进行中批次
已实现并针对性验证的成员：B01～B10 全部
未完成 / 未执行验证：TASK-040 须实现 Observation/Evidence 引用边界（不能依赖 ContextBuilder）；结果记录器 StepOutcome 与 Intent 处理在同一结果事务组合（TASK-040/041）；context_digest；SingleFlight 延后唤醒为最后到达者、真实 Worker 须校验期望 run；真实 LLM 接入归属（TASK-058 前）；锁等待测试以 sleep 推断（触及时改为直接确认）；InvestigationRunIntegrationTest "Connection is closed" 观察项；其余见 PROGRESS 待处理问题；MySQL 8.0.16、Windows mvnw.cmd NOT VERIFIED
未提交文件（含既有无关修改）：无（本交接卡与 PROGRESS 回填随 docs(progress) 提交）
共同验证及独立 Review 证据编号：B10-V1、B10-V2、B10-R1、B10-R2
下一步具体动作：
1. 等待用户指示开始 B11（Intent 分派与 Stop 竞态处理，TASK-040 → 041）
2. B11 开工：核对 Git、固定 HEAD 为 Base；Intent 分派复用 B05/B06 用例（Hypothesis/Evidence/Diagnosis）与 B10 准入/记录，限定引用范围，旧 run 结果只审计，同轮 Stop 后仅合法 COMPLETE 可收束；REQUEST_CAPABILITY 暂用 Fake Capability Port 但须经合同层
本地启动 Demo 配置：在 OPSPILOT_DB_* 环境变量基础上加 --spring.profiles.active=demo

后续 UI 约定（TASK-096/099 实施）：
- 底座 React＋TypeScript＋Vite＋Tailwind CSS＋shadcn/ui；Motion 仅在需要布局动画时引入；单一图标库；单一锁文件（npm）
- beautifului/beui/transitions 只借组织方式与过渡，不整套复制；Rare UI 第一版不用其源码；复制 MIT 代码保留声明
- 故障详情主体：当前影响→当前状态→当前判断→判断依据→处理建议→恢复情况；宽屏时间线置侧，技术信息折叠或进抽屉
- 浅色、中性灰文本、小面积语义色；不用巨型标题、满屏渐变、大片玻璃；动效 120～180ms、面板 180～240ms，尊重 reduced-motion
- 状态只认 Java 真实结果：不照搬 setTimeout 演示，不展示思维链，只展示 availableActions，409 刷新事实，无 AI 置信度，预算≠进度，SSE 断开≠FAILED，API 不支持的操作不展示
- 先打磨调查中/等待审批/恢复验证三态（共用组件），先做故障详情再复用
