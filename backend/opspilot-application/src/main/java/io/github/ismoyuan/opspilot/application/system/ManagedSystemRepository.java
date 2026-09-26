package io.github.ismoyuan.opspilot.application.system;

import io.github.ismoyuan.opspilot.domain.system.ManagedSystem;
import java.util.Optional;

/** 业务系统读取端口；V0.1 系统配置由 Seed 建立，不提供写入（05 §14）。 */
public interface ManagedSystemRepository {

    Optional<ManagedSystem> findBySystemKey(String systemKey);
}
