# 当前工作

更新时间：2026-09-27（本地）
仓库/分支：/root/projects/OpsPilot-V0.1-IMPLEMENTATION，main；HEAD 为 TASK-012 提交（紧随 625489e）；无 remote
当前业务任务：TASK-012 DONE（独立 Review PASS，已提交；提交只含 V002、两份 Schema 测试及 PROGRESS/CURRENT 中 TASK-012 部分）
当前文档工作：批次工作流随 docs(workflow) 提交入库；不属于 TASK-012 或 B01
下一批次：B01（013 → 014 → 015），TODO；Base SHA：未固定（实际开工时读取完整 HEAD）
任务内位置：TASK-012 已收尾；流程文档提交后以该提交为 B01 Base SHA 开工
本地启动 Demo 配置：在 OPSPILOT_DB_* 环境变量基础上加 --spring.profiles.active=demo
TASK-012 原范围：infrastructure db/migration（V002）与 Schema 测试、docs/dev/
本轮文档范围：AGENTS、CLAUDE、START-HERE、Manifest、07/08 工作流、docs/dev；业务文件保持不变
本轮明确不做：领域模型（TASK-013）、状态转换仓储（TASK-014）、创建/开始调查用例（TASK-015/016）、调查事实表（TASK-021）

已完成：
- V002：incident（8 状态 CHECK、键格式、来源、resolved_at⇔RESOLVED、04 §89 三个索引、lock_version）；incident_affected_resource（复合主键＋资源索引）；
  investigation（04 §16 全部字段，UNIQUE(incident_id)，04 §98：run_no≥1、本轮计数≤快照上限、stop 时间与身份同空同非空，限制值≥1，无默认限制值）；
  incident_timeline_event（只追加，INDEX(incident_id,id)，actor_type USER/SYSTEM/AI_RUNTIME，payload 内含 schemaName/schemaVersion，用 IS TRUE 防止 NULL 放行）
- SystemIntegrationSchemaTest 断言放宽为“V001 已应用＋5 表存在”，以容纳后续迁移

未提交修改：无（工作流文档随 docs(workflow) 提交）

已执行验证：见 PROGRESS TASK-012 行
本轮文档验证：diff --check、批次覆盖/依赖、原Task正文未变、进度映射、475个本地链接、既有代码哈希均通过；见 PROGRESS“工作流文档变更”
未执行验证：Windows mvnw.cmd NOT VERIFIED；MySQL 8.0.16 NOT VERIFIED
新依赖：无；Migration：V002（尚未应用于任何持久库）

当前阻塞：无

下一步具体动作：
1. TASK-012 已完成；工作流文档已单独提交。
2. 上述收尾后按 BATCH-PLAN 开始 B01：核实批外前置，记录完整 base SHA、允许目录、不变量及验证矩阵到 CURRENT/PROGRESS。
3. 批内依次实现 013 → 014 → 015，逐项针对性验证；批尾完整验证后整批独立 Review，未通过/未提交不进入 B02。
4. 续接入口：BATCH-PLAN §1/§4/§5、PROGRESS 的 B01 记录及 08 对应 Task；本轮未运行业务构建，不新增业务验证结论。

后续 UI 约定（TASK-096/099 实施）：
- 底座 React＋TypeScript＋Vite＋Tailwind CSS＋shadcn/ui；Motion 仅在需要布局动画时引入；单一图标库；单一锁文件（npm）
- beautifului/beui/transitions 只借组织方式与过渡，不整套复制；Rare UI 第一版不用其源码；复制 MIT 代码保留声明
- 故障详情主体：当前影响→当前状态→当前判断→判断依据→处理建议→恢复情况；宽屏时间线置侧，技术信息折叠或进抽屉
- 浅色、中性灰文本、小面积语义色；不用巨型标题、满屏渐变、大片玻璃；动效 120～180ms、面板 180～240ms，尊重 reduced-motion
- 状态只认 Java 真实结果：不照搬 setTimeout 演示，不展示思维链，只展示 availableActions，409 刷新事实，无 AI 置信度，预算≠进度，SSE 断开≠FAILED，API 不支持的操作不展示
- 先打磨调查中/等待审批/恢复验证三态（共用组件），先做故障详情再复用
