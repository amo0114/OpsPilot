package io.github.ismoyuan.opspilot.infrastructure.dispatch;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 启动恢复与存活期间补派发（07 §51、§88：dispatcher.recoveryScanIntervalSeconds=5）。
 *
 * @param recoveryEnabled 关闭时不自动运行启动恢复与周期扫描（仅供以测试替身替换派发器的测试隔离使用）
 */
@ConfigurationProperties("opspilot.dispatcher")
public record DispatcherProperties(
        @DefaultValue("5") int recoveryScanIntervalSeconds,
        @DefaultValue("true") boolean recoveryEnabled) {

    public DispatcherProperties {
        if (recoveryScanIntervalSeconds < 1) {
            throw new IllegalArgumentException("recoveryScanIntervalSeconds must be >= 1");
        }
    }
}
