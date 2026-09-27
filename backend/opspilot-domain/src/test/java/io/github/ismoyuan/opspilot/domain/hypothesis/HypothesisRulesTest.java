package io.github.ismoyuan.opspilot.domain.hypothesis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ismoyuan.opspilot.domain.error.DomainException;
import io.github.ismoyuan.opspilot.domain.error.ErrorCode;
import java.time.Instant;
import java.util.Arrays;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** 01 §13～§14 的四状态与状态变化规则，以及创建字段上限（V003）。 */
class HypothesisRulesTest {

    private static final Instant T0 = Instant.parse("2026-09-27T08:00:00Z");

    /** 与实现独立书写的允许边：任何已评估状态之间可变化，不能停在原状态，也不能回到 PENDING。 */
    private static final Set<String> ALLOWED = Set.of(
            "PENDING->SUPPORTED",
            "PENDING->INSUFFICIENT_EVIDENCE",
            "PENDING->REFUTED",
            "SUPPORTED->INSUFFICIENT_EVIDENCE",
            "SUPPORTED->REFUTED",
            "INSUFFICIENT_EVIDENCE->SUPPORTED",
            "INSUFFICIENT_EVIDENCE->REFUTED",
            "REFUTED->SUPPORTED",
            "REFUTED->INSUFFICIENT_EVIDENCE");

    @Test
    void exactlyTheFrozenFourStatesExist() {
        assertThat(Arrays.stream(HypothesisStatus.values()).map(Enum::name))
                .containsExactly("PENDING", "SUPPORTED", "INSUFFICIENT_EVIDENCE", "REFUTED");
    }

    @Test
    void statusChangesFollowTheMatrixExhaustively() {
        for (HypothesisStatus from : HypothesisStatus.values()) {
            for (HypothesisStatus to : HypothesisStatus.values()) {
                Hypothesis current = hypothesis(from);
                if (ALLOWED.contains(from + "->" + to)) {
                    Hypothesis changed = current.changeStatus(to, T0.plusSeconds(5));
                    assertThat(changed.status()).isEqualTo(to);
                    assertThat(changed.updatedAt()).isEqualTo(T0.plusSeconds(5));
                    assertThat(changed)
                            .usingRecursiveComparison()
                            .ignoringFields("status", "updatedAt")
                            .isEqualTo(current);
                } else {
                    assertThatThrownBy(() -> current.changeStatus(to, T0))
                            .as(from + "->" + to)
                            .isInstanceOfSatisfying(DomainException.class, ex -> {
                                assertThat(ex.errorCode()).isEqualTo(ErrorCode.REQUEST_VALIDATION_FAILED);
                                assertThat(ex.details())
                                        .containsEntry("reason", "ILLEGAL_TRANSITION")
                                        .containsEntry("currentStatus", from.name())
                                        .containsEntry("targetStatus", to.name());
                            });
                }
            }
        }
    }

    @Test
    void newHypothesisValidatesTextWithinColumnLimits() {
        NewHypothesis stripped = new NewHypothesis(1, "  Consumer 已停止  ", "   ");
        assertThat(stripped.title()).isEqualTo("Consumer 已停止");
        assertThat(stripped.description()).isNull();
        assertThat(new NewHypothesis(1, "界".repeat(NewHypothesis.TITLE_MAX), "界".repeat(NewHypothesis.DESCRIPTION_MAX))
                        .title())
                .hasSize(NewHypothesis.TITLE_MAX);

        assertInvalid(() -> new NewHypothesis(1, " ", null), "title", "BLANK");
        assertInvalid(() -> new NewHypothesis(1, "x".repeat(NewHypothesis.TITLE_MAX + 1), null), "title", "TOO_LONG");
        assertInvalid(
                () -> new NewHypothesis(1, "t", "x".repeat(NewHypothesis.DESCRIPTION_MAX + 1)),
                "description",
                "TOO_LONG");
    }

    private static void assertInvalid(Runnable action, String field, String reason) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(DomainException.class, ex -> {
            assertThat(ex.errorCode()).isEqualTo(ErrorCode.REQUEST_VALIDATION_FAILED);
            assertThat(ex.details()).containsEntry("field", field).containsEntry("reason", reason);
        });
    }

    private static Hypothesis hypothesis(HypothesisStatus status) {
        return new Hypothesis(11, 7, "Consumer 已停止", null, status, T0, T0, 3);
    }
}
