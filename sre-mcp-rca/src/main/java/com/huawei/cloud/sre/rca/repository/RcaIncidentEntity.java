package com.huawei.cloud.sre.rca.repository;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * RCA 事故记录 JPA 实体。
 *
 * <p>持久化每次根因分析的结果，用于审计追溯和历史事故检索。
 */
@Entity
@Table(name = "rca_incidents")
public class RcaIncidentEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** 触发分析的服务名称。 */
    @Column(nullable = false, length = 255)
    private String service;

    /** 事故标题或简要描述。 */
    @Column(nullable = false, length = 512)
    private String incidentTitle;

    /** 分析得出的根因摘要。 */
    @Column(columnDefinition = "TEXT")
    private String rootCause;

    /** 严重等级：CRITICAL / HIGH / MEDIUM / LOW。 */
    @Column(length = 20)
    private String severity;

    /** 完整 RCA 报告序列化为 JSON。 */
    @Column(columnDefinition = "TEXT")
    private String reportJson;

    /** 根因所在组件。 */
    @Column(length = 255)
    private String rootCauseComponent;

    /** 置信度 0.0–1.0。 */
    private double confidence;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @PrePersist
    void prePersist() {
        createdAt = Instant.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }

    /** Default constructor required by JPA. */
    protected RcaIncidentEntity() {}

    /**
     * 创建新实体。
     *
     * @param service            服务名
     * @param incidentTitle      事故标题
     * @param rootCause          根因摘要
     * @param severity           严重等级
     * @param rootCauseComponent 根因组件
     * @param confidence         置信度
     * @param reportJson         完整报告 JSON
     */
    public RcaIncidentEntity(
            String service,
            String incidentTitle,
            String rootCause,
            String severity,
            String rootCauseComponent,
            double confidence,
            String reportJson
    ) {
        this.service = service;
        this.incidentTitle = incidentTitle;
        this.rootCause = rootCause;
        this.severity = severity;
        this.rootCauseComponent = rootCauseComponent;
        this.confidence = confidence;
        this.reportJson = reportJson;
    }

    public UUID getId() { return id; }
    public String getService() { return service; }
    public String getIncidentTitle() { return incidentTitle; }
    public String getRootCause() { return rootCause; }
    public String getSeverity() { return severity; }
    public String getReportJson() { return reportJson; }
    public String getRootCauseComponent() { return rootCauseComponent; }
    public double getConfidence() { return confidence; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
