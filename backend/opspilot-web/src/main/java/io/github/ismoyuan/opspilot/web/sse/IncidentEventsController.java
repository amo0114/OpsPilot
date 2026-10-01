package io.github.ismoyuan.opspilot.web.sse;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Min;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Incident 事件流（05 §62～§67、08 TASK-087～089）。SSE 只推送变化，不代替 GET：页面先 GET 详情取得 Snapshot 与 lastTimelineEventId，
 * 再以 {@code afterId} 连接；浏览器自动重连时带 Last-Event-ID，优先于 URL 中首次连接的游标。都没有时从该 Incident 的第一条事件补发。
 * 不使用 05 §8 的响应包络；Incident 不存在时在建立事件流之前返回 404。
 */
@RestController
@RequestMapping("/api/v1/incidents/{incidentKey}")
@Tag(name = "Timeline")
public class IncidentEventsController {

    private final IncidentSseHub hub;

    public IncidentEventsController(IncidentSseHub hub) {
        this.hub = hub;
    }

    @GetMapping(path = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(
            @PathVariable String incidentKey,
            @RequestHeader(name = "Last-Event-ID", required = false) @Min(0) Long lastEventId,
            @RequestParam(required = false) @Min(0) Long afterId) {
        long cursor = lastEventId != null ? lastEventId : afterId != null ? afterId : 0;
        return hub.subscribe(incidentKey, cursor);
    }
}
