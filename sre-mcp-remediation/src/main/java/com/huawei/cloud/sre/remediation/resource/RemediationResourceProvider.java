package com.huawei.cloud.sre.remediation.resource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.cloud.sre.remediation.repository.RemediationContext;
import com.huawei.cloud.sre.remediation.repository.RemediationContextRepository;
import com.huawei.cloud.sre.remediation.repository.SopStrategyRepository;
import com.huawei.cloud.sre.remediation.service.ApprovalService;
import com.huawei.cloud.sre.remediation.statemachine.RemediationState;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Remediation MCP Resource 注册提供者。
 *
 * <p>提供 4 个 MCP Resource：
 * <ul>
 *   <li>{@code sre://remediation/active-contexts} — 当前活跃修复工单</li>
 *   <li>{@code sre://remediation/sop-library} — SOP 策略库概览</li>
 *   <li>{@code sre://remediation/approval-queue} — 待审批工单队列</li>
 *   <li>{@code sre://remediation/execution-history} — 最近执行历史</li>
 * </ul>
 */
@Component
public class RemediationResourceProvider {

    private static final Logger log = LoggerFactory.getLogger(RemediationResourceProvider.class);

    private final RemediationContextRepository contextRepository;
    private final SopStrategyRepository sopStrategyRepository;
    private final ApprovalService approvalService;
    private final ObjectMapper objectMapper;

    /**
     * @param contextRepository   工单 JPA 仓库
     * @param sopStrategyRepository SOP 策略 JPA 仓库
     * @param approvalService     审批服务
     * @param objectMapper        JSON 序列化
     */
    public RemediationResourceProvider(RemediationContextRepository contextRepository,
                                       SopStrategyRepository sopStrategyRepository,
                                       ApprovalService approvalService,
                                       ObjectMapper objectMapper) {
        this.contextRepository = contextRepository;
        this.sopStrategyRepository = sopStrategyRepository;
        this.approvalService = approvalService;
        this.objectMapper = objectMapper;
    }

    /**
     * 构建并返回 4 个 MCP Resource 注册规范。
     *
     * @return SyncResourceSpecification 列表
     */
    public List<McpServerFeatures.SyncResourceSpecification> registrations() {
        List<McpServerFeatures.SyncResourceSpecification> regs = new ArrayList<>();
        regs.add(activeContextsResource());
        regs.add(sopLibraryResource());
        regs.add(approvalQueueResource());
        regs.add(executionHistoryResource());
        log.info("RemediationResourceProvider registered {} resources", regs.size());
        return regs;
    }

    private McpServerFeatures.SyncResourceSpecification activeContextsResource() {
        var resource = new McpSchema.Resource(
                "sre://remediation/active-contexts",
                "Active Remediation Contexts",
                "当前所有活跃的修复工单，含状态、风险级别、目标服务",
                "application/json",
                null
        );
        return new McpServerFeatures.SyncResourceSpecification(resource, (exchange, req) -> {
            try {
                long count = contextRepository.countActiveContexts();
                List<RemediationContext> active = new ArrayList<>();
                for (RemediationState state : RemediationState.values()) {
                    if (!state.isTerminal()) {
                        active.addAll(contextRepository.findByState(state));
                    }
                }
                List<Map<String, Object>> items = new ArrayList<>();
                for (RemediationContext ctx : active) {
                    Map<String, Object> m = new HashMap<>();
                    m.put("contextId", ctx.getContextId());
                    m.put("targetService", ctx.getTargetService());
                    m.put("action", ctx.getStrategyName());
                    m.put("state", ctx.getState().name());
                    m.put("riskLevel", ctx.getRiskLevel() != null ? ctx.getRiskLevel() : "");
                    m.put("requestedBy", ctx.getRequestedBy() != null ? ctx.getRequestedBy() : "");
                    m.put("createdAt", ctx.getCreatedAt() != null ? ctx.getCreatedAt().toString() : "");
                    items.add(m);
                }
                Map<String, Object> payload = new HashMap<>();
                payload.put("activeContexts", items);
                payload.put("count", count);
                payload.put("generatedAt", Instant.now().toString());
                String json = objectMapper.writeValueAsString(payload);
                return new McpSchema.ReadResourceResult(List.of(
                        new McpSchema.TextResourceContents("sre://remediation/active-contexts", "application/json", json)));
            } catch (Exception e) {
                log.error("Failed to read active-contexts resource", e);
                return errorResult("sre://remediation/active-contexts", e.getMessage());
            }
        });
    }

    private McpServerFeatures.SyncResourceSpecification sopLibraryResource() {
        var resource = new McpSchema.Resource(
                "sre://remediation/sop-library",
                "SOP Strategy Library",
                "SOP 策略库概览：所有已注册的修复策略，含适用症状、风险级别、预计耗时",
                "application/json",
                null
        );
        return new McpServerFeatures.SyncResourceSpecification(resource, (exchange, req) -> {
            try {
                var strategies = sopStrategyRepository.findAll();
                List<Map<String, Object>> items = new ArrayList<>();
                for (var s : strategies) {
                    Map<String, Object> m = new HashMap<>();
                    m.put("strategyId", s.getStrategyId());
                    m.put("name", s.getName());
                    m.put("description", s.getDescription() != null ? s.getDescription() : "");
                    m.put("riskLevel", s.getRiskLevel());
                    m.put("toolName", s.getToolName() != null ? s.getToolName() : "");
                    m.put("estimatedMinutes", s.getEstimatedMinutes());
                    m.put("priority", s.getPriority());
                    m.put("symptomKeywords", s.getSymptomKeywords() != null ? s.getSymptomKeywords() : "");
                    items.add(m);
                }
                Map<String, Object> payload = new HashMap<>();
                payload.put("strategies", items);
                payload.put("count", items.size());
                payload.put("generatedAt", Instant.now().toString());
                String json = objectMapper.writeValueAsString(payload);
                return new McpSchema.ReadResourceResult(List.of(
                        new McpSchema.TextResourceContents("sre://remediation/sop-library", "application/json", json)));
            } catch (Exception e) {
                log.error("Failed to read sop-library resource", e);
                return errorResult("sre://remediation/sop-library", e.getMessage());
            }
        });
    }

    private McpServerFeatures.SyncResourceSpecification approvalQueueResource() {
        var resource = new McpSchema.Resource(
                "sre://remediation/approval-queue",
                "Approval Queue",
                "待审批的修复工单队列，含工单 ID、风险级别、审批人列表",
                "application/json",
                null
        );
        return new McpServerFeatures.SyncResourceSpecification(resource, (exchange, req) -> {
            try {
                var pending = contextRepository.findPendingApprovals();
                List<Map<String, Object>> items = new ArrayList<>();
                for (RemediationContext ctx : pending) {
                    boolean allApproved = approvalService.allApproved(ctx);
                    Map<String, Object> m = new HashMap<>();
                    m.put("contextId", ctx.getContextId());
                    m.put("targetService", ctx.getTargetService());
                    m.put("action", ctx.getStrategyName());
                    m.put("riskLevel", ctx.getRiskLevel() != null ? ctx.getRiskLevel() : "");
                    m.put("requestedBy", ctx.getRequestedBy() != null ? ctx.getRequestedBy() : "");
                    m.put("approvers", ctx.getApprovers() != null ? ctx.getApprovers() : "");
                    m.put("allApproved", allApproved);
                    m.put("createdAt", ctx.getCreatedAt() != null ? ctx.getCreatedAt().toString() : "");
                    items.add(m);
                }
                Map<String, Object> payload = new HashMap<>();
                payload.put("pendingApprovals", items);
                payload.put("count", items.size());
                payload.put("generatedAt", Instant.now().toString());
                String json = objectMapper.writeValueAsString(payload);
                return new McpSchema.ReadResourceResult(List.of(
                        new McpSchema.TextResourceContents("sre://remediation/approval-queue", "application/json", json)));
            } catch (Exception e) {
                log.error("Failed to read approval-queue resource", e);
                return errorResult("sre://remediation/approval-queue", e.getMessage());
            }
        });
    }

    private McpServerFeatures.SyncResourceSpecification executionHistoryResource() {
        var resource = new McpSchema.Resource(
                "sre://remediation/execution-history",
                "Execution History",
                "最近 20 条修复执行历史，含执行结果、耗时、风险级别",
                "application/json",
                null
        );
        return new McpServerFeatures.SyncResourceSpecification(resource, (exchange, req) -> {
            try {
                // Collect terminal contexts (completed/failed) for history
                List<RemediationContext> history = new ArrayList<>();
                history.addAll(contextRepository.findByState(RemediationState.COMPLETED));
                history.addAll(contextRepository.findByState(RemediationState.FAILED));
                history.addAll(contextRepository.findByState(RemediationState.ROLLED_BACK));
                history.addAll(contextRepository.findByState(RemediationState.REJECTED));

                // Sort by createdAt desc, limit 20
                history.sort((a, b) -> {
                    if (a.getCreatedAt() == null) return 1;
                    if (b.getCreatedAt() == null) return -1;
                    return b.getCreatedAt().compareTo(a.getCreatedAt());
                });
                List<RemediationContext> recent = history.stream().limit(20).toList();

                List<Map<String, Object>> items = new ArrayList<>();
                for (RemediationContext ctx : recent) {
                    Map<String, Object> m = new HashMap<>();
                    m.put("contextId", ctx.getContextId());
                    m.put("targetService", ctx.getTargetService());
                    m.put("action", ctx.getStrategyName());
                    m.put("state", ctx.getState().name());
                    m.put("riskLevel", ctx.getRiskLevel() != null ? ctx.getRiskLevel() : "");
                    m.put("requestedBy", ctx.getRequestedBy() != null ? ctx.getRequestedBy() : "");
                    m.put("createdAt", ctx.getCreatedAt() != null ? ctx.getCreatedAt().toString() : "");
                    m.put("historyEntries", ctx.getHistoryEntries());
                    items.add(m);
                }
                Map<String, Object> payload = new HashMap<>();
                payload.put("history", items);
                payload.put("count", items.size());
                payload.put("generatedAt", Instant.now().toString());
                String json = objectMapper.writeValueAsString(payload);
                return new McpSchema.ReadResourceResult(List.of(
                        new McpSchema.TextResourceContents("sre://remediation/execution-history", "application/json", json)));
            } catch (Exception e) {
                log.error("Failed to read execution-history resource", e);
                return errorResult("sre://remediation/execution-history", e.getMessage());
            }
        });
    }

    private McpSchema.ReadResourceResult errorResult(String uri, String error) {
        return new McpSchema.ReadResourceResult(List.of(
                new McpSchema.TextResourceContents(uri, "application/json",
                        "{\"error\":\"" + error + "\"}")));
    }
}
