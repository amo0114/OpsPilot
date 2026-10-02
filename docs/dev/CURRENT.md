# 当前工作

更新时间：2026-10-02（本地，B36 DONE）
仓库/分支：/root/projects/OpsPilot-V0.1-IMPLEMENTATION，main；无 remote。靶场仓库 /root/projects/shortlink，Demo 改动在本地分支 opspilot-demo（9a87b40，不推送）
当前批次/状态：B36 DONE（OpsPilot 代码 b436fe7，ShortLink opspilot-demo 9a87b40，B36-R3 PASS）；B37 未开始
成员 Task 及顺序：B36 = TASK-095（DONE）；下一批 B37 = TASK-096～098（Web 基础壳、系统页与 Incident 列表）
固定 Base SHA：B36 为 OpsPilot 27d2f100df0ebab8067bcebd2ce4b4c1f7432584、ShortLink 5310a70e9aaaa3afad1e2cea80e69eb64f659179；B37 开工时读取当时 HEAD
批外前置核实：B37 开工时按 08 核对（TASK-095 已 DONE，b436fe7）
允许目录 / 明确不做 / 关键不变量：B37 开工时按 BATCH-PLAN 与 08 固定
本批规格章节及 PROGRESS 记录：最近完成：PROGRESS「B36」
当前成员及位置：无进行中批次
已实现并针对性验证的成员：B01～B36 全部 DONE；S1、S2、S3 真实注入/Gate/Reset 已在真实 ShortLink 靶场运行（B34-E1～E3、B35-E1/E2、B36-E1/E2），场景验收待 TASK-107～109
未完成 / 未执行验证：S1/S2 调查验收（107/108）、S3 完整闭环（109）、OpsPilot 入 Compose、Prometheus/Loki 与调查只读账号/Redis ACL（105/106）NOT RUN/NOT VERIFIED；B36 R2 修复后真实 ShortLink 场景与 ShortLink 自身门禁 NOT RUN；B33-R2 非阻塞 P3；05 §45～§48 独立 GET 无归属 Task（待确认）；真实浏览器 EventSource 重连、代理空闲断开、真实 Provider 恢复采样 NOT RUN；其余见 PROGRESS 待处理问题；Redis < 7.2、MySQL 8.0.16、Windows mvnw.cmd NOT VERIFIED
未提交文件（含既有无关修改）：无（本交接卡与 PROGRESS 回填随 docs(progress) 提交）；前端原型在仓库外 /root/projects/opspilot-ui-prototype/，不属于任何已提交批次
共同验证及独立 Review 证据编号：B36-V4（exit 0）、B36-E2；B36-R1/R2 NEEDS CHANGES → B36-R3 PASS
下一步具体动作：
1. B37（TASK-096～098）是前端批次，开工前先征得用户确认；按 BATCH-PLAN 固定基线，遵循下方 UI 约定
2. 复现 S2：先在靶场 `docker compose -f compose.dev.yaml --profile tools run --rm build mvn -B -ntp -Dspotless.apply.skip=true -Dspotless.check.skip=true -DskipTests -pl project -am clean package` → `SHORTLINK_REPO=/root/projects/shortlink docker compose -f deploy/demo/docker-compose.yml up -d --build --wait` → `python3 scripts/demo/shortlink_s3.py prepare` / `create-load --rate 5` → OpsPilot demo profile，设 OPSPILOT_FAULTLAB_SHORTLINK_MANAGEMENT_ENDPOINT=http://127.0.0.1:18081、OPSPILOT_FAULTLAB_SHORTLINK_ENDPOINT=http://localhost:18001、OPSPILOT_FAULTLAB_MYSQL_CONTROL_URL=jdbc:mysql://127.0.0.1:13317/、OPSPILOT_FAULTLAB_MYSQL_CONTROL_PASSWORD=fault_control_local_only（S1/S3 的变量见 PROGRESS B34/B35）；运行 Maven 测试前先 Compose down；长时间负载用 setsid 脱离调用 shell（工具 600 秒超时会连带终止）
3. 不推送

本地启动 Demo 配置：在 OPSPILOT_DB_* 环境变量基础上加 --spring.profiles.active=demo

后续 UI 约定（TASK-096/099 实施）：
- 底座 React＋TypeScript＋Vite＋Tailwind CSS＋shadcn/ui；Motion 仅在需要布局动画时引入；单一图标库；单一锁文件（npm）
- beautifului/beui/transitions 只借组织方式与过渡，不整套复制；Rare UI 第一版不用其源码；复制 MIT 代码保留声明
- 故障详情主体：当前影响→当前状态→当前判断→判断依据→处理建议→恢复情况；宽屏时间线置侧，技术信息折叠或进抽屉
- 浅色、中性灰文本、小面积语义色；不用巨型标题、满屏渐变、大片玻璃；动效 120～180ms、面板 180～240ms，尊重 reduced-motion
- 状态只认 Java 真实结果：不照搬 setTimeout 演示，不展示思维链，只展示 availableActions，409 刷新事实，无 AI 置信度，预算≠进度，SSE 断开≠FAILED，API 不支持的操作不展示
- 先打磨调查中/等待审批/恢复验证三态（共用组件），先做故障详情再复用
