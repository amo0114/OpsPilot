package io.github.ismoyuan.opspilot.application.capability.extract;

/** cache-status.observation / 1（06 §65、§117）：Redis 状态白名单统计，不含 Key 名或 Value；空值表示本次未取得。 */
public record CacheStatusObservationV1(
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
        Long uptimeSeconds) {

    public static final String SCHEMA_NAME = "cache-status.observation";
    public static final int SCHEMA_VERSION = 1;
}
