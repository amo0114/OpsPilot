package io.github.ismoyuan.opspilot.application.evidence;

import io.github.ismoyuan.opspilot.domain.evidence.Evidence;
import io.github.ismoyuan.opspilot.domain.evidence.NewEvidence;
import java.time.Instant;
import java.util.Optional;

/**
 * Evidence 只插入端口（08 TASK-024、DB-INV-004）：没有更新或删除方法。同 Investigation 与唯一关系由数据库复合外键和
 * UNIQUE(observation_id, hypothesis_id) 做最后保护，业务拒绝由创建事务先行判定。
 */
public interface EvidenceRepository {

    Evidence insert(NewEvidence evidence, Instant createdAt);

    /** 加共享锁读取最新已提交的关系，不受事务快照影响。 */
    Optional<Evidence> findByObservationAndHypothesis(long observationId, long hypothesisId);
}
