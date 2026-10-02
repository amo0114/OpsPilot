package io.github.ismoyuan.opspilot.infrastructure.faultlab;

import static io.github.ismoyuan.opspilot.infrastructure.faultlab.DemoTrafficObserver.CALL_TIMEOUT;
import static io.github.ismoyuan.opspilot.infrastructure.faultlab.DemoTrafficObserver.bounded;
import static io.github.ismoyuan.opspilot.infrastructure.faultlab.DemoTrafficObserver.control;

import io.github.ismoyuan.opspilot.application.faultlab.ConsumerStopEnvironment;
import io.github.ismoyuan.opspilot.infrastructure.provider.DemoControlClient;
import io.github.ismoyuan.opspilot.infrastructure.provider.DemoControlClient.ContainerState;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * S3 Demo 靶场的真实访问（08 TASK-093）：Docker Engine API 控制消费者容器；统计 Stream 与跳转探测由 {@link DemoTrafficObserver} 完成。
 */
final class DemoConsumerStopEnvironment implements ConsumerStopEnvironment {

    private final DemoControlClient control;
    private final FaultLabProperties.StatisticsConsumerStop config;
    private final DemoTrafficObserver traffic;
    private final Clock clock;

    DemoConsumerStopEnvironment(
            DemoControlClient control, FaultLabProperties.StatisticsConsumerStop config, Clock clock) {
        this.control = control;
        this.config = config;
        this.clock = clock;
        this.traffic = new DemoTrafficObserver(
                control,
                new DemoTrafficObserver.StreamTarget(
                        config.redisEndpoint(),
                        config.redisUsername(),
                        config.redisPassword(),
                        config.streamKey(),
                        config.consumerGroup()),
                config.redirectProbeUrls(),
                config.probeTimeoutOrDefault(),
                clock);
    }

    @Override
    public ConsumerContainer inspectConsumer(Instant deadline) {
        Instant bounded = bounded(clock, deadline, CALL_TIMEOUT);
        ContainerState state =
                control(() -> control.inspectContainer(config.dockerEndpoint(), config.containerName(), bounded));
        return new ConsumerContainer(
                state.containerId(),
                switch (state.runtimeState()) {
                    case RUNNING -> RuntimeState.RUNNING;
                    case STOPPED -> RuntimeState.STOPPED;
                    case RESTARTING -> RuntimeState.RESTARTING;
                    case PAUSED -> RuntimeState.PAUSED;
                    case UNKNOWN -> RuntimeState.UNKNOWN;
                },
                switch (state.health()) {
                    case HEALTHY -> HealthState.HEALTHY;
                    case STARTING -> HealthState.STARTING;
                    case NOT_CONFIGURED -> HealthState.NOT_CONFIGURED;
                    case UNHEALTHY, UNKNOWN -> HealthState.UNHEALTHY;
                },
                state.startedAt(),
                state.finishedAt());
    }

    @Override
    public void stopConsumer(String containerId, Duration grace, Instant deadline) {
        Instant bounded = bounded(clock, deadline, CALL_TIMEOUT.plus(grace));
        control(() -> {
            control.stopContainer(config.dockerEndpoint(), containerId, Math.toIntExact(grace.toSeconds()), bounded);
            return null;
        });
    }

    @Override
    public void startConsumer(String containerId, Instant deadline) {
        Instant bounded = bounded(clock, deadline, CALL_TIMEOUT);
        control(() -> {
            control.startContainer(config.dockerEndpoint(), containerId, bounded);
            return null;
        });
    }

    @Override
    public StreamSnapshot readStream(Instant deadline) {
        return traffic.readStream(deadline);
    }

    @Override
    public RedirectProbe probeRedirect(Instant deadline) {
        return traffic.probeRedirect(deadline);
    }
}
