package io.github.ismoyuan.opspilot.application.recovery;

import java.time.Instant;
import java.util.List;

/**
 * RecoveryPolicy 持久化端口（04 §48～§49）。激活只插入新版本并退休旧 ACTIVE，不改写已存在版本的内容（01 §28）；
 * 调用方必须先在同一事务内锁定资源父行，再查询与替换 ACTIVE。
 */
public interface RecoveryPolicyRepository {

    /**
     * 以 SELECT … FOR UPDATE 锁定稳定存在的 managed_resource 行（04 §49、03 §50），覆盖没有旧策略时的并发首次激活。
     *
     * @return 资源不存在时为 false
     */
    boolean lockResource(long managedResourceId);

    /** 该资源当前的 ACTIVE 策略，按 id 升序；正常配置下至多一条，调用方不得在多条时猜测选择。 */
    List<RecoveryPolicyRecord> findActive(long managedResourceId);

    /** @return 该资源该 policyKey 已有的最大版本号，没有时为 0 */
    int maxVersion(long managedResourceId, String policyKey);

    /**
     * 把该资源全部 ACTIVE 策略置为 RETIRED（跨 policy_key，04 §49）。
     *
     * @return 退休的行数
     */
    int retireActive(long managedResourceId, Instant retiredAt);

    /** @return 新策略 id */
    long insertActive(NewRecoveryPolicy policy);

    /** 新插入的 ACTIVE 版本；criteriaPayload 已按 Schema 编码。 */
    record NewRecoveryPolicy(
            long managedResourceId,
            String policyKey,
            String name,
            int versionNo,
            String criteriaSchemaName,
            int criteriaSchemaVersion,
            String criteriaPayload,
            Instant activatedAt) {}
}
