package com.huawei.cloud.sre.monitor.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.cloud.sre.monitor.dto.AnomalyResult;
import com.huawei.cloud.sre.monitor.dto.BaselineStatus;
import com.huawei.cloud.sre.monitor.dto.CapacityForecast;
import com.huawei.cloud.sre.monitor.repository.BaselineEntity;
import com.huawei.cloud.sre.monitor.repository.BaselineRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BaselineEngineTest {

    @Mock
    private BaselineRepository baselineRepository;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOps;

    private BaselineEngine engine;

    @BeforeEach
    void setUp() {
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOps);
        lenient().when(valueOps.get(anyString())).thenReturn(null);
        engine = new BaselineEngine(baselineRepository, redisTemplate, new ObjectMapper(), 0.2);
    }

    @Test
    void updateBaseline_createsNewEntity_whenNoneExists() {
        when(baselineRepository.findByServiceAndMetric("svc", "cpu")).thenReturn(Optional.empty());
        when(baselineRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        BaselineStatus status = engine.updateBaseline("svc", "cpu", List.of(50.0, 55.0, 60.0));

        assertThat(status.service()).isEqualTo("svc");
        assertThat(status.metric()).isEqualTo("cpu");
        assertThat(status.ewmaMean()).isGreaterThan(0.0);
        assertThat(status.sampleCount()).isEqualTo(3L);
        verify(baselineRepository).save(any(BaselineEntity.class));
    }

    @Test
    void updateBaseline_updatesExistingEntity() {
        BaselineEntity existing = new BaselineEntity("svc", "cpu", 50.0, 25.0, 10L, 0.2);
        when(baselineRepository.findByServiceAndMetric("svc", "cpu")).thenReturn(Optional.of(existing));
        when(baselineRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        BaselineStatus status = engine.updateBaseline("svc", "cpu", List.of(80.0));

        assertThat(status.ewmaMean()).isGreaterThan(50.0);
        assertThat(status.sampleCount()).isEqualTo(11L);
    }

    @Test
    void updateBaseline_returnsCurrentStatus_whenObservationsEmpty() {
        BaselineEntity existing = new BaselineEntity("svc", "cpu", 50.0, 25.0, 40L, 0.2);
        when(baselineRepository.findByServiceAndMetric("svc", "cpu")).thenReturn(Optional.of(existing));

        BaselineStatus status = engine.updateBaseline("svc", "cpu", List.of());

        assertThat(status.ewmaMean()).isEqualTo(50.0);
    }

    @Test
    void detectAnomaly_returnsNormal_whenValueWithinBounds() {
        BaselineEntity entity = new BaselineEntity("svc", "latency", 100.0, 25.0, 50L, 0.2);
        when(baselineRepository.findByServiceAndMetric("svc", "latency")).thenReturn(Optional.of(entity));

        AnomalyResult result = engine.detectAnomaly("svc", "latency", 105.0);

        assertThat(result.isAnomaly()).isFalse();
        assertThat(result.anomalyType()).isEqualTo("NORMAL");
        assertThat(result.severity()).isEqualTo("NORMAL");
    }

    @Test
    void detectAnomaly_returnsAnomaly_whenSpikeDetected() {
        // mean=100, variance=1 (stdDev=1) → spike at 104 = 4σ
        BaselineEntity entity = new BaselineEntity("svc", "latency", 100.0, 1.0, 50L, 0.2);
        when(baselineRepository.findByServiceAndMetric("svc", "latency")).thenReturn(Optional.of(entity));

        AnomalyResult result = engine.detectAnomaly("svc", "latency", 104.0);

        assertThat(result.isAnomaly()).isTrue();
        assertThat(result.anomalyType()).isEqualTo("SPIKE");
        assertThat(result.deviationSigma()).isGreaterThanOrEqualTo(3.0);
        assertThat(result.isCritical()).isTrue();
    }

    @Test
    void detectAnomaly_returnsDrop_whenValueFarBelow() {
        BaselineEntity entity = new BaselineEntity("svc", "cpu", 80.0, 1.0, 50L, 0.2);
        when(baselineRepository.findByServiceAndMetric("svc", "cpu")).thenReturn(Optional.of(entity));

        AnomalyResult result = engine.detectAnomaly("svc", "cpu", 70.0);

        assertThat(result.isAnomaly()).isTrue();
        assertThat(result.anomalyType()).isEqualTo("DROP");
    }

    @Test
    void detectAnomaly_noAnomaly_whenBaselineUnstable() {
        // Only 10 samples < MIN_STABLE_SAMPLES(30): should not fire anomaly
        BaselineEntity entity = new BaselineEntity("svc", "cpu", 50.0, 1.0, 10L, 0.2);
        when(baselineRepository.findByServiceAndMetric("svc", "cpu")).thenReturn(Optional.of(entity));

        AnomalyResult result = engine.detectAnomaly("svc", "cpu", 100.0);

        assertThat(result.isAnomaly()).isFalse();
    }

    @Test
    void detectAnomaly_returnsNormal_whenVarianceNearZero() {
        BaselineEntity entity = new BaselineEntity("svc", "cpu", 50.0, 0.0, 50L, 0.2);
        when(baselineRepository.findByServiceAndMetric("svc", "cpu")).thenReturn(Optional.of(entity));

        AnomalyResult result = engine.detectAnomaly("svc", "cpu", 9999.0);

        assertThat(result.isAnomaly()).isFalse();
        assertThat(result.anomalyType()).isEqualTo("NORMAL");
    }

    @Test
    void forecastCapacity_returnsStableTrend_whenDataFlat() {
        when(baselineRepository.findByServiceAndMetric(anyString(), anyString())).thenReturn(Optional.empty());

        CapacityForecast forecast = engine.forecastCapacity("svc", "disk",
                List.of(60.0, 60.0, 60.0, 60.0, 60.0), 24);

        assertThat(forecast.trend()).isEqualTo("STABLE");
        assertThat(forecast.trendSlope()).isBetween(-0.05, 0.05);
        assertThat(forecast.hasCapacityRisk()).isFalse();
    }

    @Test
    void forecastCapacity_returnsIncreasingTrend_whenDataRising() {
        when(baselineRepository.findByServiceAndMetric(anyString(), anyString())).thenReturn(Optional.empty());

        CapacityForecast forecast = engine.forecastCapacity("svc", "disk",
                List.of(50.0, 55.0, 60.0, 65.0, 70.0), 24);

        assertThat(forecast.trend()).isEqualTo("INCREASING");
        assertThat(forecast.trendSlope()).isGreaterThan(0.0);
        assertThat(forecast.forecastPoints()).hasSize(24);
    }

    @Test
    void forecastCapacity_returnsInsufficientData_whenOnePoint() {
        CapacityForecast forecast = engine.forecastCapacity("svc", "disk", List.of(70.0), 12);

        assertThat(forecast.forecastPoints()).isEmpty();
        assertThat(forecast.recommendation()).contains("Insufficient data");
    }

    @Test
    void getBaselineStatus_returnsEmpty_whenNothingStored() {
        when(baselineRepository.findByServiceAndMetric("new-svc", "mem")).thenReturn(Optional.empty());

        BaselineStatus status = engine.getBaselineStatus("new-svc", "mem");

        assertThat(status.service()).isEqualTo("new-svc");
        assertThat(status.sampleCount()).isZero();
        assertThat(status.isStable()).isFalse();
    }
}
