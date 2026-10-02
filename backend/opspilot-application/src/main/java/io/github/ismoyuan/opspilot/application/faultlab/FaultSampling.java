package io.github.ismoyuan.opspilot.application.faultlab;

import io.github.ismoyuan.opspilot.application.faultlab.ConsumerStopEnvironment.RedirectProbe;
import io.github.ismoyuan.opspilot.application.faultlab.ConsumerStopEnvironment.StreamSnapshot;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** 真实注入器共用的采样与计时工具（S1、S3）：跳转探测累计、扣除探测后的负载写入速率、可中断等待。 */
final class FaultSampling {

    private FaultSampling() {}

    /** 一次 Stream 采样及其之前已发出的跳转探测次数（用于扣除探测写入的消息）。 */
    record Sample(StreamSnapshot stream, long probesBefore) {}

    /**
     * 两次采样之间扣除跳转探测之后每秒新写入的条目数。每次探测按至多写入一条统计消息全额扣除（不论成功与否，取保守上限）；Redis 不提供
     * 累计写入数时无法证明负载，按 0 处理（不估算）。
     */
    static double loadRate(Sample from, Sample to) {
        double seconds =
                Duration.between(from.stream().observedAt(), to.stream().observedAt())
                                .toNanos()
                        / 1e9;
        Long fromAdded = from.stream().entriesAdded();
        Long toAdded = to.stream().entriesAdded();
        if (fromAdded == null || toAdded == null || seconds <= 0) {
            return 0;
        }
        long probesBetween = to.probesBefore() - from.probesBefore();
        return (toAdded - fromAdded - probesBetween) / seconds;
    }

    static void pause(Duration duration) {
        if (!duration.isPositive()) {
            return;
        }
        try {
            Thread.sleep(duration);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new FaultInjectionException("Fault lab action was interrupted");
        }
    }

    static Duration multiply(Duration duration, double factor) {
        return Duration.ofNanos(Math.round(duration.toNanos() * factor));
    }

    static Duration max(Duration a, Duration b) {
        return a.compareTo(b) >= 0 ? a : b;
    }

    static Duration min(Duration a, Duration b) {
        return a.compareTo(b) <= 0 ? a : b;
    }

    /** 跳转探测的累计结果。 */
    static final class Probes {

        private final List<Duration> latencies = new ArrayList<>();
        private int failures;

        void add(RedirectProbe probe) {
            latencies.add(probe.latency());
            if (!probe.succeeded()) {
                failures++;
            }
        }

        long count() {
            return latencies.size();
        }

        double errorRate() {
            return latencies.isEmpty() ? 0 : (double) failures / latencies.size();
        }

        boolean errorRateAtLeast(double limit) {
            return !latencies.isEmpty() && errorRate() >= limit;
        }

        /** 最近秩法 P99（含失败请求的耗时）。 */
        Duration p99() {
            if (latencies.isEmpty()) {
                return Duration.ZERO;
            }
            List<Duration> sorted = latencies.stream().sorted().toList();
            return sorted.get((int) Math.ceil(0.99 * sorted.size()) - 1);
        }
    }
}
