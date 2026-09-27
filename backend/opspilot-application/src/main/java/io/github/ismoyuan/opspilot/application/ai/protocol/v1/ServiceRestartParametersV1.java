package io.github.ismoyuan.opspilot.application.ai.protocol.v1;

/**
 * service.restart 不接受任何 AI 参数（06 §105）：容器、主机、命令、信号由 ManagedResource → ResourceBinding → Docker Provider
 * 解析。线上必须是显式 {}。
 */
public record ServiceRestartParametersV1() {}
