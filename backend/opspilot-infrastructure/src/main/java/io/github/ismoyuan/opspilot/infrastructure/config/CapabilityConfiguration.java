package io.github.ismoyuan.opspilot.infrastructure.config;

import io.github.ismoyuan.opspilot.application.capability.CapabilityGuardSettings;
import io.github.ismoyuan.opspilot.domain.capability.CapabilityRegistry;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CapabilityProperties.class)
class CapabilityConfiguration {

    /** 启动时校验（超时非正值则启动失败）。 */
    @Bean
    CapabilityRegistry capabilityRegistry(CapabilityProperties properties) {
        return CapabilityRegistry.v01(properties.timeoutsByKey());
    }

    /** 启动时校验（窗口非正值则启动失败）。 */
    @Bean
    CapabilityGuardSettings capabilityGuardSettings(CapabilityProperties properties) {
        return properties.guardSettings();
    }
}
