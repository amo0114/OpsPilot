package io.github.ismoyuan.opspilot.infrastructure.investigation;

import io.github.ismoyuan.opspilot.application.ai.protocol.v1.QueueInspectArgumentsV1;
import io.github.ismoyuan.opspilot.application.ai.protocol.v1.ServiceInspectArgumentsV1;
import io.github.ismoyuan.opspilot.application.recovery.RecoveryCriterionV1;
import io.github.ismoyuan.opspilot.application.recovery.RecoveryPolicyActivationService;
import io.github.ismoyuan.opspilot.application.recovery.RecoveryPolicyCriteriaV1;
import io.github.ismoyuan.opspilot.application.recovery.RecoveryPredicateV1;
import io.github.ismoyuan.opspilot.application.recovery.RecoverySamplingV1;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 可请求处理建议的已诊断 Incident（08 TASK-063～064）：在 {@link InvestigationFixture} 之上，Incident（DIAGNOSED，版本 7）的
 * Diagnosis v1 为 PRIMARY_CAUSE_IDENTIFIED，冻结两条 Evidence：统计消费者服务观测 SUPPORTS、Stream 观测 CONTEXT。统计消费者与
 * project-api 都绑定了 service.restart（Docker）；Stream 没有写能力。
 */
record RemediationFixture(
        JdbcTemplate jdbc,
        InvestigationFixture investigation,
        long consumer,
        long projectApi,
        long consumerEvidence,
        long streamEvidence) {

    static RemediationFixture seed(JdbcTemplate jdbc) {
        for (String table : List.of("capability_binding", "resource_binding", "data_source_connection")) {
            jdbc.update("DELETE FROM " + table);
        }
        InvestigationFixture fixture = InvestigationFixture.reset(jdbc);
        long consumer = resource(jdbc, "statistics-consumer", "统计消费者", "CONSUMER");
        long projectApi = resource(jdbc, "project-api", "短链服务", "SERVICE");
        jdbc.update("INSERT INTO data_source_connection (connection_key, name, provider_type, endpoint,"
                + " config_schema_name, config_schema_version, config_payload, status, created_at, updated_at) VALUES"
                + " ('docker-local', 'D', 'DOCKER', 'unix:///var/run/docker.sock', 'docker.connection.config', 1,"
                + " '{}', 'ACTIVE', UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))");
        restartable(jdbc, consumer, "shortlink-statistics-consumer");
        restartable(jdbc, projectApi, "shortlink-project");
        jdbc.update(
                "INSERT INTO hypothesis (investigation_id, title, status, created_at, updated_at) VALUES (?,"
                        + " '统计消费者已停止', 'SUPPORTED', UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))",
                fixture.investigationId());
        long hypothesis = jdbc.queryForObject(
                "SELECT MAX(id) FROM hypothesis WHERE investigation_id = ?", Long.class, fixture.investigationId());
        long streamObservation = fixture.observation(fixture.incidentId(), fixture.investigationId(), "inv-stream");
        long consumerObservation = serviceObservation(jdbc, fixture, consumer);
        long consumerEvidence = evidence(jdbc, fixture, consumerObservation, hypothesis, "SUPPORTS");
        long streamEvidence = evidence(jdbc, fixture, streamObservation, hypothesis, "CONTEXT");
        RemediationFixture seeded =
                new RemediationFixture(jdbc, fixture, consumer, projectApi, consumerEvidence, streamEvidence);
        seeded.diagnosis(1, "PRIMARY_CAUSE_IDENTIFIED", hypothesis, consumerEvidence, streamEvidence);
        jdbc.update("UPDATE incident SET status = 'DIAGNOSED', lock_version = 7 WHERE id = ?", fixture.incidentId());
        return seeded;
    }

    String incidentKey() {
        return jdbc.queryForObject(
                "SELECT incident_key FROM incident WHERE id = ?", String.class, investigation.incidentId());
    }

    void diagnosis(int version, String conclusion, Long primary, long... evidenceIds) {
        jdbc.update(
                "INSERT INTO diagnosis (investigation_id, run_no, version_no, conclusion_type, primary_hypothesis_id,"
                        + " summary, impact_summary, termination_reason, created_at) VALUES (?, ?, ?, ?, ?,"
                        + " '统计消费者已停止运行，统计消息不再被消费。', '访问统计延迟', 'AGENT_COMPLETED', UTC_TIMESTAMP(3))",
                investigation.investigationId(),
                version,
                version,
                conclusion,
                primary);
        long id = jdbc.queryForObject(
                "SELECT id FROM diagnosis WHERE investigation_id = ? AND version_no = ?",
                Long.class,
                investigation.investigationId(),
                version);
        for (long evidenceId : evidenceIds) {
            jdbc.update(
                    "INSERT INTO diagnosis_evidence_ref (diagnosis_id, evidence_id, created_at) VALUES (?, ?,"
                            + " UTC_TIMESTAMP(3))",
                    id,
                    evidenceId);
        }
    }

    /**
     * 统计消费者上的 ACTIVE 策略：先检查 Stream（经 Redis 绑定，配置选定消费组 stats-consumer-group）的 lag，再检查消费者运行状态；
     * 启用所需的 queue.inspect / service.inspect 绑定。
     *
     * @return 策略 id
     */
    long activateRecoveryPolicy(RecoveryPolicyActivationService recoveryPolicies, String policyKey) {
        long stream = investigation.streamId();
        jdbc.update("INSERT INTO data_source_connection (connection_key, name, provider_type, endpoint,"
                + " config_schema_name, config_schema_version, config_payload, status, created_at, updated_at) VALUES"
                + " ('redis-local', 'R', 'REDIS', 'redis://redis:6379', 'redis.connection.config', 1, '{}', 'ACTIVE',"
                + " UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))");
        jdbc.update(
                "INSERT INTO resource_binding (managed_resource_id, data_source_connection_id, selector_schema_name,"
                        + " selector_schema_version, selector_payload, created_at, updated_at) SELECT ?, id,"
                        + " 'redis.resource.binding', 1, '{\"streamKey\": \"shortlink:stats\", \"consumerGroup\":"
                        + " \"stats-consumer-group\"}', UTC_TIMESTAMP(3), UTC_TIMESTAMP(3) FROM data_source_connection"
                        + " WHERE connection_key = 'redis-local'",
                stream);
        for (Object[] binding :
                List.of(new Object[] {stream, "queue.inspect"}, new Object[] {consumer, "service.inspect"})) {
            jdbc.update(
                    "INSERT INTO capability_binding (managed_resource_id, capability_key, enabled, created_at,"
                            + " updated_at) VALUES (?, ?, TRUE, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))",
                    binding);
        }
        return recoveryPolicies
                .activate(new RecoveryPolicyActivationService.ActivateCommand(
                        consumer,
                        policyKey,
                        "统计消费者恢复标准",
                        RecoveryPolicyCriteriaV1.of(
                                60,
                                60,
                                List.of(
                                        new RecoveryCriterionV1.QueueInspect(
                                                "stream-lag-drained",
                                                "积压达标",
                                                "statistics-stream",
                                                new QueueInspectArgumentsV1(),
                                                new RecoverySamplingV1(1, 0, null),
                                                new RecoveryPredicateV1.NumericCompare(
                                                        "lag", RecoveryPredicateV1.ComparisonOperator.LTE, 20.0),
                                                true),
                                        new RecoveryCriterionV1.ServiceInspect(
                                                "consumer-running",
                                                "消费者持续运行",
                                                "statistics-consumer",
                                                new ServiceInspectArgumentsV1(),
                                                new RecoverySamplingV1(2, 5, 10),
                                                new RecoveryPredicateV1.FieldEquals("runtimeState", "RUNNING"),
                                                true)))))
                .policyId();
    }

    private static long resource(JdbcTemplate jdbc, String key, String name, String type) {
        jdbc.update(
                "INSERT INTO managed_resource (managed_system_id, resource_key, name, resource_type, status,"
                        + " created_at, updated_at) SELECT id, ?, ?, ?, 'ACTIVE', UTC_TIMESTAMP(3), UTC_TIMESTAMP(3)"
                        + " FROM managed_system",
                key,
                name,
                type);
        return jdbc.queryForObject("SELECT id FROM managed_resource WHERE resource_key = ?", Long.class, key);
    }

    private static void restartable(JdbcTemplate jdbc, long resourceId, String containerName) {
        jdbc.update(
                "INSERT INTO resource_binding (managed_resource_id, data_source_connection_id, selector_schema_name,"
                        + " selector_schema_version, selector_payload, created_at, updated_at) SELECT ?, id,"
                        + " 'docker.resource.binding', 1, ?, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3)"
                        + " FROM data_source_connection",
                resourceId,
                "{\"containerName\": \"" + containerName + "\"}");
        jdbc.update(
                "INSERT INTO capability_binding (managed_resource_id, capability_key, enabled, created_at, updated_at)"
                        + " VALUES (?, 'service.restart', TRUE, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))",
                resourceId);
    }

    private static long serviceObservation(JdbcTemplate jdbc, InvestigationFixture fixture, long resourceId) {
        jdbc.update(
                "INSERT INTO capability_invocation (incident_id, investigation_id, run_no, capability_key,"
                        + " managed_resource_id, status, request_schema_name, request_schema_version, request_payload,"
                        + " response_schema_name, response_schema_version, response_payload, started_at, finished_at,"
                        + " duration_ms, correlation_id, created_at, updated_at) VALUES (?, ?, 1, 'service.inspect', ?,"
                        + " 'SUCCEEDED', 'service.inspect.request', 1, '{}', 'service.inspect.result', 1, '{}',"
                        + " UTC_TIMESTAMP(3) - INTERVAL 5 MINUTE, UTC_TIMESTAMP(3) - INTERVAL 5 MINUTE, 20, 'inv-svc',"
                        + " UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))",
                fixture.incidentId(),
                fixture.investigationId(),
                resourceId);
        long invocation = jdbc.queryForObject(
                "SELECT id FROM capability_invocation WHERE correlation_id = 'inv-svc'", Long.class);
        jdbc.update(
                "INSERT INTO observation (incident_id, investigation_id, capability_invocation_id, managed_resource_id,"
                        + " observation_kind, schema_name, schema_version, payload, summary, observed_at, created_at)"
                        + " VALUES (?, ?, ?, ?, 'SERVICE_STATUS', 'service-status.observation', 1, '{}',"
                        + " '服务运行状态 STOPPED，退出码 137', UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))",
                fixture.incidentId(),
                fixture.investigationId(),
                invocation,
                resourceId);
        return jdbc.queryForObject(
                "SELECT id FROM observation WHERE capability_invocation_id = ?", Long.class, invocation);
    }

    private static long evidence(
            JdbcTemplate jdbc, InvestigationFixture fixture, long observation, long hypothesis, String relation) {
        jdbc.update(
                "INSERT INTO evidence (investigation_id, observation_id, hypothesis_id, relation, reason, created_at)"
                        + " VALUES (?, ?, ?, ?, '依据', UTC_TIMESTAMP(3))",
                fixture.investigationId(),
                observation,
                hypothesis,
                relation);
        return jdbc.queryForObject(
                "SELECT id FROM evidence WHERE observation_id = ? AND hypothesis_id = ?",
                Long.class,
                observation,
                hypothesis);
    }
}
