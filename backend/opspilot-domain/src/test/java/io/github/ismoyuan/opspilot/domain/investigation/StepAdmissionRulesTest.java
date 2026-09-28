package io.github.ismoyuan.opspilot.domain.investigation;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ismoyuan.opspilot.domain.diagnosis.TerminationReason;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** 07 §42、01 §11、05 §89：单步准入的拒绝条件与顺序、截止边界、等待上限、连续 AI 失败计数与收束原因。 */
class StepAdmissionRulesTest {

    private static final Instant RUN_START = Instant.parse("2026-09-28T08:00:00Z");
    private static final InvestigationLimits LIMITS = new InvestigationLimits(12, 480, 60, 3);

    private static Investigation investigation(int capabilityCount, int aiFailures, boolean stopped) {
        return new Investigation(
                7,
                3,
                RUN_START.minusSeconds(3_600),
                RUN_START,
                2,
                RUN_START,
                capabilityCount,
                20,
                aiFailures,
                stopped ? RUN_START.plusSeconds(5) : null,
                stopped ? "demo-user" : null,
                LIMITS,
                4);
    }

    @Test
    void openRunAdmitsAndWaitIsTheSmallerOfStepTimeoutAndRemainingTime() {
        Investigation open = investigation(11, 2, false);

        assertThat(open.checkStepAdmission(2, RUN_START.plusSeconds(100))).isEmpty();
        assertThat(open.stepWaitLimit(RUN_START.plusSeconds(100))).isEqualTo(Duration.ofSeconds(60));
        assertThat(open.stepWaitLimit(RUN_START.plusSeconds(450))).isEqualTo(Duration.ofSeconds(30));
    }

    /** 第一个成立的原因即结果：旧 run 优先于 Stop，Stop 优先于到期、额度与连续失败。 */
    @Test
    void rejectionsFollowTheFrozenOrder() {
        Instant late = RUN_START.plusSeconds(480);
        Investigation everything = investigation(12, 3, true);

        assertThat(everything.checkStepAdmission(1, late)).contains(StepAdmissionRejection.STALE_RUN);
        assertThat(everything.checkStepAdmission(2, late)).contains(StepAdmissionRejection.STOP_REQUESTED);
        assertThat(investigation(12, 3, false).checkStepAdmission(2, late))
                .contains(StepAdmissionRejection.DEADLINE_REACHED);
        assertThat(investigation(12, 3, false).checkStepAdmission(2, RUN_START))
                .contains(StepAdmissionRejection.CAPABILITY_BUDGET_EXHAUSTED);
        assertThat(investigation(11, 3, false).checkStepAdmission(2, RUN_START))
                .contains(StepAdmissionRejection.AI_FAILURE_THRESHOLD_REACHED);
    }

    @Test
    void deadlineInstantItselfIsNoLongerAdmissible() {
        Investigation open = investigation(0, 0, false);

        assertThat(open.checkStepAdmission(2, RUN_START.plusSeconds(480).minusMillis(1)))
                .isEmpty();
        assertThat(open.checkStepAdmission(2, RUN_START.plusSeconds(480)))
                .contains(StepAdmissionRejection.DEADLINE_REACHED);
    }

    @Test
    void rejectionsMapToTerminationReasonsOnlyForCurrentRunExits() {
        assertThat(StepAdmissionRejection.NOT_INVESTIGATING.terminationReason()).isEmpty();
        assertThat(StepAdmissionRejection.STALE_RUN.terminationReason()).isEmpty();
        assertThat(StepAdmissionRejection.STOP_REQUESTED.terminationReason())
                .isEqualTo(Optional.of(TerminationReason.USER_STOPPED));
        assertThat(StepAdmissionRejection.DEADLINE_REACHED.terminationReason())
                .contains(TerminationReason.INVESTIGATION_TIMEOUT);
        assertThat(StepAdmissionRejection.CAPABILITY_BUDGET_EXHAUSTED.terminationReason())
                .contains(TerminationReason.CAPABILITY_BUDGET_EXHAUSTED);
        assertThat(StepAdmissionRejection.AI_FAILURE_THRESHOLD_REACHED.terminationReason())
                .contains(TerminationReason.AI_RUNTIME_UNAVAILABLE);
    }

    /** 收束原因取第一个成立的退出条件；旧 run 或尚无退出条件时不收束（08 TASK-042）。 */
    @Test
    void terminationReasonIsTheFirstExitConditionOfTheCurrentRun() {
        Instant late = RUN_START.plusSeconds(480);

        assertThat(investigation(12, 3, true).terminationReason(2, late)).contains(TerminationReason.USER_STOPPED);
        assertThat(investigation(12, 3, false).terminationReason(2, RUN_START))
                .contains(TerminationReason.CAPABILITY_BUDGET_EXHAUSTED);
        assertThat(investigation(12, 3, true).terminationReason(1, late)).isEmpty();
        assertThat(investigation(11, 2, false).terminationReason(2, RUN_START)).isEmpty();
    }

    @Test
    void aiFailuresCountUpAndALegalOutputResetsThem() {
        Investigation twice = investigation(0, 0, false).withAiStepFailure().withAiStepFailure();

        assertThat(twice.consecutiveAiFailureCount()).isEqualTo(2);
        assertThat(twice.currentRunNo()).isEqualTo(2);
        assertThat(twice.withAiStepSuccess().consecutiveAiFailureCount()).isZero();
    }
}
