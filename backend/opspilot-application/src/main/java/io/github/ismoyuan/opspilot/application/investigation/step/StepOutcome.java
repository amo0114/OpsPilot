package io.github.ismoyuan.opspilot.application.investigation.step;

/**
 * 记录 Step 结果后的当前情况，供结果处理决定是否继续（TASK-040/041）。
 *
 * @param currentRun Incident 仍在调查且当前 run 就是该 Step 的 run；为 false 时是迟到输出，只作审计，不能驱动领域写入
 * @param stopRequested 当前 run 已提交 Stop
 * @param consecutiveAiFailures 记录后当前 run 的连续 AI 失败数
 */
public record StepOutcome(boolean currentRun, boolean stopRequested, int consecutiveAiFailures) {}
