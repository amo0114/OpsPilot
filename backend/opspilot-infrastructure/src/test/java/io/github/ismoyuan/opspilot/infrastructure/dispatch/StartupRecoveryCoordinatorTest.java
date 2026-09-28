package io.github.ismoyuan.opspilot.infrastructure.dispatch;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ismoyuan.opspilot.application.dispatch.DispatchableWork;
import io.github.ismoyuan.opspilot.application.dispatch.InterruptedWorkRecorder;
import io.github.ismoyuan.opspilot.application.dispatch.StartupRecoveryCoordinator;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 08 TASK-043、07 §51～§52：派发前先记录中断，界限固定为本进程启动时刻；记录失败时不派发任何工作，由之后的补派发以同一界限重试，
 * 成功后才派发，此后补派发不再记录（B12-R1）。
 */
class StartupRecoveryCoordinatorTest {

    private static final Instant BOOT = Instant.parse("2026-09-28T08:00:00.123456Z");

    /** 第一次调用失败，之后成功；记录每次收到的界限，并把调用顺序写入共享事件。 */
    static final class FlakyRecorder implements InterruptedWorkRecorder {
        final List<Instant> cutoffs = new ArrayList<>();
        final List<String> events;

        FlakyRecorder(List<String> events) {
            this.events = events;
        }

        @Override
        public int recordInterrupted(Instant startedBefore) {
            cutoffs.add(startedBefore);
            events.add("record");
            if (cutoffs.size() == 1) {
                throw new IllegalStateException("database unavailable");
            }
            return 1;
        }
    }

    @Test
    void dispatchWaitsUntilTheInterruptedWorkIsRecorded() {
        List<String> events = new ArrayList<>();
        FlakyRecorder recorder = new FlakyRecorder(events);
        StartupRecoveryCoordinator coordinator = new StartupRecoveryCoordinator(
                List.of(() -> {
                    events.add("scan");
                    return List.of(new DispatchableWork.Investigation(7, 2));
                }),
                List.of(recorder),
                work -> events.add("dispatch"),
                Clock.fixed(BOOT, ZoneOffset.UTC));

        assertThat(coordinator.recoverAfterStartup()).isZero();
        assertThat(events).containsExactly("record");

        assertThat(coordinator.redispatchPending()).isOne();
        assertThat(coordinator.redispatchPending()).isOne();

        assertThat(recorder.cutoffs).containsExactly(BOOT.minusNanos(456_000), BOOT.minusNanos(456_000));
        assertThat(events).containsExactly("record", "record", "scan", "dispatch", "scan", "dispatch");
    }
}
