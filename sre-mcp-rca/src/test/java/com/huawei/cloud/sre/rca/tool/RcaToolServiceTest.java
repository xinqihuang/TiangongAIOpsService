package com.huawei.cloud.sre.rca.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.cloud.sre.common.adapter.AomAdapter;
import com.huawei.cloud.sre.common.adapter.ApmAdapter;
import com.huawei.cloud.sre.common.adapter.CtsAdapter;
import com.huawei.cloud.sre.common.adapter.LtsAdapter;
import com.huawei.cloud.sre.common.dto.LogSearchResult;
import com.huawei.cloud.sre.common.dto.MetricResult;
import com.huawei.cloud.sre.common.dto.TraceResult;
import com.huawei.cloud.sre.rca.dto.IncidentSummary;
import com.huawei.cloud.sre.rca.dto.RcaReport;
import com.huawei.cloud.sre.rca.dto.TopologyResult;
import com.huawei.cloud.sre.rca.repository.RcaIncidentRepository;
import com.huawei.cloud.sre.rca.service.KnowledgeGraphService;
import com.huawei.cloud.sre.rca.service.RcaInferenceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RcaToolServiceTest {

    @Mock private AomAdapter aomAdapter;
    @Mock private LtsAdapter ltsAdapter;
    @Mock private ApmAdapter apmAdapter;
    @Mock private CtsAdapter ctsAdapter;
    @Mock private KnowledgeGraphService kgService;
    @Mock private RcaInferenceService inferenceService;
    @Mock private RcaIncidentRepository incidentRepository;

    private RcaToolService toolService;

    @BeforeEach
    void setUp() {
        toolService = new RcaToolService(
                aomAdapter, ltsAdapter, apmAdapter, ctsAdapter,
                kgService, inferenceService, incidentRepository,
                new ObjectMapper()
        );
    }

    @Test
    void queryMetrics_delegatesToAomAdapter() {
        MetricResult expected = new MetricResult(
                "user-service", "cpu_usage", Instant.now(), Instant.now(), "percent", List.of());
        when(aomAdapter.queryMetric(anyString(), anyString(), any(), any(), anyInt()))
                .thenReturn(expected);

        MetricResult result = toolService.queryMetrics(
                "user-service", "cpu_usage",
                "2025-01-01T00:00:00Z", "2025-01-01T01:00:00Z", 60);

        assertThat(result.service()).isEqualTo("user-service");
        verify(aomAdapter).queryMetric(anyString(), anyString(), any(), any(), anyInt());
    }

    @Test
    void queryMetrics_usesDefaultPeriod_whenZeroProvided() {
        MetricResult expected = new MetricResult(
                "svc", "mem", Instant.now(), Instant.now(), "bytes", List.of());
        when(aomAdapter.queryMetric(anyString(), anyString(), any(), any(), anyInt()))
                .thenReturn(expected);

        toolService.queryMetrics("svc", "mem", "2025-01-01T00:00:00Z", "2025-01-01T01:00:00Z", 0);

        verify(aomAdapter).queryMetric(anyString(), anyString(), any(), any(), org.mockito.Mockito.eq(60));
    }

    @Test
    void searchLogs_delegatesToLtsAdapter() {
        LogSearchResult expected = new LogSearchResult("svc", "ERROR", 5L, List.of());
        when(ltsAdapter.searchLogs(anyString(), anyString(), anyInt(), anyInt())).thenReturn(expected);

        LogSearchResult result = toolService.searchLogs("svc", "ERROR", 30, 100);

        assertThat(result.keyword()).isEqualTo("ERROR");
        verify(ltsAdapter).searchLogs("svc", "ERROR", 30, 100);
    }

    @Test
    void searchLogs_capsMaxResults_atThousand() {
        LogSearchResult expected = new LogSearchResult("svc", "kw", 0L, List.of());
        when(ltsAdapter.searchLogs(anyString(), anyString(), anyInt(), anyInt())).thenReturn(expected);

        toolService.searchLogs("svc", "kw", 30, 5000);

        verify(ltsAdapter).searchLogs(anyString(), anyString(), anyInt(), org.mockito.Mockito.eq(1000));
    }

    @Test
    void analyzeTraces_delegatesToApmAdapter() {
        TraceResult expected = new TraceResult("trace-1", "svc", Instant.now(), 100L, 200, List.of(), List.of());
        when(apmAdapter.analyzeTrace("trace-1", 0L)).thenReturn(expected);

        TraceResult result = toolService.analyzeTraces("trace-1");

        assertThat(result.traceId()).isEqualTo("trace-1");
        verify(apmAdapter).analyzeTrace("trace-1", 0L);
    }

    @Test
    void queryTopology_delegatesToKgService() {
        TopologyResult expected = new TopologyResult("svc", 2, List.of(), List.of());
        when(kgService.queryTopology("svc", 2)).thenReturn(expected);

        TopologyResult result = toolService.queryTopology("svc", 2);

        assertThat(result.rootService()).isEqualTo("svc");
    }

    @Test
    void queryTopology_usesDefaultDepth_whenZeroProvided() {
        TopologyResult expected = new TopologyResult("svc", 2, List.of(), List.of());
        when(kgService.queryTopology("svc", 2)).thenReturn(expected);

        toolService.queryTopology("svc", 0);

        verify(kgService).queryTopology("svc", 2);
    }

    @Test
    void findSimilarIncidents_delegatesToInferenceService() {
        List<IncidentSummary> expected = List.of(new IncidentSummary(
                "INC-1", "DB timeout", "db issue", "order-svc", "HIGH",
                0.9, Instant.now(), "fixed", 30L));
        when(inferenceService.retrieveSimilarIncidents(anyString(), anyInt())).thenReturn(expected);

        List<IncidentSummary> result = toolService.findSimilarIncidents("service down", 5);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).incidentId()).isEqualTo("INC-1");
    }

    @Test
    void findSimilarIncidents_clampTopK_atTwenty() {
        when(inferenceService.retrieveSimilarIncidents(anyString(), anyInt())).thenReturn(List.of());

        toolService.findSimilarIncidents("desc", 100);

        verify(inferenceService).retrieveSimilarIncidents(anyString(), org.mockito.Mockito.eq(20));
    }

    @Test
    void analyzeRootCause_generatesIncidentId_whenAuto() {
        RcaReport report = new RcaReport("INC-AUTO", "cause", "comp", "desc",
                List.of(), List.of(), "scope", "HIGH", List.of(), List.of(), 0.9, Instant.now());
        when(inferenceService.analyze(anyString(), anyString(), any())).thenReturn(report);

        RcaReport result = toolService.analyzeRootCause("auto", "context", "evidence1,evidence2");

        assertThat(result).isNotNull();
        verify(inferenceService).analyze(anyString(), org.mockito.Mockito.eq("context"), any());
    }

    @Test
    void analyzeRootCause_usesProvidedIncidentId() {
        RcaReport report = new RcaReport("INC-001", "cause", "comp", "desc",
                List.of(), List.of(), "scope", "MEDIUM", List.of(), List.of(), 0.7, Instant.now());
        when(inferenceService.analyze(anyString(), anyString(), any())).thenReturn(report);

        toolService.analyzeRootCause("INC-001", "context", "");

        verify(inferenceService).analyze(org.mockito.Mockito.eq("INC-001"), anyString(), any());
    }

    @Test
    void queryChanges_returnsMapWithChangesKey() {
        when(ctsAdapter.queryTraces(any(), anyString(), any(), any(), anyInt())).thenReturn(List.of());

        Object result = toolService.queryChanges("svc", "2025-01-01T00:00:00Z", "2025-01-01T01:00:00Z");

        assertThat(result).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) result;
        assertThat(map).containsKey("changes");
        assertThat(map).containsKey("totalCount");
    }
}
