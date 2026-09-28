package io.github.ismoyuan.opspilot.infrastructure.ai;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Java → AI Runtime 调用配置（07 §88）。token 只来自环境变量（07 §89），为空时调用直接以 AI_RUNTIME_UNAVAILABLE 失败而不发请求；
 * 调查单步的等待上限由调用方给出（05 §89），Remediation 使用 remediationTimeout。
 *
 * @param baseUrl AI Runtime 内部地址，只应在内部网络可达（05 §90）
 * @param token 内部共享 Token，以 Authorization: Bearer 发送，不记录日志
 */
@ConfigurationProperties("opspilot.ai-runtime")
public record AiRuntimeProperties(
        @DefaultValue("http://localhost:8000") URI baseUrl,
        @DefaultValue("") String token,
        @DefaultValue("2s") Duration connectTimeout,
        @DefaultValue("60s") Duration remediationTimeout) {

    public AiRuntimeProperties {
        if (!connectTimeout.isPositive() || !remediationTimeout.isPositive()) {
            throw new IllegalArgumentException("AI runtime timeouts must be positive");
        }
    }

    boolean tokenConfigured() {
        return !token.isBlank();
    }
}
