package io.github.ismoyuan.opspilot.infrastructure.persistence.mybatis.recovery;

import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 恢复策略：资源父行锁、ACTIVE 查询、退休与插入新版本（04 §48～§49）；没有改写已存在版本内容的语句。 */
@Mapper
interface RecoveryPolicyMapper {

    Long lockResource(@Param("resourceId") long resourceId);

    List<PolicyRow> selectActive(@Param("resourceId") long resourceId);

    int selectMaxVersion(@Param("resourceId") long resourceId, @Param("policyKey") String policyKey);

    int retireActive(@Param("resourceId") long resourceId, @Param("at") LocalDateTime at);

    int insertActive(@Param("key") GeneratedKey key, @Param("policy") PolicyInsert policy);

    record PolicyRow(
            long id,
            long managedResourceId,
            String policyKey,
            String name,
            int versionNo,
            String criteriaSchemaName,
            int criteriaSchemaVersion,
            String criteriaPayload,
            LocalDateTime activatedAt) {}

    record PolicyInsert(
            long managedResourceId,
            String policyKey,
            String name,
            int versionNo,
            String criteriaSchemaName,
            int criteriaSchemaVersion,
            String criteriaPayload,
            LocalDateTime at) {}

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
