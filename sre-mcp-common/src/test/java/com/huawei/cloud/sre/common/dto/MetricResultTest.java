package com.huawei.cloud.sre.common.dto;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MetricResultTest {

    private static final Instant START = Instant.parse("2025-01-01T00:00:00Z");
    private static final Instant END = Instant.parse("2025-01-01T01:00:00Z");

    @Test
    void isEmpty_withNoDataPoints_returnsTrue() {
        var result = new MetricResult("svc", "cpu", START, END, "percent", List.of());
        assertThat(result.isEmpty()).isTrue();
    }

    @Test
    void isEmpty_withDataPoints_returnsFalse() {
        var dp = new MetricResult.DataPoint(START, 50.0);
        var result = new MetricResult("svc", "cpu", START, END, "percent", List.of(dp));
        assertThat(result.isEmpty()).isFalse();
    }

    @Test
    void max_withDataPoints_returnsMax() {
        var dps = List.of(
                new MetricResult.DataPoint(START, 30.0),
                new MetricResult.DataPoint(END, 80.0),
                new MetricResult.DataPoint(START.plusSeconds(60), 50.0)
        );
        var result = new MetricResult("svc", "cpu", START, END, "percent", dps);
        assertThat(result.max()).isEqualTo(80.0);
    }

    @Test
    void average_withDataPoints_returnsAverage() {
        var dps = List.of(
                new MetricResult.DataPoint(START, 30.0),
                new MetricResult.DataPoint(END, 60.0)
        );
        var result = new MetricResult("svc", "cpu", START, END, "percent", dps);
        assertThat(result.average()).isEqualTo(45.0);
    }

    @Test
    void max_withEmptyDataPoints_returnsZero() {
        var result = new MetricResult("svc", "cpu", START, END, "percent", List.of());
        assertThat(result.max()).isZero();
    }

    @Test
    void average_withNullDataPoints_returnsZero() {
        var result = new MetricResult("svc", "cpu", START, END, "percent", null);
        assertThat(result.average()).isZero();
    }
}
