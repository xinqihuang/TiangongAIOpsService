package com.huawei.cloud.sre.remediation.dto;

import java.util.List;

/**
 * SOP 策略匹配结果。
 *
 * @param matched          是否找到匹配策略
 * @param strategyId       策略 ID
 * @param strategyName     策略名称
 * @param description      策略描述
 * @param riskLevel        风险级别
 * @param toolName         对应 Tool 名称
 * @param executionSteps   执行步骤
 * @param estimatedMinutes 预计耗时（分钟）
 * @param alternatives     备选策略名称列表
 */
public record StrategyMatch(
        boolean matched,
        String strategyId,
        String strategyName,
        String description,
        String riskLevel,
        String toolName,
        String executionSteps,
        int estimatedMinutes,
        List<String> alternatives
) {
    /** 构造未匹配结果。 */
    public static StrategyMatch noMatch() {
        return new StrategyMatch(false, null, null,
                "No matching SOP strategy found", "UNKNOWN",
                null, null, 0, List.of());
    }
}
