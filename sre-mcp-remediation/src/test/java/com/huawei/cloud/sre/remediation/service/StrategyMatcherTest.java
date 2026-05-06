package com.huawei.cloud.sre.remediation.service;

import com.huawei.cloud.sre.remediation.dto.RiskAssessment;
import com.huawei.cloud.sre.remediation.dto.StrategyMatch;
import com.huawei.cloud.sre.remediation.repository.SopStrategy;
import com.huawei.cloud.sre.remediation.repository.SopStrategyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StrategyMatcherTest {

    @Mock
    private SopStrategyRepository strategyRepository;

    private StrategyMatcher strategyMatcher;

    private SopStrategy restartStrategy;
    private SopStrategy dbStrategy;

    @BeforeEach
    void setUp() {
        strategyMatcher = new StrategyMatcher(strategyRepository);

        restartStrategy = new SopStrategy(
                "RestartPod-OOM", "Restart pod on OOM or crash",
                "oom,crash,restart,memory", "*",
                "LOW", "restartPod", "1. Identify pod\n2. kubectl delete pod",
                5, 1
        );

        dbStrategy = new SopStrategy(
                "DBFailover", "Perform database failover",
                "database,failover,replication,replica", "db,rds",
                "HIGH", "dbFailover", "1. Check lag\n2. Promote replica",
                30, 2
        );

        lenient().when(strategyRepository.searchByKeyword(anyString())).thenReturn(List.of());
    }

    @Test
    void matchStrategy_matchesOomSymptom() {
        when(strategyRepository.searchByKeyword("crash")).thenReturn(List.of(restartStrategy));

        StrategyMatch result = strategyMatcher.matchStrategy("pod crash due to oom", "order-service");

        assertThat(result.matched()).isTrue();
        assertThat(result.strategyName()).isEqualTo("RestartPod-OOM");
        assertThat(result.riskLevel()).isEqualTo("LOW");
        assertThat(result.toolName()).isEqualTo("restartPod");
    }

    @Test
    void matchStrategy_noMatch_returnsNoMatchResult() {
        StrategyMatch result = strategyMatcher.matchStrategy("unknown symptom xyz", "service");

        assertThat(result.matched()).isFalse();
        assertThat(result.strategyId()).isNull();
        assertThat(result.description()).contains("No matching SOP");
    }

    @Test
    void matchStrategy_multipleMatches_returnsBestByPriority() {
        when(strategyRepository.searchByKeyword("crash")).thenReturn(List.of(restartStrategy));
        when(strategyRepository.searchByKeyword("database")).thenReturn(List.of(dbStrategy));

        StrategyMatch result = strategyMatcher.matchStrategy("pod crash and database failover needed", "svc");

        assertThat(result.matched()).isTrue();
        // restartStrategy has priority 1 (lower = better), dbStrategy has priority 2
        assertThat(result.strategyName()).isEqualTo("RestartPod-OOM");
        assertThat(result.alternatives()).contains("DBFailover");
    }

    @Test
    void matchStrategy_shortWords_areIgnored() {
        // "pod" has 3 chars, "oom" has 3 chars — both skipped (< 4)
        StrategyMatch result = strategyMatcher.matchStrategy("pod oom", "service");
        assertThat(result.matched()).isFalse();
    }

    @Test
    void assessRisk_restartPod_isLow() {
        RiskAssessment assessment = strategyMatcher.assessRisk("restartPod", "order-service", "prod");

        assertThat(assessment.riskLevel()).isEqualTo("LOW");
        assertThat(assessment.requiresApproval()).isFalse();
        assertThat(assessment.approvalType()).isEqualTo("NONE");
        assertThat(assessment.rollbackPossible()).isTrue();
    }

    @Test
    void assessRisk_dbFailover_isHigh() {
        RiskAssessment assessment = strategyMatcher.assessRisk("dbFailover", "payment-db", "prod");

        assertThat(assessment.riskLevel()).isEqualTo("HIGH");
        assertThat(assessment.requiresApproval()).isTrue();
        assertThat(assessment.approvalType()).isEqualTo("DUAL");
        assertThat(assessment.rollbackPossible()).isFalse();
    }

    @Test
    void assessRisk_switchTraffic_isMedium() {
        RiskAssessment assessment = strategyMatcher.assessRisk("switchTraffic", "api-gw", null);

        assertThat(assessment.riskLevel()).isEqualTo("MEDIUM");
        assertThat(assessment.requiresApproval()).isTrue();
        assertThat(assessment.approvalType()).isEqualTo("SINGLE");
    }

    @Test
    void assessRisk_productionContext_increasesScore() {
        RiskAssessment low = strategyMatcher.assessRisk("restartPod", "svc", null);
        RiskAssessment prod = strategyMatcher.assessRisk("restartPod", "svc", "production environment");

        assertThat(prod.riskScore()).isGreaterThan(low.riskScore());
    }

    @Test
    void assessRisk_dbService_increasesScore() {
        RiskAssessment base = strategyMatcher.assessRisk("switchTraffic", "api-gateway", null);
        RiskAssessment dbSvc = strategyMatcher.assessRisk("switchTraffic", "payment-db", null);

        assertThat(dbSvc.riskScore()).isGreaterThan(base.riskScore());
    }

    @Test
    void assessRisk_unknownAction_defaultsMedium() {
        RiskAssessment assessment = strategyMatcher.assessRisk("someUnknownAction", "svc", null);
        assertThat(assessment.riskLevel()).isEqualTo("MEDIUM");
    }

    @Test
    void determineRiskLevel_allKnownActions() {
        assertThat(StrategyMatcher.determineRiskLevel("restartPod")).isEqualTo("LOW");
        assertThat(StrategyMatcher.determineRiskLevel("scaleDeployment")).isEqualTo("LOW");
        assertThat(StrategyMatcher.determineRiskLevel("renewCertificate")).isEqualTo("LOW");
        assertThat(StrategyMatcher.determineRiskLevel("cleanDisk")).isEqualTo("MEDIUM");
        assertThat(StrategyMatcher.determineRiskLevel("adjustConnectionPool")).isEqualTo("MEDIUM");
        assertThat(StrategyMatcher.determineRiskLevel("switchTraffic")).isEqualTo("MEDIUM");
        assertThat(StrategyMatcher.determineRiskLevel("replaceNode")).isEqualTo("HIGH");
        assertThat(StrategyMatcher.determineRiskLevel("dbFailover")).isEqualTo("HIGH");
        assertThat(StrategyMatcher.determineRiskLevel("rollbackRelease")).isEqualTo("HIGH");
    }
}
