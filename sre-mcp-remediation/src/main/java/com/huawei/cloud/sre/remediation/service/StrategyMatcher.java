package com.huawei.cloud.sre.remediation.service;

import com.huawei.cloud.sre.remediation.dto.RiskAssessment;
import com.huawei.cloud.sre.remediation.dto.StrategyMatch;
import com.huawei.cloud.sre.remediation.repository.SopStrategy;
import com.huawei.cloud.sre.remediation.repository.SopStrategyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * SOP 策略匹配服务。
 *
 * <p>根据故障症状描述在策略库中搜索最合适的 SOP，
 * 并依据风险级别生成风险评估报告。
 */
@Service
public class StrategyMatcher {

    private static final Logger log = LoggerFactory.getLogger(StrategyMatcher.class);

    private final SopStrategyRepository strategyRepository;

    /**
     * @param strategyRepository SOP 策略库仓库
     */
    public StrategyMatcher(SopStrategyRepository strategyRepository) {
        this.strategyRepository = strategyRepository;
    }

    /**
     * 根据症状描述匹配最优 SOP 策略。
     *
     * @param symptomDescription 故障症状描述
     * @param targetService      目标服务名（可选，用于过滤）
     * @return 匹配结果（含策略详情或无匹配说明）
     */
    public StrategyMatch matchStrategy(String symptomDescription, String targetService) {
        log.info("StrategyMatcher.matchStrategy symptom={} service={}", symptomDescription, targetService);

        List<SopStrategy> candidates = new ArrayList<>();

        // First: keyword-based DB search
        String[] words = symptomDescription.toLowerCase().split("\\s+");
        for (String word : words) {
            if (word.length() >= 4) {
                candidates.addAll(strategyRepository.searchByKeyword(word));
            }
        }

        // Filter by in-memory match (full keyword check)
        List<SopStrategy> matched = candidates.stream()
                .filter(s -> s.matches(symptomDescription))
                .distinct()
                .sorted((a, b) -> Integer.compare(a.getPriority(), b.getPriority()))
                .toList();

        if (matched.isEmpty()) {
            log.warn("No SOP strategy found for symptom={}", symptomDescription);
            return StrategyMatch.noMatch();
        }

        SopStrategy best = matched.get(0);
        List<String> alternatives = matched.stream().skip(1)
                .limit(3).map(SopStrategy::getName).toList();

        log.info("StrategyMatcher.matchStrategy found strategy={} risk={}", best.getName(), best.getRiskLevel());
        return new StrategyMatch(
                true, best.getStrategyId(), best.getName(), best.getDescription(),
                best.getRiskLevel(), best.getToolName(), best.getExecutionSteps(),
                best.getEstimatedMinutes(), alternatives
        );
    }

    /**
     * 根据操作名称和目标服务评估风险。
     *
     * @param action        操作名称（如 restartPod、dbFailover）
     * @param targetService 目标服务名
     * @param context       额外上下文（如环境、时间段）
     * @return 风险评估结果
     */
    public RiskAssessment assessRisk(String action, String targetService, String context) {
        log.info("StrategyMatcher.assessRisk action={} service={}", action, targetService);

        String riskLevel = determineRiskLevel(action);
        int riskScore = computeRiskScore(action, targetService, context);
        List<String> factors = buildRiskFactors(action, targetService);
        List<String> mitigations = buildMitigations(action);
        boolean requiresApproval = !"LOW".equals(riskLevel);
        String approvalType = switch (riskLevel) {
            case "HIGH" -> "DUAL";
            case "MEDIUM" -> "SINGLE";
            default -> "NONE";
        };
        String impact = estimateImpact(action, targetService);

        return new RiskAssessment(action, riskLevel, riskScore, factors, mitigations,
                requiresApproval, approvalType, impact, isRollbackPossible(action));
    }

    // ── Risk classification ────────────────────────────────────────────────────

    static String determineRiskLevel(String action) {
        return switch (action.toLowerCase()) {
            case "restartpod", "scaledeployment", "renewcertificate" -> "LOW";
            case "cleandisk", "adjustconnectionpool", "switchtraffic" -> "MEDIUM";
            case "replacenode", "dbfailover", "rollbackrelease" -> "HIGH";
            default -> "MEDIUM";
        };
    }

    private int computeRiskScore(String action, String service, String context) {
        int base = switch (determineRiskLevel(action)) {
            case "LOW" -> 20;
            case "MEDIUM" -> 50;
            case "HIGH" -> 80;
            default -> 50;
        };
        // Production penalty
        if (context != null && (context.contains("prod") || context.contains("production"))) {
            base += 10;
        }
        // Database penalty
        if (service != null && service.toLowerCase().contains("db")) {
            base += 5;
        }
        return Math.min(100, base);
    }

    private List<String> buildRiskFactors(String action, String service) {
        List<String> factors = new ArrayList<>();
        String level = determineRiskLevel(action);
        if ("HIGH".equals(level)) {
            factors.add("High-impact operation affecting production traffic");
            factors.add("Potential data loss or service unavailability");
        } else if ("MEDIUM".equals(level)) {
            factors.add("Moderate impact on service performance");
            factors.add("Requires coordination with dependent services");
        } else {
            factors.add("Low impact, self-healing operation");
        }
        if (service != null && service.toLowerCase().contains("payment")) {
            factors.add("Financial critical service — extra caution required");
        }
        return factors;
    }

    private List<String> buildMitigations(String action) {
        return switch (action.toLowerCase()) {
            case "dbfailover" -> List.of("Verify replication lag < 10s", "Notify application teams", "Test failover in staging first");
            case "rollbackrelease" -> List.of("Confirm rollback target version", "Check database schema compatibility", "Alert on-call team");
            case "replacenode" -> List.of("Drain node traffic first", "Ensure cluster has capacity", "Monitor pod rescheduling");
            default -> List.of("Monitor service metrics post-execution", "Have rollback plan ready");
        };
    }

    private String estimateImpact(String action, String service) {
        return switch (action.toLowerCase()) {
            case "restartpod" -> "Brief request interruption (< 30s) for " + service;
            case "scaledeployment" -> "Increased resource usage, no user impact for " + service;
            case "dbfailover" -> "DB connection reset (~60s downtime) for all clients of " + service;
            case "rollbackrelease" -> "Feature regression to previous version for " + service;
            default -> "Moderate impact on " + service;
        };
    }

    private boolean isRollbackPossible(String action) {
        return switch (action.toLowerCase()) {
            case "dbfailover", "replacenode" -> false;
            default -> true;
        };
    }
}
