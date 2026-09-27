# 当前工作

更新时间：2026-09-27（本地）
仓库/分支：/root/projects/OpsPilot-V0.1-IMPLEMENTATION，main；无 remote
当前批次/状态：B01 REVIEW（B01-R1 PASS，提交中）
成员 Task 及顺序：TASK-013 → TASK-014 → TASK-015（均 REVIEW）
固定 Base SHA：5cc63e03c57b560184127ba94c4581e3e077dbff
批外前置核实：TASK-012 DONE（9d482d0）；工作流文档 5cc63e0
允许目录 / 明确不做 / 关键不变量：见 PROGRESS「B01」记录
本批规格章节及 PROGRESS 记录：PROGRESS「B01」
当前成员及位置：全部成员实现完成；B01-R1 PASS
已实现并针对性验证的成员：TASK-013（domain 22/22）；TASK-014（MyBatisIncidentTransitionTest 8/8）；TASK-015（CreateIncidentIntegrationTest 16/16、MyBatisSystemRepositoryTest 12/12）
未完成 / 未执行验证：boot jar 启动 NOT VERIFIED（本批无 HTTP/迁移变更）；MySQL 8.0.16、Windows mvnw.cmd NOT RUN
未提交文件（含既有无关修改）：全部为 B01 改动，见 PROGRESS「B01」本批修改文件（含未跟踪目录）；无既有无关修改
共同验证及独立 Review 证据编号：B01-V1（backend `./mvnw -B clean verify` exit 0：domain 22、infrastructure 191、web 21）；B01-R1 PASS（无 P0/P1）
下一步具体动作：
1. 独立 Reviewer 按 BATCH-PLAN §2 审查 5cc63e0 到当前工作树（git status --short、git diff 5cc63e0、git ls-files --others --exclude-standard）
2. Review PASS 且获用户提交授权后提交（建议标题 feat(incident): add incident creation and guarded transitions (TASK-013–015)），回填 SHA，B01 与成员一起 DONE
3. 未提交/未 DONE 前不开始 B02
本地启动 Demo 配置：在 OPSPILOT_DB_* 环境变量基础上加 --spring.profiles.active=demo

后续 UI 约定（TASK-096/099 实施）：
- 底座 React＋TypeScript＋Vite＋Tailwind CSS＋shadcn/ui；Motion 仅在需要布局动画时引入；单一图标库；单一锁文件（npm）
- beautifului/beui/transitions 只借组织方式与过渡，不整套复制；Rare UI 第一版不用其源码；复制 MIT 代码保留声明
- 故障详情主体：当前影响→当前状态→当前判断→判断依据→处理建议→恢复情况；宽屏时间线置侧，技术信息折叠或进抽屉
- 浅色、中性灰文本、小面积语义色；不用巨型标题、满屏渐变、大片玻璃；动效 120～180ms、面板 180～240ms，尊重 reduced-motion
- 状态只认 Java 真实结果：不照搬 setTimeout 演示，不展示思维链，只展示 availableActions，409 刷新事实，无 AI 置信度，预算≠进度，SSE 断开≠FAILED，API 不支持的操作不展示
- 先打磨调查中/等待审批/恢复验证三态（共用组件），先做故障详情再复用
