package com.huawei.cloud.sre.rca.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.cloud.sre.rca.dto.IncidentSummary;
import com.huawei.cloud.sre.rca.dto.RcaReport;
import com.huawei.cloud.sre.rca.repository.RcaIncidentEntity;
import com.huawei.cloud.sre.rca.repository.RcaIncidentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * RCA 推理服务。
 *
 * <p>封装 RAG（检索增强生成）流程：
 * <ol>
 *   <li>从向量存储检索历史相似事故</li>
 *   <li>构建包含上下文的 Prompt</li>
 *   <li>调用 vLLM（OpenAI 兼容 API）生成根因分析</li>
 *   <li>将结果结构化为 {@link RcaReport}</li>
 * </ol>
 */
@Service
public class RcaInferenceService {

    private static final Logger log = LoggerFactory.getLogger(RcaInferenceService.class);

    private static final String SYSTEM_PROMPT = """
            You are an expert Site Reliability Engineer (SRE) specializing in root cause analysis.
            Your task is to analyze production incidents and identify root causes with high precision.

            Guidelines:
            - Base your analysis strictly on the provided evidence
            - Assign confidence scores honestly (0.0 = no evidence, 1.0 = definitive proof)
            - Identify cascading failures vs. root causes
            - Suggest actionable remediation steps
            - Output valid JSON matching the specified schema
            """;

    private static final String RCA_PROMPT_TEMPLATE = """
            Analyze the following production incident and provide a structured root cause analysis.

            ## Incident Context
            %s

            ## Collected Evidence
            %s

            ## Similar Historical Incidents (for reference)
            %s

            ## Required Output Format (JSON)
            {
              "rootCause": "<one-line summary>",
              "rootCauseComponent": "<service/component name>",
              "rootCauseDescription": "<detailed explanation>",
              "contributingFactors": ["<factor1>", "<factor2>"],
              "evidenceList": ["<key evidence 1>", "<key evidence 2>"],
              "impactScope": "<affected services and users>",
              "severity": "<CRITICAL|HIGH|MEDIUM|LOW>",
              "immediateActions": ["<action1>", "<action2>"],
              "preventiveMeasures": ["<measure1>", "<measure2>"],
              "confidence": <0.0-1.0>
            }

            Respond with ONLY the JSON object, no additional text.
            """;

    private final ChatClient chatClient;
    private final VectorStore vectorStore;
    private final RcaIncidentRepository incidentRepository;
    private final ObjectMapper objectMapper;

    /**
     * @param chatClientBuilder  Spring AI ChatClient builder（自动注入 vLLM 模型）
     * @param vectorStore        向量存储（历史事故嵌入）
     * @param incidentRepository RCA 事故 JPA 仓库
     * @param objectMapper       JSON 序列化工具
     */
    public RcaInferenceService(
            ChatClient.Builder chatClientBuilder,
            VectorStore vectorStore,
            RcaIncidentRepository incidentRepository,
            ObjectMapper objectMapper
    ) {
        this.chatClient = chatClientBuilder.build();
        this.vectorStore = vectorStore;
        this.incidentRepository = incidentRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * 执行根因分析推理。
     *
     * @param incidentId      事故 ID（用于报告关联）
     * @param incidentContext 事故上下文描述（现象、时间、受影响服务等）
     * @param evidence        收集到的证据列表（日志摘要、指标异常、Trace 信息）
     * @return 结构化 RCA 报告
     */
    public RcaReport analyze(String incidentId, String incidentContext, List<String> evidence) {
        log.info("RcaInferenceService.analyze incidentId={}", incidentId);
        long start = System.currentTimeMillis();

        List<IncidentSummary> similarIncidents = retrieveSimilarIncidents(incidentContext, 3);
        String evidenceText = evidence != null ? String.join("\n- ", evidence) : "No evidence provided";
        String similarText = formatSimilarIncidents(similarIncidents);
        String prompt = RCA_PROMPT_TEMPLATE.formatted(incidentContext, "- " + evidenceText, similarText);

        try {
            String responseJson = chatClient.prompt()
                    .system(SYSTEM_PROMPT)
                    .user(prompt)
                    .call()
                    .content();

            RcaReport report = parseRcaReport(incidentId, responseJson);
            persistReport(report, incidentContext);
            indexIncidentForFutureRag(incidentId, incidentContext, report);

            long elapsed = System.currentTimeMillis() - start;
            log.info("RcaInferenceService.analyze done incidentId={} confidence={} elapsed={}ms",
                    incidentId, report.confidence(), elapsed);
            return report;

        } catch (Exception e) {
            log.error("RcaInferenceService.analyze failed incidentId={}", incidentId, e);
            return fallbackReport(incidentId, incidentContext, e.getMessage());
        }
    }

    /**
     * 从向量存储检索与当前事故相似的历史事故（RAG 检索阶段）。
     *
     * @param incidentContext 当前事故上下文
     * @param topK            最多返回条数
     * @return 相似事故摘要列表
     */
    public List<IncidentSummary> retrieveSimilarIncidents(String incidentContext, int topK) {
        log.info("RcaInferenceService.retrieveSimilarIncidents topK={}", topK);
        try {
            List<Document> docs = vectorStore.similaritySearch(
                    SearchRequest.builder()
                            .query(incidentContext)
                            .topK(topK)
                            .similarityThreshold(0.6)
                            .build()
            );

            List<IncidentSummary> summaries = new ArrayList<>();
            for (Document doc : docs) {
                Map<String, Object> meta = doc.getMetadata();
                summaries.add(new IncidentSummary(
                        String.valueOf(meta.getOrDefault("incidentId", "unknown")),
                        String.valueOf(meta.getOrDefault("title", "Unknown incident")),
                        String.valueOf(meta.getOrDefault("rootCause", "")),
                        String.valueOf(meta.getOrDefault("service", "")),
                        String.valueOf(meta.getOrDefault("severity", "UNKNOWN")),
                        doc.getScore() != null ? doc.getScore() : 0.0,
                        Instant.parse(String.valueOf(meta.getOrDefault("occurredAt", Instant.now().toString()))),
                        String.valueOf(meta.getOrDefault("resolution", "")),
                        Long.parseLong(String.valueOf(meta.getOrDefault("durationMinutes", "0")))
                ));
            }
            return summaries;
        } catch (Exception e) {
            log.warn("Failed to retrieve similar incidents from vector store: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 将事故数据索引到向量存储，供未来 RAG 使用。
     *
     * @param incidentId 事故 ID
     * @param context    事故上下文
     * @param report     分析报告
     */
    public void indexIncidentForFutureRag(String incidentId, String context, RcaReport report) {
        try {
            String content = context + "\nRoot cause: " + report.rootCause()
                    + "\nComponent: " + report.rootCauseComponent();
            Document doc = new Document.Builder()
                    .text(content)
                    .metadata("incidentId", incidentId)
                    .metadata("title", context.substring(0, Math.min(context.length(), 100)))
                    .metadata("rootCause", report.rootCause())
                    .metadata("service", report.rootCauseComponent())
                    .metadata("severity", report.severity())
                    .metadata("occurredAt", report.analyzedAt().toString())
                    .metadata("resolution", String.join("; ", report.immediateActions()))
                    .metadata("durationMinutes", "0")
                    .build();
            vectorStore.add(List.of(doc));
            log.info("Indexed incident incidentId={} to vector store", incidentId);
        } catch (Exception e) {
            log.warn("Failed to index incident to vector store incidentId={}: {}", incidentId, e.getMessage());
        }
    }

    private RcaReport parseRcaReport(String incidentId, String json) throws Exception {
        String cleaned = json.strip();
        if (cleaned.startsWith("```json")) {
            cleaned = cleaned.substring(7);
        }
        if (cleaned.startsWith("```")) {
            cleaned = cleaned.substring(3);
        }
        if (cleaned.endsWith("```")) {
            cleaned = cleaned.substring(0, cleaned.length() - 3);
        }
        cleaned = cleaned.strip();

        @SuppressWarnings("unchecked")
        Map<String, Object> map = objectMapper.readValue(cleaned, Map.class);

        @SuppressWarnings("unchecked")
        List<String> contributing = (List<String>) map.getOrDefault("contributingFactors", List.of());
        @SuppressWarnings("unchecked")
        List<String> evidenceList = (List<String>) map.getOrDefault("evidenceList", List.of());
        @SuppressWarnings("unchecked")
        List<String> immediate = (List<String>) map.getOrDefault("immediateActions", List.of());
        @SuppressWarnings("unchecked")
        List<String> preventive = (List<String>) map.getOrDefault("preventiveMeasures", List.of());
        double confidence = ((Number) map.getOrDefault("confidence", 0.5)).doubleValue();

        return new RcaReport(
                incidentId,
                String.valueOf(map.getOrDefault("rootCause", "Unknown")),
                String.valueOf(map.getOrDefault("rootCauseComponent", "Unknown")),
                String.valueOf(map.getOrDefault("rootCauseDescription", "")),
                contributing,
                evidenceList,
                String.valueOf(map.getOrDefault("impactScope", "")),
                String.valueOf(map.getOrDefault("severity", "MEDIUM")),
                immediate,
                preventive,
                confidence,
                Instant.now()
        );
    }

    private void persistReport(RcaReport report, String context) {
        try {
            String json = objectMapper.writeValueAsString(report);
            var entity = new RcaIncidentEntity(
                    report.rootCauseComponent(),
                    context.substring(0, Math.min(context.length(), 512)),
                    report.rootCause(),
                    report.severity(),
                    report.rootCauseComponent(),
                    report.confidence(),
                    json
            );
            incidentRepository.save(entity);
        } catch (Exception e) {
            log.warn("Failed to persist RCA report: {}", e.getMessage());
        }
    }

    private String formatSimilarIncidents(List<IncidentSummary> incidents) {
        if (incidents.isEmpty()) {
            return "No similar historical incidents found.";
        }
        StringBuilder sb = new StringBuilder();
        for (IncidentSummary s : incidents) {
            sb.append(String.format("- [%.0f%% similar] %s (Service: %s, Root cause: %s, Resolution: %s)%n",
                    s.similarityScore() * 100, s.title(), s.service(), s.rootCause(), s.resolution()));
        }
        return sb.toString();
    }

    private RcaReport fallbackReport(String incidentId, String context, String errorMsg) {
        return new RcaReport(
                incidentId,
                "Analysis failed — manual review required",
                "Unknown",
                "LLM inference failed: " + errorMsg,
                List.of(),
                List.of(),
                context.substring(0, Math.min(context.length(), 200)),
                "HIGH",
                List.of("Escalate to on-call engineer"),
                List.of("Investigate LLM connectivity"),
                0.0,
                Instant.now()
        );
    }

    /**
     * 关联告警：根据告警描述列表，用 LLM 生成告警分组和共同原因分析。
     *
     * @param alertDescriptions 告警描述列表
     * @return LLM 输出的分析文本（JSON 格式）
     */
    public String correlateAlerts(List<String> alertDescriptions) {
        String prompt = """
                You are analyzing multiple alerts from a production system.
                Group the following alerts by their likely common root cause,
                and identify the most critical issue.

                Alerts:
                %s

                Output JSON: {
                  "groups": [
                    {
                      "groupId": "<id>",
                      "alertIndices": [<indices>],
                      "commonCause": "<cause>",
                      "affectedService": "<service>",
                      "severity": "<CRITICAL|HIGH|MEDIUM|LOW>"
                    }
                  ],
                  "analysis": "<overall summary>"
                }
                """.formatted(formatAlertList(alertDescriptions));

        try {
            return chatClient.prompt()
                    .system(SYSTEM_PROMPT)
                    .user(prompt)
                    .call()
                    .content();
        } catch (Exception e) {
            log.error("correlateAlerts LLM call failed", e);
            return "{\"groups\": [], \"analysis\": \"Correlation failed: " + e.getMessage() + "\"}";
        }
    }

    private String formatAlertList(List<String> alerts) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < alerts.size(); i++) {
            sb.append(i).append(". ").append(alerts.get(i)).append("\n");
        }
        return sb.toString();
    }
}
