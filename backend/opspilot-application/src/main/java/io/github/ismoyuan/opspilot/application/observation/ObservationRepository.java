package io.github.ismoyuan.opspilot.application.observation;

import io.github.ismoyuan.opspilot.domain.observation.NewObservation;
import io.github.ismoyuan.opspilot.domain.observation.Observation;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Observation 只插入端口（08 TASK-022）：没有更新或删除方法。插入与来源 Invocation 的一致性在同一语句内核对。
 */
public interface ObservationRepository {

    /**
     * 仅当来源 Invocation 已 SUCCEEDED，且其 Incident、调查/恢复上下文与资源都与 {@code observation} 一致时插入
     * （04 §22、01 §16：查询失败不产生 Observation）。
     *
     * @throws IllegalArgumentException 不满足上述条件，未写入
     */
    Observation insert(NewObservation observation, Instant createdAt);

    Optional<Observation> findById(long id);

    /** 按 id 升序。 */
    List<Observation> findByInvestigationId(long investigationId);
}
