package io.github.ismoyuan.opspilot.web.timeline;

import io.github.ismoyuan.opspilot.application.timeline.query.TimelineEventView;
import io.github.ismoyuan.opspilot.application.timeline.query.TimelinePage;
import io.github.ismoyuan.opspilot.application.timeline.query.TimelineQueryService;
import io.github.ismoyuan.opspilot.domain.timeline.TimelineActorType;
import io.github.ismoyuan.opspilot.domain.timeline.TimelineEventType;
import io.github.ismoyuan.opspilot.web.request.RequestIdFilter;
import io.github.ismoyuan.opspilot.web.response.ApiTimes;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Incident 时间线（05 §59～§61、08 TASK-084），只读：append-only，用 afterId 游标与 limit 分段，不用页码。Controller 只做 HTTP↔View
 * 映射。
 */
@RestController
@RequestMapping("/api/v1/incidents/{incidentKey}")
@Tag(name = "Timeline")
public class TimelineController {

    private final TimelineQueryService queries;

    public TimelineController(TimelineQueryService queries) {
        this.queries = queries;
    }

    @GetMapping("/timeline")
    public TimelineResponse timeline(
            @PathVariable String incidentKey,
            @RequestParam(defaultValue = "0") @Min(0) long afterId,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit,
            HttpServletRequest request) {
        TimelinePage page = queries.listEvents(incidentKey, afterId, limit);
        return new TimelineResponse(
                page.events().stream().map(TimelineEventResponse::of).toList(),
                page.nextAfterId(),
                RequestIdFilter.requestId(request));
    }

    /** 05 §60：事件列表与下一次游标，外加标准 requestId（05 §8）。 */
    public record TimelineResponse(List<TimelineEventResponse> data, long nextAfterId, String requestId) {}

    public record TimelineEventResponse(
            long id, TimelineEventType eventType, String occurredAt, TimelineActorType actorType, String summary) {

        static TimelineEventResponse of(TimelineEventView view) {
            return new TimelineEventResponse(
                    view.id(), view.eventType(), ApiTimes.format(view.occurredAt()), view.actorType(), view.summary());
        }
    }
}
