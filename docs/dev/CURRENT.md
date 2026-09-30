# 当前工作

更新时间：2026-09-30（本地，B23-R2 PASS，待提交）
仓库/分支：/root/projects/OpsPilot-V0.1-IMPLEMENTATION，main；无 remote
当前批次/状态：B23 REVIEW（B23-R1 NEEDS CHANGES 的 P2 已修复，B23-V2 exit 0；B23-R2 PASS，未提交）；B22 DONE（1a655fd）
成员 Task 及顺序：TASK-075（REVIEW）→ TASK-076（REVIEW）
固定 Base SHA：8afeae94a7d927c8ec13bd0e0a66163a9fc3730c
批外前置核实：TASK-074 DONE（1a655fd）；TASK-029 DONE（d796643）；TASK-044/046 DONE（36fd25a）
允许目录 / 明确不做 / 关键不变量：见 PROGRESS「B23」——application/recovery、infrastructure recovery MyBatis、schema 注册、demo Seed、组件详情 recoveryPolicy；不做谓词求值（077）、采样/Runner（078/079）、批准快照（069）、审批并发（067）、策略管理 API、新迁移、新依赖
本批规格章节及 PROGRESS 记录：08 TASK-075/076；06 §113～§116、§131；09 §75～§77；04 §48～§49；PROGRESS「B23」
当前成员及位置：两成员实现完成；B23-R1 P2（整数字段小数被截断）已在严格 Codec Mapper 修复并加回归测试
已实现并针对性验证的成员：TASK-075（Codec 63/63、Registry 48/48）、TASK-076（激活 6/6、Seed 8/8、系统查询与 Web）
未完成 / 未执行验证：B23-R2 PASS；提交与真实 SHA 回填待完成；CCG 门禁 NOT RUN（工具缺失）；ai-runtime、真实 LLM、S1～S3 NOT RUN；既有遗留见 PROGRESS 待处理问题；Redis < 7.2、MySQL 8.0.16、Windows mvnw.cmd NOT VERIFIED
未提交文件（含既有无关修改）：17 个未跟踪文件（application/recovery 11 个、ActiveRecoveryPolicyProjection、mybatis/recovery 2 个＋XML、2 个新测试）；12 个修改的代码/测试/Seed 文件（含本次修复的 JacksonSchemaCodecRegistry 与 JacksonSchemaCodecRegistryTest）与 docs/dev/CURRENT.md、PROGRESS.md；无既有无关修改
共同验证及独立 Review 证据编号：B23-V1（修复前）、B23-R1 NEEDS CHANGES、B23-V2（修复后 clean verify exit 0，2026-09-30 07:44～07:58 UTC）；B23-R2 PASS（111 项 Codec 测试与原复现探针通过）
下一步具体动作：
1. B23-R2 已 PASS；审查范围为 Base 8afeae9 至工作树全部变化（含 17 个未跟踪文件），证据见 PROGRESS
2. PASS 且用户授权后提交（建议标题 `feat(recovery): recovery policy criteria codec, activation and S3 seed (TASK-075–076)`），回填 SHA，B23 与成员一起 DONE；不推送
3. 之后才开始 B24（TASK-067）

本地启动 Demo 配置：在 OPSPILOT_DB_* 环境变量基础上加 --spring.profiles.active=demo

后续 UI 约定（TASK-096/099 实施）：
- 底座 React＋TypeScript＋Vite＋Tailwind CSS＋shadcn/ui；Motion 仅在需要布局动画时引入；单一图标库；单一锁文件（npm）
- beautifului/beui/transitions 只借组织方式与过渡，不整套复制；Rare UI 第一版不用其源码；复制 MIT 代码保留声明
- 故障详情主体：当前影响→当前状态→当前判断→判断依据→处理建议→恢复情况；宽屏时间线置侧，技术信息折叠或进抽屉
- 浅色、中性灰文本、小面积语义色；不用巨型标题、满屏渐变、大片玻璃；动效 120～180ms、面板 180～240ms，尊重 reduced-motion
- 状态只认 Java 真实结果：不照搬 setTimeout 演示，不展示思维链，只展示 availableActions，409 刷新事实，无 AI 置信度，预算≠进度，SSE 断开≠FAILED，API 不支持的操作不展示
- 先打磨调查中/等待审批/恢复验证三态（共用组件），先做故障详情再复用
