package com.huawei.cloud.sre.monitor.repository;

import java.time.Instant;

/**
 * 动态基线状态数据对象（内存存储，无 JPA 依赖）。
 */
public class BaselineEntity {

    private String baselineKey;
    private String service;
    private String metric;
    private double ewmaMean;
    private double ewmaVariance;
    private long sampleCount;
    private double alpha;
    private Instant lastUpdated;
    private Instant createdAt;

    protected BaselineEntity() {}

    /**
     * @param service      服务名
     * @param metric       指标名
     * @param ewmaMean     EWMA 均值
     * @param ewmaVariance EWMA 方差
     * @param sampleCount  样本数
     * @param alpha        平滑系数
     */
    public BaselineEntity(String service, String metric,
                          double ewmaMean, double ewmaVariance,
                          long sampleCount, double alpha) {
        this.baselineKey = service + ":" + metric;
        this.service = service;
        this.metric = metric;
        this.ewmaMean = ewmaMean;
        this.ewmaVariance = ewmaVariance;
        this.sampleCount = sampleCount;
        this.alpha = alpha;
        Instant now = Instant.now();
        this.createdAt = now;
        this.lastUpdated = now;
    }

    public String getBaselineKey() { return baselineKey; }
    public String getService() { return service; }
    public String getMetric() { return metric; }
    public double getEwmaMean() { return ewmaMean; }
    public double getEwmaVariance() { return ewmaVariance; }
    public long getSampleCount() { return sampleCount; }
    public double getAlpha() { return alpha; }
    public Instant getLastUpdated() { return lastUpdated; }
    public Instant getCreatedAt() { return createdAt; }

    public void setEwmaMean(double ewmaMean) { this.ewmaMean = ewmaMean; this.lastUpdated = Instant.now(); }
    public void setEwmaVariance(double ewmaVariance) { this.ewmaVariance = ewmaVariance; }
    public void setSampleCount(long sampleCount) { this.sampleCount = sampleCount; }
}
