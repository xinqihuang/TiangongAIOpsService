package com.huawei.cloud.sre.common.dto;

import java.time.Instant;
import java.util.List;

/**
 * AOM 性能指标查询结果。
 *
 * @param service   服务名称
 * @param metric    指标名称
 * @param startTime 查询开始时间
 * @param endTime   查询结束时间
 * @param unit      指标单位，如 "percent"、"bytes/s"
 * @param dataPoints 时间序列数据点列表
 */
public record MetricResult(
        String service,
        String metric,
        Instant startTime,
        Instant endTime,
        String unit,
        List<DataPoint> dataPoints
) {

    /**
     * 单个时间序列数据点。
     *
     * @param timestamp 时间戳
     * @param value     指标值
     */
    public record DataPoint(Instant timestamp, double value) {}

    /** 是否没有数据点。 */
    public boolean isEmpty() {
        return dataPoints == null || dataPoints.isEmpty();
    }

    /** 最大值，若无数据则返回 0.0。 */
    public double max() {
        if (isEmpty()) {
            return 0.0;
        }
        return dataPoints.stream().mapToDouble(DataPoint::value).max().orElse(0.0);
    }

    /** 平均值，若无数据则返回 0.0。 */
    public double average() {
        if (isEmpty()) {
            return 0.0;
        }
        return dataPoints.stream().mapToDouble(DataPoint::value).average().orElse(0.0);
    }
}
