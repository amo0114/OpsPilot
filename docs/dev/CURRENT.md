# 当前工作

更新时间：2026-09-29（本地）
仓库/分支：/root/projects/OpsPilot-V0.1-IMPLEMENTATION，main；无 remote
当前批次/状态：B16 REVIEW（B16-R2 PASS，待用户授权提交；未提交）
成员 Task 及顺序：B16 = TASK-052 → 053
固定 Base SHA：8535b08d781b62f1e369ef57afb8fa9fc34380dd
批外前置核实：TASK-051 DONE（3ec7aea，B15-R3 PASS）
允许目录 / 明确不做 / 关键不变量：见 PROGRESS「B16」（application capability/provider、investigation context；domain ErrorCode；infrastructure Prometheus/Loki Provider、配置、上下文查询；测试；docs/dev；不做 ShortLink 靶场、其余 Provider、调查循环接入、Migration）
本批规格章节及 PROGRESS 记录：PROGRESS「B16」
当前成员及位置：TASK-052～053 实现、修复与 B16-R2 独立复审完成，无提交前阻塞项；待授权提交
已实现并针对性验证的成员：B01～B15 全部，及 B13 前的独立修复；B16 的 TASK-052～053（B16-R2 PASS，未提交，仍 REVIEW）
未完成 / 未执行验证：B16 用户提交授权、提交及 SHA 回填；B16 真实进程冒烟 NOT RUN（调查循环未接入）；其余真实 Provider（TASK-054～057）；执行服务接入调查循环与孤立 Invocation 补完（TASK-058）；恢复采样准入（TASK-078）；AgentStep 输出信封读取方（TASK-100 等）；真实 LLM 接入归属（TASK-058 前）；ShortLink 真实导出校准（TASK-105/106）；观察项：surefire 退出等待、Hikari 连接已关闭告警、Fake runtime 偶发 AI_RUNTIME_UNAVAILABLE；其余见 PROGRESS 待处理问题；MySQL 8.0.16、Windows mvnw.cmd NOT VERIFIED
未提交文件（含既有无关修改）：开工时工作树干净；本批修改见 git status 与 PROGRESS「B16」修改文件
共同验证及独立 Review 证据编号：B16-V1；B16-R1 NEEDS CHANGES（已修复）；B16-V2（clean verify exit 0）；B16-R2 PASS（独立专项 verify 92/92，含真实 Prometheus/Loki/MySQL）
下一步具体动作：
1. 等待用户授权提交 B16（B16-R2 PASS，Base 仍为 8535b08）；不再修改已审源码
2. 获授权后提交，回填真实 SHA 并将 B16 与两项成员一起标 DONE；不推送，不提前开始 B17

本地启动 Demo 配置：在 OPSPILOT_DB_* 环境变量基础上加 --spring.profiles.active=demo

后续 UI 约定（TASK-096/099 实施）：
- 底座 React＋TypeScript＋Vite＋Tailwind CSS＋shadcn/ui；Motion 仅在需要布局动画时引入；单一图标库；单一锁文件（npm）
- beautifului/beui/transitions 只借组织方式与过渡，不整套复制；Rare UI 第一版不用其源码；复制 MIT 代码保留声明
- 故障详情主体：当前影响→当前状态→当前判断→判断依据→处理建议→恢复情况；宽屏时间线置侧，技术信息折叠或进抽屉
- 浅色、中性灰文本、小面积语义色；不用巨型标题、满屏渐变、大片玻璃；动效 120～180ms、面板 180～240ms，尊重 reduced-motion
- 状态只认 Java 真实结果：不照搬 setTimeout 演示，不展示思维链，只展示 availableActions，409 刷新事实，无 AI 置信度，预算≠进度，SSE 断开≠FAILED，API 不支持的操作不展示
- 先打磨调查中/等待审批/恢复验证三态（共用组件），先做故障详情再复用
