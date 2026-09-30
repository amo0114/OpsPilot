package io.github.ismoyuan.opspilot.infrastructure.investigation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ismoyuan.opspilot.application.ClockConfiguration;
import io.github.ismoyuan.opspilot.application.ai.protocol.v1.RemediationDraftRequest;
import io.github.ismoyuan.opspilot.application.ai.protocol.v1.RemediationDraftResponse;
import io.github.ismoyuan.opspilot.application.ai.protocol.v1.ServiceRestartParametersV1;
import io.github.ismoyuan.opspilot.application.capability.CapabilityAccess;
import io.github.ismoyuan.opspilot.application.capability.CapabilityProviderResolver;
import io.github.ismoyuan.opspilot.application.error.ApplicationException;
import io.github.ismoyuan.opspilot.application.remediation.RemediationActions;
import io.github.ismoyuan.opspilot.application.remediation.RemediationDraftContext;
import io.github.ismoyuan.opspilot.application.remediation.RemediationDraftContextBuilder;
import io.github.ismoyuan.opspilot.application.remediation.RemediationProposalValidator;
import io.github.ismoyuan.opspilot.application.remediation.ValidatedRemediationProposal;
import io.github.ismoyuan.opspilot.domain.capability.CapabilitySchema;
import io.github.ismoyuan.opspilot.domain.capability.RiskLevel;
import io.github.ismoyuan.opspilot.domain.diagnosis.DiagnosisConclusionType;
import io.github.ismoyuan.opspilot.domain.error.ErrorCode;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

/**
 * 08 TASK-063～064：真实 MySQL 上从当前 Diagnosis、冻结 Evidence 与相关写动作构造 RemediationDraftRequest，并校验 AI 建议、由 Java
 * 产生 riskLevel / requiresApproval。系统中另有一个同样绑定了 service.restart 的无关服务（project-api），用来确认 allowedActions
 * 只含与本次诊断相关的资源（用户决定，2026-09-30）。
 */
@SpringBootTest
@Testcontainers
@Import({
    RemediationDraftContextBuilder.class,
    RemediationActions.class,
    RemediationProposalValidator.class,
    CapabilityAccess.class,
    CapabilityProviderResolver.class,
    ClockConfiguration.class
})
class RemediationDraftIntegrationTest {

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4.11");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired
    RemediationDraftContextBuilder builder;

    @Autowired
    RemediationActions actions;

    @Autowired
    RemediationProposalValidator validator;

    @Autowired
    JdbcTemplate jdbc;

    RemediationFixture seeded;
    InvestigationFixture fixture;
    long consumer;
    long projectApi;
    long consumerEvidence;
    long streamEvidence;

    @BeforeEach
    void seed() {
        seeded = RemediationFixture.seed(jdbc);
        fixture = seeded.investigation();
        consumer = seeded.consumer();
        projectApi = seeded.projectApi();
        consumerEvidence = seeded.consumerEvidence();
        streamEvidence = seeded.streamEvidence();
    }

    /** 请求只含当前 Diagnosis 与它冻结的 Evidence；allowedActions 只含与诊断相关的消费者，不含同样可重启的 project-api。 */
    @Test
    void theRequestCarriesTheCurrentDiagnosisItsFrozenEvidenceAndOnlyRelatedActions() {
        RemediationDraftContext context = builder.build(key(), 7);

        RemediationDraftRequest request = context.request();
        assertThat(request.incident().incidentKey()).isEqualTo(key());
        assertThat(request.diagnosis().version()).isOne();
        assertThat(request.diagnosis().conclusionType()).isEqualTo(DiagnosisConclusionType.PRIMARY_CAUSE_IDENTIFIED);
        assertThat(request.diagnosis().evidence())
                .extracting(
                        RemediationDraftRequest.EvidenceSummary::id, RemediationDraftRequest.EvidenceSummary::summary)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(consumerEvidence, "支持：服务运行状态 STOPPED，退出码 137"),
                        org.assertj.core.groups.Tuple.tuple(streamEvidence, "背景：消息积压 2180"));
        assertThat(request.allowedActions())
                .containsExactly(new RemediationDraftRequest.AllowedAction(
                        "service.restart", consumer, "statistics-consumer", "统计消费者"));
        assertThat(request.toString()).doesNotContain("shortlink-statistics-consumer", "docker.sock");
        assertThat(context.incidentVersion()).isEqualTo(7);
        assertThat(context.diagnosisId())
                .isEqualTo(jdbc.queryForObject("SELECT id FROM diagnosis WHERE version_no = 1", Long.class));
    }

    /** 受影响资源同样是相关资源：把 project-api 登记为受影响后，它的重启动作也会出现。 */
    @Test
    void affectedResourcesAreAlsoRelated() {
        jdbc.update(
                "INSERT INTO incident_affected_resource (incident_id, managed_resource_id, created_at) VALUES"
                        + " (?, ?, UTC_TIMESTAMP(3))",
                fixture.incidentId(),
                projectApi);

        assertThat(builder.build(key(), 7).request().allowedActions())
                .extracting(RemediationDraftRequest.AllowedAction::resourceKey)
                .containsExactly("statistics-consumer", "project-api");
    }

    /** 05 §29：UNDETERMINED、没有相关可执行动作都返回 DIAGNOSIS_NOT_ACTIONABLE；状态、版本与编号按通用规则拒绝。 */
    @Test
    void requestsThatCannotProduceAProposalAreRejectedBeforeAnyAiCall() {
        assertRejected(key(), 6, ErrorCode.INCIDENT_VERSION_CONFLICT, null);
        assertRejected("INC-20990101-0001", 7, ErrorCode.INCIDENT_NOT_FOUND, null);
        jdbc.update("UPDATE incident SET status = 'INVESTIGATING' WHERE id = ?", fixture.incidentId());
        assertRejected(key(), 7, ErrorCode.INCIDENT_STATE_CONFLICT, null);
        jdbc.update("UPDATE incident SET status = 'DIAGNOSED' WHERE id = ?", fixture.incidentId());

        jdbc.update("UPDATE capability_binding SET enabled = FALSE WHERE managed_resource_id = ?", consumer);
        assertRejected(key(), 7, ErrorCode.DIAGNOSIS_NOT_ACTIONABLE, "NO_APPLICABLE_ACTION");
        jdbc.update("UPDATE capability_binding SET enabled = TRUE WHERE managed_resource_id = ?", consumer);
        jdbc.update("UPDATE managed_resource SET status = 'DISABLED' WHERE id = ?", consumer);
        assertRejected(key(), 7, ErrorCode.DIAGNOSIS_NOT_ACTIONABLE, "NO_APPLICABLE_ACTION");
        jdbc.update("UPDATE managed_resource SET status = 'ACTIVE' WHERE id = ?", consumer);
        jdbc.update("UPDATE data_source_connection SET status = 'DISABLED'");
        assertRejected(key(), 7, ErrorCode.DIAGNOSIS_NOT_ACTIONABLE, "NO_APPLICABLE_ACTION");
        jdbc.update("UPDATE data_source_connection SET status = 'ACTIVE'");

        // 最新版本才算数：v2 为 UNDETERMINED 时 v1 的 PRIMARY 不能再用来生成方案
        diagnosis(2, "UNDETERMINED", null);
        assertRejected(key(), 7, ErrorCode.DIAGNOSIS_NOT_ACTIONABLE, "UNDETERMINED");
    }

    /** 06 §13、§105：AI 只给动作、目标、{} 参数与说明；Java 给出 MEDIUM 与需要审批，参数写为请求 Schema 的规范 JSON。 */
    @Test
    void aProposalWithinTheAllowedActionsGetsJavaPolicy() {
        RemediationDraftContext context = builder.build(key(), 7);

        ValidatedRemediationProposal proposal =
                validator.validate(context.allowedActions(), context.diagnosisId(), response(context, consumer));

        assertThat(proposal.capabilityKey()).isEqualTo("service.restart");
        assertThat(proposal.target().id()).isEqualTo(consumer);
        assertThat(proposal.riskLevel()).isEqualTo(RiskLevel.MEDIUM);
        assertThat(proposal.requiresApproval()).isTrue();
        assertThat(proposal.parameterSchema()).isEqualTo(new CapabilitySchema("service.restart.request", 1));
        assertThat(proposal.parameterPayload()).isEqualTo("{}");
        assertThat(proposal.diagnosisId()).isEqualTo(context.diagnosisId());
        assertThat(proposal.title()).isEqualTo("恢复统计消费");
    }

    /**
     * 05 §88：所选目标不在 allowedActions 中（系统里另一可重启服务、没有写能力的 Stream）即 AI_INTENT_NOT_ALLOWED；创建事务内以当时
     * 重新计算的 allowedActions 校验（TASK-065），等待期间绑定被停用的动作同样被拒绝。
     */
    @Test
    void proposalsOutsideTheCurrentAllowedActionsAreRejected() {
        RemediationDraftContext context = builder.build(key(), 7);

        for (long target : List.of(projectApi, fixture.streamId())) {
            assertThatThrownBy(() -> validator.validate(
                            context.allowedActions(), context.diagnosisId(), response(context, target)))
                    .isInstanceOfSatisfying(ApplicationException.class, ex -> {
                        assertThat(ex.errorCode()).isEqualTo(ErrorCode.AI_INTENT_NOT_ALLOWED);
                        assertThat(ex.details()).containsEntry("reason", "ACTION_NOT_ALLOWED");
                    });
        }

        jdbc.update("UPDATE capability_binding SET enabled = FALSE WHERE managed_resource_id = ?", consumer);
        var recomputed = actions.allowedActions(
                jdbc.queryForObject("SELECT managed_system_id FROM incident WHERE id = ?", Long.class, incident()),
                List.of(consumer, fixture.streamId()));
        assertThat(recomputed).isEmpty();
        assertThatThrownBy(() -> validator.validate(recomputed, context.diagnosisId(), response(context, consumer)))
                .isInstanceOfSatisfying(
                        ApplicationException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.AI_INTENT_NOT_ALLOWED));
    }

    // ---------------------------------------------------------------- helpers

    private static RemediationDraftResponse response(RemediationDraftContext context, long target) {
        return new RemediationDraftResponse(
                1,
                context.request().correlationId(),
                RemediationDraftResponse.RemediationIntentType.PROPOSE_REMEDIATION,
                new RemediationDraftResponse.Proposal(
                        "恢复统计消费",
                        "重新启动已停止的统计消费者。",
                        new RemediationDraftResponse.Action(
                                "service.restart",
                                target,
                                new ServiceRestartParametersV1(),
                                "重新启动统计消费者",
                                "统计消费短暂中断后恢复。")));
    }

    private void assertRejected(String incidentKey, long version, ErrorCode code, String reason) {
        assertThatThrownBy(() -> builder.build(incidentKey, version))
                .isInstanceOfSatisfying(ApplicationException.class, ex -> {
                    assertThat(ex.errorCode()).isEqualTo(code);
                    if (reason != null) {
                        assertThat(ex.details()).containsEntry("reason", reason);
                    }
                });
    }

    private long incident() {
        return fixture.incidentId();
    }

    private String key() {
        return seeded.incidentKey();
    }

    private void diagnosis(int version, String conclusion, Long primary, long... evidenceIds) {
        seeded.diagnosis(version, conclusion, primary, evidenceIds);
    }
}
