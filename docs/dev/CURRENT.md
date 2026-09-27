# 当前工作

更新时间：2026-09-27（本地）
仓库/分支：/root/projects/OpsPilot-V0.1-IMPLEMENTATION，main；无 remote
当前批次/状态：B02 REVIEW（B02-R1 PASS，提交中）
成员 Task 及顺序：TASK-016 → TASK-017（均 REVIEW）
固定 Base SHA：cb694401f955ecf6a63202c19ad900dbeec38e18
批外前置核实：TASK-015 DONE（ced26e0）；B01 DONE
允许目录 / 明确不做 / 关键不变量：见 PROGRESS「B02」记录
本批规格章节及 PROGRESS 记录：PROGRESS「B02」
当前成员及位置：全部成员实现完成；B02-R1 PASS
已实现并针对性验证的成员：TASK-016（Start 4 例＋变异）；TASK-017（Continue 3 例＋InvestigationRunTest＋变异）
未完成 / 未执行验证：真实派发/Worker NOT RUN（TASK-035 起）；MySQL 8.0.16、Windows mvnw.cmd NOT RUN
未提交文件（含既有无关修改）：全部为 B02 改动，见 PROGRESS「B02」本批修改文件（含未跟踪目录）；无既有无关修改
共同验证及独立 Review 证据编号：B02-V1（backend `./mvnw -B clean verify` exit 0：domain 23、infrastructure 198、web 21）；B02-R1 PASS（无 P0/P1）
后续任务（已记入 PROGRESS 待处理问题，本批未实现）：TASK-035 真实派发替换 DeferredWorkDispatcher；TASK-036～043 调查循环、Guard、AgentStep、终止与启动恢复；TASK-062/066/067 真实 PENDING Approval 复核；TASK-082 VerificationFailed 进入新 run
下一步具体动作：
1. 独立 Reviewer 按 BATCH-PLAN §2 审查 cb69440 到当前工作树（git status --short、git diff cb69440、git ls-files --others --exclude-standard）
2. Review PASS 且获用户提交授权后提交（建议标题 feat(investigation): start and continue investigation runs (TASK-016–017)），回填 SHA，B02 与成员一起 DONE
3. 未提交/未 DONE 前不开始 B03
本地启动 Demo 配置：在 OPSPILOT_DB_* 环境变量基础上加 --spring.profiles.active=demo

后续 UI 约定（TASK-096/099 实施）：
- 底座 React＋TypeScript＋Vite＋Tailwind CSS＋shadcn/ui；Motion 仅在需要布局动画时引入；单一图标库；单一锁文件（npm）
- beautifului/beui/transitions 只借组织方式与过渡，不整套复制；Rare UI 第一版不用其源码；复制 MIT 代码保留声明
- 故障详情主体：当前影响→当前状态→当前判断→判断依据→处理建议→恢复情况；宽屏时间线置侧，技术信息折叠或进抽屉
- 浅色、中性灰文本、小面积语义色；不用巨型标题、满屏渐变、大片玻璃；动效 120～180ms、面板 180～240ms，尊重 reduced-motion
- 状态只认 Java 真实结果：不照搬 setTimeout 演示，不展示思维链，只展示 availableActions，409 刷新事实，无 AI 置信度，预算≠进度，SSE 断开≠FAILED，API 不支持的操作不展示
- 先打磨调查中/等待审批/恢复验证三态（共用组件），先做故障详情再复用
