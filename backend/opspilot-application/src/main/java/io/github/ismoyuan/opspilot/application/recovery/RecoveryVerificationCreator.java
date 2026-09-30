package io.github.ismoyuan.opspilot.application.recovery;

import io.github.ismoyuan.opspilot.application.dispatch.WorkDispatcher;
import io.github.ismoyuan.opspilot.application.recovery.RecoveryVerificationRepository.NewRecoveryVerification;
import io.github.ismoyuan.opspilot.application.schema.SchemaCodecRegistry;
import io.github.ismoyuan.opspilot.domain.incident.Incident;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 在调用方的状态事务内创建一次 PENDING RecoveryVerification（04 §50～§54、§79～§80，08 TASK-080～081）：按已冻结的策略身份与完整快照
 * 写入，verification_no 按 Incident 递增，deadline_at＝创建时间＋快照 maxDurationSeconds，此后不刷新。提交之后才派发 Verification
 * Worker；派发失败只记录，PENDING 由补派发来源恢复（TASK-083）。
 *
 * <p>前提：调用方已在同一事务中持有该 Incident 的行锁，并负责 Incident 状态迁移与时间线。本类不选择策略——执行成功路径使用 Execution
 * 创建时冻结的快照（不重新查询 ACTIVE 策略），外部处理入口在自己的创建事务中先选定策略再交给本类。
 *
 * <p>派发器经 {@link ObjectProvider} 到 afterCommit 时才取得：派发器创建时要取得执行 Worker，而执行 Worker 的成功事务依赖本类；
 * 构造期直接依赖派发器会形成创建环，正式应用无法启动（B29-R1）。
 */
@Service
public class RecoveryVerificationCreator {

    private static final Logger log = LoggerFactory.getLogger(RecoveryVerificationCreator.class);

    private final RecoveryVerificationRepository verifications;
    private final SchemaCodecRegistry codecs;
    private final ObjectProvider<WorkDispatcher> dispatcher;

    public RecoveryVerificationCreator(
            RecoveryVerificationRepository verifications,
            SchemaCodecRegistry codecs,
            ObjectProvider<WorkDispatcher> dispatcher) {
        this.verifications = verifications;
        this.codecs = codecs;
        this.dispatcher = dispatcher;
    }

    /** 新建的 Verification。 */
    public record Created(long verificationId, int verificationNo, Instant deadlineAt) {}

    /**
     * @param incident 本事务已锁定的 Incident
     * @param actionExecutionId 执行成功路径为该 Execution，外部处理为空
     * @param policySnapshot recovery.policy.snapshot / 1 的 JSON（已冻结，原样复制）
     */
    public Created createPending(
            Incident incident,
            Long actionExecutionId,
            long recoveryPolicyId,
            int recoveryPolicyVersion,
            String policySnapshot,
            Instant now) {
        RecoveryPolicySnapshotV1 snapshot = codecs.decode(
                RecoveryPolicySnapshotV1.SCHEMA_NAME,
                RecoveryPolicySnapshotV1.SCHEMA_VERSION,
                policySnapshot,
                RecoveryPolicySnapshotV1.class);
        if (snapshot.policyId() != recoveryPolicyId || snapshot.policyVersion() != recoveryPolicyVersion) {
            throw new IllegalStateException("Policy snapshot does not match its policy identity");
        }
        int verificationNo = verifications.nextVerificationNo(incident.id());
        Instant deadlineAt = now.plusSeconds(snapshot.maxDurationSeconds());
        long verificationId = verifications.insertPending(new NewRecoveryVerification(
                incident.id(),
                actionExecutionId,
                snapshot.managedResourceId(),
                recoveryPolicyId,
                recoveryPolicyVersion,
                policySnapshot,
                verificationNo,
                deadlineAt,
                now));
        dispatchAfterCommit(verificationId);
        return new Created(verificationId, verificationNo, deadlineAt);
    }

    private void dispatchAfterCommit(long verificationId) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    dispatcher.getObject().dispatchRecoveryVerification(verificationId);
                } catch (RuntimeException ex) {
                    log.warn(
                            "Verification dispatch failed after commit: verificationId={} exception={}",
                            verificationId,
                            ex.getClass().getName());
                }
            }
        });
    }
}
