package io.github.ismoyuan.opspilot.infrastructure.faultlab;

import io.github.ismoyuan.opspilot.application.faultlab.MysqlSlowQueryInjector;
import io.github.ismoyuan.opspilot.application.faultlab.RedisLatencyInjector;
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
 * Fault Lab 真实注入器装配（08 TASK-093～095）。各注入器只有显式启用（application-demo.yml）时才存在；默认与生产 profile 没有注入器，注入请求得到
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
        String prefix = "opspilot.fault-lab.statistics-consumer-stop.";
        requireText(config.systemKey(), prefix + "system-key");
        requireText(config.dockerEndpoint(), prefix + "docker-endpoint");
        requireText(config.containerName(), prefix + "container-name");
        requireText(config.redisEndpoint(), prefix + "redis-endpoint");
        requireText(config.streamKey(), prefix + "stream-key");
        requireText(config.consumerGroup(), prefix + "consumer-group");
        Clock time = clock.getIfAvailable(Clock::systemUTC);
        return new StatisticsConsumerStopInjector(
                new DemoConsumerStopEnvironment(new DemoControlClient(time, providers.responseLimit()), config, time),
                config.settings(),
                config.systemKey(),
                config.targetResourceKeyOrDefault(),
                time);
    }

    @Bean
    @ConditionalOnProperty(prefix = "opspilot.fault-lab.redis-latency", name = "enabled", havingValue = "true")
    RedisLatencyInjector redisLatencyInjector(
            FaultLabProperties properties, ProviderProperties providers, ObjectProvider<Clock> clock) {
        FaultLabProperties.RedisLatency config = properties.redisLatency();
        String prefix = "opspilot.fault-lab.redis-latency.";
        requireText(config.systemKey(), prefix + "system-key");
        if (config.toxiproxyEndpoint() == null) {
            throw new IllegalStateException(prefix + "toxiproxy-endpoint is required");
        }
        requireText(config.proxyRedisEndpoint(), prefix + "proxy-redis-endpoint");
        requireText(config.redisEndpoint(), prefix + "redis-endpoint");
        requireText(config.streamKey(), prefix + "stream-key");
        requireText(config.consumerGroup(), prefix + "consumer-group");
        Clock time = clock.getIfAvailable(Clock::systemUTC);
        return new RedisLatencyInjector(
                new DemoRedisLatencyEnvironment(
                        new DemoControlClient(time, providers.responseLimit()),
                        new ToxiproxyClient(config.toxiproxyEndpoint(), config.proxyNameOrDefault(), time),
                        config,
                        time),
                config.settings(),
                config.systemKey(),
                config.targetResourceKeyOrDefault(),
                time);
    }

    @Bean
    @ConditionalOnProperty(prefix = "opspilot.fault-lab.mysql-slow-query", name = "enabled", havingValue = "true")
    MysqlSlowQueryInjector mysqlSlowQueryInjector(FaultLabProperties properties, ObjectProvider<Clock> clock) {
        FaultLabProperties.MysqlSlowQuery config = properties.mysqlSlowQuery();
        String prefix = "opspilot.fault-lab.mysql-slow-query.";
        requireText(config.systemKey(), prefix + "system-key");
        if (config.managementEndpoint() == null || config.shortlinkEndpoint() == null) {
            throw new IllegalStateException(prefix + "management-endpoint and shortlink-endpoint are required");
        }
        requireText(config.controlJdbcUrl(), prefix + "control-jdbc-url");
        requireText(config.controlUsername(), prefix + "control-username");
        requireText(config.createUsername(), prefix + "create-username");
        requireText(config.createGroupId(), prefix + "create-group-id");
        Clock time = clock.getIfAvailable(Clock::systemUTC);
        return new MysqlSlowQueryInjector(
                new DemoMysqlSlowQueryEnvironment(config, time),
                config.settings(),
                config.systemKey(),
                config.targetResourceKeyOrDefault(),
                time);
    }

    private static void requireText(String value, String property) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(property + " is required");
        }
    }
}
