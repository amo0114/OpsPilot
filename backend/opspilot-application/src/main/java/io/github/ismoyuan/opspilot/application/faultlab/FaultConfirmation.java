package io.github.ismoyuan.opspilot.application.faultlab;

import java.time.Instant;
import java.util.Objects;

/**
 * 注入器确认故障生效的结果（只供 Fault Lab 写入实验与 Ground Truth，不进入 Incident 或任何产品响应）。
 *
 * @param detectedAt 首次确认预期异常的时间（Incident.detected_at，09 §19）
 * @param redisLatencyGate S1 的 Gate 实测：所达症状分支与真实基线/故障数值（09 §33、ACC-S1-003）；其他场景为空
 * @param mysqlSlowQueryGate S2 的 Gate 实测（09 §51、ACC-S2-001～005）；其他场景为空
 */
public record FaultConfirmation(
        Instant detectedAt,
        FaultGroundTruthV1.RedisLatencyGate redisLatencyGate,
        FaultGroundTruthV1.MysqlSlowQueryGate mysqlSlowQueryGate) {

    public FaultConfirmation {
        Objects.requireNonNull(detectedAt, "detectedAt");
    }

    public static FaultConfirmation detectedAt(Instant detectedAt) {
        return new FaultConfirmation(detectedAt, null, null);
    }
}
