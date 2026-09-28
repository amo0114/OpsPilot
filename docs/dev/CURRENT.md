# 当前工作

更新时间：2026-09-27（本地）
仓库/分支：/root/projects/OpsPilot-V0.1-IMPLEMENTATION，main；无 remote
当前批次/状态：B08 REVIEW（B08-R2 PASS，提交中）
成员 Task 及顺序：B08 = TASK-033 → TASK-034（均 REVIEW）
固定 Base SHA：dfcbca66eb61f3e84200a0190cfbc4ce1e9e551e（未移动）
批外前置核实：TASK-032 DONE（d796643，B07-R2 PASS）；开工时工作树干净
允许目录 / 明确不做 / 关键不变量：见 PROGRESS「B08」（ai-runtime 服务与端点、Fake LLM；Java AiRuntime 客户端与配置；不做真实 LLM、AgentStep 审计、Context Builder、Intent 分派）
本批规格章节及 PROGRESS 记录：PROGRESS「B08」（含 B08-R1 修复与 B08-V2）
当前成员及位置：修复完成，等待 Reviewer 复核
已实现并针对性验证的成员：TASK-033（pytest 143；uvicorn＋Fake 冒烟）、TASK-034（HttpAiRuntimeClientTest 17/17 含响应体延迟回归；Java→Python 跨语言冒烟）
未完成 / 未执行验证：B08-R1 修复复核；真实 LLM 未接入（TASK-058 前待确认归属）；AgentStep 模型/Prompt/Token 元数据传递（TASK-038）；ccg 质量关卡 NOT RUN（本机未安装）；InvestigationRunIntegrationTest "Connection is closed" 观察项；Invocation 技术 API 后端归属（TASK-103 前）；Plan supersede 占位（TASK-062/067）；recovery_verification 外键（TASK-074）；MySQL 8.0.16、Windows mvnw.cmd NOT VERIFIED
未提交文件（含既有无关修改）：无既有无关修改；修改 8 个代码/测试/配置文件＋docs/dev 两份，新增 16 个未跟踪文件（`git ls-files --others --exclude-standard`，清单见 PROGRESS「B08」本批修改文件）
共同验证及独立 Review 证据编号：B08-V1（修复前）、B08-V2（修复后：backend verify exit 0；ai-runtime sync/format/check/pytest exit 0；真实进程与跨语言冒烟）；B08-R1 PASS AFTER PATCH → B08-R2 PASS
下一步具体动作：
1. Reviewer 复核 HttpAiRuntimeClient.exchange（sendAsync＋future 限时＋取消）与回归测试、FakeLlmClient 去除 prompt 历史，并重跑必要门禁
2. 复核 PASS 且获用户授权后提交（建议 `feat(ai-runtime): fake-backed decision service and Java AI runtime client (TASK-033–034)`），回填 SHA 后批次与成员标 DONE；不得提前开始 B09
本地启动 Demo 配置：在 OPSPILOT_DB_* 环境变量基础上加 --spring.profiles.active=demo

后续 UI 约定（TASK-096/099 实施）：
- 底座 React＋TypeScript＋Vite＋Tailwind CSS＋shadcn/ui；Motion 仅在需要布局动画时引入；单一图标库；单一锁文件（npm）
- beautifului/beui/transitions 只借组织方式与过渡，不整套复制；Rare UI 第一版不用其源码；复制 MIT 代码保留声明
- 故障详情主体：当前影响→当前状态→当前判断→判断依据→处理建议→恢复情况；宽屏时间线置侧，技术信息折叠或进抽屉
- 浅色、中性灰文本、小面积语义色；不用巨型标题、满屏渐变、大片玻璃；动效 120～180ms、面板 180～240ms，尊重 reduced-motion
- 状态只认 Java 真实结果：不照搬 setTimeout 演示，不展示思维链，只展示 availableActions，409 刷新事实，无 AI 置信度，预算≠进度，SSE 断开≠FAILED，API 不支持的操作不展示
- 先打磨调查中/等待审批/恢复验证三态（共用组件），先做故障详情再复用
