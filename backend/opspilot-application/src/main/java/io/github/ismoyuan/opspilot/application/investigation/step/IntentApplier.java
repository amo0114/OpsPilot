package io.github.ismoyuan.opspilot.application.investigation.step;

import io.github.ismoyuan.opspilot.application.ai.protocol.v1.InvestigationStepResponse;

/**
 * 在结果事务中处置一个当前 run、未被 Stop 阻止的 Intent（08 TASK-040）。调用方已按 Incident → Investigation → Step 持锁；
 * 实现必须把业务拒绝转为 REJECTED 处置而不是让外层事务整体回滚，不得调用网络。
 */
@FunctionalInterface
public interface IntentApplier {

    IntentDisposition apply(ActiveStep step, InvestigationStepResponse response);
}
