package io.github.ismoyuan.opspilot.application.system;

import io.github.ismoyuan.opspilot.domain.system.ResourceBinding;
import java.util.List;

/** 资源数据绑定读取端口；Provider 解析据此找到资源在各数据源中的定位（06 §17）。 */
public interface ResourceBindingRepository {

    /** 按 dataSourceConnectionId 升序；不按连接状态过滤，连接是否 ACTIVE 由调用方判定。 */
    List<ResourceBinding> findAllByResourceId(long managedResourceId);
}
