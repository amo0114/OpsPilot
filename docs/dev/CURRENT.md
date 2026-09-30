# 当前工作

更新时间：2026-10-01（本地，B29-R2 PASS）
仓库/分支：/root/projects/OpsPilot-V0.1-IMPLEMENTATION，main；无 remote
当前批次/状态：B29 REVIEW（B29-R2 PASS，B29-V2 通过，待提交）
成员 Task 及顺序：TASK-080 → TASK-081（均 REVIEW）
固定 Base SHA：c0eb35c202d6724c4b3b3674933670557a365971（不变）
批外前置核实：TASK-069/071 DONE、TASK-074/075 DONE、TASK-079 DONE（c9dab5b）
允许目录 / 明确不做 / 关键不变量：见 PROGRESS「B29」范围；不做 Incident 结果迁移（082）、启动恢复（083）、GET 验证视图、UI/SSE、新迁移、新依赖；成功落账不重选 ACTIVE 策略、同 Execution 唯一 Verification、派发在提交后、事务先锁 Incident、正式应用以真实派发器与 Worker 启动
本批规格章节及 PROGRESS 记录：PROGRESS「B29」（含“B29-R1 修复”与“B29-R2”）
当前成员及位置：TASK-080/081 实现、R1 修复与独立复审完成，B29-R2 PASS，均 REVIEW 待提交
已实现并针对性验证的成员：B01～B28 全部，及 B13 前的独立修复
未完成 / 未执行验证：B29 提交与 SHA 回填；Verification 终态后 Incident 仍 VERIFYING（082）；Verification 补派发/启动恢复（083）；真实 Provider 恢复采样与 S3 端到端、ai-runtime、真实 LLM NOT RUN；CCG 门禁工具本机缺失；Redis < 7.2、MySQL 8.0.16、Windows mvnw.cmd NOT VERIFIED
未提交文件（含既有无关修改）：B29 全部改动（见 PROGRESS「B29」修改文件，另含 R1 回归 opspilot-boot ApplicationWiringTest）；无既有无关修改
共同验证及独立 Review 证据编号：B29-V1（修复前）、B29-R1（NEEDS CHANGES）、B29-V2（修复后 clean verify exit 0）、B29-R2（PASS，独立启动/集成/Web 测试 50/50，日志 /tmp/b29-r2-tests.log）
下一步具体动作：
1. B29-R2 已 PASS，可按用户授权进入本批代码提交及进度记录提交步骤，回填真实 SHA 后将 B29 与 TASK-080/081 一起标 DONE
2. 完成本批提交与回填前不开始 B30；不推送

本地启动 Demo 配置：在 OPSPILOT_DB_* 环境变量基础上加 --spring.profiles.active=demo

后续 UI 约定（TASK-096/099 实施）：
- 底座 React＋TypeScript＋Vite＋Tailwind CSS＋shadcn/ui；Motion 仅在需要布局动画时引入；单一图标库；单一锁文件（npm）
- beautifului/beui/transitions 只借组织方式与过渡，不整套复制；Rare UI 第一版不用其源码；复制 MIT 代码保留声明
- 故障详情主体：当前影响→当前状态→当前判断→判断依据→处理建议→恢复情况；宽屏时间线置侧，技术信息折叠或进抽屉
- 浅色、中性灰文本、小面积语义色；不用巨型标题、满屏渐变、大片玻璃；动效 120～180ms、面板 180～240ms，尊重 reduced-motion
- 状态只认 Java 真实结果：不照搬 setTimeout 演示，不展示思维链，只展示 availableActions，409 刷新事实，无 AI 置信度，预算≠进度，SSE 断开≠FAILED，API 不支持的操作不展示
- 先打磨调查中/等待审批/恢复验证三态（共用组件），先做故障详情再复用
