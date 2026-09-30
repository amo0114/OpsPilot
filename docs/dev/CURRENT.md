# 当前工作

更新时间：2026-09-30（本地，B27-R1 PASS）
仓库/分支：/root/projects/OpsPilot-V0.1-IMPLEMENTATION，main；无 remote
当前批次/状态：B27 REVIEW（B27-V1 通过，B27-R1 PASS，待提交）
成员 Task 及顺序：TASK-072 → TASK-073
固定 Base SHA：3c335fec5c15d234d12500433e6f238c8792b738
批外前置核实：TASK-071 DONE（96dc7cd）、TASK-069 DONE（90d80a2）、TASK-035 DONE（B09）
允许目录 / 明确不做 / 关键不变量：见 PROGRESS「B27」范围；不做 Verification 创建（080）、谓词/采样（077～079）、083、UI/SSE、新迁移、新依赖；核对绝不重发 CHANGE，先登记提交再 inspect，上限/deadline 不因重启刷新
本批规格章节及 PROGRESS 记录：PROGRESS「B27」
当前成员及位置：TASK-072、TASK-073 实现与针对性验证完成，均 REVIEW
已实现并针对性验证的成员：B01～B26 全部，及 B13 前的独立修复
未完成 / 未执行验证：B27 提交及 SHA 回填；执行成功后 Incident 停在 EXECUTING（080 创建 Verification）；ShortLink/S3 端到端、ai-runtime、真实 LLM NOT RUN；CCG 门禁工具本机缺失；其余见 PROGRESS 待处理问题；Redis < 7.2、MySQL 8.0.16、Windows mvnw.cmd NOT VERIFIED
未提交文件（含既有无关修改）：B27 全部改动（见 PROGRESS「B27」修改文件，含 8 个未跟踪文件与 1 个重命名）；无既有无关修改
共同验证及独立 Review 证据编号：B27-V1（clean verify exit 0）、B27-R1（PASS；独立重跑 46 tests 全通过）
下一步具体动作：
1. B27-R1 已 PASS；审查与实际验证已记入 PROGRESS，等待提交
2. 获用户授权后提交，回填 SHA，B27 与 TASK-072/073 一起 DONE；不开始 B28，不推送

本地启动 Demo 配置：在 OPSPILOT_DB_* 环境变量基础上加 --spring.profiles.active=demo

后续 UI 约定（TASK-096/099 实施）：
- 底座 React＋TypeScript＋Vite＋Tailwind CSS＋shadcn/ui；Motion 仅在需要布局动画时引入；单一图标库；单一锁文件（npm）
- beautifului/beui/transitions 只借组织方式与过渡，不整套复制；Rare UI 第一版不用其源码；复制 MIT 代码保留声明
- 故障详情主体：当前影响→当前状态→当前判断→判断依据→处理建议→恢复情况；宽屏时间线置侧，技术信息折叠或进抽屉
- 浅色、中性灰文本、小面积语义色；不用巨型标题、满屏渐变、大片玻璃；动效 120～180ms、面板 180～240ms，尊重 reduced-motion
- 状态只认 Java 真实结果：不照搬 setTimeout 演示，不展示思维链，只展示 availableActions，409 刷新事实，无 AI 置信度，预算≠进度，SSE 断开≠FAILED，API 不支持的操作不展示
- 先打磨调查中/等待审批/恢复验证三态（共用组件），先做故障详情再复用
