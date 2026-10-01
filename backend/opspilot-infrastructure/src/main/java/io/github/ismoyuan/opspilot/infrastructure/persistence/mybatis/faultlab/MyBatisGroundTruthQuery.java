package io.github.ismoyuan.opspilot.infrastructure.persistence.mybatis.faultlab;

import io.github.ismoyuan.opspilot.application.faultlab.FaultGroundTruthV1;
import io.github.ismoyuan.opspilot.application.faultlab.evaluation.GroundTruthQuery;
import io.github.ismoyuan.opspilot.application.schema.SchemaCodecRegistry;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/** Ground Truth 的唯一读取实现（04 §64）；只服务 Evaluation。 */
@Repository
class MyBatisGroundTruthQuery implements GroundTruthQuery {

    private final FaultExperimentMapper mapper;
    private final SchemaCodecRegistry codecs;

    MyBatisGroundTruthQuery(FaultExperimentMapper mapper, SchemaCodecRegistry codecs) {
        this.mapper = mapper;
        this.codecs = codecs;
    }

    @Override
    public Optional<FaultGroundTruthV1> findByExperimentId(long experimentId) {
        return Optional.ofNullable(mapper.selectGroundTruth(experimentId))
                .map(row ->
                        codecs.decode(row.schemaName(), row.schemaVersion(), row.payload(), FaultGroundTruthV1.class));
    }
}
