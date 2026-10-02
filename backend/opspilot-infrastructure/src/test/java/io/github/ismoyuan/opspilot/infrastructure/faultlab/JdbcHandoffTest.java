package io.github.ismoyuan.opspilot.infrastructure.faultlab;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ismoyuan.opspilot.infrastructure.faultlab.DemoMysqlSlowQueryEnvironment.JdbcHandoff;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * B36-R2：后台 JDBC 线程的连接交接与调用方取消。只有两种先后：取消在前则不得开始 SQL，开始在前则取消中止该连接；真实建连期间取消见
 * {@link MysqlSlowQueryInjectorIntegrationTest#aTimedOutClearDoesNotRunLater()}。
 */
class JdbcHandoffTest {

    @Test
    void aCancelledCallDoesNotStartSql() {
        AtomicInteger aborts = new AtomicInteger();
        JdbcHandoff handoff = new JdbcHandoff();

        handoff.cancel();

        assertThat(handoff.start(connection(aborts))).isFalse();
        assertThat(aborts).hasValue(0);
    }

    @Test
    void cancellingAStartedCallAbortsItsConnection() {
        AtomicInteger aborts = new AtomicInteger();
        JdbcHandoff handoff = new JdbcHandoff();

        assertThat(handoff.start(connection(aborts))).isTrue();
        handoff.cancel();

        assertThat(aborts).hasValue(1);
    }

    /** 交接与取消同时发生：每一次要么不开始，要么开始后被中止，不存在开始了却未中止的情况。 */
    @Test
    void racingHandoffAndCancelNeverLeavesAStartedConnectionRunning() throws Exception {
        for (int i = 0; i < 2000; i++) {
            AtomicInteger aborts = new AtomicInteger();
            JdbcHandoff handoff = new JdbcHandoff();
            CyclicBarrier barrier = new CyclicBarrier(2);
            CompletableFuture<Boolean> started = CompletableFuture.supplyAsync(() -> {
                await(barrier);
                return handoff.start(connection(aborts));
            });
            await(barrier);
            handoff.cancel();

            assertThat(aborts.get()).isEqualTo(started.get() ? 1 : 0);
        }
    }

    private static Connection connection(AtomicInteger aborts) {
        return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(), new Class<?>[] {Connection.class}, (proxy, method, args) -> {
                    if (method.getName().equals("abort")) {
                        aborts.incrementAndGet();
                        return null;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    private static void await(CyclicBarrier barrier) {
        try {
            barrier.await();
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
