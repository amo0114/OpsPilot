# 当前工作

更新时间：2026-09-30（本地）
仓库/分支：/root/projects/OpsPilot-V0.1-IMPLEMENTATION，main；无 remote
当前批次/状态：B19 DONE（代码 789f9f8，B19-R1 PASS）；B20 未开始
成员 Task 及顺序：B19 = TASK-059 → 060 → 061（均 DONE）；下一批 B20 = TASK-062 → 064
固定 Base SHA：B19 为 b2e8f3d332523727043ae571b0ca6a728e9dfd48；B20 开工时读取当时 HEAD
批外前置核实：B20 开工时按 08 核对其成员的批外前置
允许目录 / 明确不做 / 关键不变量：B20 开工时按 BATCH-PLAN 与 08 固定
本批规格章节及 PROGRESS 记录：PROGRESS「B19」
当前成员及位置：无进行中批次
已实现并针对性验证的成员：B01～B19 全部，及 B13 前的独立修复
未完成 / 未执行验证：B19 提交与真实 SHA 回填；模型输出质量在验收运行继续观察（见 PROGRESS 待处理）；Remediation Prompt 与真实模型（TASK-063）；恢复采样准入（TASK-078）；AgentStep 输出信封读取方（TASK-100 等）；Demo 调查账号/ACL 部署、docker.sock 挂载与 ShortLink 真实导出、日志格式校准（TASK-105/106）；观察项：surefire 退出等待、Hikari 连接已关闭告警、Fake runtime 偶发 AI_RUNTIME_UNAVAILABLE、AI 客户端 h2c 升级告警；其余见 PROGRESS 待处理问题；Redis < 7.2、MySQL 8.0.16、Windows mvnw.cmd NOT VERIFIED
未提交文件（含既有无关修改）：无（本交接卡与 PROGRESS 回填随 docs(progress) 提交）
共同验证及独立 Review 证据编号：B19-V1、B19-R1
下一步具体动作：
1. 等待用户指示开始 B20（TASK-062 → 064，修复计划表、上下文与 Proposal 校验）
2. 重跑真实闭合：先以 openai-compatible 启动 ai-runtime（Key 文件 ~/.config/opspilot/llm.env 只加载进该进程），再 `-Dtest=RealLlmClosureRun`；ai-runtime 命令一律 `uv run --frozen`
3. 不推送




本地启动 Demo 配置：在 OPSPILOT_DB_* 环境变量基础上加 --spring.profiles.active=demo

后续 UI 约定（TASK-096/099 实施）：
- 底座 React＋TypeScript＋Vite＋Tailwind CSS＋shadcn/ui；Motion 仅在需要布局动画时引入；单一图标库；单一锁文件（npm）
- beautifului/beui/transitions 只借组织方式与过渡，不整套复制；Rare UI 第一版不用其源码；复制 MIT 代码保留声明
- 故障详情主体：当前影响→当前状态→当前判断→判断依据→处理建议→恢复情况；宽屏时间线置侧，技术信息折叠或进抽屉
- 浅色、中性灰文本、小面积语义色；不用巨型标题、满屏渐变、大片玻璃；动效 120～180ms、面板 180～240ms，尊重 reduced-motion
- 状态只认 Java 真实结果：不照搬 setTimeout 演示，不展示思维链，只展示 availableActions，409 刷新事实，无 AI 置信度，预算≠进度，SSE 断开≠FAILED，API 不支持的操作不展示
- 先打磨调查中/等待审批/恢复验证三态（共用组件），先做故障详情再复用
