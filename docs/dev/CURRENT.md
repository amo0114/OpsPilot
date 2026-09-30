# 当前工作

更新时间：2026-09-30（本地，B25 REVIEW）
仓库/分支：/root/projects/OpsPilot-V0.1-IMPLEMENTATION，main；无 remote
当前批次/状态：B25 REVIEW（实现与针对性验证完成，B25-V1 exit 0；B25-R1 PASS，未提交）；B24 DONE（0b934ed）
成员 Task 及顺序：TASK-069（REVIEW）
固定 Base SHA：050aa1a6168a028bbd356b15cadf6782cf808fec
批外前置核实：TASK-067 DONE（0b934ed）；TASK-068/074 DONE（1a655fd）；TASK-075/076 DONE（e9d32c6）
允许目录 / 明确不做 / 关键不变量：见 PROGRESS「B25」——批准成功路径、快照/执行上下文、执行设置、Execution MyBatis 与 PENDING 补派发来源、批准 Web 响应；不做 Docker/Worker（070/071）、核对/启动恢复（072/073）、Verification 创建、谓词/采样、新迁移、新依赖
本批规格章节及 PROGRESS 记录：08 TASK-069；04 §45～§47、§52、§78～§79；05 §38～§39、§43；PROGRESS「B25」
当前成员及位置：TASK-069 实现完成，停在 REVIEW
已实现并针对性验证的成员：TASK-069（审批集成 18/18、Web 17/17、变异 3 项）
未完成 / 未执行验证：B25 提交与真实 SHA 回填待完成；批准后 Execution 在 TASK-071 前保持 PENDING、Incident 停在 EXECUTING；CCG 门禁 NOT RUN（工具缺失）；ai-runtime、真实 LLM、S1～S3 NOT RUN；其余见 PROGRESS 待处理问题；Redis < 7.2、MySQL 8.0.16、Windows mvnw.cmd NOT VERIFIED
未提交文件（含既有无关修改）：13 个未跟踪文件（domain/execution 2、ApprovalApprovedPayloadV1、application/execution 3、RecoveryPolicySnapshotV1、config 2、mybatis/execution 3＋XML）；12 个修改的代码/测试文件与 docs/dev/CURRENT.md、PROGRESS.md；无既有无关修改
共同验证及独立 Review 证据编号：B25-V1（backend clean verify exit 0，2026-09-30 09:41～09:52 UTC）；B25-R1 PASS（本轮 83 项针对性测试通过）
下一步具体动作：
1. B25-R1 已 PASS；范围为 Base 050aa1a 至工作树全部变化（含 13 个未跟踪文件），审查证据见 PROGRESS
2. PASS 且用户授权后提交（建议标题 `feat(approval): approve creates the pending execution with a frozen recovery contract (TASK-069)`），回填 SHA，B25 与成员一起 DONE；不推送
3. 之后才开始 B26（TASK-070～071）

本地启动 Demo 配置：在 OPSPILOT_DB_* 环境变量基础上加 --spring.profiles.active=demo

后续 UI 约定（TASK-096/099 实施）：
- 底座 React＋TypeScript＋Vite＋Tailwind CSS＋shadcn/ui；Motion 仅在需要布局动画时引入；单一图标库；单一锁文件（npm）
- beautifului/beui/transitions 只借组织方式与过渡，不整套复制；Rare UI 第一版不用其源码；复制 MIT 代码保留声明
- 故障详情主体：当前影响→当前状态→当前判断→判断依据→处理建议→恢复情况；宽屏时间线置侧，技术信息折叠或进抽屉
- 浅色、中性灰文本、小面积语义色；不用巨型标题、满屏渐变、大片玻璃；动效 120～180ms、面板 180～240ms，尊重 reduced-motion
- 状态只认 Java 真实结果：不照搬 setTimeout 演示，不展示思维链，只展示 availableActions，409 刷新事实，无 AI 置信度，预算≠进度，SSE 断开≠FAILED，API 不支持的操作不展示
- 先打磨调查中/等待审批/恢复验证三态（共用组件），先做故障详情再复用
