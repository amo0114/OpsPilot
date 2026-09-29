package io.github.ismoyuan.opspilot.application.capability.result;

import io.github.ismoyuan.opspilot.domain.capability.CapabilitySchema;

/**
 * cache.inspect.result / 1（06 §62）：Redis PING/INFO 的白名单统计，不含任何 Key 名或 Value（CAP-INV-008）。
 * 每个统计值为空表示本次未取得（或 Redis 未设置，如 maxmemory 为 0），不以 0 代替；不可达时除 reachable 外全部为空。
 *
 * @param hitRate 总是由 keyspaceHits / (keyspaceHits + keyspaceMisses) 派生（两者都取得且和大于 0 时才有值）；Provider 可省略，
 *     给出的值须与计数相符（误差不超过 0.0005，对应三位小数），不能与计数矛盾（B15-R1）
 * @param blockedClients 不超过 connectedClients（两者都取得时）
 */
public record CacheInspectResultV1(
        boolean reachable,
        Long pingLatencyMs,
        Long usedMemoryBytes,
        Long maxMemoryBytes,
        Long connectedClients,
        Long blockedClients,
        Long instantOpsPerSec,
        Long keyspaceHits,
        Long keyspaceMisses,
        Double hitRate,
        Long evictedKeys,
        Long expiredKeys,
        Long uptimeSeconds)
        implements CapabilityResult {

    public static final String SCHEMA_NAME = "cache.inspect.result";
    public static final int SCHEMA_VERSION = 1;

    static final double HIT_RATE_TOLERANCE = 0.0005;

    public CacheInspectResultV1 {
        ResultChecks.optionalNonNegative("pingLatencyMs", pingLatencyMs);
        ResultChecks.optionalNonNegative("usedMemoryBytes", usedMemoryBytes);
        ResultChecks.optionalNonNegative("maxMemoryBytes", maxMemoryBytes);
        ResultChecks.optionalNonNegative("connectedClients", connectedClients);
        ResultChecks.optionalNonNegative("blockedClients", blockedClients);
        ResultChecks.optionalNonNegative("instantOpsPerSec", instantOpsPerSec);
        ResultChecks.optionalNonNegative("keyspaceHits", keyspaceHits);
        ResultChecks.optionalNonNegative("keyspaceMisses", keyspaceMisses);
        ResultChecks.optionalRatio("hitRate", hitRate);
        ResultChecks.optionalNonNegative("evictedKeys", evictedKeys);
        ResultChecks.optionalNonNegative("expiredKeys", expiredKeys);
        ResultChecks.optionalNonNegative("uptimeSeconds", uptimeSeconds);
        if (!reachable
                && (pingLatencyMs != null
                        || usedMemoryBytes != null
                        || maxMemoryBytes != null
                        || connectedClients != null
                        || blockedClients != null
                        || instantOpsPerSec != null
                        || keyspaceHits != null
                        || keyspaceMisses != null
                        || hitRate != null
                        || evictedKeys != null
                        || expiredKeys != null
                        || uptimeSeconds != null)) {
            throw new IllegalArgumentException("statistics must be absent when unreachable");
        }
        boolean measurable = keyspaceHits != null && keyspaceMisses != null && keyspaceHits + keyspaceMisses > 0;
        if (hitRate != null && !measurable) {
            throw new IllegalArgumentException("hitRate requires keyspaceHits and keyspaceMisses");
        }
        Double derived = measurable ? keyspaceHits / (double) (keyspaceHits + keyspaceMisses) : null;
        if (hitRate != null && Math.abs(hitRate - derived) > HIT_RATE_TOLERANCE) {
            throw new IllegalArgumentException("hitRate must match keyspaceHits and keyspaceMisses");
        }
        hitRate = derived;
        if (connectedClients != null && blockedClients != null && blockedClients > connectedClients) {
            throw new IllegalArgumentException("blockedClients must not exceed connectedClients");
        }
    }

    @Override
    public CapabilitySchema resultSchema() {
        return new CapabilitySchema(SCHEMA_NAME, SCHEMA_VERSION);
    }
}
