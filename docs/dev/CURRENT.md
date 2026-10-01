# 当前工作

更新时间：2026-10-01（本地，B31-R1 PASS）
仓库/分支：/root/projects/OpsPilot-V0.1-IMPLEMENTATION，main；无 remote
当前批次/状态：B31 REVIEW（B31-R1 PASS，B31-V1 通过，待提交）
成员 Task 及顺序：B31 = TASK-084 → 085 → 086
固定 Base SHA：a2a9827c2fbd24de7b27458fa00a3594ea4a7127
批外前置核实：TASK-083 DONE（39f9468，B30-R1 PASS）
允许目录 / 明确不做 / 关键不变量：见 PROGRESS「B31」范围；不做 SSE、05 §45～§48 独立 GET、UI、新迁移/依赖
本批规格章节及 PROGRESS 记录：PROGRESS「B31」
当前成员及位置：084～086 均 REVIEW，独立 Review PASS；B31-V1 clean verify exit 0（2026-10-01 03:36～03:50 UTC）
已实现并针对性验证的成员：B01～B30 全部（DONE），B31 的 084～086（REVIEW，未提交），及 B13 前的独立修复；恢复控制流（批准→执行→核对→验证→结果迁移→启动恢复）已闭合，真实场景验收待 TASK-105～109
未完成 / 未执行验证：B31 提交与 SHA 回填；非阻塞 P3：样本整数格式化的三元表达式仍返回 Double（见 B31-R1）；SSE（087～089）；05 §45～§48 独立 GET 无归属 Task（待确认）；真实 Provider 恢复采样与 S3 端到端 NOT RUN；CCG 门禁工具本机缺失；其余见 PROGRESS 待处理问题；Redis < 7.2、MySQL 8.0.16、Windows mvnw.cmd NOT VERIFIED
未提交文件（含既有无关修改）：开工时无；本批修改（含未跟踪新文件）见 git status 与 PROGRESS「B31」成员进度
共同验证及独立 Review 证据编号：B31-V1（exit 0）；B31-R1（PASS，独立测试 59/59，日志 /tmp/b31-r1-tests.log）
下一步具体动作：
1. B31-R1 已 PASS，可按用户授权进入代码与进度文档提交步骤，回填真实 SHA 后将 B31 与 TASK-084～086 一起标 DONE；不提前开始 B32
2. 不推送

本地启动 Demo 配置：在 OPSPILOT_DB_* 环境变量基础上加 --spring.profiles.active=demo

后续 UI 约定（TASK-096/099 实施）：
- 底座 React＋TypeScript＋Vite＋Tailwind CSS＋shadcn/ui；Motion 仅在需要布局动画时引入；单一图标库；单一锁文件（npm）
- beautifului/beui/transitions 只借组织方式与过渡，不整套复制；Rare UI 第一版不用其源码；复制 MIT 代码保留声明
- 故障详情主体：当前影响→当前状态→当前判断→判断依据→处理建议→恢复情况；宽屏时间线置侧，技术信息折叠或进抽屉
- 浅色、中性灰文本、小面积语义色；不用巨型标题、满屏渐变、大片玻璃；动效 120～180ms、面板 180～240ms，尊重 reduced-motion
- 状态只认 Java 真实结果：不照搬 setTimeout 演示，不展示思维链，只展示 availableActions，409 刷新事实，无 AI 置信度，预算≠进度，SSE 断开≠FAILED，API 不支持的操作不展示
- 先打磨调查中/等待审批/恢复验证三态（共用组件），先做故障详情再复用
