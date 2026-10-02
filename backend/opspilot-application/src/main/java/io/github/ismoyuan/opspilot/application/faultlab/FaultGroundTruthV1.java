package io.github.ismoyuan.opspilot.application.faultlab;

import java.time.Instant;
import java.util.Objects;

/**
 * fault-lab.ground-truth / 1：一次演练的真正答案（04 §64、09 §20）。只由 Fault Lab 写入、Evaluation 读取；不得出现在调查上下文、AI 请求、
 * Prompt、Observation、Evidence、Diagnosis 或普通产品 API 中（09 §21：泄漏即 P0 失败）。
 *
 * @param latencyMs 只有 REDIS_NETWORK_LATENCY 有，其余为空
 * @param containerId 只有 STATISTICS_CONSUMER_STOPPED 有：被停止的消费者容器 id（09 §63）；插入实验时尚未注入为空，确认生效后写入
 * @param consumerStoppedAt 与 containerId 同时出现：消费者真正停止的时间（09 §63）
 * @param redisLatencyGate 只有 REDIS_NETWORK_LATENCY 有：确认生效时 Gate 的所达症状分支与真实数值（09 §33、ACC-S1-003）；插入时为空
 * @param mysqlSlowQueryGate 只有 MYSQL_SLOW_QUERY_POOL_EXHAUSTION 有：确认生效时的实际配方、连接池/慢语句/HTTP 实测与所达分支（09 §51、
 *     ACC-S2-001～005）；插入时为空
 */
public record FaultGroundTruthV1(
        String schemaName,
        int schemaVersion,
        FaultCause cause,
        Integer latencyMs,
        String containerId,
        Instant consumerStoppedAt,
        RedisLatencyGate redisLatencyGate,
        MysqlSlowQueryGate mysqlSlowQueryGate) {

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
        if ((containerId == null) != (consumerStoppedAt == null)) {
            throw new IllegalArgumentException("containerId and consumerStoppedAt go together");
        }
        if (containerId != null && cause != FaultCause.STATISTICS_CONSUMER_STOPPED) {
            throw new IllegalArgumentException("containerId belongs to STATISTICS_CONSUMER_STOPPED only");
        }
        if (containerId != null && containerId.isBlank()) {
            throw new IllegalArgumentException("containerId must not be blank");
        }
        if (mysqlSlowQueryGate != null && cause != FaultCause.MYSQL_SLOW_QUERY_POOL_EXHAUSTION) {
            throw new IllegalArgumentException("mysqlSlowQueryGate belongs to MYSQL_SLOW_QUERY_POOL_EXHAUSTION only");
        }
        if (redisLatencyGate != null) {
            if (cause != FaultCause.REDIS_NETWORK_LATENCY) {
                throw new IllegalArgumentException("redisLatencyGate belongs to REDIS_NETWORK_LATENCY only");
            }
            if (!latencyMs.equals(redisLatencyGate.injectedLatencyMs())) {
                throw new IllegalArgumentException("latencyMs must be the injected latency");
            }
        }
    }

    /**
     * S2 Gate 的实测（09 §51）：实际配方，Gate 期间连接池 active 最大值、连续饱和采样数、pending > 0 的采样数与最大值，慢语句摘要的
     * 平均/最大耗时，以及创建接口在基线窗口与故障期的 P99、错误率（0～1）。
     */
    public record MysqlSlowQueryGate(
            SymptomBranch symptomBranch,
            int workers,
            int statementMillis,
            int maxActive,
            int saturatedSamples,
            int pendingSamples,
            int maxPending,
            long slowStatementAvgMs,
            long slowStatementMaxMs,
            long baselineP99Ms,
            long faultP99Ms,
            double baselineErrorRate,
            double faultErrorRate) {

        public MysqlSlowQueryGate {
            Objects.requireNonNull(symptomBranch, "symptomBranch");
            if (workers < 1 || statementMillis < 1 || maxActive < 0 || saturatedSamples < 0 || pendingSamples < 0) {
                throw new IllegalArgumentException("measurements must not be negative");
            }
            if (baselineErrorRate < 0 || baselineErrorRate > 1 || faultErrorRate < 0 || faultErrorRate > 1) {
                throw new IllegalArgumentException("error rates are ratios in [0, 1]");
            }
        }
    }

    /** S1／S2 HTTP 症状的达标分支（09 §33、§51）。 */
    public enum SymptomBranch {
        LATENCY,
        ERROR_RATE,
        BOTH
    }

    /**
     * S1 Gate 的实测（09 §33）：经业务同一代理的 PING 中位数，以及跳转在基线窗口与故障期的 P99、错误率（0～1）。
     *
     * @param injectedLatencyMs 实际注入的 downstream 延迟
     */
    public record RedisLatencyGate(
            SymptomBranch symptomBranch,
            int injectedLatencyMs,
            long pingMedianMs,
            long baselineP99Ms,
            long faultP99Ms,
            double baselineErrorRate,
            double faultErrorRate) {

        public RedisLatencyGate {
            Objects.requireNonNull(symptomBranch, "symptomBranch");
            if (injectedLatencyMs < 1 || pingMedianMs < 0 || baselineP99Ms < 0 || faultP99Ms < 0) {
                throw new IllegalArgumentException("measurements must not be negative");
            }
            if (baselineErrorRate < 0 || baselineErrorRate > 1 || faultErrorRate < 0 || faultErrorRate > 1) {
                throw new IllegalArgumentException("error rates are ratios in [0, 1]");
            }
        }
    }

    public static FaultGroundTruthV1 of(FaultCause cause, Integer latencyMs) {
        return new FaultGroundTruthV1(SCHEMA_NAME, SCHEMA_VERSION, cause, latencyMs, null, null, null, null);
    }

    /** 补上被停止的消费者（确认生效时）。 */
    public FaultGroundTruthV1 withStoppedConsumer(String stoppedContainerId, Instant stoppedAt) {
        return new FaultGroundTruthV1(
                schemaName,
                schemaVersion,
                cause,
                latencyMs,
                stoppedContainerId,
                stoppedAt,
                redisLatencyGate,
                mysqlSlowQueryGate);
    }

    /** 补上 S1 Gate 实测（确认生效时）；latencyMs 取实际注入值。 */
    public FaultGroundTruthV1 withRedisLatencyGate(RedisLatencyGate gate) {
        return new FaultGroundTruthV1(
                schemaName,
                schemaVersion,
                cause,
                gate.injectedLatencyMs(),
                containerId,
                consumerStoppedAt,
                gate,
                mysqlSlowQueryGate);
    }

    /** 补上 S2 Gate 实测（确认生效时）。 */
    public FaultGroundTruthV1 withMysqlSlowQueryGate(MysqlSlowQueryGate gate) {
        return new FaultGroundTruthV1(
                schemaName, schemaVersion, cause, latencyMs, containerId, consumerStoppedAt, redisLatencyGate, gate);
    }
}
