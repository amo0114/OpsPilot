package io.github.ismoyuan.opspilot.domain.agentstep;

import java.time.Instant;
import java.util.Objects;

/**
 * 一次 AI 决策步骤的运行记录（04 §59～§61、02 §14）：内部技术记录，不是产品领域对象。runNo 是准入时的轮号快照，stepNo 在整个
 * Investigation 内单调、跨 run 不重置。不保存完整 Prompt、思维链或凭证（04 §60、07 §79）。
 */
public record AgentStep(
        long id,
        long incidentId,
        long investigationId,
        int runNo,
        int stepNo,
        AgentStepStatus status,
        Instant startedAt) {

    public AgentStep {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(startedAt, "startedAt");
        if (runNo < 1 || stepNo < 1) {
            throw new IllegalArgumentException("runNo and stepNo must be >= 1");
        }
    }
}
