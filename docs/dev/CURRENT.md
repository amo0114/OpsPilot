# 当前工作

更新时间：2026-09-27（本地）
仓库/分支：/root/projects/OpsPilot-V0.1-IMPLEMENTATION，main；无 remote
当前批次/状态：B03 REVIEW（B03-R2 PASS，提交中）
成员 Task 及顺序：TASK-018 → TASK-019 → TASK-020（均 REVIEW）
固定 Base SHA：4630f27ee3aece7138c79338ee39042c427f3287
批外前置核实：TASK-017 DONE（c0deb3c）；B02 DONE
允许目录 / 明确不做 / 关键不变量：见 PROGRESS「B03」记录
本批规格章节及 PROGRESS 记录：PROGRESS「B03」
当前成员及位置：B03-R1 P1（expectedVersion 小数截断）已修复并验证；等待复核
已实现并针对性验证的成员：TASK-018（Stop 2 例＋变异）；TASK-019（Cancel 5 例＋变异）；TASK-020（HTTP 契约 2 例＋变异）
未完成 / 未执行验证：真实审批取消联动（TASK-067）、Stop 收束与迟到结果（TASK-039～043）NOT RUN；MySQL 8.0.16、Windows mvnw.cmd NOT RUN
未提交文件（含既有无关修改）：全部为 B03 改动，见 PROGRESS「B03」本批修改文件（含未跟踪目录）；无既有无关修改
共同验证及独立 Review 证据编号：B03-V1；B03-R1 PASS AFTER PATCH（P1）；B03-V2 修复后全量 verify exit 0（domain 23、infrastructure 205、web 21、boot 2）；B03-R2 PASS
下一步具体动作：
1. 独立 Reviewer 复核 P1 修复（ApiJsonConfiguration 关闭 ACCEPT_FLOAT_AS_INT、IncidentApiContractTest 小数反例），基线仍为 4630f27
2. Review PASS 且获用户提交授权后提交（建议标题 feat(incident): stop, cancel and basic incident API (TASK-018–020)），回填 SHA，B03 与成员一起 DONE
3. 未提交/未 DONE 前不开始 B04
本地启动 Demo 配置：在 OPSPILOT_DB_* 环境变量基础上加 --spring.profiles.active=demo

后续 UI 约定（TASK-096/099 实施）：
- 底座 React＋TypeScript＋Vite＋Tailwind CSS＋shadcn/ui；Motion 仅在需要布局动画时引入；单一图标库；单一锁文件（npm）
- beautifului/beui/transitions 只借组织方式与过渡，不整套复制；Rare UI 第一版不用其源码；复制 MIT 代码保留声明
- 故障详情主体：当前影响→当前状态→当前判断→判断依据→处理建议→恢复情况；宽屏时间线置侧，技术信息折叠或进抽屉
- 浅色、中性灰文本、小面积语义色；不用巨型标题、满屏渐变、大片玻璃；动效 120～180ms、面板 180～240ms，尊重 reduced-motion
- 状态只认 Java 真实结果：不照搬 setTimeout 演示，不展示思维链，只展示 availableActions，409 刷新事实，无 AI 置信度，预算≠进度，SSE 断开≠FAILED，API 不支持的操作不展示
- 先打磨调查中/等待审批/恢复验证三态（共用组件），先做故障详情再复用
