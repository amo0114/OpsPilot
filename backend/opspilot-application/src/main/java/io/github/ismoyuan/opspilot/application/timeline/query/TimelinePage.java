package io.github.ismoyuan.opspilot.application.timeline.query;

import java.util.List;

/**
 * 一段按 id 升序的追加事件（05 §60）。
 *
 * @param nextAfterId 下一次请求的游标：本段最后一条事件的 id；本段为空时原样返回请求的 afterId，游标不后退
 */
public record TimelinePage(List<TimelineEventView> events, long nextAfterId) {

    public TimelinePage {
        events = List.copyOf(events);
    }
}
