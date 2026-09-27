package io.github.ismoyuan.opspilot.application.investigation;

import io.github.ismoyuan.opspilot.domain.investigation.Investigation;
import io.github.ismoyuan.opspilot.domain.investigation.InvestigationLimits;
import java.time.Instant;
import java.util.Optional;

/** Investigation 持久化端口；调用方须已在同一事务中锁定所属 Incident（Incident → Investigation 锁序）。 */
public interface InvestigationRepository {

    /** 加排他锁读取。 */
    Optional<Investigation> findByIncidentIdForUpdate(long incidentId);

    /** 首次开始：以 run 1 创建唯一 Investigation，限制值为配置快照（UNIQUE(incident_id) 兜底）。 */
    Investigation insertFirstRun(long incidentId, InvestigationLimits limits, Instant now);

    /**
     * 写入 {@link Investigation#nextRun} 的结果：以 previous 的轮号与 lock_version 为条件，lock_version 加一。
     *
     * @return 写入后的 Investigation
     */
    Investigation saveNextRun(Investigation previous, Investigation next);

    /**
     * 写入 {@link Investigation#withStopRequested} 的结果：以原轮号、原版本且尚未 Stop 为条件，lock_version 加一。
     *
     * @return 写入后的 Investigation
     */
    Investigation saveStopRequest(Investigation previous, Investigation stopped);
}
