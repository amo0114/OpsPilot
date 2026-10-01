# 当前工作

更新时间：2026-10-01（22:58 +08:00 独立验证，B33-R2 PASS）
仓库/分支：/root/projects/OpsPilot-V0.1-IMPLEMENTATION，main；无 remote
当前批次/状态：B33 REVIEW（B33-R2 PASS，R1 三项均已解决；B33-V2 exit 0；已通过，待用户授权提交）
成员 Task 及顺序：B33 = TASK-090 → 091 → 092
固定 Base SHA：0afaeccf515fad4fa7514362a3356e6852ab36bb
批外前置核实：TASK-089 DONE（addb5df，B32-R1 PASS）
允许目录 / 明确不做 / 关键不变量：见 PROGRESS「B33」范围；不做真实注入器（093～095）、Ground Truth 公开读取、UI、新依赖
本批规格章节及 PROGRESS 记录：PROGRESS「B33」
当前成员及位置：090～092 均 REVIEW；B33-R2 PASS，独立专项 36/36 通过；B33-V2 clean verify exit 0 的实际日志及报告已核验；1 项非阻塞 P3：Reset 竞争测试的在途窗口应通过 latch 固定
已实现并针对性验证的成员：B01～B30 全部（DONE），B31 的 084～086（DONE，09d085e；TASK-085 P3 修复 d6c5255），B32 的 087～089（DONE，addb5df），及 B13 前的独立修复；恢复控制流（批准→执行→核对→验证→结果迁移→启动恢复）已闭合，真实场景验收待 TASK-105～109
未完成 / 未执行验证：待用户授权提交及回填 SHA；非阻塞 P3 详见 B33-R2；本 Reviewer 未重跑完整 clean verify 或变异检查；真实注入器/Gate（093～095）NOT RUN；Fault Harness 日志源分离 NOT VERIFIED；05 §45～§48 独立 GET 无归属 Task（待确认）；真实浏览器 EventSource 重连、代理空闲断开、真实 Provider 恢复采样与 S3 端到端 NOT RUN/NOT VERIFIED；CCG 门禁工具本机缺失；其余见 PROGRESS 待处理问题；Redis < 7.2、MySQL 8.0.16、Windows mvnw.cmd NOT VERIFIED
未提交文件（含既有无关修改）：开工时无；本批修改（含未跟踪新文件与 V007 迁移）见 git status 与 PROGRESS「B33」
共同验证及独立 Review 证据编号：B33-V1/R1 保留历史；B33-V2（exit 0，修复后）＋B33-R2 PASS；独立日志 /tmp/b33-r2-targeted.log，受审代码清单 /tmp/b33-r2-code.sha256
下一步具体动作：
1. B33-R2 已 PASS；按用户授权提交本批全部改动（含未跟踪文件与 V007），回填真实 SHA 后将 B33 与 090～092 一起标 DONE；更正记录用新提交，不提前开始 B34。若处理非阻塞测试 P3，重跑受影响回归并更新证据
2. 不推送

本地启动 Demo 配置：在 OPSPILOT_DB_* 环境变量基础上加 --spring.profiles.active=demo

后续 UI 约定（TASK-096/099 实施）：
- 底座 React＋TypeScript＋Vite＋Tailwind CSS＋shadcn/ui；Motion 仅在需要布局动画时引入；单一图标库；单一锁文件（npm）
- beautifului/beui/transitions 只借组织方式与过渡，不整套复制；Rare UI 第一版不用其源码；复制 MIT 代码保留声明
- 故障详情主体：当前影响→当前状态→当前判断→判断依据→处理建议→恢复情况；宽屏时间线置侧，技术信息折叠或进抽屉
- 浅色、中性灰文本、小面积语义色；不用巨型标题、满屏渐变、大片玻璃；动效 120～180ms、面板 180～240ms，尊重 reduced-motion
- 状态只认 Java 真实结果：不照搬 setTimeout 演示，不展示思维链，只展示 availableActions，409 刷新事实，无 AI 置信度，预算≠进度，SSE 断开≠FAILED，API 不支持的操作不展示
- 先打磨调查中/等待审批/恢复验证三态（共用组件），先做故障详情再复用
