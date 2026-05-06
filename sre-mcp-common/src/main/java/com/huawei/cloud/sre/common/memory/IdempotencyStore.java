package com.huawei.cloud.sre.common.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/**
 * 基于 Redis SETNX 的幂等键存储。
 *
 * <p>用于防止 MCP Tool 因网络重试、Client 重复调用等原因被重复执行。
 * 特别适用于有副作用的操作（重启 Pod、主备切换等）。
 *
 * <p>Key 格式：{@code mcp:idempotency:{idempotencyKey}}
 */
@Component
public class IdempotencyStore {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyStore.class);
    private static final String KEY_PREFIX = "mcp:idempotency:";
    private static final Duration DEFAULT_TTL = Duration.ofHours(24);

    private final StringRedisTemplate redisTemplate;

    /**
     * @param redisTemplate Spring Redis 模板
     */
    public IdempotencyStore(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 尝试占用幂等键（原子 SETNX）。
     *
     * <p>若占用成功（首次调用），返回 true，调用方应执行实际操作并将结果存入 {@link #saveResult}。
     * 若占用失败（已有相同 key），返回 false，调用方应直接返回缓存的结果。
     *
     * @param idempotencyKey 幂等键，由调用方生成（如 "restart-pod-{podName}-{timestamp-bucket}"）
     * @return true 表示首次请求，false 表示重复请求
     */
    public boolean tryAcquire(String idempotencyKey) {
        String lockKey = KEY_PREFIX + idempotencyKey + ":lock";
        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(lockKey, "1", DEFAULT_TTL);
        boolean result = Boolean.TRUE.equals(acquired);
        log.debug("IdempotencyStore tryAcquire key={} acquired={}", idempotencyKey, result);
        return result;
    }

    /**
     * 尝试占用幂等键，自定义 TTL。
     *
     * @param idempotencyKey 幂等键
     * @param ttl            幂等键有效期
     * @return true 表示首次请求
     */
    public boolean tryAcquire(String idempotencyKey, Duration ttl) {
        String lockKey = KEY_PREFIX + idempotencyKey + ":lock";
        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(lockKey, "1", ttl);
        return Boolean.TRUE.equals(acquired);
    }

    /**
     * 保存操作执行结果（JSON 字符串），供重复请求直接返回。
     *
     * @param idempotencyKey 幂等键
     * @param result         操作结果的 JSON 序列化字符串
     */
    public void saveResult(String idempotencyKey, String result) {
        String resultKey = KEY_PREFIX + idempotencyKey + ":result";
        redisTemplate.opsForValue().set(resultKey, result, DEFAULT_TTL);
        log.debug("IdempotencyStore saveResult key={}", idempotencyKey);
    }

    /**
     * 获取已存储的操作结果。
     *
     * @param idempotencyKey 幂等键
     * @return 操作结果 JSON，若不存在返回 empty
     */
    public Optional<String> getResult(String idempotencyKey) {
        String resultKey = KEY_PREFIX + idempotencyKey + ":result";
        String value = redisTemplate.opsForValue().get(resultKey);
        return Optional.ofNullable(value);
    }

    /**
     * 释放幂等锁（若操作失败需回滚占用，避免死锁）。
     *
     * @param idempotencyKey 幂等键
     */
    public void release(String idempotencyKey) {
        String lockKey = KEY_PREFIX + idempotencyKey + ":lock";
        redisTemplate.delete(lockKey);
        log.debug("IdempotencyStore release key={}", idempotencyKey);
    }
}
