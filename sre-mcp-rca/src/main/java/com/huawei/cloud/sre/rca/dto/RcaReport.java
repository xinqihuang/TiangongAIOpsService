package com.huawei.cloud.sre.rca.dto;

import java.time.Instant;
import java.util.List;

/**
 * 根因分析报告。
 *
 * @param incidentId           事故 ID
 * @param rootCause            根因摘要（一句话）
 * @param rootCauseComponent   根因组件/服务名
 * @param rootCauseDescription 根因详细描述
 * @param contributingFactors  关联影响因素列表
 * @param evidenceList         支撑根因的证据列表（日志/指标/Trace 摘要）
 * @param impactScope          影响范围描述
 * @param severity             严重等级：CRITICAL / HIGH / MEDIUM / LOW
 * @param immediateActions     建议的即时处置动作
 * @param preventiveMeasures   建议的预防措施
 * @param confidence           根因置信度 0.0–1.0
 * @param analyzedAt           分析完成时间
 */
public record RcaReport(
        String incidentId,
        String rootCause,
        String rootCauseComponent,
        String rootCauseDescription,
        List<String> contributingFactors,
        List<String> evidenceList,
        String impactScope,
        String severity,
        List<String> immediateActions,
        List<String> preventiveMeasures,
        double confidence,
        Instant analyzedAt
) {

    /** 高置信度阈值（≥ 0.8）。 */
    public boolean isHighConfidence() {
        return confidence >= 0.8;
    }

    /** 是否严重等级为 CRITICAL 或 HIGH。 */
    public boolean isCritical() {
        return "CRITICAL".equalsIgnoreCase(severity) || "HIGH".equalsIgnoreCase(severity);
    }
}
