package com.huawei.cloud.sre.monitor.dto;

import java.time.Instant;
import java.util.List;

/**
 * 告警聚合分组结果。
 *
 * @param groupId          分组 ID
 * @param service          关联服务名称
 * @param commonCause      推断的共同根因
 * @param severity         组内最高严重级别
 * @param alertIds         归入本组的告警 ID 列表
 * @param alertCount       告警数量
 * @param firstOccurrence  最早告警时间
 * @param lastOccurrence   最新告警时间
 * @param aggregationType  聚合类型：TIME/TOPOLOGY/SEMANTIC
 * @param suppressed       是否已被静默规则压制
 */
public record AlertGroup(
        String groupId,
        String service,
        String commonCause,
        String severity,
        List<String> alertIds,
        int alertCount,
        Instant firstOccurrence,
        Instant lastOccurrence,
        String aggregationType,
        boolean suppressed
) {

    /** 是否为风暴（同一分组内告警数超过 10）。 */
    public boolean isAlertStorm() {
        return alertCount > 10;
    }
}
