package io.github.ismoyuan.opspilot.application.dispatch;

import java.util.List;

/**
 * 从数据库读出当前需要 Worker 的工作（07 §51 表）：调查为 INVESTIGATING 的 Incident 及其 current_run_no；
 * Execution（PENDING/RUNNING）与 Verification（PENDING/RUNNING）的来源由 TASK-073、TASK-083 实现。只读，不改变任何状态。
 */
public interface DispatchableWorkSource {

    List<? extends DispatchableWork> findDispatchable();
}
