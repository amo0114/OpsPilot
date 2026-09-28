package io.github.ismoyuan.opspilot.infrastructure.dispatch;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 后台 Worker 线程池（07 §47、§88）：并发与排队都有上限，不用无界线程池。
 *
 * @param queueCapacity 线程都忙时可排队的唤醒数，超出即拒绝并记录，由补派发重新唤醒（本批取值）
 */
@ConfigurationProperties("opspilot.worker")
public record WorkerProperties(
        @DefaultValue("8") int maxConcurrency,
        @DefaultValue("16") int queueCapacity) {

    public WorkerProperties {
        if (maxConcurrency < 1 || queueCapacity < 0) {
            throw new IllegalArgumentException("worker maxConcurrency must be >= 1 and queueCapacity >= 0");
        }
    }
}
