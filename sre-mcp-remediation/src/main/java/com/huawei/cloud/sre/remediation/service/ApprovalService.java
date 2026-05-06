package com.huawei.cloud.sre.remediation.service;

import com.huawei.cloud.sre.remediation.repository.RemediationContext;
import com.huawei.cloud.sre.remediation.repository.RemediationContextRepository;
import com.huawei.cloud.sre.remediation.statemachine.RemediationState;
import com.huawei.cloud.sre.remediation.statemachine.RemediationStateMachine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * 审批工作流服务。
 *
 * <p>管理修复工单的审批流程：
 * <ul>
 *   <li>LOW 风险：无需审批，直接 → EXECUTING</li>
 *   <li>MEDIUM 风险：单人审批，通过 → APPROVED → EXECUTING</li>
 *   <li>HIGH 风险：双人审批，两人都通过 → APPROVED → EXECUTING</li>
 * </ul>
 *
 * <p>审批令牌存储在 Redis（Key：{@code remediation:approval:{contextId}:{approver}}），
 * TTL 默认 4 小时（超时自动取消）。
 */
@Service
public class ApprovalService {

    private static final Logger log = LoggerFactory.getLogger(ApprovalService.class);
    private static final String APPROVAL_KEY_PREFIX = "remediation:approval:";

    private final RemediationContextRepository contextRepository;
    private final RemediationStateMachine stateMachine;
    private final StringRedisTemplate redisTemplate;
    private final Duration approvalTtl;

    /**
     * @param contextRepository 工单仓库
     * @param stateMachine      状态机
     * @param redisTemplate     Redis 模板
     * @param approvalTtlHours  审批超时时间（小时，默认 4）
     */
    public ApprovalService(
            RemediationContextRepository contextRepository,
            RemediationStateMachine stateMachine,
            StringRedisTemplate redisTemplate,
            @Value("${remediation.approval.ttl-hours:4}") int approvalTtlHours
    ) {
        this.contextRepository = contextRepository;
        this.stateMachine = stateMachine;
        this.redisTemplate = redisTemplate;
        this.approvalTtl = Duration.ofHours(approvalTtlHours);
    }

    /**
     * 根据风险级别路由修复工单：
     * <ul>
     *   <li>LOW → 直接转 EXECUTING（无需审批）</li>
     *   <li>MEDIUM / HIGH → 转 PENDING_APPROVAL，设置所需审批人</li>
     * </ul>
     *
     * @param context     修复工单
     * @param approvers   预设审批人列表（MEDIUM: 1人；HIGH: 2人）
     */
    public void routeByRisk(RemediationContext context, List<String> approvers) {
        stateMachine.transit(context, RemediationState.RISK_ASSESSED, "system");

        if ("LOW".equalsIgnoreCase(context.getRiskLevel())) {
            log.info("Low risk action, auto-approving contextId={}", context.getContextId());
            stateMachine.transit(context, RemediationState.EXECUTING, "system");
        } else {
            context.setApprovers(String.join(",", approvers));
            stateMachine.transit(context, RemediationState.PENDING_APPROVAL, "system");
            log.info("Routing to approval contextId={} riskLevel={} approvers={}",
                    context.getContextId(), context.getRiskLevel(), approvers);
        }
        contextRepository.save(context);
    }

    /**
     * 记录审批人的批准意见。当所有必需审批人都批准时，自动推进到 APPROVED。
     *
     * @param contextId  工单 ID
     * @param approver   审批人标识
     * @param note       审批意见
     * @return 审批后工单状态
     */
    public RemediationState approve(String contextId, String approver, String note) {
        RemediationContext context = contextRepository.findById(contextId)
                .orElseThrow(() -> new IllegalArgumentException("Context not found: " + contextId));

        if (context.getState() != RemediationState.PENDING_APPROVAL) {
            throw new IllegalStateException("Context not in PENDING_APPROVAL state: " + contextId);
        }

        String approvalKey = APPROVAL_KEY_PREFIX + contextId + ":" + approver;
        redisTemplate.opsForValue().set(approvalKey, "approved:" + note, approvalTtl);
        log.info("Approval recorded contextId={} approver={}", contextId, approver);

        if (allApproved(context)) {
            context.setApprovalNote(note);
            stateMachine.transit(context, RemediationState.APPROVED, approver);
            contextRepository.save(context);
            log.info("All approvals received contextId={}, transitioning to APPROVED", contextId);
        }

        return context.getState();
    }

    /**
     * 拒绝修复工单。
     *
     * @param contextId 工单 ID
     * @param approver  拒绝人
     * @param reason    拒绝原因
     * @return 拒绝后工单状态
     */
    public RemediationState reject(String contextId, String approver, String reason) {
        RemediationContext context = contextRepository.findById(contextId)
                .orElseThrow(() -> new IllegalArgumentException("Context not found: " + contextId));

        context.setApprovalNote("Rejected by " + approver + ": " + reason);
        stateMachine.transit(context, RemediationState.REJECTED, approver);
        contextRepository.save(context);
        log.info("Context rejected contextId={} by={} reason={}", contextId, approver, reason);
        return context.getState();
    }

    /**
     * 检查工单是否已经获得所有必需审批。
     *
     * @param context 工单
     * @return true 表示全部审批通过
     */
    public boolean allApproved(RemediationContext context) {
        if (context.getApprovers() == null || context.getApprovers().isEmpty()) {
            return false;
        }
        List<String> required = Arrays.asList(context.getApprovers().split(","));
        for (String approver : required) {
            String key = APPROVAL_KEY_PREFIX + context.getContextId() + ":" + approver.trim();
            if (!Boolean.TRUE.equals(redisTemplate.hasKey(key))) {
                return false;
            }
        }
        return true;
    }
}
