package io.github.ismoyuan.opspilot.infrastructure.persistence.mybatis.incident;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ismoyuan.opspilot.application.error.ApplicationException;
import io.github.ismoyuan.opspilot.application.incident.IncidentRepository;
import io.github.ismoyuan.opspilot.domain.error.ErrorCode;
import io.github.ismoyuan.opspilot.domain.incident.Incident;
import io.github.ismoyuan.opspilot.domain.incident.IncidentKey;
import io.github.ismoyuan.opspilot.domain.incident.IncidentStatus;
import io.github.ismoyuan.opspilot.domain.incident.IncidentTransition;
import io.github.ismoyuan.opspilot.domain.incident.IncidentTrigger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

/**
 * 真实 MySQL 上验证 expectedStatus＋expectedVersion 条件更新（04 §14、07 §35）：成功加版本、冲突分类，
 * 以及两个并发迁移只成功一个。各事务真实提交，因此每个用例使用独立 Incident。
 */
@SpringBootTest
@Testcontainers
class MyBatisIncidentTransitionTest {

    private static final Instant AT = Instant.parse("2026-09-27T02:03:04.567891Z");
    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4.11");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired
    IncidentRepository incidents;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PlatformTransactionManager transactionManager;

    TransactionTemplate tx;
    ExecutorService executor;
    long systemId;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(transactionManager);
        executor = Executors.newFixedThreadPool(8);
        systemId = insert(
                "INSERT INTO managed_system (system_key, name, environment, status, created_at, updated_at) VALUES"
                        + " (?, 'ShortLink', 'DEMO', 'ACTIVE', UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))",
                "system-" + SEQUENCE.incrementAndGet());
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    @Test
    void legalTransitionUpdatesStatusAndIncrementsVersion() {
        Incident created = incident(IncidentStatus.CREATED, 0);

        Incident investigating =
                inTx(() -> incidents.apply(created.transitionFor(IncidentTrigger.START_INVESTIGATION), AT));

        assertThat(investigating.status()).isEqualTo(IncidentStatus.INVESTIGATING);
        assertThat(investigating.version()).isEqualTo(1);
        assertThat(investigating.resolvedAt()).isNull();
        assertThat(jdbc.queryForObject(
                        "SELECT DATE_FORMAT(updated_at, '%Y-%m-%dT%H:%i:%s.%f') FROM incident WHERE id = ?",
                        String.class, created.id()))
                .isEqualTo("2026-09-27T02:03:04.567000");
        assertThat(incidents.findByKey(created.incidentKey())).contains(investigating);
    }

    @Test
    void resolvingWritesResolvedAtInTheSameUpdate() {
        Incident verifying = incident(IncidentStatus.VERIFYING, 4);

        Incident resolved =
                inTx(() -> incidents.apply(verifying.transitionFor(IncidentTrigger.VERIFICATION_PASSED), AT));

        assertThat(resolved.status()).isEqualTo(IncidentStatus.RESOLVED);
        assertThat(resolved.version()).isEqualTo(5);
        assertThat(resolved.resolvedAt()).isEqualTo(Instant.parse("2026-09-27T02:03:04.567Z"));
    }

    @Test
    void changedStatusIsStateConflictWithCurrentFacts() {
        Incident created = incident(IncidentStatus.CREATED, 0);
        IncidentTransition start = created.transitionFor(IncidentTrigger.START_INVESTIGATION);
        inTx(() -> incidents.apply(start, AT));

        assertConflict(
                () -> inTx(() -> incidents.apply(created.transitionFor(IncidentTrigger.CANCEL_INCIDENT), AT)),
                ErrorCode.INCIDENT_STATE_CONFLICT,
                Map.of(
                        "incidentKey",
                        created.incidentKey().value(),
                        "currentStatus",
                        "INVESTIGATING",
                        "expectedStatuses",
                        List.of("CREATED"),
                        "version",
                        1L));
        assertThat(incidents.findById(created.id()).orElseThrow().version()).isEqualTo(1);
    }

    @Test
    void sameStatusWithOtherVersionIsVersionConflict() {
        Incident stale = incident(IncidentStatus.DIAGNOSED, 5);
        jdbc.update("UPDATE incident SET lock_version = 6 WHERE id = ?", stale.id());

        assertConflict(
                () -> inTx(() -> incidents.apply(stale.transitionFor(IncidentTrigger.CONTINUE_INVESTIGATION), AT)),
                ErrorCode.INCIDENT_VERSION_CONFLICT,
                Map.of("incidentKey", stale.incidentKey().value(), "currentStatus", "DIAGNOSED", "version", 6L));
        assertThat(incidents.findById(stale.id()).orElseThrow().status()).isEqualTo(IncidentStatus.DIAGNOSED);
    }

    /** status 条件独立生效：即使版本号相同，状态不符也不得写入。 */
    @Test
    void statusPredicateHoldsEvenWhenVersionMatches() {
        Incident created = incident(IncidentStatus.CREATED, 3);
        jdbc.update("UPDATE incident SET status = 'CANCELLED' WHERE id = ?", created.id());

        assertConflict(
                () -> inTx(() -> incidents.apply(created.transitionFor(IncidentTrigger.START_INVESTIGATION), AT)),
                ErrorCode.INCIDENT_STATE_CONFLICT,
                Map.of(
                        "incidentKey",
                        created.incidentKey().value(),
                        "currentStatus",
                        "CANCELLED",
                        "expectedStatuses",
                        List.of("CREATED"),
                        "version",
                        3L));
        assertThat(incidents.findById(created.id()).orElseThrow().status()).isEqualTo(IncidentStatus.CANCELLED);
    }

    @Test
    void missingIncidentIsNotFound() {
        IncidentTransition ghost = new IncidentTransition(
                Long.MAX_VALUE,
                IncidentTrigger.START_INVESTIGATION,
                IncidentStatus.CREATED,
                0,
                IncidentStatus.INVESTIGATING);

        assertConflict(
                () -> inTx(() -> incidents.apply(ghost, AT)),
                ErrorCode.INCIDENT_NOT_FOUND,
                Map.of("incidentId", Long.MAX_VALUE));
    }

    /** 第二个迁移在第一个提交前被行锁阻塞，提交后按新事实判定冲突，不会覆盖第一个结果。 */
    @Test
    void concurrentTransitionWaitsForFirstCommitThenConflicts() throws Exception {
        Incident created = incident(IncidentStatus.CREATED, 0);
        CountDownLatch firstUpdated = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);

        Future<Incident> first = executor.submit(() -> inTx(() -> {
            Incident updated = incidents.apply(created.transitionFor(IncidentTrigger.START_INVESTIGATION), AT);
            firstUpdated.countDown();
            await(releaseFirst);
            return updated;
        }));
        assertThat(firstUpdated.await(10, TimeUnit.SECONDS)).isTrue();

        Future<Incident> second = executor.submit(
                () -> inTx(() -> incidents.apply(created.transitionFor(IncidentTrigger.CANCEL_INCIDENT), AT)));
        assertThatThrownBy(() -> second.get(700, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);

        releaseFirst.countDown();
        assertThat(first.get(10, TimeUnit.SECONDS).status()).isEqualTo(IncidentStatus.INVESTIGATING);
        assertThatThrownBy(() -> second.get(10, TimeUnit.SECONDS))
                .hasCauseInstanceOf(ApplicationException.class)
                .cause()
                .satisfies(cause -> assertThat(((ApplicationException) cause).errorCode())
                        .isEqualTo(ErrorCode.INCIDENT_STATE_CONFLICT));
        Incident after = incidents.findById(created.id()).orElseThrow();
        assertThat(after.status()).isEqualTo(IncidentStatus.INVESTIGATING);
        assertThat(after.version()).isEqualTo(1);
    }

    /** 同时发起的相互竞争迁移恰好一个成功，其余均为冲突。 */
    @Test
    void racingTransitionsHaveExactlyOneWinner() throws Exception {
        Incident created = incident(IncidentStatus.CREATED, 0);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<IncidentStatus>> results = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            IncidentTrigger trigger =
                    i % 2 == 0 ? IncidentTrigger.START_INVESTIGATION : IncidentTrigger.CANCEL_INCIDENT;
            results.add(executor.submit(() -> {
                await(start);
                try {
                    return inTx(() -> incidents.apply(created.transitionFor(trigger), AT))
                            .status();
                } catch (ApplicationException ex) {
                    assertThat(ex.errorCode()).isEqualTo(ErrorCode.INCIDENT_STATE_CONFLICT);
                    return null;
                }
            }));
        }
        start.countDown();

        List<IncidentStatus> winners = new ArrayList<>();
        for (Future<IncidentStatus> result : results) {
            IncidentStatus status = result.get(30, TimeUnit.SECONDS);
            if (status != null) {
                winners.add(status);
            }
        }
        Incident after = incidents.findById(created.id()).orElseThrow();
        assertThat(winners).hasSize(1).containsExactly(after.status());
        assertThat(after.version()).isEqualTo(1);
    }

    private Incident incident(IncidentStatus status, long version) {
        String key = String.format("INC-20260927-%04d", SEQUENCE.incrementAndGet());
        insert(
                "INSERT INTO incident (incident_key, managed_system_id, title, impact_summary, status, created_source,"
                        + " created_by, started_at, detected_at, resolved_at, created_at, updated_at, lock_version)"
                        + " VALUES (?, ?, '短链接跳转明显变慢', '跳转变慢', ?, 'MANUAL', 'demo-user', UTC_TIMESTAMP(3),"
                        + " UTC_TIMESTAMP(3), NULL, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3), ?)",
                key,
                systemId,
                status.name(),
                version);
        return incidents.findByKey(new IncidentKey(key)).orElseThrow();
    }

    private <T> T inTx(java.util.function.Supplier<T> action) {
        return tx.execute(status -> action.get());
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("latch timeout");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }

    private static void assertConflict(Runnable action, ErrorCode code, Map<String, Object> details) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ApplicationException.class, ex -> {
            assertThat(ex.errorCode()).isEqualTo(code);
            assertThat(ex.details()).isEqualTo(details);
        });
    }

    private long insert(String sql, Object... args) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(
                connection -> {
                    var statement = connection.prepareStatement(sql, new String[] {"id"});
                    for (int i = 0; i < args.length; i++) {
                        statement.setObject(i + 1, args[i]);
                    }
                    return statement;
                },
                keys);
        return keys.getKey().longValue();
    }
}
