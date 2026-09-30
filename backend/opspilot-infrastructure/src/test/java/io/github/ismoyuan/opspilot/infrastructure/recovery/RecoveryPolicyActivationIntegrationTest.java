package io.github.ismoyuan.opspilot.infrastructure.recovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ismoyuan.opspilot.application.ClockConfiguration;
import io.github.ismoyuan.opspilot.application.ai.protocol.v1.MetricsQueryArgumentsV1;
import io.github.ismoyuan.opspilot.application.ai.protocol.v1.QueueInspectArgumentsV1;
import io.github.ismoyuan.opspilot.application.ai.protocol.v1.ServiceInspectArgumentsV1;
import io.github.ismoyuan.opspilot.application.ai.protocol.v1.WindowKey;
import io.github.ismoyuan.opspilot.application.capability.CapabilityAccess;
import io.github.ismoyuan.opspilot.application.capability.CapabilityProviderResolver;
import io.github.ismoyuan.opspilot.application.error.ApplicationException;
import io.github.ismoyuan.opspilot.application.recovery.RecoveryCriterionV1;
import io.github.ismoyuan.opspilot.application.recovery.RecoveryPolicyActivationService;
import io.github.ismoyuan.opspilot.application.recovery.RecoveryPolicyActivationService.ActivateCommand;
import io.github.ismoyuan.opspilot.application.recovery.RecoveryPolicyActivationService.Activated;
import io.github.ismoyuan.opspilot.application.recovery.RecoveryPolicyCriteriaV1;
import io.github.ismoyuan.opspilot.application.recovery.RecoveryPolicyRecord;
import io.github.ismoyuan.opspilot.application.recovery.RecoveryPolicyRepository;
import io.github.ismoyuan.opspilot.application.recovery.RecoveryPolicyValidator;
import io.github.ismoyuan.opspilot.application.recovery.RecoveryPredicateV1.ComparisonOperator;
import io.github.ismoyuan.opspilot.application.recovery.RecoveryPredicateV1.FieldEquals;
import io.github.ismoyuan.opspilot.application.recovery.RecoveryPredicateV1.NumericCompare;
import io.github.ismoyuan.opspilot.application.recovery.RecoverySamplingV1;
import io.github.ismoyuan.opspilot.application.schema.SchemaCodecRegistry;
import io.github.ismoyuan.opspilot.domain.error.ErrorCode;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

/**
 * 真实 MySQL 上的 RecoveryPolicy 激活（08 TASK-076、04 §49）：以 ShortLink demo 配置与其 S3 策略 v1 为起点，验证 ACTIVE 前校验
 * （同系统、OBSERVE 绑定、唯一 Provider、metricKey）拒绝时不写入；激活退休旧 ACTIVE（跨 policy_key）、按 key 递增版本且不改写旧版本；
 * 并发首次激活经资源父行锁串行，最终只有一个 ACTIVE。
 */
@SpringBootTest(properties = "spring.flyway.locations=classpath:db/migration,classpath:db/demo")
@Testcontainers
@Import({
    RecoveryPolicyActivationService.class,
    RecoveryPolicyValidator.class,
    CapabilityAccess.class,
    CapabilityProviderResolver.class,
    ClockConfiguration.class
})
class RecoveryPolicyActivationIntegrationTest {

    private static final String SEED = "db/demo/R__shortlink_demo_seed.sql";

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4.11");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired
    RecoveryPolicyActivationService activation;

    @Autowired
    RecoveryPolicyRepository policies;

    @Autowired
    SchemaCodecRegistry codecs;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    DataSource dataSource;

    long consumer;
    long stream;

    /** 每个用例从 demo 配置与 S3 v1 重新开始；Seed 的 upsert 同时恢复被用例改动的绑定。 */
    @BeforeEach
    void reset() throws Exception {
        jdbc.update("DELETE FROM recovery_policy");
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource(SEED));
        }
        consumer = resourceId("statistics-consumer");
        stream = resourceId("statistics-stream");
    }

    @Test
    void aNewVersionRetiresTheActiveOneWithoutRewritingIt() {
        RecoveryPolicyRecord seeded = policies.findActive(consumer).getFirst();
        String seededRow = policyRow(seeded.id());

        Activated activated = activation.activate(
                new ActivateCommand(consumer, "statistics-consumer-recovery", "统计消费者恢复标准", tightened(seeded)));

        assertThat(activated.versionNo()).isEqualTo(2);
        assertThat(activated.retiredCount()).isEqualTo(1);
        assertThat(policies.findActive(consumer)).singleElement().satisfies(active -> {
            assertThat(active.id()).isEqualTo(activated.policyId());
            assertThat(active.versionNo()).isEqualTo(2);
            assertThat(decode(active).criteria().get(2).predicate())
                    .isEqualTo(new NumericCompare("pendingCount", ComparisonOperator.LTE, 10.0));
        });
        assertThat(jdbc.queryForObject("SELECT status FROM recovery_policy WHERE id = ?", String.class, seeded.id()))
                .isEqualTo("RETIRED");
        assertThat(jdbc.queryForObject(
                        "SELECT retired_at IS NOT NULL AND retired_at >= activated_at FROM recovery_policy WHERE id = ?",
                        Boolean.class,
                        seeded.id()))
                .isTrue();
        assertThat(policyRow(seeded.id())).isEqualTo(seededRow);
    }

    /** 每个资源最多一个 ACTIVE 跨 policy_key 生效（04 §49）；新 key 从版本 1 开始。 */
    @Test
    void anotherPolicyKeyAlsoReplacesTheActivePolicy() {
        Activated activated = activation.activate(new ActivateCommand(
                consumer,
                "consumer-running-only",
                "消费者运行检查",
                RecoveryPolicyCriteriaV1.of(60, 60, List.of(consumerRunning()))));

        assertThat(activated.versionNo()).isEqualTo(1);
        assertThat(activated.retiredCount()).isEqualTo(1);
        assertThat(activeCount(consumer)).isEqualTo(1);
        assertThat(policies.findActive(consumer).getFirst().policyKey()).isEqualTo("consumer-running-only");
    }

    @Test
    void theFirstPolicyOfAResourceStartsAtVersionOne() {
        Activated activated = activation.activate(new ActivateCommand(
                stream, "stream-recovery", "积压恢复", RecoveryPolicyCriteriaV1.of(60, 60, List.of(lagDrained()))));

        assertThat(activated).isEqualTo(new Activated(activated.policyId(), 1, 0));
        assertThat(activeCount(stream)).isEqualTo(1);
        assertThat(activeCount(consumer)).isEqualTo(1);
    }

    /** ACTIVE 前校验（08 TASK-076）：任一项不合格即拒绝，已有策略与版本不变。 */
    @Test
    void criteriaThatCannotRunAreRejectedWithoutWriting() {
        String before = allPolicies();

        rejects(ErrorCode.RESOURCE_NOT_IN_SYSTEM, "TARGET_NOT_IN_SYSTEM", () -> single(queue("billing-stream")));
        rejects(
                ErrorCode.CAPABILITY_NOT_ALLOWED,
                "RESOURCE_TYPE_NOT_SUPPORTED",
                () -> single(serviceOn("statistics-stream")));
        rejects(
                ErrorCode.CAPABILITY_ARGUMENT_INVALID,
                "METRIC_KEY_NOT_AVAILABLE",
                () -> single(metric("custom.metric")));

        jdbc.update("UPDATE capability_binding SET enabled = FALSE WHERE managed_resource_id = ?", stream);
        rejects(ErrorCode.CAPABILITY_NOT_BOUND, "BINDING_MISSING_OR_DISABLED", () -> single(lagDrained()));
        jdbc.update("UPDATE capability_binding SET enabled = TRUE WHERE managed_resource_id = ?", stream);

        jdbc.update(
                "UPDATE resource_binding SET selector_payload = JSON_OBJECT() WHERE managed_resource_id = ?", stream);
        rejects(ErrorCode.CAPABILITY_PROVIDER_NOT_CONFIGURED, null, () -> single(lagDrained()));

        jdbc.update("DELETE FROM resource_binding WHERE managed_resource_id = ?", stream);
        rejects(ErrorCode.CAPABILITY_PROVIDER_NOT_CONFIGURED, null, () -> single(lagDrained()));

        jdbc.update("UPDATE managed_resource SET status = 'DISABLED' WHERE id = ?", stream);
        rejects(ErrorCode.CAPABILITY_NOT_ALLOWED, "RESOURCE_NOT_ACTIVE", () -> single(lagDrained()));

        assertThat(allPolicies()).isEqualTo(before);
    }

    @Test
    void invalidKeysNamesAndResourcesAreRejectedWithoutWriting() {
        String before = allPolicies();
        RecoveryPolicyCriteriaV1 criteria = RecoveryPolicyCriteriaV1.of(60, 60, List.of(consumerRunning()));

        assertThatThrownBy(
                        () -> activation.activate(new ActivateCommand(consumer, "Consumer Recovery", "名称", criteria)))
                .isInstanceOfSatisfying(
                        ApplicationException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.REQUEST_VALIDATION_FAILED));
        assertThatThrownBy(
                        () -> activation.activate(new ActivateCommand(consumer, "consumer-recovery", "  ", criteria)))
                .isInstanceOfSatisfying(
                        ApplicationException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.REQUEST_VALIDATION_FAILED));
        assertThatThrownBy(() -> activation.activate(
                        new ActivateCommand(consumer, "consumer-recovery", "名".repeat(129), criteria)))
                .isInstanceOfSatisfying(
                        ApplicationException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.REQUEST_VALIDATION_FAILED));
        assertThatThrownBy(() -> activation.activate(new ActivateCommand(999_999, "consumer-recovery", "名称", criteria)))
                .isInstanceOfSatisfying(
                        ApplicationException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));

        assertThat(allPolicies()).isEqualTo(before);
    }

    /**
     * 同一资源的并发首次激活（04 §49、03 §50）：资源父行锁让它们串行完成，没有死锁或唯一冲突，最终只有一个 ACTIVE，
     * 其余全部 RETIRED。
     */
    @Test
    void concurrentFirstActivationsLeaveExactlyOneActivePolicy() throws Exception {
        int workers = 8;
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Activated>> results = new ArrayList<>();
        try {
            for (int i = 0; i < workers; i++) {
                String key = "stream-recovery-" + i;
                results.add(pool.submit(() -> {
                    start.await();
                    return activation.activate(new ActivateCommand(
                            stream, key, "积压恢复 " + key, RecoveryPolicyCriteriaV1.of(60, 60, List.of(lagDrained()))));
                }));
            }
            start.countDown();
            for (Future<Activated> result : results) {
                assertThat(result.get(60, TimeUnit.SECONDS).versionNo()).isEqualTo(1);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(activeCount(stream)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM recovery_policy WHERE managed_resource_id = ? AND status = 'RETIRED'",
                        Integer.class,
                        stream))
                .isEqualTo(workers - 1);
        assertThat(results.stream()
                        .mapToInt(result -> {
                            try {
                                return result.get().retiredCount();
                            } catch (Exception ex) {
                                throw new IllegalStateException(ex);
                            }
                        })
                        .sum())
                .isEqualTo(workers - 1);
    }

    // ---------------------------------------------------------------- data

    private void rejects(ErrorCode code, String reason, Supplier<RecoveryPolicyCriteriaV1> criteria) {
        assertThatThrownBy(() ->
                        activation.activate(new ActivateCommand(stream, "stream-recovery", "积压恢复", criteria.get())))
                .isInstanceOfSatisfying(ApplicationException.class, ex -> {
                    assertThat(ex.errorCode()).isEqualTo(code);
                    assertThat(ex.details()).containsKey("criterionKey");
                    if (reason != null) {
                        assertThat(ex.details()).containsEntry("reason", reason);
                    }
                });
    }

    private static RecoveryPolicyCriteriaV1 single(RecoveryCriterionV1 criterion) {
        return RecoveryPolicyCriteriaV1.of(60, 60, List.of(criterion));
    }

    /** 同一 S3 合同，只把 pending 阈值收紧为 10。 */
    private RecoveryPolicyCriteriaV1 tightened(RecoveryPolicyRecord seeded) {
        RecoveryPolicyCriteriaV1 criteria = decode(seeded);
        List<RecoveryCriterionV1> changed = new ArrayList<>(criteria.criteria());
        RecoveryCriterionV1 pending = changed.get(2);
        changed.set(
                2,
                new RecoveryCriterionV1.QueueInspect(
                        pending.criterionKey(),
                        pending.name(),
                        pending.targetResourceKey(),
                        new QueueInspectArgumentsV1(),
                        pending.sampling(),
                        new NumericCompare("pendingCount", ComparisonOperator.LTE, 10.0),
                        pending.required()));
        return RecoveryPolicyCriteriaV1.of(criteria.maxDurationSeconds(), criteria.maxSampleAgeSeconds(), changed);
    }

    private RecoveryPolicyCriteriaV1 decode(RecoveryPolicyRecord record) {
        return codecs.decode(
                record.criteriaSchemaName(),
                record.criteriaSchemaVersion(),
                record.criteriaPayload(),
                RecoveryPolicyCriteriaV1.class);
    }

    private static RecoveryCriterionV1 lagDrained() {
        return queue("statistics-stream");
    }

    private static RecoveryCriterionV1 queue(String target) {
        return new RecoveryCriterionV1.QueueInspect(
                "stream-lag-drained",
                "积压达标",
                target,
                new QueueInspectArgumentsV1(),
                new RecoverySamplingV1(1, 0, null),
                new NumericCompare("lag", ComparisonOperator.LTE, 20.0),
                true);
    }

    private static RecoveryCriterionV1 consumerRunning() {
        return serviceOn("statistics-consumer");
    }

    private static RecoveryCriterionV1 serviceOn(String target) {
        return new RecoveryCriterionV1.ServiceInspect(
                "consumer-running",
                "消费者运行",
                target,
                new ServiceInspectArgumentsV1(),
                new RecoverySamplingV1(2, 5, 10),
                new FieldEquals("runtimeState", "RUNNING"),
                true);
    }

    private static RecoveryCriterionV1 metric(String metricKey) {
        return new RecoveryCriterionV1.MetricsQuery(
                "latency-recovered",
                "延迟恢复",
                "redirect-service",
                new MetricsQueryArgumentsV1(metricKey, WindowKey.LAST_15_MIN, false),
                new RecoverySamplingV1(1, 0, null),
                new NumericCompare("latest", ComparisonOperator.LT, 200.0),
                true);
    }

    private long resourceId(String key) {
        return jdbc.queryForObject("SELECT id FROM managed_resource WHERE resource_key = ?", Long.class, key);
    }

    private int activeCount(long resourceId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM recovery_policy WHERE managed_resource_id = ? AND status = 'ACTIVE'",
                Integer.class,
                resourceId);
    }

    private String policyRow(long id) {
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT policy_key, name, version_no, CAST(criteria_payload AS CHAR) AS criteria, activated_at"
                        + " FROM recovery_policy WHERE id = ?",
                id);
        return row.toString();
    }

    private String allPolicies() {
        return jdbc.queryForList("SELECT id, managed_resource_id, policy_key, version_no, status, retired_at,"
                        + " CAST(criteria_payload AS CHAR) AS criteria FROM recovery_policy ORDER BY id")
                .toString();
    }
}
