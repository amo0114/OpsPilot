package io.github.ismoyuan.opspilot.application.dispatch;

import java.util.List;

/**
 * 从数据库读出当前需要 Worker 的工作（07 §51 表）：调查为 INVESTIGATING 的 Incident 及其 current_run_no；
 * Execution 为 PENDING/RUNNING（TASK-073）；Verification 为 PENDING/RUNNING（TASK-083）。只读，不改变任何状态。
 */
public interface DispatchableWorkSource {

    List<? extends DispatchableWork> findDispatchable();
}
