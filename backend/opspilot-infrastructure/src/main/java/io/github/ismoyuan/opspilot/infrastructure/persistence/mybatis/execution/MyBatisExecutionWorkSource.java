package io.github.ismoyuan.opspilot.infrastructure.persistence.mybatis.execution;

import io.github.ismoyuan.opspilot.application.dispatch.DispatchableWork;
import io.github.ismoyuan.opspilot.application.dispatch.DispatchableWorkSource;
import java.util.List;
import org.springframework.stereotype.Repository;

/**
 * 需要 Worker 的 ActionExecution（07 §51 表，08 TASK-069、TASK-073），供启动恢复与周期补派发唤醒：
 * <ul>
 *   <li>PENDING：批准已提交但派发丢失、失败或线程池拒绝。重派发安全——只有 PENDING → RUNNING 条件更新成功的 Worker 才能发出一次
 *       CHANGE（04 §82）。
 *   <li>RUNNING：CHANGE 可能已在远端发生（进程在 restart 途中退出、结果落账失败或核对中断）。Worker 对它只恢复剩余的有界只读核对，
 *       绝不重发写操作；次数、上限与截止时间都取自数据库，不刷新。正由本 JVM Worker 处理的同一 Execution 被派发器单飞合并。
 * </ul>
 * 终态不派发。只读，不改变任何状态；旧进程遗留的 RUNNING 不标记中断——其结果未知，只能由核对判定（04 §82）。
 */
@Repository
class MyBatisExecutionWorkSource implements DispatchableWorkSource {

    private final ActionExecutionMapper mapper;

    MyBatisExecutionWorkSource(ActionExecutionMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public List<DispatchableWork.ActionExecution> findDispatchable() {
        return mapper.selectDispatchable();
    }
}
