package com.huawei.cloud.sre.rca.dto;

import java.time.Instant;

/**
 * 历史相似事故摘要（用于 RAG 上下文增强）。
 *
 * @param incidentId      历史事故 ID
 * @param title           事故标题
 * @param rootCause       根因描述
 * @param service         受影响的主要服务
 * @param severity        严重等级
 * @param similarityScore 与当前事故的语义相似度 0.0–1.0
 * @param occurredAt      历史事故发生时间
 * @param resolution      历史事故的解决方案摘要
 * @param durationMinutes 历史事故持续时长（分钟）
 */
public record IncidentSummary(
        String incidentId,
        String title,
        String rootCause,
        String service,
        String severity,
        double similarityScore,
        Instant occurredAt,
        String resolution,
        long durationMinutes
) {}
