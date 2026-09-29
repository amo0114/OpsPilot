package io.github.ismoyuan.opspilot.infrastructure.config;

import io.github.ismoyuan.opspilot.application.capability.CapabilityGuardSettings;
import io.github.ismoyuan.opspilot.application.capability.raw.RawResultStore;
import io.github.ismoyuan.opspilot.application.capability.sanitize.SanitizerSettings;
import io.github.ismoyuan.opspilot.domain.capability.CapabilityRegistry;
import io.github.ismoyuan.opspilot.infrastructure.rawresult.LocalFileRawResultStore;
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

    @Bean
    SanitizerSettings sanitizerSettings(CapabilityProperties properties) {
        return properties.sanitizerSettings();
    }

    /** 目录在首次写入时创建。 */
    @Bean
    RawResultStore rawResultStore(CapabilityProperties properties) {
        return new LocalFileRawResultStore(properties.rawResultRoot());
    }
}
