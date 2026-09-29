package io.github.ismoyuan.opspilot.application.capability;

/**
 * 执行一次已准入的调用（TASK-048 骨架中的外部调用段）：按 Provider Binding 调用外部系统，把真实结果交给 {@link ObserveResultPipeline}
 * （Sanitizer → RawResultStore → 确定性 ObservationExtractor，TASK-049～051）得到已脱敏结果与 Observation 草稿；各 Provider 由
 * TASK-052～057 提供。
 * 在任何数据库事务之外调用，必须在 {@link AdmittedInvocation#timeout()} 内返回；不做自动业务重试（06 §34）。
 * 预期的外部失败以 {@link InvocationOutcome.Failed} 返回，不抛出。
 */
@FunctionalInterface
public interface CapabilityInvoker {

    InvocationOutcome invoke(AdmittedInvocation invocation);
}
