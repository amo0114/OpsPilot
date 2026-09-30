package io.github.ismoyuan.opspilot.infrastructure.provider;

import io.github.ismoyuan.opspilot.application.capability.logs.LogPatternAggregator;
import io.github.ismoyuan.opspilot.application.capability.logs.LogsSettings;
import io.github.ismoyuan.opspilot.application.capability.metrics.MetricSeriesSummarizer;
import io.github.ismoyuan.opspilot.application.capability.provider.ObserveProvider;
import io.github.ismoyuan.opspilot.application.capability.sanitize.Sanitizer;
import io.github.ismoyuan.opspilot.application.capability.sanitize.SanitizerSettings;
import io.github.ismoyuan.opspilot.application.execution.ServiceRestartExecutor;
import io.github.ismoyuan.opspilot.application.schema.SchemaCodecRegistry;
import io.github.ismoyuan.opspilot.application.secret.SecretResolver;
import io.github.ismoyuan.opspilot.infrastructure.config.ProviderProperties;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OBSERVE Provider 装配（08 TASK-052～057）；配置非法时启动失败。时钟取应用的 Clock Bean（ClockConfiguration），未装配时用 UTC 系统时钟。
 * 日志聚合使用与结果管线相同的脱敏规则（SanitizerSettings），在归一化之前脱敏。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ProviderProperties.class)
class ProviderConfiguration {

    /** 调查上下文也按它限制每次 logs.search 进入 AI 的模式数（06 §122）。 */
    @Bean
    LogsSettings logsSettings(ProviderProperties properties) {
        return properties.logsSettings();
    }

    @Bean
    ObserveProvider prometheusMetricsQueryProvider(
            ProviderProperties properties,
            SchemaCodecRegistry codecs,
            SecretResolver secrets,
            ObjectProvider<Clock> clocks) {
        Clock clock = clocks.getIfAvailable(Clock::systemUTC);
        return new PrometheusMetricsQueryProvider(
                new ProviderHttpClient(clock, properties.responseLimit()),
                new ProviderAuthentication(codecs, secrets),
                new MetricSeriesSummarizer(properties.metricsSettings()),
                clock,
                properties.prometheusMinStep(),
                properties.prometheusMaxPoints());
    }

    @Bean
    ObserveProvider lokiLogsSearchProvider(
            ProviderProperties properties,
            LogsSettings logs,
            SanitizerSettings sanitizer,
            SchemaCodecRegistry codecs,
            SecretResolver secrets,
            ObjectProvider<Clock> clocks) {
        Clock clock = clocks.getIfAvailable(Clock::systemUTC);
        return new LokiLogsSearchProvider(
                new ProviderHttpClient(clock, properties.responseLimit()),
                new ProviderAuthentication(codecs, secrets),
                new LogPatternAggregator(new Sanitizer(sanitizer), logs),
                clock,
                logs.rawMatchLimit());
    }

    @Bean
    ObserveProvider redisCacheInspectProvider(
            ProviderProperties properties,
            SchemaCodecRegistry codecs,
            SecretResolver secrets,
            ObjectProvider<Clock> clocks) {
        Clock clock = clocks.getIfAvailable(Clock::systemUTC);
        return new RedisCacheInspectProvider(
                new RedisAccess(new ProviderAuthentication(codecs, secrets), clock, properties.responseLimit()), clock);
    }

    @Bean
    ObserveProvider mySqlDatabaseInspectProvider(
            SchemaCodecRegistry codecs, SecretResolver secrets, ObjectProvider<Clock> clocks) {
        return new MySqlDatabaseInspectProvider(
                new ProviderAuthentication(codecs, secrets), clocks.getIfAvailable(Clock::systemUTC));
    }

    @Bean
    ObserveProvider redisQueueInspectProvider(
            ProviderProperties properties,
            SchemaCodecRegistry codecs,
            SecretResolver secrets,
            ObjectProvider<Clock> clocks) {
        Clock clock = clocks.getIfAvailable(Clock::systemUTC);
        return new RedisQueueInspectProvider(
                new RedisAccess(new ProviderAuthentication(codecs, secrets), clock, properties.responseLimit()), clock);
    }

    @Bean
    ObserveProvider dockerServiceInspectProvider(
            ProviderProperties properties,
            SchemaCodecRegistry codecs,
            SecretResolver secrets,
            ObjectProvider<Clock> clocks) {
        Clock clock = clocks.getIfAvailable(Clock::systemUTC);
        return new DockerServiceInspectProvider(
                new DockerEngineClient(clock, properties.responseLimit()),
                new ProviderAuthentication(codecs, secrets),
                clock);
    }

    /** service.restart 的执行器（08 TASK-070），与 service.inspect 共用同一 Engine API 客户端实现与连接约束。 */
    @Bean
    ServiceRestartExecutor dockerServiceRestartExecutor(
            ProviderProperties properties,
            SchemaCodecRegistry codecs,
            SecretResolver secrets,
            ObjectProvider<Clock> clocks) {
        Clock clock = clocks.getIfAvailable(Clock::systemUTC);
        return new DockerServiceRestartExecutor(
                new DockerEngineClient(clock, properties.responseLimit()),
                new ProviderAuthentication(codecs, secrets),
                clock);
    }
}
