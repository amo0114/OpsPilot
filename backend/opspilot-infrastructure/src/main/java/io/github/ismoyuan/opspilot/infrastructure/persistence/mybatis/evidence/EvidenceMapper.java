package io.github.ismoyuan.opspilot.infrastructure.persistence.mybatis.evidence;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 只有插入与查询；evidence 不存在 UPDATE/DELETE 语句（DB-INV-004）。 */
@Mapper
interface EvidenceMapper {

    int insert(@Param("e") EvidenceInsert evidence);

    EvidenceRow selectById(@Param("id") long id);

    EvidenceRow selectByObservationAndHypothesisForShare(
            @Param("observationId") long observationId, @Param("hypothesisId") long hypothesisId);
}
