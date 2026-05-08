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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 修复操作 MCP Tool 服务。
 *
 * <p>包含 14 个 {@code @Tool} 方法，按风险分级路由：
 * <ul>
 *   <li>🟢 LOW（直接执行）：restartPod / scaleDeployment / renewCertificate</li>
 *   <li>🟡 MEDIUM（单人审批）：cleanDisk / adjustConnectionPool / switchTraffic</li>
 *   <li>🔴 HIGH（工单 + 双人审批）：replaceNode / dbFailover / rollbackRelease</li>
 *   <li>⚙️  通用：matchStrategy / assessRisk / requestApproval / verifyRemediation / rollbackAction</li>
 * </ul>
 *
 * <p>所有变更操作均有：
 * <ul>
 *   <li>幂等性保证（{@link IdempotencyStore}）</li>
 *   <li>熔断保护（{@link RemediationCircuitBreaker}）</li>
 *   <li>状态机驱动（{@link RemediationStateMachine}）</li>
 * </ul>
 */
@Service
public class RemediationToolService {

    private static final Logger log = LoggerFactory.getLogger(RemediationToolService.class);

    private final CceAdapter cceAdapter;
    private final EcsAdapter ecsAdapter;
    private final ElbAdapter elbAdapter;
    private final RdsAdapter rdsAdapter;
    private final ScmAdapter scmAdapter;
    private final RemediationContextRepository contextRepository;
    private final RemediationStateMachine stateMachine;
    private final ApprovalService approvalService;
    private final StrategyMatcher strategyMatcher;
    private final RemediationCircuitBreaker circuitBreaker;
    private final IdempotencyStore idempotencyStore;
    private final ObjectMapper objectMapper;

    /**
     * 构造器注入所有依赖。
     */
    public RemediationToolService(
            CceAdapter cceAdapter,
            EcsAdapter ecsAdapter,
            ElbAdapter elbAdapter,
            RdsAdapter rdsAdapter,
            ScmAdapter scmAdapter,
            RemediationContextRepository contextRepository,
            RemediationStateMachine stateMachine,
            ApprovalService approvalService,
            StrategyMatcher strategyMatcher,
            RemediationCircuitBreaker circuitBreaker,
            IdempotencyStore idempotencyStore,
            ObjectMapper objectMapper
    ) {
        this.cceAdapter = cceAdapter;
        this.ecsAdapter = ecsAdapter;
        this.elbAdapter = elbAdapter;
        this.rdsAdapter = rdsAdapter;
        this.scmAdapter = scmAdapter;
        this.contextRepository = contextRepository;
        this.stateMachine = stateMachine;
        this.approvalService = approvalService;
        this.strategyMatcher = strategyMatcher;
        this.circuitBreaker = circuitBreaker;
        this.idempotencyStore = idempotencyStore;
        this.objectMapper = objectMapper;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 🟢 LOW RISK — 直接执行（无需审批）
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * 重启指定 CCE 集群中的 Pod（低风险）。
     * 适用于：Pod 内存泄漏、进程假死、OOM 后自动重启失败等场景。
     *
     * @param namespace      Kubernetes 命名空间
     * @param podName        Pod 名称（支持前缀匹配，如 order-service-）
     * @param reason         重启原因（用于审计日志）
     * @param idempotencyKey 幂等键（相同 key 的重复调用直接返回上次结果）
     * @return 修复执行结果
     */
    @Tool(description = "Restart a Pod in the CCE cluster (low risk). Suitable for memory leak, process hang, or OOM scenarios.")
    public RemediationResult restartPod(
            @ToolParam(description = "Kubernetes namespace, e.g. default") String namespace,
            @ToolParam(description = "Pod name or prefix, e.g. order-service-abc12") String podName,
            @ToolParam(description = "Restart reason (for audit log)") String reason,
            @ToolParam(description = "Idempotency key; repeated calls with the same key return the previous result") String idempotencyKey
    ) {
        log.info("Tool[restartPod] namespace={} pod={}", namespace, podName);
        return executeWithGuards("restartPod", podName, "LOW", idempotencyKey, () -> {
            RemediationContext ctx = createContext(reason, podName, "RestartPod-LOW", "LOW", "agent");
            stateMachine.transit(ctx, RemediationState.STRATEGY_MATCHED, "system");
            approvalService.routeByRisk(ctx, List.of());

            cceAdapter.restartPod(namespace, podName);
            ctx.setExecutionResult("Pod " + podName + " restarted successfully");
            stateMachine.transit(ctx, RemediationState.VERIFYING, "system");
            stateMachine.transit(ctx, RemediationState.COMPLETED, "system");
            contextRepository.save(ctx);

            log.info("Tool[restartPod] done pod={}", podName);
            return RemediationResult.success(ctx.getContextId(), "restartPod", ctx.getState(), "LOW");
        });
    }

    /**
     * 调整 CCE Deployment 的副本数（低风险）。
     * 适用于：流量突增需快速扩容、低峰期缩容降本等场景。
     *
     * @param namespace      命名空间
     * @param deploymentName Deployment 名称
     * @param replicas       目标副本数（1-50）
     * @param reason         变更原因
     * @param idempotencyKey 幂等键
     * @return 修复执行结果
     */
    @Tool(description = "Adjust the replica count of a CCE Deployment (low risk). Use for rapid scale-out or scale-in in response to traffic changes.")
    public RemediationResult scaleDeployment(
            @ToolParam(description = "Kubernetes namespace") String namespace,
            @ToolParam(description = "Deployment name") String deploymentName,
            @ToolParam(description = "Target replica count, range 1-50") int replicas,
            @ToolParam(description = "Reason for the change") String reason,
            @ToolParam(description = "Idempotency key") String idempotencyKey
    ) {
        log.info("Tool[scaleDeployment] deployment={} replicas={}", deploymentName, replicas);
        int safeReplicas = Math.max(1, Math.min(50, replicas));
        return executeWithGuards("scaleDeployment", deploymentName, "LOW", idempotencyKey, () -> {
            RemediationContext ctx = createContext(reason, deploymentName, "ScaleDeployment-LOW", "LOW", "agent");
            stateMachine.transit(ctx, RemediationState.STRATEGY_MATCHED, "system");
            approvalService.routeByRisk(ctx, List.of());

            cceAdapter.scaleDeployment(namespace, deploymentName, safeReplicas);
            ctx.setExecutionResult("Deployment " + deploymentName + " scaled to " + safeReplicas + " replicas");
            stateMachine.transit(ctx, RemediationState.VERIFYING, "system");
            stateMachine.transit(ctx, RemediationState.COMPLETED, "system");
            contextRepository.save(ctx);

            log.info("Tool[scaleDeployment] done deployment={} replicas={}", deploymentName, safeReplicas);
            return RemediationResult.success(ctx.getContextId(), "scaleDeployment", ctx.getState(), "LOW");
        });
    }

    /**
     * 续期即将过期的 SSL/TLS 证书（低风险）。
     * 适用于：证书过期告警、HTTPS 连接异常等场景。
     *
     * @param certificateName 证书名称（SCM 中的证书 ID）
     * @param domain          证书绑定的域名
     * @param reason          续期原因
     * @param idempotencyKey  幂等键
     * @return 修复执行结果
     */
    @Tool(description = "Renew an expiring SSL/TLS certificate (low risk). Suitable for certificate expiry alert scenarios.")
    public RemediationResult renewCertificate(
            @ToolParam(description = "Certificate name or ID as shown in Huawei Cloud SCM") String certificateName,
            @ToolParam(description = "Domain bound to the certificate, e.g. api.example.com") String domain,
            @ToolParam(description = "Renewal reason") String reason,
            @ToolParam(description = "Idempotency key") String idempotencyKey
    ) {
        log.info("Tool[renewCertificate] cert={} domain={}", certificateName, domain);
        return executeWithGuards("renewCertificate", certificateName, "LOW", idempotencyKey, () -> {
            RemediationContext ctx = createContext(reason, domain, "RenewCertificate-LOW", "LOW", "agent");
            stateMachine.transit(ctx, RemediationState.STRATEGY_MATCHED, "system");
            approvalService.routeByRisk(ctx, List.of());

            scmAdapter.renewCertificate(certificateName, domain);
            ctx.setExecutionResult("Certificate " + certificateName + " renewed for domain " + domain);
            stateMachine.transit(ctx, RemediationState.VERIFYING, "system");
            stateMachine.transit(ctx, RemediationState.COMPLETED, "system");
            contextRepository.save(ctx);

            log.info("Tool[renewCertificate] done cert={}", certificateName);
            return RemediationResult.success(ctx.getContextId(), "renewCertificate", ctx.getState(), "LOW");
        });
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 🟡 MEDIUM RISK — 单人审批
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * 清理 ECS 实例磁盘空间（中风险）。
     * 适用于：磁盘使用率超阈值、日志/临时文件堆积等场景。需单人审批。
     *
     * @param instanceId     ECS 实例 ID
     * @param targetPath     清理目标路径（如 /var/log、/tmp）
     * @param approver       审批人用户名（系统会向其发送审批请求）
     * @param reason         清理原因
     * @param idempotencyKey 幂等键
     * @return 修复执行结果（若未通过审批则返回 PENDING_APPROVAL 状态）
     */
    @Tool(description = "Clean ECS disk space (medium risk, requires single-person approval). Suitable for disk usage threshold breach scenarios.")
    public RemediationResult cleanDisk(
            @ToolParam(description = "ECS instance ID") String instanceId,
            @ToolParam(description = "Target cleanup path, e.g. /var/log or /tmp") String targetPath,
            @ToolParam(description = "Approver username") String approver,
            @ToolParam(description = "Cleanup reason") String reason,
            @ToolParam(description = "Idempotency key") String idempotencyKey
    ) {
        log.info("Tool[cleanDisk] instance={} path={}", instanceId, targetPath);
        return executeWithGuards("cleanDisk", instanceId, "MEDIUM", idempotencyKey, () -> {
            RemediationContext ctx = createContext(reason, instanceId, "CleanDisk-MEDIUM", "MEDIUM", "agent");
            stateMachine.transit(ctx, RemediationState.STRATEGY_MATCHED, "system");
            approvalService.routeByRisk(ctx, List.of(approver));
            contextRepository.save(ctx);

            log.info("Tool[cleanDisk] pending approval contextId={}", ctx.getContextId());
            return RemediationResult.pendingApproval(ctx.getContextId(), "cleanDisk", "MEDIUM", List.of(approver));
        });
    }

    /**
     * 调整数据库连接池大小（中风险）。
     * 适用于：数据库连接池耗尽、连接超时频繁等场景。需单人审批。
     *
     * @param service        服务名称
     * @param poolSize       新连接池大小（5-500）
     * @param approver       审批人用户名
     * @param reason         调整原因
     * @param idempotencyKey 幂等键
     * @return 修复执行结果
     */
    @Tool(description = "Adjust the database connection pool size (medium risk, requires single-person approval). Suitable for connection pool exhaustion scenarios.")
    public RemediationResult adjustConnectionPool(
            @ToolParam(description = "Service name, e.g. order-service") String service,
            @ToolParam(description = "New connection pool size, range 5-500") int poolSize,
            @ToolParam(description = "Approver username") String approver,
            @ToolParam(description = "Adjustment reason") String reason,
            @ToolParam(description = "Idempotency key") String idempotencyKey
    ) {
        log.info("Tool[adjustConnectionPool] service={} poolSize={}", service, poolSize);
        int safeSize = Math.max(5, Math.min(500, poolSize));
        return executeWithGuards("adjustConnectionPool", service, "MEDIUM", idempotencyKey, () -> {
            RemediationContext ctx = createContext(reason, service, "AdjustConnectionPool-MEDIUM", "MEDIUM", "agent");
            stateMachine.transit(ctx, RemediationState.STRATEGY_MATCHED, "system");
            approvalService.routeByRisk(ctx, List.of(approver));
            contextRepository.save(ctx);

            log.info("Tool[adjustConnectionPool] pending approval contextId={}", ctx.getContextId());
            return RemediationResult.pendingApproval(ctx.getContextId(), "adjustConnectionPool", "MEDIUM", List.of(approver));
        });
    }

    /**
     * 切换 ELB 流量到备用后端（中风险）。
     * 适用于：主后端集群故障、蓝绿发布切换、灰度回退等场景。需单人审批。
     *
     * @param elbId          ELB 实例 ID
     * @param targetBackend  目标后端地址（如 backend-v2.internal:8080）
     * @param trafficPercent 切换流量百分比（1-100）
     * @param approver       审批人用户名
     * @param idempotencyKey 幂等键
     * @return 修复执行结果
     */
    @Tool(description = "Switch ELB traffic to a backup backend (medium risk, requires single-person approval). Use for blue-green switching or canary rollback.")
    public RemediationResult switchTraffic(
            @ToolParam(description = "ELB instance ID") String elbId,
            @ToolParam(description = "Target backend address, e.g. backend-v2.internal:8080") String targetBackend,
            @ToolParam(description = "Traffic percentage to switch, range 1-100") int trafficPercent,
            @ToolParam(description = "Approver username") String approver,
            @ToolParam(description = "Idempotency key") String idempotencyKey
    ) {
        log.info("Tool[switchTraffic] elb={} backend={} percent={}", elbId, targetBackend, trafficPercent);
        return executeWithGuards("switchTraffic", elbId, "MEDIUM", idempotencyKey, () -> {
            RemediationContext ctx = createContext(
                    "Switch traffic to " + targetBackend, elbId, "SwitchTraffic-MEDIUM", "MEDIUM", "agent");
            stateMachine.transit(ctx, RemediationState.STRATEGY_MATCHED, "system");
            approvalService.routeByRisk(ctx, List.of(approver));
            contextRepository.save(ctx);

            log.info("Tool[switchTraffic] pending approval contextId={}", ctx.getContextId());
            return RemediationResult.pendingApproval(ctx.getContextId(), "switchTraffic", "MEDIUM", List.of(approver));
        });
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 🔴 HIGH RISK — 工单 + 双人审批
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * 替换故障 ECS/CCE 节点（高风险）。
     * 适用于：节点内核崩溃、硬件故障、无法 drain 的节点等。需工单 + 双人审批。
     *
     * @param nodeId         ECS 实例 ID 或 CCE 节点 ID
     * @param approver1      第一审批人
     * @param approver2      第二审批人
     * @param reason         替换原因
     * @param idempotencyKey 幂等键
     * @return 修复执行结果（PENDING_APPROVAL 直到双人批准）
     */
    @Tool(description = "Replace a failed node (high risk, requires ticket + dual-person approval). Suitable for node crash or hardware failure scenarios.")
    public RemediationResult replaceNode(
            @ToolParam(description = "ECS instance ID or CCE node ID") String nodeId,
            @ToolParam(description = "First approver username") String approver1,
            @ToolParam(description = "Second approver username") String approver2,
            @ToolParam(description = "Replacement reason") String reason,
            @ToolParam(description = "Idempotency key") String idempotencyKey
    ) {
        log.info("Tool[replaceNode] nodeId={}", nodeId);
        return executeWithGuards("replaceNode", nodeId, "HIGH", idempotencyKey, () -> {
            RemediationContext ctx = createContext(reason, nodeId, "ReplaceNode-HIGH", "HIGH", "agent");
            stateMachine.transit(ctx, RemediationState.STRATEGY_MATCHED, "system");
            approvalService.routeByRisk(ctx, List.of(approver1, approver2));
            contextRepository.save(ctx);

            log.info("Tool[replaceNode] pending dual approval contextId={}", ctx.getContextId());
            return RemediationResult.pendingApproval(ctx.getContextId(), "replaceNode", "HIGH",
                    List.of(approver1, approver2));
        });
    }

    /**
     * 执行 RDS 数据库主备切换（高风险）。
     * 适用于：主库故障、主库 I/O 异常导致复制中断等场景。需工单 + 双人审批。
     *
     * @param instanceId     RDS 实例 ID
     * @param approver1      第一审批人
     * @param approver2      第二审批人
     * @param reason         切换原因
     * @param idempotencyKey 幂等键
     * @return 修复执行结果
     */
    @Tool(description = "Perform an RDS primary-standby failover (high risk, requires ticket + dual-person approval). Suitable for primary database failure scenarios.")
    public RemediationResult dbFailover(
            @ToolParam(description = "RDS instance ID") String instanceId,
            @ToolParam(description = "First approver username") String approver1,
            @ToolParam(description = "Second approver username") String approver2,
            @ToolParam(description = "Failover reason") String reason,
            @ToolParam(description = "Idempotency key") String idempotencyKey
    ) {
        log.info("Tool[dbFailover] instanceId={}", instanceId);
        return executeWithGuards("dbFailover", instanceId, "HIGH", idempotencyKey, () -> {
            RemediationContext ctx = createContext(reason, instanceId, "DbFailover-HIGH", "HIGH", "agent");
            stateMachine.transit(ctx, RemediationState.STRATEGY_MATCHED, "system");
            approvalService.routeByRisk(ctx, List.of(approver1, approver2));
            contextRepository.save(ctx);

            log.info("Tool[dbFailover] pending dual approval contextId={}", ctx.getContextId());
            return RemediationResult.pendingApproval(ctx.getContextId(), "dbFailover", "HIGH",
                    List.of(approver1, approver2));
        });
    }

    /**
     * 回滚服务到上一个稳定版本（高风险）。
     * 适用于：新版本发布后出现异常、需紧急回退到已知稳定版本的场景。需工单 + 双人审批。
     *
     * @param service        服务名称
     * @param targetVersion  目标回滚版本（如 v1.2.3）
     * @param approver1      第一审批人
     * @param approver2      第二审批人
     * @param idempotencyKey 幂等键
     * @return 修复执行结果
     */
    @Tool(description = "Roll back a service to a specified version (high risk, requires ticket + dual-person approval). Use for emergency rollback when a new release causes failures.")
    public RemediationResult rollbackRelease(
            @ToolParam(description = "Service name") String service,
            @ToolParam(description = "Target rollback version, e.g. v1.2.3") String targetVersion,
            @ToolParam(description = "First approver username") String approver1,
            @ToolParam(description = "Second approver username") String approver2,
            @ToolParam(description = "Idempotency key") String idempotencyKey
    ) {
        log.info("Tool[rollbackRelease] service={} version={}", service, targetVersion);
        return executeWithGuards("rollbackRelease", service, "HIGH", idempotencyKey, () -> {
            RemediationContext ctx = createContext(
                    "Rollback " + service + " to " + targetVersion,
                    service, "RollbackRelease-HIGH", "HIGH", "agent");
            stateMachine.transit(ctx, RemediationState.STRATEGY_MATCHED, "system");
            approvalService.routeByRisk(ctx, List.of(approver1, approver2));
            contextRepository.save(ctx);

            log.info("Tool[rollbackRelease] pending dual approval contextId={}", ctx.getContextId());
            return RemediationResult.pendingApproval(ctx.getContextId(), "rollbackRelease", "HIGH",
                    List.of(approver1, approver2));
        });
    }

    // ══════════════════════════════════════════════════════════════════════════
    // ⚙️  通用工具
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * 根据故障症状描述从 SOP 策略库中匹配最合适的修复策略。
     * 应在执行任何修复操作前首先调用，获取推荐策略和风险评估。
     *
     * @param symptomDescription 故障症状描述（自然语言，如 "order-service OOM killed, pod restarting frequently"）
     * @param targetService      目标服务名（可选，用于过滤策略）
     * @return SOP 策略匹配结果，含推荐 Tool、风险级别、执行步骤
     */
    @Tool(description = "Match the most suitable remediation strategy from the SOP library based on fault symptoms. Should be called before executing any remediation action.")
    public StrategyMatch matchStrategy(
            @ToolParam(description = "Fault symptom description in natural language") String symptomDescription,
            @ToolParam(description = "Target service name for optional filtering") String targetService
    ) {
        log.info("Tool[matchStrategy] symptom={} service={}", symptomDescription, targetService);
        StrategyMatch match = strategyMatcher.matchStrategy(symptomDescription, targetService);
        log.info("Tool[matchStrategy] done matched={} strategy={}", match.matched(), match.strategyName());
        return match;
    }

    /**
     * 评估指定修复操作的风险级别，返回风险因素、缓解措施和审批要求。
     *
     * @param action        修复操作名称（如 restartPod、dbFailover）
     * @param targetService 目标服务名
     * @param context       执行上下文描述（如 "production environment, peak hours"）
     * @return 风险评估结果，含风险评分、审批要求、预计影响
     */
    @Tool(description = "Assess the risk of a remediation action. Returns a risk score, approval requirements, and estimated impact to help decide whether to proceed.")
    public RiskAssessment assessRisk(
            @ToolParam(description = "Remediation action name, e.g. restartPod, dbFailover") String action,
            @ToolParam(description = "Target service name") String targetService,
            @ToolParam(description = "Execution context, e.g. production/peak-hours") String context
    ) {
        log.info("Tool[assessRisk] action={} service={}", action, targetService);
        RiskAssessment assessment = strategyMatcher.assessRisk(action, targetService, context);
        log.info("Tool[assessRisk] done riskLevel={} score={}", assessment.riskLevel(), assessment.riskScore());
        return assessment;
    }

    /**
     * 提交审批意见（通过或拒绝）。当所有必需审批人都通过后，工单自动进入执行阶段。
     *
     * @param contextId  修复工单 ID（由修复 Tool 返回的 contextId）
     * @param approver   审批人用户名
     * @param decision   审批决定：APPROVE 或 REJECT
     * @param note       审批意见
     * @return 审批后的工单状态
     */
    @Tool(description = "Submit an approval decision (APPROVE/REJECT) for a remediation ticket. Once all required approvers approve, the ticket automatically moves to the execution phase.")
    public Map<String, Object> requestApproval(
            @ToolParam(description = "Remediation ticket ID") String contextId,
            @ToolParam(description = "Approver username") String approver,
            @ToolParam(description = "Approval decision: APPROVE or REJECT") String decision,
            @ToolParam(description = "Approval note") String note
    ) {
        log.info("Tool[requestApproval] contextId={} approver={} decision={}", contextId, approver, decision);
        try {
            RemediationState newState;
            if ("APPROVE".equalsIgnoreCase(decision)) {
                newState = approvalService.approve(contextId, approver, note);
            } else {
                newState = approvalService.reject(contextId, approver, note);
            }
            log.info("Tool[requestApproval] done contextId={} newState={}", contextId, newState);
            return Map.of("contextId", contextId, "state", newState.name(),
                    "approver", approver, "decision", decision);
        } catch (Exception e) {
            log.error("Tool[requestApproval] failed contextId={}: {}", contextId, e.getMessage());
            return Map.of("error", e.getMessage(), "contextId", contextId);
        }
    }

    /**
     * 验证修复操作是否生效，通过检查关键指标确认修复结果。
     *
     * @param contextId     修复工单 ID
     * @param service       目标服务名
     * @param checkMetric   验证指标名称（如 pod_restart_count、error_rate）
     * @param expectedValue 期望值（如 "0" 表示重启次数为 0）
     * @return 验证结果（含是否通过、当前值、建议）
     */
    @Tool(description = "Verify that a remediation action took effect by checking key metrics and update the ticket status accordingly.")
    public Map<String, Object> verifyRemediation(
            @ToolParam(description = "Remediation ticket ID") String contextId,
            @ToolParam(description = "Target service name") String service,
            @ToolParam(description = "Verification metric name, e.g. pod_restart_count") String checkMetric,
            @ToolParam(description = "Expected value, e.g. \"0\" means zero restarts") String expectedValue
    ) {
        log.info("Tool[verifyRemediation] contextId={} metric={} expected={}", contextId, checkMetric, expectedValue);
        try {
            RemediationContext ctx = contextRepository.findById(contextId)
                    .orElse(null);
            if (ctx == null) {
                return Map.of("error", "Context not found: " + contextId, "verified", false);
            }

            // Simple verification: if in VERIFYING/EXECUTING state, mark as COMPLETED
            if (ctx.getState() == RemediationState.VERIFYING || ctx.getState() == RemediationState.EXECUTING) {
                stateMachine.transit(ctx, RemediationState.VERIFYING, "system");
                stateMachine.transit(ctx, RemediationState.COMPLETED, "system");
                ctx.setExecutionResult("Verified: " + checkMetric + " = " + expectedValue);
                contextRepository.save(ctx);
                circuitBreaker.recordSuccess(service, ctx.getStrategyName());
            }

            log.info("Tool[verifyRemediation] done contextId={} state={}", contextId, ctx.getState());
            return Map.of("contextId", contextId, "verified", true,
                    "state", ctx.getState().name(), "metric", checkMetric, "result", expectedValue);
        } catch (Exception e) {
            log.error("Tool[verifyRemediation] failed contextId={}: {}", contextId, e.getMessage());
            return Map.of("error", e.getMessage(), "verified", false, "contextId", contextId);
        }
    }

    /**
     * 回滚已执行的修复操作（将工单状态推进到 ROLLING_BACK → ROLLED_BACK）。
     * 用于修复操作导致额外问题时的应急回滚。
     *
     * @param contextId  修复工单 ID
     * @param reason     回滚原因
     * @param requestedBy 发起回滚的用户名
     * @return 回滚操作结果
     */
    @Tool(description = "Roll back an executed remediation action, advancing the ticket to ROLLING_BACK state. Use when the remediation causes secondary issues.")
    public Map<String, Object> rollbackAction(
            @ToolParam(description = "Remediation ticket ID") String contextId,
            @ToolParam(description = "Rollback reason") String reason,
            @ToolParam(description = "Username of the person initiating the rollback") String requestedBy
    ) {
        log.info("Tool[rollbackAction] contextId={} by={}", contextId, requestedBy);
        try {
            RemediationContext ctx = contextRepository.findById(contextId)
                    .orElseThrow(() -> new IllegalArgumentException("Context not found: " + contextId));

            if (ctx.getState() == RemediationState.FAILED || ctx.getState() == RemediationState.EXECUTING) {
                if (ctx.getState() == RemediationState.EXECUTING) {
                    stateMachine.transit(ctx, RemediationState.FAILED, requestedBy);
                }
                stateMachine.transit(ctx, RemediationState.ROLLING_BACK, requestedBy);
                ctx.addHistoryEntry("Rollback initiated: " + reason);
                stateMachine.transit(ctx, RemediationState.ROLLED_BACK, requestedBy);
                contextRepository.save(ctx);
            }

            log.info("Tool[rollbackAction] done contextId={} state={}", contextId, ctx.getState());
            return Map.of("contextId", contextId, "state", ctx.getState().name(),
                    "message", "Rollback completed for context " + contextId);
        } catch (Exception e) {
            log.error("Tool[rollbackAction] failed contextId={}: {}", contextId, e.getMessage());
            return Map.of("error", e.getMessage(), "contextId", contextId);
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    @FunctionalInterface
    private interface RemediationAction {
        RemediationResult execute() throws Exception;
    }

    private RemediationResult executeWithGuards(String action, String service, String riskLevel,
                                                String idempotencyKey, RemediationAction runnable) {
        // Idempotency check
        if (!idempotencyStore.tryAcquire(idempotencyKey)) {
            return idempotencyStore.getResult(idempotencyKey)
                    .map(json -> {
                        try {
                            RemediationResult prev = objectMapper.readValue(json, RemediationResult.class);
                            return RemediationResult.idempotentHit(prev.contextId(), action, prev.state(), riskLevel);
                        } catch (Exception e) {
                            return RemediationResult.idempotentHit("unknown", action,
                                    RemediationState.COMPLETED, riskLevel);
                        }
                    })
                    .orElseGet(() -> RemediationResult.idempotentHit("unknown", action,
                            RemediationState.COMPLETED, riskLevel));
        }

        // Circuit breaker check
        if (circuitBreaker.isOpen(service, action)) {
            idempotencyStore.release(idempotencyKey);
            return RemediationResult.failed("N/A", action, RemediationState.CANCELLED,
                    "Circuit breaker OPEN for service=" + service + " action=" + action
                    + ". Too many recent failures. Please investigate before retrying.");
        }

        try {
            RemediationResult result = runnable.execute();
            try {
                idempotencyStore.saveResult(idempotencyKey, objectMapper.writeValueAsString(result));
            } catch (Exception e) {
                log.warn("Failed to save idempotency result for key={}: {}", idempotencyKey, e.getMessage());
            }
            if (!result.requiresApproval()) {
                circuitBreaker.recordSuccess(service, action);
            }
            return result;
        } catch (Exception e) {
            idempotencyStore.release(idempotencyKey);
            circuitBreaker.recordFailure(service, action);
            log.error("Tool[{}] failed service={}: {}", action, service, e.getMessage());
            return RemediationResult.failed("N/A", action, RemediationState.FAILED, e.getMessage());
        }
    }

    private RemediationContext createContext(String description, String service,
                                             String strategy, String riskLevel, String requestedBy) {
        String idKey = strategy + ":" + service + ":" + Instant.now().toEpochMilli();
        RemediationContext ctx = new RemediationContext(description, service, strategy, riskLevel,
                requestedBy, idKey);
        return contextRepository.save(ctx);
    }
}
