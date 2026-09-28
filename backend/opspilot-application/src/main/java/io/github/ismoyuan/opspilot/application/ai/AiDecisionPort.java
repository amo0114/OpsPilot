package io.github.ismoyuan.opspilot.application.ai;

import io.github.ismoyuan.opspilot.application.ai.protocol.v1.InvestigationStepRequest;
import io.github.ismoyuan.opspilot.application.ai.protocol.v1.RemediationDraftRequest;
import io.github.ismoyuan.opspilot.application.ai.protocol.v1.RemediationDraftResponse;
import java.time.Duration;

/**
 * 调用 AI Runtime 的出站端口（07 §18、02 §20）：Java 单向同步调用，AI 只返回结构化 Intent/Proposal（BND-003、BND-010）。
 * 返回值已通过协议校验，但回显的 runNo/stepId 不是授权，业务合法性仍由 Java 用例事务判定（BND-015）。
 * HTTP 实现见 infrastructure/ai/HttpAiRuntimeClient（08 TASK-034）。
 */
public interface AiDecisionPort {

    /**
     * POST /internal/v1/investigation/step（05 §76～§86）。只发一次请求，不透明重试（07 §86）；再次询问由编排显式开启新 Step。
     *
     * @param maxWait 本次最多等待时长：调用方取单步超时与本轮剩余时间的较小值（05 §89），必须为正
     * @return 结果及模型/Prompt 版本/Token 元数据（AgentStep 记录用，08 TASK-038）
     * @throws io.github.ismoyuan.opspilot.application.error.ApplicationException AI_RUNTIME_TIMEOUT、AI_RUNTIME_UNAVAILABLE
     *     或 AI_OUTPUT_INVALID（含回显的 runNo/stepId 与请求不符）
     */
    InvestigationStepDecision decideInvestigationStep(InvestigationStepRequest request, Duration maxWait);

    /**
     * POST /internal/v1/remediation/draft（05 §87～§88），等待上限取配置；失败时调用方保持 DIAGNOSED 且不产生半套记录（05 §31）。
     *
     * @throws io.github.ismoyuan.opspilot.application.error.ApplicationException 同上；回显的 correlationId 不符为 AI_OUTPUT_INVALID
     */
    RemediationDraftResponse draftRemediation(RemediationDraftRequest request);
}
