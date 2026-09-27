package io.github.ismoyuan.opspilot.infrastructure.investigation;

import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 调查事实集成测试的数据：一个系统、两个处于 INVESTIGATING 的 Incident 及各自的 Investigation（run 1），
 * 另一 Investigation 预置一条 PENDING Hypothesis 用于跨调查引用。每次重建，数据由真实提交写入。
 */
record InvestigationFixture(
        JdbcTemplate jdbc,
        long incidentId,
        long investigationId,
        long otherIncidentId,
        long otherInvestigationId,
        long otherInvestigationHypothesis) {

    static InvestigationFixture reset(JdbcTemplate jdbc) {
        for (String table : List.of(
                "diagnosis_evidence_ref",
                "diagnosis",
                "evidence",
                "hypothesis",
                "observation",
                "capability_invocation",
                "incident_timeline_event",
                "investigation",
                "incident_affected_resource",
                "incident",
                "managed_resource",
                "managed_system")) {
            jdbc.update("DELETE FROM " + table);
        }
        jdbc.update("INSERT INTO managed_system (system_key, name, environment, status, created_at, updated_at)"
                + " VALUES ('shortlink-platform', 'S', 'DEMO', 'ACTIVE', UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))");
        jdbc.update("INSERT INTO managed_resource (managed_system_id, resource_key, name, resource_type, status,"
                + " created_at, updated_at) SELECT id, 'statistics-stream', 'Stream', 'MESSAGE_QUEUE', 'ACTIVE',"
                + " UTC_TIMESTAMP(3), UTC_TIMESTAMP(3) FROM managed_system");
        jdbc.update("INSERT INTO incident (incident_key, managed_system_id, title, impact_summary, status,"
                + " created_source, created_by, started_at, detected_at, created_at, updated_at) SELECT"
                + " CONCAT('INC-20260927-000', n), id, 'T', 'I', 'INVESTIGATING', 'MANUAL', 'demo-user',"
                + " UTC_TIMESTAMP(3), UTC_TIMESTAMP(3), UTC_TIMESTAMP(3), UTC_TIMESTAMP(3) FROM managed_system,"
                + " (SELECT 1 AS n UNION ALL SELECT 2) k");
        jdbc.update("INSERT INTO investigation (incident_id, started_at, last_activity_at, current_run_no,"
                + " current_run_started_at, max_capability_calls, max_duration_seconds, agent_step_timeout_seconds,"
                + " max_consecutive_ai_failures, created_at, updated_at) SELECT id, UTC_TIMESTAMP(3),"
                + " UTC_TIMESTAMP(3), 1, UTC_TIMESTAMP(3), 12, 480, 60, 3, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3)"
                + " FROM incident");
        long incidentId = id(jdbc, "SELECT id FROM incident WHERE incident_key = 'INC-20260927-0001'");
        long otherIncidentId = id(jdbc, "SELECT id FROM incident WHERE incident_key = 'INC-20260927-0002'");
        long otherInvestigationId = id(jdbc, "SELECT id FROM investigation WHERE incident_id = " + otherIncidentId);
        jdbc.update(
                "INSERT INTO hypothesis (investigation_id, title, status, created_at, updated_at) VALUES"
                        + " (?, '其他故障的原因', 'PENDING', UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))",
                otherInvestigationId);
        return new InvestigationFixture(
                jdbc,
                incidentId,
                id(jdbc, "SELECT id FROM investigation WHERE incident_id = " + incidentId),
                otherIncidentId,
                otherInvestigationId,
                id(jdbc, "SELECT id FROM hypothesis WHERE investigation_id = " + otherInvestigationId));
    }

    long streamId() {
        return id(jdbc, "SELECT id FROM managed_resource WHERE resource_key = 'statistics-stream'");
    }

    /**
     * 经一条 SUCCEEDED Invocation 写入真实 Observation。{@code investigationId} 为空时是恢复观测（恢复上下文 9001）。
     *
     * @return Observation id
     */
    long observation(long incident, Long investigationId, String name) {
        boolean recovery = investigationId == null;
        jdbc.update(
                "INSERT INTO capability_invocation (incident_id, investigation_id, recovery_verification_id, run_no,"
                        + " criterion_key, sample_index, capability_key, managed_resource_id, status,"
                        + " request_schema_name, request_schema_version, request_payload, response_schema_name,"
                        + " response_schema_version, response_payload, started_at, finished_at, duration_ms,"
                        + " correlation_id, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, 'queue.inspect', ?,"
                        + " 'SUCCEEDED', 'queue.inspect.request', 1, '{}', 'queue.inspect.result', 1, '{}',"
                        + " UTC_TIMESTAMP(3), UTC_TIMESTAMP(3), 42, ?, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))",
                incident,
                investigationId,
                recovery ? 9001L : null,
                recovery ? null : 1,
                recovery ? "stream-lag-drained" : null,
                recovery ? 1 : null,
                streamId(),
                name);
        long invocationId = id(jdbc, "SELECT id FROM capability_invocation WHERE correlation_id = '" + name + "'");
        jdbc.update(
                "INSERT INTO observation (incident_id, investigation_id, recovery_verification_id,"
                        + " capability_invocation_id, managed_resource_id, observation_kind, schema_name,"
                        + " schema_version, payload, summary, observed_at, created_at) VALUES (?, ?, ?, ?, ?,"
                        + " 'QUEUE_STATUS', 'queue.inspect.result', 1, '{\"lag\": 2180}', '消息积压 2180',"
                        + " UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))",
                incident,
                investigationId,
                recovery ? 9001L : null,
                invocationId,
                streamId());
        return id(jdbc, "SELECT id FROM observation WHERE capability_invocation_id = " + invocationId);
    }

    Map<String, Object> hypothesisRow(long id) {
        return jdbc.queryForMap(
                "SELECT CAST(investigation_id AS SIGNED) AS investigation_id, title, description, status, updated_at,"
                        + " CAST(lock_version AS SIGNED) AS lock_version FROM hypothesis"
                        + " WHERE id = ?",
                id);
    }

    int eventCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM incident_timeline_event", Integer.class);
    }

    List<Map<String, Object>> events(String eventType) {
        return jdbc.queryForList(
                "SELECT CAST(id AS SIGNED) AS id, CAST(incident_id AS SIGNED) AS incident_id, actor_type, actor_id, summary, payload"
                        + " FROM incident_timeline_event"
                        + " WHERE event_type = ? ORDER BY id",
                eventType);
    }

    Map<String, Object> onlyEvent(String eventType) {
        List<Map<String, Object>> events = events(eventType);
        if (events.size() != 1) {
            throw new AssertionError("expected one " + eventType + " but found " + events.size());
        }
        return events.get(0);
    }

    /** 以 MySQL JSON_EXTRACT 读取载荷字段的 JSON 文本。 */
    String payload(Map<String, Object> event, String path) {
        return jdbc.queryForObject(
                "SELECT JSON_EXTRACT(payload, ?) FROM incident_timeline_event WHERE id = ?",
                String.class,
                path,
                event.get("id"));
    }

    private static long id(JdbcTemplate jdbc, String sql) {
        return jdbc.queryForObject(sql, Long.class);
    }
}
