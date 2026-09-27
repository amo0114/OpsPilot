# OpsPilot V0.1 编码 Agent 规则

## 权威输入

首先阅读docs/specs/SPEC-MANIFEST.md，再读取当前批次成员Task列出的正式规格。
TASK-013起按docs/dev/BATCH-PLAN.md执行；该文件只定义批次与记录方式，不替代业务合同。
docs/archive及历史Review不作为实现规则；不要重新解析旧补丁。
本文件只约束工作方式，业务合同只在docs/specs。

## 范围与报告

一次实施一个READY批次（TASK-001～012保留单项流程）；成员与顺序见docs/dev/BATCH-PLAN.md。
遵守docs/specs/08-implementation-plan.md的原始依赖与每项DoD，不是盲按数字递增。
特别顺序：001～066 → 068 → 074～076 → 067 → 069～073 → 077～109。
不得把任务范围外发现“顺手修好”；记录位置、影响与建议，留给对应Task。

每次交付说明修改文件、完成目标、新依赖、Migration、实际验证命令与结果、未解决问题。
没有执行的测试明确写NOT RUN，不能因为文档写着FROZEN就写测试通过。
创建代码前先检查现有仓库，不覆盖用户已有未提交内容。

## 进度与交接

- 开工读取 docs/dev/CURRENT.md、BATCH-PLAN.md 和 PROGRESS.md 当前批次记录，核对 Git 真实状态。
- 批外前置须验证、独立Review通过并提交/DONE；批内按依赖实施，前置针对性验证完成即可继续，不必逐项提交/外审。
- 开工固定批次成员、完整base SHA、允许目录、关键不变量和验证要求；只修改本批范围，保护已有无关修改。
- PROGRESS保留逐Task状态/批次关联及共同验证、专项验证、Review、提交证据；CURRENT记录当前成员、断点、未提交文件与下一步。
- 批尾完整验证覆盖所有成员DoD，独立Reviewer审查固定base到当前代码树全部变化（含未跟踪文件）；修复沿用原批次与基线。
- 整批Review PASS后按用户授权提交，回填真实SHA，再将批次及成员一起标DONE；待提交仍REVIEW。不得提前开始下一批。
- 停工、换会话或批次结束前更新交接卡；记录模板见BATCH-PLAN，不新建逐Task长报告。
- 不凭聊天摘要宣告 DONE；没执行的验证写 NOT RUN，不编造提交或结果。
- 不覆盖、重置或清理用户已有修改；不自动推送。

## 硬边界

Java掌握业务事实、状态、调用准入、审批、写操作与恢复验证。
Python只产生强类型Intent/Proposal，不连接业务MySQL/Redis/Prometheus/Loki/Docker。
Controller不直接访问Mapper或Provider；Domain不依赖Spring。
外部网络/LLM/Docker/Sleep不得处于数据库事务中。
所有Incident状态迁移都经过唯一转换入口与expectedStatus/lock_version。
禁止任意SQL、PromQL、LogQL、Shell输入和Map<String,Object>万能能力。
Evidence关系不可变；Diagnosis版本化；不得复制Observation绕过关系唯一约束。
CHANGE不重放；只有受控只读reconciliation有界重试。
Recovery采用FAILED优先的三值矩阵，不调用AI。
Ground Truth不得进入调查请求或目标业务日志流。
Frozen Specs优先于代码；代码与规格冲突时不得用代码改写需求。
不修改Incident 8个状态；不允许通用updateStatus或绕过转换入口的CRUD状态更新。
不增加数据库核心表，除非Task明确要求。
不引入新框架/依赖解决局部问题，除非Task明确要求；新增前回答07 §125三问。
每个Task完成其针对性验证；批尾在最终代码树运行覆盖所有成员DoD的完整验证，共同构建证据可复用。
不得用-Denforcer.skip、-Dspotless.check.skip等跳过工程门禁；专项验证不得被BUILD SUCCESS替代。

## 必须保护的组合规则

每个active run具有独立预算；Java重启不建立新run或刷新deadline。
Stop和Step/Capability准入使用相同锁顺序；COMMIT是准入分界。
旧run结果不可污染新run；真实迟到观测只能按原调用审计保存。
PENDING已提交任务不能因JVM队列丢失而永久卡住。
执行前已持久化恢复合同；执行后不重新选择ACTIVE策略。
恢复样本身份、时间和有效性可从数据库重建；不伪造或透明重试样本。
S3同时检查lag、pending与服务状态，健康零积压是合法结果。

## 禁止扩大范围

不新增MQ、Outbox、分布式锁/Lease、Multi-Agent、LangGraph、MCP、RAG、租户/RBAC、
新Incident状态、新Capability或通用插件系统，除非正式规格明确变更。
依赖精确版本、SQL实现、观测模板和Demo校准在现有Task内解决，不重开架构讨论。

## 精简验证与代码

- 不追求覆盖率、测试数量或强制先测试后实现；只验证本次变更涉及的真实行为风险。
- 普通样式、直接 DTO 映射与配置不机械新增单测；采用相应构建、类型和必要人工检查。
- 内层不重复校验已经通过边界验证的同一结构；保留权威事务里的状态、权限与版本检查。
- 保留数据库约束、调用超时、审批、CHANGE 不重放和真实恢复判定；不得以精简为由删除。
- 默认不用真实 LLM、不运行完整 S1/S2/S3；仅在规定集成或里程碑执行。
- 调试用受影响检查；任务 DoD 明确要求的 verify/关键测试仍须完成，不修改其含义。
- 同一行为不在每一层重复证明；并发、SQL 与跨语言边界用其真实边界验证。
- 不加猜测性兼容层、通用重试/回退框架或 catch 后伪造成功。
