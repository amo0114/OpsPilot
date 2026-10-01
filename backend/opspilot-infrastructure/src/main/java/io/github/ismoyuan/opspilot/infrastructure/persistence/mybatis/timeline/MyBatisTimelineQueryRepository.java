package io.github.ismoyuan.opspilot.infrastructure.persistence.mybatis.timeline;

import io.github.ismoyuan.opspilot.application.timeline.query.TimelineEventView;
import io.github.ismoyuan.opspilot.application.timeline.query.TimelineQueryRepository;
import io.github.ismoyuan.opspilot.domain.timeline.TimelineActorType;
import io.github.ismoyuan.opspilot.domain.timeline.TimelineEventType;
import java.time.ZoneOffset;
import java.util.List;
import java.util.OptionalLong;
import org.springframework.stereotype.Repository;

@Repository
class MyBatisTimelineQueryRepository implements TimelineQueryRepository {

    private final TimelineQueryMapper mapper;

    MyBatisTimelineQueryRepository(TimelineQueryMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public OptionalLong findIncidentId(String incidentKey) {
        Long id = mapper.selectIncidentId(incidentKey);
        return id == null ? OptionalLong.empty() : OptionalLong.of(id);
    }

    @Override
    public List<TimelineEventView> findAfter(long incidentId, long afterId, int limit) {
        return mapper.selectAfter(incidentId, afterId, limit).stream()
                .map(row -> new TimelineEventView(
                        row.id(),
                        TimelineEventType.valueOf(row.eventType()),
                        row.occurredAt().toInstant(ZoneOffset.UTC),
                        TimelineActorType.valueOf(row.actorType()),
                        row.summary()))
                .toList();
    }
}
