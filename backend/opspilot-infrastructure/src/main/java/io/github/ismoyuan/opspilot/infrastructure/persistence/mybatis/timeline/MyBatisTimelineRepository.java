package io.github.ismoyuan.opspilot.infrastructure.persistence.mybatis.timeline;

import io.github.ismoyuan.opspilot.application.timeline.TimelineRepository;
import io.github.ismoyuan.opspilot.domain.timeline.NewTimelineEvent;
import io.github.ismoyuan.opspilot.domain.timeline.TimelinePayload;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

@Repository
class MyBatisTimelineRepository implements TimelineRepository {

    private static final String SCHEMA_NAME = "schemaName";
    private static final String SCHEMA_VERSION = "schemaVersion";

    /** 只把强类型载荷写成 JSON，不用于解码或指纹（07 §58）。 */
    private final JsonMapper json = JsonMapper.builder().build();

    private final TimelineEventMapper mapper;

    MyBatisTimelineRepository(TimelineEventMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public long append(NewTimelineEvent event) {
        TimelineEventInsert insert = new TimelineEventInsert(
                event.incidentId(),
                event.eventType().name(),
                utc(event),
                event.actorType().name(),
                event.actorId(),
                event.summary(),
                payloadJson(event.payload()),
                event.correlationId(),
                utc(event));
        mapper.insert(insert);
        return insert.getId();
    }

    /** 载荷对象内自带 schemaName/schemaVersion（04 §98），最后写入，以接口声明的值为准。 */
    private String payloadJson(TimelinePayload payload) {
        ObjectNode root = json.valueToTree(payload);
        root.put(SCHEMA_NAME, payload.schemaName());
        root.put(SCHEMA_VERSION, payload.schemaVersion());
        return json.writeValueAsString(root);
    }

    private static LocalDateTime utc(NewTimelineEvent event) {
        return LocalDateTime.ofInstant(event.occurredAt().truncatedTo(ChronoUnit.MILLIS), ZoneOffset.UTC);
    }
}
