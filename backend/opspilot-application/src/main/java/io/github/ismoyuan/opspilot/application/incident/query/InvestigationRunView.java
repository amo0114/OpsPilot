package io.github.ismoyuan.opspilot.application.incident.query;

/** 当前调查轮次与停止意图（05 §27 的 runNo / stopRequested）。 */
public record InvestigationRunView(int runNo, boolean stopRequested) {}
