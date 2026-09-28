package io.github.ismoyuan.opspilot.application.capability;

import io.github.ismoyuan.opspilot.domain.system.DataSourceConnection;
import io.github.ismoyuan.opspilot.domain.system.ResourceBinding;
import io.github.ismoyuan.opspilot.domain.system.binding.ResourceSelector;
import java.util.Objects;

/**
 * 某资源执行某 Capability 时唯一确定的 Provider Binding（06 §17～§18）：ACTIVE 数据源连接、资源在其中的绑定，以及按类型解码的选择器。
 * 凭据仍只以 credentialRef 形式留在连接上，由 Provider 在基础设施层解析（06 §20）。
 */
public record ProviderBinding(DataSourceConnection connection, ResourceBinding binding, ResourceSelector selector) {

    public ProviderBinding {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(binding, "binding");
        Objects.requireNonNull(selector, "selector");
    }
}
