package io.github.ismoyuan.opspilot.application.investigation.orchestration;

import io.github.ismoyuan.opspilot.application.ai.protocol.v1.RequestCapability;
import io.github.ismoyuan.opspilot.application.incident.IncidentRepository;
import io.github.ismoyuan.opspilot.application.investigation.InvestigationRepository;
import io.github.ismoyuan.opspilot.domain.incident.Incident;
import io.github.ismoyuan.opspilot.domain.incident.IncidentStatus;
import io.github.ismoyuan.opspilot.domain.investigation.Investigation;
import io.github.ismoyuan.opspilot.domain.investigation.StepAdmissionRejection;
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
 * M6 之前的 Fake Capability（08 TASK-040）：不能绕过准入合同——在短事务内按 Incident → Investigation 加锁，检查 INVESTIGATING、原 run、
 * Stop、本轮截止与额度（与 TASK-048 相同的准入分界），通过后如实报告“未执行”：不调用 Provider、不建 Invocation、不扣预算、不产生
 * Observation（扣预算必须伴随 Invocation 记录，04 §85）。TASK-048 以真实准入替换本类，TASK-058 接入真实执行。
 */
@Service
public class GatedFakeCapabilityExecutor implements CapabilityExecutionPort {

    private static final Logger log = LoggerFactory.getLogger(GatedFakeCapabilityExecutor.class);

    private final IncidentRepository incidents;
    private final InvestigationRepository investigations;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public GatedFakeCapabilityExecutor(
            IncidentRepository incidents,
            InvestigationRepository investigations,
            PlatformTransactionManager transactionManager,
            Clock clock) {
        this.incidents = incidents;
        this.investigations = investigations;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    @Override
    public CapabilityRequestResult execute(long incidentId, int runNo, long stepId, RequestCapability request) {
        CapabilityRequestResult result = transaction.execute(status -> {
            Optional<Incident> incident = incidents.findByIdForUpdate(incidentId);
            if (incident.isEmpty() || incident.get().status() != IncidentStatus.INVESTIGATING) {
                return new CapabilityRequestResult.Rejected(StepAdmissionRejection.NOT_INVESTIGATING);
            }
            Investigation investigation = investigations
                    .findByIncidentIdForUpdate(incidentId)
                    .orElseThrow(() ->
                            new IllegalStateException("INVESTIGATING incident without investigation: " + incidentId));
            Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
            return investigation
                    .checkCapabilityAdmission(runNo, now)
                    .<CapabilityRequestResult>map(CapabilityRequestResult.Rejected::new)
                    .orElseGet(() -> new CapabilityRequestResult.NotExecuted("CAPABILITY_EXECUTION_NOT_AVAILABLE"));
        });
        log.info(
                "Capability request not executed before TASK-048/058: stepId={} capability={} result={}",
                stepId,
                request.capabilityKey().key(),
                result);
        return result;
    }
}
