package com.huawei.cloud.sre.monitor.dto;

import java.time.Instant;

/**
 * 动态基线当前状态快照。
 *
 * @param service       服务名称
 * @param metric        指标名称
 * @param ewmaMean      EWMA 平滑均值
 * @param ewmaVariance  EWMA 平滑方差
 * @param stdDev        标准差（sqrt(variance)）
 * @param upperBound    告警上界（mean + 3*σ）
 * @param lowerBound    告警下界（mean - 3*σ）
 * @param sampleCount   已纳入基线的样本数量
 * @param lastUpdated   基线最后更新时间
 * @param isStable      基线是否已稳定（样本数 ≥ 30 时视为稳定）
 */
public record BaselineStatus(
        String service,
        String metric,
        double ewmaMean,
        double ewmaVariance,
        double stdDev,
        double upperBound,
        double lowerBound,
        long sampleCount,
        Instant lastUpdated,
        boolean isStable
) {
}
