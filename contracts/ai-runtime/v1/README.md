# AI Runtime 内部协议 v1

Java 主服务与 Python AI Runtime 之间的语言中立契约（07 §80～§84）。本目录的 JSON Schema（Draft 2020-12）是协议事实；
Java（`application/ai/protocol/v1`，record/sealed/enum）与 Python（`opspilot_ai/protocol/v1`，Pydantic v2 判别联合）各自手写强类型，
不做代码生成，靠共享 fixture 保持一致。

| Schema | 方向 | 端点 |
|---|---|---|
| `investigation-step-request.schema.json` | Java → AI | `POST /internal/v1/investigation/step` |
| `investigation-step-response.schema.json` | AI → Java | 同上 |
| `remediation-draft-request.schema.json` | Java → AI | `POST /internal/v1/remediation/draft` |
| `remediation-draft-response.schema.json` | AI → Java | 同上 |

## 约定

- 未知字段一律拒绝；不做标量隐式转换（`"2"` 不是整数，`1` 不是字符串）。
- 可选字段缺省与 `null` 等价，序列化时省略。
- 时间为 UTC、固定毫秒：`2026-09-25T07:00:00.000Z`，且须是日历上存在的时间。
- 文本上限按 Unicode 码点计数，且不能全为空白；空白固定为 Unicode White_Space 集合（U+0009～000D、0020、0085、00A0、1680、2000～200A、2028、2029、202F、205F、3000），三方以显式字符集判断，不依赖各引擎的 `\s`/`isBlank`。
- 整数与整数常量不接受 `true` 或 `1.0` 形式。整个消息不能是 `null`。
- 调查响应恰有一个主 Intent（按 `intentType` 判别）；只有 `PROPOSE_EVIDENCE_LINK` 可附 `hypothesisUpdate`，且必须针对同一 Hypothesis。
- `requestCapability` 按 `capabilityKey` 判别为六种 OBSERVE 参数类型；无参数能力也须显式 `{}`。`service.restart` 只出现在 Remediation。
- Remediation 输出没有 `riskLevel`、`requiresApproval`、RecoveryPolicy 或容器/执行上下文，这些由 Java 确定。
- 协议只校验结构：runNo 是否当前、ID 是否存在与归属、Descriptor 取值是否被允许，由 Java 业务事务判定。

## 调用元数据响应头

模型与 Prompt 信息不属于 v1 响应体（体的 Schema 不变），AI Runtime 在成功的 `/investigation/step` 与 `/remediation/draft` 响应上以头传回，
Java 记入 AgentStepRecord（04 §59、08 TASK-038）：

| 响应头 | 含义 | 记录列上限 |
|---|---|---|
| `X-OpsPilot-Model-Provider` | 模型提供方（当前只有 `fake`） | 64 字符 |
| `X-OpsPilot-Model-Name` | 模型名 | 128 字符 |
| `X-OpsPilot-Prompt-Template-Version` | 版本化 Prompt 模板，如 `investigation-v1` | 64 字符 |
| `X-OpsPilot-Prompt-Tokens` / `X-OpsPilot-Completion-Tokens` | 模型后端报告的 token 用量；未报告时不发送 | 非负整数 |

这些头只作记录：缺失、超长或非数字的值 Java 不记录，也不据此否定已通过协议校验的结果。

## Fixture

`fixtures/<schema>/` 下：

- `valid/`：Schema、Java、Pydantic 都必须接受，并能序列化回等价 JSON；
- `invalid/`：三者都必须拒绝；每个用例只在某个 valid 样例上做一处改动；
- `invalid-model-only/`：违反 JSON Schema 无法表达的规则——跨字段相等（`hypothesisUpdate.hypothesisId` 须等于所链接 Hypothesis）
  与日历有效性（如 `2026-02-30`）；Schema 接受、两端类型模型都必须拒绝。

合同测试：backend `AiProtocolContractTest`（`./mvnw -B verify`）与 ai-runtime `tests/test_protocol_v1_contract.py`（`uv run pytest`），
任一端通过而另一端失败即构建失败。
