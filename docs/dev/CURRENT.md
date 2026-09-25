# 当前工作

更新时间：2026-09-26（本地）
仓库/分支：/root/projects/OpsPilot-V0.1-IMPLEMENTATION，main；HEAD 04560f9（TASK-003）；无 remote
当前任务：TASK-004 DONE（独立 Review 复核 PASS，已提交）；TASK-005 READY，未开始
任务内位置：等待用户指示开始 TASK-005
本轮允许修改：backend 五模块中错误/请求标识/配置基础代码及 POM、docs/dev/
本轮明确不做：具体 Incident/System 等业务错误码与异常；具体 @ConfigurationProperties 类；成功响应包络（首个产品 API 的 Task 建）；数据库

已完成：
- domain.error：ErrorCategory（8 类语义，不含 HTTP）；ErrorCode（REQUEST_VALIDATION_FAILED、RESOURCE_NOT_FOUND、兜底 INTERNAL_ERROR，带面向用户的固定文案）；OpsPilotException（code＋不可变 details）；DomainException
- application：ApplicationException（可携带 cause，作基础设施错误翻译目标）；Correlation（MDC correlationId、newId()＝corr_＋32hex、可恢复先前值的 Scope，供后台 Worker 使用）；POM 加 slf4j-api
- web：RequestIdFilter（最高优先级；合法 X-Request-Id 沿用，缺失或非法生成 req_＋32hex；写响应头、MDC requestId，且作为 correlationId）；ApiExceptionHandler（OpsPilotException 按 05 §94 类别→HTTP；未预期异常→500 INTERNAL_ERROR；MVC 标准异常保留状态、替换为 05 §9 包络；message 只用固定文案；日志只记 code/status、requestId、异常链类型＋首个栈帧，不记 message/cause 文本）；POM 加 spring-boot-starter-validation、spring-boot-starter-webmvc-test(test)
- boot：@ConfigurationPropertiesScan；logging.pattern.correlation 输出 [requestId correlationId]

已执行验证：见 PROGRESS TASK-004 行（clean verify exit 0、10 个契约测试含 3 个日志不泄露断言、变异检查、构建输出敏感串 0 命中、boot jar 冒烟）
未执行验证：具体配置类绑定（尚无配置类）NOT RUN；Windows mvnw.cmd NOT RUN

未提交修改：无（TASK-004 已提交）
当前阻塞：无

下一步具体动作：
1. TASK-005 创建系统接入数据库结构（届时加入 MyBatis/Flyway/MySQL 驱动，需要可用 MySQL）

本任务需要读取的规格章节（TASK-005）：08 TASK-005；04 系统接入相关表；07 §91～§93

后续 UI 约定（TASK-096/099 实施）：
- 底座 React＋TypeScript＋Vite＋Tailwind CSS＋shadcn/ui；Motion 仅在需要布局动画时引入；单一图标库；单一锁文件（npm）
- beautifului/beui/transitions 只借组织方式与过渡，不整套复制；Rare UI 第一版不用其源码；复制 MIT 代码保留声明
- 故障详情主体：当前影响→当前状态→当前判断→判断依据→处理建议→恢复情况；宽屏时间线置侧，技术信息折叠或进抽屉
- 浅色、中性灰文本、小面积语义色；不用巨型标题、满屏渐变、大片玻璃；动效 120～180ms、面板 180～240ms，尊重 reduced-motion
- 状态只认 Java 真实结果：不照搬 setTimeout 演示，不展示思维链，只展示 availableActions，409 刷新事实，无 AI 置信度，预算≠进度，SSE 断开≠FAILED，API 不支持的操作不展示
- 先打磨调查中/等待审批/恢复验证三态（共用组件），先做故障详情再复用
