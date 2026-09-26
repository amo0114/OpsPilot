package io.github.ismoyuan.opspilot.application.system.query;

import java.util.List;
import java.util.Optional;

/**
 * 系统接入页面的只读 SQL 投影（07 §24），不加载领域对象图，不修改任何状态。
 * systemKey/resourceKey 来自外部输入，按字节精确匹配。
 */
public interface SystemQueryRepository {

    long countSystems();

    /** 按 systemKey 升序。 */
    List<SystemSummaryView> findSystems(int offset, int limit);

    Optional<SystemDetailView> findSystemDetail(String systemKey);

    boolean systemExists(String systemKey);

    Optional<ResourceCapabilityProjection> findResource(String systemKey, String resourceKey);
}
