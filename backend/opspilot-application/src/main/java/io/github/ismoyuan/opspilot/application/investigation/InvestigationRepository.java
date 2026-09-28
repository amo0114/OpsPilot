package io.github.ismoyuan.opspilot.application.investigation;

import io.github.ismoyuan.opspilot.domain.investigation.Investigation;
import io.github.ismoyuan.opspilot.domain.investigation.InvestigationLimits;
import java.time.Instant;
import java.util.Optional;

/** Investigation 持久化端口；调用方须已在同一事务中锁定所属 Incident（Incident → Investigation 锁序）。 */
public interface InvestigationRepository {

    /**
     * 不加锁判断该 Incident 是否已有 Investigation（TASK-016）：只用于 Start/Continue 决定插入首轮还是锁定既有行。调用方须已持有
     * Incident 行锁（同一 Incident 的 Start/Continue 由此串行），且本事务在取得该锁之前没有做过一致性读，从而能看到已提交的
     * Investigation；对不存在的行加锁读取会取得间隙锁并使不同 Incident 的并发首次 Start 死锁。
     */
    boolean existsForIncident(long incidentId);

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

    /**
     * 写入 {@link Investigation#withAiStepFailure}/{@link Investigation#withAiStepSuccess} 的连续失败计数：以原轮号与原版本为条件，
     * lock_version 加一。
     *
     * @return 写入后的 Investigation
     */
    Investigation saveAiFailureCount(Investigation previous, Investigation updated, Instant at);

    /**
     * 写入 {@link Investigation#withCapabilityCallAdmitted} 的本轮与累计调用计数：以原轮号与原版本为条件，lock_version 加一。
     *
     * @return 写入后的 Investigation
     */
    Investigation saveCapabilityAdmission(Investigation previous, Investigation updated, Instant at);
}
