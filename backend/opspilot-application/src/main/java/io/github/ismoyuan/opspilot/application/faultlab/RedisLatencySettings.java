package io.github.ismoyuan.opspilot.application.faultlab;

import java.time.Duration;

/**
 * S1 注入强度、Preflight、Baseline、Infrastructure Gate 与 Reset 的参数（09 §15、§16、§30～§33、§41）。都是可配置的 Demo 默认值
 * （{@link #DEFAULTS}），不是实测基线；实际基线每次注入时现场采样。
 *
 * @param latencyMs downstream 延迟（09 §31：600）
 * @param jitterMs 抖动（09 §30：0）
 * @param toxicity 作用比例（09 §30：1.0）
 * @param baselineDuration 注入前在持续负载下采样正常窗口的时长（09 §16 建议 60 秒）
 * @param sampleInterval Baseline、Gate 与 Reset 的采样间隔
 * @param probesPerSample 每次采样访问跳转接口的次数
 * @param minLoadMessageRate Baseline 窗口内扣除跳转探测后统计消息的最低写入速率（条/秒），证明持续负载在运行（09 §32 默认 15 req/s；初值
 *     取其一半）。Gate 不要求该速率：S1 症状本身会使跳转堆积、写入速率下降
 * @param healthyLag Preflight 与 Baseline 每次采样允许的最大 lag（09 §14）
 * @param healthyPending Preflight 与 Baseline 每次采样允许的最大 pending（09 §14）
 * @param maxBaselineErrorRate Baseline 窗口内跳转允许的最大错误率
 * @param pingCount 每次测量经代理 PING 的次数（09 §33：5）
 * @param healthyPingMedian Preflight 与 Reset 要求的 PING 中位数上限（09 §15、§41：Redis RTT 处于正常范围）
 * @param healthyRedirectP99 Reset 要求的跳转 P99 上限（09 §41：HTTP P99 恢复）。显式配置的绝对判据，不依赖注入时的内存基线，
 *     未经 Gate 的失败实验与进程重启后的 Reset 同样可用；须低于 LATENCY 分支下限 {@code p99Floor}
 * @param faultPingMedian Gate 要求的 PING 中位数下限（09 §33：500ms）
 * @param minFaultProbes Gate 判定 HTTP 症状前故障期至少累计的跳转探测数
 * @param p99Factor LATENCY 分支：故障期 P99 ≥ max(基线 P99 × 本系数, {@code p99Floor})（09 §33：4）
 * @param p99Floor LATENCY 分支的绝对下限（09 §33：800ms）
 * @param errorRateIncrease ERROR_RATE 分支：故障期错误率 ≥ max(基线错误率 + 本值, {@code errorRateFloor})（09 §33：0.05）
 * @param errorRateFloor ERROR_RATE 分支的绝对下限（09 §33：0.05）
 * @param gateTimeout 注入后等待 Gate 成立的最长时间（09 §33：60 秒）
 * @param resetTimeout Reset 删除 toxic 并等待 RTT 与跳转恢复的最长时间（删除会等代理排空已积压的数据）
 */
public record RedisLatencySettings(
        int latencyMs,
        int jitterMs,
        double toxicity,
        Duration baselineDuration,
        Duration sampleInterval,
        int probesPerSample,
        double minLoadMessageRate,
        long healthyLag,
        long healthyPending,
        double maxBaselineErrorRate,
        int pingCount,
        Duration healthyPingMedian,
        Duration healthyRedirectP99,
        Duration faultPingMedian,
        int minFaultProbes,
        double p99Factor,
        Duration p99Floor,
        double errorRateIncrease,
        double errorRateFloor,
        Duration gateTimeout,
        Duration resetTimeout) {

    public static final RedisLatencySettings DEFAULTS = new RedisLatencySettings(
            FaultScenarioCatalog.REDIS_LATENCY_MS,
            0,
            1.0,
            Duration.ofSeconds(60),
            Duration.ofSeconds(2),
            2,
            7.5,
            20,
            20,
            0.01,
            5,
            Duration.ofMillis(100),
            Duration.ofMillis(300),
            Duration.ofMillis(500),
            6,
            4.0,
            Duration.ofMillis(800),
            0.05,
            0.05,
            Duration.ofSeconds(60),
            Duration.ofSeconds(180));

    public RedisLatencySettings {
        if (latencyMs < 1 || jitterMs < 0 || !(toxicity > 0 && toxicity <= 1)) {
            throw new IllegalArgumentException("latency must be positive, jitter non-negative, toxicity in (0, 1]");
        }
        if (!baselineDuration.isPositive()
                || !sampleInterval.isPositive()
                || !healthyPingMedian.isPositive()
                || !healthyRedirectP99.isPositive()
                || !faultPingMedian.isPositive()
                || !p99Floor.isPositive()
                || !gateTimeout.isPositive()
                || !resetTimeout.isPositive()) {
            throw new IllegalArgumentException("durations must be positive");
        }
        if (baselineDuration.compareTo(sampleInterval) < 0) {
            throw new IllegalArgumentException("baseline must cover at least one sample interval");
        }
        if (healthyPingMedian.compareTo(faultPingMedian) >= 0) {
            throw new IllegalArgumentException("the healthy ping median must be below the fault gate");
        }
        if (healthyRedirectP99.compareTo(p99Floor) >= 0) {
            throw new IllegalArgumentException("the healthy redirect P99 must be below the latency branch floor");
        }
        if (probesPerSample < 1 || pingCount < 1 || minFaultProbes < 1 || healthyLag < 0 || healthyPending < 0) {
            throw new IllegalArgumentException("counts must be positive");
        }
        if (!(minLoadMessageRate > 0)
                || !(maxBaselineErrorRate > 0 && maxBaselineErrorRate < 1)
                || !(p99Factor >= 1)
                || !(errorRateIncrease > 0 && errorRateIncrease < 1)
                || !(errorRateFloor > 0 && errorRateFloor < 1)) {
            throw new IllegalArgumentException("rates must be in range");
        }
    }
}
