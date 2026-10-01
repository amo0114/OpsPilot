package io.github.ismoyuan.opspilot.application.faultlab;

import io.github.ismoyuan.opspilot.domain.faultlab.FaultExperimentStatus;
import java.time.Instant;
import java.util.Optional;

/**
 * fault_experiment 的生命周期端口（04 §63）。状态只按期望来源状态条件推进。本端口的读取结果不含 Ground Truth：Ground Truth 只在插入时
 * 写入，读取只经 {@link io.github.ismoyuan.opspilot.application.faultlab.evaluation.GroundTruthQuery}（04 §64）。
 */
public interface FaultExperimentRepository {

    /** 锁定 managed_system 父行：同一系统的注入与 Reset 串行，锁序为 系统 → 实验。 */
    void lockSystem(long managedSystemId);

    /**
     * 该系统是否有 INJECTING、ACTIVE 或 RESETTING 的实验（{@code exceptId} 除外，可为空）；调用方须已持有系统行锁。必须是当前读：调用方事务
     * 的快照可能早于取得系统行锁（B33-R1 P1）。
     */
    boolean existsInProgress(long managedSystemId, Long exceptId);

    /**
     * 插入 INJECTING 实验并写入 Ground Truth。
     *
     * @param groundTruthPayload fault-lab.ground-truth / 1 的 JSON
     * @return 新实验 id
     */
    long insertInjecting(
            String scenarioKey,
            long managedSystemId,
            long targetResourceId,
            String groundTruthPayload,
            Instant createdAt);

    /** 实验所属系统（不加锁）；用于先锁系统再锁实验。 */
    Optional<Long> findSystemId(long experimentId);

    Optional<FaultExperimentRecord> findForUpdate(long experimentId);

    /** INJECTING → ACTIVE，同时写入 Incident 与故障生效时间。@return 是否由本次推进 */
    boolean markActive(long experimentId, long incidentId, Instant injectedAt, Instant now);

    /** {@code from}（INJECTING 或 RESETTING）→ FAILED，写入脱敏的错误信息。@return 是否由本次推进 */
    boolean markFailed(long experimentId, FaultExperimentStatus from, String errorMessage, Instant now);

    /** {@code from}（ACTIVE 或 FAILED）→ RESETTING。@return 是否由本次推进 */
    boolean markResetting(long experimentId, FaultExperimentStatus from, Instant now);

    /** RESETTING → RESET，写入重置完成时间。@return 是否由本次推进 */
    boolean markReset(long experimentId, Instant resetAt);

    /** 启动路径：把本进程启动前就停在 INJECTING / RESETTING 的实验标为 FAILED（旧进程的外部动作结果未知）。 */
    int markInterrupted(Instant startedBefore, String errorMessage, Instant now);

    /**
     * 一次实验（不含 Ground Truth）。
     *
     * @param incidentId 注入确认生效前或未生效时为空
     * @param injectedAt 故障生效时间；未确认生效为空
     */
    record FaultExperimentRecord(
            long id,
            String scenarioKey,
            long managedSystemId,
            String systemKey,
            String systemEnvironment,
            long targetResourceId,
            String targetResourceKey,
            Long incidentId,
            FaultExperimentStatus status,
            Instant injectedAt,
            Instant resetAt) {}
}
