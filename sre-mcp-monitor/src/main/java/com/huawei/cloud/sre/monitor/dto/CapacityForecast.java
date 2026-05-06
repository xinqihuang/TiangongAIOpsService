package com.huawei.cloud.sre.monitor.dto;

import java.time.Instant;
import java.util.List;

/**
 * 容量预测结果。
 *
 * @param service          服务名称
 * @param metric           指标名称
 * @param forecastHours    预测时间范围（小时）
 * @param currentValue     当前值
 * @param forecastedValue  预测值（线性外推）
 * @param trend            趋势描述：INCREASING/DECREASING/STABLE
 * @param trendSlope       趋势斜率（每小时变化量）
 * @param exhaustionTime   预计资源耗尽时间（若持续增长），null 表示无耗尽风险
 * @param recommendation   扩容建议
 * @param forecastPoints   预测时间序列点
 * @param generatedAt      预测生成时间
 */
public record CapacityForecast(
        String service,
        String metric,
        int forecastHours,
        double currentValue,
        double forecastedValue,
        String trend,
        double trendSlope,
        Instant exhaustionTime,
        String recommendation,
        List<ForecastPoint> forecastPoints,
        Instant generatedAt
) {

    /**
     * 预测时间序列点。
     *
     * @param timestamp     时间点
     * @param forecastValue 预测值
     * @param upperBound    置信上界
     * @param lowerBound    置信下界
     */
    public record ForecastPoint(Instant timestamp, double forecastValue, double upperBound, double lowerBound) {}

    /** 是否存在容量风险（预测值超过告警阈值 80%）。 */
    public boolean hasCapacityRisk() {
        return forecastedValue > currentValue * 1.5 || exhaustionTime != null;
    }
}
