# 当前工作

更新时间：2026-09-30（本地）
仓库/分支：/root/projects/OpsPilot-V0.1-IMPLEMENTATION，main；无 remote
当前批次/状态：B19 REVIEW（B19-R1 PASS，待用户提交）
成员 Task 及顺序：B19 = TASK-059 → 060 → 061
固定 Base SHA：b2e8f3d332523727043ae571b0ca6a728e9dfd48
批外前置核实：TASK-058 DONE（b2d29c7，B18-R3 PASS）
允许目录 / 明确不做 / 关键不变量：见 PROGRESS「B19」（调查完成/版本演进/UNDETERMINED 的 application 与持久化及测试；不做 Remediation 表、Web/UI 新接口、Migration）
本批规格章节及 PROGRESS 记录：PROGRESS「B19」
当前成员及位置：TASK-059～061 核查与端到端验证完成（生产代码无需修改，补 4 项集成测试，裁定关闭 UNDETERMINED 冻结范围待处理行）；B19-R1 PASS，断点在用户提交
已实现并针对性验证的成员：B01～B18 全部，及 B13 前的独立修复；B19 的 TASK-059～061
未完成 / 未执行验证：B19 提交与真实 SHA 回填；模型输出质量在验收运行继续观察（见 PROGRESS 待处理）；Remediation Prompt 与真实模型（TASK-063）；恢复采样准入（TASK-078）；AgentStep 输出信封读取方（TASK-100 等）；Demo 调查账号/ACL 部署、docker.sock 挂载与 ShortLink 真实导出、日志格式校准（TASK-105/106）；观察项：surefire 退出等待、Hikari 连接已关闭告警、Fake runtime 偶发 AI_RUNTIME_UNAVAILABLE、AI 客户端 h2c 升级告警；其余见 PROGRESS 待处理问题；Redis < 7.2、MySQL 8.0.16、Windows mvnw.cmd NOT VERIFIED
未提交文件（含既有无关修改）：开工时工作树干净；本批修改见 git status
共同验证及独立 Review 证据编号：B19-V1（见 PROGRESS「B19」）；B19-R1 PASS（独立重跑调查编排 32/32）
下一步具体动作：
1. 用户按计划提交测试代码，再回填真实 SHA、将 B19 与 TASK-059～061 一起标 DONE 后提交进度文档；提交前保持 REVIEW，不推送



本地启动 Demo 配置：在 OPSPILOT_DB_* 环境变量基础上加 --spring.profiles.active=demo

后续 UI 约定（TASK-096/099 实施）：
- 底座 React＋TypeScript＋Vite＋Tailwind CSS＋shadcn/ui；Motion 仅在需要布局动画时引入；单一图标库；单一锁文件（npm）
- beautifului/beui/transitions 只借组织方式与过渡，不整套复制；Rare UI 第一版不用其源码；复制 MIT 代码保留声明
- 故障详情主体：当前影响→当前状态→当前判断→判断依据→处理建议→恢复情况；宽屏时间线置侧，技术信息折叠或进抽屉
- 浅色、中性灰文本、小面积语义色；不用巨型标题、满屏渐变、大片玻璃；动效 120～180ms、面板 180～240ms，尊重 reduced-motion
- 状态只认 Java 真实结果：不照搬 setTimeout 演示，不展示思维链，只展示 availableActions，409 刷新事实，无 AI 置信度，预算≠进度，SSE 断开≠FAILED，API 不支持的操作不展示
- 先打磨调查中/等待审批/恢复验证三态（共用组件），先做故障详情再复用
