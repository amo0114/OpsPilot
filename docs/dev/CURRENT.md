# 当前工作

更新时间：2026-09-28（本地）
仓库/分支：/root/projects/OpsPilot-V0.1-IMPLEMENTATION，main；无 remote
当前批次/状态：B14 REVIEW（B14-R2 PASS，提交中）
成员 Task 及顺序：B14 = TASK-047 → TASK-048
固定 Base SHA：711c6db0384ce41206f02f871e3284034ccf0fa3
批外前置核实：TASK-046 DONE（36fd25a，B13-R2 PASS）；开工时工作树干净
允许目录 / 明确不做 / 关键不变量：见 PROGRESS「B14」（Canonical JSON、Duplicate Guard、OBSERVE 准入与结果事务、执行骨架；不做 Sanitizer/RawResult/Extractor/真实 Provider/编排器接入/恢复采样准入）
本批规格章节及 PROGRESS 记录：PROGRESS「B14」
当前成员及位置：TASK-047、TASK-048 均 REVIEW；B14-R1 P2（INCIDENT_CONTEXT 窗口未在准入解析、迟到结果被报告为成功）已修复
已实现并针对性验证的成员：TASK-047～048（B14-V1、B14-V2 verify exit 0）
未完成 / 未执行验证：B14-R2；真实进程冒烟 NOT RUN（执行服务尚未接入调查循环，属 TASK-058）；其余 NOT RUN 与交接事项见 PROGRESS「B14」及待处理问题
未提交文件（含既有无关修改）：见 PROGRESS「B14」本批修改文件（12 个修改＋28 个未跟踪 backend 文件，含 B14-R1 修复新增 4 个）、docs/dev/CURRENT.md、docs/dev/PROGRESS.md；无既有无关修改
共同验证及独立 Review 证据编号：B14-V1、B14-V2（完成）；B14-R1（2 个 P2，已修复）；B14-R2（NOT RUN）
下一步具体动作：
1. 独立 Reviewer 复审 Base 711c6db 到当前工作树（重点 WindowResolver、准入窗口校验与 Discarded 返回）
2. PASS 后按用户授权提交（建议 `feat(capability): canonical json, duplicate guard and observe admission skeleton (TASK-047–048)`），回填 SHA 后批次与成员标 DONE
3. 不推送；未获指示不开始 B15
本地启动 Demo 配置：在 OPSPILOT_DB_* 环境变量基础上加 --spring.profiles.active=demo

后续 UI 约定（TASK-096/099 实施）：
- 底座 React＋TypeScript＋Vite＋Tailwind CSS＋shadcn/ui；Motion 仅在需要布局动画时引入；单一图标库；单一锁文件（npm）
- beautifului/beui/transitions 只借组织方式与过渡，不整套复制；Rare UI 第一版不用其源码；复制 MIT 代码保留声明
- 故障详情主体：当前影响→当前状态→当前判断→判断依据→处理建议→恢复情况；宽屏时间线置侧，技术信息折叠或进抽屉
- 浅色、中性灰文本、小面积语义色；不用巨型标题、满屏渐变、大片玻璃；动效 120～180ms、面板 180～240ms，尊重 reduced-motion
- 状态只认 Java 真实结果：不照搬 setTimeout 演示，不展示思维链，只展示 availableActions，409 刷新事实，无 AI 置信度，预算≠进度，SSE 断开≠FAILED，API 不支持的操作不展示
- 先打磨调查中/等待审批/恢复验证三态（共用组件），先做故障详情再复用
