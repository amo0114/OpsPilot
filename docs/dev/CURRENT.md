# 当前工作

更新时间：2026-09-26（本地）
仓库/分支：/root/projects/OpsPilot-V0.1-IMPLEMENTATION，main；HEAD c804769（TASK-005）；无 remote
当前任务：TASK-006 DONE（独立 Review PASS，已提交）；TASK-007 READY，未开始
任务内位置：等待用户指示开始 TASK-007
本轮允许修改：domain/system、application/system、infrastructure persistence/mybatis/system 及其测试、infrastructure POM、docs/dev/
本轮明确不做：HTTP（TASK-011）；DataSourceConnection/ResourceBinding/CapabilityBinding（TASK-007）；Seed（TASK-010）；写入方法

已完成：
- domain.system：SystemStatus、ResourceStatus（ACTIVE/DISABLED/ARCHIVED）；ResourceType（6 种）；ManagedSystem、ManagedResource 为不可变 record（id、key、name、description 可空、environment/resourceType、status、version=lock_version），isActive()、ManagedResource.belongsTo(system)；不依赖 Spring
- application.system：ManagedSystemRepository.findBySystemKey；ManagedResourceRepository.findById / findBySystemIdAndResourceKey / findAllBySystemId（按 resource_key 升序）
- infrastructure.persistence.mybatis.system：@Mapper 接口＋同路径 XML（显式 SQL、构造器 resultMap，基本类型用 _long）；Row 保留原文，仓储用 Enum.valueOf 严格转换；@Repository 包内可见
- POM：infrastructure 加 mybatis-spring-boot-starter 4.1.0（compile）；测试依赖改为 spring-boot-starter-test、spring-boot-starter-flyway（junit/assertj 由 starter-test 提供）
- 测试根 InfrastructureTestApplication（@SpringBootApplication）；MyBatisSystemRepositoryTest（@SpringBootTest＋Testcontainers＋@Transactional 回滚）

已执行验证：见 PROGRESS TASK-006 行
未执行验证：Windows mvnw.cmd NOT RUN；MySQL 8.0.16 NOT RUN

未提交修改：无（TASK-006 已提交）
当前阻塞：无

下一步具体动作：
1. TASK-007 DataSourceConnection/ResourceBinding/CapabilityBinding 与 SelectorSchema（沿用 TASK-006 的 Port＋MyBatis 适配器＋真实 MySQL 测试模式）

本任务需要读取的规格章节（TASK-007）：08 TASK-007；03 §9～§15；04 §9～§11、§67～§69；06 §14～§18

后续 UI 约定（TASK-096/099 实施）：
- 底座 React＋TypeScript＋Vite＋Tailwind CSS＋shadcn/ui；Motion 仅在需要布局动画时引入；单一图标库；单一锁文件（npm）
- beautifului/beui/transitions 只借组织方式与过渡，不整套复制；Rare UI 第一版不用其源码；复制 MIT 代码保留声明
- 故障详情主体：当前影响→当前状态→当前判断→判断依据→处理建议→恢复情况；宽屏时间线置侧，技术信息折叠或进抽屉
- 浅色、中性灰文本、小面积语义色；不用巨型标题、满屏渐变、大片玻璃；动效 120～180ms、面板 180～240ms，尊重 reduced-motion
- 状态只认 Java 真实结果：不照搬 setTimeout 演示，不展示思维链，只展示 availableActions，409 刷新事实，无 AI 置信度，预算≠进度，SSE 断开≠FAILED，API 不支持的操作不展示
- 先打磨调查中/等待审批/恢复验证三态（共用组件），先做故障详情再复用
