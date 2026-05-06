package com.huawei.cloud.sre.monitor.dto;

import java.time.Instant;

/**
 * 内存高水位告警，由定时内存巡检任务生成。
 *
 * @param instanceId    华为云实例 ID
 * @param componentType 组件类型：ECS / RDS / DCS / CCE
 * @param namespace     CES 命名空间，如 SYS.ECS
 * @param memoryPercent 当前内存使用率（%）
 * @param threshold     触发阈值（%），通常为 90.0
 * @param detectedAt    检测时间
 * @param handling      RAG/LLM 处置决策（若因去重跳过则为 null）
 */
public record MemoryAlert(
        String instanceId,
        String componentType,
        String namespace,
        double memoryPercent,
        double threshold,
        Instant detectedAt,
        AlertHandlingResult handling
) {}
