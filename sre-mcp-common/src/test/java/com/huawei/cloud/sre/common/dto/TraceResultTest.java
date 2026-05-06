package com.huawei.cloud.sre.common.dto;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TraceResultTest {

    private static final Instant NOW = Instant.now();

    @Test
    void hasErrors_withErrorSpans_returnsTrue() {
        var errorSpan = new TraceResult.Span("span-1", null, "svc", "GET /", NOW, 100L, 500, true, "timeout");
        var result = new TraceResult("trace-1", "svc", NOW, 100L, 500, List.of(errorSpan), List.of(errorSpan));
        assertThat(result.hasErrors()).isTrue();
    }

    @Test
    void hasErrors_withNoErrorSpans_returnsFalse() {
        var result = new TraceResult("trace-1", "svc", NOW, 100L, 200, List.of(), List.of());
        assertThat(result.hasErrors()).isFalse();
    }

    @Test
    void spanCount_returnsCorrectCount() {
        var span1 = new TraceResult.Span("span-1", null, "svc", "GET /", NOW, 50L, 200, false, null);
        var span2 = new TraceResult.Span("span-2", "span-1", "db", "SELECT", NOW, 30L, 200, false, null);
        var result = new TraceResult("trace-1", "svc", NOW, 100L, 200, List.of(span1, span2), List.of());
        assertThat(result.spanCount()).isEqualTo(2);
    }

    @Test
    void spanCount_withNullSpans_returnsZero() {
        var result = new TraceResult("trace-1", "svc", NOW, 100L, 200, null, null);
        assertThat(result.spanCount()).isZero();
    }

    @Test
    void slowestSpan_returnsSpanWithMaxDuration() {
        var fast = new TraceResult.Span("span-1", null, "svc", "op1", NOW, 10L, 200, false, null);
        var slow = new TraceResult.Span("span-2", null, "svc", "op2", NOW, 999L, 200, false, null);
        var result = new TraceResult("trace-1", "svc", NOW, 999L, 200, List.of(fast, slow), List.of());
        assertThat(result.slowestSpan()).isPresent();
        assertThat(result.slowestSpan().get().durationMs()).isEqualTo(999L);
    }

    @Test
    void slowestSpan_withEmptySpans_returnsEmpty() {
        var result = new TraceResult("trace-1", "svc", NOW, 0L, 200, List.of(), List.of());
        assertThat(result.slowestSpan()).isEmpty();
    }
}
