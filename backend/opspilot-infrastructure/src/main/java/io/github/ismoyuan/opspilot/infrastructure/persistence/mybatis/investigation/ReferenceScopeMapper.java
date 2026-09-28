package io.github.ismoyuan.opspilot.infrastructure.persistence.mybatis.investigation;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** AI 可引用范围的只读查询（08 TASK-040）；只有 SELECT。 */
@Mapper
interface ReferenceScopeMapper {

    int countObservationInScope(
            @Param("investigationId") long investigationId,
            @Param("runNo") int runNo,
            @Param("observationId") long observationId);

    List<Long> selectEvidenceInScope(
            @Param("investigationId") long investigationId,
            @Param("runStartedAt") LocalDateTime runStartedAt,
            @Param("evidenceIds") Collection<Long> evidenceIds);
}
