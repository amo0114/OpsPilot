package io.github.ismoyuan.opspilot.infrastructure.config;

import io.github.ismoyuan.opspilot.application.execution.ExecutionSettings;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ExecutionProperties.class)
class ExecutionConfiguration {

    /** 启动时校验（上限或核对时长非正值则启动失败）。 */
    @Bean
    ExecutionSettings executionSettings(ExecutionProperties properties) {
        return properties.settings();
    }
}
