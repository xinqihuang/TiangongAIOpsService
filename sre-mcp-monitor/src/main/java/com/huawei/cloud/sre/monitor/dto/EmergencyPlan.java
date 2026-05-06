package com.huawei.cloud.sre.monitor.dto;

import java.time.Instant;
import java.util.List;

/**
 * 应急预案，存储于 RAG 向量知识库。
 *
 * <p>通过 {@code indexEmergencyPlan} MCP Tool 写入，通过 {@code handleAlert} 检索命中。
 *
 * @param planId        预案唯一 ID
 * @param title         预案标题，如"order-service OOM 应急预案"
 * @param alertPattern  该预案对应的告警描述模式，用于向量相似度匹配
 * @param service       适用服务名称（"*" 表示通用预案）
 * @param severity      预案对应的告警级别：CRITICAL / HIGH / MEDIUM / LOW
 * @param responseSteps 有序处置步骤列表
 * @param rootCauseHint 已知根因提示
 * @param notes         备注（维护人、版本等）
 * @param createdAt     入库时间
 */
public record EmergencyPlan(
        String planId,
        String title,
        String alertPattern,
        String service,
        String severity,
        List<String> responseSteps,
        String rootCauseHint,
        String notes,
        Instant createdAt
) {}
