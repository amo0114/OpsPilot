package io.github.ismoyuan.opspilot.application.faultlab;

import java.time.Duration;

/**
 * S2 注入配方、Preflight、Baseline、Infrastructure Gate 与 Reset 的参数（09 §16、§48～§51、§59）。都是可配置的 Demo 默认值
 * （{@link #DEFAULTS}），实际基线每次注入时现场采样。
 *
 * @param workers 慢刷新任务数。09 §48 初始配方为 7；B36 在真实 ShortLink（Pool 上限 8、创建 5 req/s）实测 7 个时 pending 只有一次 > 0、
 *     创建 P99 < 150ms，未达 Gate，校准为 8
 * @param statementMillis 每次刷新在数据库侧的时长（09 §44：约 3 秒）
 * @param baselineDuration 注入前持续负载下的正常窗口（09 §16 建议 60 秒）
 * @param sampleInterval Baseline、Gate 与 Reset 的采样间隔
 * @param probesPerSample 每次采样的创建探测次数
 * @param minLoadRate Baseline 窗口内扣除探测后的创建速率下限（条/秒；09 §49 默认 5 req/s，初值取其一半）
 * @param maxBaselineErrorRate Baseline 窗口内创建允许的最大错误率
 * @param saturatedActive Gate 的 active 饱和下限（09 §51：≥ 7），Preflight/Baseline/Reset 要求 active 低于它
 * @param saturatedSamples Gate 要求连续达到饱和的采样数
 * @param pendingSamples Gate 要求 pending > 0 的采样数（09 §51：至少 2）
 * @param slowStatementMillis 慢语句摘要平均或最大耗时下限（09 §51：2000ms）
 * @param p99Factor LATENCY 分支：故障期创建 P99 ≥ max(基线 × 本系数, {@code p99Floor})（09 §51：5）
 * @param p99Floor LATENCY 分支绝对下限（09 §51：2000ms）
 * @param errorRateIncrease ERROR_RATE 分支：≥ max(基线 + 本值, {@code errorRateFloor})（0.05）
 * @param errorRateFloor ERROR_RATE 分支绝对下限（0.05）
 * @param gateTimeout 注入后等待 Gate 的最长时间（09 §51：60 秒）
 * @param healthyCreateP99 Reset 要求的创建 P99 上限（09 §59：Create API 恢复正常范围）；显式绝对判据，须低于 {@code p99Floor}
 * @param resetTimeout Reset 停止负载、等连接池回落、清理摘要并确认创建恢复的最长时间
 */
public record MysqlSlowQuerySettings(
        int workers,
        int statementMillis,
        Duration baselineDuration,
        Duration sampleInterval,
        int probesPerSample,
        double minLoadRate,
        double maxBaselineErrorRate,
        int saturatedActive,
        int saturatedSamples,
        int pendingSamples,
        Duration slowStatementMillis,
        double p99Factor,
        Duration p99Floor,
        double errorRateIncrease,
        double errorRateFloor,
        Duration gateTimeout,
        Duration healthyCreateP99,
        Duration resetTimeout) {

    public static final MysqlSlowQuerySettings DEFAULTS = new MysqlSlowQuerySettings(
            8,
            3000,
            Duration.ofSeconds(60),
            Duration.ofSeconds(2),
            1,
            2.5,
            0.01,
            7,
            3,
            2,
            Duration.ofMillis(2000),
            5.0,
            Duration.ofMillis(2000),
            0.05,
            0.05,
            Duration.ofSeconds(60),
            Duration.ofMillis(500),
            Duration.ofSeconds(120));

    public MysqlSlowQuerySettings {
        if (workers < 1 || statementMillis < 100 || probesPerSample < 1 || saturatedActive < 1) {
            throw new IllegalArgumentException("workload and probe counts must be positive");
        }
        if (saturatedSamples < 1 || pendingSamples < 1) {
            throw new IllegalArgumentException("gate sample counts must be positive");
        }
        if (!baselineDuration.isPositive()
                || !sampleInterval.isPositive()
                || !slowStatementMillis.isPositive()
                || !p99Floor.isPositive()
                || !gateTimeout.isPositive()
                || !healthyCreateP99.isPositive()
                || !resetTimeout.isPositive()) {
            throw new IllegalArgumentException("durations must be positive");
        }
        if (baselineDuration.compareTo(sampleInterval) < 0) {
            throw new IllegalArgumentException("baseline must cover at least one sample interval");
        }
        if (healthyCreateP99.compareTo(p99Floor) >= 0) {
            throw new IllegalArgumentException("the healthy create P99 must be below the latency branch floor");
        }
        if (!(minLoadRate > 0)
                || !(maxBaselineErrorRate > 0 && maxBaselineErrorRate < 1)
                || !(p99Factor >= 1)
                || !(errorRateIncrease > 0 && errorRateIncrease < 1)
                || !(errorRateFloor > 0 && errorRateFloor < 1)) {
            throw new IllegalArgumentException("rates must be in range");
        }
    }
}
