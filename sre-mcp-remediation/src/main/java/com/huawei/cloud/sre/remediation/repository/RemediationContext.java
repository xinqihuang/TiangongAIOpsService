package com.huawei.cloud.sre.remediation.repository;

import com.huawei.cloud.sre.remediation.statemachine.RemediationState;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 修复工单上下文 JPA 实体。
 *
 * <p>持久化修复工单的完整生命周期信息，包括状态、风险级别、
 * 审批信息、执行历史记录。
 */
@Entity
@Table(name = "remediation_contexts")
public class RemediationContext {

    @Id
    @Column(name = "context_id", nullable = false, length = 36)
    private String contextId;

    @Column(nullable = false, length = 256)
    private String incidentDescription;

    @Column(nullable = false, length = 128)
    private String targetService;

    @Column(nullable = false, length = 128)
    private String strategyName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private RemediationState state;

    /** 风险级别：LOW / MEDIUM / HIGH。 */
    @Column(nullable = false, length = 16)
    private String riskLevel;

    /** 操作人（发起修复的用户或 Agent）。 */
    @Column(nullable = false, length = 128)
    private String requestedBy;

    /** 审批人（中风险：一个；高风险：两个，逗号分隔）。 */
    @Column(length = 256)
    private String approvers;

    /** 审批意见。 */
    @Column(length = 512)
    private String approvalNote;

    /** 执行历史（每条为一行，换行分隔）。 */
    @Column(columnDefinition = "TEXT")
    private String executionHistory;

    /** 最终执行结果描述。 */
    @Column(length = 1024)
    private String executionResult;

    /** 幂等键（防止重复执行）。 */
    @Column(length = 128, unique = true)
    private String idempotencyKey;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected RemediationContext() {}

    /**
     * 创建新修复工单。
     *
     * @param incidentDescription 事故描述
     * @param targetService       目标服务名
     * @param strategyName        SOP 策略名称
     * @param riskLevel           风险级别（LOW/MEDIUM/HIGH）
     * @param requestedBy         发起人
     * @param idempotencyKey      幂等键
     */
    public RemediationContext(String incidentDescription, String targetService,
                              String strategyName, String riskLevel,
                              String requestedBy, String idempotencyKey) {
        this.contextId = UUID.randomUUID().toString();
        this.incidentDescription = incidentDescription;
        this.targetService = targetService;
        this.strategyName = strategyName;
        this.state = RemediationState.INITIATED;
        this.riskLevel = riskLevel;
        this.requestedBy = requestedBy;
        this.idempotencyKey = idempotencyKey;
        this.executionHistory = "";
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

    /** 向执行历史追加一条记录。 */
    public void addHistoryEntry(String entry) {
        String ts = Instant.now().toString();
        if (executionHistory == null || executionHistory.isEmpty()) {
            executionHistory = "[" + ts + "] " + entry;
        } else {
            executionHistory = executionHistory + "\n[" + ts + "] " + entry;
        }
    }

    /** 解析历史记录为列表。 */
    public List<String> getHistoryEntries() {
        if (executionHistory == null || executionHistory.isEmpty()) return List.of();
        return List.of(executionHistory.split("\n"));
    }

    public String getContextId() { return contextId; }
    public String getIncidentDescription() { return incidentDescription; }
    public String getTargetService() { return targetService; }
    public String getStrategyName() { return strategyName; }
    public RemediationState getState() { return state; }
    public String getRiskLevel() { return riskLevel; }
    public String getRequestedBy() { return requestedBy; }
    public String getApprovers() { return approvers; }
    public String getApprovalNote() { return approvalNote; }
    public String getExecutionHistory() { return executionHistory; }
    public String getExecutionResult() { return executionResult; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void setState(RemediationState state) { this.state = state; }
    public void setApprovers(String approvers) { this.approvers = approvers; }
    public void setApprovalNote(String approvalNote) { this.approvalNote = approvalNote; }
    public void setExecutionResult(String executionResult) { this.executionResult = executionResult; }
    public void setStrategyName(String strategyName) { this.strategyName = strategyName; }
    public void setRiskLevel(String riskLevel) { this.riskLevel = riskLevel; }
}
