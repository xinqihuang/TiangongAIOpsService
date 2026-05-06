package com.huawei.cloud.sre.remediation.dto;

import com.huawei.cloud.sre.remediation.statemachine.RemediationState;

import java.time.Instant;
import java.util.List;

/**
 * 修复操作执行结果。
 *
 * @param contextId       工单 ID
 * @param action          执行的操作名称
 * @param state           操作后工单状态
 * @param success         是否成功
 * @param message         结果描述
 * @param riskLevel       风险级别
 * @param requiresApproval 是否需要人工审批
 * @param approvers       需要的审批人列表
 * @param executedAt      执行时间
 * @param idempotent      是否为重复（幂等）请求
 */
public record RemediationResult(
        String contextId,
        String action,
        RemediationState state,
        boolean success,
        String message,
        String riskLevel,
        boolean requiresApproval,
        List<String> approvers,
        Instant executedAt,
        boolean idempotent
) {

    /** 构造成功结果。 */
    public static RemediationResult success(String contextId, String action,
                                            RemediationState state, String riskLevel) {
        return new RemediationResult(contextId, action, state, true,
                "Action '%s' completed successfully".formatted(action),
                riskLevel, false, List.of(), Instant.now(), false);
    }

    /** 构造需要审批的结果。 */
    public static RemediationResult pendingApproval(String contextId, String action,
                                                    String riskLevel, List<String> approvers) {
        return new RemediationResult(contextId, action, RemediationState.PENDING_APPROVAL,
                true, "Action '%s' requires approval (%s risk)".formatted(action, riskLevel),
                riskLevel, true, approvers, Instant.now(), false);
    }

    /** 构造失败结果。 */
    public static RemediationResult failed(String contextId, String action,
                                           RemediationState state, String error) {
        return new RemediationResult(contextId, action, state, false, error,
                "UNKNOWN", false, List.of(), Instant.now(), false);
    }

    /** 构造幂等命中结果（重复请求）。 */
    public static RemediationResult idempotentHit(String contextId, String action,
                                                  RemediationState state, String riskLevel) {
        return new RemediationResult(contextId, action, state, true,
                "Idempotent: previous result returned for action '%s'".formatted(action),
                riskLevel, false, List.of(), Instant.now(), true);
    }
}
