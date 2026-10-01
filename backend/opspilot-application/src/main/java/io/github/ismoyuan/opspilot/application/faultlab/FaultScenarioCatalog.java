package io.github.ismoyuan.opspilot.application.faultlab;

import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * V0.1 的三个固定场景（08 TASK-090、09 §28～§63）：不为三个场景建立场景/动作/模板表（04 §62）。目标资源与症状资源取自 ShortLink Demo
 * 的资源键（redirect-service 即 project-api 容器）；场景只适用于含这些资源的 DEMO/TEST 系统。S3 把 statistics-consumer 登记为受影响
 * 资源：统计功能本身受影响，处理建议的候选资源依赖它（RemediationActions）。
 */
@Component
public class FaultScenarioCatalog {

    /** S1 默认注入延迟（09 §31）。 */
    public static final int REDIS_LATENCY_MS = 600;

    private static final List<FaultScenario> SCENARIOS = List.of(
            new FaultScenario(
                    "redis-latency",
                    "Redis 响应延迟",
                    "人为增加 Redis 请求延迟，用于验证缓存异常场景。",
                    "shortlink-redis",
                    "短链接跳转明显变慢",
                    "短链接跳转速度明显下降",
                    List.of("redirect-service"),
                    FaultGroundTruthV1.of(FaultCause.REDIS_NETWORK_LATENCY, REDIS_LATENCY_MS)),
            new FaultScenario(
                    "mysql-slow-query",
                    "MySQL 慢查询",
                    "在数据库侧制造慢查询并占用应用连接池，用于验证数据库异常场景。",
                    "shortlink-mysql",
                    "短链接业务接口响应变慢",
                    "创建短链接等业务接口响应明显变慢",
                    List.of("redirect-service"),
                    FaultGroundTruthV1.of(FaultCause.MYSQL_SLOW_QUERY_POOL_EXHAUSTION, null)),
            new FaultScenario(
                    "statistics-consumer-stop",
                    "统计消费者停止",
                    "停止独立的访问统计消费者，用于验证消息积压与恢复验证场景。",
                    "statistics-consumer",
                    "访问统计数据长时间未更新",
                    "短链接访问统计延迟，统计数据不再增长",
                    List.of("statistics-consumer"),
                    FaultGroundTruthV1.of(FaultCause.STATISTICS_CONSUMER_STOPPED, null)));

    /** 按固定顺序（05 §69）。 */
    public List<FaultScenario> all() {
        return SCENARIOS;
    }

    /** 按字节精确匹配。 */
    public Optional<FaultScenario> find(String scenarioKey) {
        return SCENARIOS.stream()
                .filter(scenario -> scenario.scenarioKey().equals(scenarioKey))
                .findFirst();
    }
}
