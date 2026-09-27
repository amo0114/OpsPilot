package io.github.ismoyuan.opspilot.application.ai;

import io.github.ismoyuan.opspilot.application.ai.protocol.v1.InvestigationStepRequest;
import io.github.ismoyuan.opspilot.application.ai.protocol.v1.InvestigationStepResponse;
import io.github.ismoyuan.opspilot.application.ai.protocol.v1.RemediationDraftRequest;
import io.github.ismoyuan.opspilot.application.ai.protocol.v1.RemediationDraftResponse;

/**
 * 调用 AI Runtime 的出站端口（07 §18、02 §20）：Java 单向同步调用，AI 只返回结构化 Intent/Proposal（BND-003、BND-010）。
 * 返回值已通过协议校验，但回显的 runNo/stepId 不是授权，业务合法性仍由 Java 用例事务判定（BND-015）。
 * HTTP 实现、超时与认证由 TASK-034 在 infrastructure 提供。
 */
public interface AiDecisionPort {

    /** POST /internal/v1/investigation/step（05 §76～§86）。 */
    InvestigationStepResponse decideInvestigationStep(InvestigationStepRequest request);

    /** POST /internal/v1/remediation/draft（05 §87～§88）。 */
    RemediationDraftResponse draftRemediation(RemediationDraftRequest request);
}
