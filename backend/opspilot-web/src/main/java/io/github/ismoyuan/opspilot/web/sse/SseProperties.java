package io.github.ismoyuan.opspilot.web.sse;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Incident 事件流配置（05 §66、07 §71～§74）：{@code opspilot.sse.heartbeat-interval}（默认 15 秒；每次 heartbeat 同时按游标补读一次，
 * 唤醒丢失也能自愈）、{@code emitter-timeout}（默认 30 分钟，到期由浏览器带 Last-Event-ID 自动重连）。
 */
@ConfigurationProperties("opspilot.sse")
public record SseProperties(Duration heartbeatInterval, Duration emitterTimeout) {

    public static final Duration DEFAULT_HEARTBEAT_INTERVAL = Duration.ofSeconds(15);
    public static final Duration DEFAULT_EMITTER_TIMEOUT = Duration.ofMinutes(30);

    public SseProperties {
        heartbeatInterval = positive(heartbeatInterval, DEFAULT_HEARTBEAT_INTERVAL, "heartbeatInterval");
        emitterTimeout = positive(emitterTimeout, DEFAULT_EMITTER_TIMEOUT, "emitterTimeout");
    }

    private static Duration positive(Duration configured, Duration fallback, String name) {
        if (configured == null) {
            return fallback;
        }
        if (configured.isNegative() || configured.isZero()) {
            throw new IllegalArgumentException("opspilot.sse." + name + " must be positive");
        }
        return configured;
    }
}
