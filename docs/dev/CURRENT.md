# 当前工作

更新时间：2026-10-02（本地，B34 REVIEW，B34-R3 PASS，待授权提交）
仓库/分支：/root/projects/OpsPilot-V0.1-IMPLEMENTATION，main；无 remote。靶场仓库 /root/projects/shortlink，本批在本地分支 opspilot-demo（不推送）
当前批次/状态：B34 REVIEW（B34-R3 PASS，无剩余提交前阻塞项；待用户授权提交）
成员 Task 及顺序：B34 = TASK-093（S3 Statistics Consumer Stop 真实注入）
固定 Base SHA：OpsPilot 2e4adebaea37bc15ab89f396b9ed782f26c41e29；ShortLink da887dc54ddd023e4e772251715e84d0fd4af17a
批外前置核实：TASK-092 DONE（e296fd6，B33-R2 PASS）
允许目录 / 明确不做 / 关键不变量：见 PROGRESS「B34」范围
本批规格章节及 PROGRESS 记录：08 TASK-093；09 §3～§22、§60～§70；05 §68～§73；04 §62～§64；PROGRESS「B34」
当前成员及位置：TASK-093 REVIEW；R1-01～04、R2-01 与 TASK-092 死锁修复均已独立复核通过
已实现并针对性验证的成员：TASK-093（B34-V3 完整 verify exit 0、B34-E3 真实 ShortLink 证据已核验；B34-R3 独立 verify 107/107、真实 HTTP 正文悬挂/响应头超时/中断复测通过）；B01～B33 全部 DONE
未完成 / 未执行验证：双仓库授权提交与 SHA 回填；R3 未重跑完整 clean verify 或 ShortLink 场景，复用并核验 B34-V3/E3；S3 完整闭环（TASK-109）、OpsPilot 入 Compose（TASK-105）、Loki 选择器层面的控制日志分离（TASK-105）NOT RUN/NOT VERIFIED；B33-R2 非阻塞 P3（测试时序）；05 §45～§48 独立 GET 无归属 Task（待确认）；真实浏览器 EventSource 重连、代理空闲断开、真实 Provider 恢复采样 NOT RUN；Redis < 7.2、MySQL 8.0.16、Windows mvnw.cmd NOT VERIFIED
未提交文件（含既有无关修改）：OpsPilot——见 PROGRESS「B34」修改文件（backend 17 个修改＋新增 faultlab/provider/测试文件、deploy/demo/、scripts/demo/、docs/dev 两份）；ShortLink 分支 opspilot-demo——.dockerignore、project/Dockerfile、application-demo.yaml、shardingsphere-config-demo.yaml、README.md。无既有无关修改
共同验证及独立 Review 证据编号：B34-V3（exit 0，最终代码）、B34-E3；B34-V1/V2、E1/E2 为修复前；B34-R1、R2 NEEDS CHANGES（均已关闭）；B34-R3 PASS（/tmp/b34-r3-targeted.log、/tmp/b34-r3-http-deadline.log；代码/配置哈希清单见 PROGRESS）
下一步具体动作：
1. 等待用户授权分别提交已通过 B34-R3 的 ShortLink opspilot-demo 分支与 OpsPilot；固定基线及受审树清单见 PROGRESS
2. 获授权后提交并回填两个真实 SHA，再将 B34/TASK-093 标 DONE；此前不提交、不开始 B35，不推送
3. 复现环境：`SHORTLINK_REPO=/root/projects/shortlink docker compose -f deploy/demo/docker-compose.yml up -d --build --wait`（先在靶场打包 project）→ `python3 scripts/demo/shortlink_s3.py prepare` / `load` → OpsPilot 以 demo profile 运行并设 OPSPILOT_FAULTLAB_REDIS_ENDPOINT=redis://127.0.0.1:16380、OPSPILOT_FAULTLAB_S3_PROBE_URLS=<prepare 输出的短链>

本地启动 Demo 配置：在 OPSPILOT_DB_* 环境变量基础上加 --spring.profiles.active=demo

后续 UI 约定（TASK-096/099 实施）：
- 底座 React＋TypeScript＋Vite＋Tailwind CSS＋shadcn/ui；Motion 仅在需要布局动画时引入；单一图标库；单一锁文件（npm）
- beautifului/beui/transitions 只借组织方式与过渡，不整套复制；Rare UI 第一版不用其源码；复制 MIT 代码保留声明
- 故障详情主体：当前影响→当前状态→当前判断→判断依据→处理建议→恢复情况；宽屏时间线置侧，技术信息折叠或进抽屉
- 浅色、中性灰文本、小面积语义色；不用巨型标题、满屏渐变、大片玻璃；动效 120～180ms、面板 180～240ms，尊重 reduced-motion
- 状态只认 Java 真实结果：不照搬 setTimeout 演示，不展示思维链，只展示 availableActions，409 刷新事实，无 AI 置信度，预算≠进度，SSE 断开≠FAILED，API 不支持的操作不展示
- 先打磨调查中/等待审批/恢复验证三态（共用组件），先做故障详情再复用
