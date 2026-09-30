package io.github.ismoyuan.opspilot.application.remediation;

import io.github.ismoyuan.opspilot.application.ai.protocol.v1.RemediationDraftResponse;
import io.github.ismoyuan.opspilot.application.canonical.CanonicalJsonWriter;
import io.github.ismoyuan.opspilot.application.error.ApplicationException;
import io.github.ismoyuan.opspilot.domain.capability.CapabilityDefinition;
import io.github.ismoyuan.opspilot.domain.error.ErrorCode;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * 校验 AI 的处理建议并由 Java 补全策略字段（08 TASK-064、05 §30 第 3 步、§88，06 §12～§13、§105）。协议层已保证结构、未知字段
 * （riskLevel、requiresApproval、容器身份等出现即 AI_OUTPUT_INVALID）、文本上限与 correlationId 回显；这里只做业务判定：所选
 * （capabilityKey, targetResourceId）必须在调用方给出的 allowedActions 中，否则 AI_INTENT_NOT_ALLOWED；参数写为该能力请求 Schema 的
 * 规范 JSON；riskLevel 与 requiresApproval 取自 Registry 定义。调用方在请求 AI 前与创建方案的事务内（TASK-065）各给出当时计算的
 * allowedActions，因此资源归属、ACTIVE、Binding 与 Provider 的变化都会反映到结果中。
 */
@Service
public class RemediationProposalValidator {

    private final CanonicalJsonWriter json;

    public RemediationProposalValidator(CanonicalJsonWriter json) {
        this.json = json;
    }

    /**
     * @param allowedActions 当时允许的写动作（{@link RemediationActions}）
     * @param diagnosisId Plan 将基于的当前 Diagnosis
     * @throws ApplicationException AI_INTENT_NOT_ALLOWED（details.reason = ACTION_NOT_ALLOWED），不回显 AI 文本
     */
    public ValidatedRemediationProposal validate(
            List<AllowedRemediationAction> allowedActions, long diagnosisId, RemediationDraftResponse response) {
        RemediationDraftResponse.Proposal proposal = response.proposal();
        RemediationDraftResponse.Action action = proposal.action();
        AllowedRemediationAction chosen = allowedActions.stream()
                .filter(allowed -> allowed.matches(action.capabilityKey(), action.targetResourceId()))
                .findFirst()
                .orElseThrow(() -> new ApplicationException(
                        ErrorCode.AI_INTENT_NOT_ALLOWED,
                        "Remediation proposal chose an action outside allowedActions",
                        Map.of(
                                "capabilityKey", action.capabilityKey(),
                                "targetResourceId", action.targetResourceId(),
                                "reason", "ACTION_NOT_ALLOWED")));
        CapabilityDefinition definition = chosen.definition();
        return new ValidatedRemediationProposal(
                diagnosisId,
                proposal.title(),
                proposal.summary(),
                definition.key().key(),
                chosen.resource(),
                definition.requestSchema(),
                json.write(action.parameters()),
                action.summary(),
                action.expectedImpactSummary(),
                definition.defaultRiskLevel(),
                definition.requiresApproval());
    }
}
