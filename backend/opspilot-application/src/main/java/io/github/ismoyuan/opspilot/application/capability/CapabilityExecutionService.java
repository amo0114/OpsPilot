package io.github.ismoyuan.opspilot.application.capability;

import io.github.ismoyuan.opspilot.application.ai.protocol.v1.RequestCapability;
import io.github.ismoyuan.opspilot.domain.error.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * 调查 OBSERVE 调用的执行骨架（08 TASK-048、07 §59）：准入短事务（{@link CapabilityAdmissionService}）→ 事务外调用
 * {@link CapabilityInvoker} → 结果短事务（{@link CapabilityResultRecorder}）。三段之间不持有数据库事务。
 *
 * <p>Invoker 的生产实现（Provider＋Sanitizer＋RawResultStore＋Extractor）由 TASK-049～057 提供，调查循环在 TASK-058 接入本服务；
 * 此前没有 Invoker 时拒绝执行且不做准入，不会扣预算后空转。Invoker 抛出的意外异常按 CAPABILITY_INVOCATION_FAILED 记录，调查继续
 * （06 §126）；成功结果无法落账时改记失败，仍无法落账则 Invocation 保持 RUNNING，留待中断标记。落账时 Invocation 已是终态
 * （如已被中断标记）则返回 {@link CapabilityExecutionResult.Discarded}，不把未写入的结果报告为成功或失败（B14-R1）。
 */
@Service
public class CapabilityExecutionService {

    private static final Logger log = LoggerFactory.getLogger(CapabilityExecutionService.class);

    static final String UNEXPECTED_FAILURE = "Capability invocation failed unexpectedly";
    static final String UNRECORDED_RESULT = "Capability result could not be recorded";

    private final CapabilityAdmissionService admissions;
    private final CapabilityResultRecorder results;
    private final ObjectProvider<CapabilityInvoker> invokers;

    public CapabilityExecutionService(
            CapabilityAdmissionService admissions,
            CapabilityResultRecorder results,
            ObjectProvider<CapabilityInvoker> invokers) {
        this.admissions = admissions;
        this.results = results;
        this.invokers = invokers;
    }

    /**
     * @throws IllegalStateException 尚无 CapabilityInvoker（在任何准入之前）
     */
    public CapabilityExecutionResult execute(long incidentId, int runNo, RequestCapability request) {
        CapabilityInvoker invoker = invokers.getIfAvailable();
        if (invoker == null) {
            throw new IllegalStateException("No capability invoker is configured");
        }
        CapabilityAdmission admission = admissions.admit(incidentId, runNo, request);
        return switch (admission) {
            case CapabilityAdmission.NotAdmitted notAdmitted ->
                new CapabilityExecutionResult.NotAdmitted(notAdmitted.reason());
            case CapabilityAdmission.Rejected rejected ->
                new CapabilityExecutionResult.Rejected(rejected.code(), rejected.reason());
            case CapabilityAdmission.Admitted admitted -> run(invoker, admitted.invocation());
        };
    }

    private CapabilityExecutionResult run(CapabilityInvoker invoker, AdmittedInvocation invocation) {
        InvocationOutcome outcome;
        try {
            outcome = invoker.invoke(invocation);
        } catch (RuntimeException ex) {
            log.warn(
                    "Capability invoker failed: invocationId={} capability={} exception={}",
                    invocation.invocationId(),
                    invocation.definition().key().key(),
                    ex.getClass().getName());
            outcome = new InvocationOutcome.Failed(ErrorCode.CAPABILITY_INVOCATION_FAILED, UNEXPECTED_FAILURE);
        }
        return switch (outcome) {
            case InvocationOutcome.Succeeded succeeded -> recordSucceeded(invocation, succeeded);
            case InvocationOutcome.Failed failed -> recordFailed(invocation, failed.errorCode(), failed.safeMessage());
        };
    }

    private CapabilityExecutionResult recordSucceeded(
            AdmittedInvocation invocation, InvocationOutcome.Succeeded succeeded) {
        try {
            return results.recordSucceeded(invocation.invocationId(), succeeded)
                    .<CapabilityExecutionResult>map(observationIds ->
                            new CapabilityExecutionResult.Succeeded(invocation.invocationId(), observationIds))
                    .orElseGet(() -> new CapabilityExecutionResult.Discarded(invocation.invocationId()));
        } catch (RuntimeException ex) {
            log.warn(
                    "Capability result could not be recorded: invocationId={} exception={}",
                    invocation.invocationId(),
                    ex.getClass().getName());
            return recordFailed(invocation, ErrorCode.CAPABILITY_INVOCATION_FAILED, UNRECORDED_RESULT);
        }
    }

    /** 已是终态时本次失败没有写入，如实报告为未采用（B14-R1）。 */
    private CapabilityExecutionResult recordFailed(AdmittedInvocation invocation, ErrorCode code, String message) {
        return results.recordFailed(invocation.invocationId(), code, message)
                ? new CapabilityExecutionResult.Failed(invocation.invocationId(), code)
                : new CapabilityExecutionResult.Discarded(invocation.invocationId());
    }
}
