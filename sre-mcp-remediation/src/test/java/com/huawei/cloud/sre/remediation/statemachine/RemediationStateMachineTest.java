package com.huawei.cloud.sre.remediation.statemachine;

import com.huawei.cloud.sre.remediation.repository.RemediationContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class RemediationStateMachineTest {

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private RemediationStateMachine stateMachine;

    @BeforeEach
    void setUp() {
        stateMachine = new RemediationStateMachine(eventPublisher);
        stateMachine.init();
    }

    private RemediationContext newContext() {
        return new RemediationContext("pod OOM crash", "order-service",
                "RestartPod", "LOW", "agent", "idem-key-1");
    }

    @Test
    void transit_validSequence_lowRiskPath() {
        RemediationContext ctx = newContext();
        assertThat(ctx.getState()).isEqualTo(RemediationState.INITIATED);

        stateMachine.transit(ctx, RemediationState.STRATEGY_MATCHED, "system");
        assertThat(ctx.getState()).isEqualTo(RemediationState.STRATEGY_MATCHED);

        stateMachine.transit(ctx, RemediationState.RISK_ASSESSED, "system");
        assertThat(ctx.getState()).isEqualTo(RemediationState.RISK_ASSESSED);

        // LOW risk bypasses approval
        stateMachine.transit(ctx, RemediationState.EXECUTING, "system");
        assertThat(ctx.getState()).isEqualTo(RemediationState.EXECUTING);

        stateMachine.transit(ctx, RemediationState.VERIFYING, "system");
        stateMachine.transit(ctx, RemediationState.COMPLETED, "system");
        assertThat(ctx.getState()).isEqualTo(RemediationState.COMPLETED);
    }

    @Test
    void transit_validSequence_highRiskPath() {
        RemediationContext ctx = newContext();

        stateMachine.transit(ctx, RemediationState.STRATEGY_MATCHED, "system");
        stateMachine.transit(ctx, RemediationState.RISK_ASSESSED, "system");
        stateMachine.transit(ctx, RemediationState.PENDING_APPROVAL, "system");
        stateMachine.transit(ctx, RemediationState.APPROVED, "ops-lead");
        stateMachine.transit(ctx, RemediationState.EXECUTING, "system");
        stateMachine.transit(ctx, RemediationState.VERIFYING, "system");
        stateMachine.transit(ctx, RemediationState.COMPLETED, "system");

        assertThat(ctx.getState()).isEqualTo(RemediationState.COMPLETED);
    }

    @Test
    void transit_invalidTransition_throwsException() {
        RemediationContext ctx = newContext();
        // Cannot jump from INITIATED directly to EXECUTING
        assertThatThrownBy(() -> stateMachine.transit(ctx, RemediationState.EXECUTING, "system"))
                .isInstanceOf(IllegalStateTransitionException.class);
    }

    @Test
    void transit_fromTerminalState_throwsException() {
        RemediationContext ctx = newContext();
        stateMachine.transit(ctx, RemediationState.CANCELLED, "user");
        assertThat(ctx.getState()).isEqualTo(RemediationState.CANCELLED);

        assertThatThrownBy(() -> stateMachine.transit(ctx, RemediationState.INITIATED, "system"))
                .isInstanceOf(IllegalStateTransitionException.class);
    }

    @Test
    void transit_failurePath_rollback() {
        RemediationContext ctx = newContext();
        stateMachine.transit(ctx, RemediationState.STRATEGY_MATCHED, "system");
        stateMachine.transit(ctx, RemediationState.RISK_ASSESSED, "system");
        stateMachine.transit(ctx, RemediationState.EXECUTING, "system");
        stateMachine.transit(ctx, RemediationState.FAILED, "system");
        stateMachine.transit(ctx, RemediationState.ROLLING_BACK, "system");
        stateMachine.transit(ctx, RemediationState.ROLLED_BACK, "system");

        assertThat(ctx.getState()).isEqualTo(RemediationState.ROLLED_BACK);
        assertThat(ctx.getState().isTerminal()).isTrue();
    }

    @Test
    void transit_publishesEvent() {
        RemediationContext ctx = newContext();
        stateMachine.transit(ctx, RemediationState.STRATEGY_MATCHED, "system");
        verify(eventPublisher).publishEvent(any(StateChangedEvent.class));
    }

    @Test
    void transit_recordsHistoryEntry() {
        RemediationContext ctx = newContext();
        stateMachine.transit(ctx, RemediationState.STRATEGY_MATCHED, "test-user");
        assertThat(ctx.getHistoryEntries()).isNotEmpty();
        assertThat(ctx.getHistoryEntries().get(0))
                .contains("INITIATED → STRATEGY_MATCHED")
                .contains("test-user");
    }

    @Test
    void canTransit_validPair_returnsTrue() {
        assertThat(stateMachine.canTransit(RemediationState.INITIATED, RemediationState.STRATEGY_MATCHED)).isTrue();
        assertThat(stateMachine.canTransit(RemediationState.RISK_ASSESSED, RemediationState.EXECUTING)).isTrue();
    }

    @Test
    void canTransit_invalidPair_returnsFalse() {
        assertThat(stateMachine.canTransit(RemediationState.INITIATED, RemediationState.EXECUTING)).isFalse();
        assertThat(stateMachine.canTransit(RemediationState.COMPLETED, RemediationState.INITIATED)).isFalse();
    }

    @Test
    void allowedTransitions_initiatedState() {
        Set<RemediationState> allowed = stateMachine.allowedTransitions(RemediationState.INITIATED);
        assertThat(allowed).contains(RemediationState.STRATEGY_MATCHED, RemediationState.CANCELLED);
        assertThat(allowed).doesNotContain(RemediationState.EXECUTING);
    }

    @Test
    void allowedTransitions_terminalState_isEmpty() {
        assertThat(stateMachine.allowedTransitions(RemediationState.COMPLETED)).isEmpty();
        assertThat(stateMachine.allowedTransitions(RemediationState.REJECTED)).isEmpty();
    }

    @Test
    void remediationState_isTerminal_correctBehavior() {
        assertThat(RemediationState.COMPLETED.isTerminal()).isTrue();
        assertThat(RemediationState.ROLLED_BACK.isTerminal()).isTrue();
        assertThat(RemediationState.REJECTED.isTerminal()).isTrue();
        assertThat(RemediationState.CANCELLED.isTerminal()).isTrue();
        assertThat(RemediationState.EXECUTING.isTerminal()).isFalse();
        assertThat(RemediationState.INITIATED.isTerminal()).isFalse();
    }
}
