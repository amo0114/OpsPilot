# 当前工作

更新时间：2026-09-30（本地，B24 REVIEW）
仓库/分支：/root/projects/OpsPilot-V0.1-IMPLEMENTATION，main；无 remote
当前批次/状态：B24 REVIEW（实现与针对性验证完成，B24-V1 exit 0；B24-R1 PASS，未提交）；B23 DONE（e9d32c6）
成员 Task 及顺序：TASK-067（REVIEW）
固定 Base SHA：bdfbaac0d8a57bd40e454b4a9f338b91eb8957f0
批外前置核实：TASK-066 DONE（ef115c8）；TASK-068/074 DONE（1a655fd）；TASK-075/076 DONE（e9d32c6）
允许目录 / 明确不做 / 关键不变量：见 PROGRESS「B24」——ErrorCode、RecoveryPolicySelector、ApprovalApplicationService 锁前快照与策略复核、集成测试；不做批准成功路径与 Execution 创建（069）、Worker、API 结构变化、新迁移、新依赖
本批规格章节及 PROGRESS 记录：08 TASK-067；04 §44、§49、§52、§78；05 §37～§43；PROGRESS「B24」
当前成员及位置：TASK-067 实现完成，停在 REVIEW
已实现并针对性验证的成员：TASK-067（审批集成 13/13、Web 16/16、变异 3 项）
未完成 / 未执行验证：B24 提交与真实 SHA 回填待完成；批准成功路径（069）；CCG 门禁 NOT RUN（工具缺失）；ai-runtime、真实 LLM、S1～S3 NOT RUN；其余见 PROGRESS 待处理问题；Redis < 7.2、MySQL 8.0.16、Windows mvnw.cmd NOT VERIFIED
未提交文件（含既有无关修改）：未跟踪 application/recovery/RecoveryPolicySelector.java；修改 ErrorCode、ApprovalApplicationService、RemediationApprovalIntegrationTest、RemediationControllerTest、docs/dev/CURRENT.md、PROGRESS.md；无既有无关修改
共同验证及独立 Review 证据编号：B24-V1（backend clean verify exit 0，2026-09-30 08:55～09:06 UTC）；B24-R1 PASS（本轮 29 项针对性测试通过）
下一步具体动作：
1. B24-R1 已 PASS；范围为 Base bdfbaac 至工作树全部变化（含 RecoveryPolicySelector.java），审查证据见 PROGRESS
2. PASS 且用户授权后提交（建议标题 `feat(approval): recovery policy admission and post-lock rechecks for approvals (TASK-067)`），回填 SHA，B24 与成员一起 DONE；不推送
3. 之后才开始 B25（TASK-069）

本地启动 Demo 配置：在 OPSPILOT_DB_* 环境变量基础上加 --spring.profiles.active=demo

后续 UI 约定（TASK-096/099 实施）：
- 底座 React＋TypeScript＋Vite＋Tailwind CSS＋shadcn/ui；Motion 仅在需要布局动画时引入；单一图标库；单一锁文件（npm）
- beautifului/beui/transitions 只借组织方式与过渡，不整套复制；Rare UI 第一版不用其源码；复制 MIT 代码保留声明
- 故障详情主体：当前影响→当前状态→当前判断→判断依据→处理建议→恢复情况；宽屏时间线置侧，技术信息折叠或进抽屉
- 浅色、中性灰文本、小面积语义色；不用巨型标题、满屏渐变、大片玻璃；动效 120～180ms、面板 180～240ms，尊重 reduced-motion
- 状态只认 Java 真实结果：不照搬 setTimeout 演示，不展示思维链，只展示 availableActions，409 刷新事实，无 AI 置信度，预算≠进度，SSE 断开≠FAILED，API 不支持的操作不展示
- 先打磨调查中/等待审批/恢复验证三态（共用组件），先做故障详情再复用
