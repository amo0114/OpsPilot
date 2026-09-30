# 当前工作

更新时间：2026-09-30（本地，B28-R2 PASS）
仓库/分支：/root/projects/OpsPilot-V0.1-IMPLEMENTATION，main；无 remote
当前批次/状态：B28 REVIEW（B28-R1 两项 P1 已关闭，B28-V2 通过，B28-R2 PASS，待提交）
成员 Task 及顺序：TASK-077 → TASK-078 → TASK-079（均 REVIEW）
固定 Base SHA：db9e2052f4155c96b0e4c5299ddea9302b183a33（不变）
批外前置核实：TASK-073 DONE（f8d6122）；TASK-074～076 DONE
允许目录 / 明确不做 / 关键不变量：见 PROGRESS「B28」范围；不做 Verification 创建（080/081）、Incident 结果迁移（082）、启动恢复（083）、UI/SSE、新迁移、新依赖；FALSE 优先、UNKNOWN 不当 0、样本槽位不重试、sleep 不在事务内、调用与样本不越过冻结 deadline、准入事务先锁 Incident
本批规格章节及 PROGRESS 记录：PROGRESS「B28」（含“B28-R1 修复”）
当前成员及位置：TASK-077/078/079 实现、R1 修复与针对性验证完成，均 REVIEW
已实现并针对性验证的成员：B01～B27 全部，及 B13 前的独立修复
未完成 / 未执行验证：B28 提交与 SHA 回填；Verification 终态后 Incident 仍 VERIFYING（082）；Verification 创建（080/081）与补派发/启动恢复（083）；真实 Provider 恢复采样与 S3 端到端、ai-runtime、真实 LLM NOT RUN；CCG 门禁工具本机缺失；Redis < 7.2、MySQL 8.0.16、Windows mvnw.cmd NOT VERIFIED
未提交文件（含既有无关修改）：B28 全部改动（见 PROGRESS「B28」修改文件，另含 R1 修复涉及的 ProviderCapabilityInvoker、CapabilityAdmissionService、CriterionReason 及测试 ProviderCapabilityInvokerTest）；无既有无关修改
共同验证及独立 Review 证据编号：B28-V1（修复前）、B28-R1（NEEDS CHANGES）、B28-V2（修复后 clean verify exit 0）、B28-R2（PASS；独立重跑 48 项全部通过）
下一步具体动作：
1. B28-R2 已 PASS；审查与验证证据已记入 PROGRESS，等待提交
2. PASS 且获用户授权后提交，回填 SHA，B28 与 TASK-077～079 一起 DONE；不开始 B29，不推送

本地启动 Demo 配置：在 OPSPILOT_DB_* 环境变量基础上加 --spring.profiles.active=demo

后续 UI 约定（TASK-096/099 实施）：
- 底座 React＋TypeScript＋Vite＋Tailwind CSS＋shadcn/ui；Motion 仅在需要布局动画时引入；单一图标库；单一锁文件（npm）
- beautifului/beui/transitions 只借组织方式与过渡，不整套复制；Rare UI 第一版不用其源码；复制 MIT 代码保留声明
- 故障详情主体：当前影响→当前状态→当前判断→判断依据→处理建议→恢复情况；宽屏时间线置侧，技术信息折叠或进抽屉
- 浅色、中性灰文本、小面积语义色；不用巨型标题、满屏渐变、大片玻璃；动效 120～180ms、面板 180～240ms，尊重 reduced-motion
- 状态只认 Java 真实结果：不照搬 setTimeout 演示，不展示思维链，只展示 availableActions，409 刷新事实，无 AI 置信度，预算≠进度，SSE 断开≠FAILED，API 不支持的操作不展示
- 先打磨调查中/等待审批/恢复验证三态（共用组件），先做故障详情再复用
