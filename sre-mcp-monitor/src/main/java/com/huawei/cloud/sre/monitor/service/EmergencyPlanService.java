package com.huawei.cloud.sre.monitor.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.cloud.sre.monitor.dto.AlertHandlingResult;
import com.huawei.cloud.sre.monitor.dto.EmergencyPlan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 应急预案服务：RAG 检索 → LLM 自主决策兜底。
 *
 * <p>处理流程：
 * <ol>
 *   <li>将告警描述向量化，在 {@link VectorStore} 中执行相似度检索</li>
 *   <li>若最高相似度 ≥ {@code ragThreshold}，直接返回命中的应急预案（{@code RAG_PLAN}）</li>
 *   <li>若未命中，调用 vLLM 进行自主决策（{@code LLM_DECISION}）</li>
 *   <li>LLM 调用失败时返回兜底步骤（{@code FALLBACK}），保证系统始终有响应</li>
 * </ol>
 */
@Service
public class EmergencyPlanService {

    private static final Logger log = LoggerFactory.getLogger(EmergencyPlanService.class);

    private static final String SYSTEM_PROMPT = """
            You are an expert Site Reliability Engineer (SRE).
            Analyze production alerts and provide precise, actionable emergency response decisions.
            Output ONLY valid JSON — no markdown fences, no extra text.
            """;

    private static final String DECISION_TEMPLATE = """
            An alert has fired and no matching emergency plan exists in the knowledge base.
            Provide an autonomous response decision.

            ## Alert
            - Description: %s
            - Service: %s
            - Severity: %s
            - Timestamp: %s

            ## Output Format (JSON only)
            {
              "planTitle": "LLM Decision: <one-line title>",
              "responseSteps": [
                "<step 1 — specific, actionable>",
                "<step 2>",
                "<step 3>"
              ],
              "reasoning": "<why these steps address the alert>",
              "rootCauseHypothesis": "<most likely root cause>",
              "escalationRequired": <true|false>,
              "severity": "<CRITICAL|HIGH|MEDIUM|LOW>"
            }
            """;

    private final VectorStore vectorStore;
    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;

    /**
     * @param vectorStore       应急预案向量存储
     * @param chatClientBuilder vLLM ChatClient 构造器（来自 common 模块）
     * @param objectMapper      JSON 序列化工具
     */
    public EmergencyPlanService(
            VectorStore vectorStore,
            ChatClient.Builder chatClientBuilder,
            ObjectMapper objectMapper
    ) {
        this.vectorStore = vectorStore;
        this.chatClient = chatClientBuilder.build();
        this.objectMapper = objectMapper;
    }

    /**
     * 处理告警：先检索 RAG 知识库，命中则返回预案，否则由 LLM 自主决策。
     *
     * @param alertDescription 告警描述（含告警名称、资源、消息等）
     * @param service          受影响的服务名
     * @param severity         告警级别：CRITICAL / HIGH / MEDIUM / LOW
     * @param ragThreshold     RAG 命中阈值（0.0-1.0），低于此值触发 LLM 决策，推荐 0.65
     * @return 告警处置决策结果
     */
    public AlertHandlingResult handle(
            String alertDescription, String service, String severity, double ragThreshold
    ) {
        double threshold = Math.max(0.0, Math.min(1.0, ragThreshold));
        log.info("EmergencyPlanService.handle service={} severity={} threshold={}", service, severity, threshold);

        // 1. RAG search
        try {
            List<Document> hits = vectorStore.similaritySearch(
                    SearchRequest.builder()
                            .query(alertDescription)
                            .topK(3)
                            .similarityThreshold(threshold)
                            .build()
            );
            if (!hits.isEmpty()) {
                Document best = hits.get(0);
                double score = best.getScore() != null ? best.getScore() : 0.0;
                log.info("EmergencyPlanService: RAG hit score={} planId={}", score,
                        best.getMetadata().get("planId"));
                return buildRagResult(service, severity, best);
            }
            log.info("EmergencyPlanService: RAG miss (threshold={}), falling back to LLM", threshold);
        } catch (Exception e) {
            log.warn("EmergencyPlanService: RAG search failed, falling back to LLM: {}", e.getMessage());
        }

        // 2. LLM autonomous decision
        return decideWithLlm(alertDescription, service, severity);
    }

    /**
     * 将应急预案索引到向量知识库，供后续 RAG 检索命中。
     *
     * @param plan 要写入的应急预案
     * @throws RuntimeException 若向量写入失败
     */
    public void indexPlan(EmergencyPlan plan) {
        log.info("EmergencyPlanService.indexPlan planId={} title={}", plan.planId(), plan.title());
        try {
            String stepsJson = objectMapper.writeValueAsString(plan.responseSteps());
            // embed: title + alertPattern + rootCauseHint for best semantic match
            String embeddableText = plan.title() + "\n"
                    + "Alert pattern: " + plan.alertPattern() + "\n"
                    + "Service: " + plan.service() + "\n"
                    + "Root cause: " + plan.rootCauseHint();

            Document doc = new Document.Builder()
                    .text(embeddableText)
                    .metadata("planId", plan.planId())
                    .metadata("title", plan.title())
                    .metadata("alertPattern", plan.alertPattern())
                    .metadata("service", plan.service())
                    .metadata("severity", plan.severity())
                    .metadata("responseSteps", stepsJson)
                    .metadata("rootCauseHint", plan.rootCauseHint())
                    .metadata("notes", plan.notes() != null ? plan.notes() : "")
                    .metadata("createdAt", plan.createdAt().toString())
                    .build();

            vectorStore.add(List.of(doc));
            log.info("EmergencyPlanService.indexPlan done planId={}", plan.planId());
        } catch (Exception e) {
            log.error("EmergencyPlanService.indexPlan failed planId={}: {}", plan.planId(), e.getMessage());
            throw new RuntimeException("Failed to index emergency plan: " + e.getMessage(), e);
        }
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private AlertHandlingResult buildRagResult(String service, String severity, Document doc) {
        Map<String, Object> meta = doc.getMetadata();
        String stepsJson = String.valueOf(meta.getOrDefault("responseSteps", "[]"));
        List<String> steps;
        try {
            steps = objectMapper.readValue(stepsJson, new TypeReference<>() {});
        } catch (Exception e) {
            steps = List.of(stepsJson);
        }
        double score = doc.getScore() != null ? doc.getScore() : 0.0;
        String planSeverity = String.valueOf(meta.getOrDefault("severity", severity));

        return new AlertHandlingResult(
                "RAG_PLAN",
                String.valueOf(meta.getOrDefault("title", "Matched Emergency Plan")),
                score,
                steps,
                "Matched existing plan from knowledge base. Alert pattern: "
                        + meta.getOrDefault("alertPattern", "—"),
                String.valueOf(meta.getOrDefault("rootCauseHint", "See plan details")),
                "CRITICAL".equals(planSeverity) || "HIGH".equals(planSeverity),
                planSeverity,
                String.valueOf(meta.getOrDefault("service", service)),
                Instant.now()
        );
    }

    private AlertHandlingResult decideWithLlm(String alertDescription, String service, String severity) {
        log.info("EmergencyPlanService.decideWithLlm service={} severity={}", service, severity);
        String prompt = DECISION_TEMPLATE.formatted(alertDescription, service, severity, Instant.now());
        try {
            String json = chatClient.prompt()
                    .system(SYSTEM_PROMPT)
                    .user(prompt)
                    .call()
                    .content();
            return parseLlmDecision(json, service, severity);
        } catch (Exception e) {
            log.error("EmergencyPlanService: LLM call failed: {}", e.getMessage());
            return fallback(service, severity, e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private AlertHandlingResult parseLlmDecision(String json, String service, String severity) throws Exception {
        String cleaned = json.strip();
        if (cleaned.startsWith("```json")) cleaned = cleaned.substring(7);
        if (cleaned.startsWith("```")) cleaned = cleaned.substring(3);
        if (cleaned.endsWith("```")) cleaned = cleaned.substring(0, cleaned.length() - 3);
        cleaned = cleaned.strip();

        Map<String, Object> map = objectMapper.readValue(cleaned, Map.class);
        List<String> steps = (List<String>) map.getOrDefault("responseSteps", List.of("通知值班工程师"));
        boolean escalate = Boolean.parseBoolean(String.valueOf(map.getOrDefault("escalationRequired", "false")));

        return new AlertHandlingResult(
                "LLM_DECISION",
                String.valueOf(map.getOrDefault("planTitle", "LLM Generated Response")),
                0.0,
                steps,
                String.valueOf(map.getOrDefault("reasoning", "")),
                String.valueOf(map.getOrDefault("rootCauseHypothesis", "")),
                escalate,
                String.valueOf(map.getOrDefault("severity", severity)),
                service,
                Instant.now()
        );
    }

    private AlertHandlingResult fallback(String service, String severity, String errorMsg) {
        return new AlertHandlingResult(
                "FALLBACK",
                "Emergency Response (LLM unavailable)",
                0.0,
                List.of(
                        "立即通知值班工程师",
                        "检查 " + service + " 服务状态（Pod / 进程 / 连接）",
                        "查看最近 30 分钟告警与日志",
                        "若 5 分钟内无法定位原因，启动战时流程"
                ),
                "LLM inference failed: " + errorMsg + ". Using default escalation steps.",
                "Unknown — manual investigation required",
                true,
                severity,
                service,
                Instant.now()
        );
    }
}
