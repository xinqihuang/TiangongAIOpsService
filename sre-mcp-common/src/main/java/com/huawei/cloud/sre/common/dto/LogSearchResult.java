package com.huawei.cloud.sre.common.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * LTS 日志搜索结果。
 *
 * @param service    服务名称
 * @param keyword    搜索关键词
 * @param totalCount 匹配日志总条数
 * @param entries    日志条目列表（最多返回 100 条）
 */
public record LogSearchResult(
        String service,
        String keyword,
        long totalCount,
        List<LogEntry> entries
) {

    /**
     * 单条日志记录。
     *
     * @param timestamp  日志时间戳
     * @param level      日志级别，如 "ERROR"、"WARN"、"INFO"
     * @param message    日志正文
     * @param traceId    关联的 Trace ID（可为 null）
     * @param fields     结构化字段，如 pod、namespace 等
     */
    public record LogEntry(
            Instant timestamp,
            String level,
            String message,
            String traceId,
            Map<String, String> fields
    ) {}

    /** 是否命中任何日志。 */
    public boolean hasMatches() {
        return totalCount > 0;
    }

    /** 错误级别日志条目数（从已返回的条目中统计）。 */
    public long errorCount() {
        if (entries == null) {
            return 0;
        }
        return entries.stream()
                .filter(e -> "ERROR".equalsIgnoreCase(e.level()))
                .count();
    }
}
