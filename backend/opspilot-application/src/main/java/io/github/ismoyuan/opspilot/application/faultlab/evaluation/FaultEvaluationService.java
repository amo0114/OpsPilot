package io.github.ismoyuan.opspilot.application.faultlab.evaluation;

import io.github.ismoyuan.opspilot.application.error.ApplicationException;
import io.github.ismoyuan.opspilot.application.faultlab.FaultGroundTruthV1;
import io.github.ismoyuan.opspilot.domain.error.ErrorCode;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Evaluation 的内部代码路径（05 §73、09 §20）：自动验收（TASK-107～109）用 Ground Truth 评价调查结果。没有公开 HTTP 接口，调查链路不得调用。
 */
@Service
public class FaultEvaluationService {

    private final GroundTruthQuery groundTruths;

    public FaultEvaluationService(GroundTruthQuery groundTruths) {
        this.groundTruths = groundTruths;
    }

    /** @throws ApplicationException RESOURCE_NOT_FOUND */
    @Transactional(readOnly = true)
    public FaultGroundTruthV1 groundTruth(long experimentId) {
        return groundTruths
                .findByExperimentId(experimentId)
                .orElseThrow(() -> new ApplicationException(
                        ErrorCode.RESOURCE_NOT_FOUND,
                        "Fault experiment not found",
                        Map.of("experimentId", experimentId)));
    }
}
