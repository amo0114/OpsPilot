package io.github.ismoyuan.opspilot.application.faultlab;

import java.util.List;
import java.util.Objects;

/**
 * 代码维护的故障场景定义（04 §62、05 §69）。Incident 模板只描述用户可见的症状，不含原因、场景键或“注入”字样：它会进入调查上下文
 * （09 §21）。groundTruth 只写入 fault_experiment，不经场景列表返回。
 *
 * @param targetResourceKey 注入作用的资源（05 §69 公开）
 * @param incidentTitle 症状标题
 * @param incidentImpactSummary 症状影响
 * @param affectedResourceKeys 用户可见症状所在的资源
 */
public record FaultScenario(
        String scenarioKey,
        String name,
        String description,
        String targetResourceKey,
        String incidentTitle,
        String incidentImpactSummary,
        List<String> affectedResourceKeys,
        FaultGroundTruthV1 groundTruth) {

    public FaultScenario {
        Objects.requireNonNull(scenarioKey, "scenarioKey");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(targetResourceKey, "targetResourceKey");
        Objects.requireNonNull(incidentTitle, "incidentTitle");
        Objects.requireNonNull(incidentImpactSummary, "incidentImpactSummary");
        affectedResourceKeys = List.copyOf(affectedResourceKeys);
        Objects.requireNonNull(groundTruth, "groundTruth");
    }
}
