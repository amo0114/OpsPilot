package io.github.ismoyuan.opspilot.domain.investigation;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/** 01 §9：新一轮只重置本轮字段，保留首次开始时间、累计计数与限制快照。 */
class InvestigationRunTest {

    @Test
    void nextRunResetsOnlyCurrentRunFields() {
        Instant started = Instant.parse("2026-09-27T01:00:00Z");
        Instant now = Instant.parse("2026-09-27T03:00:00Z");
        InvestigationLimits limits = new InvestigationLimits(12, 480, 60, 3);
        Investigation exhausted = new Investigation(
                7,
                42,
                started,
                started.plusSeconds(400),
                1,
                started,
                12,
                20,
                2,
                started.plusSeconds(100),
                "demo-user",
                limits,
                5);

        Investigation next = exhausted.nextRun(now);

        assertThat(next).isEqualTo(new Investigation(7, 42, started, now, 2, now, 0, 20, 0, null, null, limits, 5));
        assertThat(next.stopRequested()).isFalse();
        assertThat(next.currentRunDeadline()).isEqualTo(now.plusSeconds(480));
    }
}
