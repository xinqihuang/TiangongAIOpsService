package com.huawei.cloud.sre.monitor.dto;

import java.time.Instant;

/**
 * 告警规则数据传输对象。
 *
 * @param ruleId       规则 ID
 * @param name         规则名称
 * @param service      监控服务名称
 * @param metric       监控指标名称
 * @param condition    触发条件，如 "> 90"
 * @param threshold    阈值
 * @param severity     严重级别：CRITICAL/HIGH/MEDIUM/LOW
 * @param enabled      是否启用
 * @param topicUrn     通知主题 URN（SMN）
 * @param createdAt    创建时间
 * @param updatedAt    最后更新时间
 */
public record AlertRuleDto(
        String ruleId,
        String name,
        String service,
        String metric,
        String condition,
        double threshold,
        String severity,
        boolean enabled,
        String topicUrn,
        Instant createdAt,
        Instant updatedAt
) {
}
