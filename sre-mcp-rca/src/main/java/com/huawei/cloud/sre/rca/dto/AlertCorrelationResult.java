package com.huawei.cloud.sre.rca.dto;

import java.time.Instant;
import java.util.List;

/**
 * 告警关联分析结果。
 *
 * @param alertIds        输入的告警 ID 列表
 * @param correlationGroups 关联分组结果；同一根因通常会产生多条告警
 * @param totalAlerts     输入告警总数
 * @param correlatedCount 成功关联（归组）的告警数
 * @param analysisNote    整体关联分析摘要
 */
public record AlertCorrelationResult(
        List<String> alertIds,
        List<CorrelationGroup> correlationGroups,
        int totalAlerts,
        int correlatedCount,
        String analysisNote
) {

    /**
     * 单个关联分组。
     *
     * @param groupId         分组 ID
     * @param memberAlertIds  组内告警 ID 列表
     * @param commonCause     推测的共同原因摘要
     * @param affectedService 受影响的服务
     * @param severity        分组严重等级
     * @param firstOccurrence 最早发生时间
     * @param lastOccurrence  最晚发生时间
     */
    public record CorrelationGroup(
            String groupId,
            List<String> memberAlertIds,
            String commonCause,
            String affectedService,
            String severity,
            Instant firstOccurrence,
            Instant lastOccurrence
    ) {}
}
