package io.github.ismoyuan.opspilot.infrastructure.dispatch;

import io.github.ismoyuan.opspilot.application.dispatch.DispatchableWork;
import io.github.ismoyuan.opspilot.application.dispatch.WorkKey;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 同一 JVM 内每个工作身份至多一个 Worker（07 §48～§49、§51）。只是运行保护：丢了不影响事实，数据库状态与 lock_version 仍是
 * 最终保护。登记、合并与释放在同一把锁内完成：
 *
 * <ul>
 *   <li>无拥有者时发放新的拥有者 token；
 *   <li>已有拥有者且请求相同工作（同一 run 或同一身份）时合并，不再排队；
 *   <li>已有拥有者但请求不同工作（调查的新 run）时记为延后唤醒，只保留最新一个，拥有者释放时交回调用方立即派发，
 *       因此新 run 唤醒不会因合并丢失；
 *   <li>释放必须出示当前拥有者 token，旧 Worker 不能移除后来 Worker 的登记。
 * </ul>
 */
public final class SingleFlightRegistry {

    /** 拥有者凭证：只能用来释放自己的登记。 */
    public record OwnerToken(WorkKey key, long serial) {}

    private record Entry(OwnerToken owner, DispatchableWork work, DispatchableWork deferred) {}

    private final Map<WorkKey, Entry> entries = new HashMap<>();
    private final AtomicLong serials = new AtomicLong();

    /** @return 取得拥有权时的 token；已被拥有时为空（已合并或已延后） */
    public synchronized Optional<OwnerToken> acquireOrDefer(DispatchableWork work) {
        Objects.requireNonNull(work, "work");
        Entry current = entries.get(work.key());
        if (current == null) {
            OwnerToken token = new OwnerToken(work.key(), serials.incrementAndGet());
            entries.put(work.key(), new Entry(token, work, null));
            return Optional.of(token);
        }
        if (!current.work().equals(work)) {
            entries.put(work.key(), new Entry(current.owner(), current.work(), work));
        }
        return Optional.empty();
    }

    /**
     * 只有当前拥有者能释放；释放同时取走延后的唤醒交给调用方派发。token 已过期时什么也不做。
     *
     * @return 释放时待派发的延后工作
     */
    public synchronized Optional<DispatchableWork> release(OwnerToken token) {
        Objects.requireNonNull(token, "token");
        Entry current = entries.get(token.key());
        if (current == null || !current.owner().equals(token)) {
            return Optional.empty();
        }
        entries.remove(token.key());
        return Optional.ofNullable(current.deferred());
    }

    public synchronized boolean isOwned(WorkKey key) {
        return entries.containsKey(key);
    }
}
