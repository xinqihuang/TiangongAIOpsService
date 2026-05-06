package com.huawei.cloud.sre.remediation.statemachine;

/**
 * 修复工单状态枚举。
 *
 * <p>合法转换路径：
 * <pre>
 * INITIATED → STRATEGY_MATCHED → RISK_ASSESSED → PENDING_APPROVAL
 *                                               ↘ EXECUTING (低风险直接执行)
 * PENDING_APPROVAL → APPROVED → EXECUTING
 * PENDING_APPROVAL → REJECTED → CANCELLED
 * EXECUTING → VERIFYING → COMPLETED
 * EXECUTING → FAILED → ROLLING_BACK → ROLLED_BACK
 * EXECUTING → FAILED → FAILED (终态)
 * 任意 → CANCELLED (人工取消)
 * </pre>
 */
public enum RemediationState {

    /** 工单已创建，尚未匹配策略。 */
    INITIATED,

    /** 已匹配到 SOP 策略，等待风险评估。 */
    STRATEGY_MATCHED,

    /** 风险评估完成，等待路由（低风险→直接执行，中/高风险→待审批）。 */
    RISK_ASSESSED,

    /** 等待人工审批（中风险：单人；高风险：双人）。 */
    PENDING_APPROVAL,

    /** 审批通过，等待执行。 */
    APPROVED,

    /** 修复操作执行中。 */
    EXECUTING,

    /** 执行完成，验证中。 */
    VERIFYING,

    /** 修复完成并验证通过（终态）。 */
    COMPLETED,

    /** 执行失败，等待回滚决策。 */
    FAILED,

    /** 回滚中。 */
    ROLLING_BACK,

    /** 回滚完成（终态）。 */
    ROLLED_BACK,

    /** 审批拒绝（终态）。 */
    REJECTED,

    /** 人工取消（终态）。 */
    CANCELLED;

    /** 是否为终态（不能再转换）。 */
    public boolean isTerminal() {
        return this == COMPLETED || this == ROLLED_BACK
                || this == REJECTED || this == CANCELLED;
    }

    /** 是否为活跃状态（需要处理）。 */
    public boolean isActive() {
        return !isTerminal() && this != FAILED;
    }
}
