package io.github.ismoyuan.opspilot.application.investigation.step;

import io.github.ismoyuan.opspilot.application.incident.IncidentRepository;
import io.github.ismoyuan.opspilot.application.investigation.InvestigationRepository;
import io.github.ismoyuan.opspilot.domain.agentstep.AgentStep;
import io.github.ismoyuan.opspilot.domain.incident.Incident;
import io.github.ismoyuan.opspilot.domain.incident.IncidentStatus;
import io.github.ismoyuan.opspilot.domain.investigation.Investigation;
import io.github.ismoyuan.opspilot.domain.investigation.StepAdmissionRejection;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * AI Step 的原子准入（08 TASK-039、07 §42、01 §11）：一个短事务内按 Incident → Investigation 加锁（与 Stop、Capability 准入同一锁序），
 * 检查 INVESTIGATING、期望 run、Stop、本轮 deadline、本轮 Capability 额度与连续 AI 失败阈值，全部通过才登记 RUNNING Step 并提交。
 * COMMIT 即线性化边界：Stop 先提交则此处拒绝，此处先提交则这一步允许在上限内完成。事务内没有 LLM、Provider 或 sleep；
 * 不调用 resumeInvestigation、不刷新 run（应用恢复同样经此准入）。
 */
@Service
public class StepAdmissionService {

    private final IncidentRepository incidents;
    private final InvestigationRepository investigations;
    private final AgentStepRepository steps;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public StepAdmissionService(
            IncidentRepository incidents,
            InvestigationRepository investigations,
            AgentStepRepository steps,
            PlatformTransactionManager transactionManager,
            Clock clock) {
        this.incidents = incidents;
        this.investigations = investigations;
        this.steps = steps;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    public StepAdmission admit(long incidentId, int expectedRunNo) {
        return transaction.execute(status -> {
            Optional<Incident> incident = incidents.findByIdForUpdate(incidentId);
            if (incident.isEmpty() || incident.get().status() != IncidentStatus.INVESTIGATING) {
                return new StepAdmission.Rejected(StepAdmissionRejection.NOT_INVESTIGATING);
            }
            Investigation investigation = investigations
                    .findByIncidentIdForUpdate(incidentId)
                    .orElseThrow(() ->
                            new IllegalStateException("INVESTIGATING incident without investigation: " + incidentId));
            // 取得两把锁之后才读时间：锁等待可能越过本轮截止，截止判断、Step 起点与等待上限都用持锁时刻（B10-R1）
            Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
            Optional<StepAdmissionRejection> rejection = investigation.checkStepAdmission(expectedRunNo, now);
            if (rejection.isPresent()) {
                return new StepAdmission.Rejected(rejection.get());
            }
            AgentStep step = steps.insertRunning(incidentId, investigation.id(), investigation.currentRunNo(), now);
            return new StepAdmission.Admitted(step, investigation.stepWaitLimit(now));
        });
    }
}
