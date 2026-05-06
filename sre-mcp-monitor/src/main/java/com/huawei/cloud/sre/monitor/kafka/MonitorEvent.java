package com.huawei.cloud.sre.monitor.kafka;

import java.time.Instant;
import java.util.Map;

/**
 * 监控事件，用于 Kafka 事件总线传输。
 *
 * @param eventId   唯一事件 ID
 * @param eventType 事件类型：ALERT_TRIGGERED / BASELINE_UPDATED / ANOMALY_DETECTED / ALERT_RESOLVED
 * @param service   相关服务名
 * @param metric    相关指标名
 * @param severity  严重级别
 * @param message   事件描述
 * @param payload   附加数据（可序列化为 JSON）
 * @param timestamp 事件发生时间
 */
public record MonitorEvent(
        String eventId,
        String eventType,
        String service,
        String metric,
        String severity,
        String message,
        Map<String, Object> payload,
        Instant timestamp
) {
}
