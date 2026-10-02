package io.github.ismoyuan.opspilot.application.faultlab;

import java.time.Duration;

/**
 * S3 Preflight、Baseline 与 Infrastructure Gate 的参数（09 §15、§16、§61、§65）。都是可配置的 Demo 默认值（{@link #DEFAULTS}），不是实测基线；
 * 实际基线每次注入时现场采样。
 *
 * @param baselineDuration 注入前在持续负载下采样正常窗口的时长（09 §16 建议 60 秒）
 * @param sampleInterval Baseline 与 Gate 的采样间隔
 * @param probesPerSample 每次采样访问跳转接口的次数
 * @param minLoadMessageRate Baseline 窗口与 Gate 最近三次采样内、扣除跳转探测之后统计消息的最低写入速率（条/秒），证明持续负载在运行
 *     （09 §62 默认 10 req/s；初值取其一半）
 * @param healthyLag Preflight 与 Baseline 每次采样允许的最大 lag（09 §16 初值 20）
 * @param healthyPending Preflight 与 Baseline 每次采样允许的最大 pending（09 §16 初值 20）
 * @param stopGrace Docker stop 的宽限期
 * @param gateTimeout 停止后等待 Gate 成立的最长时间（09 §65：60 秒）
 * @param minLagGrowth Gate 要求末次 lag 较基线至少增长的条目数（09 §65：50）
 * @param maxErrorRate 跳转接口允许的最大错误率（09 §65：小于 1%）
 * @param p99Factor 故障期跳转 P99 不得超过 max(基线 P99 × 本系数, {@code p99Floor})，即不发生严重恶化（09 §17、§65）
 * @param p99Floor 上述上限的绝对下限
 * @param resetTimeout Reset 启动消费者后等待其运行（且健康检查通过）的最长时间
 */
public record ConsumerStopSettings(
        Duration baselineDuration,
        Duration sampleInterval,
        int probesPerSample,
        double minLoadMessageRate,
        long healthyLag,
        long healthyPending,
        Duration stopGrace,
        Duration gateTimeout,
        long minLagGrowth,
        double maxErrorRate,
        double p99Factor,
        Duration p99Floor,
        Duration resetTimeout) {

    public static final ConsumerStopSettings DEFAULTS = new ConsumerStopSettings(
            Duration.ofSeconds(60),
            Duration.ofSeconds(2),
            2,
            5.0,
            20,
            20,
            Duration.ofSeconds(10),
            Duration.ofSeconds(60),
            50,
            0.01,
            4.0,
            Duration.ofMillis(500),
            Duration.ofSeconds(90));

    public ConsumerStopSettings {
        if (!baselineDuration.isPositive()
                || !sampleInterval.isPositive()
                || stopGrace.isNegative()
                || !gateTimeout.isPositive()
                || !p99Floor.isPositive()
                || !resetTimeout.isPositive()) {
            throw new IllegalArgumentException("durations must be positive");
        }
        if (baselineDuration.compareTo(sampleInterval) < 0) {
            throw new IllegalArgumentException("baseline must cover at least one sample interval");
        }
        if (!(minLoadMessageRate > 0)) {
            throw new IllegalArgumentException("minimum load message rate must be positive");
        }
        if (probesPerSample < 1 || healthyLag < 0 || healthyPending < 0 || minLagGrowth < 1) {
            throw new IllegalArgumentException("counts must be positive");
        }
        if (!(maxErrorRate > 0 && maxErrorRate < 1) || !(p99Factor >= 1)) {
            throw new IllegalArgumentException("error rate must be in (0, 1) and p99 factor at least 1");
        }
    }
}
