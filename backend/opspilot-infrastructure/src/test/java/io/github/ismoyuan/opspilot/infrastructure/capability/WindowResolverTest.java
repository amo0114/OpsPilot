package io.github.ismoyuan.opspilot.infrastructure.capability;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ismoyuan.opspilot.application.ai.protocol.v1.WindowKey;
import io.github.ismoyuan.opspilot.application.capability.QueryWindow;
import io.github.ismoyuan.opspilot.application.capability.ResolvedWindow;
import io.github.ismoyuan.opspilot.application.capability.WindowResolver;
import io.github.ismoyuan.opspilot.domain.error.ErrorCode;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** 06 §42、B14-R1：windowKey 的唯一解析规则——等长紧邻的比较窗口、60 分钟总范围、INCIDENT_CONTEXT 先解析再判断。 */
class WindowResolverTest {

    static final Instant NOW = Instant.parse("2026-09-28T08:00:00.000Z");

    @Test
    void fixedWindowsAndTheirAdjacentPreviousWindow() {
        assertThat(resolve(WindowKey.LAST_15_MIN, true, NOW))
                .isEqualTo(new WindowResolver.Resolved(new ResolvedWindow(window(15, 0), window(30, 15))));
        assertThat(resolve(WindowKey.LAST_30_MIN, true, NOW))
                .isEqualTo(new WindowResolver.Resolved(new ResolvedWindow(window(30, 0), window(60, 30))));
        assertThat(resolve(WindowKey.LAST_60_MIN, false, NOW))
                .isEqualTo(new WindowResolver.Resolved(new ResolvedWindow(window(60, 0), null)));
        assertThat(resolve(WindowKey.LAST_60_MIN, true, NOW))
                .isEqualTo(new WindowResolver.Invalid(
                        ErrorCode.METRIC_COMPARISON_WINDOW_EXCEEDS_LIMIT, "TOTAL_RANGE_OVER_60_MIN"));
    }

    /** INCIDENT_CONTEXT 从故障开始到现在，最多 60 分钟；比较时按解析出的长度判断总范围，不裁短。 */
    @Test
    void incidentContextIsResolvedFromTheIncidentStartBeforeJudgingComparison() {
        assertThat(resolve(WindowKey.INCIDENT_CONTEXT, true, NOW.minus(Duration.ofMinutes(20))))
                .isEqualTo(new WindowResolver.Resolved(new ResolvedWindow(window(20, 0), window(40, 20))));
        assertThat(resolve(WindowKey.INCIDENT_CONTEXT, true, NOW.minus(Duration.ofMinutes(30))))
                .isInstanceOf(WindowResolver.Resolved.class);
        assertThat(resolve(WindowKey.INCIDENT_CONTEXT, true, NOW.minus(Duration.ofMinutes(45))))
                .isEqualTo(new WindowResolver.Invalid(
                        ErrorCode.METRIC_COMPARISON_WINDOW_EXCEEDS_LIMIT, "TOTAL_RANGE_OVER_60_MIN"));
        assertThat(resolve(WindowKey.INCIDENT_CONTEXT, false, NOW.minus(Duration.ofMinutes(45))))
                .isEqualTo(new WindowResolver.Resolved(new ResolvedWindow(window(45, 0), null)));
        // 故障已久：取当前允许的 60 分钟，不让无限历史进入
        assertThat(resolve(WindowKey.INCIDENT_CONTEXT, false, NOW.minus(Duration.ofHours(3))))
                .isEqualTo(new WindowResolver.Resolved(new ResolvedWindow(window(60, 0), null)));
    }

    @Test
    void anIncidentThatHasNotStartedHasNothingToQuery() {
        for (Instant started : new Instant[] {NOW, NOW.plusSeconds(30)}) {
            assertThat(resolve(WindowKey.INCIDENT_CONTEXT, false, started))
                    .isEqualTo(new WindowResolver.Invalid(
                            ErrorCode.CAPABILITY_ARGUMENT_INVALID, "INCIDENT_CONTEXT_NOT_STARTED"));
        }
    }

    private static WindowResolver.Resolution resolve(WindowKey key, boolean compare, Instant incidentStartedAt) {
        return WindowResolver.resolve(key, compare, incidentStartedAt, NOW);
    }

    /** [now − fromMinutesAgo, now − toMinutesAgo) */
    private static QueryWindow window(int fromMinutesAgo, int toMinutesAgo) {
        return new QueryWindow(
                NOW.minus(Duration.ofMinutes(fromMinutesAgo)), NOW.minus(Duration.ofMinutes(toMinutesAgo)));
    }
}
