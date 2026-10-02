package io.github.ismoyuan.opspilot.infrastructure.persistence.mybatis.faultlab;

import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * fault_experiment 的生命周期语句（04 §63）。除 {@link #selectGroundTruth} 外没有语句读取 ground_truth_payload；该语句只由
 * {@link MyBatisGroundTruthQuery}（Evaluation 端口）调用（04 §64）。写入只有插入与确认生效时的补充（{@link #markActive}）。
 */
@Mapper
interface FaultExperimentMapper {

    Long lockSystem(@Param("systemId") long systemId);

    int countInProgress(@Param("systemId") long systemId, @Param("exceptId") Long exceptId);

    int insertInjecting(@Param("key") GeneratedKey key, @Param("e") ExperimentInsert experiment);

    Long selectSystemId(@Param("id") long id);

    ExperimentRow selectForUpdate(@Param("id") long id);

    int markActive(
            @Param("id") long id,
            @Param("incidentId") long incidentId,
            @Param("injectedAt") LocalDateTime injectedAt,
            @Param("groundTruthPayload") String groundTruthPayload,
            @Param("now") LocalDateTime now);

    int markFailed(
            @Param("id") long id,
            @Param("from") String from,
            @Param("message") String message,
            @Param("now") LocalDateTime now);

    int markResetting(@Param("id") long id, @Param("from") String from, @Param("now") LocalDateTime now);

    int markReset(@Param("id") long id, @Param("resetAt") LocalDateTime resetAt);

    int markInterrupted(
            @Param("startedBefore") LocalDateTime startedBefore,
            @Param("message") String message,
            @Param("now") LocalDateTime now);

    GroundTruthRow selectGroundTruth(@Param("id") long id);

    record ExperimentInsert(
            String scenarioKey,
            long managedSystemId,
            long targetResourceId,
            String groundTruthSchemaName,
            int groundTruthSchemaVersion,
            String groundTruthPayload,
            LocalDateTime createdAt) {}

    /** 时间为 UTC；不含 Ground Truth。 */
    record ExperimentRow(
            long id,
            String scenarioKey,
            long managedSystemId,
            String systemKey,
            String systemEnvironment,
            long targetResourceId,
            String targetResourceKey,
            Long incidentId,
            String status,
            LocalDateTime injectedAt,
            LocalDateTime resetAt) {}

    record GroundTruthRow(String schemaName, int schemaVersion, String payload) {}

    /** MyBatis 回填自增主键的载体。 */
    final class GeneratedKey {

        private Long id;

        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }
    }
}
