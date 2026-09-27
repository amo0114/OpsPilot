package io.github.ismoyuan.opspilot.infrastructure.config;

import io.github.ismoyuan.opspilot.domain.investigation.InvestigationLimits;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(InvestigationProperties.class)
class InvestigationConfiguration {

    /** 启动时校验（任一限制 < 1 则启动失败）。 */
    @Bean
    InvestigationLimits investigationLimits(InvestigationProperties properties) {
        return properties.toLimits();
    }
}
