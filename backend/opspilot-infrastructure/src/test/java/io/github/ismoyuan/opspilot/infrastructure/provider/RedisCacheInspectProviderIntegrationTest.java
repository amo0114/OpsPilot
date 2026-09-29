package io.github.ismoyuan.opspilot.infrastructure.provider;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ismoyuan.opspilot.application.ai.protocol.v1.CacheInspectArgumentsV1;
import io.github.ismoyuan.opspilot.application.capability.AdmittedInvocation;
import io.github.ismoyuan.opspilot.application.capability.provider.ProviderOutcome;
import io.github.ismoyuan.opspilot.application.capability.result.CacheInspectResultV1;
import io.github.ismoyuan.opspilot.application.secret.SecretNotFoundException;
import io.github.ismoyuan.opspilot.application.secret.SecretResolver;
import io.github.ismoyuan.opspilot.application.secret.SecretValue;
import io.github.ismoyuan.opspilot.domain.capability.CapabilityKey;
import io.github.ismoyuan.opspilot.domain.error.ErrorCode;
import io.github.ismoyuan.opspilot.domain.system.ProviderType;
import io.github.ismoyuan.opspilot.domain.system.binding.RedisResourceBindingV1;
import io.github.ismoyuan.opspilot.infrastructure.schema.SchemaCodecs;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;

/**
 * 08 TASK-054：真实 Redis（redis:7.4.5）。调查账号由 ACL 限定为只能 PING/INFO/XINFO（06 §63）；Provider 以该账号经 credentialRef 认证，
 * 读取真实 INFO 统计（命中/未命中、内存、连接等），不读取任何键值；认证失败、权限不足与配置不完整分别映射。
 */
class RedisCacheInspectProviderIntegrationTest {

    static GenericContainer<?> redis;

    static final Map<String, String> SECRETS = Map.of(
            "env://OPSPILOT_REDIS_OBSERVER", "observer-pass",
            "env://OPSPILOT_REDIS_NOINFO", "noinfo-pass",
            "env://OPSPILOT_REDIS_WRONG", "not-the-password");

    static final SecretResolver RESOLVER = reference -> {
        String value = SECRETS.get(reference);
        if (value == null) {
            throw new SecretNotFoundException(SecretNotFoundException.Reason.NOT_FOUND, "missing " + reference);
        }
        return new SecretValue(value);
    };

    @BeforeAll
    static void startRedis() throws Exception {
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
        cli("ACL", "SETUSER", "opspilot_noinfo", "on", ">noinfo-pass", "-@all", "+ping");
        cli("SET", "shortlink:link:Ab3x", "https://example.com/secret-target");
        cli("GET", "shortlink:link:Ab3x");
        cli("GET", "shortlink:link:missing");
    }

    @AfterAll
    static void stop() {
        if (redis != null) {
            redis.stop();
        }
    }

    @Test
    void realInfoStatisticsAreReadWithTheRestrictedAccount() throws Exception {
        ProviderOutcome.Fetched fetched =
                (ProviderOutcome.Fetched) fetch("env://OPSPILOT_REDIS_OBSERVER", "opspilot_observer");
        CacheInspectResultV1 result = (CacheInspectResultV1) fetched.result();

        assertThat(result.reachable()).isTrue();
        assertThat(result.pingLatencyMs()).isNotNull().isNotNegative();
        assertThat(result.usedMemoryBytes()).isPositive();
        assertThat(result.maxMemoryBytes()).isNull(); // 默认 maxmemory 0 = 未设置
        assertThat(result.connectedClients()).isPositive();
        assertThat(result.blockedClients()).isZero();
        assertThat(result.keyspaceHits()).isGreaterThanOrEqualTo(1);
        assertThat(result.keyspaceMisses()).isGreaterThanOrEqualTo(1);
        assertThat(result.hitRate()).isStrictlyBetween(0.0, 1.0);
        assertThat(result.uptimeSeconds()).isNotNull();
        // 原始结果只有白名单字段，没有键名、值或 INFO 中的其他字段
        assertThat(fetched.rawResult())
                .contains("used_memory:", "keyspace_hits:")
                .doesNotContain("shortlink:link")
                .doesNotContain("secret-target")
                .doesNotContain("executable")
                .doesNotContain("db0");

        cli("CONFIG", "SET", "maxmemory", "104857600");
        CacheInspectResultV1 limited = (CacheInspectResultV1)
                ((ProviderOutcome.Fetched) fetch("env://OPSPILOT_REDIS_OBSERVER", "opspilot_observer")).result();
        assertThat(limited.maxMemoryBytes()).isEqualTo(104_857_600L);
        cli("CONFIG", "SET", "maxmemory", "0");
    }

    @Test
    void credentialAndPermissionFailuresAreMapped() {
        assertThat(fetch("env://OPSPILOT_REDIS_WRONG", "opspilot_observer"))
                .isEqualTo(
                        new ProviderOutcome.Failed(ErrorCode.AUTHENTICATION_FAILED, "Redis rejected the credential"));
        assertThat(fetch("env://OPSPILOT_REDIS_NOINFO", "opspilot_noinfo"))
                .isEqualTo(new ProviderOutcome.Failed(
                        ErrorCode.AUTHORIZATION_DENIED, "Redis denied the command for this account"));
        assertThat(fetch(null, "opspilot_observer"))
                .isEqualTo(
                        new ProviderOutcome.Failed(ErrorCode.INVALID_BINDING, "Redis username requires a credential"));
        assertThat(fetch("env://OPSPILOT_REDIS_MISSING", "opspilot_observer"))
                .isEqualTo(new ProviderOutcome.Failed(
                        ErrorCode.SECRET_NOT_FOUND, "Connection credential is not available"));
    }

    static ProviderOutcome fetch(String credentialRef, String username) {
        AdmittedInvocation invocation = ProviderInvocations.admitted(
                CapabilityKey.CACHE_INSPECT,
                ProviderType.REDIS,
                "redis://" + redis.getHost() + ":" + redis.getMappedPort(6379),
                credentialRef,
                "{\"username\":\"" + username + "\"}",
                new RedisResourceBindingV1(null, null),
                new CacheInspectArgumentsV1(),
                null,
                Duration.ofSeconds(5));
        return ProviderInvocations.fetch(provider(), invocation);
    }

    static RedisCacheInspectProvider provider() {
        return new RedisCacheInspectProvider(
                new RedisAccess(
                        new ProviderAuthentication(SchemaCodecs.registry(), RESOLVER), Clock.systemUTC(), 1024 * 1024),
                Clock.systemUTC());
    }

    static String cli(String... command) throws Exception {
        String[] full = new String[command.length + 1];
        full[0] = "redis-cli";
        System.arraycopy(command, 0, full, 1, command.length);
        var result = redis.execInContainer(full);
        assertThat(result.getExitCode()).as(String.join(" ", command)).isZero();
        return result.getStdout().strip();
    }
}
