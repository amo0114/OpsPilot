package io.github.ismoyuan.opspilot.domain.investigation;

/**
 * 单轮调查限制（04 §16）：首次建立 Investigation 时从配置快照，之后各轮复用，Continue 不接受客户端额度。
 *
 * @param maxCapabilityCalls 单轮 Capability 准入上限，默认 12
 * @param maxDurationSeconds 单轮墙钟上限，默认 480
 * @param agentStepTimeoutSeconds 一次 AI 请求上限，默认 60
 * @param maxConsecutiveAiFailures 当前轮连续 AI 失败阈值，默认 3
 */
public record InvestigationLimits(
        int maxCapabilityCalls, int maxDurationSeconds, int agentStepTimeoutSeconds, int maxConsecutiveAiFailures) {

    public InvestigationLimits {
        if (maxCapabilityCalls < 1
                || maxDurationSeconds < 1
                || agentStepTimeoutSeconds < 1
                || maxConsecutiveAiFailures < 1) {
            throw new IllegalArgumentException("investigation limits must be >= 1");
        }
    }
}
