# 当前工作

更新时间：2026-10-01（20:26 +08:00，TASK-085-FIX-R2 PASS）
仓库/分支：/root/projects/OpsPilot-V0.1-IMPLEMENTATION，main；无 remote
当前批次/状态：TASK-085 修复（B31-R1 P3，独立修复）REVIEW：FIX-R2 PASS，已通过，待用户授权提交；B32 DONE（addb5df）；B33 未开始
成员 Task 及顺序：B32 = TASK-087 → 088 → 089（均 DONE）；下一批 B33 = TASK-090 → 091 → 092
固定 Base SHA：TASK-085 修复为 4144e44fa9b62a3481facfae2f6c37379f5ad8a4
批外前置核实：B33 开工时按 08 核对其成员的批外前置
允许目录 / 明确不做 / 关键不变量：本独立修复仅 web/incident/IncidentResponses、对应 JSON 测试与 docs/dev；数值不变，只改表示，必须正确排除 long 上界 2^63；不重开 B31、不开始 B33
本批规格章节及 PROGRESS 记录：PROGRESS「TASK-085 修复 — 详情恢复样本整数格式化（B31-R1 P3）」
当前成员及位置：TASK-085 修复：整数表示及 FIX-R1 P2 已通过 FIX-R2 复审；独立重跑 web 52、domain 43、boot 4＋1，Enforcer/Spotless 与 diff 检查通过
已实现并针对性验证的成员：B01～B30 全部（DONE），B31 的 084～086（DONE，09d085e），B32 的 087～089（DONE，addb5df），及 B13 前的独立修复；恢复控制流（批准→执行→核对→验证→结果迁移→启动恢复）已闭合，真实场景验收待 TASK-105～109
未完成 / 未执行验证：TASK-085 独立修复待用户授权提交及回填 SHA；完整 clean verify、infrastructure 测试与 CCG 本修复 NOT RUN；05 §45～§48 独立 GET 无归属 Task（待确认）；真实浏览器 EventSource 重连、代理空闲断开、真实 Provider 恢复采样与 S3 端到端 NOT RUN/NOT VERIFIED；CCG 门禁工具本机缺失；其余见 PROGRESS 待处理问题；Redis < 7.2、MySQL 8.0.16、Windows mvnw.cmd NOT VERIFIED
未提交文件（含既有无关修改）：开工时无；本修复：backend/opspilot-web/.../web/incident/IncidentResponses.java（改）、opspilot-web/src/test/.../web/incident/IncidentDetailJsonTest.java（新增，未跟踪）、docs/dev
共同验证及独立 Review 证据编号：TASK-085-FIX-R2 PASS；日志 /tmp/task085-fix-r2-web.log、/tmp/task085-fix-r2-boot.log；范围、受测文件哈希与 NOT RUN 见 PROGRESS「TASK-085 修复」；保留 R1 历史记录
下一步具体动作：
1. FIX-R2 已 PASS；按用户授权提交 TASK-085 独立修复（含未跟踪测试及 docs/dev），回填真实 SHA 后将独立修复标 DONE；当前未提交，不提前 DONE
2. 修复提交后再开始 B33（TASK-090 → 091 → 092），按 BATCH-PLAN 固定基线
3. 不推送

本地启动 Demo 配置：在 OPSPILOT_DB_* 环境变量基础上加 --spring.profiles.active=demo

后续 UI 约定（TASK-096/099 实施）：
- 底座 React＋TypeScript＋Vite＋Tailwind CSS＋shadcn/ui；Motion 仅在需要布局动画时引入；单一图标库；单一锁文件（npm）
- beautifului/beui/transitions 只借组织方式与过渡，不整套复制；Rare UI 第一版不用其源码；复制 MIT 代码保留声明
- 故障详情主体：当前影响→当前状态→当前判断→判断依据→处理建议→恢复情况；宽屏时间线置侧，技术信息折叠或进抽屉
- 浅色、中性灰文本、小面积语义色；不用巨型标题、满屏渐变、大片玻璃；动效 120～180ms、面板 180～240ms，尊重 reduced-motion
- 状态只认 Java 真实结果：不照搬 setTimeout 演示，不展示思维链，只展示 availableActions，409 刷新事实，无 AI 置信度，预算≠进度，SSE 断开≠FAILED，API 不支持的操作不展示
- 先打磨调查中/等待审批/恢复验证三态（共用组件），先做故障详情再复用
