package io.github.ismoyuan.opspilot.infrastructure.persistence.mybatis.recovery;

import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** RecoveryVerification 读取与带期望状态、版本的条件推进（04 §50、§80）；终态没有再改写的语句。 */
@Mapper
interface RecoveryVerificationMapper {

    VerificationRow selectById(@Param("id") long id);

    int markRunning(
            @Param("id") long id, @Param("expectedVersion") long expectedVersion, @Param("at") LocalDateTime at);

    int markFinished(
            @Param("id") long id,
            @Param("from") String from,
            @Param("expectedVersion") long expectedVersion,
            @Param("status") String status,
            @Param("summary") String summary,
            @Param("payload") String payload,
            @Param("at") LocalDateTime at);

    record VerificationRow(
            long id,
            long incidentId,
            int verificationNo,
            String status,
            String policySnapshot,
            LocalDateTime deadlineAt,
            LocalDateTime startedAt,
            long lockVersion) {}
}
