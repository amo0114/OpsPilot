package io.github.ismoyuan.opspilot.application.correlation;

import java.util.UUID;
import org.slf4j.MDC;

/**
 * 日志关联标识（07 §98）。HTTP 请求沿用 requestId；后台 Worker 没有请求时用 {@link #newId()} 自行生成。
 */
public final class Correlation {

    public static final String MDC_KEY = "correlationId";

    private Correlation() {}

    public static String newId() {
        return "corr_" + UUID.randomUUID().toString().replace("-", "");
    }

    /** 在当前线程设置 correlationId，关闭时恢复先前值。 */
    public static Scope open(String correlationId) {
        String previous = MDC.get(MDC_KEY);
        MDC.put(MDC_KEY, correlationId);
        return () -> {
            if (previous == null) {
                MDC.remove(MDC_KEY);
            } else {
                MDC.put(MDC_KEY, previous);
            }
        };
    }

    @FunctionalInterface
    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }
}
