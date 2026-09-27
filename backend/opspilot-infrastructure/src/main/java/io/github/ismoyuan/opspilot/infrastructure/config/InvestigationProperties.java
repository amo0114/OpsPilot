package io.github.ismoyuan.opspilot.infrastructure.config;

import io.github.ismoyuan.opspilot.domain.investigation.InvestigationLimits;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 单轮调查限制配置（07 §88，默认值见 04 §16）；只在首次建立 Investigation 时快照，修改不影响已有调查。
 */
@ConfigurationProperties("opspilot.investigation")
public record InvestigationProperties(
        @DefaultValue("12") int maxCapabilityCalls,
        @DefaultValue("480") int maxDurationSeconds,
        @DefaultValue("60") int agentStepTimeoutSeconds,
        @DefaultValue("3") int maxConsecutiveAiFailures) {

    public InvestigationLimits toLimits() {
        return new InvestigationLimits(
                maxCapabilityCalls, maxDurationSeconds, agentStepTimeoutSeconds, maxConsecutiveAiFailures);
    }
}
