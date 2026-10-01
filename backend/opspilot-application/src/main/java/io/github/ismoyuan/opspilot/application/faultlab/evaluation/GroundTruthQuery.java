package io.github.ismoyuan.opspilot.application.faultlab.evaluation;

import io.github.ismoyuan.opspilot.application.faultlab.FaultGroundTruthV1;
import java.util.Optional;

/**
 * 读取 Ground Truth 的唯一端口（04 §64、05 §73、08 TASK-091）：只供 Fault Lab 的 Evaluation 代码路径使用。调查上下文、AI 客户端、
 * Capability、产品查询与 Web API 不得依赖本端口或 fault_experiment.ground_truth_payload（ACC-INV-003）。
 */
public interface GroundTruthQuery {

    Optional<FaultGroundTruthV1> findByExperimentId(long experimentId);
}
