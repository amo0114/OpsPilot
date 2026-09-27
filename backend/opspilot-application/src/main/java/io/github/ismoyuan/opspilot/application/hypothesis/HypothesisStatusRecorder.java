package io.github.ismoyuan.opspilot.application.hypothesis;

import io.github.ismoyuan.opspilot.application.correlation.Correlation;
import io.github.ismoyuan.opspilot.application.error.ApplicationException;
import io.github.ismoyuan.opspilot.application.investigation.ActiveInvestigation;
import io.github.ismoyuan.opspilot.application.timeline.TimelineRepository;
import io.github.ismoyuan.opspilot.domain.error.ErrorCode;
import io.github.ismoyuan.opspilot.domain.hypothesis.Hypothesis;
import io.github.ismoyuan.opspilot.domain.hypothesis.HypothesisStatus;
import io.github.ismoyuan.opspilot.domain.timeline.HypothesisStatusChangedPayloadV1;
import io.github.ismoyuan.opspilot.domain.timeline.NewTimelineEvent;
import io.github.ismoyuan.opspilot.domain.timeline.TimelineActorType;
import io.github.ismoyuan.opspilot.domain.timeline.TimelineEventType;
import java.time.Instant;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Hypothesis 状态变化的唯一写入点：条件更新当前状态并追加 HYPOTHESIS_STATUS_CHANGED，二者在调用方的同一事务内提交
 * （01 §14、04 §27）。单独更新与随 Evidence 附带的更新（05 §82）共用此处。
 */
@Component
public class HypothesisStatusRecorder {

    /** 状态变化理由上限，本批自定，与 Evidence reason 列一致。 */
    public static final int REASON_MAX = 1000;

    private final HypothesisRepository hypotheses;
    private final TimelineRepository timeline;

    public HypothesisStatusRecorder(HypothesisRepository hypotheses, TimelineRepository timeline) {
        this.hypotheses = hypotheses;
        this.timeline = timeline;
    }

    /**
     * 按 id 加锁读取属于该调查的 Hypothesis。不存在与属于其他 Investigation 同样拒绝，不泄露归属。
     *
     * @throws ApplicationException REQUEST_VALIDATION_FAILED（field=hypothesisId、reason=NOT_IN_INVESTIGATION）
     */
    public Hypothesis lockInInvestigation(ActiveInvestigation active, long hypothesisId) {
        return hypotheses
                .findByIdForUpdate(hypothesisId)
                .filter(h -> h.investigationId() == active.investigationId())
                .orElseThrow(() -> new ApplicationException(
                        ErrorCode.REQUEST_VALIDATION_FAILED,
                        "Hypothesis does not belong to the investigation",
                        Map.of(
                                "field",
                                "hypothesisId",
                                "reason",
                                "NOT_IN_INVESTIGATION",
                                "hypothesisId",
                                hypothesisId)));
    }

    /**
     * 调用方须已持有 {@code active} 与 {@code locked} 的行锁。
     *
     * @param evidenceId 随 Evidence 附带时为该 Evidence，否则为空
     * @throws io.github.ismoyuan.opspilot.domain.error.DomainException 非法状态变化
     */
    public Hypothesis change(
            ActiveInvestigation active,
            Hypothesis locked,
            HypothesisStatus target,
            String reason,
            Long evidenceId,
            Instant now) {
        String checkedReason = optionalReason(reason);
        Hypothesis changed = hypotheses.saveStatus(locked, locked.changeStatus(target, now));
        timeline.append(new NewTimelineEvent(
                active.incident().id(),
                TimelineEventType.HYPOTHESIS_STATUS_CHANGED,
                now,
                TimelineActorType.AI_RUNTIME,
                null,
                "假设「" + locked.title() + "」状态由 " + locked.status() + " 变为 " + target,
                new HypothesisStatusChangedPayloadV1(
                        active.incidentKey(),
                        active.investigationId(),
                        locked.id(),
                        locked.status().name(),
                        target.name(),
                        checkedReason,
                        evidenceId),
                Correlation.currentId()));
        return changed;
    }

    private static String optionalReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return null;
        }
        String text = reason.strip();
        if (text.codePointCount(0, text.length()) > REASON_MAX) {
            throw new ApplicationException(
                    ErrorCode.REQUEST_VALIDATION_FAILED,
                    "Hypothesis status reason too long",
                    Map.of("field", "reason", "reason", "TOO_LONG"));
        }
        return text;
    }
}
