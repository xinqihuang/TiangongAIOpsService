package com.huawei.cloud.sre.remediation.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * 修复操作专用熔断器。
 *
 * <p>独立于 Resilience4j 的轻量实现，专门针对修复操作的安全语义：
 * 同一目标服务的修复操作在 30 分钟内累计失败 3 次即熔断，
 * 熔断后阻止继续执行，避免重复操作加剧故障。
 *
 * <p>状态存储于 Redis：
 * <ul>
 *   <li>失败计数：{@code remediation:cb:failures:{service}:{action}}</li>
 *   <li>熔断标记：{@code remediation:cb:open:{service}:{action}}</li>
 * </ul>
 */
@Component
public class RemediationCircuitBreaker {

    private static final Logger log = LoggerFactory.getLogger(RemediationCircuitBreaker.class);
    private static final String CB_FAILURES_PREFIX = "remediation:cb:failures:";
    private static final String CB_OPEN_PREFIX = "remediation:cb:open:";

    private final StringRedisTemplate redisTemplate;
    private final int failureThreshold;
    private final Duration windowDuration;
    private final Duration openDuration;

    /**
     * @param redisTemplate    Redis 模板
     * @param failureThreshold 失败次数阈值（默认 3）
     * @param windowMinutes    统计窗口（分钟，默认 30）
     * @param openMinutes      熔断持续时间（分钟，默认 30）
     */
    public RemediationCircuitBreaker(
            StringRedisTemplate redisTemplate,
            @Value("${remediation.circuit-breaker.failure-threshold:3}") int failureThreshold,
            @Value("${remediation.circuit-breaker.window-minutes:30}") int windowMinutes,
            @Value("${remediation.circuit-breaker.open-minutes:30}") int openMinutes
    ) {
        this.redisTemplate = redisTemplate;
        this.failureThreshold = failureThreshold;
        this.windowDuration = Duration.ofMinutes(windowMinutes);
        this.openDuration = Duration.ofMinutes(openMinutes);
    }

    /**
     * 检查指定操作是否被熔断。
     *
     * @param service 目标服务名
     * @param action  操作名称（如 restartPod）
     * @return true 表示熔断打开，禁止执行
     */
    public boolean isOpen(String service, String action) {
        String openKey = CB_OPEN_PREFIX + service + ":" + action;
        boolean open = Boolean.TRUE.equals(redisTemplate.hasKey(openKey));
        if (open) {
            log.warn("Circuit breaker OPEN for service={} action={}", service, action);
        }
        return open;
    }

    /**
     * 记录一次执行失败，若达到阈值则触发熔断。
     *
     * @param service 目标服务名
     * @param action  操作名称
     */
    public void recordFailure(String service, String action) {
        String failKey = CB_FAILURES_PREFIX + service + ":" + action;
        Long count = redisTemplate.opsForValue().increment(failKey);
        if (count == null) count = 1L;

        if (count == 1) {
            redisTemplate.expire(failKey, windowDuration);
        }

        log.warn("Remediation failure recorded service={} action={} count={}/{}",
                service, action, count, failureThreshold);

        if (count >= failureThreshold) {
            String openKey = CB_OPEN_PREFIX + service + ":" + action;
            redisTemplate.opsForValue().set(openKey,
                    "opened_at:" + Instant.now().toString(), openDuration);
            log.error("Circuit breaker OPENED for service={} action={} after {} failures",
                    service, action, count);
        }
    }

    /**
     * 记录一次执行成功，重置失败计数。
     *
     * @param service 目标服务名
     * @param action  操作名称
     */
    public void recordSuccess(String service, String action) {
        String failKey = CB_FAILURES_PREFIX + service + ":" + action;
        redisTemplate.delete(failKey);
        log.debug("Circuit breaker success recorded service={} action={}", service, action);
    }

    /**
     * 手动关闭熔断（运维介入）。
     *
     * @param service 目标服务名
     * @param action  操作名称
     */
    public void reset(String service, String action) {
        redisTemplate.delete(CB_OPEN_PREFIX + service + ":" + action);
        redisTemplate.delete(CB_FAILURES_PREFIX + service + ":" + action);
        log.info("Circuit breaker manually reset service={} action={}", service, action);
    }

    /**
     * 获取当前失败计数。
     *
     * @param service 目标服务名
     * @param action  操作名称
     * @return 失败次数
     */
    public int getFailureCount(String service, String action) {
        String failKey = CB_FAILURES_PREFIX + service + ":" + action;
        String val = redisTemplate.opsForValue().get(failKey);
        if (val == null) return 0;
        try { return Integer.parseInt(val); } catch (NumberFormatException e) { return 0; }
    }
}
