package io.github.ismoyuan.opspilot.infrastructure.dispatch;

import io.github.ismoyuan.opspilot.application.dispatch.ActionExecutionWorker;
import io.github.ismoyuan.opspilot.application.dispatch.InvestigationWorker;
import io.github.ismoyuan.opspilot.application.dispatch.RecoveryVerificationWorker;
import io.github.ismoyuan.opspilot.application.dispatch.StartupRecoveryCoordinator;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({WorkerProperties.class, DispatcherProperties.class})
class DispatchConfiguration {

    @Bean(destroyMethod = "shutdown")
    InProcessWorkDispatcher workDispatcher(
            WorkerProperties properties,
            ObjectProvider<InvestigationWorker> investigations,
            ObjectProvider<ActionExecutionWorker> executions,
            RecoveryVerificationWorker verifications) {
        return new InProcessWorkDispatcher(
                workerPool(properties),
                new SingleFlightRegistry(),
                investigations.getIfAvailable(UnwiredInvestigationWorker::new),
                executions.getIfAvailable(UnwiredActionExecutionWorker::new),
                verifications);
    }

    /** 固定大小、有界排队、满则拒绝（07 §47）。 */
    static ThreadPoolExecutor workerPool(WorkerProperties properties) {
        AtomicInteger threads = new AtomicInteger();
        return new ThreadPoolExecutor(
                properties.maxConcurrency(),
                properties.maxConcurrency(),
                0,
                TimeUnit.MILLISECONDS,
                properties.queueCapacity() == 0
                        ? new SynchronousQueue<>()
                        : new ArrayBlockingQueue<>(properties.queueCapacity()),
                runnable -> {
                    Thread thread = new Thread(runnable, "opspilot-worker-" + threads.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = "opspilot.dispatcher.recovery-enabled", havingValue = "true", matchIfMissing = true)
    DispatchRecoveryScheduler dispatchRecoveryScheduler(
            StartupRecoveryCoordinator coordinator, DispatcherProperties properties) {
        return new DispatchRecoveryScheduler(coordinator, properties);
    }

    /** 占位由真实 Verification Worker 所在 Task（TASK-079）删除。 */
    @Bean
    RecoveryVerificationWorker recoveryVerificationWorker() {
        return new PlaceholderRecoveryVerificationWorker();
    }
}
