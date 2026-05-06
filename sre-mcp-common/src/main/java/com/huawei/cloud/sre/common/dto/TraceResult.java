package com.huawei.cloud.sre.common.dto;

import java.time.Instant;
import java.util.List;

/**
 * APM 链路追踪分析结果。
 *
 * @param traceId      Trace ID
 * @param rootService  根节点服务名
 * @param startTime    链路开始时间
 * @param durationMs   链路总耗时（毫秒）
 * @param statusCode   终态状态码（HTTP 语义，200 成功 / 5xx 错误）
 * @param spans        所有 Span 列表
 * @param errorSpans   异常 Span 列表（statusCode >= 400 的子集）
 */
public record TraceResult(
        String traceId,
        String rootService,
        Instant startTime,
        long durationMs,
        int statusCode,
        List<Span> spans,
        List<Span> errorSpans
) {

    /**
     * 单个调用 Span。
     *
     * @param spanId       Span ID
     * @param parentSpanId 父 Span ID（根节点为 null）
     * @param service      服务名
     * @param operation    操作名，如 "GET /api/users"
     * @param startTime    开始时间
     * @param durationMs   耗时（毫秒）
     * @param statusCode   HTTP 状态码
     * @param error        是否异常
     * @param errorMessage 异常信息（非异常时为 null）
     */
    public record Span(
            String spanId,
            String parentSpanId,
            String service,
            String operation,
            Instant startTime,
            long durationMs,
            int statusCode,
            boolean error,
            String errorMessage
    ) {}

    /** 是否存在异常 Span。 */
    public boolean hasErrors() {
        return errorSpans != null && !errorSpans.isEmpty();
    }

    /** Span 总数。 */
    public int spanCount() {
        return spans == null ? 0 : spans.size();
    }

    /** 找出耗时最长的 Span（用于定位性能瓶颈）。 */
    public java.util.Optional<Span> slowestSpan() {
        if (spans == null || spans.isEmpty()) {
            return java.util.Optional.empty();
        }
        return spans.stream().max(java.util.Comparator.comparingLong(Span::durationMs));
    }
}
