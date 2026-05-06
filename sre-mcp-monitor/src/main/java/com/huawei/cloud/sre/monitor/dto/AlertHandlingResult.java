package com.huawei.cloud.sre.monitor.dto;

import java.time.Instant;
import java.util.List;

/**
 * 告警处置决策结果。
 *
 * <p>{@code source} 字段说明决策来源：
 * <ul>
 *   <li>{@code RAG_PLAN} — 命中向量知识库中的存量应急预案</li>
 *   <li>{@code LLM_DECISION} — 知识库未命中，由大模型自主决策</li>
 *   <li>{@code FALLBACK} — 大模型调用失败，返回兜底处置建议</li>
 * </ul>
 *
 * @param source               决策来源：RAG_PLAN / LLM_DECISION / FALLBACK
 * @param planTitle            预案标题（RAG）或 LLM 生成的响应标题
 * @param similarityScore      RAG 相似度分数（0.0-1.0），LLM 决策时为 0.0
 * @param responseSteps        有序处置步骤
 * @param reasoning            决策依据/分析说明
 * @param rootCauseHypothesis  根因假设
 * @param escalationRequired   是否需要上报升级
 * @param severity             告警严重级别
 * @param service              受影响服务
 * @param decidedAt            决策时间
 */
public record AlertHandlingResult(
        String source,
        String planTitle,
        double similarityScore,
        List<String> responseSteps,
        String reasoning,
        String rootCauseHypothesis,
        boolean escalationRequired,
        String severity,
        String service,
        Instant decidedAt
) {}
