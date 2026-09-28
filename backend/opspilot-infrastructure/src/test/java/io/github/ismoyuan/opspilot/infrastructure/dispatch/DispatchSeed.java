package io.github.ismoyuan.opspilot.infrastructure.dispatch;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

/** 派发测试数据：直接写入“已提交但从未被唤醒”的 Incident/Investigation，模拟提交后、派发前进程退出。 */
final class DispatchSeed {

    private final JdbcTemplate jdbc;
    private int sequence;

    DispatchSeed(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        for (String table : List.of(
                "incident_timeline_event",
                "investigation",
                "incident_affected_resource",
                "incident",
                "managed_system")) {
            jdbc.update("DELETE FROM " + table);
        }
        jdbc.update("INSERT INTO managed_system (system_key, name, environment, status, created_at, updated_at)"
                + " VALUES ('shortlink-platform', 'S', 'DEMO', 'ACTIVE', UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))");
    }

    long incident(String status) {
        String key = String.format("INC-20260928-%04d", ++sequence);
        jdbc.update(
                "INSERT INTO incident (incident_key, managed_system_id, title, impact_summary, status, created_source,"
                        + " created_by, started_at, detected_at, created_at, updated_at, lock_version) SELECT ?, id,"
                        + " 'T', 'I', ?, 'MANUAL', 'demo-user', UTC_TIMESTAMP(3), UTC_TIMESTAMP(3),"
                        + " UTC_TIMESTAMP(3), UTC_TIMESTAMP(3), 4 FROM managed_system",
                key,
                status);
        return jdbc.queryForObject("SELECT id FROM incident WHERE incident_key = ?", Long.class, key);
    }

    /** 本轮已开始 10 分钟、用过 5 次调用；stopped 时写入停止意图。 */
    void investigation(long incidentId, int runNo, boolean stopped) {
        jdbc.update(
                "INSERT INTO investigation (incident_id, started_at, last_activity_at, current_run_no,"
                        + " current_run_started_at, current_run_capability_count, capability_call_count,"
                        + " stop_requested_at, stop_requested_by, max_capability_calls, max_duration_seconds,"
                        + " agent_step_timeout_seconds, max_consecutive_ai_failures, created_at, updated_at,"
                        + " lock_version) VALUES (?, UTC_TIMESTAMP(3) - INTERVAL 1 HOUR, UTC_TIMESTAMP(3), ?,"
                        + " UTC_TIMESTAMP(3) - INTERVAL 10 MINUTE, 5, 9, ?, ?, 12, 480, 60, 3, UTC_TIMESTAMP(3),"
                        + " UTC_TIMESTAMP(3), 6)",
                incidentId,
                runNo,
                stopped ? java.time.LocalDateTime.of(2026, 9, 28, 1, 0) : null,
                stopped ? "demo-user" : null);
    }

    /** 恢复前后比对：run、起点、计数、Stop、版本都不得变化。 */
    List<java.util.Map<String, Object>> snapshot() {
        return jdbc.queryForList("SELECT i.id, i.status, CAST(i.lock_version AS SIGNED) AS incident_version,"
                + " v.current_run_no, v.current_run_started_at, v.current_run_capability_count,"
                + " CAST(v.capability_call_count AS SIGNED) AS total_calls, v.stop_requested_at,"
                + " CAST(v.lock_version AS SIGNED) AS investigation_version"
                + " FROM incident i LEFT JOIN investigation v ON v.incident_id = i.id ORDER BY i.id");
    }
}
