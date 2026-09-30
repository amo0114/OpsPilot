package io.github.ismoyuan.opspilot.infrastructure.persistence.mybatis.execution;

import io.github.ismoyuan.opspilot.application.execution.ActionExecutionRepository;
import io.github.ismoyuan.opspilot.domain.execution.ActionExecutionStatus;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/** {@link ActionExecutionRepository} 的 MyBatis 实现；时间以 UTC 毫秒精度存储（04 §3）。 */
@Repository
class MyBatisActionExecutions implements ActionExecutionRepository {

    private final ActionExecutionMapper mapper;

    MyBatisActionExecutions(ActionExecutionMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public long insertPending(NewActionExecution execution) {
        ActionExecutionMapper.GeneratedKey key = new ActionExecutionMapper.GeneratedKey();
        mapper.insertPending(
                key,
                new ActionExecutionMapper.PendingInsert(
                        execution.remediationActionId(),
                        execution.approvalRequestId(),
                        execution.idempotencyKey(),
                        execution.executorKey(),
                        execution.recoveryPolicyId(),
                        execution.recoveryPolicyVersion(),
                        execution.recoveryPolicySnapshot(),
                        execution.executionContextSchemaName(),
                        execution.executionContextSchemaVersion(),
                        execution.executionContextPayload(),
                        execution.maxReconciliationAttempts(),
                        execution.correlationId(),
                        LocalDateTime.ofInstant(execution.createdAt().truncatedTo(ChronoUnit.MILLIS), ZoneOffset.UTC)));
        return key.getId();
    }

    @Override
    public Optional<ExecutionRecord> findById(long executionId) {
        return Optional.ofNullable(mapper.selectRecord(executionId))
                .map(row -> new ExecutionRecord(
                        row.id(),
                        ActionExecutionStatus.valueOf(row.status()),
                        row.lockVersion(),
                        row.remediationActionId(),
                        row.planId(),
                        row.incidentId(),
                        row.context(),
                        instant(row.startedAt()),
                        row.reconciliationAttemptCount(),
                        row.maxReconciliationAttempts(),
                        instant(row.lastReconciliationAt()),
                        instant(row.reconciliationDeadlineAt())));
    }

    @Override
    public boolean markRunning(long executionId, long expectedVersion, String contextPayload, Instant startedAt) {
        return mapper.markRunning(executionId, expectedVersion, contextPayload, utc(startedAt)) == 1;
    }

    @Override
    public boolean registerReconciliation(
            long executionId, long expectedVersion, Instant attemptedAt, Instant deadlineIfFirst) {
        return mapper.registerReconciliation(executionId, expectedVersion, utc(attemptedAt), utc(deadlineIfFirst)) == 1;
    }

    @Override
    public boolean markSucceeded(
            long executionId,
            long expectedVersion,
            String resultSchemaName,
            int resultSchemaVersion,
            String resultPayload,
            Instant finishedAt) {
        return mapper.markSucceeded(
                        executionId,
                        expectedVersion,
                        resultSchemaName,
                        resultSchemaVersion,
                        resultPayload,
                        utc(finishedAt))
                == 1;
    }

    @Override
    public boolean markFailed(
            long executionId,
            ActionExecutionStatus from,
            long expectedVersion,
            String errorCode,
            String errorMessage,
            Instant finishedAt) {
        return mapper.markFailed(executionId, from.name(), expectedVersion, errorCode, errorMessage, utc(finishedAt))
                == 1;
    }

    @Override
    public RecoveryContract findRecoveryContract(long executionId) {
        ActionExecutionMapper.ContractRow row = mapper.selectRecoveryContract(executionId);
        if (row == null) {
            throw new IllegalArgumentException("Unknown action execution: " + executionId);
        }
        return new RecoveryContract(row.recoveryPolicyId(), row.recoveryPolicyVersion(), row.policySnapshot());
    }

    @Override
    public void markPlanExecuted(long planId, Instant at) {
        mapper.updatePlanFromActive(planId, "EXECUTED", utc(at));
    }

    @Override
    public void markPlanCancelled(long planId, Instant at) {
        mapper.updatePlanFromActive(planId, "CANCELLED", utc(at));
    }

    private static Instant instant(LocalDateTime at) {
        return at == null ? null : at.toInstant(ZoneOffset.UTC);
    }

    private static LocalDateTime utc(Instant at) {
        return LocalDateTime.ofInstant(at.truncatedTo(ChronoUnit.MILLIS), ZoneOffset.UTC);
    }

    @Override
    public Optional<ExecutionRef> findByActionId(long remediationActionId) {
        return Optional.ofNullable(mapper.selectByActionId(remediationActionId))
                .map(row -> new ExecutionRef(row.id(), ActionExecutionStatus.valueOf(row.status())));
    }
}
