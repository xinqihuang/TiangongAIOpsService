package com.huawei.cloud.sre.common.dto;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LogSearchResultTest {

    @Test
    void hasMatches_withTotalCountGreaterThanZero_returnsTrue() {
        var result = new LogSearchResult("svc", "ERROR", 5L, List.of());
        assertThat(result.hasMatches()).isTrue();
    }

    @Test
    void hasMatches_withZeroCount_returnsFalse() {
        var result = new LogSearchResult("svc", "ERROR", 0L, List.of());
        assertThat(result.hasMatches()).isFalse();
    }

    @Test
    void errorCount_countsOnlyErrorLevelEntries() {
        var entries = List.of(
                new LogSearchResult.LogEntry(Instant.now(), "ERROR", "msg1", null, Map.of()),
                new LogSearchResult.LogEntry(Instant.now(), "INFO", "msg2", null, Map.of()),
                new LogSearchResult.LogEntry(Instant.now(), "ERROR", "msg3", null, Map.of()),
                new LogSearchResult.LogEntry(Instant.now(), "WARN", "msg4", null, Map.of())
        );
        var result = new LogSearchResult("svc", "keyword", 4L, entries);
        assertThat(result.errorCount()).isEqualTo(2L);
    }

    @Test
    void errorCount_withNullEntries_returnsZero() {
        var result = new LogSearchResult("svc", "keyword", 0L, null);
        assertThat(result.errorCount()).isZero();
    }
}
