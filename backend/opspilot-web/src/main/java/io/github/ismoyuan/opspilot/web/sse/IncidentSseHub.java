package io.github.ismoyuan.opspilot.web.sse;

import io.github.ismoyuan.opspilot.application.incident.query.IncidentChanges;
import io.github.ismoyuan.opspilot.application.incident.query.IncidentChangesQueryService;
import io.github.ismoyuan.opspilot.application.incident.query.IncidentStateView;
import io.github.ismoyuan.opspilot.application.timeline.IncidentTimelineAppended;
import io.github.ismoyuan.opspilot.application.timeline.query.TimelineEventView;
import io.github.ismoyuan.opspilot.domain.incident.IncidentAction;
import io.github.ismoyuan.opspilot.domain.incident.IncidentStatus;
import io.github.ismoyuan.opspilot.domain.timeline.TimelineActorType;
import io.github.ismoyuan.opspilot.domain.timeline.TimelineEventType;
import io.github.ismoyuan.opspilot.web.response.ApiTimes;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Incident 事件流的内存连接管理器（08 TASK-087～089、05 §62～§66、07 §71～§74）。只持有连接与每个连接已发送到的游标，不是事实来源：
 * Java 重启时连接全部断开，浏览器带 Last-Event-ID 重连后从 Timeline 补发。
 *
 * <ul>
 *   <li>只发送已提交的事实（ENG-INV-016）：Timeline 追加的唤醒只在事务提交后到达（{@link TransactionPhase#AFTER_COMMIT}，回滚不唤醒），
 *       且唤醒不携带内容——发送端每次都以新的只读事务按订阅游标从数据库读取，按 id 顺序发送；回调到达的先后不代表提交先后
 *       （08 TASK-088）。同 Incident 的追加都先取 Incident 行锁（04 §57），游标之后不会再出现更小的已提交 id。
 *   <li>订阅先登记、再补读（08 TASK-089）：登记之前提交的事件由首次补读读到，之后提交的由唤醒触发再读，没有“查完历史、尚未注册”
 *       的空隙；游标只前进，同一连接不重复发送，跨重连由前端按 event id 去重。
 *   <li>同一 Incident 的补读与发送串行执行（SseEmitter 不支持并发发送）；heartbeat 在同一串行任务中发送，并顺带补读一次，使丢失的唤醒
 *       （如线程池拒绝）不会让连接永久停留在旧游标（07 §73）。
 *   <li>生命周期先于 Web 服务器的优雅停机结束：停止时先完成全部连接，停机不必等待长连接超时。
 * </ul>
 */
@Component
public class IncidentSseHub implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(IncidentSseHub.class);

    /** 每次读取的事件条数；不足一批说明已追上。 */
    static final int BATCH = 200;

    private final IncidentChangesQueryService changes;
    private final SseProperties properties;
    private final Map<Long, Channel> channels = new ConcurrentHashMap<>();
    private final ExecutorService senders;
    private final ScheduledExecutorService heartbeats;
    private final AtomicBoolean started = new AtomicBoolean();

    public IncidentSseHub(IncidentChangesQueryService changes, SseProperties properties) {
        this.changes = changes;
        this.properties = properties;
        AtomicInteger threads = new AtomicInteger();
        this.senders = Executors.newFixedThreadPool(2, task -> daemon(task, "sse-sender-" + threads.incrementAndGet()));
        this.heartbeats = Executors.newSingleThreadScheduledExecutor(task -> daemon(task, "sse-heartbeat"));
    }

    @Override
    public void start() {
        if (started.compareAndSet(false, true)) {
            long interval = properties.heartbeatInterval().toMillis();
            heartbeats.scheduleAtFixedRate(this::heartbeat, interval, interval, TimeUnit.MILLISECONDS);
        }
    }

    /** 默认相位最高，先于 Web 服务器的优雅停机停止（Spring 按相位从高到低停止）。 */
    @Override
    public void stop() {
        if (started.compareAndSet(true, false)) {
            heartbeats.shutdownNow();
            senders.shutdownNow();
            channels.values().forEach(channel -> channel.subscriptions.forEach(s -> s.emitter.complete()));
            channels.clear();
        }
    }

    @Override
    public boolean isRunning() {
        return started.get();
    }

    /**
     * 登记一个连接并安排首次补读。
     *
     * @param afterId 已收到的最后一个 Timeline id（Last-Event-ID 或 Snapshot 的 lastTimelineEventId），没有为 0：从第一条事件补发
     * @throws io.github.ismoyuan.opspilot.application.error.ApplicationException INCIDENT_NOT_FOUND（在建立事件流之前）
     */
    public SseEmitter subscribe(String incidentKey, long afterId) {
        long incidentId = changes.incidentId(incidentKey);
        SseEmitter emitter = new SseEmitter(properties.emitterTimeout().toMillis());
        Subscription subscription = new Subscription(emitter, afterId);
        Channel channel = channels.compute(incidentId, (id, existing) -> {
            Channel target = existing != null ? existing : new Channel(id, incidentKey);
            target.subscriptions.add(subscription);
            return target;
        });
        Runnable remove = () -> unsubscribe(incidentId, subscription);
        emitter.onCompletion(remove);
        emitter.onTimeout(remove);
        emitter.onError(error -> remove.run());
        schedule(channel);
        return emitter;
    }

    /** 事务提交后的唤醒（没有事务时为追加之后立即）；只安排补读，不在提交线程上读库或发送。 */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onTimelineAppended(IncidentTimelineAppended appended) {
        Channel channel = channels.get(appended.incidentId());
        if (channel != null) {
            schedule(channel);
        }
    }

    /** 当前连接数（07 §101 active SSE connections）。 */
    public int connectionCount() {
        return channels.values().stream()
                .mapToInt(channel -> channel.subscriptions.size())
                .sum();
    }

    private void heartbeat() {
        for (Channel channel : channels.values()) {
            channel.heartbeatDue.set(true);
            schedule(channel);
        }
    }

    /** 同一 Incident 同时只有一个补读任务；任务运行中到达的唤醒置脏，由该任务再读一轮。 */
    private void schedule(Channel channel) {
        channel.dirty.set(true);
        if (channel.running.compareAndSet(false, true)) {
            try {
                senders.execute(() -> drain(channel));
            } catch (RejectedExecutionException ex) {
                channel.running.set(false);
                log.warn("SSE catch-up not scheduled, next heartbeat retries: incidentId={}", channel.incidentId);
            }
        }
    }

    private void drain(Channel channel) {
        try {
            while (channel.dirty.getAndSet(false)) {
                boolean heartbeat = channel.heartbeatDue.getAndSet(false);
                for (Subscription subscription : channel.subscriptions) {
                    catchUp(channel, subscription, heartbeat);
                }
            }
        } catch (RuntimeException ex) {
            log.warn(
                    "SSE catch-up failed, next wake or heartbeat retries: incidentId={} error={}",
                    channel.incidentId,
                    ex.getClass().getSimpleName());
        } finally {
            channel.running.set(false);
        }
        if (channel.dirty.get()) {
            schedule(channel);
        }
    }

    /** 从该连接的游标起按 id 顺序发送已提交事件，追上后在状态变化时发送 incident-state。 */
    private void catchUp(Channel channel, Subscription subscription, boolean heartbeat) {
        try {
            IncidentChanges read;
            do {
                read = changes.read(channel.incidentKey, subscription.cursor, BATCH);
                for (TimelineEventView event : read.events()) {
                    subscription.emitter.send(SseEmitter.event()
                            .id(Long.toString(event.id()))
                            .name("timeline")
                            .data(TimelinePush.of(read.state(), event), MediaType.APPLICATION_JSON));
                    subscription.cursor = event.id();
                }
            } while (read.events().size() == BATCH);
            if (!read.state().equals(subscription.lastState)) {
                subscription.emitter.send(SseEmitter.event()
                        .name("incident-state")
                        .data(StatePush.of(read.state()), MediaType.APPLICATION_JSON));
                subscription.lastState = read.state();
            }
            if (heartbeat) {
                subscription.emitter.send(SseEmitter.event().name("heartbeat").data(""));
            }
        } catch (IOException | IllegalStateException ex) {
            // 连接已断开：移除即可，事实仍在 Timeline，重连按 Last-Event-ID 补发
            unsubscribe(channel.incidentId, subscription);
        }
    }

    private void unsubscribe(long incidentId, Subscription subscription) {
        channels.computeIfPresent(incidentId, (id, channel) -> {
            channel.subscriptions.remove(subscription);
            return channel.subscriptions.isEmpty() ? null : channel;
        });
    }

    private static Thread daemon(Runnable task, String name) {
        Thread thread = new Thread(task, name);
        thread.setDaemon(true);
        return thread;
    }

    private static final class Channel {

        final long incidentId;
        final String incidentKey;
        final Set<Subscription> subscriptions = ConcurrentHashMap.newKeySet();
        final AtomicBoolean running = new AtomicBoolean();
        final AtomicBoolean dirty = new AtomicBoolean();
        final AtomicBoolean heartbeatDue = new AtomicBoolean();

        Channel(long incidentId, String incidentKey) {
            this.incidentId = incidentId;
            this.incidentKey = incidentKey;
        }
    }

    /** 游标与已发送状态只由该 Incident 的串行补读任务读写。 */
    private static final class Subscription {

        final SseEmitter emitter;
        long cursor;
        IncidentStateView lastState;

        Subscription(SseEmitter emitter, long cursor) {
            this.emitter = emitter;
            this.cursor = cursor;
        }
    }

    /** 05 §64 event: timeline。 */
    record TimelinePush(String incidentKey, IncidentStatus incidentStatus, Event event) {

        record Event(
                long id, TimelineEventType eventType, String occurredAt, TimelineActorType actorType, String summary) {}

        static TimelinePush of(IncidentStateView state, TimelineEventView event) {
            return new TimelinePush(
                    state.incidentKey(),
                    state.status(),
                    new Event(
                            event.id(),
                            event.eventType(),
                            ApiTimes.format(event.occurredAt()),
                            event.actorType(),
                            event.summary()));
        }
    }

    /** 05 §64 event: incident-state。 */
    record StatePush(String incidentKey, IncidentStatus status, long version, List<IncidentAction> availableActions) {

        static StatePush of(IncidentStateView state) {
            return new StatePush(state.incidentKey(), state.status(), state.version(), state.availableActions());
        }
    }
}
