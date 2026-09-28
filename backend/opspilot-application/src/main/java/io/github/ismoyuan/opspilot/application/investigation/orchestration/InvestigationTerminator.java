package io.github.ismoyuan.opspilot.application.investigation.orchestration;

import io.github.ismoyuan.opspilot.application.diagnosis.CreateDiagnosisCommand;
import io.github.ismoyuan.opspilot.application.diagnosis.DiagnosisApplicationService;
import io.github.ismoyuan.opspilot.application.incident.IncidentRepository;
import io.github.ismoyuan.opspilot.application.investigation.InvestigationRepository;
import io.github.ismoyuan.opspilot.domain.diagnosis.DiagnosisConclusionType;
import io.github.ismoyuan.opspilot.domain.diagnosis.DiagnosisDraft;
import io.github.ismoyuan.opspilot.domain.diagnosis.TerminationReason;
import io.github.ismoyuan.opspilot.domain.incident.Incident;
import io.github.ismoyuan.opspilot.domain.incident.IncidentStatus;
import io.github.ismoyuan.opspilot.domain.investigation.Investigation;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 本轮的确定性收束（08 TASK-042、01 §11～§12、07 §52～§53）。一个短事务内按 Incident → Investigation 加锁，持锁后重新判定
 * 退出条件（Stop → 截止 → 额度 → 连续 AI 失败）；成立时以真实原因新增 UNDETERMINED 版本并 INVESTIGATING → DIAGNOSED。
 *
 * <p>本轮合法草稿只有合法 COMPLETE_INVESTIGATION，它在结果事务内已直接形成 Diagnosis，因此到这里时本轮没有未采用的合法草稿；
 * 不再为“凑答案”启动 AI Step，也不复制以前 Diagnosis 的结论或引用。UNDETERMINED 冻结本轮建立的 Evidence，
 * 使下一轮仍能看到这些事实。额度耗尽只结束本轮，Continue 仍可开启新 run。
 */
@Service
public class InvestigationTerminator {

    private static final Logger log = LoggerFactory.getLogger(InvestigationTerminator.class);

    private final IncidentRepository incidents;
    private final InvestigationRepository investigations;
    private final ReferenceScopeQuery scope;
    private final DiagnosisApplicationService diagnoses;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public InvestigationTerminator(
            IncidentRepository incidents,
            InvestigationRepository investigations,
            ReferenceScopeQuery scope,
            DiagnosisApplicationService diagnoses,
            PlatformTransactionManager transactionManager,
            Clock clock) {
        this.incidents = incidents;
        this.investigations = investigations;
        this.scope = scope;
        this.diagnoses = diagnoses;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /**
     * @return 已收束时的原因；Incident 已不在调查、run 已切换或本轮尚无退出条件时为空，且不写任何数据
     */
    public Optional<TerminationReason> terminate(long incidentId, int runNo) {
        Optional<TerminationReason> terminated = transaction.execute(status -> {
            Optional<Incident> incident = incidents.findByIdForUpdate(incidentId);
            if (incident.isEmpty() || incident.get().status() != IncidentStatus.INVESTIGATING) {
                return Optional.<TerminationReason>empty();
            }
            Investigation investigation = investigations
                    .findByIncidentIdForUpdate(incidentId)
                    .orElseThrow(() ->
                            new IllegalStateException("INVESTIGATING incident without investigation: " + incidentId));
            // 与准入相同：取得两把锁之后才读时间
            Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
            Optional<TerminationReason> reason = investigation.terminationReason(runNo, now);
            if (reason.isEmpty()) {
                return reason;
            }
            DiagnosisDraft draft = new DiagnosisDraft(
                    DiagnosisConclusionType.UNDETERMINED,
                    null,
                    summary(reason.get()),
                    incident.get().impactSummary(),
                    scope.currentRunEvidenceIds(investigation.id(), investigation.currentRunStartedAt()));
            diagnoses.createDiagnosis(new CreateDiagnosisCommand(incidentId, runNo, draft, reason.get()));
            return reason;
        });
        terminated.ifPresent(reason ->
                log.info("Investigation run terminated: incidentId={} runNo={} reason={}", incidentId, runNo, reason));
        return terminated;
    }

    /** 固定文案：只说明为何结束，不推断原因。 */
    static String summary(TerminationReason reason) {
        return switch (reason) {
            case USER_STOPPED -> "用户已停止本轮调查；已取得的事实不足以确定原因。";
            case CAPABILITY_BUDGET_EXHAUSTED -> "本轮调查的能力调用额度已用完；已取得的事实不足以确定原因，可继续调查开启新一轮。";
            case INVESTIGATION_TIMEOUT -> "本轮调查已到时间上限；已取得的事实不足以确定原因，可继续调查开启新一轮。";
            case AI_RUNTIME_UNAVAILABLE -> "AI 服务连续不可用或输出无效，本轮调查无法继续；已取得的事实不足以确定原因。";
            case AGENT_COMPLETED -> throw new IllegalArgumentException("AGENT_COMPLETED is not a deterministic exit");
        };
    }
}
