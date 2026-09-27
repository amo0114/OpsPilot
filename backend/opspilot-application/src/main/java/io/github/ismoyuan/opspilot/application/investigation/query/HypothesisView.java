package io.github.ismoyuan.opspilot.application.investigation.query;

import io.github.ismoyuan.opspilot.domain.hypothesis.HypothesisStatus;

/** 05 §51。description 可为空。 */
public record HypothesisView(long id, String title, String description, HypothesisStatus status) {}
