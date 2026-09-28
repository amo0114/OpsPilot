package io.github.ismoyuan.opspilot.application.investigation.orchestration;

import io.github.ismoyuan.opspilot.application.ai.protocol.v1.DiagnosisDraftV1;
import io.github.ismoyuan.opspilot.application.ai.protocol.v1.HypothesisUpdate;
import io.github.ismoyuan.opspilot.application.ai.protocol.v1.InvestigationStepResponse;
import io.github.ismoyuan.opspilot.application.ai.protocol.v1.ProposeEvidenceLink;
import io.github.ismoyuan.opspilot.application.ai.protocol.v1.ProposeHypothesis;
import io.github.ismoyuan.opspilot.application.ai.protocol.v1.UpdateHypothesis;
import io.github.ismoyuan.opspilot.application.diagnosis.CreateDiagnosisCommand;
import io.github.ismoyuan.opspilot.application.diagnosis.DiagnosisApplicationService;
import io.github.ismoyuan.opspilot.application.evidence.EvidenceApplicationService;
import io.github.ismoyuan.opspilot.application.evidence.LinkEvidenceCommand;
import io.github.ismoyuan.opspilot.application.hypothesis.HypothesisApplicationService;
import io.github.ismoyuan.opspilot.application.hypothesis.ProposeHypothesisCommand;
import io.github.ismoyuan.opspilot.application.hypothesis.UpdateHypothesisStatusCommand;
import io.github.ismoyuan.opspilot.application.investigation.step.ActiveStep;
import io.github.ismoyuan.opspilot.application.investigation.step.IntentApplier;
import io.github.ismoyuan.opspilot.application.investigation.step.IntentDisposition;
import io.github.ismoyuan.opspilot.domain.diagnosis.DiagnosisDraft;
import io.github.ismoyuan.opspilot.domain.diagnosis.TerminationReason;
import io.github.ismoyuan.opspilot.domain.error.ErrorCode;
import io.github.ismoyuan.opspilot.domain.error.OpsPilotException;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 调查 Intent 分派（08 TASK-040、02 §18、05 §79～§86）。在结果事务中、由记录器确认仍属当前 run 且未被 Stop 阻止后调用，
 * 复用已有用例：PROPOSE_HYPOTHESIS、UPDATE_HYPOTHESIS → Hypothesis 用例；PROPOSE_EVIDENCE_LINK（含同一 Hypothesis 的附带更新）→
 * Evidence 用例；COMPLETE_INVESTIGATION → Diagnosis 创建事务（AGENT_COMPLETED）；REQUEST_CAPABILITY 不在事务内执行，返回 ACCEPTED
 * 由编排器在提交后经 Capability 准入处理。
 *
 * <p>每个 Intent 在保存点（嵌套事务）中执行：业务拒绝回滚到保存点，只撤销该 Intent 的写入，Step 审计与拒绝原因仍随结果事务提交
 * （05 §83）。证据与诊断只能引用本轮或以前 Diagnosis 冻结范围内的观测/证据（B10-R1）。
 */
@Service
public class IntentDispatcher implements IntentApplier {

    private final HypothesisApplicationService hypotheses;
    private final EvidenceApplicationService evidence;
    private final DiagnosisApplicationService diagnoses;
    private final ReferenceScopeQuery scope;
    private final TransactionTemplate savepoint;

    public IntentDispatcher(
            HypothesisApplicationService hypotheses,
            EvidenceApplicationService evidence,
            DiagnosisApplicationService diagnoses,
            ReferenceScopeQuery scope,
            PlatformTransactionManager transactionManager) {
        this.hypotheses = hypotheses;
        this.evidence = evidence;
        this.diagnoses = diagnoses;
        this.scope = scope;
        this.savepoint = new TransactionTemplate(transactionManager);
        this.savepoint.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
    }

    @Override
    public IntentDisposition apply(ActiveStep step, InvestigationStepResponse response) {
        if (response instanceof InvestigationStepResponse.RequestCapabilityStep) {
            return IntentDisposition.of(IntentDisposition.Outcome.ACCEPTED);
        }
        try {
            savepoint.executeWithoutResult(status -> applyInSavepoint(step, response));
            return IntentDisposition.of(IntentDisposition.Outcome.APPLIED);
        } catch (Rejection rejection) {
            return IntentDisposition.rejected(rejection.code, rejection.reason);
        } catch (OpsPilotException rejected) {
            Object reason = rejected.details().get("reason");
            return IntentDisposition.rejected(rejected.errorCode().name(), reason == null ? null : reason.toString());
        }
    }

    private void applyInSavepoint(ActiveStep step, InvestigationStepResponse response) {
        switch (response) {
            case InvestigationStepResponse.ProposeHypothesisStep s -> propose(step, s.proposeHypothesis());
            case InvestigationStepResponse.UpdateHypothesisStep s -> update(step, s.updateHypothesis());
            case InvestigationStepResponse.ProposeEvidenceLinkStep s ->
                link(step, s.proposeEvidenceLink(), s.hypothesisUpdate());
            case InvestigationStepResponse.CompleteInvestigationStep s ->
                complete(step, s.completeInvestigation().diagnosis());
            case InvestigationStepResponse.RequestCapabilityStep s ->
                throw new IllegalStateException("capability requests are handled after commit");
        }
    }

    private void propose(ActiveStep step, ProposeHypothesis intent) {
        hypotheses.proposeHypothesis(
                new ProposeHypothesisCommand(step.incidentId(), intent.title(), intent.description()));
    }

    private void update(ActiveStep step, UpdateHypothesis intent) {
        hypotheses.updateHypothesisStatus(new UpdateHypothesisStatusCommand(
                step.incidentId(), intent.hypothesisId(), intent.targetStatus(), intent.reason()));
    }

    private void link(ActiveStep step, ProposeEvidenceLink intent, HypothesisUpdate update) {
        if (!scope.isObservationInScope(step.investigationId(), step.runNo(), intent.observationId())) {
            throw new Rejection(ErrorCode.REQUEST_VALIDATION_FAILED.name(), "OBSERVATION_OUT_OF_SCOPE");
        }
        evidence.createEvidenceLink(new LinkEvidenceCommand(
                step.incidentId(),
                intent.observationId(),
                intent.hypothesisId(),
                intent.relation(),
                intent.reason(),
                update == null ? null : update.targetStatus()));
    }

    private void complete(ActiveStep step, DiagnosisDraftV1 draft) {
        Set<Long> outside = scope.evidenceOutOfScope(step.investigationId(), step.runStartedAt(), draft.evidenceIds());
        if (!outside.isEmpty()) {
            throw new Rejection(ErrorCode.DIAGNOSIS_INVARIANT_VIOLATION.name(), "EVIDENCE_OUT_OF_SCOPE");
        }
        diagnoses.createDiagnosis(new CreateDiagnosisCommand(
                step.incidentId(),
                step.runNo(),
                new DiagnosisDraft(
                        draft.conclusionType(),
                        draft.primaryHypothesisId(),
                        draft.summary(),
                        draft.impactSummary(),
                        draft.evidenceIds()),
                TerminationReason.AGENT_COMPLETED));
    }

    /** 分派层自身的拒绝（引用范围），同样回滚到保存点。 */
    private static final class Rejection extends RuntimeException {
        private final String code;
        private final String reason;

        Rejection(String code, String reason) {
            super(code + ":" + reason, null, false, false);
            this.code = code;
            this.reason = reason;
        }
    }
}
