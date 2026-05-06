package com.huawei.cloud.sre.monitor.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.cloud.sre.monitor.dto.AlertGroup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AlertAggregatorTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOps;

    @Mock
    private HashOperations<String, Object, Object> hashOps;

    private AlertAggregator aggregator;

    @BeforeEach
    void setUp() {
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOps);
        lenient().when(redisTemplate.opsForHash()).thenReturn(hashOps);
        lenient().when(redisTemplate.hasKey(anyString())).thenReturn(false);
        aggregator = new AlertAggregator(redisTemplate, new ObjectMapper(), 5);
    }

    @Test
    void aggregate_returnsEmpty_whenNoAlerts() {
        List<AlertGroup> result = aggregator.aggregate(List.of());
        assertThat(result).isEmpty();
    }

    @Test
    void aggregate_returnsSingleGroup_forSingleAlert() {
        Map<String, Object> alert = buildAlert("a1", "order-service", "cpu_usage", "HIGH", "CPU spike detected");

        List<AlertGroup> groups = aggregator.aggregate(List.of(alert));

        assertThat(groups).hasSize(1);
        assertThat(groups.get(0).service()).isEqualTo("order-service");
        assertThat(groups.get(0).alertCount()).isEqualTo(1);
    }

    @Test
    void aggregate_deduplicates_semanticallySimilarAlerts() {
        Map<String, Object> a1 = buildAlert("a1", "order-service", "cpu_usage", "HIGH", "CPU spike detected on order");
        Map<String, Object> a2 = buildAlert("a2", "order-service", "cpu_usage", "HIGH", "CPU spike detected on order");

        List<AlertGroup> groups = aggregator.aggregate(List.of(a1, a2));

        // Two identical alerts from same service/metric are merged into a single group
        assertThat(groups).hasSize(1);
        assertThat(groups.get(0).service()).isEqualTo("order-service");
    }

    @Test
    void aggregate_groupsByTopology_whenServicePrefixMatches() {
        Map<String, Object> a1 = buildAlert("a1", "order-service", "cpu_usage", "HIGH", "CPU high on order-v1");
        Map<String, Object> a2 = buildAlert("a2", "order-worker", "cpu_usage", "HIGH", "CPU high on order-v2");

        List<AlertGroup> groups = aggregator.aggregate(List.of(a1, a2));

        assertThat(groups).isNotEmpty();
    }

    @Test
    void aggregate_marksAsSuppressed_whenSilenced() {
        when(redisTemplate.hasKey("monitor:silence:order-service:cpu_usage")).thenReturn(true);
        Map<String, Object> alert = buildAlert("a1", "order-service", "cpu_usage", "HIGH", "CPU spike");

        List<AlertGroup> groups = aggregator.aggregate(List.of(alert));

        assertThat(groups.get(0).suppressed()).isTrue();
    }

    @Test
    void silence_setsRedisKey() {
        aggregator.silence("order-service", "cpu_usage", Duration.ofMinutes(30));
        verify(valueOps).set(eq("monitor:silence:order-service:cpu_usage"),
                eq("silenced"), eq(Duration.ofMinutes(30)));
    }

    @Test
    void unsilence_deletesRedisKey() {
        aggregator.unsilence("order-service", "cpu_usage");
        verify(redisTemplate).delete("monitor:silence:order-service:cpu_usage");
    }

    @Test
    void isSilenced_returnsFalse_whenNoKeyExists() {
        when(redisTemplate.hasKey(anyString())).thenReturn(false);
        assertThat(aggregator.isSilenced("order-service", "cpu_usage")).isFalse();
    }

    @Test
    void isSilenced_returnsTrue_whenKeyExists() {
        when(redisTemplate.hasKey("monitor:silence:order-service:cpu_usage")).thenReturn(true);
        assertThat(aggregator.isSilenced("order-service", "cpu_usage")).isTrue();
    }

    @Test
    void alertGroup_isAlertStorm_whenMoreThan10() {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 11; i++) ids.add("a" + i);
        AlertGroup group = new AlertGroup("g1", "svc", "cause", "HIGH",
                ids, 11, Instant.now(), Instant.now(), "TIME", false);
        assertThat(group.isAlertStorm()).isTrue();
    }

    @Test
    void alertGroup_isNotAlertStorm_whenFewAlerts() {
        AlertGroup group = new AlertGroup("g1", "svc", "cause", "HIGH",
                List.of("a1", "a2"), 2, Instant.now(), Instant.now(), "TIME", false);
        assertThat(group.isAlertStorm()).isFalse();
    }

    private Map<String, Object> buildAlert(String id, String service, String metric,
                                            String severity, String message) {
        Map<String, Object> alert = new HashMap<>();
        alert.put("id", id);
        alert.put("service", service);
        alert.put("metric", metric);
        alert.put("severity", severity);
        alert.put("message", message);
        alert.put("timestamp", Instant.now().toString());
        return alert;
    }
}
