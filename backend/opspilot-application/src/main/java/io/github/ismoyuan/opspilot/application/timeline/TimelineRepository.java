package io.github.ismoyuan.opspilot.application.timeline;

import io.github.ismoyuan.opspilot.domain.timeline.NewTimelineEvent;

/**
 * 时间线只追加端口，没有更新与删除（01 §34）。调用方须在同一短事务内已持有该 Incident 的行锁，
 * 使同一 Incident 的事件 ID 顺序与提交顺序一致（04 §57）。
 */
public interface TimelineRepository {

    /** @return 新事件 ID */
    long append(NewTimelineEvent event);
}
