package io.github.ismoyuan.opspilot.infrastructure.faultlab;

import io.github.ismoyuan.opspilot.application.faultlab.StatisticsConsumerStopInjector;
import io.github.ismoyuan.opspilot.infrastructure.config.ProviderProperties;
import io.github.ismoyuan.opspilot.infrastructure.provider.DemoControlClient;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Fault Lab 真实注入器装配（08 TASK-093）。只有显式启用（application-demo.yml）时才存在；默认与生产 profile 没有注入器，注入请求得到
 * FAULT_INJECTION_FAILED / INJECTOR_NOT_AVAILABLE（PRODUCTION 系统仍先得到 FAULT_SCENARIO_NOT_ALLOWED）。配置缺项时启动失败。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(FaultLabProperties.class)
class FaultLabConfiguration {

    @Bean
    @ConditionalOnProperty(
            prefix = "opspilot.fault-lab.statistics-consumer-stop",
            name = "enabled",
            havingValue = "true")
    StatisticsConsumerStopInjector statisticsConsumerStopInjector(
            FaultLabProperties properties, ProviderProperties providers, ObjectProvider<Clock> clock) {
        FaultLabProperties.StatisticsConsumerStop config = properties.statisticsConsumerStop();
        requireText(config.systemKey(), "system-key");
        requireText(config.dockerEndpoint(), "docker-endpoint");
        requireText(config.containerName(), "container-name");
        requireText(config.redisEndpoint(), "redis-endpoint");
        requireText(config.streamKey(), "stream-key");
        requireText(config.consumerGroup(), "consumer-group");
        Clock time = clock.getIfAvailable(Clock::systemUTC);
        return new StatisticsConsumerStopInjector(
                new DemoConsumerStopEnvironment(new DemoControlClient(time, providers.responseLimit()), config, time),
                config.settings(),
                config.systemKey(),
                config.targetResourceKeyOrDefault(),
                time);
    }

    private static void requireText(String value, String property) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("opspilot.fault-lab.statistics-consumer-stop." + property + " is required");
        }
    }
}
