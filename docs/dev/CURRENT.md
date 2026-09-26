# 当前工作

更新时间：2026-09-26（本地）
仓库/分支：/root/projects/OpsPilot-V0.1-IMPLEMENTATION，main；HEAD 为 TASK-007 提交（紧随 0a0818b）；无 remote
当前任务：TASK-007 DONE（独立 Review PASS，已提交）；TASK-008 READY，未开始
任务内位置：等待用户指示开始 TASK-008
本轮允许修改：domain/system、application/system、infrastructure persistence/mybatis/system 及其测试、docs/dev/
本轮明确不做：Codec 与具体选择器类型（TASK-008）；SecretResolver/credentialRef 格式解析（TASK-009）；Seed（TASK-010）；HTTP（TASK-011）；CapabilityRegistry 与 Provider 解析（TASK-044/046）；写入方法

已完成：
- domain.system：ProviderType（PROMETHEUS/LOKI/REDIS/MYSQL/DOCKER）、ConnectionStatus（ACTIVE/DISABLED/ARCHIVED）；
  SelectorSchema、ConfigSchema（name 非空、version>=1）；DataSourceConnection（credentialRef 可空、configSchema＋未解码 configPayload、isActive）；
  ResourceBinding（selectorSchema＋未解码 selectorPayload）；CapabilityBinding（capabilityKey String、enabled）；均为不可变 record，不依赖 Spring
- application.system：DataSourceConnectionRepository.findById / findByConnectionKey；ResourceBindingRepository.findAllByResourceId（按连接 id 升序，不按连接状态过滤）；
  CapabilityBindingRepository.findByResourceIdAndCapabilityKey / findAllByResourceId（按 key 升序）
- infrastructure：3 个 @Mapper＋XML（构造器 resultMap，INT UNSIGNED→_int，BOOLEAN→_boolean，JSON 列按 String 读取）；
  connection_key、capability_key 查找在索引等值外追加 CAST(... AS BINARY) 精确比较（列为 ai_ci）
- 测试：MyBatisBindingRepositoryTest（@SpringBootTest＋Testcontainers mysql:8.4.11＋@Transactional 回滚）

TASK-007 提交内容：代码均为新增文件（domain/system 7 个、application/system 3 个 Port、infrastructure 3 Mapper＋3 Row＋3 仓储＋3 XML、MyBatisBindingRepositoryTest）；docs/dev/PROGRESS.md、CURRENT.md 为修改

已执行验证：见 PROGRESS TASK-007 行
未执行验证：Windows mvnw.cmd NOT VERIFIED；MySQL 8.0.16 NOT VERIFIED（boot jar 启动由独立 Review 实测 health 200）
新依赖：无；Migration：无（沿用 V001）

未提交修改：无（TASK-007 已提交）
当前阻塞：无

下一步具体动作：
1. TASK-008 SchemaCodecRegistry：schemaName/schemaVersion → 强类型 Codec，首批 Prometheus/Loki/Redis/MySql/Docker ResourceBindingV1
2. 需读取：08 TASK-008；04 §67～§69；07 §96～§97；06 §19；未知版本拒绝，不以 Map 作为应用层协议
3. 遗留：system_key/resource_key 查找须在接入外部输入前（TASK-011/040）改为按字节精确匹配

后续 UI 约定（TASK-096/099 实施）：
- 底座 React＋TypeScript＋Vite＋Tailwind CSS＋shadcn/ui；Motion 仅在需要布局动画时引入；单一图标库；单一锁文件（npm）
- beautifului/beui/transitions 只借组织方式与过渡，不整套复制；Rare UI 第一版不用其源码；复制 MIT 代码保留声明
- 故障详情主体：当前影响→当前状态→当前判断→判断依据→处理建议→恢复情况；宽屏时间线置侧，技术信息折叠或进抽屉
- 浅色、中性灰文本、小面积语义色；不用巨型标题、满屏渐变、大片玻璃；动效 120～180ms、面板 180～240ms，尊重 reduced-motion
- 状态只认 Java 真实结果：不照搬 setTimeout 演示，不展示思维链，只展示 availableActions，409 刷新事实，无 AI 置信度，预算≠进度，SSE 断开≠FAILED，API 不支持的操作不展示
- 先打磨调查中/等待审批/恢复验证三态（共用组件），先做故障详情再复用
