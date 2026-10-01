package io.github.ismoyuan.opspilot.application.faultlab;

/** Ground Truth 中的实验真实原因（09 §20）。只属于 Fault Lab 与 Evaluation，绝不进入调查（ACC-INV-003）。 */
public enum FaultCause {
    REDIS_NETWORK_LATENCY,
    MYSQL_SLOW_QUERY_POOL_EXHAUSTION,
    STATISTICS_CONSUMER_STOPPED
}
