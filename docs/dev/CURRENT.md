# 当前工作

更新时间：2026-09-27（本地）
仓库/分支：/root/projects/OpsPilot-V0.1-IMPLEMENTATION，main；无 remote
当前批次/状态：B04 REVIEW（B04-R1 PASS，提交中）
成员 Task 及顺序：TASK-021 → TASK-022（均 REVIEW）
固定 Base SHA：f116f8e76b230ba758d1279394875a8db544b6f3
批外前置核实：TASK-020 DONE（626a19f）；B03 DONE
允许目录 / 明确不做 / 关键不变量：见 PROGRESS「B04」记录
本批规格章节及 PROGRESS 记录：PROGRESS「B04」
当前成员及位置：全部成员实现完成；等待 B04-R1 独立 Review
已实现并针对性验证的成员：TASK-021（InvestigationFactSchemaTest 36/36）；TASK-022（MyBatisObservationRepositoryTest 4/4＋2 变异）
未完成 / 未执行验证：recovery_verification 外键（TASK-074）、Hypothesis/Evidence/Diagnosis 领域（TASK-023～026）NOT RUN；MySQL 8.0.16、Windows mvnw.cmd NOT RUN
未提交文件（含既有无关修改）：全部为 B04 改动，见 PROGRESS「B04」本批修改文件（含未跟踪文件）；无既有无关修改
共同验证及独立 Review 证据编号：B04-V1（backend `./mvnw -B clean verify` exit 0：domain 23、infrastructure 245、web 21、boot 2）；B04-R1 PASS
后续回填（已记入 PROGRESS 待处理问题）：TASK-074 补 recovery_verification 外键；TASK-043/047/048 状态索引；TASK-025/026 诊断引用同 Investigation；TASK-049/051 脱敏与结果 Codec
下一步具体动作：
1. 独立 Reviewer 按 BATCH-PLAN §2 审查 f116f8e 到当前工作树（git status --short、git diff f116f8e、git ls-files --others --exclude-standard）
2. Review PASS 且获用户提交授权后提交（建议标题 feat(investigation): investigation fact tables and immutable observations (TASK-021–022)），回填 SHA，B04 与成员一起 DONE
3. 未提交/未 DONE 前不开始 B05
本地启动 Demo 配置：在 OPSPILOT_DB_* 环境变量基础上加 --spring.profiles.active=demo

后续 UI 约定（TASK-096/099 实施）：
- 底座 React＋TypeScript＋Vite＋Tailwind CSS＋shadcn/ui；Motion 仅在需要布局动画时引入；单一图标库；单一锁文件（npm）
- beautifului/beui/transitions 只借组织方式与过渡，不整套复制；Rare UI 第一版不用其源码；复制 MIT 代码保留声明
- 故障详情主体：当前影响→当前状态→当前判断→判断依据→处理建议→恢复情况；宽屏时间线置侧，技术信息折叠或进抽屉
- 浅色、中性灰文本、小面积语义色；不用巨型标题、满屏渐变、大片玻璃；动效 120～180ms、面板 180～240ms，尊重 reduced-motion
- 状态只认 Java 真实结果：不照搬 setTimeout 演示，不展示思维链，只展示 availableActions，409 刷新事实，无 AI 置信度，预算≠进度，SSE 断开≠FAILED，API 不支持的操作不展示
- 先打磨调查中/等待审批/恢复验证三态（共用组件），先做故障详情再复用
