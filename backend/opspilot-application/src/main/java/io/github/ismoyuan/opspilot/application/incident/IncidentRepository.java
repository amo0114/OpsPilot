package io.github.ismoyuan.opspilot.application.incident;

import io.github.ismoyuan.opspilot.domain.incident.Incident;
import io.github.ismoyuan.opspilot.domain.incident.IncidentKey;
import io.github.ismoyuan.opspilot.domain.incident.IncidentTransition;
import io.github.ismoyuan.opspilot.domain.incident.NewIncident;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.Optional;

/**
 * Incident 持久化端口。没有通用 updateStatus（07 §35）：状态只能经 {@link #apply} 以
 * expectedStatus＋expectedVersion 条件更新，调用方须处在用例事务中。
 */
public interface IncidentRepository {

    Optional<Incident> findById(long id);

    /** 按字节精确匹配编号。 */
    Optional<Incident> findByKey(IncidentKey incidentKey);

    /**
     * 按字节精确匹配编号并对该行加排他锁（SELECT … FOR UPDATE）；运行控制事务按 Incident → Investigation 顺序加锁（05 §27）。
     */
    Optional<Incident> findByKeyForUpdate(IncidentKey incidentKey);

    /**
     * 执行一次已由策略验证的迁移：status 与 lock_version 同时匹配才更新，lock_version 加一；
     * 目标为 RESOLVED 时同一语句写入 resolved_at。
     *
     * @return 迁移后的 Incident
     * @throws io.github.ismoyuan.opspilot.application.error.ApplicationException 未命中时按当前行给出
     *     INCIDENT_NOT_FOUND、INCIDENT_STATE_CONFLICT 或 INCIDENT_VERSION_CONFLICT（含 currentStatus、version）
     */
    Incident apply(IncidentTransition transition, Instant at);

    /** 当日已用的最大编号序号，没有则为 0；只用于分配候选编号，唯一性由 uk_incident_key 保证。 */
    int lastSequenceOn(LocalDate day);

    /**
     * 以 CREATED、version 0 插入。
     *
     * @throws IncidentKeyTakenException 编号已被并发创建占用
     */
    Incident insert(NewIncident incident, IncidentKey incidentKey, Instant createdAt);

    /** 记录受影响资源；归属与去重由调用方在同一事务内校验。 */
    void addAffectedResources(long incidentId, Collection<Long> managedResourceIds, Instant createdAt);
}
