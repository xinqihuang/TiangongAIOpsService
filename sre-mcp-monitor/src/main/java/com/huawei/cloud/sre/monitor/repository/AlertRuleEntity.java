package com.huawei.cloud.sre.monitor.repository;

import java.time.Instant;
import java.util.UUID;

/**
 * 告警规则数据对象（内存存储，无 JPA 依赖）。
 */
public class AlertRuleEntity {

    private String ruleId;
    private String name;
    private String service;
    private String metric;
    private String condition;
    private double threshold;
    private String severity;
    private boolean enabled;
    private String topicUrn;
    private Instant createdAt;
    private Instant updatedAt;

    protected AlertRuleEntity() {}

    /**
     * @param name      规则名称
     * @param service   监控服务
     * @param metric    监控指标
     * @param condition 触发条件，如 "> 90"
     * @param threshold 阈值
     * @param severity  严重级别
     * @param topicUrn  SMN 通知主题 URN
     */
    public AlertRuleEntity(String name, String service, String metric,
                           String condition, double threshold, String severity, String topicUrn) {
        this.ruleId = UUID.randomUUID().toString();
        this.name = name;
        this.service = service;
        this.metric = metric;
        this.condition = condition;
        this.threshold = threshold;
        this.severity = severity;
        this.enabled = true;
        this.topicUrn = topicUrn;
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    public String getRuleId() { return ruleId; }
    public String getName() { return name; }
    public String getService() { return service; }
    public String getMetric() { return metric; }
    public String getCondition() { return condition; }
    public double getThreshold() { return threshold; }
    public String getSeverity() { return severity; }
    public boolean isEnabled() { return enabled; }
    public String getTopicUrn() { return topicUrn; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        this.updatedAt = Instant.now();
    }

    public void setThreshold(double threshold) {
        this.threshold = threshold;
        this.updatedAt = Instant.now();
    }

    public void setSeverity(String severity) {
        this.severity = severity;
        this.updatedAt = Instant.now();
    }

    public void setTopicUrn(String topicUrn) {
        this.topicUrn = topicUrn;
        this.updatedAt = Instant.now();
    }
}
