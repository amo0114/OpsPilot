# 当前工作

更新时间：2026-10-02（本地，B34 DONE）
仓库/分支：/root/projects/OpsPilot-V0.1-IMPLEMENTATION，main；无 remote。靶场仓库 /root/projects/shortlink，Demo 改动在本地分支 opspilot-demo（不推送）
当前批次/状态：B34 DONE（代码 c8b7904，ShortLink 5310a70，B34-R3 PASS）；B35 未开始
成员 Task 及顺序：B34 = TASK-093（DONE）；下一批 B35 = TASK-094（S1 Redis Latency 真实注入）
固定 Base SHA：B34 为 OpsPilot 2e4adebaea37bc15ab89f396b9ed782f26c41e29、ShortLink da887dc54ddd023e4e772251715e84d0fd4af17a；B35 开工时读取当时 HEAD（ShortLink 以 opspilot-demo 分支当时 HEAD 为基线）
批外前置核实：B35 开工时按 08 核对（TASK-093 已 DONE，c8b7904）
允许目录 / 明确不做 / 关键不变量：B35 开工时按 BATCH-PLAN 与 08 固定
本批规格章节及 PROGRESS 记录：最近完成：PROGRESS「B34」
当前成员及位置：无进行中批次
已实现并针对性验证的成员：B01～B34 全部 DONE；S3 真实注入/Gate/Reset 已在真实 ShortLink 靶场运行（B34-E1～E3），S3 完整闭环验收待 TASK-109
未完成 / 未执行验证：S3 完整闭环（TASK-109）、OpsPilot 入 Compose 与 Loki 选择器层面的控制日志分离（TASK-105）NOT RUN/NOT VERIFIED；B33-R2 非阻塞 P3（Reset/注入竞争测试时序）；05 §45～§48 独立 GET 无归属 Task（待确认）；真实浏览器 EventSource 重连、代理空闲断开、真实 Provider 恢复采样 NOT RUN；其余见 PROGRESS 待处理问题；Redis < 7.2、MySQL 8.0.16、Windows mvnw.cmd NOT VERIFIED
未提交文件（含既有无关修改）：无（本交接卡与 PROGRESS 回填随 docs(progress) 提交）
共同验证及独立 Review 证据编号：B34-V3（exit 0）、B34-E3；B34-R1/R2 NEEDS CHANGES → B34-R3 PASS
下一步具体动作：
1. 开始 B35（TASK-094 S1 Redis Latency：Toxiproxy 600ms 下行延迟、ShortLink 与 OpsPilot Redis Provider 经同一 Proxy、Gate 与 Reset），按 BATCH-PLAN 固定基线；扩展 deploy/demo Compose 与 ShortLink opspilot-demo 分支，开工前核对环境
2. 复现 S3 环境：`SHORTLINK_REPO=/root/projects/shortlink docker compose -f deploy/demo/docker-compose.yml up -d --build --wait`（先在靶场打包 project）→ `python3 scripts/demo/shortlink_s3.py prepare` / `load` → OpsPilot 以 demo profile 运行并设 OPSPILOT_FAULTLAB_REDIS_ENDPOINT=redis://127.0.0.1:16380、OPSPILOT_FAULTLAB_S3_PROBE_URLS=<prepare 输出的短链>
3. 不推送

本地启动 Demo 配置：在 OPSPILOT_DB_* 环境变量基础上加 --spring.profiles.active=demo

后续 UI 约定（TASK-096/099 实施）：
- 底座 React＋TypeScript＋Vite＋Tailwind CSS＋shadcn/ui；Motion 仅在需要布局动画时引入；单一图标库；单一锁文件（npm）
- beautifului/beui/transitions 只借组织方式与过渡，不整套复制；Rare UI 第一版不用其源码；复制 MIT 代码保留声明
- 故障详情主体：当前影响→当前状态→当前判断→判断依据→处理建议→恢复情况；宽屏时间线置侧，技术信息折叠或进抽屉
- 浅色、中性灰文本、小面积语义色；不用巨型标题、满屏渐变、大片玻璃；动效 120～180ms、面板 180～240ms，尊重 reduced-motion
- 状态只认 Java 真实结果：不照搬 setTimeout 演示，不展示思维链，只展示 availableActions，409 刷新事实，无 AI 置信度，预算≠进度，SSE 断开≠FAILED，API 不支持的操作不展示
- 先打磨调查中/等待审批/恢复验证三态（共用组件），先做故障详情再复用
