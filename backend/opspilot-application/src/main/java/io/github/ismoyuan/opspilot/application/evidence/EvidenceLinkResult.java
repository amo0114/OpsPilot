package io.github.ismoyuan.opspilot.application.evidence;

import io.github.ismoyuan.opspilot.domain.evidence.Evidence;
import io.github.ismoyuan.opspilot.domain.hypothesis.Hypothesis;

/** 新建的 Evidence 及同事务结束时关联 Hypothesis 的当前状态。 */
public record EvidenceLinkResult(Evidence evidence, Hypothesis hypothesis) {}
