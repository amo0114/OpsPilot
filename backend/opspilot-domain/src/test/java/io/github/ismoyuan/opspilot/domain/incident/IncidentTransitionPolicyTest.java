package io.github.ismoyuan.opspilot.domain.incident;

import static io.github.ismoyuan.opspilot.domain.incident.IncidentStatus.AWAITING_APPROVAL;
import static io.github.ismoyuan.opspilot.domain.incident.IncidentStatus.CANCELLED;
import static io.github.ismoyuan.opspilot.domain.incident.IncidentStatus.CREATED;
import static io.github.ismoyuan.opspilot.domain.incident.IncidentStatus.DIAGNOSED;
import static io.github.ismoyuan.opspilot.domain.incident.IncidentStatus.EXECUTING;
import static io.github.ismoyuan.opspilot.domain.incident.IncidentStatus.INVESTIGATING;
import static io.github.ismoyuan.opspilot.domain.incident.IncidentStatus.RESOLVED;
import static io.github.ismoyuan.opspilot.domain.incident.IncidentStatus.VERIFYING;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ismoyuan.opspilot.domain.error.DomainException;
import io.github.ismoyuan.opspilot.domain.error.ErrorCode;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 01 §4 主状态机：穷举 8 状态 × 全部触发，只有冻结的边允许，其余全部拒绝。 */
class IncidentTransitionPolicyTest {

    /** 01 §4 的完整边集，与实现独立书写。 */
    private static final Map<String, IncidentStatus> ALLOWED = new HashMap<>();

    static {
        allow(CREATED, IncidentTrigger.START_INVESTIGATION, INVESTIGATING);
        allow(INVESTIGATING, IncidentTrigger.COMPLETE_INVESTIGATION, DIAGNOSED);
        allow(DIAGNOSED, IncidentTrigger.CONTINUE_INVESTIGATION, INVESTIGATING);
        allow(DIAGNOSED, IncidentTrigger.REQUEST_APPROVAL, AWAITING_APPROVAL);
        allow(DIAGNOSED, IncidentTrigger.VERIFY_RECOVERY, VERIFYING);
        allow(AWAITING_APPROVAL, IncidentTrigger.REJECT_APPROVAL, DIAGNOSED);
        allow(AWAITING_APPROVAL, IncidentTrigger.CANCEL_APPROVAL, DIAGNOSED);
        allow(AWAITING_APPROVAL, IncidentTrigger.START_EXECUTION, EXECUTING);
        allow(EXECUTING, IncidentTrigger.EXECUTION_FAILED, DIAGNOSED);
        allow(EXECUTING, IncidentTrigger.EXECUTION_SUCCEEDED, VERIFYING);
        allow(VERIFYING, IncidentTrigger.VERIFICATION_PASSED, RESOLVED);
        allow(VERIFYING, IncidentTrigger.VERIFICATION_FAILED, INVESTIGATING);
        allow(VERIFYING, IncidentTrigger.VERIFICATION_INCONCLUSIVE, DIAGNOSED);
        for (IncidentStatus from : List.of(CREATED, INVESTIGATING, DIAGNOSED, AWAITING_APPROVAL)) {
            allow(from, IncidentTrigger.CANCEL_INCIDENT, CANCELLED);
        }
    }

    private static void allow(IncidentStatus from, IncidentTrigger trigger, IncidentStatus to) {
        ALLOWED.put(from + "/" + trigger, to);
    }

    @Test
    void exactlyTheFrozenEightStatesExist() {
        assertThat(Arrays.stream(IncidentStatus.values()).map(Enum::name))
                .containsExactly(
                        "CREATED",
                        "INVESTIGATING",
                        "DIAGNOSED",
                        "AWAITING_APPROVAL",
                        "EXECUTING",
                        "VERIFYING",
                        "RESOLVED",
                        "CANCELLED");
    }

    @Test
    void everyStatusTriggerPairMatchesTheFrozenStateMachine() {
        int allowed = 0;
        for (IncidentStatus from : IncidentStatus.values()) {
            for (IncidentTrigger trigger : IncidentTrigger.values()) {
                IncidentStatus expected = ALLOWED.get(from + "/" + trigger);
                assertThat(IncidentTransitionPolicy.target(from, trigger))
                        .as("%s --%s-->", from, trigger)
                        .isEqualTo(java.util.Optional.ofNullable(expected));
                if (expected != null) {
                    allowed++;
                }
            }
        }
        assertThat(allowed).isEqualTo(17);
    }

    @Test
    void terminalStatesHaveNoOutgoingTransitions() {
        for (IncidentStatus terminal : List.of(RESOLVED, CANCELLED)) {
            assertThat(terminal.isTerminal()).isTrue();
            for (IncidentTrigger trigger : IncidentTrigger.values()) {
                assertThat(IncidentTransitionPolicy.target(terminal, trigger)).isEmpty();
            }
        }
    }

    @Test
    void onlyVerificationPassedReachesResolved() {
        for (IncidentStatus from : IncidentStatus.values()) {
            for (IncidentTrigger trigger : IncidentTrigger.values()) {
                if (IncidentTransitionPolicy.target(from, trigger)
                        .filter(RESOLVED::equals)
                        .isPresent()) {
                    assertThat(from).isEqualTo(VERIFYING);
                    assertThat(trigger).isEqualTo(IncidentTrigger.VERIFICATION_PASSED);
                }
            }
        }
    }

    @Test
    void executingAndVerifyingCannotBeCancelled() {
        assertThat(IncidentTransitionPolicy.target(EXECUTING, IncidentTrigger.CANCEL_INCIDENT))
                .isEmpty();
        assertThat(IncidentTransitionPolicy.target(VERIFYING, IncidentTrigger.CANCEL_INCIDENT))
                .isEmpty();
    }

    @Test
    void incidentProducesTransitionCarryingItsVersion() {
        Incident incident = incident(DIAGNOSED, 7);

        assertThat(incident.transitionFor(IncidentTrigger.CONTINUE_INVESTIGATION))
                .isEqualTo(new IncidentTransition(
                        42, IncidentTrigger.CONTINUE_INVESTIGATION, DIAGNOSED, 7, INVESTIGATING));
    }

    @Test
    void illegalTransitionIsStateConflictWithExpectedStatuses() {
        Incident incident = incident(EXECUTING, 3);

        assertThatThrownBy(() -> incident.transitionFor(IncidentTrigger.CANCEL_INCIDENT))
                .isInstanceOfSatisfying(DomainException.class, ex -> {
                    assertThat(ex.errorCode()).isEqualTo(ErrorCode.INCIDENT_STATE_CONFLICT);
                    assertThat(ex.details())
                            .containsEntry("incidentKey", "INC-20260927-0001")
                            .containsEntry("currentStatus", "EXECUTING")
                            .containsEntry(
                                    "expectedStatuses",
                                    List.of("CREATED", "INVESTIGATING", "DIAGNOSED", "AWAITING_APPROVAL"));
                });
    }

    @Test
    void staleExpectedVersionIsVersionConflict() {
        Incident incident = incident(CREATED, 2);

        assertThatThrownBy(() -> incident.transitionFor(IncidentTrigger.START_INVESTIGATION, 1))
                .isInstanceOfSatisfying(DomainException.class, ex -> {
                    assertThat(ex.errorCode()).isEqualTo(ErrorCode.INCIDENT_VERSION_CONFLICT);
                    assertThat(ex.details())
                            .containsEntry("currentStatus", "CREATED")
                            .containsEntry("version", 2L);
                });
        assertThat(incident.transitionFor(IncidentTrigger.START_INVESTIGATION, 2)
                        .targetStatus())
                .isEqualTo(INVESTIGATING);
    }

    @Test
    void transitionRecordCannotBeBuiltForIllegalEdges() {
        assertThatThrownBy(() -> new IncidentTransition(1, IncidentTrigger.VERIFICATION_PASSED, DIAGNOSED, 0, RESOLVED))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new IncidentTransition(1, IncidentTrigger.START_INVESTIGATION, CREATED, 0, DIAGNOSED))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() ->
                        new IncidentTransition(1, IncidentTrigger.START_INVESTIGATION, CREATED, -1, INVESTIGATING))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static Incident incident(IncidentStatus status, long version) {
        Instant now = Instant.parse("2026-09-27T01:00:00Z");
        return new Incident(
                42,
                new IncidentKey("INC-20260927-0001"),
                1,
                "短链接跳转明显变慢",
                null,
                "短链接跳转速度明显下降",
                status,
                IncidentSource.MANUAL,
                "demo-user",
                now,
                now,
                status == RESOLVED ? now : null,
                version);
    }
}
