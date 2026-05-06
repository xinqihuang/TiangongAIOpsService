package com.huawei.cloud.sre.remediation.statemachine;

import java.time.Instant;

/**
 * 状态变更事件，由 {@link RemediationStateMachine} 在每次成功转换后发布。
 *
 * @param contextId   工单 ID
 * @param from        转换前状态
 * @param to          转换后状态
 * @param triggeredBy 触发者（用户/系统）
 * @param occurredAt  转换时间
 */
public record StateChangedEvent(
        String contextId,
        RemediationState from,
        RemediationState to,
        String triggeredBy,
        Instant occurredAt
) {
}
