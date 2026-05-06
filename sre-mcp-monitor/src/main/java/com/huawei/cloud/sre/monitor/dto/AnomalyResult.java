package com.huawei.cloud.sre.monitor.dto;

import java.time.Instant;
import java.util.List;

/**
 * 异常检测结果。
 *
 * @param service         服务名称
 * @param metric          指标名称
 * @param detectedAt      检测时间
 * @param isAnomaly       是否检测到异常
 * @param currentValue    当前值
 * @param baselineMean    基线均值
 * @param baselineStdDev  基线标准差
 * @param deviationSigma  偏差倍数（|current - mean| / stdDev）
 * @param severity        严重程度：CRITICAL/HIGH/MEDIUM/LOW/NORMAL
 * @param anomalyType     异常类型：SPIKE/DROP/TREND/NORMAL
 * @param suggestion      处置建议
 * @param relatedMetrics  相关联指标列表
 */
public record AnomalyResult(
        String service,
        String metric,
        Instant detectedAt,
        boolean isAnomaly,
        double currentValue,
        double baselineMean,
        double baselineStdDev,
        double deviationSigma,
        String severity,
        String anomalyType,
        String suggestion,
        List<String> relatedMetrics
) {

    /** 是否为严重异常（≥ 3σ）。 */
    public boolean isCritical() {
        return deviationSigma >= 3.0 && isAnomaly;
    }
}
