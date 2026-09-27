package io.github.ismoyuan.opspilot.domain.evidence;

import java.time.Instant;
import java.util.Objects;

/**
 * 已持久化的证据关系：创建后不修改、不删除、不版本化（01 §18、DB-INV-004）。判断改变时依靠新的 Observation、新的 Evidence、
 * Hypothesis 当前状态与新的 Diagnosis 表达，不重解释旧关系。
 *
 * @param content 插入时的全部事实
 */
public record Evidence(long id, NewEvidence content, Instant createdAt) {

    public Evidence {
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(createdAt, "createdAt");
    }
}
