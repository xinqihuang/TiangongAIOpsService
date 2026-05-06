package com.huawei.cloud.sre.remediation.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.cloud.sre.common.adapter.CceAdapter;
import com.huawei.cloud.sre.common.adapter.EcsAdapter;
import com.huawei.cloud.sre.common.adapter.ElbAdapter;
import com.huawei.cloud.sre.common.adapter.RdsAdapter;
import com.huawei.cloud.sre.common.adapter.ScmAdapter;
import com.huawei.cloud.sre.common.memory.IdempotencyStore;
import com.huawei.cloud.sre.remediation.dto.RemediationResult;
import com.huawei.cloud.sre.remediation.dto.RiskAssessment;
import com.huawei.cloud.sre.remediation.dto.StrategyMatch;
import com.huawei.cloud.sre.remediation.repository.RemediationContext;
import com.huawei.cloud.sre.remediation.repository.RemediationContextRepository;
import com.huawei.cloud.sre.remediation.service.ApprovalService;
import com.huawei.cloud.sre.remediation.service.RemediationCircuitBreaker;
import com.huawei.cloud.sre.remediation.service.StrategyMatcher;
import com.huawei.cloud.sre.remediation.statemachine.RemediationState;
import com.huawei.cloud.sre.remediation.statemachine.RemediationStateMachine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RemediationToolServiceTest {

    @Mock private CceAdapter cceAdapter;
    @Mock private EcsAdapter ecsAdapter;
    @Mock private ElbAdapter elbAdapter;
    @Mock private RdsAdapter rdsAdapter;
    @Mock private ScmAdapter scmAdapter;
    @Mock private RemediationContextRepository contextRepository;
    @Mock private RemediationStateMachine stateMachine;
    @Mock private ApprovalService approvalService;
    @Mock private StrategyMatcher strategyMatcher;
    @Mock private RemediationCircuitBreaker circuitBreaker;
    @Mock private IdempotencyStore idempotencyStore;
    @Mock private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private RemediationToolService toolService;

    private RemediationContext mockContext;

    @BeforeEach
    void setUp() {
        mockContext = new RemediationContext("test incident", "order-service",
                "TestStrategy", "LOW", "agent", "idem-test-1");

        lenient().when(contextRepository.save(any(RemediationContext.class))).thenReturn(mockContext);
        lenient().when(idempotencyStore.tryAcquire(anyString())).thenReturn(true);
        lenient().when(circuitBreaker.isOpen(anyString(), anyString())).thenReturn(false);
        lenient().doNothing().when(stateMachine).transit(any(), any(), anyString());
        lenient().doNothing().when(approvalService).routeByRisk(any(), any());
        lenient().doNothing().when(circuitBreaker).recordSuccess(anyString(), anyString());
    }

    @Test
    void restartPod_lowRisk_executesDirectly() {
        when(cceAdapter.restartPod("default", "order-pod-abc")).thenReturn(Map.of("status", "triggered"));

        RemediationResult result = toolService.restartPod("default", "order-pod-abc", "OOM crash", "key-1");

        assertThat(result.success()).isTrue();
        assertThat(result.action()).isEqualTo("restartPod");
        assertThat(result.riskLevel()).isEqualTo("LOW");
        verify(cceAdapter).restartPod("default", "order-pod-abc");
    }

    @Test
    void restartPod_circuitBreakerOpen_returnsFailedResult() {
        when(circuitBreaker.isOpen("order-pod-abc", "restartPod")).thenReturn(true);

        RemediationResult result = toolService.restartPod("default", "order-pod-abc", "OOM", "key-cb");

        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("Circuit breaker OPEN");
    }

    @Test
    void restartPod_idempotentCall_returnsIdempotentResult() {
        when(idempotencyStore.tryAcquire("key-dup")).thenReturn(false);
        when(idempotencyStore.getResult("key-dup")).thenReturn(Optional.empty());

        RemediationResult result = toolService.restartPod("default", "pod-1", "crash", "key-dup");

        assertThat(result.idempotent()).isTrue();
    }

    @Test
    void scaleDeployment_clampsReplicasTo50() {
        when(cceAdapter.scaleDeployment("default", "order-svc", 50)).thenReturn(Map.of("status", "scaled"));

        RemediationResult result = toolService.scaleDeployment("default", "order-svc", 999,
                "scale test", "key-scale");

        assertThat(result.success()).isTrue();
        // verify clamping: 999 → 50
        verify(cceAdapter).scaleDeployment("default", "order-svc", 50);
    }

    @Test
    void replaceNode_highRisk_returnsPendingApproval() {
        RemediationResult result = toolService.replaceNode(
                "node-001", "ops-lead", "biz-owner", "hardware failure", "key-node");

        assertThat(result.requiresApproval()).isTrue();
        assertThat(result.action()).isEqualTo("replaceNode");
        assertThat(result.approvers()).contains("ops-lead", "biz-owner");
    }

    @Test
    void dbFailover_highRisk_returnsPendingApproval() {
        RemediationResult result = toolService.dbFailover(
                "rds-001", "sre-lead", "dba-lead", "primary down", "key-dbfail");

        assertThat(result.requiresApproval()).isTrue();
        assertThat(result.riskLevel()).isEqualTo("HIGH");
        assertThat(result.approvers()).containsExactlyInAnyOrder("sre-lead", "dba-lead");
    }

    @Test
    void matchStrategy_delegatesToStrategyMatcher() {
        StrategyMatch expected = new StrategyMatch(true, "sop-001", "RestartPod",
                "Restart pod on OOM", "LOW", "restartPod", "step1", 5, List.of());
        when(strategyMatcher.matchStrategy("pod oom crash", "order-service")).thenReturn(expected);

        StrategyMatch result = toolService.matchStrategy("pod oom crash", "order-service");

        assertThat(result.matched()).isTrue();
        assertThat(result.strategyId()).isEqualTo("sop-001");
    }

    @Test
    void assessRisk_delegatesToStrategyMatcher() {
        RiskAssessment expected = new RiskAssessment("restartPod", "LOW", 20,
                List.of("Low impact"), List.of("Monitor post-execution"), false, "NONE",
                "Brief interruption < 30s for order-service", true);
        when(strategyMatcher.assessRisk("restartPod", "order-service", "prod")).thenReturn(expected);

        RiskAssessment result = toolService.assessRisk("restartPod", "order-service", "prod");

        assertThat(result.riskLevel()).isEqualTo("LOW");
        assertThat(result.riskScore()).isEqualTo(20);
    }

    @Test
    void requestApproval_approve_delegatesToApprovalService() {
        when(approvalService.approve("ctx-001", "ops-lead", "LGTM"))
                .thenReturn(RemediationState.APPROVED);

        Map<String, Object> result = toolService.requestApproval("ctx-001", "ops-lead", "APPROVE", "LGTM");

        assertThat(result.get("state")).isEqualTo("APPROVED");
        assertThat(result.get("decision")).isEqualTo("APPROVE");
    }

    @Test
    void requestApproval_reject_delegatesToApprovalService() {
        when(approvalService.reject("ctx-001", "ops-lead", "too risky"))
                .thenReturn(RemediationState.REJECTED);

        Map<String, Object> result = toolService.requestApproval("ctx-001", "ops-lead", "REJECT", "too risky");

        assertThat(result.get("state")).isEqualTo("REJECTED");
    }

    @Test
    void verifyRemediation_contextNotFound_returnsError() {
        when(contextRepository.findById("unknown-ctx")).thenReturn(Optional.empty());

        Map<String, Object> result = toolService.verifyRemediation(
                "unknown-ctx", "order-service", "error_rate", "0");

        assertThat(result.get("verified")).isEqualTo(false);
        assertThat(result.get("error").toString()).contains("not found");
    }

    @Test
    void rollbackAction_failedContext_performsRollback() {
        RemediationContext failedCtx = new RemediationContext("incident", "svc",
                "SomeStrategy", "HIGH", "agent", "key-rb");
        // Force state to FAILED via reflection substitute — use state machine
        // We simulate a FAILED context by creating one and transitioning via direct mock
        when(contextRepository.findById("ctx-rb")).thenReturn(Optional.of(failedCtx));

        // Set state to FAILED first (bypassing state machine for test setup)
        failedCtx.setState(RemediationState.FAILED);

        Map<String, Object> result = toolService.rollbackAction("ctx-rb", "test rollback", "ops");

        assertThat(result.get("contextId")).isEqualTo("ctx-rb");
        assertThat(result).containsKey("state");
    }

    @Test
    void rollbackAction_contextNotFound_returnsError() {
        when(contextRepository.findById("no-ctx")).thenReturn(Optional.empty());

        Map<String, Object> result = toolService.rollbackAction("no-ctx", "reason", "user");

        assertThat(result).containsKey("error");
    }
}
