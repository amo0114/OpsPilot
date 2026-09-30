package io.github.ismoyuan.opspilot.infrastructure.persistence.mybatis.execution;

import io.github.ismoyuan.opspilot.application.dispatch.DispatchableWork;
import io.github.ismoyuan.opspilot.application.dispatch.DispatchableWorkSource;
import java.util.List;
import org.springframework.stereotype.Repository;

/**
 * PENDING 的 ActionExecution（07 §51 表）：批准已提交但派发丢失或失败时由周期补派发与启动恢复重新唤醒（08 TASK-069）。重派发是安全的：
 * 只有 PENDING → RUNNING 条件更新成功的 Worker 才能发出一次 CHANGE（04 §82）。RUNNING 不在此列——其结果可能已在远端发生，由
 * TASK-073 的启动恢复按有界只读核对处理，绝不重新派发写操作。
 */
@Repository
class MyBatisPendingExecutionWorkSource implements DispatchableWorkSource {

    private final ActionExecutionMapper mapper;

    MyBatisPendingExecutionWorkSource(ActionExecutionMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public List<DispatchableWork.ActionExecution> findDispatchable() {
        return mapper.selectPending();
    }
}
