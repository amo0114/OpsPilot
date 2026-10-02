# 当前工作

更新时间：2026-10-02（本地，B35-R2 PASS，待授权提交）
仓库/分支：/root/projects/OpsPilot-V0.1-IMPLEMENTATION，main；无 remote。靶场仓库 /root/projects/shortlink，Demo 改动在本地分支 opspilot-demo（不推送）
当前批次/状态：B35 REVIEW（B35-R2 PASS，无剩余提交前阻塞项；待用户授权提交）
成员 Task 及顺序：B35 = TASK-094（S1 Redis Latency 真实注入）
固定 Base SHA：OpsPilot 210bbbda03d556e9765f947c5cfe56bed3464ba9；ShortLink opspilot-demo 5310a70e9aaaa3afad1e2cea80e69eb64f659179
批外前置核实：TASK-093 DONE（c8b7904，B34-R3 PASS）
允许目录 / 明确不做 / 关键不变量：见 PROGRESS「B35」范围
本批规格章节及 PROGRESS 记录：08 TASK-094；09 §9、§13～§22、§28～§41；PROGRESS「B35」
当前成员及位置：TASK-094 REVIEW；R1-01 已经 B35-R2 独立复核关闭
已实现并针对性验证的成员：TASK-094（B35-V2/E2 证据已核验；B35-R2 独立 verify 127/127、真实 HTTP 慢成功拒绝与无内存基线 Reset 复测通过）；B01～B34 全部 DONE
未完成 / 未执行验证：授权提交与 SHA 回填；R2 完整 clean verify/完整 ShortLink 场景/实际 JVM 重启 NOT RUN，已核验 B35-V2/E2；S1 调查验收（TASK-107）、OpsPilot 入 Compose 与 cache.inspect 经代理的真实调用（TASK-105）、Prometheus/Loki 与 Redis ACL（105/106）NOT RUN；S3 完整闭环（TASK-109）NOT RUN；B33-R2 非阻塞 P3；05 §45～§48 独立 GET 无归属 Task（待确认）；真实浏览器 EventSource 重连、代理空闲断开、真实 Provider 恢复采样 NOT RUN；Redis < 7.2、MySQL 8.0.16、Windows mvnw.cmd NOT VERIFIED
未提交文件（含既有无关修改）：OpsPilot——见 PROGRESS「B35」修改文件（backend 16 个修改＋11 个新增、deploy/demo 两处、docs/dev 两份）；ShortLink 无改动。无既有无关修改
共同验证及独立 Review 证据编号：B35-V2（exit 0，最终代码）、B35-E2；B35-V1/E1 为修复前；B35-R1 NEEDS CHANGES（已关闭）；B35-R2 PASS（/tmp/b35-r2-targeted.log、/tmp/b35-r2-probes.log；受审文件哈希见 PROGRESS）
下一步具体动作：
1. 等待用户授权提交已通过 B35-R2 的 OpsPilot 工作树；ShortLink 无需提交
2. 获授权后提交并回填真实 SHA，再将 B35/TASK-094 标 DONE；此前不开始 B36，不推送
3. 复现 S1：`SHORTLINK_REPO=/root/projects/shortlink docker compose -f deploy/demo/docker-compose.yml up -d --build --wait` → `python3 scripts/demo/shortlink_s3.py prepare` / `load --rate 15` → OpsPilot demo profile，设 OPSPILOT_FAULTLAB_REDIS_ENDPOINT=redis://127.0.0.1:16380、OPSPILOT_FAULTLAB_PROXY_REDIS_ENDPOINT=redis://127.0.0.1:16381、OPSPILOT_FAULTLAB_TOXIPROXY_ENDPOINT=http://127.0.0.1:18474、OPSPILOT_FAULTLAB_S3_PROBE_URLS=<prepare 输出的短链>；运行 Maven 测试前先 `docker compose ... down`（本机内存不足以同时承载 Demo 栈与 Testcontainers）

本地启动 Demo 配置：在 OPSPILOT_DB_* 环境变量基础上加 --spring.profiles.active=demo

后续 UI 约定（TASK-096/099 实施）：
- 底座 React＋TypeScript＋Vite＋Tailwind CSS＋shadcn/ui；Motion 仅在需要布局动画时引入；单一图标库；单一锁文件（npm）
- beautifului/beui/transitions 只借组织方式与过渡，不整套复制；Rare UI 第一版不用其源码；复制 MIT 代码保留声明
- 故障详情主体：当前影响→当前状态→当前判断→判断依据→处理建议→恢复情况；宽屏时间线置侧，技术信息折叠或进抽屉
- 浅色、中性灰文本、小面积语义色；不用巨型标题、满屏渐变、大片玻璃；动效 120～180ms、面板 180～240ms，尊重 reduced-motion
- 状态只认 Java 真实结果：不照搬 setTimeout 演示，不展示思维链，只展示 availableActions，409 刷新事实，无 AI 置信度，预算≠进度，SSE 断开≠FAILED，API 不支持的操作不展示
- 先打磨调查中/等待审批/恢复验证三态（共用组件），先做故障详情再复用
