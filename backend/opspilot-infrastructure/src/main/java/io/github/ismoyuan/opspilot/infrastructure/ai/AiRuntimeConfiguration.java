package io.github.ismoyuan.opspilot.infrastructure.ai;

import io.github.ismoyuan.opspilot.application.ai.AiDecisionPort;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AiRuntimeProperties.class)
class AiRuntimeConfiguration {

    @Bean
    AiDecisionPort aiDecisionPort(AiRuntimeProperties properties) {
        return new HttpAiRuntimeClient(properties, new AiProtocolCodec());
    }
}
