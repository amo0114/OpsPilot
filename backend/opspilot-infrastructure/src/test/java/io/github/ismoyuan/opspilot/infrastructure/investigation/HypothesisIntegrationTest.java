package io.github.ismoyuan.opspilot.infrastructure.investigation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;

import io.github.ismoyuan.opspilot.application.hypothesis.HypothesisApplicationService;
import io.github.ismoyuan.opspilot.application.hypothesis.HypothesisStatusRecorder;
import io.github.ismoyuan.opspilot.application.hypothesis.ProposeHypothesisCommand;
import io.github.ismoyuan.opspilot.application.hypothesis.UpdateHypothesisStatusCommand;
import io.github.ismoyuan.opspilot.application.timeline.TimelineRepository;
import io.github.ismoyuan.opspilot.domain.error.ErrorCode;
import io.github.ismoyuan.opspilot.domain.error.OpsPilotException;
import io.github.ismoyuan.opspilot.domain.hypothesis.Hypothesis;
import io.github.ismoyuan.opspilot.domain.hypothesis.HypothesisStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

/**
 * 真实 MySQL 上验证 Hypothesis（08 TASK-023、01 §13～§14、04 §27）：以 PENDING 创建、合法状态变化与
 * HYPOTHESIS_* 时间线同事务提交；非法变化、跨 Investigation、非调查阶段被拒绝且不写入；时间线失败时整体回滚。
 */
@SpringBootTest
@Testcontainers
@Import({HypothesisApplicationService.class, HypothesisStatusRecorder.class, HypothesisIntegrationTest.FixedClock.class
})
class HypothesisIntegrationTest {

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
    HypothesisApplicationService service;

    @MockitoSpyBean
    TimelineRepository timeline;

    @Autowired
    JdbcTemplate jdbc;

    InvestigationFixture fixture;

    @BeforeEach
    void seed() {
        fixture = InvestigationFixture.reset(jdbc);
    }

    @Test
    void proposeCreatesPendingHypothesisWithTimelineInOneCommit() {
        Hypothesis created = service.proposeHypothesis(
                new ProposeHypothesisCommand(fixture.incidentId(), " Statistics Consumer 已停止 ", "消息持续进入 Stream。"));

        assertThat(created.investigationId()).isEqualTo(fixture.investigationId());
        assertThat(created.title()).isEqualTo("Statistics Consumer 已停止");
        assertThat(created.status()).isEqualTo(HypothesisStatus.PENDING);
        assertThat(created.version()).isZero();
        assertThat(created.createdAt()).isEqualTo(NOW).isEqualTo(created.updatedAt());

        Map<String, Object> event = fixture.onlyEvent("HYPOTHESIS_CREATED");
        assertThat(event)
                .containsEntry("incident_id", fixture.incidentId())
                .containsEntry("actor_type", "AI_RUNTIME")
                .containsEntry("summary", "提出待验证原因：Statistics Consumer 已停止");
        assertThat(fixture.payload(event, "$.schemaName")).isEqualTo("\"timeline.hypothesis-created\"");
        assertThat(fixture.payload(event, "$.hypothesisId")).isEqualTo(String.valueOf(created.id()));
        assertThat(fixture.payload(event, "$.investigationId")).isEqualTo(String.valueOf(fixture.investigationId()));
    }

    /** 01 §14：状态可以反复变化，每次变化都留下一条时间线，版本递增；历史不丢失。 */
    @Test
    void everyStatusChangeIsRecordedInTheTimeline() {
        long id = propose();

        service.updateHypothesisStatus(
                new UpdateHypothesisStatusCommand(fixture.incidentId(), id, HypothesisStatus.SUPPORTED, "延迟升高"));
        Hypothesis refuted = service.updateHypothesisStatus(
                new UpdateHypothesisStatusCommand(fixture.incidentId(), id, HypothesisStatus.REFUTED, "  "));

        assertThat(refuted.status()).isEqualTo(HypothesisStatus.REFUTED);
        assertThat(refuted.version()).isEqualTo(2);
        assertThat(fixture.hypothesisRow(id)).containsEntry("status", "REFUTED").containsEntry("lock_version", 2L);

        List<Map<String, Object>> changes = fixture.events("HYPOTHESIS_STATUS_CHANGED");
        assertThat(changes).hasSize(2);
        assertThat(fixture.payload(changes.get(0), "$.previousStatus")).isEqualTo("\"PENDING\"");
        assertThat(fixture.payload(changes.get(0), "$.newStatus")).isEqualTo("\"SUPPORTED\"");
        assertThat(fixture.payload(changes.get(0), "$.reason")).isEqualTo("\"延迟升高\"");
        assertThat(fixture.payload(changes.get(1), "$.previousStatus")).isEqualTo("\"SUPPORTED\"");
        assertThat(fixture.payload(changes.get(1), "$.newStatus")).isEqualTo("\"REFUTED\"");
        assertThat(fixture.payload(changes.get(1), "$.reason")).isEqualTo("null");
        assertThat(fixture.payload(changes.get(1), "$.evidenceId")).isEqualTo("null");
    }

    @Test
    void illegalChangesAreRejectedWithoutWrites() {
        long id = propose();
        service.updateHypothesisStatus(
                new UpdateHypothesisStatusCommand(fixture.incidentId(), id, HypothesisStatus.SUPPORTED, null));
        Map<String, Object> before = fixture.hypothesisRow(id);
        int events = fixture.eventCount();

        assertRejected(HypothesisStatus.SUPPORTED, id, ErrorCode.REQUEST_VALIDATION_FAILED);
        assertRejected(HypothesisStatus.PENDING, id, ErrorCode.REQUEST_VALIDATION_FAILED);
        // 属于其他 Investigation 或不存在：同样拒绝，不泄露归属
        assertRejected(
                HypothesisStatus.REFUTED, fixture.otherInvestigationHypothesis(), ErrorCode.REQUEST_VALIDATION_FAILED);
        assertRejected(HypothesisStatus.REFUTED, 999_999L, ErrorCode.REQUEST_VALIDATION_FAILED);
        assertThatThrownBy(() -> service.updateHypothesisStatus(new UpdateHypothesisStatusCommand(
                        fixture.incidentId(), id, HypothesisStatus.REFUTED, "x".repeat(1001))))
                .isInstanceOfSatisfying(
                        OpsPilotException.class, ex -> assertThat(ex.details()).containsEntry("field", "reason"));

        assertThat(fixture.hypothesisRow(id)).isEqualTo(before);
        assertThat(fixture.eventCount()).isEqualTo(events);
        assertThat(jdbc.queryForObject(
                        "SELECT status FROM hypothesis WHERE id = ?",
                        String.class,
                        fixture.otherInvestigationHypothesis()))
                .isEqualTo("PENDING");
    }

    @Test
    void writesRequireAnActiveInvestigation() {
        long id = propose();
        int events = fixture.eventCount();
        jdbc.update("UPDATE incident SET status = 'DIAGNOSED' WHERE id = ?", fixture.incidentId());

        assertThatThrownBy(
                        () -> service.proposeHypothesis(new ProposeHypothesisCommand(fixture.incidentId(), "t", null)))
                .isInstanceOfSatisfying(
                        OpsPilotException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.INCIDENT_STATE_CONFLICT));
        assertRejected(HypothesisStatus.SUPPORTED, id, ErrorCode.INCIDENT_STATE_CONFLICT);
        assertThatThrownBy(() -> service.proposeHypothesis(new ProposeHypothesisCommand(999_999L, "t", null)))
                .isInstanceOfSatisfying(
                        OpsPilotException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.INCIDENT_NOT_FOUND));

        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM hypothesis WHERE investigation_id = ?",
                        Integer.class,
                        fixture.investigationId()))
                .isOne();
        assertThat(fixture.hypothesisRow(id)).containsEntry("status", "PENDING");
        assertThat(fixture.eventCount()).isEqualTo(events);
    }

    /** 状态与时间线原子提交：时间线写入失败时，新 Hypothesis 与状态变化都不落库。 */
    @Test
    void timelineFailureRollsBackHypothesisWrites() {
        long id = propose();
        doThrow(new IllegalStateException("timeline down")).when(timeline).append(any());

        assertThatThrownBy(
                        () -> service.proposeHypothesis(new ProposeHypothesisCommand(fixture.incidentId(), "t", null)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> service.updateHypothesisStatus(
                        new UpdateHypothesisStatusCommand(fixture.incidentId(), id, HypothesisStatus.SUPPORTED, null)))
                .isInstanceOf(IllegalStateException.class);

        doCallRealMethod().when(timeline).append(any());
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM hypothesis WHERE investigation_id = ?",
                        Integer.class,
                        fixture.investigationId()))
                .isOne();
        assertThat(fixture.hypothesisRow(id)).containsEntry("status", "PENDING").containsEntry("lock_version", 0L);
        assertThat(fixture.events("HYPOTHESIS_STATUS_CHANGED")).isEmpty();
    }

    private long propose() {
        return service.proposeHypothesis(new ProposeHypothesisCommand(fixture.incidentId(), "Redis 性能异常", null))
                .id();
    }

    private void assertRejected(HypothesisStatus target, long hypothesisId, ErrorCode code) {
        assertThatThrownBy(() -> service.updateHypothesisStatus(
                        new UpdateHypothesisStatusCommand(fixture.incidentId(), hypothesisId, target, null)))
                .isInstanceOfSatisfying(
                        OpsPilotException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(code));
    }
}
