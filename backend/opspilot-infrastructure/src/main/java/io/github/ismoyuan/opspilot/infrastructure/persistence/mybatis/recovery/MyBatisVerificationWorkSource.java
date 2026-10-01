package io.github.ismoyuan.opspilot.infrastructure.persistence.mybatis.recovery;

import io.github.ismoyuan.opspilot.application.dispatch.DispatchableWork;
import io.github.ismoyuan.opspilot.application.dispatch.DispatchableWorkSource;
import java.util.List;
import org.springframework.stereotype.Repository;

/**
 * 需要 Worker 的 RecoveryVerification（07 §51 表，08 TASK-083），供启动恢复与周期补派发唤醒：PENDING（创建已提交但派发丢失、失败或
 * 线程池拒绝）与 RUNNING（进程退出或等待被中断）。Runner 只按持久化快照、原 deadline 与样本槽位继续，不刷新时间、不重试同一槽位；
 * 正由本 JVM Worker 处理的同一 Verification 被派发器单飞合并。终态不派发。只读，不改变任何状态。
 */
@Repository
class MyBatisVerificationWorkSource implements DispatchableWorkSource {

    private final RecoveryVerificationMapper mapper;

    MyBatisVerificationWorkSource(RecoveryVerificationMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public List<DispatchableWork.RecoveryVerification> findDispatchable() {
        return mapper.selectDispatchable();
    }
}
