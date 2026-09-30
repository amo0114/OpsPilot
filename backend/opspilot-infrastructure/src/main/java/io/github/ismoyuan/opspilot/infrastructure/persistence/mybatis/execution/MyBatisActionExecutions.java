package io.github.ismoyuan.opspilot.infrastructure.persistence.mybatis.execution;

import io.github.ismoyuan.opspilot.application.execution.ActionExecutionRepository;
import io.github.ismoyuan.opspilot.domain.execution.ActionExecutionStatus;
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
    public Optional<ExecutionRef> findByActionId(long remediationActionId) {
        return Optional.ofNullable(mapper.selectByActionId(remediationActionId))
                .map(row -> new ExecutionRef(row.id(), ActionExecutionStatus.valueOf(row.status())));
    }
}
