package io.github.ismoyuan.opspilot.application.hypothesis;

import io.github.ismoyuan.opspilot.domain.hypothesis.Hypothesis;
import io.github.ismoyuan.opspilot.domain.hypothesis.NewHypothesis;
import java.time.Instant;
import java.util.Optional;

/**
 * Hypothesis 持久化端口（04 §27）。没有删除，也没有改写标题/描述的方法；状态只经 {@link #saveStatus} 条件更新，
 * 且调用方必须在同一事务追加 HYPOTHESIS_STATUS_CHANGED（01 §14）。调用方须已锁定所属 Incident → Investigation。
 */
public interface HypothesisRepository {

    /** 以 PENDING、version 0 插入。 */
    Hypothesis insert(NewHypothesis hypothesis, Instant createdAt);

    /** 加排他锁读取。 */
    Optional<Hypothesis> findByIdForUpdate(long id);

    /**
     * 写入 {@link Hypothesis#changeStatus} 的结果：以 previous 的 Investigation、状态与 lock_version 为条件，lock_version 加一。
     *
     * @return 写入后的 Hypothesis
     */
    Hypothesis saveStatus(Hypothesis previous, Hypothesis changed);
}
