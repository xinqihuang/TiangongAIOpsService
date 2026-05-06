package com.huawei.cloud.sre.remediation.dto;

import java.util.List;

/**
 * 风险评估结果。
 *
 * @param action           操作名称
 * @param riskLevel        风险级别：LOW / MEDIUM / HIGH
 * @param riskScore        风险评分（0-100）
 * @param riskFactors      风险因素列表
 * @param mitigations      风险缓解措施
 * @param requiresApproval 是否需要审批
 * @param approvalType     审批类型：NONE / SINGLE / DUAL
 * @param estimatedImpact  预计影响描述
 * @param rollbackPossible 是否可回滚
 */
public record RiskAssessment(
        String action,
        String riskLevel,
        int riskScore,
        List<String> riskFactors,
        List<String> mitigations,
        boolean requiresApproval,
        String approvalType,
        String estimatedImpact,
        boolean rollbackPossible
) {
    /** 是否为高风险（需要工单 + 双人审批）。 */
    public boolean isHighRisk() { return "HIGH".equalsIgnoreCase(riskLevel); }
}
