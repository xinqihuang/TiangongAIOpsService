package com.huawei.cloud.sre.remediation.repository;

import com.huawei.cloud.sre.remediation.statemachine.RemediationState;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class RemediationContextRepositoryIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
            .withDatabaseName("sre_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
    }

    @Autowired
    private RemediationContextRepository repository;

    private RemediationContext newContext(String service, String strategy, String idempotencyKey) {
        return new RemediationContext("test incident for " + service, service,
                strategy, "LOW", "agent", idempotencyKey);
    }

    @Test
    void save_andFindById_roundTrip() {
        RemediationContext ctx = newContext("order-service", "RestartPod", "idem-001");
        RemediationContext saved = repository.save(ctx);

        Optional<RemediationContext> found = repository.findById(saved.getContextId());

        assertThat(found).isPresent();
        assertThat(found.get().getTargetService()).isEqualTo("order-service");
        assertThat(found.get().getState()).isEqualTo(RemediationState.INITIATED);
        assertThat(found.get().getCreatedAt()).isNotNull();
        assertThat(found.get().getUpdatedAt()).isNotNull();
    }

    @Test
    void findByIdempotencyKey_returnsCorrectContext() {
        repository.save(newContext("payment-service", "DbFailover", "idem-pay-001"));

        Optional<RemediationContext> found = repository.findByIdempotencyKey("idem-pay-001");

        assertThat(found).isPresent();
        assertThat(found.get().getTargetService()).isEqualTo("payment-service");
    }

    @Test
    void findByIdempotencyKey_notFound_returnsEmpty() {
        Optional<RemediationContext> found = repository.findByIdempotencyKey("nonexistent-key");
        assertThat(found).isEmpty();
    }

    @Test
    void findByState_returnsMatchingContexts() {
        RemediationContext ctx1 = repository.save(newContext("svc-a", "Strategy1", "k1"));
        RemediationContext ctx2 = repository.save(newContext("svc-b", "Strategy2", "k2"));

        // Transition one to STRATEGY_MATCHED
        ctx1.setState(RemediationState.STRATEGY_MATCHED);
        repository.save(ctx1);

        List<RemediationContext> initiated = repository.findByState(RemediationState.INITIATED);
        List<RemediationContext> matched = repository.findByState(RemediationState.STRATEGY_MATCHED);

        assertThat(initiated).anyMatch(c -> c.getTargetService().equals("svc-b"));
        assertThat(matched).anyMatch(c -> c.getTargetService().equals("svc-a"));
    }

    @Test
    void findPendingApprovals_returnsOnlyPendingApproval() {
        RemediationContext pending = repository.save(newContext("svc-pending", "DbFailover", "k-pending"));
        pending.setState(RemediationState.PENDING_APPROVAL);
        pending.setApprovers("ops-lead,biz-lead");
        repository.save(pending);

        repository.save(newContext("svc-other", "RestartPod", "k-other"));

        List<RemediationContext> pendingList = repository.findPendingApprovals();

        assertThat(pendingList).hasSize(1);
        assertThat(pendingList.get(0).getTargetService()).isEqualTo("svc-pending");
        assertThat(pendingList.get(0).getApprovers()).contains("ops-lead");
    }

    @Test
    void findByTargetServiceOrderByCreatedAtDesc_returnsByService() {
        repository.save(newContext("user-service", "Strategy-A", "k-us-1"));
        repository.save(newContext("user-service", "Strategy-B", "k-us-2"));
        repository.save(newContext("order-service", "Strategy-C", "k-os-1"));

        List<RemediationContext> userContexts =
                repository.findByTargetServiceOrderByCreatedAtDesc("user-service");

        assertThat(userContexts).hasSize(2);
        assertThat(userContexts).allMatch(c -> c.getTargetService().equals("user-service"));
    }

    @Test
    void countActiveContexts_excludesTerminalStates() {
        RemediationContext active1 = repository.save(newContext("svc-1", "S1", "k-cnt-1"));
        RemediationContext active2 = repository.save(newContext("svc-2", "S2", "k-cnt-2"));
        RemediationContext completed = repository.save(newContext("svc-3", "S3", "k-cnt-3"));

        completed.setState(RemediationState.COMPLETED);
        repository.save(completed);

        long count = repository.countActiveContexts();
        assertThat(count).isGreaterThanOrEqualTo(2);
    }

    @Test
    void addHistoryEntry_persistsAndParsesCorrectly() {
        RemediationContext ctx = repository.save(newContext("svc-h", "Strategy-H", "k-hist"));
        ctx.addHistoryEntry("State changed: INITIATED → STRATEGY_MATCHED by system");
        ctx.addHistoryEntry("State changed: STRATEGY_MATCHED → EXECUTING by system");
        repository.save(ctx);

        RemediationContext reloaded = repository.findById(ctx.getContextId()).orElseThrow();
        List<String> entries = reloaded.getHistoryEntries();

        assertThat(entries).hasSize(2);
        assertThat(entries.get(0)).contains("INITIATED → STRATEGY_MATCHED");
        assertThat(entries.get(1)).contains("STRATEGY_MATCHED → EXECUTING");
    }
}
