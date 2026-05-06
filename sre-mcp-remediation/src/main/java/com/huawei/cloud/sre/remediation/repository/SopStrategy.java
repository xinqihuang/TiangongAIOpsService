package com.huawei.cloud.sre.remediation.repository;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * SOP（标准操作程序）策略库实体。
 *
 * <p>存储每个故障类型对应的修复策略、风险级别、执行步骤。
 * 通过症状关键词匹配找到最合适的 SOP。
 */
@Entity
@Table(name = "sop_strategies")
public class SopStrategy {

    @Id
    @Column(name = "strategy_id", nullable = false, length = 36)
    private String strategyId;

    @Column(nullable = false, length = 128, unique = true)
    private String name;

    @Column(nullable = false, length = 512)
    private String description;

    /** 症状关键词（逗号分隔），用于模糊匹配事故描述。 */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String symptomKeywords;

    /** 适用服务类型（逗号分隔，如 "api,web,worker"；"*" 表示通用）。 */
    @Column(nullable = false, length = 256)
    private String applicableServices;

    /** 风险级别：LOW / MEDIUM / HIGH。 */
    @Column(nullable = false, length = 16)
    private String riskLevel;

    /** 对应的 Tool 名称（如 restartPod）。 */
    @Column(nullable = false, length = 128)
    private String toolName;

    /** 执行步骤描述（文本）。 */
    @Column(columnDefinition = "TEXT")
    private String executionSteps;

    /** 预计耗时（分钟）。 */
    @Column(nullable = false)
    private int estimatedMinutes;

    /** 优先级（数字越小优先级越高）。 */
    @Column(nullable = false)
    private int priority;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected SopStrategy() {}

    /**
     * 创建 SOP 策略。
     *
     * @param name               策略名称（唯一）
     * @param description        描述
     * @param symptomKeywords    症状关键词（逗号分隔）
     * @param applicableServices 适用服务
     * @param riskLevel          风险级别
     * @param toolName           对应 Tool 名称
     * @param executionSteps     执行步骤
     * @param estimatedMinutes   预计耗时
     * @param priority           优先级
     */
    public SopStrategy(String name, String description, String symptomKeywords,
                       String applicableServices, String riskLevel, String toolName,
                       String executionSteps, int estimatedMinutes, int priority) {
        this.strategyId = UUID.randomUUID().toString();
        this.name = name;
        this.description = description;
        this.symptomKeywords = symptomKeywords;
        this.applicableServices = applicableServices;
        this.riskLevel = riskLevel;
        this.toolName = toolName;
        this.executionSteps = executionSteps;
        this.estimatedMinutes = estimatedMinutes;
        this.priority = priority;
    }

    @PrePersist
    void onPersist() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    /** 检查症状描述是否匹配本策略（关键词命中率 ≥ 1）。 */
    public boolean matches(String symptomText) {
        if (symptomText == null || symptomText.isEmpty()) return false;
        String lower = symptomText.toLowerCase();
        for (String kw : symptomKeywords.split(",")) {
            if (lower.contains(kw.trim().toLowerCase())) return true;
        }
        return false;
    }

    public String getStrategyId() { return strategyId; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public String getSymptomKeywords() { return symptomKeywords; }
    public String getApplicableServices() { return applicableServices; }
    public String getRiskLevel() { return riskLevel; }
    public String getToolName() { return toolName; }
    public String getExecutionSteps() { return executionSteps; }
    public int getEstimatedMinutes() { return estimatedMinutes; }
    public int getPriority() { return priority; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
