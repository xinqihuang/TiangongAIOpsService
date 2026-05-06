package com.huawei.cloud.sre.rca.service;

import com.huawei.cloud.sre.rca.dto.KgQueryResult;
import com.huawei.cloud.sre.rca.dto.TopologyResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Result;
import org.neo4j.driver.Session;
import org.neo4j.driver.Value;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KnowledgeGraphServiceTest {

    @Mock
    private Driver driver;

    @Mock
    private Session session;

    private KnowledgeGraphService service;

    @BeforeEach
    void setUp() {
        lenient().when(driver.session()).thenReturn(session);
        service = new KnowledgeGraphService(driver);
    }

    @Test
    void executeCypher_returnsEmptyResult_whenNoRows() {
        Result result = mock(Result.class);
        when(result.hasNext()).thenReturn(false);
        when(session.run(anyString(), any(Map.class))).thenReturn(result);

        KgQueryResult kgResult = service.executeCypher("MATCH (n) RETURN n LIMIT 0", Map.of());

        assertThat(kgResult.records()).isEmpty();
        assertThat(kgResult.totalCount()).isZero();
        assertThat(kgResult.cypher()).isEqualTo("MATCH (n) RETURN n LIMIT 0");
    }

    @Test
    void queryTopology_returnsFallbackNode_whenNoResults() {
        Result result = mock(Result.class);
        when(result.hasNext()).thenReturn(false);
        when(session.run(anyString(), any(Value.class))).thenReturn(result);

        TopologyResult topo = service.queryTopology("user-service", 2);

        assertThat(topo.rootService()).isEqualTo("user-service");
        assertThat(topo.nodes()).hasSize(1);
        assertThat(topo.nodes().get(0).name()).isEqualTo("user-service");
        assertThat(topo.edges()).isEmpty();
    }

    @Test
    void queryTopology_clampDepthToRange() {
        Result result = mock(Result.class);
        when(result.hasNext()).thenReturn(false);
        when(session.run(anyString(), any(Value.class))).thenReturn(result);

        TopologyResult topoMin = service.queryTopology("svc", 0);
        assertThat(topoMin.depth()).isEqualTo(1);

        TopologyResult topoMax = service.queryTopology("svc", 99);
        assertThat(topoMax.depth()).isEqualTo(5);
    }

    @Test
    void kgQueryResult_hasResults_returnsFalse_whenEmpty() {
        KgQueryResult empty = new KgQueryResult("MATCH (n) RETURN n", List.of(), 0, 10L);
        assertThat(empty.hasResults()).isFalse();
    }

    @Test
    void kgQueryResult_hasResults_returnsTrue_whenNonEmpty() {
        KgQueryResult nonEmpty = new KgQueryResult(
                "MATCH (n) RETURN n",
                List.of(Map.of("n", "value")),
                1,
                5L
        );
        assertThat(nonEmpty.hasResults()).isTrue();
    }
}
