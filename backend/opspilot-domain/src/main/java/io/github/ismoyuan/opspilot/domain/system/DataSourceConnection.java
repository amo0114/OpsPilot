package io.github.ismoyuan.opspilot.domain.system;

import java.util.Objects;

/**
 * OpsPilot 获取数据或执行基础设施操作的外部来源，独立于具体资源（03 §9、04 §9）。
 *
 * <p>凭据只以 credentialRef（如 env://KEY）出现，由 SecretResolver 在基础设施层解析；本对象不含明文凭据（03 §12）。
 *
 * @param credentialRef 可为空，表示连接无需凭据
 * @param configPayload 未解码的 JSON 对象文本，只能按 configSchema 经 Codec 解释
 * @param version 对应 lock_version
 */
public record DataSourceConnection(
        long id,
        String connectionKey,
        String name,
        ProviderType providerType,
        String endpoint,
        String credentialRef,
        ConfigSchema configSchema,
        String configPayload,
        ConnectionStatus status,
        long version) {

    public DataSourceConnection {
        Objects.requireNonNull(connectionKey, "connectionKey");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(providerType, "providerType");
        Objects.requireNonNull(endpoint, "endpoint");
        Objects.requireNonNull(configSchema, "configSchema");
        Objects.requireNonNull(configPayload, "configPayload");
        Objects.requireNonNull(status, "status");
    }

    public boolean isActive() {
        return status == ConnectionStatus.ACTIVE;
    }
}
