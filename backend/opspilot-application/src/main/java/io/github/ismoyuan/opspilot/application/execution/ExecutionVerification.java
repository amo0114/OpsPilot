package io.github.ismoyuan.opspilot.application.execution;

import io.github.ismoyuan.opspilot.application.incident.IncidentRepository;
import io.github.ismoyuan.opspilot.application.recovery.RecoveryVerificationCreator;
import io.github.ismoyuan.opspilot.domain.incident.Incident;
import io.github.ismoyuan.opspilot.domain.incident.IncidentTrigger;
import java.time.Instant;

/**
 * 执行确定成功后的衔接（08 TASK-080、04 §79、§82）：在记录 SUCCEEDED 的同一事务内，以 Execution 创建时冻结的恢复合同创建唯一
 * PENDING Verification，并使 Incident EXECUTING → VERIFYING。直接成功与只读核对确认成功共用；不重新选择 ACTIVE 策略，批准后策略
 * 退休或升级不阻断真实执行结果的落账。调用方须已锁定 Incident，并已以条件更新赢得 RUNNING → SUCCEEDED。
 */
final class ExecutionVerification {

    private final ActionExecutionRepository executions;
    private final IncidentRepository incidents;
    private final RecoveryVerificationCreator verifications;

    ExecutionVerification(
            ActionExecutionRepository executions,
            IncidentRepository incidents,
            RecoveryVerificationCreator verifications) {
        this.executions = executions;
        this.incidents = incidents;
        this.verifications = verifications;
    }

    /** @param incident 本事务已锁定、仍为 EXECUTING 的 Incident */
    RecoveryVerificationCreator.Created start(Incident incident, long executionId, Instant now) {
        ActionExecutionRepository.RecoveryContract contract = executions.findRecoveryContract(executionId);
        RecoveryVerificationCreator.Created created = verifications.createPending(
                incident,
                executionId,
                contract.recoveryPolicyId(),
                contract.recoveryPolicyVersion(),
                contract.policySnapshot(),
                now);
        incidents.apply(incident.transitionFor(IncidentTrigger.EXECUTION_SUCCEEDED, incident.version()), now);
        return created;
    }
}
