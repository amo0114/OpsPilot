package io.github.ismoyuan.opspilot.infrastructure.provider;

import io.github.ismoyuan.opspilot.domain.error.ErrorCode;
import io.github.ismoyuan.opspilot.domain.system.DataSourceConnection;
import io.github.ismoyuan.opspilot.domain.system.connection.RedisConnectionConfigV1;
import io.github.ismoyuan.opspilot.infrastructure.provider.RedisConnection.RedisErrorReply;
import java.net.InetSocketAddress;
import java.time.Clock;
import java.time.Instant;

/**
 * Redis Provider 的连接与认证（06 §63、07 §63）：按 redis.connection.config 与 credentialRef 以 ACL 账号 AUTH；有用户名却没有凭据为
 * INVALID_BINDING（不猜测）。错误应答映射：WRONGPASS/NOAUTH 与认证阶段的 ERR 为 AUTHENTICATION_FAILED，NOPERM 为
 * AUTHORIZATION_DENIED；其余由各 Provider 按语境处理。
 */
final class RedisAccess {

    private final ProviderAuthentication authentication;
    private final Clock clock;
    private final long maxResponseBytes;

    RedisAccess(ProviderAuthentication authentication, Clock clock, long maxResponseBytes) {
        this.authentication = authentication;
        this.clock = clock;
        this.maxResponseBytes = maxResponseBytes;
    }

    /** @throws ProviderCallException 配置、凭据、连接或认证失败 */
    RedisConnection connect(DataSourceConnection connection, Instant deadline) {
        RedisConnectionConfigV1 config =
                authentication.config(connection, RedisConnectionConfigV1.SCHEMA_NAME, RedisConnectionConfigV1.class);
        InetSocketAddress address = RedisConnection.address(connection.endpoint());
        String password = authentication.credential(connection);
        if (config.username() != null && password == null) {
            throw new ProviderCallException(ErrorCode.INVALID_BINDING, "Redis username requires a credential");
        }
        RedisConnection redis = RedisConnection.open(address, deadline, clock, maxResponseBytes);
        if (password == null) {
            return redis;
        }
        try {
            if (config.username() == null) {
                redis.call(RedisCommand.AUTH, deadline, password);
            } else {
                redis.call(RedisCommand.AUTH, deadline, config.username(), password);
            }
            return redis;
        } catch (RedisErrorReply ex) {
            redis.close();
            throw new ProviderCallException(ErrorCode.AUTHENTICATION_FAILED, "Redis rejected the credential");
        } catch (RuntimeException ex) {
            redis.close();
            throw ex;
        }
    }

    /** 认证之后的错误应答。 */
    static ProviderCallException translate(RedisErrorReply reply) {
        return switch (reply.code()) {
            case "NOPERM" ->
                new ProviderCallException(ErrorCode.AUTHORIZATION_DENIED, "Redis denied the command for this account");
            case "NOAUTH", "WRONGPASS" ->
                new ProviderCallException(ErrorCode.AUTHENTICATION_FAILED, "Redis rejected the credential");
            default -> new ProviderCallException(ErrorCode.QUERY_REJECTED, "Redis rejected the command");
        };
    }
}
