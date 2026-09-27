package io.github.ismoyuan.opspilot.domain.observation;

import java.time.Instant;
import java.util.Objects;

/**
 * 已持久化的观测：创建后不修改、不删除（01 §34、INV-008）。新事实只能插入新 Observation。
 *
 * @param content 插入时的全部事实
 */
public record Observation(long id, NewObservation content, Instant createdAt) {

    public Observation {
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(createdAt, "createdAt");
    }

    public boolean isInvestigationObservation() {
        return content.investigationId() != null;
    }
}
