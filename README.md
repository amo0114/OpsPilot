# OpsPilot V0.1 定稿规格包

> 基线：FINAL-FREEZE-20260925 / Version 0.1  
> 交付类型：Markdown规格、实施入口与历史归档；不含业务代码实现。

## 从哪里开始

正式编码入口是 [SPEC-MANIFEST](docs/specs/SPEC-MANIFEST.md)。
本包已把最终裁决直接合入10份正文，不需要让编码Agent自行决定旧补丁优先级。

完整包保留源文档与裁决归档；实施精简包只保留正式规格、操作规则与交付说明。
两个包中的00～09正文与Manifest内容一致。历史材料不是实施输入。

## 文件结构

```text
README.md
AGENTS.md
CLAUDE.md
SHA256SUMS.md
docs/
  specs/
    SPEC-MANIFEST.md
    00-product.md
    01-lifecycle.md
    02-java-ai-boundary.md
    03-domain-model.md
    04-database.md
    05-api.md
    06-capability.md
    07-engineering.md
    08-implementation-plan.md
    09-acceptance.md
  delivery/
    CONSOLIDATION-REPORT.md
    VALIDATION-REPORT.md
  archive/                         # 完整包专有
    design-history/
      README.md
      FINAL-FREEZE.md
      originals/                   # 14份原始正文/补丁，字节保留
    review/
      REVIEW-ADJUDICATION.md
      FINAL-REVIEW-SUMMARY.md
      FINAL-REVIEW-INSTRUCTIONS.md
```

## 三项修订已采用

预算正式属于每个active investigation run，重启不刷新。
RUNNING写操作不重放，但只读reconciliation允许有界重试。
恢复判定为FAILED > INCONCLUSIVE > PASSED，不让UNKNOWN覆盖有效的FALSE。

同时合并Stop原子准入、可恢复样本协议、lag/pending健康门禁与写操作前RecoveryPolicy快照。
所有Task编号保留为001～109；为了先有恢复合同，068/074～076在067/069之前实施。

## 如何导入已有仓库

先比较本包与仓库现有docs/specs以及AGENTS/CLAUDE文件，避免直接覆盖你已有的无关规则或业务代码。
把正式规格放入对应目录；原设计材料仅归档。本次未访问或修改你的Git仓库，
因此“文本合并完成”不等于“仓库已导入或提交”。

确认TASK-001导入核对后，从TASK-002开始逐Task实施。
实际构建、数据库迁移、真实Provider、ShortLink故障注入和S1/S2/S3验收由实施任务完成。
不要把FROZEN当作运行验收PASS。

## 阅读与核验

[合并报告](docs/delivery/CONSOLIDATION-REPORT.md)列出来源、改动、默认值和任务调整。
[交付校验](docs/delivery/VALIDATION-REPORT.md)只记录本次实际执行的文档与参考逻辑检查。
文件字节校验见SHA256SUMS.md；它不代表业务功能经过验证。
