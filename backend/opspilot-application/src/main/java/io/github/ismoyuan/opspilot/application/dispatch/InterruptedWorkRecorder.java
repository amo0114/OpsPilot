package io.github.ismoyuan.opspilot.application.dispatch;

import java.time.Instant;

/**
 * 启动恢复的第一步（07 §51～§55）：把旧 Java 进程退出时仍为 RUNNING 的运行记录如实标为中断，再派发工作。
 * 单实例部署下，本进程启动之前开始的 RUNNING 记录不可能属于本进程的 Worker；本进程自己的记录不受影响。
 */
public interface InterruptedWorkRecorder {

    /**
     * @param startedBefore 本进程启动时刻；只处理此前开始的 RUNNING 记录
     * @return 标记的记录数；重复调用只影响仍为 RUNNING 的记录
     */
    int recordInterrupted(Instant startedBefore);
}
