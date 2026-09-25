# OpsPilot V0.1 文档交付校验报告

> 校验日期：2026-09-25  
> 基线：FINAL-FREEZE-20260925 / 0.1  
> 仅记录本次实际运行的文件/Markdown解析与参考逻辑检查。不是项目测试报告。

## 1. 已执行检查

| 项目 | 实际结果 | 解释 |
|---|---|---|
| 00～09文件生成 | PASS：10份 | 均为UTF-8 Markdown，已实际写入容器 |
| 正文一级标题 | PASS：每份1个 | 保留原编号章节，规范化标题层级 |
| Markdown代码围栏 | PASS：无未闭合围栏 | 用行级扫描并结合Markdown解析检查 |
| JSON示例 | PASS：81个可解析 | 只证明JSON语法，不代替未来JSON Schema合同测试 |
| 正文内部Markdown文件链接 | PASS：无缺失文件 | 文本中的代码路径示意不要求现在存在 |
| Task编号 | PASS：109个且唯一 | 001～109全部存在，没有重复或缺失 |
| Task前置依赖 | PASS | 显式顺序中每个前置均早于当前任务，未发现依赖环 |
| 不变量编号 | PASS | INV15、BND16、DB7、API7、CAP20、ENG20、ACC10全部存在 |
| 最终针对性验收用例 | PASS：15项已记录 | ACC-FINAL-01～15；这是定义存在，不是系统测试运行通过 |
| 旧语义模式检查 | PASS：已列严格残留模式为0 | 检查旧计数字段、run_epoch别名、MyBatis4核心库称谓、pending单调下降旧例等 |
| 原14份文件归档 | PASS：逐字节SHA256一致 | 共438,857字节，不改写原始历史材料 |
| 未解析工具引用 | PASS：正文无工具引用占位 | 没有turnNfile/search残留或工作容器路径泄露 |

禁止规则、历史对比和任务状态中的字符串并非实现旧语义，因此不以“某个词出现过”代替语义检查。
例如任务状态TODO是开发管理状态，不是遗留待定规格；禁止任意Shell的说明必须保留。

## 2. 已执行的参考逻辑检查

使用独立Python参考函数检查文档规定的布尔合取与lag健康语义。
这不是OpsPilot Java实现，也未连接Redis或Docker。

required三项TRUE/FALSE/UNKNOWN的27种组合均符合：
至少一个有效FALSE→FAILED；无FALSE但有UNKNOWN→INCONCLUSIVE；全部TRUE→PASSED。

| lag序列，阈值20 | 参考结果 |
|---|---|
| 0 → 0 → 0 → 0 | TRUE |
| 2 → 0 → 1 → 0 | TRUE |
| 200 → 210 → 50 → 10 | TRUE，最终进入健康区间 |
| 200 → 150 → 100 → 50 | FALSE，完整计划结束仍未健康 |
| 50 → 10 → 30 → 0 | FALSE，进入后又越界 |
| 200 → UNKNOWN → 10 → 0 | UNKNOWN，没有足够完整轨迹 |
| 0 → UNKNOWN → 30 → 0 | FALSE，存在已观测的健康后越界 |

同时检查S3 Policy示例的criterionKey唯一、默认B/C/D/A顺序和采样数合计9。
这里只检查给出的文档示例；样本新鲜度、时间间隔和真实Provider属于后续实施验证。

## 3. 没有执行的验证

- Java编译、Spring Boot启动、Maven依赖兼容、Python/前端构建：NOT RUN。
- JSON Schema正负合同fixture、数据库约束和并发测试：NOT RUN。
- 真实MySQL迁移、Docker操作、Prometheus/Loki/Redis读取：NOT RUN。
- ShortLink ACK行为、S2压力校准、P99测量、S1/S2/S3验收：NOT RUN。
- 用户仓库导入或Git提交：NOT PERFORMED。

本报告只能支持“文档包文件齐备且通过所列静态检查”，不能支持“系统已可运行”、
“六项HIGH已由代码修复”或“真实故障实验已经PASS”。
