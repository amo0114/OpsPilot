package io.github.ismoyuan.opspilot.infrastructure.provider;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ismoyuan.opspilot.application.ai.protocol.v1.QueueInspectArgumentsV1;
import io.github.ismoyuan.opspilot.application.capability.AdmittedInvocation;
import io.github.ismoyuan.opspilot.application.capability.provider.ProviderOutcome;
import io.github.ismoyuan.opspilot.application.capability.result.QueueInspectResultV1;
import io.github.ismoyuan.opspilot.application.capability.result.QueueInspectResultV1.ConsumerGroup;
import io.github.ismoyuan.opspilot.application.secret.SecretValue;
import io.github.ismoyuan.opspilot.domain.capability.CapabilityKey;
import io.github.ismoyuan.opspilot.domain.error.ErrorCode;
import io.github.ismoyuan.opspilot.domain.system.ProviderType;
import io.github.ismoyuan.opspilot.domain.system.binding.RedisResourceBindingV1;
import io.github.ismoyuan.opspilot.infrastructure.schema.SchemaCodecs;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;

/**
 * 08 TASK-056、09 §10～§12：真实 Redis Stream（redis:7.4.5）与只能 XINFO 的 ACL 账号。消费者读取 3 条、确认 1 条后停止，生产者继续
 * 写入：lag 增长而 pending 不变，streamLength 不等于积压；lastDeliveredAt 是消费者最近成功读取的真实时间，不是条目 ID 时间；删除条目
 * 导致 Redis 无法计算 lag 时为空；只返回指定组；不读取消息正文。
 */
class RedisQueueInspectProviderIntegrationTest {

    static GenericContainer<?> redis;
    static final List<String> ids = new ArrayList<>();
    static Instant beforeRead;
    static Instant afterRead;

    @BeforeAll
    static void prepareStream() throws Exception {
        redis = new GenericContainer<>("redis:7.4.5").withExposedPorts(6379);
        redis.start();
        cli(
                "ACL",
                "SETUSER",
                "opspilot_observer",
                "on",
                ">observer-pass",
                "resetkeys",
                "~shortlink:*",
                "-@all",
                "+ping",
                "+info",
                "+xinfo|stream",
                "+xinfo|groups",
                "+xinfo|consumers");
        cli("XGROUP", "CREATE", "shortlink:stats", "stats-consumer-group", "$", "MKSTREAM");
        cli("XGROUP", "CREATE", "shortlink:stats", "other-group", "$");
        for (int i = 0; i < 5; i++) {
            ids.add(cli("XADD", "shortlink:stats", "*", "payload", "secret-payload-" + i));
        }
        Thread.sleep(2_000); // 使条目生成时间与投递时间明显不同
        beforeRead = Instant.now();
        cli(
                "XREADGROUP",
                "GROUP",
                "stats-consumer-group",
                "consumer-1",
                "COUNT",
                "3",
                "STREAMS",
                "shortlink:stats",
                ">");
        afterRead = Instant.now();
        cli("XACK", "shortlink:stats", "stats-consumer-group", ids.getFirst());
        // 消费者停止后生产者继续
        for (int i = 5; i < 15; i++) {
            ids.add(cli("XADD", "shortlink:stats", "*", "payload", "secret-payload-" + i));
        }
        // 同组另有一个从未成功读取的消费者（inactive = -1，B17-R1）
        cli("XGROUP", "CREATECONSUMER", "shortlink:stats", "stats-consumer-group", "never-read");
        // 只有从未读取消费者的组
        cli("XGROUP", "CREATE", "shortlink:idle", "stats-consumer-group", "$", "MKSTREAM");
        cli("XGROUP", "CREATECONSUMER", "shortlink:idle", "stats-consumer-group", "never-read");
        cli("XADD", "shortlink:idle", "*", "payload", "a");
        cli("XADD", "shortlink:idle", "*", "payload", "b");
        // 有删除空洞的 Stream：Redis 无法计算 lag
        cli("XGROUP", "CREATE", "shortlink:holes", "stats-consumer-group", "0", "MKSTREAM");
        cli("XADD", "shortlink:holes", "*", "payload", "a");
        String middle = cli("XADD", "shortlink:holes", "*", "payload", "b");
        cli("XADD", "shortlink:holes", "*", "payload", "c");
        cli("XDEL", "shortlink:holes", middle);
        cli("XADD", "other:stream", "*", "payload", "x");
    }

    @AfterAll
    static void stop() {
        if (redis != null) {
            redis.stop();
        }
    }

    @Test
    void lagPendingAndDeliveryTimeAreReadForTheBoundGroupOnly() {
        ProviderOutcome.Fetched fetched = fetched("shortlink:stats", "stats-consumer-group");
        QueueInspectResultV1 result = (QueueInspectResultV1) fetched.result();

        assertThat(result.streamLength()).isEqualTo(15); // 保留条目数，不是积压
        assertThat(result.lastGeneratedId()).isEqualTo(ids.getLast());
        assertThat(result.lastGeneratedAt())
                .isEqualTo(Instant.ofEpochMilli(Long.parseLong(ids.getLast().split("-")[0])));
        assertThat(result.consumerGroups()).singleElement().satisfies(group -> {
            assertThat(group.group()).isEqualTo("stats-consumer-group");
            assertThat(group.lag()).isEqualTo(12L); // 尚未投递：15 − 3
            assertThat(group.pendingCount()).isEqualTo(2L); // 已投递未确认：3 − 1
            assertThat(group.consumerCount()).isEqualTo(2L); // consumer-1 与从未读取的 never-read
            assertThat(group.lastDeliveredId()).isEqualTo(ids.get(2));
            // 最近成功读取的时间（消费者的 inactive），不是第 3 条的生成时间
            assertThat(group.lastDeliveredAt()).isBetween(beforeRead.minusMillis(300), afterRead.plusMillis(300));
            // 第 3 条早在读取前 2 秒生成：若误用条目 ID 时间会落在上面区间之外
            assertThat(Instant.ofEpochMilli(Long.parseLong(ids.get(2).split("-")[0])))
                    .isBefore(beforeRead.minusMillis(1_500));
        });
        assertThat(fetched.rawResult() + result)
                .doesNotContain("secret-payload")
                .doesNotContain("other-group");
    }

    /** B17-R1：组内消费者都从未成功读取（inactive = -1）是合法状态：其余统计照常，投递时间为空。 */
    @Test
    void consumersThatNeverReadDoNotFailTheInspection() {
        QueueInspectResultV1 result = (QueueInspectResultV1)
                fetched("shortlink:idle", "stats-consumer-group").result();

        ConsumerGroup group = result.consumerGroups().getFirst();
        assertThat(group.consumerCount()).isEqualTo(1L);
        assertThat(group.lag()).isEqualTo(2L);
        assertThat(group.pendingCount()).isZero();
        assertThat(group.lastDeliveredAt()).isNull();
    }

    @Test
    void anUncomputableLagIsNullAndNotZero() {
        QueueInspectResultV1 result = (QueueInspectResultV1)
                fetched("shortlink:holes", "stats-consumer-group").result();

        ConsumerGroup group = result.consumerGroups().getFirst();
        assertThat(group.lag()).isNull();
        assertThat(group.lastDeliveredId()).isNull(); // 0-0：从未投递
        assertThat(group.lastDeliveredAt()).isNull(); // 没有消费者读取过
    }

    @Test
    void missingGroupsStreamsForbiddenKeysAndIncompleteBindings() {
        assertThat(((QueueInspectResultV1)
                                fetched("shortlink:stats", "missing-group").result())
                        .consumerGroups())
                .isEmpty();
        assertThat(fetch("shortlink:none", "stats-consumer-group"))
                .isEqualTo(new ProviderOutcome.Failed(ErrorCode.RESOURCE_NOT_FOUND, "Redis stream does not exist"));
        assertThat(fetch("other:stream", "stats-consumer-group"))
                .isEqualTo(new ProviderOutcome.Failed(
                        ErrorCode.AUTHORIZATION_DENIED, "Redis denied the command for this account"));
        assertThat(ProviderInvocations.fetch(provider(), invocation(new RedisResourceBindingV1(null, null))))
                .isEqualTo(
                        new ProviderOutcome.Failed(ErrorCode.INVALID_BINDING, "Resource binding has no Redis stream"));
    }

    private static ProviderOutcome.Fetched fetched(String stream, String group) {
        ProviderOutcome outcome = fetch(stream, group);
        assertThat(outcome).isInstanceOf(ProviderOutcome.Fetched.class);
        return (ProviderOutcome.Fetched) outcome;
    }

    private static ProviderOutcome fetch(String stream, String group) {
        return ProviderInvocations.fetch(provider(), invocation(new RedisResourceBindingV1(stream, group)));
    }

    private static AdmittedInvocation invocation(RedisResourceBindingV1 selector) {
        return ProviderInvocations.admitted(
                CapabilityKey.QUEUE_INSPECT,
                ProviderType.REDIS,
                "redis://" + redis.getHost() + ":" + redis.getMappedPort(6379),
                "env://OPSPILOT_REDIS_OBSERVER",
                "{\"username\":\"opspilot_observer\"}",
                selector,
                new QueueInspectArgumentsV1(),
                null,
                Duration.ofSeconds(5));
    }

    private static RedisQueueInspectProvider provider() {
        return new RedisQueueInspectProvider(
                new RedisAccess(
                        new ProviderAuthentication(
                                SchemaCodecs.registry(), reference -> new SecretValue("observer-pass")),
                        Clock.systemUTC(),
                        1024 * 1024),
                Clock.systemUTC());
    }

    private static String cli(String... command) throws Exception {
        String[] full = new String[command.length + 1];
        full[0] = "redis-cli";
        System.arraycopy(command, 0, full, 1, command.length);
        var result = redis.execInContainer(full);
        assertThat(result.getExitCode()).as(String.join(" ", command)).isZero();
        return result.getStdout().strip();
    }
}
