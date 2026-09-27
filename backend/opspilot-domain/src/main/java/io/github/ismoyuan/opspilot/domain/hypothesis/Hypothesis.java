package io.github.ismoyuan.opspilot.domain.hypothesis;

import io.github.ismoyuan.opspilot.domain.error.DomainException;
import io.github.ismoyuan.opspilot.domain.error.ErrorCode;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * 属于某个 Investigation 的待验证原因（03 §28～§29、04 §27）。只有当前状态可以变化，历史由时间线保存，
 * 不另建 HypothesisHistory。
 *
 * @param description 可为空
 * @param version 对应 lock_version
 */
public record Hypothesis(
        long id,
        long investigationId,
        String title,
        String description,
        HypothesisStatus status,
        Instant createdAt,
        Instant updatedAt,
        long version) {

    public Hypothesis {
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }

    /**
     * 改变当前判断；版本号由持久化条件更新递增。
     *
     * @throws DomainException 不满足 {@link HypothesisStatus#canChangeTo}（REQUEST_VALIDATION_FAILED，
     *     reason=ILLEGAL_TRANSITION）
     */
    public Hypothesis changeStatus(HypothesisStatus target, Instant at) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(at, "at");
        if (!status.canChangeTo(target)) {
            throw new DomainException(
                    ErrorCode.REQUEST_VALIDATION_FAILED,
                    "Illegal hypothesis status change " + status + " -> " + target,
                    Map.of(
                            "field",
                            "targetStatus",
                            "reason",
                            "ILLEGAL_TRANSITION",
                            "hypothesisId",
                            id,
                            "currentStatus",
                            status.name(),
                            "targetStatus",
                            target.name()));
        }
        return new Hypothesis(id, investigationId, title, description, target, createdAt, at, version);
    }
}
