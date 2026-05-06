package com.huawei.cloud.sre.rca.dto;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RcaReportTest {

    @Test
    void isHighConfidence_trueAtExactly08() {
        RcaReport report = report("HIGH", 0.8);
        assertThat(report.isHighConfidence()).isTrue();
    }

    @Test
    void isHighConfidence_falseBelow08() {
        RcaReport report = report("HIGH", 0.79);
        assertThat(report.isHighConfidence()).isFalse();
    }

    @Test
    void isCritical_trueForCritical() {
        assertThat(report("CRITICAL", 0.5).isCritical()).isTrue();
    }

    @Test
    void isCritical_trueForHigh() {
        assertThat(report("HIGH", 0.5).isCritical()).isTrue();
    }

    @Test
    void isCritical_falseForMedium() {
        assertThat(report("MEDIUM", 0.5).isCritical()).isFalse();
    }

    @Test
    void isCritical_caseInsensitive() {
        assertThat(report("critical", 0.5).isCritical()).isTrue();
        assertThat(report("high", 0.5).isCritical()).isTrue();
    }

    private RcaReport report(String severity, double confidence) {
        return new RcaReport(
                "INC-1", "root cause", "component", "description",
                List.of(), List.of(), "scope", severity,
                List.of(), List.of(), confidence, Instant.now()
        );
    }
}
