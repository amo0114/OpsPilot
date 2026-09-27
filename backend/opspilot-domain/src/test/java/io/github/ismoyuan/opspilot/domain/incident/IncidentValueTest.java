package io.github.ismoyuan.opspilot.domain.incident;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ismoyuan.opspilot.domain.error.DomainException;
import io.github.ismoyuan.opspilot.domain.error.ErrorCode;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** IncidentKey 格式、NewIncident 创建校验（05 §21、V002 列长度）与 Incident 的 resolvedAt 不变量。 */
class IncidentValueTest {

    private static final Instant NOW = Instant.parse("2026-09-27T01:00:00Z");

    @Test
    void incidentKeyFormatsDayAndSequence() {
        IncidentKey key = IncidentKey.of(LocalDate.of(2026, 9, 27), 1);

        assertThat(key.value()).isEqualTo("INC-20260927-0001");
        assertThat(key.sequence()).isEqualTo(1);
        assertThat(IncidentKey.of(LocalDate.of(2026, 9, 27), 12345).value()).isEqualTo("INC-20260927-12345");
        assertThat(IncidentKey.dayPrefix(LocalDate.of(2026, 9, 27))).isEqualTo("INC-20260927-");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "INC-001",
                "inc-20260927-0001",
                "INC-20260927-001",
                "INC-20261327-0001",
                "INC-20260927-0000",
                "INC-20260927-0001\n",
                " INC-20260927-0001",
                "INC-20260927-1234567890"
            })
    void rejectsMalformedIncidentKeys(String value) {
        assertThatThrownBy(() -> new IncidentKey(value)).isInstanceOf(RuntimeException.class);
    }

    @Test
    void newIncidentStripsTextAndDefaultsStartedAt() {
        NewIncident incident = newIncident("  短链接跳转明显变慢 ", "  ", " 影响 ", null);

        assertThat(incident.title()).isEqualTo("短链接跳转明显变慢");
        assertThat(incident.description()).isNull();
        assertThat(incident.impactSummary()).isEqualTo("影响");
        assertThat(incident.startedAt()).isEqualTo(NOW);
    }

    @Test
    void newIncidentAcceptsLimitsCountedInCharacters() {
        String title = "慢".repeat(NewIncident.TITLE_MAX);
        String emoji = "😀".repeat(NewIncident.IMPACT_SUMMARY_MAX);

        NewIncident incident =
                newIncident(title, "d".repeat(NewIncident.DESCRIPTION_MAX), emoji, NOW.minusSeconds(600));

        assertThat(incident.title()).hasSize(200);
        assertThat(incident.startedAt()).isEqualTo(NOW.minusSeconds(600));
    }

    @Test
    void newIncidentRejectsBlankTooLongAndFutureStart() {
        assertInvalid(() -> newIncident(" ", null, "影响", null), "title", "BLANK");
        assertInvalid(() -> newIncident(null, null, "影响", null), "title", "BLANK");
        assertInvalid(() -> newIncident("t".repeat(201), null, "影响", null), "title", "TOO_LONG");
        assertInvalid(() -> newIncident("标题", null, "", null), "impactSummary", "BLANK");
        assertInvalid(() -> newIncident("标题", null, "i".repeat(1001), null), "impactSummary", "TOO_LONG");
        assertInvalid(() -> newIncident("标题", "d".repeat(2001), "影响", null), "description", "TOO_LONG");
        assertInvalid(
                () -> newIncident(
                        "标题",
                        null,
                        "影响",
                        NOW.plus(NewIncident.STARTED_AT_TOLERANCE).plusMillis(1)),
                "startedAt",
                "AFTER_DETECTED_AT");
        assertThat(newIncident("标题", null, "影响", NOW.plus(NewIncident.STARTED_AT_TOLERANCE))
                        .startedAt())
                .isEqualTo(NOW.plus(NewIncident.STARTED_AT_TOLERANCE));
        assertInvalid(
                () -> new NewIncident(1, "标题", null, "影响", IncidentSource.MANUAL, " ", null, NOW),
                "createdBy",
                "BLANK");
    }

    @Test
    void incidentRequiresResolvedAtExactlyWhenResolved() {
        assertThatThrownBy(() -> incident(IncidentStatus.RESOLVED, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> incident(IncidentStatus.VERIFYING, NOW)).isInstanceOf(IllegalArgumentException.class);
        assertThat(incident(IncidentStatus.RESOLVED, NOW).resolvedAt()).isEqualTo(NOW);
    }

    private static NewIncident newIncident(String title, String description, String impact, Instant startedAt) {
        return new NewIncident(1, title, description, impact, IncidentSource.MANUAL, "demo-user", startedAt, NOW);
    }

    private static Incident incident(IncidentStatus status, Instant resolvedAt) {
        return new Incident(
                1,
                new IncidentKey("INC-20260927-0001"),
                1,
                "t",
                null,
                "i",
                status,
                IncidentSource.MANUAL,
                "demo-user",
                NOW,
                NOW,
                resolvedAt,
                0);
    }

    private static void assertInvalid(Runnable action, String field, String reason) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(DomainException.class, ex -> {
            assertThat(ex.errorCode()).isEqualTo(ErrorCode.REQUEST_VALIDATION_FAILED);
            assertThat(ex.details()).containsEntry("field", field).containsEntry("reason", reason);
        });
    }
}
