package io.github.ismoyuan.opspilot.infrastructure.persistence.mybatis.diagnosis;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 只有插入与查询；diagnosis、diagnosis_evidence_ref 不存在 UPDATE/DELETE 语句（04 §32、§36）。 */
@Mapper
interface DiagnosisMapper {

    /** 版本号 = 该 Investigation 现有最大 version_no + 1。 */
    int insertNextVersion(@Param("d") DiagnosisInsert diagnosis);

    int insertEvidenceRefs(
            @Param("diagnosisId") long diagnosisId,
            @Param("evidenceIds") Collection<Long> evidenceIds,
            @Param("createdAt") LocalDateTime createdAt);

    DiagnosisRow selectById(@Param("id") long id);

    List<Long> selectEvidenceIds(@Param("diagnosisId") long diagnosisId);
}
