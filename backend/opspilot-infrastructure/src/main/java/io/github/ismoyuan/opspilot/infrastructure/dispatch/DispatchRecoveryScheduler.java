package io.github.ismoyuan.opspilot.infrastructure.dispatch;

import io.github.ismoyuan.opspilot.application.dispatch.StartupRecoveryCoordinator;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;

/**
 * 应用就绪后运行一次启动恢复，然后按 recoveryScanIntervalSeconds 周期补派发（07 §51）。扫描异常只记录，不中断后续扫描。
 */
public class DispatchRecoveryScheduler implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(DispatchRecoveryScheduler.class);

    private final StartupRecoveryCoordinator coordinator;
    private final DispatcherProperties properties;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "opspilot-dispatch-rescan");
        thread.setDaemon(true);
        return thread;
    });

    public DispatchRecoveryScheduler(StartupRecoveryCoordinator coordinator, DispatcherProperties properties) {
        this.coordinator = coordinator;
        this.properties = properties;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        guarded("startup", coordinator::recoverAfterStartup);
        long interval = properties.recoveryScanIntervalSeconds();
        scheduler.scheduleWithFixedDelay(
                () -> guarded("rescan", coordinator::redispatchPending), interval, interval, TimeUnit.SECONDS);
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
    }

    private static void guarded(String trigger, java.util.function.IntSupplier scan) {
        try {
            int dispatched = scan.getAsInt();
            if (dispatched > 0) {
                log.debug("Dispatch recovery {} woke {} work item(s)", trigger, dispatched);
            }
        } catch (RuntimeException ex) {
            log.warn(
                    "Dispatch recovery {} failed: exception={}",
                    trigger,
                    ex.getClass().getName());
        }
    }
}
