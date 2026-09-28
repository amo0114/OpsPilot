package io.github.ismoyuan.opspilot.infrastructure.persistence.mybatis.invocation;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
interface InvocationInterruptionMapper {

    /** 不加锁读取；随后按 id 条件更新，只锁这些行。 */
    List<Long> selectRunningInvestigationCallsStartedBefore(@Param("startedBefore") LocalDateTime startedBefore);

    int markInterrupted(
            @Param("ids") Collection<Long> ids,
            @Param("errorCode") String errorCode,
            @Param("errorMessage") String errorMessage,
            @Param("finishedAt") LocalDateTime finishedAt);
}
