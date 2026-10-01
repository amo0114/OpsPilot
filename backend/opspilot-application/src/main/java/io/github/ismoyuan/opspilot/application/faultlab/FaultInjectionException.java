package io.github.ismoyuan.opspilot.application.faultlab;

/**
 * 注入、确认或重置没有成功。消息会写入 fault_experiment.error_message 并可能出现在日志中，因此只能是固定、脱敏的说明（07 §99），
 * 不含凭据、端点或控制命令。
 */
public class FaultInjectionException extends RuntimeException {

    public FaultInjectionException(String safeMessage) {
        super(safeMessage);
    }
}
