package com.huawei.cloud.sre.rca.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.cloud.sre.rca.dto.IncidentSummary;
import com.huawei.cloud.sre.rca.dto.RcaReport;
import com.huawei.cloud.sre.rca.repository.RcaIncidentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RcaInferenceServiceTest {

    @Mock
    private ChatClient.Builder chatClientBuilder;

    @Mock
    private ChatClient chatClient;

    @Mock
    private VectorStore vectorStore;

    @Mock
    private RcaIncidentRepository incidentRepository;

    private RcaInferenceService service;
    private ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        when(chatClientBuilder.build()).thenReturn(chatClient);
        service = new RcaInferenceService(chatClientBuilder, vectorStore, incidentRepository, objectMapper);
    }

    @Test
    void analyze_returnsFallbackReport_whenLlmThrows() {
        // Stub: prompt chain throws
        ChatClient.ChatClientRequestSpec spec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.ChatClientRequestSpec userSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec callSpec = mock(ChatClient.CallResponseSpec.class);

        when(chatClient.prompt()).thenReturn(spec);
        when(spec.system(any(String.class))).thenReturn(spec);
        when(spec.user(any(String.class))).thenReturn(userSpec);
        when(userSpec.call()).thenReturn(callSpec);
        when(callSpec.content()).thenThrow(new RuntimeException("LLM unavailable"));
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());

        RcaReport report = service.analyze("INC-001", "Service down", List.of("CPU spike"));

        assertThat(report.incidentId()).isEqualTo("INC-001");
        assertThat(report.confidence()).isEqualTo(0.0);
        assertThat(report.rootCauseDescription()).contains("LLM inference failed");
        assertThat(report.immediateActions()).contains("Escalate to on-call engineer");
    }

    @Test
    void analyze_parsesLlmResponse_whenValid() throws Exception {
        String llmJson = """
                {
                  "rootCause": "Database connection pool exhausted",
                  "rootCauseComponent": "order-service",
                  "rootCauseDescription": "The connection pool was exhausted due to slow queries",
                  "contributingFactors": ["slow queries", "traffic spike"],
                  "evidenceList": ["db connections=100/100", "p99 latency=5000ms"],
                  "impactScope": "checkout flow affected",
                  "severity": "HIGH",
                  "immediateActions": ["increase pool size", "kill slow queries"],
                  "preventiveMeasures": ["add query timeout", "add connection monitoring"],
                  "confidence": 0.85
                }
                """;

        ChatClient.ChatClientRequestSpec spec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.ChatClientRequestSpec userSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec callSpec = mock(ChatClient.CallResponseSpec.class);

        when(chatClient.prompt()).thenReturn(spec);
        when(spec.system(any(String.class))).thenReturn(spec);
        when(spec.user(any(String.class))).thenReturn(userSpec);
        when(userSpec.call()).thenReturn(callSpec);
        when(callSpec.content()).thenReturn(llmJson);
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());

        RcaReport report = service.analyze("INC-002", "Order service failing", List.of("db error"));

        assertThat(report.rootCause()).isEqualTo("Database connection pool exhausted");
        assertThat(report.rootCauseComponent()).isEqualTo("order-service");
        assertThat(report.severity()).isEqualTo("HIGH");
        assertThat(report.confidence()).isEqualTo(0.85);
        assertThat(report.immediateActions()).hasSize(2);
        assertThat(report.isHighConfidence()).isTrue();
        assertThat(report.isCritical()).isTrue();
    }

    @Test
    void retrieveSimilarIncidents_returnsEmptyList_whenVectorStoreThrows() {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenThrow(new RuntimeException("vector store down"));

        List<IncidentSummary> result = service.retrieveSimilarIncidents("some incident", 3);

        assertThat(result).isEmpty();
    }

    @Test
    void retrieveSimilarIncidents_mapsDocumentMetadata() {
        Document doc = new Document.Builder()
                .text("incident content")
                .metadata("incidentId", "INC-100")
                .metadata("title", "DB timeout")
                .metadata("rootCause", "slow query")
                .metadata("service", "order-service")
                .metadata("severity", "HIGH")
                .metadata("occurredAt", Instant.now().toString())
                .metadata("resolution", "killed slow query")
                .metadata("durationMinutes", "45")
                .score(0.92)
                .build();
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(doc));

        List<IncidentSummary> result = service.retrieveSimilarIncidents("order service failing", 5);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).incidentId()).isEqualTo("INC-100");
        assertThat(result.get(0).similarityScore()).isEqualTo(0.92);
        assertThat(result.get(0).durationMinutes()).isEqualTo(45L);
    }

    @Test
    void indexIncidentForFutureRag_callsVectorStoreAdd() {
        RcaReport report = new RcaReport(
                "INC-999", "DB issue", "db-service", "Connection pool exhausted",
                List.of(), List.of(), "checkout", "HIGH",
                List.of("restart db"), List.of("add monitoring"),
                0.8, Instant.now()
        );

        service.indexIncidentForFutureRag("INC-999", "order service failing", report);

        verify(vectorStore).add(anyList());
    }

    @Test
    void rcaReport_isHighConfidence_falseWhenBelow08() {
        RcaReport report = new RcaReport(
                "id", "cause", "comp", "desc", List.of(), List.of(),
                "scope", "LOW", List.of(), List.of(), 0.79, Instant.now()
        );
        assertThat(report.isHighConfidence()).isFalse();
    }

    @Test
    void rcaReport_isCritical_trueForCriticalSeverity() {
        RcaReport report = new RcaReport(
                "id", "cause", "comp", "desc", List.of(), List.of(),
                "scope", "CRITICAL", List.of(), List.of(), 0.5, Instant.now()
        );
        assertThat(report.isCritical()).isTrue();
    }
}
