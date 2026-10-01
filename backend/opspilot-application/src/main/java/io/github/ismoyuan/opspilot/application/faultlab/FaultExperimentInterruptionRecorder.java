package io.github.ismoyuan.opspilot.application.faultlab;

import io.github.ismoyuan.opspilot.application.dispatch.InterruptedWorkRecorder;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Fault Lab 的启动中断记录（07 §51～§55）：旧进程退出时仍停在 INJECTING 或 RESETTING 的实验，外部注入/恢复动作是否完成未知，记为 FAILED，
 * 使同一系统不会被永远占用，并可通过 Reset 恢复实验环境。不创建 Incident，不重试注入。
 */
@Service
public class FaultExperimentInterruptionRecorder implements InterruptedWorkRecorder {

    private static final Logger log = LoggerFactory.getLogger(FaultExperimentInterruptionRecorder.class);

    private final FaultLabApplicationService faultLab;

    public FaultExperimentInterruptionRecorder(FaultLabApplicationService faultLab) {
        this.faultLab = faultLab;
    }

    @Override
    public int recordInterrupted(Instant startedBefore) {
        int count = faultLab.recordInterrupted(startedBefore);
        if (count > 0) {
            log.info("Interrupted fault experiments recorded: experiments={}", count);
        }
        return count;
    }
}
