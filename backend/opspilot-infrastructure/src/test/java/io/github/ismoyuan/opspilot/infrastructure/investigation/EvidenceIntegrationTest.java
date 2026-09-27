package io.github.ismoyuan.opspilot.infrastructure.investigation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;

import io.github.ismoyuan.opspilot.application.evidence.EvidenceApplicationService;
import io.github.ismoyuan.opspilot.application.evidence.EvidenceLinkResult;
import io.github.ismoyuan.opspilot.application.evidence.EvidenceRepository;
import io.github.ismoyuan.opspilot.application.evidence.LinkEvidenceCommand;
import io.github.ismoyuan.opspilot.application.hypothesis.HypothesisApplicationService;
import io.github.ismoyuan.opspilot.application.hypothesis.HypothesisStatusRecorder;
import io.github.ismoyuan.opspilot.application.hypothesis.ProposeHypothesisCommand;
import io.github.ismoyuan.opspilot.application.hypothesis.UpdateHypothesisStatusCommand;
import io.github.ismoyuan.opspilot.application.timeline.TimelineRepository;
import io.github.ismoyuan.opspilot.domain.error.ErrorCode;
import io.github.ismoyuan.opspilot.domain.error.OpsPilotException;
import io.github.ismoyuan.opspilot.domain.evidence.EvidenceRelation;
import io.github.ismoyuan.opspilot.domain.hypothesis.HypothesisStatus;
import io.github.ismoyuan.opspilot.domain.timeline.TimelineEventType;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

/**
 * 真实 MySQL 上验证 Evidence 创建事务（08 TASK-024、04 §29～§31、05 §82～§83）：Observation、Hypothesis、Evidence 同属一个
 * Investigation，恢复观测不能作调查证据；重复关系返回 EVIDENCE_LINK_ALREADY_EXISTS 且原关系不变、不产生新 Observation；
 * 关系、EVIDENCE_LINKED 与附带的同 Hypothesis 状态变化同事务提交或一起回滚；持久层没有修改/删除 Evidence 的入口。
 */
@SpringBootTest
@Testcontainers
@Import({
    EvidenceApplicationService.class,
    HypothesisApplicationService.class,
    HypothesisStatusRecorder.class,
    EvidenceIntegrationTest.FixedClock.class
})
class EvidenceIntegrationTest {

    static final Instant NOW = Instant.parse("2026-09-27T08:00:00.250Z");

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4.11");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @TestConfiguration
    static class FixedClock {
        @Bean
        Clock clock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }

    @Autowired
    EvidenceApplicationService service;

    @Autowired
    HypothesisApplicationService hypotheses;

    @MockitoSpyBean
    TimelineRepository timeline;

    @Autowired
    JdbcTemplate jdbc;

    InvestigationFixture fixture;
    long observation;
    long hypothesis;

    @BeforeEach
    void seed() {
        fixture = InvestigationFixture.reset(jdbc);
        observation = fixture.observation(fixture.incidentId(), fixture.investigationId(), "inv-1");
        hypothesis = hypotheses
                .proposeHypothesis(new ProposeHypothesisCommand(fixture.incidentId(), "Statistics Consumer 已停止", null))
                .id();
    }

    /** 关系、EVIDENCE_LINKED 与附带的 PENDING → SUPPORTED 一次提交，状态变化事件指向该 Evidence。 */
    @Test
    void createsEvidenceWithAttachedStatusChangeInOneCommit() {
        EvidenceLinkResult result = service.createEvidenceLink(
                link(observation, hypothesis, EvidenceRelation.SUPPORTS, " 消费者组存在积压 ", HypothesisStatus.SUPPORTED));

        long evidenceId = result.evidence().id();
        assertThat(result.evidence().content().reason()).isEqualTo("消费者组存在积压");
        assertThat(result.evidence().createdAt()).isEqualTo(NOW);
        assertThat(result.hypothesis().status()).isEqualTo(HypothesisStatus.SUPPORTED);
        assertThat(evidenceRow(evidenceId))
                .containsEntry("investigation_id", fixture.investigationId())
                .containsEntry("observation_id", observation)
                .containsEntry("hypothesis_id", hypothesis)
                .containsEntry("relation", "SUPPORTS")
                .containsEntry("reason", "消费者组存在积压");
        assertThat(fixture.hypothesisRow(hypothesis)).containsEntry("status", "SUPPORTED");

        Map<String, Object> linked = fixture.onlyEvent("EVIDENCE_LINKED");
        assertThat(linked).containsEntry("actor_type", "AI_RUNTIME");
        assertThat(fixture.payload(linked, "$.evidenceId")).isEqualTo(String.valueOf(evidenceId));
        assertThat(fixture.payload(linked, "$.relation")).isEqualTo("\"SUPPORTS\"");
        Map<String, Object> changed = fixture.onlyEvent("HYPOTHESIS_STATUS_CHANGED");
        assertThat((Long) changed.get("id")).isGreaterThan((Long) linked.get("id"));
        assertThat(fixture.payload(changed, "$.evidenceId")).isEqualTo(String.valueOf(evidenceId));
        assertThat(fixture.payload(changed, "$.newStatus")).isEqualTo("\"SUPPORTED\"");
    }

    /** 同一 Observation 可支持或背景关联多个 Hypothesis（03 §32）；附带状态与当前相同时不产生状态变化。 */
    @Test
    void sameObservationMayRelateToOtherHypothesesAndUnchangedStatusIsNoOp() {
        long other = hypotheses
                .proposeHypothesis(new ProposeHypothesisCommand(fixture.incidentId(), "数据库过载", null))
                .id();
        service.createEvidenceLink(link(observation, hypothesis, EvidenceRelation.SUPPORTS, "积压", null));
        hypotheses.updateHypothesisStatus(
                new UpdateHypothesisStatusCommand(fixture.incidentId(), other, HypothesisStatus.SUPPORTED, null));
        int statusEvents = fixture.events("HYPOTHESIS_STATUS_CHANGED").size();

        EvidenceLinkResult second = service.createEvidenceLink(
                link(observation, other, EvidenceRelation.CONTEXT, "背景", HypothesisStatus.SUPPORTED));

        assertThat(second.hypothesis().status()).isEqualTo(HypothesisStatus.SUPPORTED);
        assertThat(evidenceCount()).isEqualTo(2);
        assertThat(fixture.events("EVIDENCE_LINKED")).hasSize(2);
        assertThat(fixture.events("HYPOTHESIS_STATUS_CHANGED")).hasSize(statusEvents);
    }

    /** 05 §83：重复关系被拒绝，原关系、Hypothesis 状态与时间线保持不变，也不产生任何新 Observation。 */
    @Test
    void duplicateLinkIsRejectedAndOriginalIsKept() {
        long original = service.createEvidenceLink(
                        link(observation, hypothesis, EvidenceRelation.CONTEXT, "最初的解释", null))
                .evidence()
                .id();
        Map<String, Object> before = evidenceRow(original);
        int events = fixture.eventCount();
        int observations = count("observation");

        assertThatThrownBy(() -> service.createEvidenceLink(
                        link(observation, hypothesis, EvidenceRelation.REFUTES, "改口", HypothesisStatus.REFUTED)))
                .isInstanceOfSatisfying(OpsPilotException.class, ex -> {
                    assertThat(ex.errorCode()).isEqualTo(ErrorCode.EVIDENCE_LINK_ALREADY_EXISTS);
                    assertThat(ex.details())
                            .containsEntry("evidenceId", original)
                            .containsEntry("observationId", observation)
                            .containsEntry("hypothesisId", hypothesis);
                });

        assertThat(evidenceCount()).isOne();
        assertThat(evidenceRow(original)).isEqualTo(before);
        assertThat(fixture.hypothesisRow(hypothesis)).containsEntry("status", "PENDING");
        assertThat(fixture.eventCount()).isEqualTo(events);
        assertThat(count("observation")).isEqualTo(observations);
    }

    /** INV-003、04 §31：跨 Investigation/Incident 的 Observation 或 Hypothesis、恢复观测、不存在的引用都不写入。 */
    @Test
    void referencesOutsideTheInvestigationAreRejectedWithoutWrites() {
        long otherObservation =
                fixture.observation(fixture.otherIncidentId(), fixture.otherInvestigationId(), "inv-other");
        long recoveryObservation = fixture.observation(fixture.incidentId(), null, "recovery-1");
        int events = fixture.eventCount();

        assertNotInInvestigation(
                link(otherObservation, hypothesis, EvidenceRelation.SUPPORTS, "r", null), "observationId");
        assertNotInInvestigation(
                link(recoveryObservation, hypothesis, EvidenceRelation.SUPPORTS, "r", HypothesisStatus.SUPPORTED),
                "observationId");
        assertNotInInvestigation(link(999_999L, hypothesis, EvidenceRelation.SUPPORTS, "r", null), "observationId");
        assertNotInInvestigation(
                link(observation, fixture.otherInvestigationHypothesis(), EvidenceRelation.SUPPORTS, "r", null),
                "hypothesisId");
        assertThatThrownBy(() ->
                        service.createEvidenceLink(link(observation, hypothesis, EvidenceRelation.SUPPORTS, " ", null)))
                .isInstanceOfSatisfying(
                        OpsPilotException.class,
                        ex -> assertThat(ex.details())
                                .containsEntry("field", "reason")
                                .containsEntry("reason", "BLANK"));

        assertThat(evidenceCount()).isZero();
        assertThat(fixture.hypothesisRow(hypothesis)).containsEntry("status", "PENDING");
        assertThat(fixture.eventCount()).isEqualTo(events);
    }

    /** 关系与附带状态变化同事务：状态变化非法或其时间线写入失败时，Evidence 与 EVIDENCE_LINKED 一起回滚。 */
    @Test
    void failureOfTheAttachedStatusChangeRollsBackTheEvidence() {
        hypotheses.updateHypothesisStatus(
                new UpdateHypothesisStatusCommand(fixture.incidentId(), hypothesis, HypothesisStatus.SUPPORTED, null));
        int events = fixture.eventCount();

        assertThatThrownBy(() -> service.createEvidenceLink(
                        link(observation, hypothesis, EvidenceRelation.REFUTES, "r", HypothesisStatus.PENDING)))
                .isInstanceOfSatisfying(
                        OpsPilotException.class,
                        ex -> assertThat(ex.details()).containsEntry("reason", "ILLEGAL_TRANSITION"));

        doThrow(new IllegalStateException("timeline down"))
                .when(timeline)
                .append(argThat(e -> e.eventType() == TimelineEventType.HYPOTHESIS_STATUS_CHANGED));
        assertThatThrownBy(() -> service.createEvidenceLink(
                        link(observation, hypothesis, EvidenceRelation.REFUTES, "r", HypothesisStatus.REFUTED)))
                .isInstanceOf(IllegalStateException.class);
        doCallRealMethod().when(timeline).append(argThat(e -> true));

        assertThat(evidenceCount()).isZero();
        assertThat(fixture.hypothesisRow(hypothesis)).containsEntry("status", "SUPPORTED");
        assertThat(fixture.eventCount()).isEqualTo(events);
    }

    /** 端口只有插入与读取；所有 Mapper XML 中都不存在 UPDATE/DELETE evidence 语句（DB-INV-004）。 */
    @Test
    void persistenceOffersNoWayToModifyEvidence() throws IOException {
        assertThat(Arrays.stream(EvidenceRepository.class.getDeclaredMethods()).map(Method::getName))
                .containsExactlyInAnyOrder("insert", "findByObservationAndHypothesis");

        Pattern modify = Pattern.compile("(?is)\\b(update\\s+evidence\\b|delete\\s+from\\s+evidence\\b)");
        Resource[] mappers =
                new PathMatchingResourcePatternResolver().getResources("classpath*:io/github/**/*Mapper.xml");
        assertThat(mappers).isNotEmpty();
        for (Resource mapper : mappers) {
            String xml = mapper.getContentAsString(StandardCharsets.UTF_8);
            assertThat(modify.matcher(xml).find()).as(mapper.getFilename()).isFalse();
        }
    }

    private LinkEvidenceCommand link(
            long observationId, long hypothesisId, EvidenceRelation relation, String reason, HypothesisStatus update) {
        return new LinkEvidenceCommand(fixture.incidentId(), observationId, hypothesisId, relation, reason, update);
    }

    private void assertNotInInvestigation(LinkEvidenceCommand command, String field) {
        assertThatThrownBy(() -> service.createEvidenceLink(command))
                .isInstanceOfSatisfying(OpsPilotException.class, ex -> {
                    assertThat(ex.errorCode()).isEqualTo(ErrorCode.REQUEST_VALIDATION_FAILED);
                    assertThat(ex.details())
                            .containsEntry("field", field)
                            .containsEntry("reason", "NOT_IN_INVESTIGATION");
                });
    }

    private Map<String, Object> evidenceRow(long id) {
        return jdbc.queryForMap(
                "SELECT CAST(investigation_id AS SIGNED) AS investigation_id,"
                        + " CAST(observation_id AS SIGNED) AS observation_id,"
                        + " CAST(hypothesis_id AS SIGNED) AS hypothesis_id, relation, reason, created_at"
                        + " FROM evidence WHERE id = ?",
                id);
    }

    private int evidenceCount() {
        return count("evidence");
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }
}
