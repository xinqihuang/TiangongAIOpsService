package com.huawei.cloud.sre.rca.repository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RCA 事故仓库集成测试（Testcontainers PostgreSQL）。
 */
@DataJpaTest
@Testcontainers
class RcaIncidentRepositoryIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
            .withDatabaseName("sre_mcp_test")
            .withUsername("sre")
            .withPassword("test");

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private RcaIncidentRepository repository;

    @Autowired
    private TestEntityManager entityManager;

    @Test
    void save_andFindById_works() {
        var entity = new RcaIncidentEntity(
                "user-service", "High error rate on /login",
                "DB connection pool exhausted", "HIGH",
                "user-db", 0.85, "{\"rootCause\":\"db exhausted\"}");

        var saved = repository.save(entity);
        entityManager.flush();
        entityManager.clear();

        var found = repository.findById(saved.getId());
        assertThat(found).isPresent();
        assertThat(found.get().getService()).isEqualTo("user-service");
        assertThat(found.get().getSeverity()).isEqualTo("HIGH");
        assertThat(found.get().getConfidence()).isEqualTo(0.85);
        assertThat(found.get().getCreatedAt()).isNotNull();
    }

    @Test
    void findBySeverityAndCreatedAtBetween_returnsMatchingEntities() {
        Instant now = Instant.now();
        var entity1 = new RcaIncidentEntity("svc-a", "Incident 1", "Root 1", "HIGH", "comp-a", 0.9, "{}");
        var entity2 = new RcaIncidentEntity("svc-b", "Incident 2", "Root 2", "LOW", "comp-b", 0.5, "{}");
        repository.saveAll(List.of(entity1, entity2));
        entityManager.flush();

        List<RcaIncidentEntity> highSeverity = repository.findBySeverityAndCreatedAtBetween(
                "HIGH", now.minusSeconds(60), now.plusSeconds(60));

        assertThat(highSeverity).hasSize(1);
        assertThat(highSeverity.get(0).getSeverity()).isEqualTo("HIGH");
    }

    @Test
    void findByRootCauseComponentOrderByCreatedAtDesc_returnsInOrder() {
        var e1 = new RcaIncidentEntity("svc", "Title 1", "cause", "HIGH", "db-service", 0.8, "{}");
        var e2 = new RcaIncidentEntity("svc", "Title 2", "cause 2", "MEDIUM", "db-service", 0.7, "{}");
        repository.saveAll(List.of(e1, e2));
        entityManager.flush();

        List<RcaIncidentEntity> results = repository.findByRootCauseComponentOrderByCreatedAtDesc("db-service");
        assertThat(results).hasSizeGreaterThanOrEqualTo(2);
    }
}
