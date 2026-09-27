package io.github.ismoyuan.opspilot.application.ai.protocol.v1;

/** cache.inspect 没有 AI 可控参数（06 §22），线上仍须显式 {}。 */
public record CacheInspectArgumentsV1() implements CapabilityArguments {}
