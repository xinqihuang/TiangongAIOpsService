package com.huawei.cloud.sre.remediation.statemachine;

import com.huawei.cloud.sre.remediation.repository.RemediationContext;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 自研轻量修复工单状态机。
 *
 * <p>基于 {@code EnumMap<State, Set<State>>} 定义合法转换表，
 * 每次成功转换后通过 Spring {@link ApplicationEventPublisher} 发布 {@link StateChangedEvent}。
 *
 * <p>设计原则：
 * <ul>
 *   <li>无注解、无 XML、无第三方依赖（纯 Java + Spring 事件）</li>
 *   <li>线程安全（转换表只读，状态存储在 Entity 中）</li>
 *   <li>易于测试（可不启动 Spring 直接实例化）</li>
 * </ul>
 */
@Component
public class RemediationStateMachine {

    private static final Logger log = LoggerFactory.getLogger(RemediationStateMachine.class);

    private final Map<RemediationState, Set<RemediationState>> transitions =
            new EnumMap<>(RemediationState.class);

    private final ApplicationEventPublisher eventPublisher;

    /**
     * @param eventPublisher Spring 事件发布器
     */
    public RemediationStateMachine(ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    /**
     * 初始化合法转换表。
     */
    @PostConstruct
    public void init() {
        transitions.put(RemediationState.INITIATED,
                EnumSet.of(RemediationState.STRATEGY_MATCHED, RemediationState.CANCELLED));

        transitions.put(RemediationState.STRATEGY_MATCHED,
                EnumSet.of(RemediationState.RISK_ASSESSED, RemediationState.CANCELLED));

        transitions.put(RemediationState.RISK_ASSESSED,
                EnumSet.of(RemediationState.PENDING_APPROVAL,
                        RemediationState.EXECUTING,      // 低风险直接执行
                        RemediationState.CANCELLED));

        transitions.put(RemediationState.PENDING_APPROVAL,
                EnumSet.of(RemediationState.APPROVED, RemediationState.REJECTED,
                        RemediationState.CANCELLED));

        transitions.put(RemediationState.APPROVED,
                EnumSet.of(RemediationState.EXECUTING, RemediationState.CANCELLED));

        transitions.put(RemediationState.EXECUTING,
                EnumSet.of(RemediationState.VERIFYING, RemediationState.FAILED,
                        RemediationState.CANCELLED));

        transitions.put(RemediationState.VERIFYING,
                EnumSet.of(RemediationState.COMPLETED, RemediationState.FAILED));

        transitions.put(RemediationState.FAILED,
                EnumSet.of(RemediationState.ROLLING_BACK, RemediationState.CANCELLED));

        transitions.put(RemediationState.ROLLING_BACK,
                EnumSet.of(RemediationState.ROLLED_BACK, RemediationState.FAILED));

        // Terminal states: COMPLETED, ROLLED_BACK, REJECTED, CANCELLED → empty sets
        transitions.put(RemediationState.COMPLETED, EnumSet.noneOf(RemediationState.class));
        transitions.put(RemediationState.ROLLED_BACK, EnumSet.noneOf(RemediationState.class));
        transitions.put(RemediationState.REJECTED, EnumSet.noneOf(RemediationState.class));
        transitions.put(RemediationState.CANCELLED, EnumSet.noneOf(RemediationState.class));
    }

    /**
     * 执行状态转换。
     *
     * @param context      修复上下文（状态存储于此）
     * @param target       目标状态
     * @param triggeredBy  触发者标识（用户名或 "system"）
     * @throws IllegalStateTransitionException 若转换不合法
     */
    public void transit(RemediationContext context, RemediationState target, String triggeredBy) {
        RemediationState current = context.getState();
        Set<RemediationState> allowed = transitions.getOrDefault(current, EnumSet.noneOf(RemediationState.class));

        if (!allowed.contains(target)) {
            log.warn("Illegal transition contextId={} from={} to={}", context.getContextId(), current, target);
            throw new IllegalStateTransitionException(current, target);
        }

        RemediationState from = current;
        context.setState(target);
        context.addHistoryEntry("State changed: %s → %s by %s".formatted(from, target, triggeredBy));
        log.info("State transition contextId={} from={} to={} by={}",
                context.getContextId(), from, target, triggeredBy);

        eventPublisher.publishEvent(new StateChangedEvent(
                context.getContextId(), from, target, triggeredBy, Instant.now()));
    }

    /**
     * 检查从当前状态到目标状态的转换是否合法（不执行转换）。
     *
     * @param current 当前状态
     * @param target  目标状态
     * @return true 表示转换合法
     */
    public boolean canTransit(RemediationState current, RemediationState target) {
        return transitions.getOrDefault(current, EnumSet.noneOf(RemediationState.class)).contains(target);
    }

    /**
     * 获取从当前状态出发的所有合法目标状态。
     *
     * @param current 当前状态
     * @return 合法目标状态集合
     */
    public Set<RemediationState> allowedTransitions(RemediationState current) {
        return Set.copyOf(transitions.getOrDefault(current, EnumSet.noneOf(RemediationState.class)));
    }
}
