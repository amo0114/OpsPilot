package io.github.ismoyuan.opspilot.application.faultlab;

import java.util.Objects;

/**
 * fault-lab.ground-truth / 1：一次演练的真正答案（04 §64、09 §20）。只由 Fault Lab 写入、Evaluation 读取；不得出现在调查上下文、AI 请求、
 * Prompt、Observation、Evidence、Diagnosis 或普通产品 API 中（09 §21：泄漏即 P0 失败）。
 *
 * @param latencyMs 只有 REDIS_NETWORK_LATENCY 有，其余为空
 */
public record FaultGroundTruthV1(String schemaName, int schemaVersion, FaultCause cause, Integer latencyMs) {

    public static final String SCHEMA_NAME = "fault-lab.ground-truth";
    public static final int SCHEMA_VERSION = 1;

    public FaultGroundTruthV1 {
        if (!SCHEMA_NAME.equals(schemaName) || schemaVersion != SCHEMA_VERSION) {
            throw new IllegalArgumentException("fault-lab.ground-truth / 1 expected");
        }
        Objects.requireNonNull(cause, "cause");
        if ((cause == FaultCause.REDIS_NETWORK_LATENCY) != (latencyMs != null)) {
            throw new IllegalArgumentException("latencyMs belongs to REDIS_NETWORK_LATENCY only");
        }
        if (latencyMs != null && latencyMs < 1) {
            throw new IllegalArgumentException("latencyMs must be positive");
        }
    }

    public static FaultGroundTruthV1 of(FaultCause cause, Integer latencyMs) {
        return new FaultGroundTruthV1(SCHEMA_NAME, SCHEMA_VERSION, cause, latencyMs);
    }
}
