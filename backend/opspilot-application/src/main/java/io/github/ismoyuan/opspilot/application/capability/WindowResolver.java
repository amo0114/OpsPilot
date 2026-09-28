package io.github.ismoyuan.opspilot.application.capability;

import io.github.ismoyuan.opspilot.application.ai.protocol.v1.WindowKey;
import io.github.ismoyuan.opspilot.domain.error.ErrorCode;
import java.time.Duration;
import java.time.Instant;

/**
 * windowKey 的唯一解析规则（06 §42、B14-R1），在准入事务内于扣预算之前执行，结果随 {@link AdmittedInvocation} 交给 Provider：
 *
 * <ul>
 *   <li>LAST_n_MIN：current = [now − n, now)；
 *   <li>INCIDENT_CONTEXT：current = [max(Incident.startedAt, now − 60 分钟), now)——故障已久时取当前允许范围，不让无限历史进入模型；
 *       故障开始时刻不早于 now 时无可查询范围，拒绝 CAPABILITY_ARGUMENT_INVALID；
 *   <li>比较前一窗口：previous = [current.start − |current|, current.start)，等长紧邻；previous.start 到 current.end 的总范围超过
 *       60 分钟时拒绝 METRIC_COMPARISON_WINDOW_EXCEEDS_LIMIT，不静默裁短任何一侧。
 * </ul>
 */
public final class WindowResolver {

    /** 单次查询与比较的总范围上限（06 §42）。 */
    public static final Duration MAX_RANGE = Duration.ofMinutes(60);

    private WindowResolver() {}

    /** 解析结果：成功带出窗口，失败带出拒绝码与原因。 */
    public sealed interface Resolution permits Resolved, Invalid {}

    public record Resolved(ResolvedWindow window) implements Resolution {}

    public record Invalid(ErrorCode code, String reason) implements Resolution {}

    public static Resolution resolve(WindowKey key, boolean comparePrevious, Instant incidentStartedAt, Instant now) {
        Instant start = switch (key) {
            case LAST_15_MIN -> now.minus(Duration.ofMinutes(15));
            case LAST_30_MIN -> now.minus(Duration.ofMinutes(30));
            case LAST_60_MIN -> now.minus(Duration.ofMinutes(60));
            case INCIDENT_CONTEXT -> {
                Instant earliest = now.minus(MAX_RANGE);
                yield incidentStartedAt.isAfter(earliest) ? incidentStartedAt : earliest;
            }
        };
        if (!start.isBefore(now)) {
            return new Invalid(ErrorCode.CAPABILITY_ARGUMENT_INVALID, "INCIDENT_CONTEXT_NOT_STARTED");
        }
        QueryWindow current = new QueryWindow(start, now);
        if (!comparePrevious) {
            return new Resolved(new ResolvedWindow(current, null));
        }
        Duration length = current.length();
        if (length.multipliedBy(2).compareTo(MAX_RANGE) > 0) {
            return new Invalid(ErrorCode.METRIC_COMPARISON_WINDOW_EXCEEDS_LIMIT, "TOTAL_RANGE_OVER_60_MIN");
        }
        return new Resolved(new ResolvedWindow(current, new QueryWindow(start.minus(length), start)));
    }
}
