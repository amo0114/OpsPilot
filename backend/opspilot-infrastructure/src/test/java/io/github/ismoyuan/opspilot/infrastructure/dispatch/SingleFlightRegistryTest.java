package io.github.ismoyuan.opspilot.infrastructure.dispatch;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ismoyuan.opspilot.application.dispatch.DispatchableWork;
import io.github.ismoyuan.opspilot.application.dispatch.WorkKey;
import io.github.ismoyuan.opspilot.application.dispatch.WorkType;
import io.github.ismoyuan.opspilot.infrastructure.dispatch.SingleFlightRegistry.OwnerToken;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/** 08 TASK-036、07 §48～§51：拥有者 token、合并、延后的新 run 唤醒与只由拥有者释放。 */
class SingleFlightRegistryTest {

    private final SingleFlightRegistry registry = new SingleFlightRegistry();

    @Test
    void sameWorkWhileOwnedIsCoalescedWithoutAnyDeferredWake() {
        DispatchableWork run1 = new DispatchableWork.Investigation(7, 1);
        OwnerToken token = registry.acquireOrDefer(run1).orElseThrow();

        assertThat(registry.acquireOrDefer(run1)).isEmpty();
        assertThat(registry.acquireOrDefer(new DispatchableWork.Investigation(7, 1)))
                .isEmpty();

        assertThat(registry.release(token)).isEmpty();
        assertThat(registry.isOwned(run1.key())).isFalse();
    }

    /** 新 run 的唤醒不会因合并丢失：拥有者释放时交回最新的延后工作（07 §48）。 */
    @Test
    void newerRunWhileOwnedIsDeferredAndHandedBackOnRelease() {
        OwnerToken token = registry.acquireOrDefer(new DispatchableWork.Investigation(7, 1))
                .orElseThrow();

        assertThat(registry.acquireOrDefer(new DispatchableWork.Investigation(7, 2)))
                .isEmpty();
        assertThat(registry.acquireOrDefer(new DispatchableWork.Investigation(7, 3)))
                .isEmpty();

        assertThat(registry.release(token)).contains(new DispatchableWork.Investigation(7, 3));
        assertThat(registry.isOwned(new WorkKey(WorkType.INVESTIGATION, 7))).isFalse();
    }

    /** 旧 Worker 只能释放自己：过期 token 不能移除后来 Worker 的登记，也拿不走它的延后唤醒（08 TASK-036）。 */
    @Test
    void staleTokenCannotReleaseALaterOwner() {
        OwnerToken first = registry.acquireOrDefer(new DispatchableWork.Investigation(7, 1))
                .orElseThrow();
        assertThat(registry.release(first)).isEmpty();
        OwnerToken second = registry.acquireOrDefer(new DispatchableWork.Investigation(7, 2))
                .orElseThrow();
        registry.acquireOrDefer(new DispatchableWork.Investigation(7, 3));

        assertThat(registry.release(first)).isEmpty();

        assertThat(registry.isOwned(new WorkKey(WorkType.INVESTIGATION, 7))).isTrue();
        assertThat(registry.release(second)).contains(new DispatchableWork.Investigation(7, 3));
    }

    @Test
    void keysAreSeparatedByWorkType() {
        assertThat(registry.acquireOrDefer(new DispatchableWork.Investigation(5, 1)))
                .isPresent();
        assertThat(registry.acquireOrDefer(new DispatchableWork.ActionExecution(5)))
                .isPresent();
        assertThat(registry.acquireOrDefer(new DispatchableWork.RecoveryVerification(5)))
                .isPresent();
        assertThat(registry.acquireOrDefer(new DispatchableWork.ActionExecution(5)))
                .isEmpty();
    }

    @Test
    void concurrentAcquireGrantsExactlyOneOwner() throws Exception {
        int contenders = 16;
        ExecutorService pool = Executors.newFixedThreadPool(contenders);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Callable<Optional<OwnerToken>>> tasks = IntStream.range(0, contenders)
                    .<Callable<Optional<OwnerToken>>>mapToObj(i -> () -> {
                        start.await();
                        return registry.acquireOrDefer(new DispatchableWork.Investigation(9, 1));
                    })
                    .toList();
            List<Future<Optional<OwnerToken>>> results =
                    tasks.stream().map(pool::submit).toList();
            start.countDown();
            long owners = 0;
            for (Future<Optional<OwnerToken>> result : results) {
                owners += result.get().isPresent() ? 1 : 0;
            }
            assertThat(owners).isOne();
        } finally {
            pool.shutdownNow();
        }
    }
}
